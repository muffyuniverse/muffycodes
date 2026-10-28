package com.example.notes

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * One exchange with a model, behind one interface.
 *
 * The app's whole agent exists on the other side of this: a request in, a reply out, and no
 * knowledge of which provider answered. That is not decoration — it is what makes the loop
 * testable without a socket, a key or a bill, and it is what lets the app say exactly which
 * pinned endpoint it used when something goes wrong.
 */
interface Door {
    fun turn(request: Request): Result<Reply>

    /**
     * Sends the conversation again **for the provider's cache, not for an answer.**
     *
     * A conversation nobody is talking in still has a price: the next sentence the person types is
     * a fresh prompt unless the cache is warm. Re-sending the identical request every few minutes
     * costs a cache read of it, which is a tenth of the price of building it again.
     *
     * **The answer is not read, and that is the act.** [OpenRouterDoor] asks for one token, so
     * nothing is generated, nothing is parsed and nothing can end a turn — a model that answered
     * with nonsense here would change nothing.
     *
     * Defaulted to an ordinary turn with the result dropped, because a door that cannot tell the
     * two apart still warms correctly; the scripted door overrides it to keep pings out of a
     * script that is about turns.
     */
    fun ping(request: Request): Result<Unit> = turn(request).map { }
}

/**
 * The provider's answer was a refusal, **kept as a code.**
 *
 * A ping's failure is not a turn's failure: a dead key and a parameter the provider will not take
 * are both "no", and only the first is the person's to fix. The number is what tells them apart,
 * and a message that only said "OpenRouter answered 401" would be parsed back into the number it
 * came from.
 */
class Refused(val code: Int, message: String) : IllegalStateException(message)

/** One thing said in a burst. */
data class Msg(
    val role: String,
    val text: String,
    val callId: String = "",
    val calls: List<Call> = emptyList(),
) {
    companion object {
        /** The operator's words. */
        fun user(text: String) = Msg("user", text)

        /** The agent's own words, with the calls it made in the same reply. */
        fun assistant(text: String, calls: List<Call> = emptyList()) =
            Msg("assistant", text, calls = calls)

        /** The answer to one call, matched by the id the model chose. */
        fun tool(callId: String, text: String) = Msg("tool", text, callId = callId)
    }
}

/** One tool the model asked for: the name, the arguments as it wrote them, and its own id. */
data class Call(val id: String, val tool: String, val args: String)

/** One reply. A reply may carry words, calls, or both — the contract allows both. */
data class Reply(val text: String, val calls: List<Call> = emptyList())

/**
 * One request, as this app builds it.
 *
 * The three prompt layers are not three messages. They are one **system** message whose parts
 * are the instructions, the project's own instructions, and the agent's context — because a
 * provider caches on the longest common *token prefix*, and three messages that never change
 * would still be re-read if the thing between them changed. Concatenated in that order, an
 * edit to the context invalidates from the context onwards and never touches the instructions.
 */
data class Request(
    val model: String,
    val provider: String,
    val effort: String,
    val system: String,
    val messages: List<Msg>,
    val tools: List<ToolSpec>,
) {
    /** What the request costs to describe: used by the record, not by the model. */
    val shape: String
        get() = "$model · $provider · ${messages.size} messages · ${tools.size} tools"
}

/** One tool, described to the model the way the endpoint expects it. */
data class ToolSpec(
    val name: String,
    val description: String,
    val parameters: JSONObject,
)

/**
 * A door that answers from a script.
 *
 * Two things it is for, and both are about tests being worth something: the loop can be driven
 * through every path it has — a call, two calls, a terminating call, a refusal — without a
 * network, and `asked` is the record of exactly what the app *sent*, which is the only way to
 * assert that the instructions, the context and the seam were in the request at all.
 */
class ScriptedDoor(vararg script: Reply) : Door {

    private val replies = ArrayDeque(script.toList())

    /** Every request the app made, in order. */
    val asked = mutableListOf<Request>()

    /** Every keepalive ping, in order — **not** counted as a question, and it consumes no reply. */
    val pings = mutableListOf<Request>()

    override fun turn(request: Request): Result<Reply> {
        asked.add(request)
        val next = replies.removeFirstOrNull()
            ?: return Result.failure(IllegalStateException("the script ran out of replies"))
        return Result.success(next)
    }

