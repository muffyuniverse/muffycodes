package com.example.notes

import java.io.File

/**
 * **A request written as a document, the way a person writes one in a `.http` file.**
 *
 * ```
 * POST https://api.github.com/repos/owner/name/issues
 * Authorization: Bearer {{secret:github}}
 * Content-Type: application/json
 * X-GitHub-Api-Version: 2022-11-28
 *
 * {"title": "{{file:notes/title.txt}}", "body": "{{file:notes/body.md}}"}
 * ```
 *
 * The first line is the method and the address; every line after it until a blank one is a header; the
 * rest is the body, verbatim. That is the shape the VS Code REST client uses and the shape a person
 * already knows, which is the whole reason for it: *one argument, the request itself*.
 *
 * ### The three things a document has to get right
 *
 * 1. **The verb may be left out.** `https://example.com` is a `GET` — nobody writes `GET` in a
 *    browser bar, and refusing that would be pedantry at the door.
 * 2. **A `#` or `//` line at the start is a comment**, so a file can explain itself. Only *before* the
 *    first line: inside a body, `//` is two slashes somebody meant to send.
 * 3. **The body is not trimmed.** A trailing newline in a JSON body is harmless and a trailing newline
 *    in a shell script being POSTed is data.
 *
 * ### What it refuses, and why each refusal is a sentence
 *
 * A header name that is not a token, a document with no address, a `{{…}}` name that expands to
 * nothing — all refused in words rather than sent half-formed. An API's answer to a malformed request
 * is a 400 with no explanation of which line was wrong; this app knows the line.
 */
