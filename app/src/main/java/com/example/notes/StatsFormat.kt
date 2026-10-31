package com.example.notes

import java.util.Locale

/**
 * How a number in the statistics table is written.
 *
 * **Precision is a decision about what a reader can act on, not a matter of taste.** The rule used
 * throughout: show enough digits that two different values print differently, and no more. Cost is
 * the case that forces this — a turn costs about $0.0001 and a ping about $0.000024, so a fixed
 * three decimals would print both as "0.000" and a fixed five would print the turn as "0.00010",
 * losing the difference between a cheap turn and an expensive one. Costs are therefore written to
 * **five decimals**, and the two derived rates — which are the smallest numbers on the screen — to
 * five as well, because they are the ones a person compares across buckets.
 *
 * Shares are a percentage and one decimal is enough: the difference between 95.1% and 95.2% cached
 * is not a decision, while the difference between 95% and 5% is. They are printed with one decimal
 * so that "0.0%" and "not measured" can still be told apart by the reader.
 */
object StatsFormat {

    /** A dash for a number that does not exist, which is not the same as zero. */
    const val NONE = "—"

    /**
     * A token count, grouped, with a short suffix past a thousand.
     *
     * Grouping because a table of six-figure token counts is unreadable without it, and a suffix
     * because "1,328,760" in a 60dp column becomes an ellipsis — which is the one thing a table
     * cannot afford. The exact number is still in the log, which is where an exact number belongs.
     */
    /**
     * A count of **characters**, with the unit said out loud: `2.1k chars`.
     *
     * **The unit is not decoration, and leaving it off was a defect.** A stalled answer has no
     * `usage` block - the accounting arrives at the end of a stream that never ended - so the only
     * thing known about it is how many characters had arrived. That made the stall block count
     * characters while the table above it counts tokens, and both went through [tokens], so
     * `output 2.4k` (tokens) and `written 2.1k` (characters) looked like the same measurement two
     * rows apart. They differ by roughly a factor of four on this project's own text.
     *
     * Keeping characters is right - it is what is known, and a token count invented from them would
     * be a guess dressed as a measurement. Saying so is what makes the two blocks readable together.
     */
    fun characters(value: Long): String = when {
        value < 1_000 -> "$value chars"
        value < 1_000_000 -> String.format(Locale.UK, "%.1fk chars", value / 1_000.0)
        else -> String.format(Locale.UK, "%.1fM chars", value / 1_000_000.0)
    }

    fun tokens(value: Long): String = when {
        value < 1_000 -> value.toString()
        value < 1_000_000 -> String.format(Locale.UK, "%.1fk", value / 1_000.0)
        else -> String.format(Locale.UK, "%.1fM", value / 1_000_000.0)
    }

    /**
     * A share, as a percentage.
     *
     * One decimal, and [NONE] rather than "0.0%" when there is no denominator: a bucket with no
     * requests did not have a cache hit rate of zero, it had no requests.
     */
    /**
     * A share of a whole, as a percentage with **three decimals**.
     *
     * Three, not one, because a cache share is the number a person reads to decide whether caching is
     * working: `97.1%` cannot distinguish 97.05% from 97.149%, and the difference between "almost all
     * of it" and "all but one request in two thousand" is exactly what the screen is being consulted
     * for. One decimal rounds that distinction away.
     *
     * The trade is width - `97.100%` is six characters where `97.1%` was four - and it is worth it
     * here because the share rides in the same cell as its absolute (`137k (97.100%)`), so the
     * absolute keeps its own column width intact and only the parenthesised part grows.
     *
     * `null` is not zero: a span with no input tokens has no share, which is a different fact from a
     * share of 0%, and the screen prints a dash for it.
     */
    fun share(value: Double?): String =
        if (value == null) NONE else String.format(Locale.UK, "%.2f%%", value * 100)

    // **Two decimals here, none in [change], and the difference is deliberate.**
    //
    // The operator asked for both, separately, and the wording was specific each time: *"add cached %
    // line as share of total input with .00% precision"* for a share, and *"no decimals for percents"*
    // for a change. They are different numbers and they answer different questions:
    //
    //   a share  — what fraction of this period's input was served from cache. Two decimals, because
    //              97.10% and 97.05% are different facts about how well the cache is working, and the
    //              whole point of the line is to watch it move.
    //   a change — how much a figure moved between two periods. No decimals, because it is a ratio of
    //              two numbers already rounded on screen, and a column of `+18.2%` is a column of
    //              digits to read past.
    //
    // **Do not unify them.** A rule applied to "percents" that treats these the same would take the
    // precision out of the one line whose purpose is precision.