    override fun ping(request: Request): Result<Unit> {
        pings.add(request)
        return Result.success(Unit)
    }
}

/**
 * The real door: one pinned endpoint, no fallback, no autorouting.
 *
 * `provider.only` names one **tag**, never a provider's base slug — a base slug matches every
 * endpoint that provider runs, so writing one would quietly turn the pin back into autorouting
 * inside that provider. `allow_fallbacks` is false for the same reason: this app's answer to a
 * provider that is down is to stop and say so, not to spend the operator's money on a different
 * model than the one they chose.
 *
 * `tool_choice` is `auto` and never a forced function. Measured: the pinned endpoints report
 * `supports_tool_choice: {auto: true, function: false}`, so the forced form would be refused by
 * at least one of them — and this app must behave the same whichever one is active.
 *
 * **Reasoning is no longer excluded, and streaming is why.** It used to be: the tokens were
 * generated, billed, and then thrown away unseen, which is the worst of both. With a streamed
 * answer they are not thrown away — they arrive *first*, while the model is thinking and before it
 * has written anything, which makes "diligent" observable. A connection that has died is silent; a
 * model working hard is not. That is exactly the distinction the idle timeout has to make, so
 * excluding reasoning would leave the app blind to the one signal that answers it.
 *
 * It also reaches the footer, so a person watching a long turn sees progress rather than a still
 * screen — and it is not in the conversation: [Streamed.thinking] is kept apart from
 * [Streamed.said], so nothing the model thought is ever sent back to it as something it said.
 */
