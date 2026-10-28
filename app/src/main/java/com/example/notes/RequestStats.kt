package com.example.notes

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The request logs, summed into a table.
 *
 * **Why the summing is its own object.** The screen draws numbers; the question of *which* numbers
 * belong in a bucket is arithmetic that can be wrong in ways a picture will not show — a cache
 * counted as well as the input it is part of, a ping blended into a turn, an average taken over
 * buckets that have no requests in them. All of that is decided here, in functions of their
 * arguments, so it can be tested without a screen and without a file.
 *
 * **Every number is nullable and nothing is invented.** A bucket with no requests has no average
 * cost per request; that is not zero, it is a division that did not happen. The screen prints a
 * dash for it, and the difference matters because "0.000000" is what a free bucket would look
 * like too.
 */
object RequestStats {

    /**
     * **An interval, half-open: `fromMs` inclusive, `toMs` inclusive.**
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
        val hours: Long get() = if (toMs < fromMs) 0 else (toMs - fromMs) / 3_600_000L + 1
    }

    /**
     * The interval between two picks, in either order, and never wider than the data.
     *
     * **Left to right regardless of which picker was moved**, because a person sets "start 9am,
     * end 5pm" in the order they think of it, and a table that went blank when the two were
     * swapped would look broken rather than empty.
     */
    fun interval(a: Long, b: Long): Interval = Interval(minOf(a, b), maxOf(a, b))

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
     * One line of the log, as the table needs it.
     *
     * Read leniently: a line that does not parse is skipped rather than aborting the table, because
     * the log is appended to while it is being read and the last line may be half-written.
     */
    fun parse(line: String): Row? {
        val cells = line.split('\t')
        // utc, time, day, kind, ms, provider, model, prompt, cached, completion, reasoning,
        // cost, finish
        if (cells.size < 10) return null
        // **Read the span before the kind, because an aggregate has no kind of its own.** It sums
        // turns and keepalives whose kinds differed, so its kind column says `all` - and a parser
        // that insisted on turn/ping/stall here would drop every rolled-up row on the floor. That
        // failure is silent in the worst way: the compactor writes correct aggregates, the reader
        // ignores them, and the table goes back to summing raw rows that are no longer there.
        //
        // The kind of an aggregate is therefore mapped to TURN, a value nothing reads: `buckets`
        // decides by span, and an aggregate's kind is never consulted.
        val span = cells.getOrNull(13)?.takeIf { it.isNotBlank() } ?: RequestLog.SPAN_RAW
        val kind = when (cells[1]) {
            "turn" -> RequestLog.Stats.Kind.TURN
            "ping" -> RequestLog.Stats.Kind.PING
            "stall" -> RequestLog.Stats.Kind.STALL
            // The aggregate marker. Recognised rather than refused, and mapped to the neutral kind.
            RequestLog.KIND_ALL -> RequestLog.Stats.Kind.TURN
            else -> return null
        }
        // **No contradiction check on the kind.** An earlier version refused a line whose span was
        // an aggregate while its kind named one response - "an aggregate cannot be only a turn".
        // That is true of a row the compactor writes, and it made every other producer of an
        // aggregate line fail: a hand-written fixture, and any future level built by a path that
        // does not go through `aggregateLine`. A reader that refuses rows it could understand is
        // stricter than the format, and the format is what matters here - `buckets` decides by
        // span, so the kind of an aggregate is simply not read.
        // **The instant, and nothing else, places the row.** A line without a readable one is not
        // guessed at from its clock time: a guessed timezone is worse than a missing row, because
        // it looks like a measurement.
        // **An ISO instant, read as an instant.** It used to be raw millis, and before that a
        // clock time that needed a day beside it to mean anything - which is what made the log
        // hold a local time at all.
        val atMs = try {
            Instant.parse(cells[0]).toEpochMilli()
        } catch (t: Throwable) {
            return null
        }
        fun long(i: Int) = cells.getOrNull(i)?.takeIf { it.isNotBlank() }?.toLongOrNull()
        fun double(i: Int) = cells.getOrNull(i)?.takeIf { it.isNotBlank() }?.toDoubleOrNull()
        val cost = double(9)

        /**
         * **A line that predates the span columns is a raw response, because that is what it is.**
         *
         * The three columns were appended, so every line already on a phone lacks them. Defaulting
         * to `raw`/1/`cost != null` is not a guess about what those lines mean: one line was always
         * one response, and whether it reported a cost is readable from its `cost` cell. Treating a
         * missing span as anything else would silently turn real history into an aggregate.
         */
        val requests = long(14) ?: 1
        val costReported = long(15) ?: if (cost != null) 1 else 0

        return Row(
            atMs = atMs,
            kind = kind,
            // **An aggregate row has no kind of its own**: it sums turns and keepalives, and each
            // contributing line had its own. The kind is carried through as the row's own kind for
            // a raw line and is meaningless for an aggregate, which `buckets` reads by span.
            span = span,
            requests = requests,
            costReported = costReported,
            prompt = long(5),
            cached = long(6),
            completion = long(7),
            reasoning = long(8),
            cost = cost,
            said = long(11),
            thinking = long(12),
        )
    }

