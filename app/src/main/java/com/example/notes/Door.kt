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

/**
 * **There is no `Failure`/`classify` any more, and the absence is the change.**
 *
 * Those two answered *"is this the account's fault or the endpoint's?"* — the question a fallback
 * ladder needs, and the ladder is gone: a second machine used to be a provider tag behind one fixed
 * address, and it is now a second connection the person chose (`PLAN-credentials` C2). With nothing
 * to try next there is nothing for the classifier to decide, and the session's reconnection ladder —
 * which retries the same connection — never asked it.
 *
 * **The rule those tests encoded is kept, because it was never about routing.** A 401, a 402 or a 403
 * means stop and tell the person, and that is what `AgentSession.failed` does, carrying the provider's
 * own words in `lastWords` rather than the next endpoint's unrelated complaint. A timeout or a 5xx is
 * retried on the ladder until its window closes.
 */

/** One thing said in a burst. */
data class Msg(
    val role: String,
    val text: String,
    val callId: String = "",
    val calls: List<Call> = emptyList(),
    /** The reasoning that came with this assistant turn, sent back because the provider requires it. */
    val thinking: String = "",
    /**
     * Pictures to send **with** this message, as data URLs (`data:image/png;base64,…`).
     *
     * **A message carries its pictures; a tool result cannot.** The contract makes a `role: "tool"`
     * message text-only, so `look` does not answer with a picture — it records one as pending and the
     * next message carries it. That is why this field lives on a message at all, and why the tool
     * branch of [messagesOf] ignores it rather than being tempted to put it somewhere.
     *
     * Empty for almost every message. When it is empty the content is serialised as a plain string,
     * which is the shape every provider has always accepted.
     */
    val pictures: List<String> = emptyList(),
) {
    companion object {
        /** The operator's words, and the pictures that came with them. */
        fun user(text: String, pictures: List<String> = emptyList()) =
            Msg("user", text, pictures = pictures)

        /** The agent's own words, with the calls it made in the same reply. */
        fun assistant(text: String, calls: List<Call> = emptyList(), thinking: String = "") =
            Msg("assistant", text, calls = calls, thinking = thinking)

        /** The answer to one call, matched by the id the model chose. */
        fun tool(callId: String, text: String) = Msg("tool", text, callId = callId)
    }
}

/** One tool the model asked for: the name, the arguments as it wrote them, and its own id. */
data class Call(val id: String, val tool: String, val args: String)

/**
 * One reply. A reply may carry words, calls, or both — the contract allows both.
 *
 * **[usage] is the provider's own accounting for this reply**, carried here rather than only into
 * `requests.log`, because the bubble footer asks a question the request log cannot answer: *what did
 * this message cost*. `requests.log` is keyed by instant and knows nothing about row ids; the footer
 * is keyed by the row's id. The reply is the only thing that knows both what it said and what it
 * cost, so the number has to travel with it.
 *
 * **Null means the provider said nothing**, which is not the same as zero: a `Reply` built by a
 * scripted door has no accounting, and a footer that printed `output 0` for it would claim a
 * measurement nobody made. The two accessors return null in that case for the same reason.
 */
data class Reply(
    val text: String,
    val calls: List<Call> = emptyList(),
    val usage: JSONObject? = null,
    /**
     * What the model thought before it answered, when the provider sent it.
     *
     * **This is sent back, and the operator reversed the rule that said otherwise.** The rule was
     * "nothing the model thought is ever sent back to it as something it said", which is tidy and
     * wrong: a thinking-mode model refuses the next request with `400 The reasoning_content in the
     * thinking mode must be passed back to the API`. The provider's contract is that the reasoning
     * is part of the assistant turn, so it is stored and replayed with it.
     *
     * It is kept **apart** from [text] rather than folded into it, because the two are different
     * things in every place that reads them: the bubble shows what was said, the footer counts what
     * was thought, and the request puts each in its own field.
     */
    val thinking: String = "",
) {
    /** Tokens the model wrote, when the provider said. */
    val outputTokens: Int?
        get() = usage?.let { if (it.has("completion_tokens")) it.optInt("completion_tokens") else null }

    /** Tokens it spent thinking, when the provider said. */
    val thinkingTokens: Int?
        get() = usage?.optJSONObject("completion_tokens_details")
            ?.let { if (it.has("reasoning_tokens")) it.optInt("reasoning_tokens") else null }
}

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
 * **Reasoning is not excluded, and it is sent back.** It used to be excluded outright: the tokens
 * were generated, billed, and then thrown away unseen, which is the worst of both. Streaming fixed
 * the first half — reasoning arrives *first*, while the model is thinking and before it has written
 * anything, which makes "diligent" observable: a connection that has died is silent, a model working
 * hard is not. That is the distinction the idle timeout has to make.
 *
 * **The second half is a reversal, and the operator made it.** This class used to say, in as many
 * words, that nothing the model thought is ever sent back to it as something it said — the reasoning
 * went to the footer and no further. That is tidy and it is wrong: a thinking-mode model answers the
 * *next* request with `400 The reasoning_content in the thinking mode must be passed back to the
 * API`. The provider's contract is that the reasoning is part of the assistant turn, so it is stored
 * with the turn and replayed with it.
 *
 * **Kept apart from [Streamed.said] still**, and that part survives: the two are different things
 * everywhere they are read. The bubble shows what was said, the footer counts what was thought, and
 * the request puts each in its own field — the model never reads its own reasoning as prose it wrote.
 */
