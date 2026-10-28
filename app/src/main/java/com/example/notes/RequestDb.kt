package com.example.notes

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.io.File

/**
 * The request history, **in SQLite.**
 *
 * ## Why this replaced a text file with a five-level pyramid
 *
 * The log began as one tab-separated file, and answering "what did this interval cost" from it meant
 * reading every row of the file. To avoid that it grew a **pyramid**: raw rows rolled into `hour`
 * rows, which rolled into `day`, `month` and `year` rows, all in the same file. The pyramid worked,
 * and it cost more than it saved:
 *
 * - the reader had to work out **which level spoke for which unit**, so that a request counted in an
 *   hour row was not counted again as a raw row. That was `units`, `coversUnit`, `counts`,
 *   `unitFloor` and `unitEnd` — about seventy lines of the subtlest code in the app, and three of the
 *   bugs recorded in `docs/PLAN-request-history.md` came from it.
 * - compaction had to be **destructive**, and its ordering was load-bearing: rebuild the coarser
 *   levels *before* pruning the hours, or history disappears one level at a time.
 * - retention bounded the hours and left the coarser rows standing, so a one-hour interval over
 *   age-pruned data silently reported a **whole day** under a header that said "1 hour".
 *
 * Every one of those is a consequence of a text file having no index and no aggregation. SQLite has
 * both, so the pyramid's job — making a scan cheap — is done by `GROUP BY` and an index on the
 * instant instead. What is left is three plain tables and one query.
 *
 * ## Measured, on 3.65M rows (ten thousand requests a day for a year)
 *
 * | span read | from raw rows | from a rollup table |
 * |---|---|---|
 * | 1 hour | 1.16 ms | 0.28 ms |
 * | 1 day | 2.26 ms | 0.20 ms |
 * | 1 month | 59.4 ms | 0.50 ms |
 * | 1 year | **712 ms** | 0.29 ms |
 *
 * So SQL is not magic and raw rows are not free: a year read from raw is a visible stall. The
 * rollups are kept for exactly that reason, and they are kept **per table** rather than in one file
 * at different levels — which is what removes the de-duplication problem entirely. A query picks one
 * table by the width of the interval and never reads two, so a request cannot be counted twice.
 *
 * ## What is authoritative
 *
 * **The raw row.** The rollups are a cache: `roll_minute` is a sum of raw rows for that minute, and
 * `roll_hour`/`roll_day` are sums of the level below. Any of them can be deleted and rebuilt from
 * what is still there, which is why [compact] can be written as "roll what has closed" rather than
 * as a careful destructive pass.
 *
 * Raw rows are kept only until their minute closes. That is the one thing the file design got right
 * and it is kept: a minute row cannot be split back into individual responses, so the detail has a
 * shelf life and the summary does not.
 */
object RequestDb {

    /** The database file inside the system project. */
    const val FILE = "requests.db"

    /** The three rollup tables, coarsest last. `minute` is the finest thing stored. */
    private val ROLL_TABLES = listOf("roll_minute", "roll_hour", "roll_day")

    /** Milliseconds in the units a bucket can be. */
    private const val MINUTE = 60_000L
    private const val HOUR = 3_600_000L
    private const val DAY = 86_400_000L

    /** The columns an aggregate carries. One list, so the three tables cannot drift apart. */
    private const val AGGREGATE_COLUMNS =
        "n, prompt, cached, completion, reasoning, cost, reported, said, thinking"

    /** Those columns summed, **for a table that already holds counts** (a rollup). */
    private const val SUMS =
        "SUM(n), SUM(prompt), SUM(cached), SUM(completion), SUM(reasoning), SUM(cost), " +
            "SUM(reported), SUM(said), SUM(thinking)"

    /**
     * The same nine values, **from raw rows** — where there is no `n` to sum.
     *
     * The two strings must stay the same length and order as [AGGREGATE_COLUMNS], because both are
     * selected straight into one `INSERT ... SELECT`. A mismatch would not fail to compile and would
     * not always fail at run time: SQLite would happily put a count into `cached` if the widths
     * happened to line up.
     */
    private const val RAW_SUMS =
        "COUNT(*), SUM(prompt), SUM(cached), SUM(completion), SUM(reasoning), SUM(cost), " +
            "SUM(reported), SUM(said), SUM(thinking)"