    /** One parsed line. [at] is the clock time, which is all the bucketing needs. */
    data class Row(
        /**
         * **When this response happened, as an instant.**
         *
         * Not a clock time and not a day: an instant is the only form of a time that survives a
         * change of timezone, and every bucket in the table is arithmetic on it. A log read on a
         * phone that has since flown somewhere else still places its rows correctly because of
         * this column.
         */
        val atMs: Long,
        val kind: RequestLog.Stats.Kind,
        /**
         * What the line covers: [RequestLog.SPAN_RAW] for one response, or `hour`/`day`/`month`/
         * `year` for a rolled-up row.
         *
         * Defaults to raw, which is what every line written before the column existed was.
         */
        val span: String = RequestLog.SPAN_RAW,
        /** How many requests this row covers. 1 for a raw line. */
        val requests: Long = 1,
        /**
         * How many of them reported a cost.
         *
         * **The denominator for an average, never an amount of money.** A sum of costs divided by a
         * count of requests averages over rows that never reported one, and the answer looks like
         * money while being wrong.
         */
        val costReported: Long = 0,
        val prompt: Long?,
        val cached: Long?,
        val completion: Long?,
        val reasoning: Long?,
        val cost: Double?,
        /** What a stalled answer had produced, when the row is a stall. Null for everything else. */
        val said: Long? = null,
        val thinking: Long? = null,
    )

    /**
     * One [Bucket] per span, for the one instant chosen.
     *
     * **Three columns, always** — the hour, the day and the month that instant belongs to. A row is
     * counted into each span whose bounds contain it, which is why the three columns deliberately
     * overlap: the hour's requests are also part of the day's, and the day's are part of the
     * month's. That is the point of showing them together — "this hour cost X, and it is part of a
     * day that cost Y, in a month that cost Z" is the question the table answers, and three
     * disjoint windows could never answer it.
     */
    fun totals(rows: List<Row>, span: Interval): Totals {
        // **The covered units, built once.** `counts` asks "does a coarser aggregate cover this
        // row's unit", and answering that by scanning every row would be quadratic - at ten
        // thousand raw rows, a hundred million comparisons. A map of level to sorted starts makes
        // it one lookup, and it is the same information.
        val covered = units(rows)
        var sum = Stats()
        var stalls = Stats()
        for (row in rows) {
            // **Which rows this interval counts, and never two levels of the same thing.**
            //
            // The hazard the plan names: an aggregate covers the same requests as the raw rows
            // beneath it, so counting both doubles the figure into a plausible wrong number. The
            // rule is a **disjoint decomposition**: the coarsest stored level that covers a unit
            // speaks for it, and every level beneath a covered unit is silent.
            //
            // **Raw is exact, aggregates are atomic.** A raw row counts when its own instant is
            // inside the interval. An aggregate counts when the interval touches its unit **at
            // all**, because a stored hour cannot be divided: it describes a clock hour that had
            // already closed when it was written.
            //
            // So 10:15-10:45 counts the whole stored 10:00 hour. That rounds the figure up to the
            // hour, which is worse than exact - and far better than showing nothing for a period
            // the log demonstrably has. An interval that has not been compacted is precise to the
            // millisecond, because raw rows are.
            val level = if (row.span == RequestLog.SPAN_RAW) null else row.span
            if (level == null) {
                if (row.atMs < span.fromMs || row.atMs > span.toMs) continue
            } else {
                val start = unitFloor(level, row.atMs) ?: continue
                // Touching: the unit's last millisecond is not before the interval starts, and its
                // first is not after the interval ends.
                if (unitEnd(level, start) - 1 < span.fromMs || start > span.toMs) continue
            }
            // The unit a row describes must be entirely inside the interval to be counted whole,
            // unless it is raw - see above. A coarser row that merely overlaps is still counted,
            // because its unit is atomic.
            if (!counts(row, covered)) continue
            val add = Stats(
                requests = row.requests.toInt(),
                promptTokens = row.prompt ?: 0,
                cachedTokens = row.cached ?: 0,
                completionTokens = row.completion ?: 0,
                reasoningTokens = row.reasoning ?: 0,
                cost = row.cost ?: 0.0,
                // **The reported count, not a fresh reading of `cost`.** An aggregate's `cost` is
                // a sum of reported costs and cannot say how many rows contributed; its own column
                // is the only thing that can.
                costReported = row.costReported.toInt(),
                saidChars = row.said ?: 0,
                thinkingChars = row.thinking ?: 0,
            )
            when (row.kind) {
                // Turns and keepalives are summed together because the column is time, not kind:
                // "what did this interval cost" has one answer. The kind split is still visible
                // where it matters - the keepalive is most of the spend and almost none of the
                // output, and that shows in the tokens.
                RequestLog.Stats.Kind.TURN, RequestLog.Stats.Kind.PING -> sum += add
                // A stall is billed but reports no totals, so it is counted apart rather than
                // folded in - adding it to `sum` would silently claim a cost of zero for it.
                RequestLog.Stats.Kind.STALL -> stalls += add
            }
        }
        return Totals(total = sum, stalls = stalls)
    }