    /**
     * A cost, in dollars, to **five decimals**.
     *
     * Five because that is the precision at which this app's individual responses become
     * distinguishable: a ping is about $0.00002 and a turn about $0.00010, and anything coarser
     * prints both as the same number. A bucket's total can be much larger, so the formatter widens
     * itself rather than padding — a total of $1.50 should read "1.50000" and not "1.5".
     */
    fun cost(value: Double?): String =
        if (value == null) NONE else String.format(Locale.UK, "%.5f", value)

    /**
     * A cost per million tokens, to **five decimals**.
     *
     * The smallest numbers on the screen and the ones most worth comparing: this is what the cache
     * actually saved, folded into a rate. At a dollar a million, five decimals resolves to a tenth
     * of a cent per million — finer than any difference a person would act on, which is the point
     * at which more precision stops being information.
     */
    fun perMillion(value: Double?): String =
        if (value == null) NONE else String.format(Locale.UK, "%.5f", value)

    /**
     * A cost per request, to **five decimals**.
     *
     * Same reasoning as [cost]: a ping per request is $0.00002, and three decimals would print it
     * as nothing at all.
     */
    fun perRequest(value: Double?): String =
        if (value == null) NONE else String.format(Locale.UK, "%.5f", value)

    /**
     * An absolute number **and the share it is of, in one cell**: `24.0k (91.7%)`.
     *
     * The operator asked for "absolute + share in percents as one cell", and the reason is that
     * they are one fact: 24.0k of what, answered by the number in the brackets. Drawing them as
     * two rows - which is what this screen did first - puts the share on a line of its own, where
     * it reads as a separate measurement that happens to sit underneath.
     *
     * Either half may be absent, and absence is [NONE] rather than a zero: a bucket with no
     * requests has neither an absolute nor a share, and one with tokens but no cache has an
     * absolute and no share.
     */
    fun absoluteAndShare(absolute: String?, share: Double?): String = when {
        absolute == null && share == null -> NONE
        share == null -> absolute ?: NONE
        absolute == null -> share(share)
        else -> "$absolute (${share(share)})"
    }

