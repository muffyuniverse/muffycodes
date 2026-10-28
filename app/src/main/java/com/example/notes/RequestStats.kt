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

    /** The three time axes the screen offers. */
    enum class Axis(val label: String, val buckets: Int) {
        /** The last twenty-four hours, one column per hour. */
        HOUR("hour", 24),

        /** The last thirty days, one column per day. */
        DAY("day", 30),

        /** The last twelve months, one column per month. */
        MONTH("month", 12),
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
     * Sums [rows] into [columns] buckets ending at [now].
     *
     * **The buckets are made from the clock, not from the data.** A day with no requests is a
     * column of zeroes, and that is the point: a table that only showed the hours something
     * happened would hide the shape of the day, which is what a person is looking for.
     */
    fun buckets(rows: List<Row>, axis: Axis, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<Bucket> {
        val at = Instant.ofEpochMilli(now).atZone(zone)
        // Each bucket is identified by a key, and a row is placed by computing its own key. Keys
        // rather than ranges because the three axes differ only in how a time becomes a key.
        fun keyOf(time: java.time.ZonedDateTime): String = when (axis) {
            Axis.HOUR -> time.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH"))
            Axis.DAY -> time.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            Axis.MONTH -> time.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM"))
        }
        fun labelOf(time: java.time.ZonedDateTime): String = when (axis) {
            // **The hour, truncated, because that is what the column is.** A column labelled
            // "09:30" that holds everything from 09:00 to 09:59 misnames itself, and the key it is
            // stored under is already truncated - so the label has to agree with the key.
            Axis.HOUR -> time.format(java.time.format.DateTimeFormatter.ofPattern("HH:00"))
            Axis.DAY -> time.format(java.time.format.DateTimeFormatter.ofPattern("dd MMM"))
            Axis.MONTH -> time.format(java.time.format.DateTimeFormatter.ofPattern("MMM yy"))
        }

        // The columns, oldest first, so the table reads left to right as time passing.
        val columns = (axis.buckets - 1 downTo 0).map { back ->
            val when_ = when (axis) {
                Axis.HOUR -> at.minusHours(back.toLong())
                Axis.DAY -> at.minusDays(back.toLong())
                Axis.MONTH -> at.minusMonths(back.toLong())
            }
            keyOf(when_) to labelOf(when_)
        }

        // A named accumulator rather than a Triple: three values of the SAME type in a Triple is a
        // shape whose order nobody can check by eye, and the compiler binds it to the wrong nesting
        // the moment the types repeat.
        data class Sum(val turns: Stats = Stats(), val pings: Stats = Stats(), val stalls: Stats = Stats())
        val sums = columns.associate { (key, _) -> key to Sum() }.toMutableMap()

        for (row in rows) {
            // **The instant, converted into the zone being displayed.** This is the whole of the
            // timezone story: the log records instants, the table converts them, and the axis is
            // therefore correct whatever the phone's clock is set to. Bucketing the stored clock
            // time instead would mean a row written in Berlin was counted in the Berlin hour even
            // while the phone was showing Tokyo's.
            val time = Instant.ofEpochMilli(row.atMs).atZone(zone)
            val key = keyOf(time)
            val cell = sums[key] ?: continue
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
            sums[key] = when (row.kind) {
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
     * How many days the log covers, so the screen can say what the table is of.
     *
     * **Counted from the rows rather than from the files**, now that a day is no longer a file: the
     * span a person cares about is the oldest response held, which is a fact about the rows.
     */
    fun days(rows: List<Row>, now: Long, zone: ZoneId = ZoneId.systemDefault()): Int {
        val oldest = rows.minOfOrNull { it.atMs } ?: return 0
        val from = Instant.ofEpochMilli(oldest).atZone(zone).toLocalDate()
        val to = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return java.time.temporal.ChronoUnit.DAYS.between(from, to).toInt() + 1
    }

    /** The span the axis names, in words, for the screen's own caption. */
    fun span(axis: Axis, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val at = Instant.ofEpochMilli(now).atZone(zone)
        val first = when (axis) {
            Axis.HOUR -> at.minusHours((axis.buckets - 1).toLong())
            Axis.DAY -> at.minusDays((axis.buckets - 1).toLong())
            Axis.MONTH -> at.minusMonths((axis.buckets - 1).toLong())
        }
        fun fmt(t: java.time.ZonedDateTime) = when (axis) {
            Axis.HOUR -> t.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"))
            Axis.DAY -> t.format(java.time.format.DateTimeFormatter.ofPattern("dd MMM"))
            Axis.MONTH -> t.format(java.time.format.DateTimeFormatter.ofPattern("MMM yy"))
        }
        return "${fmt(first)} to ${fmt(at)}"
    }
}
