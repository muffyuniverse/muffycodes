package com.example.notes

import com.example.notes.browser.Addresses
import org.json.JSONObject

/**
 * **A server the person wrote an article about, and the one thing the app reads out of it.**
 *
 * The operator's design is that `mcp/` holds *"mcp articles to connect to"* — an article, not a config file,
 * for the same reason `project_types/` was prose: *"a config file would have to be a schema, and a schema is a
 * thing the app has to validate and migrate."* A person can read an article, edit it, and add one without the
 * app changing.
 *
 * ## The token is never in the article
 *
 * The article names a secret (`token: linear`) and [Credentials.secret] resolves it. An article is inside a
 * project, and a project syncs to GitHub — so *"an article with a token in it is a leaked token in a
 * repository, which is the one failure mode of this whole feature that cannot be undone."* That sentence is
 * the reason this class has no `token` field: it holds a **name**, and the only thing that can turn a name
 * into a token is the store, at the moment of a request.
 *
 * ## What the fence looks like
 *
 * ````markdown
 * # Linear
 *
 * Issues and projects. Read-only is enough for most work.
 *
 * ```mcp
 * url: https://mcp.linear.app/mcp
 * auth: bearer
 * token: linear
 * ```
 * ````
 *
 * Prose above the fence is for the person; the fence is for the app. Anything the app does not recognise is
 * kept in [extra] rather than refused, so an article written for a later version of this class still loads.
 */
