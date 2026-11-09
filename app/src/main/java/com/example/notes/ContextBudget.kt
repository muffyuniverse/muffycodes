package com.example.notes

/**
 * How big the working context may get: **three numbers the operator typed**, and an estimate of how
 * much text costs.
 *
 * ### There is no ceiling, and this file used to have one
 *
 * It carried a table of five measured pins, a four-step ladder that derived a ceiling from a model's
 * window and the reply it reserved, and a clamp that held each number to `min(asked, ceiling)`. All of
 * that is gone, on the operator's instruction: *"no need for capping or using relative size numbers —
 * what user wrote is what must be used. And this way we don't need to know or display context and
 * reply ceiling of a model because they don't affect anything"*.
 *
 * The reason it was there was arithmetic the app could not do well: a window is input plus the reply
 * it reserves, and the app had to guess the split. The reason it is gone is that the guess was never
 * the app's to make — a person setting up a model knows its window, types numbers that fit inside it,
 * and if they are wrong the server says so in its own words at the first request that is too long.
 * **A refusal a person can read is better than a clamp they cannot see.**
 *
 * What that deletes is worth naming, because it was not only a guard: the pin table, the ladder, the
 * clamp, the two fields that fed it (`ActiveModel.contextLength`/`maxOutput`), the learning of a
 * window from a catalogue after a model is chosen, the "this pin allows 105k" line on the settings
 * screen, and the sentence the app put in the agent's own prompt — *"this endpoint takes 918k tokens
 * of input"* — which told the model a number the app no longer enforces.
 *
 * ### The two hands, and the numbers they use
 *
 * [Limits] is three numbers: `forget` is when the agent is asked to clear its context up, `trim` is
 * when the app cuts it, and `target` is where both aim. **Each is used exactly as written.** Zero
 * means that number is not set — the hand is off — and there is no derivation from anything, because
 * there is nothing to derive from and no share of it to take.
 *
 * ### The estimate, which is the only number this file derives
 *
 * [tokens] is an estimate and every place it is shown says so. It exists because something has to
 * decide *when* a context is over a number, and the app cannot tokenize: a tokenizer is a model's
 * vocabulary, it is not in this APK, and downloading one would be a second failure mode in the path
 * that has to work on a train.
 */
object ContextBudget {

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
     * What one picture costs, from its **pixels** — never from its encoded length.
     *
     * **The two numbers that would both be wrong.** A data URL is base64, a third larger than the
     * bytes it carries, so counting the encoding overcharges every picture by 33%. And the charge is
     * not proportional to the file at all: a 4 MB photo and a 200 kB one cost the same, because the
     * provider resizes both before it looks at them.
     *
     * **So the arithmetic is the provider's own**, published and measured: resize into a 2048×2048
     * box keeping the aspect ratio, then resize again so the **shortest side is 768**, then charge
     * 85 tokens plus 170 for each 512×512 tile, a partial tile counted as a whole one.
     *
     * **Portrait costs more than landscape, and that is not a bug.** The shortest side is pinned, so a
     * tall picture keeps its height: 768×2048 is four tiles across and four down, 1,445 tokens, where
     * a landscape 1024×768 is four tiles in all, 765. Any rule that capped the *long* edge would make
     * a portrait picture cost **more** by shrinking it into a worse tile count.
     *
     * Like [tokens], this is a **policy** number and not a bill — a provider that tiles differently
     * charges differently — which is why every place it is shown says "about".
     */
    fun pictureTokens(width: Int, height: Int): Long {
        if (width <= 0 || height <= 0) return 0L
        val (w, h) = fitted(width, height)
        val tiles = ceilDiv(w, TILE) * ceilDiv(h, TILE)
        return PICTURE_BASE + PICTURE_PER_TILE * tiles
    }

    /**
     * The size a picture is actually charged at: the provider's two resize steps, applied here.
     *
     * **Done in the app so the estimate and the wire agree.** Sending a 4000×3000 photo means the
     * provider shrinks it anyway; doing the same shrink first makes the number shown to a person the
     * number that will be billed, and cuts the request from megabytes to a couple of hundred
     * kilobytes — which is the difference between a request that works on a train and one that times
     * out.
     */
    fun fitted(width: Int, height: Int): Pair<Int, Int> {
        var w = width
        var h = height
        // Step 1: inside the 2048 box, aspect kept. Only ever shrinks.
        val longest = maxOf(w, h).toDouble()
        if (longest > FITTED_BOX) {
            val scale = FITTED_BOX / longest
            w = Math.round(w * scale).toInt()
            h = Math.round(h * scale).toInt()
        }
        // Step 2: the shortest side at 768. Only ever shrinks, so a small picture is left alone.
        val shortest = minOf(w, h).toDouble()
        if (shortest > SHORT_SIDE) {
            val scale = SHORT_SIDE / shortest
            w = Math.round(w * scale).toInt()
            h = Math.round(h * scale).toInt()
        }
        // Rounding can take a very thin picture's short side to zero; one pixel is the floor.
        return maxOf(w, 1) to maxOf(h, 1)
    }

    private fun ceilDiv(value: Int, by: Int): Int = (value + by - 1) / by

    /** The provider's resize box: a picture larger than this is scaled into it first. */
    const val FITTED_BOX = 2048.0

    /** The shortest side a picture is charged at, after the second resize step. */
    const val SHORT_SIDE = 768.0

    /** One tile's size and charge, and the flat framing charge around all of them. */
    const val TILE = 512
    const val PICTURE_BASE = 85L
    const val PICTURE_PER_TILE = 170L

    /**
     * **The three numbers, exactly as the operator wrote them.**
     *
     * No `asked`/`inForce` pair, because there is nothing to clamp them against any more; no
     * derivation, because a derived number is one the person did not write and cannot predict; and
     * no `require`, which is the part worth saying out loud. The old class refused a target above
     * either trigger by throwing from its own constructor — and its constructor ran inside a
     * composable, so **one digit typed into a settings field was an app that would not open**, and
     * it died again on every relaunch because the digit was already saved.
     *
     * What replaces the refusal is the thing the operator asked for: a number that is wrong is used,
     * the request it produces is refused by the server, and the sentence that comes back says why.
     *
     * **Zero means "not set".** For a trigger or a trim that is the hand being off. For the target it
     * is "no size to name": the ask is then simply *cut what does not serve the task*, and a trim
     * with no target keeps only the newest burst — both of which are things the app already did when
     * there was no number, so neither is a new meaning.
     */
    data class Limits(val triggerAt: Long, val hardAt: Long, val targetAt: Long) {
        companion object {
            fun of(trigger: Long, hard: Long, target: Long = 0L): Limits =
                Limits(triggerAt = trigger, hardAt = hard, targetAt = target)
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
