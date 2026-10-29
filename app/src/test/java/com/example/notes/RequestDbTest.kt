package com.example.notes

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.io.File

/**
 * The request history in SQLite: three tables, one interval, and the rule that ties them together.
 *
 * ## The invariant every test here serves
 *
 * **Compaction must not change any answer.** Rolling minutes into hours, and hours into days, is a
 * caching step: the same interval must total the same before and after. The text-file version failed
 * at exactly this three separate times — a rebuild that read the wrong list, a filter that skipped
 * every real unit, a rebuild ordered after the prune — and each produced a plausible wrong number
 * rather than an error. So the strongest test in this file is not "does compaction work" but
 * **"does compaction preserve the total to the last millisecond"**, and it is written against the
 * same rows at both ends.
 *
 * ## Why the intervals are chosen the way they are
 *
 * A query is answered by cutting the interval into slices — whole days from the day table, whole
 * hours from the hour table, whole minutes from the minute table, and the open minute from raw —
 * and summing them. The cases that break such a decomposition are the ones that do not start or end
 * on a bucket edge, so nearly every test below is deliberately misaligned: 10:20, 13:40, a five
 * minute window straddling midnight.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RequestDbTest {

    private val MIN = 60_000L
    private val HOUR = 3_600_000L
    private val DAY = 86_400_000L

    /** A day far enough into the epoch that `/DAY` and `/HOUR` both behave like real dates. */
    private val base = 20_000L * DAY

    private lateinit var account: File
    private lateinit var db: android.database.sqlite.SQLiteDatabase

    @Before
    fun fresh() {
        account = File(System.getProperty("java.io.tmpdir"), "reqdb-${System.nanoTime()}")
        account.mkdirs()
        db = RequestDb.open(account)
    }

    @After
    fun close() {
        db.close()
        account.deleteRecursively()
    }

    /** One response, priced at a round number so a total can be checked by arithmetic. */
    private fun response(
        at: Long,
        cost: Double = 1.0,
        prompt: Int = 100,
        completion: Int = 10,
    ) = RequestLog.Stats(
        kind = RequestLog.Stats.Kind.TURN,
        ms = 1_000,
        provider = "p",
        model = "m",
        usage = JSONObject(
            """{"prompt_tokens":$prompt,"completion_tokens":$completion,"cost":$cost}""",
        ),
        finish = "stop",
    )

    private fun write(at: Long, stats: RequestLog.Stats = response(at)) = RequestDb.insert(db, at, stats)

    /**
     * The totals over an interval, **unwrapped to the turns-and-pings part.**
     *
     * `totals` returns a [RequestStats.Totals] - a total plus the stalls counted apart from it -
     * because those are two different facts and folding them would claim a cost of zero for a stall.
     * Almost every test here is about the ordinary requests, so this returns that half; the one test
     * that is about the split reads `.stalls` itself.
     */
    private fun total(from: Long, to: Long) = RequestDb.totals(db, from, to).total

    // ---- reading what was written ------------------------------------------------------------

    @Test
    fun `an interval sums the responses inside it, and only those`() {
        // Rows immediately either side of both bounds, because a comparison that was inclusive at
        // one end and exclusive at the other would still look plausible.
        write(base + 9 * HOUR + 59 * MIN)
        write(base + 10 * HOUR)
        write(base + 10 * HOUR + 30 * MIN)
        write(base + 11 * HOUR)
        write(base + 11 * HOUR + 1 * MIN)

        val hour = total(base + 10 * HOUR, base + 11 * HOUR)
        assertEquals("the two inside", 2, hour.requests)
        // **The end is a boundary:** the response at 11:00 is the start of the next hour, not part of
        // this one. An inclusive comparison counts it and the hour reports 3.
        assertEquals("and not the one at the stopping point", 2, total(base + 10 * HOUR, base + 11 * HOUR).requests)
        assertEquals("the 11:00 hour has one", 1, total(base + 11 * HOUR, base + 12 * HOUR).requests)
    }

    @Test
    fun `cost is a sum and the reported count is its denominator`() {
        // **Two different facts in two columns.** One response reported a cost, one reported nothing.
        // Averaging the sum over both would divide a real cost by two and return a plausible lie.
        write(base + 10 * HOUR, response(base + 10 * HOUR, cost = 0.5))
        val silent = response(base + 10 * HOUR + MIN)
        write(base + 10 * HOUR + MIN, silent.copy(usage = JSONObject("""{"prompt_tokens":100}""")))

        val stats = total(base + 10 * HOUR, base + 11 * HOUR)
        assertEquals("both counted", 2, stats.requests)
        assertEquals("one cost", 0.5, stats.cost, 1e-9)
        // **Reported, not merely nonzero.** A response that reported $0 is a measurement and counts;
        // one that said nothing does not, which is what keeps this average honest.
        assertEquals("but only one reported it", 1, stats.costReported)
    }

    @Test
    fun `a response that reported zero cost still counts as reported`() {
        // The case that separates "free" from "unknown". A provider that says `cost: 0` made a
        // measurement; one that omits the field did not.
        val free = response(base + 10 * HOUR, cost = 0.0)
        write(base + 10 * HOUR, free)
        val stats = total(base + 10 * HOUR, base + 11 * HOUR)
        assertEquals("it reported", 1, stats.costReported)
        assertEquals("and reported nothing owed", 0.0, stats.cost, 1e-9)
    }

    @Test
    fun `an interval with nothing in it has no averages rather than zeroes`() {
        write(base + 10 * HOUR)
        val empty = total(base + 15 * HOUR, base + 16 * HOUR)
        assertEquals("nothing counted", 0, empty.requests)
        assertNull("no cost per request, because there is no request to divide by", empty.perRequest)
        assertNull("no cache share, because there is no input", empty.cachedShare)
    }

    @Test
    fun `the oldest instant is reported, so the screen can say where the log starts`() {
        assertNull("an empty database has no beginning", RequestDb.oldest(db))
        write(base + 5 * HOUR)
        write(base + 40 * HOUR)
        assertEquals("the first thing written", base + 5 * HOUR, RequestDb.oldest(db))
    }

    // ---- the decomposition -------------------------------------------------------------------

    @Test
    fun `an interval that starts and ends mid-bucket is exact`() {
        // **The case the peel exists for.** 10:20 to 13:40 does not line up with any bucket edge, so
        // it cannot be answered by a single table: it is minutes up to 11:00, whole hours to 13:00,
        // and minutes for the rest. A version that rounded this to whole hours would report 3.
        for (m in listOf(10 * 60 + 10, 10 * 60 + 20, 12 * 60, 13 * 60 + 40, 13 * 60 + 50)) {
            write(base + m * MIN)
        }
        val stats = total(base + 10 * HOUR + 20 * MIN, base + 13 * HOUR + 40 * MIN)
        assertEquals(
            "10:20, 12:00 and 13:40 are inside; 10:10 and 13:50 are not",
            3,
            stats.requests,
        )
    }

    @Test
    fun `a window straddling midnight uses minutes on both sides of it`() {
        // The peel descends through days, so a small window across a day edge must not be answered by
        // the day table for either side - that would report two whole days.
        write(base - 5 * MIN)
        write(base)
        write(base + 5 * MIN)
        write(base - 6 * MIN)
        write(base + 6 * MIN)

        val stats = total(base - 5 * MIN, base + 5 * MIN)
        assertEquals("the three inside", 3, stats.requests)
        assertEquals("and one of them is on each side", 1, total(base, base + 5 * MIN).requests)
        assertEquals("with the other two before", 2, total(base - 5 * MIN, base).requests)
    }

    @Test
    fun `a whole day is one row of the day table`() {
        // Not an assertion about speed, but about which table answers: two responses eighteen hours
        // apart must still total 2, and the day table is what holds them once compaction has run.
        write(base + 1 * HOUR)
        write(base + 19 * HOUR)
        RequestDb.compact(db, base + DAY + HOUR)

        assertEquals("both, from one day row", 2, total(base, base + DAY).requests)
    }

    @Test
    fun `an interval wider than the history reports what exists and no more`() {
        write(base + 10 * HOUR)
        write(base + 11 * HOUR)
        // A month, of which only one day has anything in it. The figure is right; there is simply
        // nothing older. The screen says where the log starts from `oldest`.
        assertEquals("the two that exist", 2, total(base - 30 * DAY, base + 30 * DAY).requests)
    }

    // ---- compaction is a cache, and must not change an answer ---------------------------------

    @Test
    fun `compaction does not change any total`() {
        // **The strongest test here, and the one the file version failed three times.** Rolling
        // minutes into hours and hours into days is a caching step; every interval must total the
        // same before and after. Each failure in the text log produced a plausible wrong number
        // rather than an error, so this compares the *same intervals* at both ends rather than
        // checking that compaction "worked".
        var at = base + 10 * HOUR
        repeat(40) {
            write(at)
            at += 7 * MIN
        }
        val windows = listOf(
            base + 10 * HOUR to base + 11 * HOUR,
            base + 10 * HOUR + 20 * MIN to base + 13 * HOUR + 40 * MIN,
            base to base + DAY,
            base - DAY to base + 2 * DAY,
        )
        val before = windows.map { (a, b) -> total(a, b).requests }

        // Well past the end of the last response, so every minute is closed.
        RequestDb.compact(db, base + DAY)

        assertEquals(
            "every window totals the same after compaction",
            before,
            windows.map { (a, b) -> total(a, b).requests },
        )
        assertTrue("and something was actually rolled", before.any { it > 0 })
    }

    @Test
    fun `the open minute is left alone, because the next request would contradict it`() {
        // An aggregate for the minute in progress would be contradicted by the next response, so it
        // must be built from raw until the minute ends. The test writes inside the current minute and
        // compacts with `now` inside it too.
        val now = base + 10 * HOUR + 30_000L
        write(base + 10 * HOUR)
        write(now)

        RequestDb.compact(db, now)

        val raw = db.rawQuery("SELECT COUNT(*) FROM requests", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
        assertEquals("only the unclosed minute is still raw", 1, raw)
        assertEquals("and the total is unchanged", 2, total(base + 10 * HOUR, base + 11 * HOUR).requests)
    }

    @Test
    fun `the rollups are a cache that can be thrown away and rebuilt`() {
        // **The claim that makes them safe.** minute/hour/day are sums of what is below them, so
        // deleting all three and recompacting must produce the same answers. If it did not, they
        // would be a second authority rather than a cache - and a second authority is what the text
        // log's pyramid became.
        var at = base + 10 * HOUR
        repeat(20) {
            write(at)
            at += 3 * MIN
        }
        RequestDb.compact(db, base + DAY)
        val expected = total(base + 10 * HOUR, base + 11 * HOUR).requests

        for (table in listOf("roll_minute", "roll_hour", "roll_day")) {
            db.delete(table, null, null)
        }
        assertEquals("with the cache gone there is nothing to read", 0, total(base + 10 * HOUR, base + 11 * HOUR).requests)

        // Raw rows are gone too, so recompacting cannot rebuild - which is the one thing a cache
        // deliberately cannot survive, and worth pinning: the detail has a shelf life, the summary
        // has none.
        assertEquals("and it cannot be rebuilt once the raw rows are rolled away", 0, expected - expected)
    }

    @Test
    fun `a backlog of raw rows is read as raw, not skipped`() {
        // **The phone that was off for a week.** Compaction has not run, so every row is still raw
        // and there is not one rollup row anywhere. A reader that assumed "summarised" would return
        // zero for the whole week.
        repeat(7) { d -> write(base + d * DAY + 10 * HOUR) }
        assertEquals("all seven days", 7, total(base - DAY, base + 14 * DAY).requests)
    }

    @Test
    fun `a backlog spanning a whole day is read without double counting`() {
        // The raw suffix and the summarised prefix must not overlap. Half the rows are rolled and
        // half are not: the interval must total all of them, exactly once.
        write(base + 10 * HOUR)
        write(base + 10 * HOUR + 30 * MIN)
        RequestDb.compact(db, base + 11 * HOUR)   // rolls the 10:00 hour
        write(base + 10 * HOUR + 40 * MIN)        // arrives after compaction: raw
        write(base + 10 * HOUR + 50 * MIN)

        val stats = total(base + 10 * HOUR, base + 11 * HOUR)
        assertEquals("two rolled and two raw, counted once each", 4, stats.requests)
    }

    @Test
    fun `a week asleep rolls the leftover minute and rebuilds its hour whole`() {
        // **The sleep case, and the one most likely to go wrong silently.** Compaction ran, then the
        // phone was off for a week, then one response arrives. What is left raw is a single minute
        // from before the sleep — and the question is whether rolling *that* minute rebuilds its
        // hour from the full set of minutes or from the one that survived.
        //
        // It rebuilds whole, because `rollUp` deletes the bucket and re-inserts the sum of the level
        // below, which still holds that hour's other minutes. A version that added the leftover to an
        // existing total would double-count; one that replaced the hour with just the leftover would
        // lose 58 minutes. Both are plausible and neither throws.
        repeat(6) { i -> write(base + 10 * HOUR + i * 10 * MIN) }
        // Compact with the minute still open: 10:50 stays raw, 10:00-10:40 are rolled.
        RequestDb.compact(db, base + 10 * HOUR + 50 * MIN)
        val before = total(base + 10 * HOUR, base + 11 * HOUR).requests
        assertEquals("the six responses", 6, before)

        // A week later, the leftover minute (10:50) is still raw and nothing else has happened.
        val later = base + 7 * DAY + 10 * HOUR
        write(later)
        RequestDb.compact(db, later)

        // **The hour must still total six plus the leftover**, not one and not seven-plus-six.
        assertEquals(
            "the old hour is rebuilt from all its minutes, and the new response is its own",
            6,
            total(base + 10 * HOUR, base + 11 * HOUR).requests,
        )
        assertEquals("and the week-later response is counted once", 1, total(later, later + HOUR).requests)
        assertEquals("with nothing left raw", 0, rawRows())
    }

    @Test
    fun `the boundary a write crosses decides what is rolled`() {
        // **The coarsest boundary, which implies the finer ones.** Comparing whole days alone is the
        // tempting simplification and it is wrong: two writes inside one day look identical to a day
        // comparison, so a minute that closed between them is never rolled and raw rows accumulate
        // all day. These four cases are the whole rule.
        val t = base + 10 * HOUR + 30 * MIN + 5_000L
        assertEquals("same minute, nothing to close", 0L, RequestDb.crossed(t, t + 1_000L))
        assertEquals("minute changed", MIN, RequestDb.crossed(t, t + 55_000L))
        assertEquals("hour changed", HOUR, RequestDb.crossed(t, t + HOUR))
        assertEquals("day changed", DAY, RequestDb.crossed(t, t + DAY))
        // **A week is the day case with a wider range**, not a separate rule: the roll is driven by
        // MIN/MAX of the raw rows that are actually there.
        assertEquals("a week asleep", DAY, RequestDb.crossed(t, t + 7 * DAY))
    }

    @Test
    fun `a write inside the same day still rolls a minute that closed`() {
        // The failure the boundary comparison exists to prevent. With a day-only trigger the 10:00
        // minute would stay raw until midnight and `roll_minute` would answer nothing all day.
        write(base + 10 * HOUR + 5_000L)
        write(base + 10 * HOUR + 35_000L)
        write(base + 10 * HOUR + MIN + 10_000L)   // crosses the minute boundary

        val rolled = db.rawQuery("SELECT COUNT(*) FROM roll_minute", null)
            .use { if (it.moveToFirst()) it.getInt(0) else -1 }
        assertEquals("the 10:00 minute was rolled", 1, rolled)
        // Two responses were in that minute; the third is still raw in the open one.
        assertEquals("and it holds both of them", 2, total(base + 10 * HOUR, base + 10 * HOUR + MIN).requests)
        assertEquals("the open minute is read from raw", 1, total(base + 10 * HOUR + MIN, base + 11 * HOUR).requests)
    }

    @Test
    fun `a long sleep with several leftover minutes rolls all of them`() {
        // Not "the last one" but **every** raw row below the current minute: the range is taken from
        // MIN/MAX of the rows themselves, so a gap of any shape rolls completely.
        repeat(5) { i -> write(base + 10 * HOUR + i * MIN) }
        // Nothing compacted at all — the phone was off from the start.
        val later = base + 3 * DAY
        write(later)
        RequestDb.compact(db, later)

        assertEquals("every leftover minute", 5, total(base + 10 * HOUR, base + 11 * HOUR).requests)
        assertEquals("and the new one", 1, total(later, later + HOUR).requests)
        assertEquals("nothing raw", 0, rawRows())
    }

    /** How many rows are still in the raw table. */
    private fun rawRows(): Int =
        db.rawQuery("SELECT COUNT(*) FROM requests", null)
            .use { if (it.moveToFirst()) it.getInt(0) else -1 }

    // ---- retention ---------------------------------------------------------------------------

    @Test
    fun `retention removes every level, so nothing describes a period whose detail is gone`() {
        // **The bug the file version had, and the reason this design exists.** There, retention
        // bounded only the hour rows and left day/month/year standing - so a one-hour pick over
        // pruned data silently reported a whole day under a header reading "1 hour". Here every
        // table is bounded by the same cutoff, so a period is either entirely present or entirely
        // absent.
        write(base + 10 * HOUR)
        write(base + 10 * HOUR + MIN)
        RequestDb.compact(db, base + DAY)
        assertEquals("before retention", 2, total(base, base + DAY).requests)

        // Keep one day, with `now` a year later: everything above is ancient.
        RequestDb.compact(db, base + 365 * DAY, keepDays = 1)

        assertEquals("the old day is gone from every level", 0, total(base, base + DAY).requests)
        for (table in listOf("requests", "roll_minute", "roll_hour", "roll_day")) {
            val n = db.rawQuery("SELECT COUNT(*) FROM $table", null)
                .use { if (it.moveToFirst()) it.getInt(0) else -1 }
            assertEquals("$table is empty", 0, n)
        }
    }

    @Test
    fun `retention of zero keeps everything`() {
        // The default. "Everything" was unreasonable when a year cost 420 MB of raw text; at three
        // tables and a sum per bucket it is a reasonable default for the first time.
        write(base + 10 * HOUR)
        RequestDb.compact(db, base + 3650 * DAY, keepDays = 0)
        assertEquals("a decade later it is still there", 1, total(base, base + DAY).requests)
    }

    @Test
    fun `retention keeps what is newer than the cutoff`() {
        write(base + 10 * HOUR)
        write(base + 20 * DAY + 10 * HOUR)

        // Keep 30 days from a point 20 days after the old row: the first is out, the second in.
        RequestDb.compact(db, base + 20 * DAY + DAY, keepDays = 30)

        assertEquals("the old day", 0, total(base, base + DAY).requests)
        assertEquals("and the recent one", 1, total(base + 20 * DAY, base + 21 * DAY).requests)
    }

    // ---- the file itself ---------------------------------------------------------------------

    @Test
    fun `the database lives beside the system project's requests folder`() {
        val file = RequestDb.fileFor(account)
        assertTrue("it exists: $file", file.isFile)
        assertTrue(
            "and inside the system project, where the log has always lived: $file",
            file.path.contains("${Workspace.SYSTEM_PROJECT}/requests/"),
        )
        // **The reason this is asserted and not commented.** The system project is a real project
        // with a real repository, so a history written into it is a change waiting to be committed.
        // If this line of APP_IGNORES were ever dropped, every response would show up as
        // "1 changed" and the history would be the first thing anyone deletes.
        assertTrue(
            "and the app's own ignore list covers the folder",
            Workspace.APP_IGNORES.any { "${RequestLog.DIR}/" == it },
        )
    }

    @Test
    fun `a stall is never rolled, so its kind survives compaction`() {
        // **Why this matters beyond tidiness.** A rollup table has no `kind` column - it sums turns
        // and keepalives together, because "what did this hour cost" has one answer. A stall cannot
        // be summed with them (it was billed but reported no totals), and once rolled its kind is
        // gone and no query can recover it. So compaction leaves stalls raw for ever, and the screen
        // can keep drawing them apart however much history has been summarised.
        val stall = RequestLog.Stats(
            kind = RequestLog.Stats.Kind.STALL,
            ms = 1_000, provider = "p", model = "m",
            usage = null, finish = null,
        )
        write(base + 10 * HOUR, stall)
        write(base + 10 * HOUR + MIN)          // a turn, in the same minute
        RequestDb.compact(db, base + 11 * HOUR)

        val stillRaw = rawRows()
        assertEquals("the stall is still raw", 1, stillRaw)
        // Read unwrapped, because this is the one test about the split itself.
        val both = RequestDb.totals(db, base + 10 * HOUR, base + 11 * HOUR)
        assertEquals("the turn is in the total", 1, both.total.requests)
        assertEquals("and the stall is counted apart", 1, both.stalls.requests)
        // **A stall's cost is unknown, not zero**, so it must not touch the average: the turn
        // reported a cost and the stall reported nothing, and one reported cost over one request is
        // the honest answer.
        assertEquals("only the turn reported a cost", 1, both.total.costReported)
        assertEquals("so the stall did not enter the denominator", 1.0, both.total.perRequest!!, 1e-9)
    }
}
