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
     * The three time axes, each naming a **container** rather than a window.
     *
     * The first version had these as rolling windows — "the last 24 hours", "the last 30 days",
     * "the last 12 months" — ending at the picked instant. That was wrong in a way that only shows
     * when you pick a date: choosing 28 October drew a day axis of **29 September to 28 October**,
     * so the column labelled with October's days was mostly September's, and the month axis showed
     * Nov 25 to Oct 26 for someone who had asked about 2026.
     *
     * The operator's structure is the other one, and it is the only reading under which the four
     * picker granularities and the three axes agree: **you pick a year and see its months; you pick
     * a month and see its days; you pick a day and see its hours.** So an axis names the container
     * its columns come from, and [container] says which one.
     */
    enum class Axis(val label: String) {
        /** The twenty-four hours of the day containing the anchor. */
        HOUR("hour"),

        /** The days of the month containing the anchor: 28, 29, 30 or 31 of them. */
        DAY("day"),

        /** The twelve months of the year containing the anchor. */
        MONTH("month"),
    }

    /**
     * The span an axis's columns cover, given any instant inside it.
     *
     * `fromMs` is inclusive and `toMs` is the last millisecond of the container, so "is this row in
     * the table" is one comparison and needs no special case for the final bucket.
     */
    data class Container(
        val fromMs: Long,
        val toMs: Long,
        /** The container's name, for the caption: "October 2026", "28 Oct 2026", "2026". */
        val label: String,
    )

    /** The container of [axis] that holds [anchor]. */
    fun container(axis: Axis, anchor: Long, zone: ZoneId = ZoneId.systemDefault()): Container {
        val at = Instant.ofEpochMilli(anchor).atZone(zone)
        // `atStartOfDay(zone)` rather than `atTime(0,0)`: on the day a zone springs forward,
        // midnight does not exist, and asking for it by name throws or silently lands at 01:00.
        fun start(local: java.time.LocalDate) = local.atStartOfDay(zone).toInstant().toEpochMilli()
        return when (axis) {
            Axis.HOUR -> {
                val day = at.toLocalDate()
                Container(
                    fromMs = start(day),
                    toMs = start(day.plusDays(1)) - 1,
                    label = day.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")),
                )
            }
            Axis.DAY -> {
                val firstOfMonth = at.toLocalDate().withDayOfMonth(1)
                Container(
                    fromMs = start(firstOfMonth),
                    toMs = start(firstOfMonth.plusMonths(1)) - 1,
                    label = firstOfMonth.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy")),
                )
            }
            Axis.MONTH -> {
                val firstOfYear = at.toLocalDate().withDayOfYear(1)
                Container(
                    fromMs = start(firstOfYear),
                    toMs = start(firstOfYear.plusYears(1)) - 1,
                    label = firstOfYear.format(java.time.format.DateTimeFormatter.ofPattern("yyyy")),
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
        val turns: Stats,
        val pings: Stats,
        /** Answers that began and then died. Billed and unreported, so they are their own line. */
        val stalls: Stats = Stats(),
    ) {
        val total: Stats get() = turns + pings + stalls
    }

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
     * Sums [rows] into the columns of the container that holds [anchor].
     *
     * **The columns come from the container, not from a subtraction.** The first version walked
     * backwards from the anchor a fixed number of times, which is how the month axis came to show
     * Nov 25 to Oct 26 for a person asking about 2026. Here the container names its own first and
     * last moment, and the columns are whatever lies between them: 24 hours, or as many days as
     * that month actually has, or twelve months.
     *
     * **Empty columns are drawn.** A day with no requests is a column of zeroes, because a table
     * that only showed the spans something happened would hide the shape of the span, which is what
     * a person looks at a cost table to see.
     */
    fun buckets(rows: List<Row>, axis: Axis, anchor: Long, zone: ZoneId = ZoneId.systemDefault()): List<Bucket> {
        val box = container(axis, anchor, zone)
        fun zoneOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone)

        fun keyOf(time: java.time.ZonedDateTime): String = when (axis) {
            Axis.HOUR -> time.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH"))
            Axis.DAY -> time.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            Axis.MONTH -> time.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"))
        }
        fun labelOf(time: java.time.ZonedDateTime): String = when (axis) {
            // **The hour is truncated because the column is an hour.** A column labelled "09:30"
            // that holds 09:00 to 09:59 misnames itself, and the key it is stored under is already
            // truncated - so the label has to agree with the key.
            Axis.HOUR -> time.format(java.time.format.DateTimeFormatter.ofPattern("HH:00"))
            // The day number alone, because the month is in the caption above the table; repeating
            // it in thirty columns costs width the cells need.
            Axis.DAY -> time.format(java.time.format.DateTimeFormatter.ofPattern("d"))
            // The month alone, because the year is in the caption, for the same reason.
            Axis.MONTH -> time.format(java.time.format.DateTimeFormatter.ofPattern("MMM"))
        }

        // Walk the container from its own first moment to its last, one step per column. Stepping
        // by *calendar* field (plusHours/plusDays/plusMonths) rather than by a fixed millisecond
        // count is what keeps this right across a DST change, where an hour or a day is not always
        // the length it is on other days.
        val steps = ArrayList<java.time.ZonedDateTime>()
        var cursor = zoneOf(box.fromMs)
        while (cursor.toInstant().toEpochMilli() <= box.toMs) {
            steps.add(cursor)
            cursor = when (axis) {
                Axis.HOUR -> cursor.plusHours(1)
                Axis.DAY -> cursor.plusDays(1)
                Axis.MONTH -> cursor.plusMonths(1)
            }
        }
        val columns = steps.map { keyOf(it) to labelOf(it) }

        // A named accumulator rather than a Triple: three values of the SAME type is a shape whose
        // order nobody can check by eye, and the compiler binds it to the wrong nesting the moment
        // the types repeat.
        data class Sum(val turns: Stats = Stats(), val pings: Stats = Stats(), val stalls: Stats = Stats())
        val sums = columns.associate { (key, _) -> key to Sum() }.toMutableMap()

        for (row in rows) {
            // **The instant, converted into the zone being displayed.** This is the whole timezone
            // story: the log records instants, the table converts them, and the axis is therefore
            // correct whatever the phone's clock is set to.
            val time = zoneOf(row.atMs)
            // Cheap rejection before the key is built: a row outside the container cannot belong to
            // any of its columns, and a twelve-month table should not format keys for years of rows.
            if (row.atMs < box.fromMs || row.atMs > box.toMs) continue
            val cell = sums[keyOf(time)] ?: continue
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
            sums[keyOf(time)] = when (row.kind) {
                RequestLog.Stats.Kind.TURN -> cell.copy(turns = cell.turns + add)
                RequestLog.Stats.Kind.PING -> cell.copy(pings = cell.pings + add)
                RequestLog.Stats.Kind.STALL -> cell.copy(stalls = cell.stalls + add)
            }
        }

        return columns.map { (key, label) ->
            val sum = sums[key] ?: Sum()
            Bucket(label = label, turns = sum.turns, pings = sum.pings, stalls = sum.stalls)
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

    /**
     * The container the axis shows, in words, for the screen's caption.
     *
     * Replaces the old first-to-last span, which described a window. A container's name is the
     * question that was asked ("October 2026"), and the span is a fact about the columns, so the
     * caption says the name and the table's own headers say the rest.
     */
    fun span(axis: Axis, anchor: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        container(axis, anchor, zone).label
}