data class McpServer(
    /** The article's own name, which is what `mcp {"server":"…"}` names. */
    val name: String,
    /** Where the article is, account-relative — so a refusal can say where to go and look. */
    val article: String,
    val url: String,
    /** How to authenticate: [AUTH_NONE] or [AUTH_BEARER]. */
    val auth: String,
    /** **The name of a secret, never a secret.** Null when [auth] is [AUTH_NONE]. */
    val tokenName: String?,
    /** Keys the app does not know. Kept, so a newer article is not silently a broken one. */
    val extra: Map<String, String> = emptyMap(),
) {

    /** The value to put in an `Authorization` header, or null for none. */
    fun authorization(credentials: Credentials): String? = when (auth) {
        AUTH_BEARER -> {
            val name = tokenName ?: return null
            // **The destination is the article's own URL**, so the vault can refuse a secret that was
            // stored for a different server. An article in git is editable by anyone with the
            // repository, so "which secret" must not be the only question asked.
            val token = credentials.secret(name, Secrets.hostOf(url)) ?: return null
            "Bearer $token"
        }
        else -> null
    }

    /** Why this server cannot be called, or null when it can. */
    fun missing(credentials: Credentials): String? = when {
        auth == AUTH_BEARER && tokenName == null ->
            "$article says `auth: bearer` but does not name a secret. Add `token: <name>`."
        auth == AUTH_BEARER && credentials.secret(tokenName!!, Secrets.hostOf(url)) == null ->
            "The secret `$tokenName` that $article names is not on this phone for " +
                "${Secrets.hostOf(url).ifEmpty { "this server" }}. " +
                "Add it in settings, and it is never written into the article."
        auth != AUTH_NONE && auth != AUTH_BEARER ->
            "$article asks for `auth: $auth`, and only `none` and `bearer` are understood. " +
                "OAuth needs a browser round trip the app does not do yet."
        else -> null
    }

    companion object {
        const val AUTH_NONE = "none"
        const val AUTH_BEARER = "bearer"

        /** The fence's own tag, in the one place it is written. */
        const val FENCE = "mcp"

        /**
         * Reads one article, or says why it is not one.
         *
         * **The name comes from the article's own heading**, falling back to the file name — because a person
         * writing `# Linear` expects to call `linear`, and a person who wrote no heading still expects the
         * file to be reachable by the name they gave it.
         */
        fun parse(text: String, name: String, article: String): Result<McpServer> {
            val fields = fence(text)
                ?: return Result.failure(
                    IllegalStateException(
                        "$article has no ```$FENCE block, so there is nothing in it the app can read. " +
                            "A server article needs a fenced block with `url:` in it.",
                    ),
                )
            val url = fields["url"]?.trim()?.takeIf { it.isNotEmpty() }
                ?: return Result.failure(
                    IllegalStateException("$article has a ```$FENCE block with no `url:` in it."),
                )
            val auth = fields["auth"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: AUTH_NONE
            val token = fields["token"]?.trim()?.takeIf { it.isNotEmpty() }
            val extra = fields.filterKeys { it != "url" && it != "auth" && it != "token" }
            return Result.success(
                McpServer(
                    name = name,
                    article = article,
                    url = url,
                    auth = auth,
                    tokenName = token,
                    extra = extra,
                ),
            )
        }

        /**
         * The `key: value` lines inside the first ```mcp fence.
         *
         * **Only the first**, because a second fence is a second example rather than a second server — and a
         * person who pastes two connection examples into one article means the one at the top.
         */
        fun fence(text: String): Map<String, String>? {
            var inside = false
            val out = LinkedHashMap<String, String>()
            for (line in text.split("\n")) {
                val trimmed = line.trim()
                if (!inside) {
                    if (trimmed.equals("```$FENCE", ignoreCase = true)) inside = true
                    continue
                }
                if (trimmed.startsWith("```")) return out.ifEmpty { null }
                if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
                val at = trimmed.indexOf(':')
                if (at <= 0) continue
                val key = trimmed.substring(0, at).trim().lowercase()
                val value = trimmed.substring(at + 1).trim()
                // **A quoted value is unquoted**, because a URL with a colon in it (`https://…`) is the
                // ordinary case and a person may still write `url: "https://…"`.
                out[key] = value.trim('"', '\'')
            }
            // An unterminated fence still answers, because the article is prose and a person may be mid-edit.
            return out.ifEmpty { null }
        }
    }
}

/**
 * **Talking to an MCP server, over streamable HTTP.**
 *
 * An interface for the same reason [com.example.notes.browser.Browser] and [Fetcher] are: the
 * suite never opens a socket. Every test of the tool is a fake that answers JSON it already knows, and the
 * real client is exercised by using the app.
 *
 * ## One tool, not one tool per remote tool
 *
 * The operator's design note is exact about why `mcp` is a single command with a sub-selector rather than
 * registering `list_issues` in the tool list: **the tool list is part of every request's cache prefix**, so a
 * server going down would rewrite the prefix of every conversation that mentioned it. The server's own
 * catalogue is therefore something the agent reads on demand, through this.
 *
 * ## No deadline of its own
 *
 * The operator's answer to "what happens when a server hangs" was *"shouldn't we just use `background` for
 * that — we have a door for such results, don't duplicate it."* So there is no per-server timeout in the
 * article and no second deadline class: `background {"tool":"mcp", …}` bounds the work with the operator's own
 * number, and `Deadline` is checked between steps here. The socket timeouts below are HTTP hygiene — a hung
 * server must not hold a thread for ever — not a second scheduling mechanism.
 */
interface Mcp {

    /** What a server offers, as its own catalogue describes it. */
    data class Tool(val name: String, val description: String, val schema: String?)

    /**
     * One call's answer: the text, and whether the server said it failed.
     *
     * **[failed] is separate from the text**, because MCP puts a tool's own failure in `result.isError`
     * rather than in a JSON-RPC error — so a client reading only `error` would report a failed call as a
     * successful one with odd text.
     */
    data class Answer(val text: String, val failed: Boolean) {

        companion object {

            /**
             * The answer inside a `tools/call` result.
             *
             * **`content` is a list of parts, and only some of them are text.** A part that is not is
             * *named* rather than dropped, because a result quietly missing a piece lies about its own
             * contents. An image's base64 is deliberately not rendered: it is the whole context
             * spent on a picture.
             */
            fun read(result: JSONObject): Answer {
                val parts = result.optJSONArray("content")
                val text = buildString {
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.optJSONObject(i) ?: continue
                            when (part.optString("type")) {
                                "text" -> {
                                    if (isNotEmpty()) append("\n")
                                    append(part.optString("text"))
                                }
                                "resource" -> {
                                    val inner = part.optJSONObject("resource")
                                    val body = inner?.optString("text")?.takeIf { it.isNotEmpty() }
                                    if (body != null) {
                                        if (isNotEmpty()) append("\n")
                                        append(body)
                                    }
                                }
                                else -> {
                                    if (isNotEmpty()) append("\n")
                                    append("[${part.optString("type").ifEmpty { "unknown" }} part, not text]")
                                }
                            }
                        }
                    }
                }
                return Answer(
                    text = text.ifEmpty { result.toString() },
                    failed = result.optBoolean("isError", false),
                )
            }
        }
    }

    /** The tools [server] offers, or a failure whose message is the reason. */
    fun tools(server: McpServer, credentials: Credentials, deadline: Deadline): Result<List<Tool>>

    /** Calls [tool] on [server] with [args], or a failure whose message is the reason. */
    fun call(
        server: McpServer,
        credentials: Credentials,
        tool: String,
        args: JSONObject,
        deadline: Deadline,
    ): Result<Answer>

    companion object {

        /** No MCP, for a tool set built without one — and every test that is not about this. */
        val NONE: Mcp = object : Mcp {
            override fun tools(
                server: McpServer,
                credentials: Credentials,
                deadline: Deadline,
            ): Result<List<Mcp.Tool>> = Result.failure(
                IllegalStateException("This app was not given a way to reach MCP servers."),
            )

            override fun call(
                server: McpServer,
                credentials: Credentials,
                tool: String,
                args: JSONObject,
                deadline: Deadline,
            ): Result<Mcp.Answer> = Result.failure(
                IllegalStateException("This app was not given a way to reach MCP servers."),
            )
        }

        /**
         * The real one: JSON-RPC 2.0 over HTTP, one POST per message.
         *
         * **A fresh session per call, deliberately.** MCP's streamable transport allows a server to keep state
         * behind `Mcp-Session-Id`, and this does not ask it to: a phone that sleeps, loses the network and
         * comes back has no session, and a client that depended on one would fail on the first call after a
         * doze. Every request is therefore self-contained — `initialize`, then the one call, in one exchange.
         *
         * That costs one extra round trip per tool call and buys the only property that matters here: a call
         * either works or it does not, with nothing to resume.
         */
        fun http(connectMs: Int = 15_000, readMs: Int = 120_000): Mcp = HttpMcp(connectMs, readMs)
    }
}

