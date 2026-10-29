package com.example.notes

import java.time.LocalDate
import java.time.ZoneId

/**
 * The request logs, summed into a table.
 *
 * **Why the shapes are their own object.** The screen draws numbers; the question of *which* numbers
 * belong in a bucket is arithmetic that can be wrong in ways a picture will not show — a cache
 * counted as well as the input it is part of, a ping blended into a turn, an average taken over
 * buckets that have no requests in them. The *summing* itself moved to SQL ([RequestDb]), because
 * adding up a year of rows is what a database is for; what is left here is the meaning of each
 * number and the arithmetic of an interval, in functions of their arguments, testable without a
 * screen and without a database.
 *
 * **Every number is nullable and nothing is invented.** A bucket with no requests has no average
 * cost per request; that is not zero, it is a division that did not happen. The screen prints a
 * dash for it, and the difference matters because "0.000000" is what a free bucket would look
 * like too.
 */
object RequestStats {

    /**
     * **An interval, half-open: `fromMs` inclusive, `toMs` EXCLUSIVE.**
     *
     * Two pickers state it: *"we need 2 pickers for starting and ending, and just 1 result column
     * for the selected interval."* Two pickers remove the whole question of where a column starts
     * and stops - the person states the interval, which is the thing they actually know.
     *
     * **Granularity is the minute.** The log keeps a minute table, so a minute is the finest thing
     * that can be *answered from an aggregate* rather than from raw rows - and a person asking what
     * the last twenty minutes cost is asking a real question. Anything finer would be a figure the
     * summaries cannot serve.
     */
    data class Interval(val fromMs: Long, val toMs: Long) {
        /** How many whole minutes it covers. An empty interval is zero, never negative. */
        val minutes: Long get() = if (toMs <= fromMs) 0 else (toMs - fromMs) / 60_000L

    }

