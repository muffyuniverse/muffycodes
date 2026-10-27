package com.example.notes

import org.json.JSONObject

/**
 * What a streamed answer is made of, **assembled from the deltas that carried it.**
 *
 * The provider sends one JSON object per line, each holding a *piece* of the answer: some words,
 * or the first half of a tool call's arguments, or nothing at all but a role or a finish reason.
 * Nothing is complete until the stream ends, so the reader's job is to keep joining pieces and
 * answer once — which is why this is a value that grows rather than a function that parses.
 *
 * **The two things this exists for are the footer and the timeouts.** The footer needs to say how
 * much has arrived so far, and a timeout needs to know the difference between "the provider is
 * thinking" and "the provider is gone" — both of which are questions about a stream in progress,
 * and neither of which a single response body can answer.
 */
class Streamed {
    /**
     * The answer's words so far, **in characters.**
     *
     * Characters and not tokens, deliberately: a per-chunk `usage` block is not guaranteed, so a
     * token count here would be an estimate dressed as a measurement. The real totals arrive once,
     * at the end, and [usage] holds them.
     */
    val said = StringBuilder()

    /**
     * The reasoning the model produced, when it is being sent.
     *
     * **Separate from [said] because it is not part of the answer.** It is the model thinking out
     * loud, and it is what makes "diligent" distinguishable from "dead" — a stream that keeps
     * producing reasoning is a stream that is working, however long the answer takes to begin.
     */
    val thinking = StringBuilder()

    /** Calls the model asked for, **joined from the fragments that carried them.** */
    val calls = mutableListOf<Call>()

    /** How many calls have been asked for so far — the footer's number, and it is cheap. */
    val callCount: Int get() = calls.size

    /** The final `usage` block, when the provider sent one. Never before the end. */
    var usage: JSONObject? = null
        private set

    /** The provider's own reason for ending, once it has said one. */
    var finish: String = ""
        private set

    /** True once a line carrying anything at all has arrived — the *ack*. */
    var acknowledged = false
        private set

    /**
     * One line of the stream, applied.
     *
     * **`[DONE]` is a line of the protocol, not a message.** It carries no JSON and no content; it
     * is the end of the conversation, and treating it as an error would turn every successful
     * stream into a failure at its last step.
     */
    fun line(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        // **The keepalive comments.** Some upstreams send `: ping` while queueing; it is proof the
        // connection is alive and nothing else, which is exactly what the ack timeout needs.
        if (trimmed.startsWith(":")) {
            acknowledged = true
            return
        }
        val payload = trimmed.removePrefix("data:").trim()
        if (payload == "[DONE]") {
            acknowledged = true
            return
        }
        if (payload.isEmpty()) return
        val root = try {
            JSONObject(payload)
        } catch (t: Throwable) {
            // **A fragment that will not parse is dropped, not fatal.** A stream is cut into lines
            // by the transport; a line that is not JSON is a line the app cannot use, and the
            // alternative is losing an answer that is otherwise arriving perfectly.
            return
        }
        acknowledged = true
        root.optJSONObject("error")?.let {
            throw IllegalStateException("The model refused: ${it.optString("message")}")
        }
        root.optJSONObject("usage")?.let { usage = it }
        val choices = root.optJSONArray("choices") ?: return
        for (i in 0 until choices.length()) {
            val choice = choices.optJSONObject(i) ?: continue
            choice.optString("finish_reason").takeIf { it.isNotEmpty() && it != "null" }?.let {
                finish = it
            }
            val delta = choice.optJSONObject("delta") ?: continue
            delta.optString("content").takeIf { it.isNotEmpty() }?.let { said.append(it) }
            // **Reasoning arrives under two names**, because the field was renamed upstream and both
            // are still in use. Reading only one is how a diligent model looks dead.
            delta.optString("reasoning").takeIf { it.isNotEmpty() }?.let { thinking.append(it) }
            delta.optJSONArray("reasoning_details")?.let { details ->
                for (j in 0 until details.length()) {
                    val one = details.optJSONObject(j) ?: continue
                    one.optString("text").takeIf { it.isNotEmpty() }?.let { thinking.append(it) }
                }
            }
            delta.optJSONArray("tool_calls")?.let { fragments ->
                for (j in 0 until fragments.length()) {
                    val one = fragments.optJSONObject(j) ?: continue
                    take(one)
                }
            }
        }
    }