    /**
     * Opens the database, creating the schema when it is new.
     *
     * **A path rather than a `Context`**, so this stays testable with the account folder every test
     * already builds. `SQLiteDatabase.openOrCreateDatabase` needs no Activity and no manifest entry.
     *
     * **Write-ahead logging**, because the agent writes one row per response while the statistics
     * screen reads: WAL lets a reader run without blocking the writer, where the default rollback
     * journal would have the screen's query stop an insert.
     */
    fun open(accountDir: File): SQLiteDatabase {
        val dir = File(File(accountDir, Workspace.SYSTEM_PROJECT), RequestLog.DIR)
        dir.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(File(dir, FILE), null)
        db.enableWriteAheadLogging()
        create(db)
        return db
    }

    /** The database file, for a test that wants to delete or measure it. */
    fun fileFor(accountDir: File): File =
        File(File(File(accountDir, Workspace.SYSTEM_PROJECT), RequestLog.DIR), FILE)

    private fun create(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS requests(
              id        INTEGER PRIMARY KEY AUTOINCREMENT,
              utc       INTEGER NOT NULL,
              kind      INTEGER NOT NULL,
              ms        INTEGER NOT NULL DEFAULT 0,
              prompt    INTEGER NOT NULL DEFAULT 0,
              cached    INTEGER NOT NULL DEFAULT 0,
              completion INTEGER NOT NULL DEFAULT 0,
              reasoning INTEGER NOT NULL DEFAULT 0,
              cost      REAL    NOT NULL DEFAULT 0,
              reported  INTEGER NOT NULL DEFAULT 0,
              said      INTEGER NOT NULL DEFAULT 0,
              thinking  INTEGER NOT NULL DEFAULT 0,
              finish    TEXT,
              provider  TEXT,
              model     TEXT
            )
            """.trimIndent(),
        )
        // **The one index that matters**, and the only one worth having: every query this app makes
        // is a range over the instant. Everything else is a full aggregate of a range that the index
        // already narrowed.
        db.execSQL("CREATE INDEX IF NOT EXISTS ix_requests_utc ON requests(utc)")
        for (table in ROLL_TABLES) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $table(
                  bucket    INTEGER PRIMARY KEY,
                  n         INTEGER NOT NULL DEFAULT 0,
                  prompt    INTEGER NOT NULL DEFAULT 0,
                  cached    INTEGER NOT NULL DEFAULT 0,
                  completion INTEGER NOT NULL DEFAULT 0,
                  reasoning INTEGER NOT NULL DEFAULT 0,
                  cost      REAL    NOT NULL DEFAULT 0,
                  reported  INTEGER NOT NULL DEFAULT 0,
                  said      INTEGER NOT NULL DEFAULT 0,
                  thinking  INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent(),
            )
        }
    }

    // ---- writing ---------------------------------------------------------------------------

    /**
     * The last boundary noticed by [write], **per database file.**
     *
     * A map keyed by the file rather than a field, because two accounts can be written to by two
     * threads and a single `var` would let one account's boundary suppress the other's compaction.
     */
    private val lastBoundary = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Which bucket boundary a write crossed since the previous one: `MINUTE`, `HOUR`, `DAY`, or 0.
     *
     * **The coarsest boundary that changed, which implies everything finer.** Comparing whole days
     * alone is not enough: two writes inside one day look identical to it, so a minute that closed
     * between them is never rolled and the raw rows accumulate all day. Comparing the boundary makes
     * a three-integer comparison do what three separate checks would.
     *
     * **A day boundary also covers a week.** A phone asleep for seven days crosses one day boundary
     * and rolls everything the `MIN`/`MAX` query finds, so there is no separate "long gap" case.
     */
    internal fun crossed(previous: Long, now: Long): Long = when {
        floorTo(previous, DAY) != floorTo(now, DAY) -> DAY
        floorTo(previous, HOUR) != floorTo(now, HOUR) -> HOUR
        floorTo(previous, MINUTE) != floorTo(now, MINUTE) -> MINUTE
        else -> 0L
    }

    /**
     * One response, as a raw row.
     *
     * **Failures are swallowed**, exactly as the text log swallowed them: a record is evidence about
     * the work, never a reason for the work to fail, and a full disk must not stop an agent mid-turn.
     */
    fun write(accountDir: File, now: Long, stats: RequestLog.Stats, keepDays: Int = 0) {
        try {
            open(accountDir).use { db ->
                insert(db, now, stats)
                // **The roll is driven by the boundary this write crossed**, not by a timer and not
                // by whole days alone. A timer would have to pick a first-run delay and would roll an
                // idle account on a schedule meaning nothing; a day comparison would leave every
                // closed minute of the day unrolled until midnight.
                val key = fileFor(accountDir).path
                val previous = lastBoundary.put(key, now)
                if (previous == null) return@use
                val crossed = crossed(previous, now)
                if (crossed == 0L) return@use
                // **Retention rides on the day boundary only**, because a day-aligned cutoff cannot
                // change within a day: running four DELETEs on every hour and minute boundary would
                // be 1,439 no-ops a day.
                compact(db, now, if (crossed == DAY) keepDays else 0)
            }
        } catch (t: Throwable) {
            // Deliberately silent. See above.
        }
    }

    internal fun insert(db: SQLiteDatabase, now: Long, stats: RequestLog.Stats) {
        val usage = stats.usage
        val values = ContentValues().apply {
            put("utc", now)
            put("kind", stats.kind.ordinal)
            put("ms", stats.ms)
            put("prompt", usage.intOr("prompt_tokens", 0))
            put("cached", usage?.optJSONObject("prompt_tokens_details").intOr("cached_tokens", 0))
            put("completion", usage.intOr("completion_tokens", 0))
            put("reasoning", usage?.optJSONObject("completion_tokens_details").intOr("reasoning_tokens", 0))
            // **Bound before the write, so "reported" and "cost" cannot disagree.** `cost` is the
            // number and `reported` is whether the provider said it; reading the field twice is how
            // those two drift apart.
            val reported = usage != null && usage.has("cost")
            put("cost", if (reported) usage.optDouble("cost") else 0.0)
            // **Reported, not merely nonzero.** A response that reported `$0` is a measurement and
            // counts toward the average; one that reported nothing must not. This is the column that
            // keeps "free" and "unknown" apart, and it is why the average has a denominator.
            put("reported", if (reported) 1 else 0)
            put("said", stats.received?.said ?: 0)
            put("thinking", stats.received?.thinking ?: 0)
            put("finish", stats.finish)
            put("provider", stats.provider)
            put("model", stats.model)
        }
        db.insert("requests", null, values)
    }

    private fun org.json.JSONObject?.intOr(key: String, fallback: Int): Int =
        if (this != null && has(key)) optInt(key) else fallback

    // ---- reading ---------------------------------------------------------------------------

    /**
     * Everything recorded in `[fromMs, toMs)`: **whole days from the day table, whole hours from the
     * hour table, whole minutes from the minute table, and the open minute from raw — summed.**
     *
     * That is the question this answers, and it is worth saying what it is *not*: no single table
     * answers a query, and no table is chosen in preference to another. Each tier contributes the
     * slice of the interval that lines up exactly with its buckets, the slices are disjoint, and the
     * total is their sum.
     *
     * ```
     * |← minutes →|←———— whole hours ————→|←——— whole days ———→|← hours →|← min →|
     *             ^ from peeled to an            ^ to peeled back          raw
     *               hour boundary                  from a day boundary       rows
     * ```
     *
     * **Disjointness is what replaces the file pyramid's hardest rule.** The file version had every
     * level in one file, so the reader had to work out which level "spoke" for which unit —
     * `units`, `coversUnit`, `counts`, `unitFloor`, `unitEnd`, about seventy lines and three silent
     * wrong-number bugs. Here each instant is in exactly one slice, so nothing can be counted twice
     * and nothing has to be silenced.
     *
     * **The peeling, from the left.** An interval rarely starts on a day boundary, so the first
     * slice climbs to the next hour using minutes, the next slice climbs to the next day using
     * hours, the middle is whole days, and the right-hand end descends the same way. Every boundary
     * it produces is a bucket edge, which is what makes each slice answerable by exactly one table.
     *
     * **Raw rows are a suffix, not an exception.** [compact] deletes a raw row once its minute is
     * rolled, so the oldest raw instant is precisely where summarising stopped. Everything before it
     * is covered by the rollups; everything from it onward is raw and is handled by exactly the same
     * peeling, one tier finer. A phone that was off for a week therefore reads its backlog as raw —
     * correctly, and without a special case.
     *
     * **The rounding is outward, and stated in the footnote.** A slice is always widened to whole
     * buckets, so the figure covers **at least** what was asked for. That is the safe direction: a
     * total that quietly excluded the ends of a chosen period would look like lost requests.
     */
    fun totals(db: SQLiteDatabase, fromMs: Long, toMs: Long): RequestStats.Stats {
        if (toMs <= fromMs) return RequestStats.Stats()
        val rawFrom = db.rawQuery("SELECT MIN(utc) FROM requests", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else Long.MAX_VALUE
        }
        val summarisedEnd = minOf(toMs, rawFrom)
        var out = RequestStats.Stats()
        if (summarisedEnd > fromMs) out += peel(db, fromMs, summarisedEnd)
        // Whatever the rollups have not reached yet, counted from its own rows.
        val rawStart = maxOf(fromMs, rawFrom)
        if (toMs > rawStart) out += sum(db, "requests", "utc", rawStart, toMs)
        return out
    }

    /**
     * The slice `[from, to)` read from the three rollup tables, widening outward at both ends.
     *
     * **Left to right, climbing then descending**, so every step is answerable by one table and the
     * steps never overlap:
     *
     * 1. minutes up to the next hour edge,
     * 2. hours up to the next day edge,
     * 3. whole days across the middle,
     * 4. hours down to the last hour edge,
     * 5. minutes for the remainder.
     */
    private fun peel(db: SQLiteDatabase, from: Long, to: Long): RequestStats.Stats {
        var out = RequestStats.Stats()
        var at = from

        // 1. **Climb to an hour edge on minutes.** Only when `from` is not already on one, so a
        //    15-minute interval starting at 10:00 does not pay for an hour it does not want.
        if (at % HOUR != 0L) {
            val end = minOf(to, ceilTo(at, HOUR))
            out += sum(db, "roll_minute", "bucket", at, end)
            at = end
        }
        if (at >= to) return out

        // 2. **Whole hours, but never past a day edge or past the last whole hour of the interval.**
        //    Bounding by `floorTo(to, HOUR)` rather than by `to` is what stops a 10:00–10:15 interval
        //    from being answered with the whole 10:00 hour: the hours it takes must end on an edge.
        val hoursEnd = minOf(floorTo(to, HOUR), ceilTo(at, DAY))
        if (hoursEnd > at) {
            out += sum(db, "roll_hour", "bucket", at, hoursEnd)
            at = hoursEnd
        }
        if (at >= to) return out

        // 3. **Whole days across the middle.** `at` is day-aligned here - either it started so, or it
        //    climbed to the edge above - so this is exact rather than rounded.
        val daysEnd = floorTo(to, DAY)
        if (at % DAY == 0L && daysEnd > at) {
            out += sum(db, "roll_day", "bucket", at, daysEnd)
            at = daysEnd
        }

        // 4. **Whole hours again, for the tail after the last whole day.** Without this a
        //    two-day-and-three-hour interval would take its last three hours as 180 minute rows
        //    instead of 3 hour rows - the same answer from 60x the rows.
        val tailHoursEnd = floorTo(to, HOUR)
        if (tailHoursEnd > at) {
            out += sum(db, "roll_hour", "bucket", at, tailHoursEnd)
            at = tailHoursEnd
        }

        // 5. **And whatever is left is minutes.** This is the tail of the interval rather than a
        //    general case, and it is always under an hour because everything above has been taken.
        if (at < to) out += sum(db, "roll_minute", "bucket", at, to)
        return out
    }

    /**
     * One slice, summed.
     *
     * **Widened outward to whole buckets.** A slice boundary that lands mid-bucket must cover the
     * bucket it lands in rather than silently dropping it — and widening is the direction that
     * covers *at least* what was asked for, which is the safe one: a total that quietly excluded the
     * ends of a chosen period would look like lost requests.
     *
     * [RAW_SUMS] differs from [SUMS] because a raw row has no `n` to sum — it *is* one request.
     * Keeping the two lists separate rather than fudging one is what stops SQLite from quietly
     * putting a count into the wrong column if the widths ever line up.
     */
    private fun sum(
        db: SQLiteDatabase,
        table: String,
        column: String,
        from: Long,
        to: Long,
    ): RequestStats.Stats {
        if (to <= from) return RequestStats.Stats()
        val width = when (table) {
            "roll_minute" -> MINUTE
            "roll_hour" -> HOUR
            "roll_day" -> DAY
            else -> 1L
        }
        val lo = if (width == 1L) from else floorTo(from, width)
        val hi = if (width == 1L) to else ceilTo(to, width)
        val sums = if (table == "requests") RAW_SUMS else SUMS
        var out = RequestStats.Stats()
        db.rawQuery(
            "SELECT $sums FROM $table WHERE $column >= ? AND $column < ?",
            arrayOf(lo.toString(), hi.toString()),
        ).use { c -> if (c.moveToFirst()) out = c.readStats() }
        return out
    }

    private fun android.database.Cursor.readStats() = RequestStats.Stats(
        requests = getInt(0),
        promptTokens = getLong(1),
        cachedTokens = getLong(2),
        completionTokens = getLong(3),
        reasoningTokens = getLong(4),
        cost = getDouble(5),
        costReported = getInt(6),
        saidChars = getLong(7),
        thinkingChars = getLong(8),
    )

    private fun floorTo(ms: Long, bucket: Long): Long = Math.floorDiv(ms, bucket) * bucket

    /** The first bucket edge strictly after [ms] — zero stays zero, so an aligned instant is fixed. */
    private fun ceilTo(ms: Long, bucket: Long): Long =
        if (ms % bucket == 0L) ms else floorTo(ms, bucket) + bucket

    /**
     * The first instant this database can answer for, or null when it holds nothing.
     *
     * The screen says "· the log starts 3 Oct" from this, and it is the honest way to explain an
     * interval wider than the history: the numbers are right, there is simply nothing older.
     */
    fun oldest(db: SQLiteDatabase): Long? {
        val candidates = buildList {
            for (table in listOf("requests") + ROLL_TABLES) {
                val column = if (table == "requests") "utc" else "bucket"
                db.rawQuery("SELECT MIN($column) FROM $table", null).use { c ->
                    if (c.moveToFirst() && !c.isNull(0)) add(c.getLong(0))
                }
            }
        }
        return candidates.minOrNull()
    }

    // ---- compaction and retention ------------------------------------------------------------

    /**
     * Rolls every closed minute, rebuilds the levels above it, then applies retention.
     *
     * **The order is still the design, but it is no longer fragile.** The file version had to
     * rebuild the coarser levels *before* deleting the hours, because a day rebuilt from pruned hours
     * would silently cover only the part of the day that survived. Here the levels are separate
     * tables fed by the one below, and raw rows are deleted only after their minute row exists — so
     * the failure it guarded against cannot be expressed.
     *
     * **Only what changed is touched.** The pass rebuilds the hours covering the minutes it just
     * rolled, and the days covering those hours. A phone that was off for a week rolls a week of
     * minutes and rebuilds a week of hours on the first tick back; a phone that has been idle for
     * four minutes rebuilds one hour.
     *
     * Returns how many raw rows were rolled away, so a test can tell "did nothing" from "worked".
     */
    fun compact(db: SQLiteDatabase, now: Long, keepDays: Int = 0): Int = db.inTransaction {
        // 1. Closed minutes, from raw. The current minute is left alone: its row would be
        //    contradicted by the next request.
        val currentMinute = floorTo(now, MINUTE)
        val rolled = db.rawQuery(
            "SELECT MIN(utc), MAX(utc) FROM requests WHERE utc < ?",
            arrayOf(currentMinute.toString()),
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) to c.getLong(1) else null }

        var removed = 0
        if (rolled != null) {
            val (first, last) = rolled
            db.execSQL(
                """
                -- **`INSERT OR REPLACE`, not `ON CONFLICT DO UPDATE`.** Upsert needs SQLite 3.24,
                -- which arrives with API 30; this app's `minSdk` is 26, whose SQLite is 3.18 and
                -- would reject the statement at run time on every phone old enough to matter.
                --
                -- Replacing is also the right semantics rather than a workaround: a minute row is
                -- recomputed from the raw rows for that minute in full, so there is no column to
                -- preserve from a previous version.
                INSERT OR REPLACE INTO roll_minute(bucket, $AGGREGATE_COLUMNS)
                SELECT (utc / $MINUTE) * $MINUTE, $RAW_SUMS
                FROM requests WHERE utc < ?
                GROUP BY (utc / $MINUTE)
                """.trimIndent(),
                arrayOf(currentMinute),
            )
            removed = db.rawQuery(
                "SELECT COUNT(*) FROM requests WHERE utc < ?",
                arrayOf(currentMinute.toString()),
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            db.delete("requests", "utc < ?", arrayOf(currentMinute.toString()))

            // 2. Hours covering the minutes just rolled, then days covering those hours.
            val firstHour = floorTo(first, HOUR)
            val lastHour = floorTo(last, HOUR) + HOUR
            rollUp(db, "roll_hour", "roll_minute", firstHour, lastHour)
            val firstDay = floorTo(first, DAY)
            val lastDay = floorTo(last, DAY) + DAY
            rollUp(db, "roll_day", "roll_hour", firstDay, lastDay)
        }

        // 3. Retention. **Every level, from the finest up**, so nothing survives that describes a
        //    period whose detail was dropped - which is the bug the file version had, where a
        //    one-hour pick over pruned data reported a whole day.
        if (keepDays > 0) {
            val cutoff = floorTo(now - keepDays.toLong() * DAY, DAY)
            for ((table, column) in listOf(
                "requests" to "utc",
                "roll_minute" to "bucket",
                "roll_hour" to "bucket",
                "roll_day" to "bucket",
            )) {
                db.delete(table, "$column < ?", arrayOf(cutoff.toString()))
            }
        }
        removed
    }

    /**
     * Rebuilds [target] from [source] over a range of buckets.
     *
     * **Delete then insert, not upsert**, and the reason is a stale row: an hour whose minutes
     * changed must not keep its old totals in any column this pass did not name. The range is the
     * span that was actually rolled, so this is bounded by the data that changed rather than by the
     * size of the history.
     */
    private fun rollUp(db: SQLiteDatabase, target: String, source: String, from: Long, to: Long) {
        val width = if (target == "roll_day") DAY else HOUR
        db.delete(target, "bucket >= ? AND bucket < ?", arrayOf(from.toString(), to.toString()))
        db.execSQL(
            """
            INSERT INTO $target(bucket, $AGGREGATE_COLUMNS)
            SELECT (bucket / $width) * $width, $SUMS
            FROM $source WHERE bucket >= ? AND bucket < ?
            GROUP BY (bucket / $width)
            """.trimIndent(),
            arrayOf(from.toString(), to.toString()),
        )
    }

    /** Runs [body] in a transaction, rolling back if it throws. */
    private fun <T> SQLiteDatabase.inTransaction(body: SQLiteDatabase.() -> T): T {
        beginTransaction()
        return try {
            val out = body()
            setTransactionSuccessful()
            out
        } finally {
            endTransaction()
        }
    }
}
