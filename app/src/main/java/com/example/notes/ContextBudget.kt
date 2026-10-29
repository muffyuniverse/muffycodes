package com.example.notes

/**
 * How big the working context may get, in the operator's numbers and the pin's ceiling.
 *
 * Round 5's §1.8: **two limits, both token counts, both the operator's.** `trigger` is when the
 * agent is told to clear its context up; `hard` is when the app trims it. `hard` is off by
 * default, because an app that silently cuts a conversation is an app that loses the thing the
 * person was working on — and the agent is the one supposed to be choosing what to forget.
 *
 * ### The clamp, and the number it must not eat
 *
 * A ceiling depends on the **pinned endpoint**, not on the model: `cloudflare` allows a 943,718
 * token reply out of a 1,048,576 window, so its input ceiling is 104,858, while `deepinfra/fp8`
 * allows 917,504. Switching a pin can move the ceiling by six times — which is why [asked] and
 * [inForce] are two different numbers:
 *
 * - [asked] is what the operator typed, and is never written back;
 * - [inForce] is `min(asked, ceiling)`, what the agent is actually held to.
 *
 * A clamp that wrote back would mean that looking at a small model once destroys the number you
 * set for a big one, and §2.3 of the design calls that out as a trap rather than a detail.
 *
 * ### Why the ceiling is a table
 *
 * The API cannot answer this. `/endpoints` reports `max_prompt_tokens: null` for every endpoint
 * of the model this app ships with, so a ceiling derived only from live data would be a number
 * nobody has. The five pins the design measured are therefore written down, and anything else
 * falls through to the catalogue's own numbers, then to a share of the window, then to a floor —
 * a ladder that always produces a number and never pretends to more certainty than it has.
 */
object ContextBudget {

    /**
     * The pins the design measured, as their input ceiling.
     *
     * `1,048,576 − max output`, per §1.8's table. Written out as the answer rather than as the
     * subtraction, so a reader can compare this with the design without doing arithmetic.
     */
    val CEILINGS = mapOf(
        "deepinfra/fp8" to 917_504L,
        "deepinfra/fp8/flex" to 917_504L,
        "decart/fp4" to 786_432L,
        "deepseek" to 655_360L,
        "cloudflare" to 104_858L,
    )

    /** The floor for a pin nobody measured: below this no agent conversation fits. */
    const val UNKNOWN_CEILING = 96_000L

    /** How much of a window the fallback assumes a reply needs. */
    private const val REPLY_SHARE = 4

    /**
     * The input ceiling for [pin], given the model's own window.
     *
     * Four steps, most certain first: **the measured pin** (a fact about that endpoint, which is
     * deliberately allowed to be tighter than the model's own window — `cloudflare` is), then the
     * pin's own live numbers (window minus the largest reply it promises), then three quarters of a
     * known window, then the floor. Each step is only reached when the one above it has nothing to
     * say.
     *
     * **The floor is the last resort and must stay rare.** It is reached only when the pin is not
     * measured *and* the model's window is unknown — and it used to be reached for every pin the
     * table did not name, because nobody passed a window: a million-token model was trimmed at
     * 96,000 while the screen promised its real size. The window now travels with the choice
     * ([ActiveModel.contextLength]) so both callers can pass it.
     */
    fun ceiling(pin: String, contextLength: Int? = null, maxOutput: Int? = null): Long {
        CEILINGS[pin]?.let { return it }
        if (contextLength != null && maxOutput != null && maxOutput < contextLength) {
            return (contextLength - maxOutput).toLong()
        }
        if (contextLength != null && contextLength > 0) {
            return contextLength.toLong() / REPLY_SHARE * (REPLY_SHARE - 1)
        }
        return UNKNOWN_CEILING
    }

    /**
     * How much of one result may enter the conversation, in characters — asked, and in force.
     *
     * **The input window, in the unit the field is typed in** — and it is typed in **tokens**, the
     * same unit as every limit beside it. It used to be characters, which made the row a person
     * types in one unit and the wall it is clamped by a second: the text had to convert 20,000
     * characters to "5.7k tokens" in its head every time, and the clamp was `window × 4` bytes of a
     * different thing again. A result is a token count now, and the clamp is the window itself.
     *
     * A window nobody knows clamps nothing: the ask is the limit, which is what the app did before
     * this number had a ceiling.
     */
    fun resultLimit(asked: Int, contextLength: Int?): Limit = Limit(
        asked = asked.toLong(),
        ceiling = if (contextLength != null && contextLength > 0) {
            contextLength.toLong()
        } else {
            Long.MAX_VALUE
        },
    )