class OpenRouterDoor(
    private val key: () -> String?,
    /**
     * **Why the key in force may not be sent**, or null.
     *
     * The one refusal the resolver makes: a key written for one host and an address naming another.
     * It arrives here rather than being thrown where it is found, because the door is what turns "no
     * key" into a sentence a person reads.
     */
    private val keyProblem: () -> String? = { null },
    private val model: String,
    /**
     * **Which upstream answered, for the log and the footer** — not a routing instruction.
     *
     * It used to be a pin sent as `provider.only`, with a ladder over the model's endpoint tags.
     * Both are gone: the address below *is* the upstream, so a second one is a second connection
     * rather than a second tag. What remains is the name a person recognises, written into the
     * request log so "who answered" can still be compared.
     */
    private val provider: String,
    /**
     * **The connection this door speaks to** — the base URL, `/chat/completions` appended.
     *
     * Defaulted to OpenRouter's, so every existing caller and test keeps working; the wiring passes
     * the account's (`ActiveModel.baseUrl`), which is what makes any OpenAI-compatible endpoint usable.
     */
    private val baseUrl: String = OPENROUTER_BASE,
    private val effort: String,
    /**
     * The project these requests belong to, so the log can say who spent the money.
     *
     * **Empty by default**, which keeps every existing caller and test working and is the honest
     * answer for a door built without one - a keepalive, or a test. It is not a permission: nothing
     * is scoped by this, it is only recorded.
     */
    private val project: String = "",
    private val timeoutMs: Int = 120_000,
    /**
     * How long after sending, until **anything at all** comes back, and how long a gap inside an
     * answer may last.
     *
     * **One number, on the operator's instruction**: "lets merge connection and stalling timeouts
     * into a single response timeout that tracks time between request and first response packet" -
     * and, asked what bounds a mid-answer death, "one number that also restarts per packet". So it
     * is an idle timeout in the strict sense: the first packet is bounded by it, and every gap after
     * that is bounded by it too, which is what makes a model that is thinking safe and a connection
     * that died caught.
     */
    private val responseMs: Int = 120_000,
    /**
     * What the store can say about the key, for the sentence when there is no token.
     *
     * Defaulted from [key] so the two cannot disagree in a test that only supplies a key, and
     * supplied for real by the wiring, which asks the store once per request.
     */
    private val keyState: () -> KeyState =
        { if (key() != null) KeyState.READABLE else KeyState.NONE },
    /**
     * **True when the server wants no key at all**, so the request carries no `Authorization` header.
     *
     * A model server on this phone or on a local network is a real connection — and it is what the
     * request shape exists for (§2.6): Ollama answers at `/api/chat` with a body of its own. Told "no
     * model key on this account yet", a person with exactly such a server would be sent to find a key
     * that does not exist.
     */
    private val keyless: Boolean = false,
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
    /**
     * **The request this door sends**, for the kind [ShapeKind.CHAT] — the connection's own when it
     * was recorded, the app's preset when it was not.
     *
     * A door *without* a shape is the compatible default, which is the whole of the preset; a door
     * *with* one is a server that answers at another path or in another dialect, and it must be the
     * same object the window stored or the app would send two different requests to one server.
     */
    internal val shape: Shape = Shape.CHAT,
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
     * **Called once per finished reply**, with the words it produced.
     *
     * Separate from [listener] because the two have different clocks, and the operator named both:
     * "only footer token numbers should be updated on every chunk" and "one full reply seems like
     * the best point to update dispayed text".
     *
     * A reply is the unit the record uses - it becomes a page - so this is the boundary at which
     * the screen and the record agree about what has been said.
     */
    var onReply: ((String) -> Unit)? = null

    /**
     * The turn, **read as it arrives.**
     *
     * The request asks for `stream: true` and the body is a sequence of events rather than one
     * JSON document. Everything the answer is made of is joined by [Streamed], so the reply this
     * returns is the same shape a non-streamed one would have been — the difference is that it was
     * assembled instead of parsed, and that the listener saw it happen.
     */
    override fun turn(request: Request): Result<Reply> {
        // **One connection, one attempt, and no ladder.**
        //
        // There used to be a fallback here: a provider tag was pinned and this walked the model's
        // list, trying the next on an endpoint failure. That ladder was a *second machine behind one
        // address*, and it cannot survive this app's rule — the address is now the connection
        // (`PLAN-credentials` C2), so a different machine is a different base URL, which is a
        // different connection the person chose.
        //
        // **What is not lost:** reconnection. That is the session's ladder (`AgentSession`), which
        // knows how long an outage has gone on and whether retrying has earned anything; it always
        // worked on the same connection and still does.
        //
        // The failure path still writes a row, so the start time is taken here rather than inside
        // `turnFrom` — a stall is recorded where it happened.
        return turnFrom(request, System.currentTimeMillis())
    }

    private fun turnFrom(request: Request, began: Long): Result<Reply> = try {
        val stream = Streamed()
        val reply = streamed(request, stream)
        onStats?.invoke(
            RequestLog.Stats(
                kind = RequestLog.Stats.Kind.TURN,
                ms = System.currentTimeMillis() - began,
                provider = provider,
                model = model,
                project = project,
                usage = stream.usage,
                finish = stream.finish.ifBlank { null },
            ),
        )
        // **The words, published once for this reply.** A per-chunk write made the bubble
        // rearrange under the person's eyes - and because `Streamed` is created per request, a
        // burst of call-speak-call-speak also emptied it at every request boundary.
        //
        // Published here because this is the one place that knows a reply finished *and* still
        // holds the stream it came from.
        onReply?.invoke(stream.said.toString())
        Result.success(reply)
    } catch (e: Exception) {
        // **A stall is billed, and a refusal is not.** This distinction is the whole reason a
        // failure row exists for one of them and not the other:
        //
        // - A refusal or an ack timeout produced no output, and OpenRouter's zero-completion
        //   insurance means it was not charged. A row would be a row of zeroes, which is noise.
        // - A stall happened *after* tokens were generated. Whether they were billed depends on
        //   whether the upstream supports cancellation - some stop, some keep generating and charge
        //   for the whole thing after the connection dies. That is money, and it left no record.
        //
        // The row carries what had arrived instead of a `usage` block, because there is no `usage`:
        // the accounting arrives at the end of a stream that never ended. Blank columns say "not
        // reported", which is true, where a zero would say "free".
        (e as? Idle)?.let { idle ->
            onStats?.invoke(
                RequestLog.Stats(
                    kind = RequestLog.Stats.Kind.STALL,
                    ms = System.currentTimeMillis() - began,
                    provider = provider,
                    model = model,
                    project = project,
                    usage = null,
                    finish = null,
                    received = RequestLog.Stats.Received(
                        said = idle.stream.said.length,
                        thinking = idle.stream.thinking.length,
                    ),
                ),
            )
        }
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
                project = project,
                usage = usageOf(said),
                finish = finishOf(said),
            ),
        )
        Result.success(Unit)
    } catch (e: Exception) {
        // **No stall row here, and it is not an oversight.** A ping is not streamed, so it has no
        // middle to die in: the request either produced a whole body or it did not, and a timeout
        // means nothing came back. That is the unbilled case, which leaves no row at all.
        Result.failure(e)
    }

    /**
     * The token, or the sentence explaining why there is none — **or null when the server wants none.**
     *
     * A model server on this phone or on a local network has no keys to hand out, and asking it for
     * one is asking a question it cannot answer: the request would carry `Authorization: Bearer null`
     * or fail before it was sent. Null here means the header is absent, which is what such a server
     * reads as "no credential", and it is a different fact from a key that is not stored — that one
     * still gets its sentence, because the person can act on it.
     */
    private fun token(): String? = if (keyless) null else key() ?: throw IllegalStateException(
        // **The resolver's own sentence comes first**, because it knows something these do not: that a
        // key exists and belongs to another host. "No model key yet" would send a person to type one
        // they already have, in a row that was refused for a reason the sentence never named.
        keyProblem() ?:
        // **A locked phone is not a missing key.** The agent sends with the screen off, and the
        // store's own key is only usable while it is unlocked — so the ordinary case is a key
        // that exists and cannot be read right now. Said as "no key", it sent a person to type
        // one they already had.
        when (keyState()) {
            KeyState.LOCKED ->
                "This phone is locked, so the model key cannot be read. It is kept: " +
                    "unlock the phone and the agent will use it."
            // **A key that is stored and would not open is not a key that was never set.** Saying
            // "no key yet" sent a person to type one they already had - reported twice.
            KeyState.UNREADABLE ->
                "A model key is stored for this account, and this phone could not read it. " +
                    "Type it again to replace it."
            else -> "No model key on this account yet"
        },
    )

    /**
     * The body this door sends — **a function of its own so a test can read it.**
     *
     * Two rules live in these lines and nowhere else, and both are protocol rather than vendor:
     *
     * - **`stream_options.include_usage`** is how a *streamed* answer reports its tokens. `usage` is
     *   part of the protocol, but on a stream most servers send it only when asked: one extra final
     *   chunk with `choices: []` carrying the whole request's totals. The app worked without this
     *   line because OpenRouter sends usage unbidden — against a strict compatible endpoint every
     *   token column would quietly stay empty, which is a silence rather than an error.
     * - **`reasoning_effort`** is OpenAI's own spelling, and it is what a model that reasons reads.
     *
     * What is **not** here any more: `provider.only` and `allow_fallbacks` were OpenRouter's routing,
     * and the address is now the connection, so there is no upstream to pin. `reasoning.exclude` was
     * its switch for throwing reasoning away, and the app wants the reasoning streamed, because it is
     * the liveness signal that tells a diligent model from a dead connection.
     */
    internal fun body(request: Request, maxTokens: Int? = null): JSONObject =
        // **The body is a shape, not a function** (see `Shape`). The default preset is this same
        // object, field for field — so this line changed nothing about the wire format, and it is
        // what makes "ask a self-hosted server in its own words" a template rather than a patch.
        //
        // **Everything here is unchanged by a ping, and must be.** A provider's cache matches the
        // longest common prefix, so the tools and the options are part of what is being kept warm;
        // only the answer's size differs, and that is not part of the prompt. A blank effort and a
        // null `max_tokens` therefore have to *remove* their keys rather than send `""` and `null`,
        // which is the shape's rule (see `Shape`) and not an accident of this map.
        shape.bodyOf(
            mapOf(
                "model" to request.model,
                "messages" to messagesOf(request),
                "tools" to toolsOf(request.tools),
                "tool_choice" to "auto",
                "stream" to false,
                "effort" to request.effort.ifBlank { null },
                "max_tokens" to maxTokens,
            ),
        )

    /** One exchange, as text. **Unparsed**, because a ping does not read its answer. */
    private fun exchange(request: Request, maxTokens: Int?): String {
        val token = token()
        val body = body(request, maxTokens)

        var connection: HttpURLConnection? = null
        val text = try {
            connection = (URL(shape.address(baseUrl)).openConnection() as HttpURLConnection).apply {
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
            connection = (URL(shape.address(baseUrl)).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_MS
                // **The ack's number for the first read.** It is replaced by the idle one as soon
                // as the stream has said anything, because from then on the question is a
                // different one.
                readTimeout = responseMs
                doOutput = true
                headers(token).forEach { (name, value) -> setRequestProperty(name, value) }
            }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val said = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw Refused(code, "OpenRouter answered $code${why(said)}")
            }
            connection.readTimeout = responseMs
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
            Idle(responseMs.toLong(), stream)
        } else {
            Unacknowledged(responseMs.toLong(), stream)
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
    internal fun headers(token: String?): List<Pair<String, String>> {
        // **A header value is one line, and a token is whatever was pasted.** A key copied out of a
        // file with Windows endings, or off a page that wrapped it, carries a line break - and a
        // line break in a header value either throws inside `setRequestProperty` or, on a laxer
        // stack, ends the header and starts another. Refused here, before a socket is opened, so
        // the sentence a person reads does not depend on which stack they run.
        require(token == null || ('\n' !in token && '\r' !in token)) {
            "The model key has a line break in it, so it cannot be sent. Copy it again."
        }
        return buildList {
            // **No key is no header**, not an empty one: a server that wants no credential reads the
            // absence, and a server that wants one refuses an empty bearer in words of its own.
            if (token != null) add("Authorization" to "Bearer $token")
            add("Content-Type" to "application/json")
            add("Accept" to "application/json")
        }
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

    /**
     * The provider's own sentence, which is always more useful than the number.
     *
     * **The wrapper is not the answer.** OpenRouter reports a provider's refusal as
     * `{"error":{"message":"Provider returned error", "metadata":{"raw":"{...the real one...}"}}}` -
     * so reading only `error.message` printed "Provider returned error" for every distinct failure,
     * and the sentence that said *why* (`Invalid schema for function 'forget'…`) sat one level
     * deeper, unread. That is how a chat that never worked reported one useless line.
     *
     * The `raw` payload is itself a JSON document, so it is unwrapped when it parses, and shown as
     * it stands when it does not.
     */
    private fun why(body: String): String = try {
        val error = JSONObject(body).optJSONObject("error")
        val wrapper = error?.optString("message").orEmpty()
        val raw = error?.optJSONObject("metadata")?.optString("raw").orEmpty()
        val inner = if (raw.isBlank()) "" else try {
            JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty()
        } catch (t: Throwable) {
            raw.take(300)
        }
        // The inner sentence alone when it exists: it says what failed, and the wrapper only says
        // that something did.
        val said = inner.ifBlank { wrapper }
        if (said.isBlank()) "" else ": $said"
    } catch (t: Throwable) {
        if (body.isBlank()) "" else ": ${body.take(200)}"
    }

    private fun messagesOf(request: Request): JSONArray {
        val array = JSONArray()
        array.put(JSONObject().put("role", "system").put("content", request.system))
        for (message in request.messages) {
            val one = JSONObject().put("role", message.role)
            // **The reasoning goes back with the turn it came from.** A thinking-mode model refuses
            // the next request without it: "400 The reasoning_content in the thinking mode must be
            // passed back to the API". Written before the branches below because every assistant
            // turn may carry it, with or without calls.
            //
            // Only when there is some. An empty string is not the same as absent: the provider
            // distinguishes "this turn had no reasoning" from "you forgot to send it", and a model
            // that never reasons should not be handed a field it never produced.
            if (message.role == "assistant" && message.thinking.isNotEmpty()) {
                one.put("reasoning_content", message.thinking)
            }
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

                else -> one.put("content", contentOf(message))
            }
            array.put(one)
        }
        return array
    }

    /**
     * One message's `content`: a **string**, or an **array of parts** when it carries pictures.
     *
     * **The string is the default and the array is the exception, deliberately.** Every message this
     * app has ever sent is a string, and every provider accepts it. Turning every message into an
     * array to serve the rare one would change the shape of the whole conversation to carry a field
     * almost none of it has — a risk taken for nothing, on the one request path that has to work on a
     * train.
     *
     * When there are pictures the text goes first and the pictures after, in the order they were
     * named: the model reads what the message is about before it looks at what it is showing, and
     * `look`'s own result sentence has already said which path is which.
     */
    private fun contentOf(message: Msg): Any {
        if (message.pictures.isEmpty()) return message.text
        val parts = JSONArray()
        // An empty text part is not the same as no text part: a message that is only a picture
        // should be *only* a picture, and a `"text": ""` in front of it is a blank line the model
        // has to read past.
        if (message.text.isNotEmpty()) {
            parts.put(JSONObject().put("type", "text").put("text", message.text))
        }
        for (picture in message.pictures) {
            parts.put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", picture))
            )
        }
        return parts
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
        // **No ENDPOINT here any more.** The address is the connection's ([baseUrl]): a second host
        // is a second connection rather than a second constant.

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
