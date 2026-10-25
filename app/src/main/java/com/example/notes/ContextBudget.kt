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
     * The input ceiling for [pin], given whatever the catalogue knows.
     *
     * Four steps, most certain first: the measured table, then the pin's own live numbers
     * (context minus the largest reply it promises), then three quarters of a known window, then
     * the floor. Each step is only reached when the one above it has nothing to say.
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
     * What one text costs, estimated in tokens.
     *
     * **The app cannot tokenize.** A tokenizer is a model's vocabulary, it is not shipped in this
     * APK, and downloading one to count bytes would be a second failure mode in the path that has
     * to work on a train. So the count is an estimate, and the direction of the error is chosen
     * deliberately: `bytes / 4`, rounded **up**, because English and code average about four
     * bytes per token and rounding up means the app asks the agent to clear up slightly early
     * rather than sending a request the endpoint refuses for being too long.
     *
     * The number is therefore a *policy* number, not a bill. Every place it is shown says so.
     */
    fun tokens(text: String): Long = (text.toByteArray().size + BYTES_PER_TOKEN - 1) / BYTES_PER_TOKEN

    /** Four bytes per token: the estimate's own constant, named so the arithmetic reads. */
    const val BYTES_PER_TOKEN = 4L

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
    data class Limits(val trigger: Limit, val hard: Limit) {

        /** The trigger in force, resolved from the ceiling when the operator left it at zero. */
        val triggerAt: Long get() = if (trigger.inForce == 0L) trigger.ceiling / 4 else trigger.inForce

        /** The hard limit in force, or zero for "never". */
        val hardAt: Long get() = hard.inForce

        companion object {
            /** Both from the operator's stored numbers and one pin's ceiling. */
            fun of(triggerAsked: Long, hardAsked: Long, ceiling: Long): Limits =
                Limits(Limit(triggerAsked, ceiling), Limit(hardAsked, ceiling))
        }
    }

    /**
     * A count as a person reads it: `918k`, `105k`, `96k`.
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