    /** How many tokens one result may carry, as the setting is stored: 20k by default. */
    fun resultTokens(asked: Int): Long = asked.toLong()

    /**
     * What one text costs, estimated in tokens.
     *
     * **The app cannot tokenize.** A tokenizer is a model's vocabulary, it is not shipped in this
     * APK, and downloading one to count bytes would be a second failure mode in the path that has
     * to work on a train. So the count is an estimate, and the constant is the one whose error lands
     * in the safe direction for the text this app produces.
     *
     * **3.5 bytes a token, not 4.** The app pins `deepseek/deepseek-v4.1-flash`, and DeepSeek
     * publishes its own ratio: about **0.3 token per English character**, which is one token per
     * 3.33 bytes of ASCII, and about 0.3 for code and JSON too. Dividing by 4 therefore *under*
     * counted English by 17% and code by 20-25% — the direction that says a context is smaller than
     * it is, so the clear-up fires late and a request can be refused for being too long. Dividing
     * by 3.5 errs by under 5% in that direction and over about 9% for Chinese (DeepSeek's 0.6
     * token per character is 5 bytes a token), which is early rather than long.
     *
     * The number is therefore a *policy* number, not a bill. Every place it is shown says so.
     */
    fun tokens(text: String): Long =
        // ceil(bytes / 3.5), in integers: (2 * bytes + 6) / 7. Shown rather than a floor division
        // by a constant, because 3.5 is not an integer and rounding down is the unsafe direction.
        (bytes(text) * 2L + 6L) / 7L

    /**
     * A text's exact size in bytes.
     *
     * **The exact number, and the only exact one this file offers.** A token needs the model's own
     * vocabulary — a tokenizer is per-model, this app may be pointed at any of them, and shipping
     * or downloading one to count bytes would be a second failure mode in the path that has to
     * work on a train. Bytes are free to compute, verifiable with `wc -c`, and they *rank* rows
     * exactly as tokens do: the conversion is near-linear, so "which row is biggest" has the same
     * answer in either unit while only one of them is a fact.
     *
     * So bytes are what a person is shown and what the file is measured in, and [tokens] is the
     * estimate that exists only to be compared with an operator's token limit.
     */
    fun bytes(text: String): Long = text.toByteArray().size.toLong()

    /**
     * Bytes per token, as a fact about the pinned model rather than a round number.
     *
     * `deepseek`'s own documentation gives 0.3 token per English character, which is 3.33 bytes of
     * ASCII — so this is 3.5, and the share it errs by is named where it is used. It is also the
     * unit a person is shown when a size has to be a count rather than a scale.
     */
    const val BYTES_PER_TOKEN = 3.5

    /** One text plus another, as one number. */
    fun tokens(vararg texts: String): Long = texts.sumOf { tokens(it) }

    /**
     * A limit as the operator asked for it and as it is in force.
     *
     * [asked] is stored; [inForce] is derived, every time, from the pin that is active now.
     */
    data class Limit(val asked: Long, val ceiling: Long) {
        /** What the agent is held to. Never written back over [asked]. */
        val inForce: Long get() = if (asked <= 0L) 0L else minOf(asked, ceiling)

        /** True when the pin is smaller than the number asked for, so the UI can say why. */
        val clamped: Boolean get() = asked > 0L && asked > ceiling

        /** `96k asked · 88k in force` — the two numbers, never one pretending to be the other. */
        fun said(): String = when {
            asked <= 0L -> "off"
            !clamped -> "${short(asked)} (this pin allows ${short(ceiling)})"
            else -> "${short(asked)} asked · ${short(inForce)} in force " +
                "(this pin allows ${short(ceiling)})"
        }
    }

    /**
     * The two limits in force for one pin.
     *
     * **`trigger` of zero means a quarter of the ceiling**, not "off": an agent with no warning
     * point would be told to clear up only when the app was about to trim, which is the app
     * deciding. A quarter is §2.2's own share, and it is derived from the pin rather than typed,
     * so a small pin gets a small trigger without anybody having to remember to change it.
     */
    /**
     * The three numbers: where both hands aim, and where each of them fires.
     *
     * **One target, two triggers** — the operator's correction, 2026-10-29: "both forget and trim
     * compactions should have same target but different triggers (so that forget triggers much
     * earlier)". Two independent numbers would let the app trim to a size the agent was never asked
     * to reach, or ask the agent to reach a size the app had already given up on.
     *
     * **The target is below both triggers, and that is the only ordering required.** A target at or
     * above a trigger would mean the act that fires there removes nothing: the context is already
     * under the number it is being asked to reach. So the target sits strictly below each trigger
     * that is on, and one clear-up buys real headroom rather than landing exactly on the point that
     * asked for it.
     *
     * **A trim below the clear-up point is a mode, not a mistake.** The operator: "trim compaction
     * actually can be set below forget and then it is only the app that handles the compaction (a
     * feature, not a bug) because forget is just never triggered." A person who would rather the app
     * do the cutting than have the agent choose gets exactly that by putting the trim first — the
     * agent is never asked, so it never acts, and the two hands never compete. What is still
     * refused is a trim so low that it fires without the agent ever being able to reach the target
     * first... which is that same mode, so nothing is refused for it. The ordering rule is only
     * about the target.
     */
    data class Limits(val trigger: Limit, val hard: Limit, val target: Limit) {