data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    val body: ByteArray?,
) {

    /**
     * The same request with every `{{…}}` replaced.
     *
     * **The address is expanded too**, and that is deliberate: a host may be written as a file's
     * contents when a person keeps several, and the vault's binding is applied by the caller against
     * the *expanded* address — which is the one that will actually be reached. A secret may not be used
     * here (see [Expand]), so expanding the address cannot move the destination to wherever a vault row
     * points.
     */
    fun expand(host: String, names: Expand.Names): Result<HttpRequest> {
        val urlOut = Expand.of(url, host, names).getOrElse { return Result.failure(it) }
        val headersOut = LinkedHashMap<String, String>()
        for ((name, value) in headers) {
            val said = Expand.of(value, host, names).getOrElse { return Result.failure(it) }
            // **The name as well as the value**, because `X-{{file:header-name}}` is how a person
            // keeps a header set in one place — and a name is checked by the same rule.
            val nameOut = Expand.of(name, host, names).getOrElse { return Result.failure(it) }
            headersOut[nameOut] = said
        }
        val bodyOut = body?.let { bytes ->
            // **A body is only expanded when it is text.** A picture being uploaded is bytes, and
            // running a text expander over them would either corrupt them or refuse a document for
            // containing `{{` by accident. The check is the app's own: a body that is not valid text
            // in its own charset is sent as it is.
            if (looksBinary(bytes)) {
                bytes
            } else {
                Expand.of(String(bytes, Charsets.UTF_8), host, names).getOrElse {
                    return Result.failure(it)
                }.toByteArray(Charsets.UTF_8)
            }
        }
        return Result.success(copy(url = urlOut, headers = headersOut, body = bodyOut))
    }

    companion object {

        /**
         * Headers that may not be written by hand, because writing one is what the vault is for.
         *
         * **The rule that makes `{{secret:…}}` mean anything.** If a header could carry a literal
         * token, the model would put one in the conversation and in the log and the store would be
         * decorative. These four are the ways a credential travels.
         */
        val SECRET_HEADERS = setOf("authorization", "cookie", "proxy-authorization", "x-api-key")

        /**
         * The verbs this app will send.
         *
         * **A closed set, because `HttpURLConnection` throws for anything else** and its message names
         * no alternative — so an unknown verb is refused here, where the refusal can list the real
         * ones. `PATCH` is in the list because it is how a GitHub file is edited, and `HEAD` because
         * it is how a caller asks whether a thing exists without paying for the body.
         */
        val METHODS: List<String> = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")

        /**
         * [text] read as a request.
         *
         * A one-line document is the common case and is why the verb may be left out: a `.http` file
         * with one address in it is how a person tests that a server is up.
         */
        fun parse(text: String): Result<HttpRequest> {
            val lines = text.replace("\r\n", "\n").lines()
            var at = 0
            // **Leading comments and blank lines**, so a file can say what it is for. `#` is a
            // comment in every language a person writes config in, and `//` is what this app's own
            // request forms are written in — both are accepted, and neither survives into the
            // request.
            while (at < lines.size) {
                val line = lines[at].trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                    at++
                } else {
                    break
                }
            }
            if (at >= lines.size) {
                return Result.failure(
                    IllegalArgumentException(
                        "This request has no address in it. The first line is the method and the " +
                            "address, like `GET https://api.github.com/user`.",
                    ),
                )
            }

            // ---- the first line: the method and the address ----
            val first = lines[at].trim()
            at++
            val space = first.indexOf(' ')
            val (method, url) = when {
                space < 0 -> "GET" to first
                // **A verb only when the first word is one.** `https://example.com/a b` is an address
                // with a space in it — which is wrong, but it is wrong in a way the address check can
                // say better than a guess at the verb.
                first.substring(0, space).uppercase() in METHODS -> first.substring(0, space).uppercase() to first.substring(space + 1).trim()
                // **A word where a verb goes is a verb, even when it is the wrong one.** `TRACE
                // https://…` read as an address would be refused as "not an address", which sends a
                // model looking for a typo in a URL that is fine. A candidate is a run of letters with
                // no address punctuation in it, so `https://example.com/a b` stays an address.
                isVerbLike(first.substring(0, space)) -> first.substring(0, space).uppercase() to ""
                else -> "GET" to first
            }
            if (url.isEmpty()) {
                return Result.failure(
                    IllegalArgumentException("This request has a method and no address: `$first`."),
                )
            }
            if (method !in METHODS) {
                return Result.failure(
                    IllegalArgumentException(
                        "\"$method\" is not a method this app can send. Use one of: " +
                            METHODS.joinToString(", ") + ".",
                    ),
                )
            }

            // ---- the headers, up to the blank line ----
            val headers = LinkedHashMap<String, String>()
            while (at < lines.size && lines[at].isNotBlank()) {
                val line = lines[at]
                at++
                val colon = line.indexOf(':')
                if (colon <= 0) {
                    return Result.failure(
                        IllegalArgumentException(
                            "\"${line.trim().take(60)}\" is not a header: a header is a name, a " +
                                "colon, and a value. Put a blank line before the body.",
                        ),
                    )
                }
                val name = line.substring(0, colon).trim()
                val value = line.substring(colon + 1).trim()
                // **A secret header cannot be written by hand.** A token typed into a document is a
                // token in the conversation, the log and the trace; the vault attaches it by name.
                if (name.lowercase() in SECRET_HEADERS) {
                    return Result.failure(
                        IllegalArgumentException(
                            "`$name` cannot be written by hand. A token written into a request is a " +
                                "token in the conversation and in the log; write " +
                                "`$name: Bearer {{secret:<name>}}` and the vault supplies it.",
                        ),
                    )
                }
                headers[name] = value
            }

            // ---- the body, verbatim, after the blank line ----
            // **Not trimmed.** The blank line is consumed, and everything after it is the body as the
            // person wrote it — a trailing newline in a script being POSTed is data.
            val body = if (at < lines.size) {
                // **`at + 1`, because `at` is still on the blank line.** The header loop stops at it
                // rather than consuming it — a body that began with its separator's own newline would
                // arrive at the server with a leading blank line, which a JSON parser tolerates and a
                // script does not.
                lines.drop(at + 1).joinToString("\n").toByteArray(Charsets.UTF_8)
            } else {
                null
            }
            if (body != null && (method == "GET" || method == "HEAD")) {
                return Result.failure(
                    IllegalArgumentException(
                        "A $method cannot carry a body. To send something, use POST or PUT — or put " +
                            "the values in the query string.",
                    ),
                )
            }
            return Result.success(HttpRequest(method, url, headers, body))
        }

        /**
         * True when [value] is a vault placeholder, optionally behind a scheme word.
         *
         * **The whole rule for a credential header**, and it is deliberately narrow: strip every
         * `{{secret:…}}` out and what is left may be `Bearer`, `Basic`, `Token`, or nothing. Anything
         * else is a value somebody typed, and a value somebody typed is a value in the log.
         */
        fun onlyPlaceholder(value: String): Boolean {
            val mentions = Expand.mentions(value).filter { it.kind == Expand.Mention.Kind.SECRET }
            if (mentions.isEmpty()) return false
            var rest = value
            for (one in mentions) rest = rest.replace(one.whole, "")
            return rest.trim().lowercase() in setOf("", "bearer", "basic", "token", "bot")
        }

        /** True when [word] looks like it belongs where a verb goes rather than being an address. */
        fun isVerbLike(word: String): Boolean =
            word.isNotEmpty() && word.all { it.isLetter() } && word.length <= 12

        /** The names a document mentions, for a screen that lists what it will spend. */
        fun namesIn(text: String): List<String> = Expand.namesIn(text)

        /** True when [file] looks like a request this app would run — used by the picker. */
        fun looksLikeRequest(file: File): Boolean = file.extension.lowercase() in setOf("http", "rest")
    }
}

/**
 * True when the bytes are not text, by the one test that is cheap and right: **a NUL byte.**
 *
 * A more careful check would sniff for UTF-8 validity, which would call a valid JSON body with an
 * unusual character binary. A NUL is the one byte that text never contains, and every binary format
 * this app fetches — PNG, JPEG, zip, APK — has one early.
 *
 * **One implementation, two callers**: `http` decides whether to quote an answer in the conversation
 * with it, and a request document decides whether to run the text expander over its own body. A body
 * that is a picture being uploaded must be sent as it is, and a second copy of this rule would be a
 * second thing to keep right.
 */
internal fun looksBinary(bytes: ByteArray): Boolean = bytes.take(1024).any { it == 0.toByte() }