/**
 * The transport, kept out of [Mcp]'s interface so a test can be a fake with no HTTP in it at all.
 *
 * ## What this deliberately does not do
 *
 * **OAuth.** Real servers (Linear, GitHub's) use OAuth 2.1 with dynamic client registration, which needs a
 * browser round trip, a callback URL and refresh. On a phone the person can paste a personal access token,
 * and that is the case this serves. `auth:` in the article leaves room to add `oauth` without breaking an
 * existing article.
 *
 * **Streaming responses.** `text/event-stream` is accepted and read to the end, because a server may answer in
 * it — but nothing here reacts to a partial answer. A tool call is one request and one answer.
 */
private class HttpMcp(private val connectMs: Int, private val readMs: Int) : Mcp {

    override fun tools(server: McpServer, credentials: Credentials, deadline: Deadline): Result<List<Mcp.Tool>> {
        val answer = exchange(server, credentials, deadline, method = "tools/list", params = null)
            .getOrElse { return Result.failure(it) }
        val list = answer.optJSONObject("result")?.optJSONArray("tools") ?: return Result.success(emptyList())
        val out = mutableListOf<Mcp.Tool>()
        for (i in 0 until list.length()) {
            val one = list.optJSONObject(i) ?: continue
            val name = one.optString("name").trim()
            if (name.isEmpty()) continue
            out.add(
                Mcp.Tool(
                    name = name,
                    description = one.optString("description").trim(),
                    schema = one.optJSONObject("inputSchema")?.toString(),
                ),
            )
        }
        return Result.success(out)
    }