    /**
     * The interval between two picked minutes, **snapped to whole minutes and ordered**.
     *
     * **The end is a boundary, not a minute to contain.** That distinction is the whole of it: from
     * 19:00 to 20:00 is **sixty** minutes - the hour beginning at 19 - and 20:00 is where it stops.
     * Treating the end as a minute of its own made 19-to-22 four hours, and made the To button read
     * `19:00` while meaning through 19:59.
     *
     * **The earlier pick is floored**, so 19:23:40 starts at 19:23 rather than dropping the first
     * forty seconds of a period the person asked for. **The later pick is rounded up to the next
     * boundary**, so 19:23:40 ends at 19:24 rather than cutting the last minute short. Rounding
     * outward means the figure always covers at least what was asked for - the safe direction,
     * since a total that quietly excluded the end of a chosen minute would look like lost requests.
     * A pick already on a boundary stays put as a start and moves up as an end, which is exactly
     * what makes "from 19:00 to 20:00" sixty minutes and not sixty-one.
     *
     * **Order does not matter.** A person moves whichever picker is nearest their thumb, and a table
     * that went blank when the two were swapped would look broken rather than empty.
     */
    fun interval(a: Long, b: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Interval {
        // **Whole CLOCK minutes, not whole UTC minutes.** The picker states a wall-clock time, and
        // what it must snap to is that time on the same wall. A UTC floor lands on a local boundary
        // only where the zone's offset is a whole number of minutes, and while every real zone is,
        // keeping the distinction costs nothing and stops the two floors being confused: the log's
        // tables are keyed by UTC buckets on purpose (see `hourFloor`), because an aggregate must
        // not be frozen at the local shape compaction happened to see.
        val from = localMinuteFloor(minOf(a, b), zone)
        val high = localMinuteFloor(maxOf(a, b), zone)
        // **The end is the next local minute boundary**, computed on the local timeline rather than
        // by adding a UTC minute: across a DST change a wall-clock minute is not always 60,000 ms.
        val to = if (maxOf(a, b) == high) {
            high
        } else {
            java.time.Instant.ofEpochMilli(high).atZone(zone)
                .plusMinutes(1).toInstant().toEpochMilli()
        }
        return Interval(from, to)
    }

    /**
     * The start of the **wall-clock** minute [ms] falls in, in [zone].
     *
     * Distinct from the UTC floors, which divide the epoch because the *log* stores UTC buckets.
     * Keeping the two apart is what stops a picker's stated time from silently meaning a different
     * instant: getting it wrong is silent, because the interval is simply the wrong minute and the
     * label agrees with itself.
     */
    fun localMinuteFloor(
        ms: Long,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): Long = java.time.Instant.ofEpochMilli(ms).atZone(zone)
        .truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
        .toInstant().toEpochMilli()

    /**
     * The boundary just after the **minute containing** [ms] - i.e. where "up to and including now"
     * stops.
     *
     * **Distinct from `interval(now, now).toMs`, which is zero-length on an exact boundary.** The
     * screen's default interval is "midnight to now", and on the one tick where the clock lands
     * exactly on a minute the two coincide, so that expression yields an empty interval and the
     * screen opens showing nothing. This always moves forward by a whole minute.
     *
     * Computed on the local timeline rather than by adding 60,000 ms, so a zone that ever shifted
     * by seconds cannot make the answer disagree with the label the picker prints.
     */
    fun endOfMinute(
        ms: Long,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): Long = java.time.Instant.ofEpochMilli(localMinuteFloor(ms, zone)).atZone(zone)
        .plusMinutes(1).toInstant().toEpochMilli()

    /**
     * The start of the **wall-clock** hour [ms] falls in, in [zone].
     *
     * Kept because the picker's hour stepper snaps on it, and because a minute floor that ignored
     * the hour would be right by accident in every whole-hour zone and wrong nowhere obvious.
     */
    fun localHourFloor(
        ms: Long,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): Long = java.time.Instant.ofEpochMilli(ms).atZone(zone)
        .truncatedTo(java.time.temporal.ChronoUnit.HOURS)
        .toInstant().toEpochMilli()

    /**
     * **The one column.** Everything the log holds for one interval, as one set of numbers.
     *
     * There is no label to carry and no second column to line up with: the interval is stated by
     * the two pickers, and these are its numbers.
     */
    data class Totals(
        /** Everything accounted for in the interval: turns and keepalives together. */
        val total: Stats = Stats(),
        /** Answers that began and then died. Billed and unreported, so they are counted apart. */
        val stalls: Stats = Stats(),
    )

    /**
     * What one kind of request did over one span.
     *
     * `requests` is a count and the rest are sums; the three rates are computed on demand and are
     * null when the division has no denominator, which is the distinction described above.
     */
    data class Stats(
        val requests: Int = 0,
        val promptTokens: Long = 0,
        val cachedTokens: Long = 0,
        val completionTokens: Long = 0,
        val reasoningTokens: Long = 0,
        val cost: Double = 0.0,
        /** How many responses actually reported a cost, so an average is over the right count. */
        val costReported: Int = 0,
        /**
         * What a stalled answer had produced, in characters.
         *
         * **Only stalls have these**, which is why there is no share and no average: they are the
         * closest thing to a billed amount for a request whose accounting never arrived. Zero for
         * every other kind, where `completion` is the better answer.
         */
        val saidChars: Long = 0,
        val thinkingChars: Long = 0,
    ) {
        operator fun plus(other: Stats) = Stats(
            requests = requests + other.requests,
            promptTokens = promptTokens + other.promptTokens,
            cachedTokens = cachedTokens + other.cachedTokens,
            completionTokens = completionTokens + other.completionTokens,
            reasoningTokens = reasoningTokens + other.reasoningTokens,
            cost = cost + other.cost,
            costReported = costReported + other.costReported,
            saidChars = saidChars + other.saidChars,
            thinkingChars = thinkingChars + other.thinkingChars,
        )

        /**
         * The share of the prompt the provider did not have to read.
         *
         * **Cache is a part of input, not a category beside it.** `cached_tokens` is a subset of
         * `prompt_tokens`, so showing it as a third absolute column would count the same tokens
         * twice. It is a share here for that reason.
         */
        val cachedShare: Double? get() = if (promptTokens == 0L) null else cachedTokens.toDouble() / promptTokens

        /** The share of the answer that was thinking rather than text. */
        val thinkingShare: Double? get() = if (completionTokens == 0L) null else reasoningTokens.toDouble() / completionTokens

        /** What one request cost, averaged over the responses that reported a cost. */
        val perRequest: Double? get() = if (costReported == 0) null else cost / costReported

        /**
         * The price of a million prompt tokens, as this span actually paid it.
         *
         * **Not the same as the endpoint's advertised rate**, and that is the useful part: it is
         * what the cache saved folded in. A span with a warm cache pays less per million than one
         * that built it from cold, and this is the number that shows that.
         */
        val perMillionInput: Double? get() = if (promptTokens == 0L) null else cost / promptTokens * 1_000_000

        /** The same for output, which is priced several times higher than input on most models. */
        val perMillionOutput: Double? get() = if (completionTokens == 0L) null else cost / completionTokens * 1_000_000
    }

    /**
     * One of the fixed intervals the screen offers above the table.
     *
     * **Two families, and the difference is where the interval STARTS.**
     *
     * *Clock* intervals snap the start to the grid: zero minutes, zero hour of the day, zero day of
     * the month, zero month of the year - whichever boundary the length implies. So "1 day" on the
     * clock is today from midnight, and "1 year" is this year from 1 January.
     *
     * *Rolling* intervals do not snap anything: the start is simply [now] minus the length. So "1
     * day" rolling is the last twenty-four hours, whatever time it is.
     *
     * **Both end at the moment the screen was opened.** That is what makes the two families
     * comparable: the same end, two different starts, and the difference between them is exactly the
     * partial period the clock interval includes.
     *
     * **Total is a member rather than a special case**, because a person who wants "everything" is
     * asking the same shape of question as one who wants "the last week" - and putting it on its own
     * would need its own button, its own selected state and its own branch in the caption.
     */
    enum class Preset(
        val label: String,
        /** How far back it reaches, in calendar terms. Null for [TOTAL]. */
        private val back: Back?,
    ) {
        MINUTES_15("15 min", Back.Duration(15 * 60_000L)),
        HOUR_1("1 hour", Back.Duration(3_600_000L)),
        HOURS_6("6 hours", Back.Duration(6 * 3_600_000L)),
        DAY_1("1 day", Back.Days(1)),
        WEEK_1("1 week", Back.Days(7)),
        MONTH_1("1 month", Back.Months(1)),
        QUARTER_1("1 quarter", Back.Months(3)),
        YEAR_1("1 year", Back.Months(12)),

        /** Everything the log holds. The only preset whose start is not derived from [now]. */
        TOTAL("total", null),
        ;

        /**
         * The interval this preset names, ending at [now] - **the moment the screen opened**.
         *
         * [oldest] is only consulted by [TOTAL], which cannot know where the history begins without
         * asking. Every other preset is a pure function of [now], which is what lets a test pin it.
         */
        fun intervalAt(
            now: Long,
            zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
            oldest: Long? = null,
        ): Interval {
            val b = back ?: return Interval(oldest ?: now, now)
            val start = when (b) {
                is Back.Duration -> now - b.ms
                is Back.Days -> java.time.Instant.ofEpochMilli(now).atZone(zone)
                    .minusDays(b.count.toLong()).toInstant().toEpochMilli()
                is Back.Months -> java.time.Instant.ofEpochMilli(now).atZone(zone)
                    .minusMonths(b.count.toLong()).toInstant().toEpochMilli()
            }
            return Interval(start, now)
        }

        /**
         * The same length, but **with the start snapped to the grid** - zero minutes, zero hour,
         * zero day of the month, zero month of the year, as far as the length reaches.
         *
         * This is the *clock* family. "1 day" becomes midnight today; "1 year" becomes 1 January.
         * The end is still [now], which is why a clock interval is always at least as wide as the
         * rolling one with the same name.
         */
        fun clockAt(
            now: Long,
            zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
            oldest: Long? = null,
        ): Interval {
            if (this == TOTAL) return intervalAt(now, zone, oldest)
            val at = java.time.Instant.ofEpochMilli(now).atZone(zone)
            val start = when (back) {
                // **Under an hour, the grid is the hour.** A fifteen-minute clock interval is the
                // fifteen minutes beginning at the hour's start, which is what "15 min" means when
                // the alternative - snapping to a 15-minute grid - would be a second convention.
                is Back.Duration -> at.truncatedTo(java.time.temporal.ChronoUnit.HOURS)
                // **A week is not a day.** Truncating to DAYS gave "1 week" the same start as
                // "1 day" - today's midnight - so the chip named a week and covered a few hours.
                // The clock family's rule is "the start of the current calendar period", so a week
                // begins on its first day: Monday, by the ISO week the rest of this app uses.
                is Back.Days -> if (back.count >= 7) {
                    at.with(
                        java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY),
                    ).truncatedTo(java.time.temporal.ChronoUnit.DAYS)
                } else {
                    at.truncatedTo(java.time.temporal.ChronoUnit.DAYS)
                }
                is Back.Months -> if (back.count >= 12) {
                    at.withDayOfYear(1).truncatedTo(java.time.temporal.ChronoUnit.DAYS)
                } else {
                    // **Quarters start on a quarter boundary**, not on the 1st of the month: a
                    // "1 quarter" clock interval that began on the 1st of October would not be a
                    // quarter at all.
                    val monthsIn = ((at.monthValue - 1) / back.count) * back.count
                    at.withMonth(monthsIn + 1).withDayOfMonth(1)
                        .truncatedTo(java.time.temporal.ChronoUnit.DAYS)
                }
                null -> return Interval(oldest ?: now, now)
            }
            return Interval(start.toInstant().toEpochMilli(), now)
        }
    }