    /**
     * The chosen instant, as a caption: `25 Oct 2026, 12:00`.
     *
     * In the device's zone, because it is what the picker set and what the three columns are
     * derived from - and a person comparing the caption against the picker must not have to convert.
     */
    fun instant(ms: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String =
        java.time.Instant.ofEpochMilli(ms).atZone(zone)
            .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm"))

    /**
     * A picker's instant, to the minute: `28 Oct 2026, 19:23`.
     *
     * **Separate from [instant] because the two answer different questions**, even though both
     * print `HH:mm` now that minutes can be chosen. This one is a *label for a choice* - the thing
     * the person picked - and [instant] is a *label for a fact* - where the log begins. They are
     * allowed to diverge (one might gain a seconds field, or a relative form), and naming them
     * apart is what keeps that a decision rather than a coincidence.
     */
    fun instantMinute(ms: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String =
        java.time.Instant.ofEpochMilli(ms).atZone(zone)
            .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm"))

    /**
     * A dotted date and a time: `30.10.2026, 03:00`.
     *
     * **Its own format because the operator named it.** "format for time and date is '30.10.2026,
     * 03:00'". The other instants on this screen say `30 Oct 2026` because that reads well in a
     * sentence; the two lines that bracket an interval are a pair of values a person compares
     * against a clock, and day-first dots are what a numeric date is read as.
     *
     * **Day before month, unambiguously.** `03.10` and `10.03` are different days and one of them
     * means October in one country and March in another; the year in full is what makes the order
     * readable rather than guessed.
     */
    fun dotted(ms: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String =
        java.time.Instant.ofEpochMilli(ms).atZone(zone)
            .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm"))

    /**
     * An interval's length: `123 days 15 hours 51 minutes`, `14 hours 31 minutes`, `15 minutes`.
     *
     * **Days, hours and minutes, each shown when it is not zero.** That is how a duration is read
     * out loud, and it keeps the figure exact at every size: `871 minutes` is unreadable, `14 hours`
     * hides the half hour that is the point, and `14.52 hours` is not how anybody says it.
     *
     * **A zero is dropped rather than padded.** `1 day 5 minutes` says what happened; `1 day 0
     * hours 5 minutes` makes a reader check a number that carries nothing. The one exception is an
     * interval of no length at all, which reads `0 minutes` rather than nothing.
     *
     * **Pluralised properly**, because "1 hours" is the kind of thing a screenshot catches and an
     * assertion written by the same person does not.
     */
    fun span(minutes: Long): String {
        fun n(count: Long, one: String, many: String) = "$count ${if (count == 1L) one else many}"
        val days = minutes / 1440
        val hours = (minutes % 1440) / 60
        val mins = minutes % 60
        val parts = buildList {
            if (days > 0) add(n(days, "day", "days"))
            if (hours > 0) add(n(hours, "hour", "hours"))
            // **Minutes always appear when nothing larger does**, so an empty interval still says
            // something rather than rendering as an empty string.
            if (mins > 0 || isEmpty()) add(n(mins, "minute", "minutes"))
        }
        return parts.joinToString(" ")
    }

    /**
     * A relative change: `+18.2%`, `-4.0%`, `-100.0%`.
     *
     * **One decimal, not three.** A change is a comparison, and a comparison that claims 18.243%
     * precision invites arithmetic on two numbers that are each already rounded on screen. The
     * shares keep three decimals because they are measurements of a thing; this is a difference
     * between two of them.
     *
     * **An explicit sign on the rise**, because a bare `18.2%` beside `-4.0%` reads as two
     * magnitudes rather than as a rise and a fall. A fall carries its own minus, which is why only
     * the positive case needs one added.
     */
    fun change(value: Double?): String = when {
        // **No baseline is a dash**, the same as every other absent figure — and it is not zero. A
        // row with nothing behind it compared against nothing would read as "no change", which is a
        // measurement; the truth is that there was nothing to measure.
        value == null -> NONE
        else -> {
            // **Whole percents, rounded once.** The operator's rule: "no decimals for percents". A
            // change compares two numbers already rounded on screen, so a decimal claims a precision
            // that neither side has — and a column of `+18.2%` is a column of digits to read past.
            //
            // **Rounded before it is signed**, so a change of -0.4% prints `0%` and never `-0%`.
            val percent = Math.round(value * 100.0).toLong()
            when {
                // **Past 99% is a cap, not a ratio.** The operator's rule: "if change is big then
                // just show >99%". A change column reading `+1240%` reports a ratio when what a
                // person needs is a direction and a magnitude — and the further past 100 it goes the
                // less the digits mean, because the previous period held almost nothing and the
                // percentage is dominated by how small the denominator is.
                //
                // **Only the rise needs a cap, because only a rise has no ceiling.**
                //
                // The operator asked the question that settles it: *"is it possible to fall more than
                // -100%?"* It is not. These are counts, tokens and money — all non-negative — so the
                // smallest change is "everything went to zero", which is -100%. A fall is bounded by
                // the quantity itself; a rise is bounded by nothing.
                //
                // Which means the branch that used to be here (`percent < -99L -> "-100%"`) was dead
                // code that duplicated the ordinary path: it could only fire at exactly -100%, where
                // the arithmetic already prints `-100%`. **A cap on a value that cannot exceed its
                // limit is a second rule for a fact the first one already states**, and it invites
                // the reader to wonder what happens beyond it.
                percent > 99L -> ">99%"
                percent > 0L -> "+$percent%"
                else -> "$percent%"
            }
        }
    }

    /** A request count. */
    fun count(value: Int): String = value.toString()

    /**
     * A day, for a tap's own record and for the caption.
     *
     * In the device's zone: the picker, the file and the table all speak local days, and the one
     * number that does not is the UTC instant in the log.
     */
    fun day(ms: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String =
        java.time.Instant.ofEpochMilli(ms)
            .atZone(zone)
            .format(java.time.format.DateTimeFormatter.ofPattern("dd MMM yyyy"))
}