    override fun call(
        server: McpServer,
        credentials: Credentials,
        tool: String,
        args: JSONObject,
        deadline: Deadline,
    ): Result<Mcp.Answer> {
        val params = JSONObject()
            .put("name", tool)
            .put("arguments", args)
        val answer = exchange(server, credentials, deadline, method = "tools/call", params = params)
            .getOrElse { return Result.failure(it) }
        val result = answer.optJSONObject("result")
            ?: return Result.success(Mcp.Answer(answer.toString(), failed = true))
        return Result.success(Mcp.Answer.read(result))
    }

    /**
     * One JSON-RPC exchange: `initialize`, the `notifications/initialized` a server expects, then [method].
     *
     * **The three messages are separate POSTs** rather than one batch, because the transport allows a server
     * to answer each with its own `Mcp-Session-Id` and a batch has nowhere to put it. The session id from the
     * first answer is echoed on the rest, which is the part of the spec a stateless client still has to
     * honour.
     */
    private fun exchange(
        server: McpServer,
        credentials: Credentials,
        deadline: Deadline,
        method: String,
        params: JSONObject?,
    ): Result<JSONObject> {
        Addresses.problem(server.url)?.let { return Result.failure(IllegalStateException(it)) }
        val auth = server.authorization(credentials)
            ?: server.missing(credentials)?.let { return Result.failure(IllegalStateException(it)) }
        var session: String? = null
        return runCatching {
            val init = post(
                server.url, session, auth, deadline,
                JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("id", 1)
                    .put("method", "initialize")
                    .put(
                        "params",
                        JSONObject()
                            .put("protocolVersion", PROTOCOL)
                            .put(
                                "capabilities",
                                JSONObject().put("tools", JSONObject()),
                            )
                            .put("clientInfo", JSONObject().put("name", "muffycodes").put("version", "1")),
                    ),
            ).getOrThrow()
            session = init.first
            init.second
            // **The notification has no id and expects no answer**, and a server that answers anyway is not an
            // error — the body is read and dropped.
            post(
                server.url, session, auth, deadline,
                JSONObject()
                    .put("jsonrpc", "2.0")
                    .put("method", "notifications/initialized"),
            ).getOrThrow()
            val request = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", 2)
                .put("method", method)
            if (params != null) request.put("params", params)
            post(server.url, session, auth, deadline, request).getOrThrow().second
        }
    }

    /** One POST, answering the session id the server sent and the JSON it answered with. */
    private fun post(
        url: String,
        session: String?,
        auth: String?,
        deadline: Deadline,
        body: JSONObject,
    ): Result<Pair<String?, JSONObject>> {
        deadline.check("mcp")
        return runCatching {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = connectMs
                connection.readTimeout = readMs
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json, text/event-stream")
                if (auth != null) connection.setRequestProperty("Authorization", auth)
                if (session != null) connection.setRequestProperty("Mcp-Session-Id", session)
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                if (code !in 200..299) {
                    // **The body is included**, because an MCP server's error body is the one place it says
                    // which header or argument was wrong, and a bare "HTTP 400" tells the agent nothing.
                    throw IllegalStateException(
                        "The server answered HTTP $code" +
                            (if (text.isBlank()) "" else ": ${text.take(400)}"),
                    )
                }
                val parsed = json(text)
                val sid = connection.getHeaderField("Mcp-Session-Id")
                sid to parsed
            } finally {
                connection.disconnect()
            }
        }
    }

    /**
     * The JSON in [text], whether it came as `application/json` or as one `data:` line of an event stream.
     *
     * **The `id` is checked rather than trusted**: a server may answer a notification, and taking the first
     * object that parses would then return the wrong message — the same class of bug as an answer attached
     * to the question before it.
     */
    private fun json(text: String): JSONObject {
        val body = text.trim()
        if (body.startsWith("{")) return JSONObject(body)
        for (line in body.split("\n")) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("data:")) continue
            val payload = trimmed.removePrefix("data:").trim()
            if (!payload.startsWith("{")) continue
            val one = runCatching { JSONObject(payload) }.getOrNull() ?: continue
            if (one.has("result") || one.has("error")) return one
        }
        throw IllegalStateException(
            "The server answered something that is not JSON: ${body.take(200)}",
        )
    }

    private companion object {
        /** The version this speaks. A server that answers another is still read, because the tool shapes agree. */
        const val PROTOCOL = "2025-06-18"
    }
}