class OpenRouterDoor(
    private val key: () -> String?,
    private val model: String,
    private val provider: String,
    private val effort: String,
    private val timeoutMs: Int = 120_000,
    /**
     * How long after sending, until **anything at all** comes back.
     *
     * Not tight, and it must not be: an upstream that is queueing holds the connection open and
     * sends nothing, and OpenRouter buffers the first delta. A stream that fails here produced
     * nothing, which is what makes retrying it free.
     */
    private val ackMs: Int = 90_000,
    /**
     * The gap allowed **between** chunks, once the answer has begun.
     *
     * A model thinking about a long answer is quiet for a while and must not be killed; a
     * connection that has died is quiet for ever. This is the number that tells them apart, and it
     * is why the two waits are separate settings rather than one.
     */
    private val gapMs: Int = 120_000,
    /**
     * What the store can say about the key, for the sentence when there is no token.
     *
     * Defaulted from [key] so the two cannot disagree in a test that only supplies a key, and
     * supplied for real by the wiring, which asks the store once per request.
     */
    private val keyState: () -> KeyState =
        { if (key() != null) KeyState.READABLE else KeyState.NONE },
    /**
     * Where each response's statistics go, or null when nobody is keeping them.
     *
     * **A parameter, and nullable, because the log is evidence and never a dependency.** A door
     * built by a test has no account folder to write into and must still work; a door built by the
     * wiring has one, and every answer it receives — including the ones a ping throws away — is
     * written down. Called from the thread that already has the numbers, so nothing is copied and
     * no second pass over the reply is needed.
     */
    private val onStats: ((RequestLog.Stats) -> Unit)? = null,
) : Door {

    /**
     * How a person is told what is happening **while it is happening.**
     *
     * Null by default, which is the same answer as "nobody is watching": a turn run for the
     * background, or by a test, has no screen to draw a footer on, and a listener that was required
     * would make every caller write an empty one.
     *
     * Called from the reading thread, once per chunk, **with the stream itself** — so a listener
     * that wants the running character count reads it rather than being handed a number that would
     * then be a second copy of the same fact.
     */
    var listener: ((Streamed, Long) -> Unit)? = null

    /**
     * The turn, **read as it arrives.**
     *
     * The request asks for `stream: true` and the body is a sequence of events rather than one
     * JSON document. Everything the answer is made of is joined by [Streamed], so the reply this
     * returns is the same shape a non-streamed one would have been — the difference is that it was
     * assembled instead of parsed, and that the listener saw it happen.
     */
    override fun turn(request: Request): Result<Reply> = try {
        val began = System.currentTimeMillis()
        val stream = Streamed()
        val reply = streamed(request, stream)
        onStats?.invoke(
            RequestLog.Stats(
                kind = RequestLog.Stats.Kind.TURN,
                ms = System.currentTimeMillis() - began,
                provider = provider,
                model = model,
                usage = stream.usage,
                finish = stream.finish.ifBlank { null },
            ),
        )
        Result.success(reply)
    } catch (e: Exception) {
        Result.failure(
            IllegalStateException(Secrets.explain(e, "The model could not be asked"))
        )
    }

    /**
     * The same request, one token of answer, thrown away.
     *
     * **The failure keeps its type**, unlike [turn]'s: whether a refusal is the person's to fix
     * depends on the code, and the caller is the one that knows a ping may not stop a session.
     */
    override fun ping(request: Request): Result<Unit> = try {
        val began = System.currentTimeMillis()
        // **The body is read now, though nothing is done with the answer.** It used to be dropped
        // on the floor, which is why a keepalive that quietly grew from twenty tokens to five
        // hundred was indistinguishable from one that was fine: the numbers arrived on every
        // response and were thrown away with the text. The text is still discarded — a ping's
        // answer must never enter the conversation — but its `usage` is the only evidence there is
        // that the ping is cheap, so it is kept.
        val said = exchange(request, maxTokens = PING_TOKENS)
        onStats?.invoke(
            RequestLog.Stats(
                kind = RequestLog.Stats.Kind.PING,
                ms = System.currentTimeMillis() - began,
                provider = provider,
                model = model,
                usage = usageOf(said),
                finish = finishOf(said),
            ),
        )
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** The token, or the sentence explaining why there is none. */
    private fun token(): String = key() ?: throw IllegalStateException(
        // **A locked phone is not a missing key.** The agent sends with the screen off, and the
        // store's own key is only usable while it is unlocked — so the ordinary case is a key
        // that exists and cannot be read right now. Said as "no key", it sent a person to type
        // one they already had.
        when (keyState()) {
            KeyState.LOCKED ->
                "This phone is locked, so the OpenRouter key cannot be read. It is kept: " +
                    "unlock the phone and the agent will use it."
            else -> "No OpenRouter key on this account yet"
        },
    )

    /**
     * The body this door sends — **a function of its own so a test can read it.**
     *
     * Three agreed rules live in these eight lines and nowhere else: the pin is a **tag** in
     * `provider.only`, `allow_fallbacks` is false (no fallback, no autorouting), and reasoning is
     * excluded because its tokens are thrown away. Every one of them is a field whose absence would
     * look like the app working, so the assembled body is the thing worth pinning rather than the
     * call that wraps it — and the call cannot be tested without a socket.
     */
    internal fun body(request: Request, maxTokens: Int? = null): JSONObject {
        val body = JSONObject()
            .put("model", request.model)
            .put("messages", messagesOf(request))
            .put("tools", toolsOf(request.tools))
            .put("tool_choice", "auto")
            .put("provider", JSONObject().put("only", JSONArray().put(request.provider))
                .put("allow_fallbacks", false))
            // **`exclude: false` since streaming landed.** The reason is above the class: reasoning
            // that is streamed is a liveness signal, not wasted tokens. It is still not part of the
            // answer, and never enters the conversation.
            .put("reasoning", JSONObject().put("exclude", false))
            .put("stream", false)
        if (request.effort.isNotBlank()) body.put("reasoning_effort", request.effort)
        // **Everything above is unchanged by a ping, and must be.** A provider's cache matches the
        // longest common prefix, so the tools and the pin are part of what is being kept warm; only
        // the answer's size differs, and that is not part of the prompt.
        if (maxTokens != null) body.put("max_tokens", maxTokens)
        return body
    }

    /** One exchange, as text. **Unparsed**, because a ping does not read its answer. */
    private fun exchange(request: Request, maxTokens: Int?): String {
        val token = token()
        val body = body(request, maxTokens)

        var connection: HttpURLConnection? = null
        val text = try {
            connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_MS
                readTimeout = timeoutMs
                doOutput = true
                headers(token).forEach { (name, value) -> setRequestProperty(name, value) }
            }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            val answer = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw Refused(code, "OpenRouter answered $code${why(answer)}")
            }
            answer
        } finally {
            connection?.disconnect()
        }
        return text
    }

    /**
     * One turn, read off the socket as it arrives.
     *
     * **Three clocks, because there are three different waits** and one number cannot describe
     * them:
     *
     *  - the **connect** timeout, unchanged at twenty seconds: a socket that will not open is a
     *    network that is not there;
     *  - the **ack**: how long after the request is sent until *anything at all* comes back. This
     *    may be long — an upstream that is queueing holds the connection and sends nothing — so it
     *    is not tight, and a stream that fails here has cost one refused request and no answer,
     *    which is what makes retrying it cheap;
     *  - the **idle**: the gap *between* chunks once the answer has begun. A model thinking hard
     *    about a long answer can go quiet for a while, but a connection that has died goes quiet
     *    for ever, and this is the number that tells them apart.
     *
     * The read timeout on the connection is set to the **idle** one, because that is the gap the
     * socket itself can see. The ack is enforced by the first read having its own deadline: the
     * connection's read timeout starts when the socket is opened, so ack and idle are the same
     * mechanism with different numbers, and the first read is the one that uses the ack's.
     */
    private fun streamed(request: Request, stream: Streamed = Streamed()): Reply {
        val token = token()
        val began = System.currentTimeMillis()
        val body = body(request, maxTokens = null).put("stream", true)

        var connection: HttpURLConnection? = null
        try {
            connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_MS
                // **The ack's number for the first read.** It is replaced by the idle one as soon
                // as the stream has said anything, because from then on the question is a
                // different one.
                readTimeout = ackMs
                doOutput = true
                headers(token).forEach { (name, value) -> setRequestProperty(name, value) }
            }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val said = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw Refused(code, "OpenRouter answered $code${why(said)}")
            }
            connection.readTimeout = gapMs
            connection.inputStream.bufferedReader().use { reader ->
                while (true) {
                    val line = try {
                        reader.readLine()
                    } catch (t: java.net.SocketTimeoutException) {
                        throw stall(stream)
                    } ?: break
                    stream.line(line)
                    // **Elapsed is measured here, where the clock is**, rather than in the footer:
                    // a screen that computed it would need its own timer, and two clocks is how the
                    // number and the thing it describes drift apart.
                    listener?.invoke(stream, System.currentTimeMillis() - began)
                }
            }
        } finally {
            connection?.disconnect()
        }
        return stream.reply()
    }

    /**
     * **Which clock ran out**, decided in one place and answerable without a socket.
     *
     * A stall has two names and they are not the same problem. Before the first chunk the provider
     * never acknowledged the request: nothing was produced, so repeating the attempt costs the same
     * as the first one. After it, an answer was being written and has stopped — a different sentence
     * for the person, and a different decision about whether to try again.
     *
     * This is a function of one boolean because that boolean is the entire distinction. Tested
     * directly, it cannot drift; tested through a socket, it could only be checked by waiting.
     */
    internal fun stall(stream: Streamed): java.io.IOException =
        if (stream.acknowledged) {
            Idle(gapMs.toLong(), stream)
        } else {
            Unacknowledged(ackMs.toLong(), stream)
        }

    /**
     * The headers a request carries, given its token.
     *
     * **A function, not three lines inside `exchange`, because the token is the one thing here a
     * report has ever been filed about.** "The openrouter key isn't passing through" is a claim
     * about a header, and while that header was built inside a method that opens a socket, the only
     * way to check the claim was to read the source and believe it. `internal` so a test can ask
     * what would be sent without sending it.
     */
    internal fun headers(token: String): List<Pair<String, String>> {
        // **A header value is one line, and a token is whatever was pasted.** A key copied out of a
        // file with Windows endings, or off a page that wrapped it, carries a line break - and a
        // line break in a header value either throws inside `setRequestProperty` or, on a laxer
        // stack, ends the header and starts another. Refused here, before a socket is opened, so
        // the sentence a person reads does not depend on which stack they run.
        require('\n' !in token && '\r' !in token) {
            "The OpenRouter key has a line break in it, so it cannot be sent. Copy it again."
        }
        return listOf(
            "Authorization" to "Bearer $token",
            "Content-Type" to "application/json",
            "Accept" to "application/json",
        )
    }

    /**
     * The `usage` block of a **non-streamed** reply, or null when the body has none.
     *
     * A ping does not go through [Streamed] — there are no deltas to assemble, and nothing about the
     * answer is wanted — so the two numbers the log needs are read straight off the document. Null
     * rather than an empty object, because "the provider did not say" and "the provider said zero"
     * are different facts and only one of them is a cache hit.
     */
    internal fun usageOf(body: String): JSONObject? = try {
        JSONObject(body).optJSONObject("usage")
    } catch (t: Throwable) {
        null
    }

    /** The `finish_reason` of a non-streamed reply, or null when it did not say. */
    internal fun finishOf(body: String): String? = try {
        JSONObject(body).optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optString("finish_reason")
            ?.takeIf { it.isNotEmpty() && it != "null" }
    } catch (t: Throwable) {
        null
    }

    /** The provider's own sentence, which is always more useful than the number. */
    private fun why(body: String): String = try {
        val said = JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
        if (said.isBlank()) "" else ": $said"
    } catch (t: Throwable) {
        if (body.isBlank()) "" else ": ${body.take(200)}"
    }

    private fun messagesOf(request: Request): JSONArray {
        val array = JSONArray()
        array.put(JSONObject().put("role", "system").put("content", request.system))
        for (message in request.messages) {
            val one = JSONObject().put("role", message.role)
            when {
                message.role == "assistant" && message.calls.isNotEmpty() -> {
                    one.put(
                        "content",
                        if (message.text.isEmpty()) JSONObject.NULL else message.text,
                    )
                    val calls = JSONArray()
                    for (call in message.calls) {
                        calls.put(
                            JSONObject()
                                .put("id", call.id)
                                .put("type", "function")
                                .put(
                                    "function",
                                    JSONObject().put("name", call.tool).put("arguments", call.args),
                                )
                        )
                    }
                    one.put("tool_calls", calls)
                }

                message.role == "tool" -> {
                    one.put("content", message.text)
                    one.put("tool_call_id", message.callId)
                }

                else -> one.put("content", message.text)
            }
            array.put(one)
        }
        return array
    }

    private fun toolsOf(tools: List<ToolSpec>): JSONArray {
        val array = JSONArray()
        for (tool in tools) {
            array.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", tool.name)
                            .put("description", tool.description)
                            .put("parameters", tool.parameters),
                    )
            )
        }
        return array
    }

    companion object {

        /**
         * What a ping asks for: **one token.**
         *
         * Not zero, although nothing is wanted: OpenAI-compatible endpoints require `max_tokens`
         * to be at least one, and OpenRouter passes the field straight to the provider, so a zero
         * comes back as a 400. Measured against the same idea in this container's own keepalive:
         * a one-token ping of a cache-warm prefix cost about $0.000018, because the price of a
         * ping is the prompt it re-reads and not the token it generates.
         */
        const val PING_TOKENS = 1

        /** The one endpoint this app talks to. A constant because two functions now open it. */
        const val ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"

        /**
         * A socket that will not open is a network that is not there, and no amount of waiting
         * changes that. Unchanged from before streaming: it was never the number that was wrong.
         */
        const val CONNECT_MS = 20_000

        /**
         * One reply out of the endpoint's own shape.
         *
         * The message's `content` may be null when the model answered with calls only, and the
         * calls' arguments arrive as a **string** of JSON rather than an object — passing that
         * string through unchanged is what lets the record show what the model actually wrote,
         * which is what somebody debugging a bad call needs to see.
         */
        fun parseReply(text: String): Reply {
            val root = try {
                JSONObject(text)
            } catch (e: Exception) {
                throw IllegalStateException("The model's answer was not JSON: ${text.take(200)}")
            }
            root.optJSONObject("error")?.let {
                throw IllegalStateException("The model refused: ${it.optString("message")}")
            }
            val choices = root.optJSONArray("choices")
                ?: throw IllegalStateException("The answer has no choices in it")
            val first = choices.optJSONObject(0)
                ?: throw IllegalStateException("The answer has no choices in it")
            val message = first.optJSONObject("message")
                ?: throw IllegalStateException("The answer has no message in it")
            val calls = ArrayList<Call>()
            message.optJSONArray("tool_calls")?.let { array ->
                for (i in 0 until array.length()) {
                    val one = array.optJSONObject(i) ?: continue
                    val function = one.optJSONObject("function") ?: continue
                    calls.add(
                        Call(
                            id = one.optString("id"),
                            tool = function.optString("name"),
                            args = function.optString("arguments", "{}"),
                        )
                    )
                }
            }
            val said = if (message.isNull("content")) "" else message.optString("content")
            return Reply(said, calls)
        }
    }
}
