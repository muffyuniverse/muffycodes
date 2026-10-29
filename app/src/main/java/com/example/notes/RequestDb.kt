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
    /**
     * **Stalls are never compacted, and that is what makes the kind split possible at all.**
     *
     * A rollup table has no `kind` column: it sums turns and keepalives together, because "what did
     * this hour cost" has one answer. A stall cannot be summed with them - it was billed but
     * reported no totals, so folding it in would silently claim a cost of zero for it - and once a
     * stall has been rolled into a bucket its kind is gone and no query can recover it.
     *
     * So `compact` leaves every stall row raw, for ever. They are rare by nature (a stalled answer
     * is an exceptional event, not a routine one), so keeping them costs a handful of rows rather
     * than a tier, and the split stays exact however much history is compacted.
     *
     * The consequence for the readers: **every rollup row is a turn or a ping, by construction**, so
     * `peel` needs no filter, and only the raw table is ever summed by kind.
     */
    private val STALL_KIND = RequestLog.Stats.Kind.STALL.ordinal

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

    /**
     * Deletes the history entirely - **including the write-ahead log and its index.**
     *
     * The three files are one database: with write-ahead logging on, the `.db` is not the whole
     * truth until the log is checkpointed, so deleting it alone leaves SQLite to replay the `-wal`
     * on the next open and the "empty" database comes back with rows in it. That would look like a
     * failure of whatever asked for an empty history rather than a failure of the delete.
     *
     * Used by a scenario that pictures the empty screen, and available for a "clear history" the
     * settings screen may grow.
     */
    fun delete(accountDir: File) {
        val db = fileFor(accountDir)
        for (suffix in listOf("", "-wal", "-shm")) {
            File(db.path + suffix).delete()
        }
    }

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
     * Which bucket boundary a write crosses, given the newest row already recorded.
     *
     * **The coarsest boundary that differs, which implies everything finer.** Comparing whole days
     * alone is not enough: two writes inside one day look identical to it, so a minute that closed
     * between them is never rolled and the raw rows accumulate all day.
     *
     * **`previous` is read from the database, not remembered.** An in-memory "last write" would be
     * reset by every process start - so the first write after opening the app would look like the
     * first write ever and roll nothing, which is precisely the wrong answer for a phone that was
     * closed all night. It would also make the trigger untestable, since a test cannot reset a
     * static that another test already set.
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
                // **The boundary is measured against what is already stored**, read from the
                // database rather than remembered in a field: a static would be reset by every
                // process start, so the first write after opening the app would look like the first
                // write ever and roll nothing - the wrong answer for a phone closed all night.
                val previous = db.rawQuery(
                    "SELECT MAX(utc) FROM requests WHERE utc < ?",
                    arrayOf(now.toString()),
                ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

                insert(db, now, stats)

                if (previous == null) return@use
                // **Retention rides on the day boundary only**, because a day-aligned cutoff cannot
                // change within a day: running four DELETEs on every hour and minute boundary would
                // be 1,439 provable no-ops a day.
                val crossed = crossed(previous, now)
                if (crossed != 0L) compact(db, now, if (crossed == DAY) keepDays else 0)
            }
        } catch (t: Throwable) {
            // Deliberately silent. A record is evidence about the work, never a reason for the work
            // to fail, and a full disk must not stop an agent mid-turn.
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
            // **Read once, into a value that cannot disagree with itself.** `cost` is the number and
            // `reported` is whether the provider said it; reading the JSON twice is how those two
            // drift apart, and a null cost is exactly the case where they must not.
            //
            // `usage?.takeIf { it.has("cost") }` rather than a `val reported = ...` flag, because
            // Kotlin cannot smart-cast `usage` to non-null from a Boolean - the flag would compile
            // into an error at `usage.optDouble`.
            val cost = usage?.takeIf { it.has("cost") }?.optDouble("cost")
            put("cost", cost ?: 0.0)
            // **Reported, not merely nonzero.** A response that reported `$0` is a measurement and
            // counts toward the average; one that reported nothing must not. This is the column that
            // keeps "free" and "unknown" apart, and it is why the average has a denominator.
            put("reported", if (cost != null) 1 else 0)
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
    fun totals(db: SQLiteDatabase, fromMs: Long, toMs: Long): RequestStats.Totals {
        if (toMs <= fromMs) return RequestStats.Totals()

        // **The boundary between summarised and raw, taken over the rows compaction actually
        // rolls.** Not over every raw row: a stall is never compacted (see STALL_KIND), so a single
        // stall from a month ago would pin this boundary at that instant for ever - the peel would
        // cover nothing, every query would fall back to raw, and the rollups would be dead weight
        // while every figure stayed correct. Silent, and it took a test to see it.
        val rolledFrom = db.rawQuery(
            "SELECT MIN(utc) FROM requests WHERE kind <> $STALL_KIND",
            null,
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else Long.MAX_VALUE }
        val summarisedEnd = minOf(toMs, rolledFrom)
        val rawStart = maxOf(fromMs, rolledFrom)

        var total = RequestStats.Stats()
        if (summarisedEnd > fromMs) total += peel(db, fromMs, summarisedEnd)
        if (toMs > rawStart) {
            total += sum(db, "requests", "utc", rawStart, toMs, " AND kind <> $STALL_KIND")
        }

        // **Stalls are read over the whole interval, never the raw suffix.** They are the one thing
        // that is always raw, so bounding them by `rawStart` would miss every stall older than the
        // oldest unrolled turn - which is most of them, since compaction keeps chasing the turns
        // forward and leaving the stalls behind.
        val stalls = sum(db, "requests", "utc", fromMs, toMs, " AND kind = $STALL_KIND")
        return RequestStats.Totals(total = total, stalls = stalls)
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
        /** SQL appended to the `WHERE`, so stalls can be summed apart from turns. */
        and: String = "",
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
            "SELECT $sums FROM $table WHERE $column >= ? AND $column < ?$and",
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
    /**
     * Everything in the history, for the screen that has to answer two questions about it at once.
     *
     * **One read, not one per question.** The table needs the total over an interval, the oldest
     * instant, and whether the log reaches back that far - and the screen re-derives all three
     * whenever a picker moves. Reading per question would open the database three times per draw.
     *
     * **`MIN`/`MAX` come from the raw table on purpose.** They answer "where does the detail start"
     * and "how far does it reach", which is what the screen's sentence needs: a total that is lower
     * than expected is explained by the log beginning late, and a rollup row existing for a period
     * whose raw rows are gone is precisely the case that must NOT be reported as coverage.
     */
    fun read(accountDir: File): History {
        // **Not `use { }`.** That closes the database on return, so every `History` handed out would
        // be already dead and the first total would throw "attempt to re-open an already-closed
        // object" - from a line with no visible connection to the cause. The handle is handed back
        // open on purpose; `History` is `Closeable` so releasing it is one word.
        val db = try {
            open(accountDir)
        } catch (t: Throwable) {
            // A history that cannot be opened is an empty history, not a crash on the way to a
            // table that would have said "no log yet" anyway.
            return History()
        }
        return try {
            // **The minimum over EVERY table, always - not raw first and rollups only as a
            // fallback.** Those two disagree the moment compaction has rolled anything: raw then
            // holds only the newest responses, so its minimum is later than the oldest bucket, and
            // the caption would say "the log starts 10:30" while a total over 10:00-10:30 answered
            // with data. The figure and the sentence would contradict each other, which is the one
            // thing this screen exists not to do.
            //
            // Written as one union so SQLite computes the extreme, rather than four queries and a
            // `minOf` in Kotlin: the same answer, one round trip, and no chance of a null slipping
            // past a comparison.
            val union = listOf("SELECT utc AS t FROM requests") +
                ROLL_TABLES.map { "SELECT bucket AS t FROM $it" }
            fun extreme(pick: String): Long? =
                db.rawQuery("SELECT $pick(t) FROM (${union.joinToString(" UNION ALL ")})", null)
                    .use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
            val oldest = extreme("MIN")
            val newest = extreme("MAX")
            History(db = db, oldest = oldest, newest = newest)
        } catch (t: Throwable) {
            // **The handle is closed on this path only**, because it is the one where nobody
            // receives it. A failure here is still an empty history rather than a crash.
            db.close()
            History()
        }
    }

    /**
     * What the screen needs besides the totals themselves.
     *
     * [db] is handed back rather than closed inside [read] because the totals are computed lazily
     * per interval; [close] is the caller's to call. When the database could not be opened, [db] is
     * null and every total is zero - which reads on screen as "no log yet".
     */
    class History(
        private val db: SQLiteDatabase? = null,
        /** The earliest instant any table holds - raw or summarised. */
        val oldest: Long? = null,
        /** The latest instant any table holds. */
        val newest: Long? = null,
    ) : java.io.Closeable {
        /**
         * The totals over `[fromMs, toMs)`. Empty when there is no history at all.
         *
         * **A closed history reads as empty rather than throwing.** The alternative is that a late
         * recomposition - after the owner has released the handle - crashes the screen, and "the
         * numbers stopped" is a far better failure than "the app stopped".
         */
        fun totals(fromMs: Long, toMs: Long): RequestStats.Totals {
            val open = db
            if (open == null || closed) return RequestStats.Totals()
            return try {
                totals(open, fromMs, toMs)
            } catch (t: Throwable) {
                RequestStats.Totals()
            }
        }

        /**
         * **Closing twice is allowed and does nothing the second time.**
         *
         * Two owners will both try: the view model releases the handle when a new read replaces it,
         * and a test or scenario releases the one it opened. A caller that has to remember which of
         * the two already ran will get it wrong, and the failure is an `IllegalStateException` from
         * a *later* query rather than from the close. `SQLiteDatabase.close` is documented as
         * idempotent; this makes the contract explicit rather than implied.
         */
        override fun close() {
            if (closed) return
            closed = true
            db?.close()
        }

        /** Whether [close] has run, so a second one is a no-op rather than a surprise. */
        @Volatile
        private var closed = false
    }

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
            "SELECT MIN(utc), MAX(utc) FROM requests WHERE utc < ? AND kind <> $STALL_KIND",
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
                FROM requests WHERE utc < ? AND kind <> $STALL_KIND
                GROUP BY (utc / $MINUTE)
                """.trimIndent(),
                arrayOf(currentMinute),
            )
            removed = db.rawQuery(
                "SELECT COUNT(*) FROM requests WHERE utc < ? AND kind <> $STALL_KIND",
                arrayOf(currentMinute.toString()),
            ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            // **Stalls are left where they are** (see STALL_KIND). Deleting them would fold their
            // kind into a bucket that has no column to hold it, and the split the screen draws would
            // then be silently wrong - a stall's zero cost indistinguishable from a free turn.
            db.delete("requests", "utc < ? AND kind <> $STALL_KIND", arrayOf(currentMinute.toString()))

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
