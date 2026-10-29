package com.example.notes

import org.json.JSONObject

/**
 * What one response cost, as the provider reported it.
 *
 * **The name is a leftover and the class is not.** This used to own the log file: the formatting,
 * the appending, the compaction, and the eleven columns a person would read by eye. All of that
 * moved to SQLite ([RequestDb]) when the rollups stopped being lines in one file — because
 * de-duplicating a pyramid of aggregates against the raw rows beneath them was the hard part, and
 * separate tables remove the question entirely rather than answering it carefully.
 *
 * What remains is the shape a response arrives in, and the *meaning* of each field, which no schema
 * can carry. Kept in this file rather than folded into [RequestDb] so the two reasons a field is
 * nullable survive next to the fields themselves.
 *
 * **Every field is nullable on purpose.** A provider that omits `usage`, an answer that never
 * arrived, a ping whose body was discarded — all of these are real and none of them is a zero.
 * Writing a zero where the truth is "not reported" would make the record lie in the one direction
 * that matters, because a zero cost is exactly what a cached response looks like.
 */
object RequestLog {

    /** The folder inside the system project, where the database lives. */
    const val DIR = "requests"

    /**
     * What one response cost, as the provider reported it.
     *
     * **Every field is nullable on purpose.** A provider that omits `usage`, an answer that never
     * arrived, a ping whose body was discarded — all of these are real and none of them is a zero.
     * Writing a zero where the truth is "not reported" would make the log lie in the one direction
     * that matters, because a zero cost is exactly what a cached response looks like.
     */
    data class Stats(
        val kind: Kind,
        val ms: Long,
        val provider: String,
        val model: String,
        val usage: JSONObject?,
        val finish: String?,
        /**
         * How much had arrived when the stream died, for a stall.
         *
         * Null for a finished request, where `usage` is the better answer. For a stall this is the
         * only evidence there is: it is what distinguishes a connection that died mid-answer from
         * one that never started, and it is the closest thing to the billed amount that exists.
         */
        val received: Received? = null,
    ) {
        /** What a stalled stream had produced, in the two things the app counts as it arrives. */
        data class Received(val said: Int, val thinking: Int)
        enum class Kind(val label: String) {
            /** A turn: the person spoke and the answer was read. */
            TURN("turn"),

            /** The keepalive: the conversation re-sent for the provider's cache. */
            PING("ping"),

            /**
             * An answer that began and then stopped arriving.
             *
             * **A row of its own, and the reason is money.** A refusal or an ack timeout produces
             * no output, and OpenRouter's zero-completion insurance means it is not billed - so
             * those leave no row and the table stays honest. A stall is not that: the model had
             * already generated tokens, and whether they were billed depends on whether the
             * upstream supports cancellation. Some do and some do not, and for the ones that do
             * not, the whole generation is charged after the connection dies.
             *
             * So this row is written with `usage` absent, which leaves every token and cost column
             * blank rather than showing a zero. A zero would say "free", and the honest answer is
             * "we do not know" - and a blank `reasoning` would say "nothing was produced", when
             * what is true is that something was, we just never received the accounting for it.
             */
            STALL("stall"),
        }

        val promptTokens: Int? get() = usage?.optInt("prompt_tokens")?.takeIf { usage.has("prompt_tokens") }
        val completionTokens: Int? get() = usage?.optInt("completion_tokens")?.takeIf { usage.has("completion_tokens") }
        val cost: Double? get() = usage?.optDouble("cost")?.takeIf { usage.has("cost") }

        /** The part of the prompt the provider did not have to read, when it says. */
        val cachedTokens: Int?
            get() = usage?.optJSONObject("prompt_tokens_details")
                ?.let { if (it.has("cached_tokens")) it.optInt("cached_tokens") else null }

        /**
         * How much of the answer was thinking.
         *
         * **This is the column the keepalive needs.** A ping asks for one token, so a ping whose
         * reasoning is large is a provider that did not honour the cap — and that is invisible in
         * every other number here, because the completion count stays small.
         */
        val reasoningTokens: Int?
            get() = usage?.optJSONObject("completion_tokens_details")
                ?.let { if (it.has("reasoning_tokens")) it.optInt("reasoning_tokens") else null }
    }

}