    /**
     * Every line of every log file under [accountDir], newest last.
     *
     * **Read on opening the screen and nowhere else**, which is what the report asked for and also
     * what keeps the cost of the feature honest: the log grows on every response, and a table that
     * watched it would be re-reading it while the agent wrote to it.
     */
    fun read(accountDir: File): List<Row> {
        val file = RequestLog.fileFor(accountDir)
        if (!file.isFile) return emptyList()
        val rows = ArrayList<Row>()
        try {
            // **Line by line, never `readLines()`.** `readLines()` builds a list of every line as a
            // String *and* then a Row for each, so the peak is both at once: at ten thousand
            // requests a day that is ~50 MB for a month and ~600 MB for a year, allocated on
            // whichever thread called this. A `useLines` sequence holds one line at a time and
            // keeps only the Rows, which are the thing the table actually needs.
            //
            // **One file, one shape, one read.** A row carries its own instant, so there is no day
            // in a file name to be read against and no neighbour to fetch: the table for any zone
            // is this same single pass.
            file.useLines { lines ->
                lines.drop(1).forEach { if (it.isNotBlank()) parse(it)?.let(rows::add) }
            }
        } catch (t: Throwable) {
            // A file that cannot be read contributes nothing rather than failing the table. A file
            // half read contributes what it read, which is the honest half.
            return rows
        }
        return rows
    }

    /**
     * True when the log has anything inside [span].
     *
     * The question a reader asks of an empty table is not "how long is the log" but **"does the log
     * reach this far"** - so a period with no rows reads "nothing happened then" rather than looking
     * like a broken table. The two are different facts and only one of them is a problem.
     */
    fun covers(rows: List<Row>, span: Interval): Boolean =
        rows.any { it.atMs in span.fromMs..span.toMs }

    /**
     * The oldest instant the log holds, or null when it holds nothing.
     *
     * Said beside the caption when a container is empty, so a person can tell "nothing happened in
     * October" from "the log does not start until December" — two different facts that look
     * identical as a grid of dashes.
     */
    fun oldest(rows: List<Row>): Long? = rows.minOfOrNull { it.atMs }

    // ---- rolling up ----------------------------------------------------------

    /**
     * One aggregate row's worth of numbers: what [rollup] produces for a span of raw rows.
     *
     * No `kind`, no `ms`, no `provider`, no `model`, no `finish`: none of them is additive, so an
     * aggregate has nothing true to say about any of them. They are the fields a rollup loses, and
     * losing them is the accepted price of not re-reading a year of requests to draw a month.
     */
    data class Rolled(
        val fromMs: Long,
        val span: String,
        val requests: Long,
        val costReported: Long,
        val prompt: Long,
        val cached: Long,
        val completion: Long,
        val reasoning: Long,
        val cost: Double,
        val said: Long,
        val thinking: Long,
    )