        init {
            // **The target must be reachable, so it is below each trigger that is on.** A trigger
            // of zero is "the app has no opinion" and is resolved from the ceiling, so it is
            // compared as the resolved number rather than as the zero.
            // **A trigger of zero is resolved from the ceiling, not "off"**, so it is never an
            // escape from this rule: `triggerAt` is a quarter of the ceiling whenever nothing was
            // typed. The check therefore has no exemption - only a pin with no window at all gives
            // a zero, and there is no target to reach against no window.
            if (triggerAt > 0L) {
                require(targetAt < triggerAt) {
                    "the clear-up target (${targetAt}) must be below the point that asks for it " +
                        "(${triggerAt}), or the ask would have nothing left to remove"
                }
            }
            require(hardAt == 0L || targetAt < hardAt) {
                "the clear-up target (${targetAt}) must be below the trim point ($hardAt), or the " +
                    "trim would fire on a context that already fits"
            }
        }

        /** The trigger in force, resolved from the ceiling when the operator left it at zero. */
        val triggerAt: Long get() = if (trigger.inForce == 0L) trigger.ceiling / 4 else trigger.inForce

        /**
         * The hard limit in force, or zero for "never".
         *
         * **Raised to the trigger, not stored that way.** What was typed stays in
         * [Limit.asked] — the number a person sees beside the field — while the number enforced is
         * the larger of the two, because a last resort that fires before the agent is asked is not
         * a last resort.
         */
        val hardAt: Long get() = if (hard.inForce == 0L) 0L else hard.inForce

        /**
         * Where both hands aim.
         *
         * **A target of zero means "half of where the clear-up is asked for"**, and it is derived
         * from [triggerAt] rather than from the ceiling for one reason: the whole value of a
         * separate target is the headroom between the two, and a default that landed on the trigger
         * would ask the agent to cut to exactly the number that asked it - so the next row to arrive
         * would cross the trigger again and the ask would repeat. Half is a floor nobody has to
         * tune: an ask at 49k aims at 24k, and one forget buys 25k of quiet.
         */
        val targetAt: Long
            get() {
                if (target.inForce > 0L) return target.inForce
                val at = triggerAt
                // A trigger of zero is a ceiling of zero - a pin with no window at all - and there
                // is no target to derive. Zero then means "nothing to reach", which is the same
                // "the app has no opinion" the trigger uses.
                return if (at <= 0L) 0L else (at / 2).coerceAtLeast(1L)
            }

        companion object {
            /**
             * Both from the operator's stored numbers and one pin's ceiling.
             *
             * **The clamp is `min(asked, ceiling)` for each, and the order is repaired rather than
             * refused**: a hard limit typed below the trigger is raised to the trigger, because the
             * alternative is an app whose last resort fires first and whose agent is never asked to
             * clear up at all. What is repaired is the effective number; what was typed stays in
             * [Limit.asked], which is what makes the screen able to say so.
             */
            fun of(
                triggerAsked: Long,
                hardAsked: Long,
                ceiling: Long,
                targetAsked: Long = 0L,
            ): Limits = Limits(
                Limit(triggerAsked, ceiling),
                Limit(hardAsked, ceiling),
                Limit(targetAsked, ceiling),
            )
        }
    }

    /**
     * A token count as a person reads it: `918k`, `105k`, `96k`.
     *
     * Thousands only, and **rounded to the nearest**, because a token count is a scale rather than
     * a quantity: `917,504` invites a reader to treat the last four digits as meaningful when they
     * are the app's own estimate, and the design's own sentence reads "this pin allows 105k" for
     * 104,858. A truncation would print 104k there and disagree with the document.
     */
    fun short(tokens: Long): String = when {
        tokens <= 0L -> "off"
        tokens >= 1_000L -> "${(tokens + 500L) / 1_000L}k"
        else -> "$tokens"
    }
}
