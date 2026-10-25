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
}

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

    override fun turn(request: Request): Result<Reply> {
        asked.add(request)
        val next = replies.removeFirstOrNull()
            ?: return Result.failure(IllegalStateException("the script ran out of replies"))
        return Result.success(next)
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
 * Reasoning is excluded. It costs tokens that are thrown away, and the agent's reasoning
 * belongs in its record, where a person can read it, rather than in a hidden field.
 */
class OpenRouterDoor(
    private val key: () -> String?,
    private val model: String,
    private val provider: String,
    private val effort: String,
    private val timeoutMs: Int = 120_000,
) : Door {

    override fun turn(request: Request): Result<Reply> = try {
        val token = key()
            ?: throw IllegalStateException("No OpenRouter key on this account yet")
        Result.success(send(request, token))
    } catch (e: Exception) {
        Result.failure(
            IllegalStateException(Secrets.explain(e, "The model could not be asked"))
        )
    }

    private fun send(request: Request, token: String): Reply {
        val body = JSONObject()
            .put("model", request.model)
            .put("messages", messagesOf(request))
            .put("tools", toolsOf(request.tools))
            .put("tool_choice", "auto")
            .put("provider", JSONObject().put("only", JSONArray().put(request.provider))
                .put("allow_fallbacks", false))
            .put("reasoning", JSONObject().put("exclude", true))
            .put("stream", false)
        if (request.effort.isNotBlank()) body.put("reasoning_effort", request.effort)

        var connection: HttpURLConnection? = null
        val text = try {
            connection = (URL("https://openrouter.ai/api/v1/chat/completions")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = timeoutMs
                doOutput = true
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            val answer = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                throw IllegalStateException("OpenRouter answered $code${why(answer)}")
            }
            answer
        } finally {
            connection?.disconnect()
        }
        return parseReply(text)
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