    /**
     * The period immediately before [current]: **the same length, ending where it starts.**
     *
     * The operator's definition - *"previous interval is just start of the current interval minus
     * length of the current interval"* - and the only one that also works for an interval set by
     * hand, which is why this is a function of the interval rather than of a preset.
     *
     * **Subtracted as a DURATION on the instant timeline**, so the previous period is exactly as
     * long as the current one. On the two days a year the clocks change that makes it *not* begin at
     * the previous local midnight: the UK's 25 October 2026 is 25 hours, so the 24-hour period before
     * the 26th's midnight starts at 01:00 on the 25th.
     *
     * That is deliberate. The alternative - the previous *calendar* day, 00:00 to 00:00 - would put
     * 25 hours of traffic beside 24 and read as a 4% rise that nothing caused. "Minus the length" is
     * the operator's own wording, and length is a duration.
     *
     * **Not re-derived from the preset.** Re-running `clockAt` on the earlier start would snap to a
     * different grid cell - clock "1 month" a month ago starts on the 1st of that month, a period of
     * the wrong length - and `intervalAt` would measure from the wrong instant. Subtraction is exact
     * by construction.
     */
    fun previousOf(
        current: Interval,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): Interval {
        val length = current.toMs - current.fromMs
        if (length <= 0) return Interval(current.fromMs, current.fromMs)
        val start = java.time.Instant.ofEpochMilli(current.fromMs).atZone(zone)
        val before = start.minus(java.time.Duration.ofMillis(length)).toInstant().toEpochMilli()
        return Interval(before, current.fromMs)
    }