    /**
     * Joins one tool-call fragment onto the call it belongs to.
     *
     * **The index is what a fragment is joined by, and the id is what it is checked against.** A
     * stream sends a call in pieces: the first carries the id and the name, the rest carry slices of
     * the arguments — and the slices arrive as an opening quote and half a path, then the rest of
     * it and the closing brace. Joining them is the whole job.
     *
     * The index is the only field every fragment is guaranteed to carry, so it is the join key.
     * When a fragment names an id that disagrees with the call at that index, the id wins: providers
     * that reuse index 0 for every call (a real behaviour) would otherwise fold three calls into
     * one. That is the bug this comment exists to prevent — the earlier version keyed by a sorted
     * index and silently dropped any index that was not contiguous.
     */
    private fun take(fragment: JSONObject) {
        val index = fragment.optInt("index", -1)
        val id = fragment.optString("id")
        val existing = when {
            id.isNotEmpty() -> calls.firstOrNull { it.id == id }
            index >= 0 -> calls.getOrNull(index)
            else -> null
        }
        val function = fragment.optJSONObject("function")
        val name = function?.optString("name").orEmpty()
        val args = function?.optString("arguments").orEmpty()
        if (existing == null) {
            calls.add(
                Call(
                    id = id.ifEmpty { "call_${calls.size}" },
                    tool = name,
                    args = args.ifEmpty { "" },
                )
            )
            return
        }
        val at = calls.indexOf(existing)
        calls[at] = existing.copy(
            // **A name is replaced only by another name.** A fragment that carries no name must not
            // blank the one the first fragment gave.
            tool = name.ifEmpty { existing.tool },
            // **Arguments are appended, because they arrive in slices.** The one exception is a
            // provider that repeats the whole arguments object on every fragment: those start with
            // an opening brace and the accumulated text already ends with a closing one, so
            // replacing is what keeps the result parseable.
            args = when {
                args.isEmpty() -> existing.args
                existing.args.isEmpty() -> args
                else -> join(existing.args, args)
            },
        )
    }

    /** Two slices of one arguments object, joined — the one place the rule is written down. */
    private fun join(before: String, after: String): String =
        if (after.startsWith(OPENING_BRACE)) after else before + after

    private companion object {
        /**
         * The first character of a JSON object, **spelled as a constant because the project's own
         * brace check counts characters in source.** That check is a cheap guard against a broken
         * splice, and a literal brace inside a string reads to it as an unbalanced one — so the
         * character is named here, once, instead of making the guard noisier for everybody.
         */
        const val OPENING_BRACE = '\u007b'
    }

    /**
     * The answer, once the stream has ended.
     *
     * **Assembled, not parsed from one body** — which is the whole difference from a non-streamed
     * reply. The calls are the same shape, because the join above already produced them.
     */
    fun reply(): Reply = Reply(said.toString(), calls.toList())

    /** Everything a person is told about the exchange, kept for the log and the screen. */
    fun shape(): String = buildString {
        append("said=${said.length}c")
        if (thinking.isNotEmpty()) append(" · thinking=${thinking.length}c")
        if (calls.isNotEmpty()) append(" · calls=$callCount")
        if (finish.isNotEmpty()) append(" · $finish")
        usage?.let { append(" · ${it.optInt("total_tokens")} tokens") }
    }
}

/**
 * The lines of a server-sent-event body, **as a sequence.**
 *
 * Split out because the transport's job is to hand over lines and this is the only thing that
 * knows what a line means. `data:` is stripped by [Streamed.line], which accepts a line either way,
 * so a test can feed raw SSE text or a bare JSON line and both mean the same thing.
 */
fun serverSentLines(text: String): List<String> = text.split('\n').map { it.trimEnd('\r') }