    /**
     * The aggregate for [span] covering the rows in [rows], or null when they hold nothing.
     *
     * **A pure function of rows and a unit boundary**, so the arithmetic that decides a month's
     * money can be tested without a file, a clock or a worker. [floorOf] is the boundary of the
     * unit — passed in rather than computed, because the zone is the caller's business and the
     * compactor must aggregate by **UTC** to survive a clock change (see the plan, §3).
     *
     * **Only closed units are rolled.** A caller that asks for the current hour would be storing an
     * aggregate that is about to stop being true, and the next append would contradict it. That
     * decision is the caller's, which is why this function does not take a clock.
     */
    fun rollup(rows: List<Row>, span: String, floorOf: Long): Rolled? {
        if (rows.isEmpty()) return null
        var requests = 0L
        var costReported = 0L
        var prompt = 0L
        var cached = 0L
        var completion = 0L
        var reasoning = 0L
        var cost = 0.0
        var said = 0L
        var thinking = 0L
        for (row in rows) {
            // **`row.requests`, not 1**, for the same reason `buckets` uses it: an aggregate may
            // already be an aggregate if a coarser level is built from a finer one, and counting
            // every contributing row as one would make a month of hours read as 720 requests.
            requests += row.requests
            costReported += row.costReported
            prompt += row.prompt ?: 0
            cached += row.cached ?: 0
            completion += row.completion ?: 0
            reasoning += row.reasoning ?: 0
            cost += row.cost ?: 0.0
            said += row.said ?: 0
            thinking += row.thinking ?: 0
        }
        return Rolled(
            fromMs = floorOf,
            span = span,
            requests = requests,
            costReported = costReported,
            prompt = prompt,
            cached = cached,
            completion = completion,
            reasoning = reasoning,
            cost = cost,
            said = said,
            thinking = thinking,
        )
    }

    /** The UTC hour that contains [ms] — the unit the compactor rolls first. */
    fun hourFloor(ms: Long): Long = Math.floorDiv(ms, 3_600_000L) * 3_600_000L

    /** The UTC day that contains [ms]. */
    fun dayFloor(ms: Long): Long = Math.floorDiv(ms, 86_400_000L) * 86_400_000L

    /** The UTC month that contains [ms], as its first millisecond. */
    fun monthFloor(ms: Long): Long = Instant.ofEpochMilli(ms)
        .atZone(java.time.ZoneOffset.UTC)
        .withDayOfMonth(1).truncatedTo(java.time.temporal.ChronoUnit.DAYS)
        .toInstant().toEpochMilli()

    /** The UTC year that contains [ms], as its first millisecond. */
    fun yearFloor(ms: Long): Long = Instant.ofEpochMilli(ms)
        .atZone(java.time.ZoneOffset.UTC)
        .withDayOfYear(1).truncatedTo(java.time.temporal.ChronoUnit.DAYS)
        .toInstant().toEpochMilli()

    /**
     * Where a unit of [span] that starts at [start] ends — the first millisecond of the next one.
     *
     * **Computed, never assumed.** A month and a year are not fixed widths, and assuming 30 days
     * would close February two days late: an aggregate written while requests can still arrive for
     * it, which is the one thing `closable` exists to prevent.
     */
    fun unitEnd(span: String, start: Long): Long = when (span) {
        "hour" -> start + 3_600_000L
        "day" -> start + 86_400_000L
        "month" -> monthFloor(start + 32L * 86_400_000L)
        else -> yearFloor(start + 366L * 86_400_000L)
    }

    /**
     * **The rows that may be rolled into [span], grouped by unit**, each group only if it is closed.
     *
     * Closed means the unit ended before [now]: its last millisecond is in the past, so nothing can
     * append to it any more. An open unit is returned by no group at all, which is what keeps an
     * aggregate from being contradicted by the next append.
     */
    fun closable(rows: List<Row>, span: String, now: Long): Map<Long, List<Row>> {
        val floor: (Long) -> Long = when (span) {
            "hour" -> ::hourFloor
            "day" -> ::dayFloor
            "month" -> ::monthFloor
            "year" -> ::yearFloor
            else -> return emptyMap()
        }
        return rows
            .groupBy { floor(it.atMs) }
            .filterKeys { unitEnd(span, it) <= now }
    }
}