    /** How far a preset reaches back. Sealed so the two snap rules cannot be confused. */
    sealed interface Back {
        /** A length in milliseconds: minutes and hours, which have no calendar meaning. */
        data class Duration(val ms: Long) : Back

        /** A count of days, subtracted on the local timeline so DST cannot shift the wall clock. */
        data class Days(val count: Int) : Back

        /** A count of months, subtracted on the calendar so 31 Oct minus one month clamps to 30 Sep. */
        data class Months(val count: Int) : Back
    }

    /**
     * How one figure changed from the previous period to this one.
     *
     * **A ratio, and null when there is nothing to compare against.** That null is the whole point:
     * a previous period of one ping against a current period of twenty-two requests is
     * arithmetically +79,600%, and printing it would look like a catastrophe rather than like a
     * period that barely had any traffic. The screen draws a dash, the same way it does for an
     * average with no denominator.
     *
     * **`-100%` is a real answer and is kept.** Current zero against a previous non-zero is a
     * genuine "it stopped", which is exactly what a person opens this table to find out.
     *
     * **Both sides must be present**, so a figure that only one period reported has no change
     * rather than a change computed against zero.
     */
    fun change(previous: Double?, current: Double?): Double? {
        if (previous == null || current == null) return null
        // **A zero baseline has no ratio.** Division would give infinity, and "infinitely more" is
        // not a measurement.
        if (previous == 0.0) return null
        return (current - previous) / previous
    }

    /** The UTC hour that contains [ms]. The unit the log's hour table is keyed by. */
    fun hourFloor(ms: Long): Long = Math.floorDiv(ms, 3_600_000L) * 3_600_000L

    /** The UTC day that contains [ms]. */
    fun dayFloor(ms: Long): Long = Math.floorDiv(ms, 86_400_000L) * 86_400_000L
}
