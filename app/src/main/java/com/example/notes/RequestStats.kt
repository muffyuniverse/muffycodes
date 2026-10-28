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
     * **The three spans, side by side, containing one chosen instant.**
     *
     * Not three views and not three axes. One pick — 12:00 on 25 October 2026 — and the table has
     * exactly three columns: the hour that instant is in, the day it is in, and the month it is in.
     * `12:00 | 25 Oct | Oct 2026`, with the metrics as rows.
     *
     * This was read three different wrong ways before it was built, so the reason is worth stating:
     *
     * - **Not "the last 24 hours".** That was the first build, and it drew a month of columns
     *   ending at the pick, which is a different question and cannot show October by itself.
     * - **Not "24 hours, or 31 days, or 12 months, chosen by an axis."** That was the second, and
     *   it made the person pick a granularity before they could see anything — when the whole point
     *   is that one instant has an hour, a day and a month at the same time.
     * - **Three columns, always, whatever is chosen.** The spans are what one instant *is*, so the
     *   table shows all three at once and needs no control to switch between them.
     */
    enum class Span(val label: String) {
        /** The clock hour, cut at the pick: picking 12:45 shows 12:00 to 12:45. */
        HOUR("hour"),

        /** The calendar day, cut at the pick: picking 12:45 shows 00:00 to 12:45. */
        DAY("day"),

        /** The calendar month, cut at the pick: picking 12:45 shows the 1st to 12:45. */
        MONTH("month"),

        /**
         * The calendar year, cut at the pick: picking 23:00 on 31 December shows the whole of it.
         *
         * **This is how a year is asked about** — there is no year-only picker and no need for one.
         * Choosing the last instant of 2025 gives 2025 as the year column, December as the month
         * column, the 31st as the day column, and so on: one pick, every column, always.
         */
        YEAR("year"),

        /**
         * **Everything up to the picked moment** — not all of time.
         *
         * The same rule as the other four spans, taken to its widest: it ends at the pick. A total
         * that ran to "now" would answer a different question from the column beside it, and the
         * five are only comparable because they share one end.
         *
         * Its start is the beginning of the log, and the container does not consult the log to find
         * it: it begins at the epoch, before any row can exist. That makes the bounds a property of
         * the pick alone, so the same instant describes the same span on every read.
         */
        TOTAL("total"),
    }

    /** The spans the table always has, in the order a person reads them: narrowest first. */
    val SPANS: List<Span> = Span.entries

    /**
     * One span's bounds and name, for a given instant.
     *
     * **Every column starts at the boundary of its own span and stops at the picked moment.**
     * Picking 12:45 on the 25th gives `12:00-12:45`, `00:00-12:45` and `1 Oct-12:45`: three
     * cumulative windows that all end at the same instant and nest inside one another.
     *
     * Two readings were rejected, and both were built first:
     *
     * - **Stopping at the end of the span** (`12:00-12:59`, the whole day, the whole month) counts
     *   time that has not happened. At 12:45 the afternoon of the 25th is in the future, so a day
     *   column claiming a full day would be inventing it.
     * - **Cutting on a boundary exception** (a pick at exactly 12:00 meaning the whole hour) made
     *   the three columns disagree with each other: the day would be whole while the hour was cut.
     *   One rule for all three is simpler and needs no explanation.
     *
     * A consequence worth knowing: with the pick at 00:00 the hour and the day are both empty,
     * because neither has any elapsed time yet. That is honest - at midnight nothing has been
     * spent today - and the month still has its earlier days.
     *
     * `fromMs` is inclusive and `toMs` is the last millisecond, so "is this row in this column" is
     * one comparison. A row after the pick is deliberately outside: it is not part of a period that
     * had not happened yet.
     */
    data class Container(
        val fromMs: Long,
        val toMs: Long,
        /**
         * What the column *is*: `12:00`, `25 Oct`, `Oct 2026`.
         *
         * The hour, the day and the month of the pick — never a whole one of any of those, because
         * every column is cut at the picked moment.
         */
        val label: String,
        /**
         * Where the column *starts*, since [label] cannot say it: `to 12:45`, `from 00:00`,
         * `from the 1st`.
         *
         * All three end at the pick, which the caption above the table states once. So the month
         * column of a pick at 12:45 on the 25th is `Oct 2026` / `from the 1st`, meaning the 1st to
         * the 25th at 12:45 — and a heading of `Oct 2026` on its own would claim the whole month.
         */
        val sub: String,
    )

    /**
     * The stored aggregates, grouped by level, for one [buckets] draw.
     *
     * Keyed by level and sorted by start so [covers] can answer in a scan of one level. A month
     * does not "equal" the days inside it, so membership has to be **containment**: the day 5 Aug
     * is covered by the month starting 1 Aug, and a set of exact unit starts could never say so.
     */
    private fun row(all: List<Row>): Map<String, List<Long>> =
        all.filter { it.span != RequestLog.SPAN_RAW }
            .groupBy { it.span }
            .mapValues { (_, rows) -> rows.map { it.atMs }.sorted() }

    /** Whether any stored [span] aggregate covers the unit `[from, to)`. */
    private fun covers(aggregates: Map<String, List<Long>>, span: String, from: Long, to: Long): Boolean =
        aggregates[span].orEmpty().any { start ->
            start <= from && to <= unitEnd(span, start)
        }

    /**
     * Whether [r] is the level that speaks for its unit.
     *
     * **The disjointness rule, in one place.** The coarsest stored aggregate wins; every level
     * beneath a covered unit stays silent. That is what makes a column a partition rather than a
     * pile, and it is the only thing standing between the table and a number that is merely
     * plausible.
     *
     * - **Raw is always counted.** Nothing covers raw: it is the floor of the pyramid, and the only
     *   level that always exists.
     * - **A coarser row need not be complete for the box.** Aggregates only ever exist for closed
     *   units - the compactor refuses an open one - so "it exists" already means "it is whole". A
     *   day row is therefore counted even when the column cuts through it, which is right: the
     *   alternative is to descend to hours that were deleted once the day was written.
     */
    private fun counts(r: Row, covered: Map<String, List<Long>>): Boolean {
        if (r.span == RequestLog.SPAN_RAW) return true
        val start = unitFloor(r.span, r.atMs) ?: return true
        val end = unitEnd(r.span, start)
        val coarser = when (r.span) {
            "hour" -> listOf("day", "month", "year")
            "day" -> listOf("month", "year")
            "month" -> listOf("year")
            else -> emptyList()
        }
        return coarser.none { covers(covered, it, start, end) }
    }

    /** The start of the unit of [span] that holds [ms], or null for a span this code does not know. */
    private fun unitFloor(span: String, ms: Long): Long? = when (span) {
        "hour" -> hourFloor(ms)
        "day" -> dayFloor(ms)
        "month" -> monthFloor(ms)
        "year" -> yearFloor(ms)
        else -> null
    }

    /** The span of [span] that holds [anchor]. */
    fun container(span: Span, anchor: Long, zone: ZoneId = ZoneId.systemDefault()): Container {
        val at = Instant.ofEpochMilli(anchor).atZone(zone)
        fun fmt(local: java.time.ZonedDateTime, pattern: String) =
            local.format(java.time.format.DateTimeFormatter.ofPattern(pattern))

        /**
         * The last millisecond counted: the pick itself, for all three spans.
         *
         * One rule, no exceptions. A request that happened after the picked moment is not counted,
         * because at the picked moment it had not happened.
         */
        fun cut(): Long = anchor

        return when (span) {
            Span.HOUR -> {
                // Truncated to the hour by dropping the parts below it, not by subtracting a
                // remainder: a zone's hour is not always 3600000 ms, and a DST hour must still
                // produce exactly one column.
                val hourStart = at.withMinute(0).withSecond(0).withNano(0)
                val end = cut()
                Container(
                    fromMs = hourStart.toInstant().toEpochMilli(),
                    toMs = end,
                    // **A range, not `12:00`.** A range is how an hour is said, and this one stops
                    // at the pick - so it names both ends and needs no second line.
                    label = fmt(hourStart, "HH:mm") + "–" +
                        fmt(Instant.ofEpochMilli(end).atZone(zone), "HH:mm"),
                    sub = "",
                )
            }
            Span.DAY -> {
                val day = at.toLocalDate()
                // `atStartOfDay(zone)` rather than `atTime(0,0)`: on the day a zone springs forward
                // midnight does not exist, and asking for it by name lands somewhere else.
                val from = day.atStartOfDay(zone).toInstant().toEpochMilli()
                Container(
                    fromMs = from,
                    toMs = cut(),
                    label = day.format(java.time.format.DateTimeFormatter.ofPattern("d MMM")),
                    // Midnight, because a day column is that day up to the pick — not the last
                    // twelve hours, which would reach back into the day before.
                    sub = "from 00:00",
                )
            }
            Span.MONTH -> {
                val first = at.toLocalDate().withDayOfMonth(1)
                val from = first.atStartOfDay(zone).toInstant().toEpochMilli()
                Container(
                    fromMs = from,
                    toMs = cut(),
                    label = first.format(java.time.format.DateTimeFormatter.ofPattern("MMM yyyy")),
                    // **The 1st, and it stops at the pick's day** — so at midday on the 25th this
                    // column covers the 1st to the 25th, not all thirty-one days. Saying "from the
                    // 1st" plus the caption's instant is the shortest heading that is still exact.
                    sub = "from the 1st",
                )
            }
            Span.YEAR -> {
                val jan1 = at.toLocalDate().withDayOfMonth(1).withMonth(1)
                Container(
                    fromMs = jan1.atStartOfDay(zone).toInstant().toEpochMilli(),
                    toMs = cut(),
                    label = jan1.format(java.time.format.DateTimeFormatter.ofPattern("yyyy")),
                    sub = "from 1 Jan",
                )
            }
            Span.TOTAL -> Container(
                // **The epoch, not the oldest row.** The container must not consult the log: the
                // same instant would then describe a different span as the log grows, and the
                // column would change its meaning between two reads. Starting before any possible
                // row makes the bounds a property of the pick alone, and `buckets` counts exactly
                // the rows that exist - up to `cut()`, like every other column.
                fromMs = 0L,
                toMs = cut(),
                label = "total",
                sub = "from the first row",
            )
        }
    }

    /**
     * One column of the table: a span of time, and what was spent in it.
     *
     * The two kinds are kept apart because they cost different things and answer different
     * questions. The keepalive fires every four minutes while idle — roughly 360 responses a day —
     * so a table that summed them with the turns would be about the keepalive while looking like it
     * was about the agent. [Stats] is what one kind did.
     */
    data class Bucket(
        val label: String,
        /**
         * Where the column starts: `from 00:00`, `from the 1st`, `from 1 Jan`, `from the first row`.
         *
         * **No default value, deliberately.** This was `= ""` and the constructor forgot it, so
         * every column rendered with a blank second line - a bug that compiles, renders, and is
         * caught only by a test asserting the text. A required parameter makes the omission a
         * compile error instead.
         */
        val sub: String,
        /** Everything accounted for in this span: turns and keepalives together. */
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
    fun buckets(rows: List<Row>, anchor: Long, zone: ZoneId = ZoneId.systemDefault()): List<Bucket> {
        // **The covered units, built once.** `counts` asks "does a coarser aggregate cover this
        // row's unit", and answering that by scanning every row would be quadratic - at ten
        // thousand raw rows, a hundred million comparisons for each of five columns. A set of
        // (level, unit start) pairs makes it one lookup, and it is the same information.
        val covered = row(rows)
        return SPANS.map { span ->
            val box = container(span, anchor, zone)
            var sum = Stats()
            var stalls = Stats()
            for (row in rows) {
                // **Which rows this column counts, and never two levels of the same thing.**
                //
                // The hazard the plan names: an aggregate covers the same requests as the raw rows
                // beneath it, so counting both doubles the column into a plausible wrong number.
                // The rule is a **disjoint decomposition**: for each part of the span, take the
                // coarsest COMPLETE aggregate that covers it, and descend into the level below only
                // for the part no aggregate covers.
                //
                // Implemented as the simplest correct form of that: a row counts if its own span's
                // unit lies wholly inside the column *and* no row from a coarser level covers that
                // same unit. An hour inside a day is dropped, because the day row already speaks for
                // it; an hour in a day that has no aggregate is counted, because nothing else does.
                if (row.atMs < box.fromMs || row.atMs > box.toMs) continue
                if (!counts(row, covered)) continue
                val add = Stats(
                    requests = row.requests.toInt(),
                    promptTokens = row.prompt ?: 0,
                    cachedTokens = row.cached ?: 0,
                    completionTokens = row.completion ?: 0,
                    reasoningTokens = row.reasoning ?: 0,
                    cost = row.cost ?: 0.0,
                    // **The reported count, not a fresh reading of `cost`.** An aggregate's `cost`
                    // is a sum of reported costs and cannot say how many rows contributed; its own
                    // column is the only thing that can.
                    costReported = row.costReported.toInt(),
                    saidChars = row.said ?: 0,
                    thinkingChars = row.thinking ?: 0,
                )
                when (row.kind) {
                    // Turns and keepalives are summed together because the columns are time, not
                    // kind: the question "what did this hour cost" has one answer, and splitting it
                    // into two blocks per column would double every heading. The kind split is
                    // already on the screen as its own two blocks over the same three columns.
                    RequestLog.Stats.Kind.TURN, RequestLog.Stats.Kind.PING -> sum += add
                    // A stall is billed but reports no totals, so it is counted apart rather than
                    // folded in - adding it to `sum` would silently claim a cost of zero for it.
                    RequestLog.Stats.Kind.STALL -> stalls += add
                }
            }
            Bucket(label = box.label, sub = box.sub, total = sum, stalls = stalls)
        }
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
     * True when the log has any row inside [box].
     *
     * **This replaces a function that answered a different question and answered it wrongly.** It
     * used to return "how many days from the oldest row to now", which ignored the anchor entirely
     * and so returned a *negative* number for a container in the past — a caption reading "-7 days
     * kept" under a table of empty columns. Under container semantics the question a reader asks is
     * not "how long is the log" but "does the log reach back this far", and that is one comparison.
     */
    fun covers(rows: List<Row>, box: Container): Boolean =
        rows.any { it.atMs in box.fromMs..box.toMs }

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
