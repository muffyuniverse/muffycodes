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
        /** Where the column starts: `to 12:45`, `from 00:00`, `from the 1st`. */
        val sub: String = "",
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
        val kind = when (cells[1]) {
            "turn" -> RequestLog.Stats.Kind.TURN
            "ping" -> RequestLog.Stats.Kind.PING
            "stall" -> RequestLog.Stats.Kind.STALL
            else -> return null
        }
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
        return Row(
            atMs = atMs,
            kind = kind,
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
        return SPANS.map { span ->
            val box = container(span, anchor, zone)
            var sum = Stats()
            var stalls = Stats()
            for (row in rows) {
                // Cheap rejection first: a row outside the span cannot be in its column, and a
                // month's column should not build a Stats for years of irrelevant rows.
                if (row.atMs < box.fromMs || row.atMs > box.toMs) continue
                val add = Stats(
                    requests = 1,
                    promptTokens = row.prompt ?: 0,
                    cachedTokens = row.cached ?: 0,
                    completionTokens = row.completion ?: 0,
                    reasoningTokens = row.reasoning ?: 0,
                    cost = row.cost ?: 0.0,
                    costReported = if (row.cost != null) 1 else 0,
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
            Bucket(label = box.label, total = sum, stalls = stalls)
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
        return try {
            // **One file, one shape, one read.** A row carries its own instant, so there is no day
            // in a file name to be read against and no neighbour to fetch: the table for any zone
            // is this same single pass.
            file.readLines().drop(1).mapNotNull { parse(it) }
        } catch (t: Throwable) {
            // A file that cannot be read contributes nothing rather than failing the table.
            emptyList()
        }
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

}
