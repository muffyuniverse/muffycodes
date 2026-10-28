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
    fun share(value: Double?): String =
        if (value == null) NONE else String.format(Locale.UK, "%.1f%%", value * 100)

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
     * The same moment to the **hour only**: `28 Oct 2026, 19:00`.
     *
     * **The interval has no minutes, so its buttons must not show any.** `instant` renders `19:23`,
     * and using it for a picker that can only choose hours would make the button claim a precision
     * the figures do not have - and, worse, an end of `19:23` would be displayed beside a figure
     * that actually runs to `19:59:59`.
     */
    fun instantHour(ms: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String =
        java.time.Instant.ofEpochMilli(ms).atZone(zone)
            .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH':00'"))

    /**
     * An interval's length, in hours: `31 hours`, `1 hour`.
     *
     * **The column's unit**, so the figure has a scale instead of being a number with no extent.
     * Hours because that is the granularity the log keeps and the interval is measured at - a
     * count of days would be wrong for a two-hour window and a count of seconds unreadable for a
     * month.
     */
    fun hours(count: Long): String = "$count ${if (count == 1L) "hour" else "hours"}"

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
