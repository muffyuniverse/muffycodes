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
     * This replaces the five nested `Span`s. The operator's revision: *"we need 2 pickers for
     * starting and ending, and just 1 result column for the selected interval with per hour
     * granularity."* Two pickers remove the whole question of where a column starts and stops -
     * the person states the interval, which is the thing they actually know.
     *
     * **Granularity is the hour**, which is also what the log keeps (§2 of the plan): a `hour` row
     * covers one clock hour, so an interval reads whole hours from the aggregates and one partial
     * hour from raw. Nothing coarser is needed to answer it, and nothing finer exists.
     */
    data class Interval(val fromMs: Long, val toMs: Long) {
        /** How many whole hours it covers. An empty interval is zero, never negative. */
        val hours: Long get() = if (toMs <= fromMs) 0 else (toMs - fromMs) / 3_600_000L

        /** The last millisecond inside the interval, for comparing against a row. */
        val lastMs: Long get() = toMs - 1
    }

    /**
     * The interval between two picked hours, **snapped to whole hours and ordered**.
     *
     * **The end is a boundary, not an hour to contain.** That distinction is the whole of it: *"if
     * now is 19:23 then you can choose interval from 19 to 20."* From 19 to 20 is **one** hour - the
     * hour beginning at 19 - and 20 is where it stops. Treating 20 as an hour of its own made
     * 19-to-22 four hours, and made the To button read `19:00` while meaning through 19:59.
     *
     * **The earlier pick is floored**, so 19:23 starts at 19:00 rather than dropping the first 23
     * minutes of a period the person asked for. **The later pick is rounded up to the next
     * boundary**, so 19:28 ends at 20:00 rather than cutting the last hour short. Rounding outward
     * means the figure always covers at least what was asked for - the safe direction, since a total
     * that quietly excluded the end of a chosen hour would look like lost requests. A pick already
     * on a boundary stays put as a start and moves up as an end, which is exactly what makes
     * "from 19 to 20" one hour and not two.
     *
     * **Order does not matter.** A person moves whichever picker is nearest their thumb, and a table
     * that went blank when the two were swapped would look broken rather than empty.
     */
    fun interval(a: Long, b: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Interval {
        // **Whole CLOCK hours, not whole UTC hours.** The picker states a wall-clock hour, and what
        // it must snap to is the same hour on that wall. `hourFloor` divides the UTC epoch, which
        // lands on a local boundary only where the zone's offset is a whole number of hours -
        // so in India (+5:30), Nepal (+5:45), Newfoundland (-3:30) or Adelaide (+9:30) a person
        // picking 19:00 got 18:30-19:30, displayed as "18:00" because the label renders `HH:00`.
        // Two ways wrong at once: the figure covered the wrong hour, and the button said a time
        // that was not the interval's start.
        //
        // The log keeps UTC hours on purpose (see `unitFloor`), and that does not change: an
        // aggregate must not be frozen at the local shape compaction happened to see, because two
        // local `01:00`s exist on the day the clocks go back. So this is a *second* floor with a
        // different meaning, and the naming has to keep them apart.
        val from = localHourFloor(minOf(a, b), zone)
        val high = localHourFloor(maxOf(a, b), zone)
        // **The end is the next local hour boundary**, computed on the local timeline rather than
        // by adding a UTC hour: across a DST change a wall-clock hour is not always 3,600,000 ms.
        val to = if (maxOf(a, b) == high) {
            high
        } else {
            java.time.Instant.ofEpochMilli(high).atZone(zone)
                .plusHours(1).toInstant().toEpochMilli()
        }
        return Interval(from, to)
    }

    /**
     * The start of the **wall-clock** hour [ms] falls in, in [zone].
     *
     * Distinct from [hourFloor], which floors the UTC epoch because the *log* stores UTC hours.
     * They agree in every whole-hour zone and disagree by the offset's remainder everywhere else -
     * a distinction worth two functions rather than one with a boolean, because getting it wrong
     * is silent: the interval is simply the wrong hour, and the label agrees with itself.
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

    /** The UTC hour that contains [ms]. The unit the log's hour table is keyed by. */
    fun hourFloor(ms: Long): Long = Math.floorDiv(ms, 3_600_000L) * 3_600_000L

    /** The UTC day that contains [ms]. */
    fun dayFloor(ms: Long): Long = Math.floorDiv(ms, 86_400_000L) * 86_400_000L
}
