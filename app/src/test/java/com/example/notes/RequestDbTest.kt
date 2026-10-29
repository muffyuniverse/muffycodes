package com.example.notes

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.time.LocalDate
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

    /**
     * A response written **through the real path**, on this test's own connection.
     *
     * `RequestDb.write` opens a connection of its own, and the boundary trigger reads the previous
     * instant from inside that connection - so a test that called it would roll on one handle and
     * then assert on another, which reads as "compaction did not happen". This drives the same two
     * steps in the same order on [db], so the trigger is still exercised end to end.
     */
    private fun write(at: Long, stats: RequestLog.Stats = response(at)) {
        val previous = db.rawQuery(
            "SELECT MAX(utc) FROM requests WHERE utc < ?",
            arrayOf(at.toString()),
        ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }
        RequestDb.insert(db, at, stats)
        val crossed = previous?.let { RequestDb.crossed(it, at) } ?: 0L
        if (crossed != 0L) RequestDb.compact(db, at, if (crossed == 86400000L) 0 else 0)
    }

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

        // **The end is a boundary, the start is not.** The 10:00 hour holds 10:00 and 10:30 - two -
        // and the response written exactly at 11:00 belongs to the NEXT hour, not this one. An
        // inclusive end would report three here and one there.
        assertEquals("the two inside", 2, total(base + 10 * HOUR, base + 11 * HOUR).requests)
        // **And the boundary instant is counted once, by the interval that begins at it.** That is
        // the whole of half-open: 11:00 ends one hour and starts the next, and only one of them
        // counts it - so the two intervals together hold exactly the rows between them, with no
        // instant counted twice and none lost.
        assertEquals("the boundary belongs to the next hour", 2, total(base + 11 * HOUR, base + 12 * HOUR).requests)
        // Starting a minute early picks that row up and nothing else: the figure tracks the bounds.
        assertEquals("9:59 added", 3, total(base + 9 * HOUR + 59 * MIN, base + 11 * HOUR).requests)
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
    fun `the oldest instant is the first thing written, while it is still raw`() {
        assertNull("an empty database has no beginning", RequestDb.oldest(db))
        // **Two writes in the same minute**, so the boundary trigger rolls nothing and both rows
        // stay raw. That is what this test is about; the next one is about the other case.
        write(base + 5 * HOUR)
        write(base + 5 * HOUR + 30_000L)
        assertEquals("the first thing written", base + 5 * HOUR, RequestDb.oldest(db))
    }

    @Test
    fun `once the detail is rolled away, the oldest instant is the bucket that survives`() {
        // **The distinction the screen's sentence needs.** After compaction the raw instants are
        // gone and the finest thing left is a day bucket, so `oldest` reports the *day* rather than
        // the response that happened to be first. Claiming 05:00 would promise a precision the data
        // no longer has - and the caption would then say "the log starts 05:00" beside figures
        // counted from whole days.
        //
        // The first version of the test above asserted 05:00 for this very case and was wrong: the
        // second write crossed an hour boundary, so compaction had already run.
        write(base + 5 * HOUR)
        write(base + 40 * HOUR)          // crosses an hour boundary -> rolls the first
        // **One row is still raw and must be**: compaction rolls strictly before the current
        // minute, so the response just written is always raw. The point of the assertion is that
        // the *first* row is gone from raw, which the oldest-instant check below proves.
        assertEquals("the newest response is always raw", 1, rawRows())
        assertEquals("so the day is the oldest thing left", base, RequestDb.oldest(db))
    }

    @Test
    fun `a history is usable after it is handed back`() {
        // **The bug this exists for: `read` wrapped its database in `use { }`**, which closes it on
        // return. Every `History` handed out was already dead, and the failure appeared later as
        // "attempt to re-open an already-closed object" from a query with no visible connection to
        // the cause. No test caught it because every test opened its own connection and queried
        // that; nothing exercised the handle `read` actually returns.
        write(base + 10 * HOUR)
        write(base + 10 * HOUR + 30 * MIN)

        RequestDb.read(account).use { history ->
            // **The day, not 10:00.** The second write crossed a minute boundary, so the 10:00
            // response was rolled and its raw row deleted - and the finest thing that survives for
            // that period is the day bucket. This is the same distinction the retention tests make:
            // after compaction the detail is gone, and reporting 10:00 would promise a precision
            // the data no longer has.
            assertEquals("the oldest instant spans every table", base, history.oldest)
            // **And a total can be computed on it**, which is what the screen does on every draw.
            assertEquals(
                "and the handle still answers",
                2,
                history.totals(base + 10 * HOUR, base + 11 * HOUR).total.requests,
            )
        }
    }

    @Test
    fun `a history can be closed twice, because two owners will both try`() {
        // The screen and the view model each used to close it; whichever ran second threw. Closing
        // is idempotent so neither has to know whether the other already did.
        val history = RequestDb.read(account)
        history.close()
        history.close()   // must not throw
        assertEquals("and a closed history reads as empty rather than crashing", 0, history.totals(0, 1).total.requests)
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
        // **Two, and 13:40 is the reason it is not three.** The interval is half-open, so its start
        // (10:20) is inside and its end (13:40) is not: the response written exactly at 13:40
        // belongs to the interval that *begins* there. My first expectation said three and named
        // 13:40 as inside - contradicting the half-open rule the rest of this file asserts.
        assertEquals(
            "10:20 and 12:00 are inside; 13:40 is the boundary, 10:10 and 13:50 are outside",
            2,
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
        // **Two: -5:00 and midnight.** The response at +5:00 is the end boundary and is excluded -
        // the same half-open rule as everywhere else, and the reason this test straddles midnight
        // is to prove the two halves are both read at minute granularity rather than one whole day
        // being taken for either side.
        assertEquals("the two inside", 2, stats.requests)
        // And each side of the boundary is answered by its own day.
        assertEquals("midnight to +5:00 has one", 1, total(base, base + 5 * MIN).requests)
        assertEquals("with the other one before", 1, total(base - 5 * MIN, base).requests)
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

    @Test
    fun `a local hour in a half-hour zone is still answered exactly`() {
        // **Why the minute tier exists.** The log keys its tables by UTC hours, but the picker
        // states a wall-clock time - and now a wall-clock *minute*. So most intervals begin and end
        // off the hour grid by construction, and the peel answers those ends from the minute table.
        //
        // A half-hour zone makes it unavoidable rather than merely common: in India (+5:30), Nepal
        // (+5:45), Adelaide (+9:30) or Newfoundland (-3:30) **no** local hour is a UTC hour, so even
        // an interval picked on the hour needs minutes at both ends.
        //
        // Pinned as arithmetic on the interval rather than through the database, because the point
        // is the shape of the boundary: a start that is not on a UTC hour edge.
        val india = java.time.ZoneId.of("Asia/Kolkata")
        val day = LocalDate.parse("2026-10-25")
        val at = day.atTime(19, 0).atZone(india).toInstant().toEpochMilli()
        val span = RequestStats.interval(at, at + 3_600_000L, india)

        assertEquals("the local hour", at, span.fromMs)
        assertEquals("is one hour of wall clock", 60L, span.minutes)
        // **And it does not begin on a UTC hour edge**, which is what forces the minute tier.
        assertNotEquals(
            "a half-hour zone puts the interval off the UTC hour grid",
            0L,
            Math.floorMod(span.fromMs, HOUR),
        )
    }

    @Test
    fun `an interval that starts off the hour grid is exact, not rounded`() {
        // The same hazard as `a local hour in a half-hour zone`, but driven through the database so
        // the peel itself is what is being checked: a start 30 minutes into a UTC hour must read
        // those 30 minutes at minute granularity and the rest at hour granularity, with no gap and
        // no overlap between the two.
        var at = base + 10 * HOUR + 30 * MIN
        repeat(6) {
            write(at)
            at += 10 * MIN
        }
        RequestDb.compact(db, base + 12 * HOUR)

        // 10:30 .. 11:30 - half of it off the grid at the start.
        assertEquals(
            "six responses, all counted once",
            6,
            total(base + 10 * HOUR + 30 * MIN, base + 11 * HOUR + 30 * MIN).requests,
        )
        // And the same interval one hour on holds none of them, so the first figure is not a
        // coincidence of a whole-hour widening.
        assertEquals(
            "an hour later holds nothing",
            0,
            total(base + 11 * HOUR + 30 * MIN, base + 12 * HOUR + 30 * MIN).requests,
        )
    }

    @Test
    fun `a fifteen-minute window is exact, because a minute can be picked`() {
        // **The case minute granularity creates.** While the finest summary was an hour, a quarter
        // of an hour was not a thing anybody could ask for. Now it is the ordinary case, so it has
        // to be exact rather than rounded to the hour it falls in.
        repeat(30) { m -> write(base + 10 * HOUR + m * MIN) }

        val quarter = total(base + 10 * HOUR + 5 * MIN, base + 10 * HOUR + 20 * MIN)
        assertEquals("5, 6, ..., 19 - fifteen of them", 15, quarter.requests)
        // And the neighbours are excluded, so the figure is not a coincidence of a widened range.
        assertEquals("the five before", 5, total(base + 10 * HOUR, base + 10 * HOUR + 5 * MIN).requests)
        assertEquals("the ten after", 10, total(base + 10 * HOUR + 20 * MIN, base + 10 * HOUR + 30 * MIN).requests)
    }

    @Test
    fun `a window with a whole hour inside it uses the hour table, not sixty minutes`() {
        // **The peel must still climb.** A window from 10:23 to 12:53 has a whole hour in the middle
        // (11:00-12:00), and taking it as 120 minute rows would be the same answer from twice the
        // rows. This asserts the answer, which is what a person sees; the table it came from is an
        // efficiency that must never change the figure.
        repeat(160) { m -> write(base + 10 * HOUR + 23 * MIN + m * MIN) }

        val window = total(base + 10 * HOUR + 23 * MIN, base + 12 * HOUR + 53 * MIN)
        assertEquals("37 before the hour, 60 in it, 53 after", 150, window.requests)
    }

    @Test
    fun `a window with no whole hour in it is still exact`() {
        // The case that looks like the previous one and is not: 10:23 to 11:53 has **no** whole
        // hour, because the only hour edge is at 11:00 and 11:00-11:53 is fifty-three minutes. So
        // the answer comes from minutes at both ends and there is nothing to climb to.
        repeat(100) { m -> write(base + 10 * HOUR + 23 * MIN + m * MIN) }

        assertEquals(
            "90 minutes, and every one of them",
            90,
            total(base + 10 * HOUR + 23 * MIN, base + 11 * HOUR + 53 * MIN).requests,
        )
    }

    @Test
    fun `a one-minute interval holds exactly its minute`() {
        // The finest thing that can be asked for, and the boundary rule at its sharpest: the minute
        // beginning at 10:23 holds 10:23:00 through 10:23:59.999, and nothing at 10:24:00.
        write(base + 10 * HOUR + 23 * MIN)
        write(base + 10 * HOUR + 23 * MIN + 59_999L)
        write(base + 10 * HOUR + 24 * MIN)

        assertEquals(
            "both responses inside it",
            2,
            total(base + 10 * HOUR + 23 * MIN, base + 10 * HOUR + 24 * MIN).requests,
        )
        assertEquals(
            "and the next minute holds only its own",
            1,
            total(base + 10 * HOUR + 24 * MIN, base + 10 * HOUR + 25 * MIN).requests,
        )
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

        // **Both, because both are inside the minute in progress.** The second row is 30 seconds
        // after the first and 30 seconds before the boundary, so the minute holding them has not
        // closed and neither can be rolled. Counting the *minute* rather than the row is what the
        // assertion is really about, and the first version of it said one.
        assertEquals("both rows are in the unclosed minute", 2, rawRows())
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
        // **One row is still raw, and must be.** The response just written is at `later` itself,
        // and compaction only rolls what is strictly *before* the current minute - so the newest
        // response is always raw. Claiming zero here would mean the minute in progress had been
        // rolled, which is the one thing compaction must never do.
        assertEquals("only the response just written", 1, rawRows())
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
        // **One row is still raw and must be: the response just written is in the minute that has
        // not closed yet.** "Nothing raw" would be the wrong claim - it would mean compaction had
        // rolled a minute that is still being written to.
        assertEquals("only the response just written", 1, rawRows())
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

        // **Keep 10 days, from a point 21 days after the old row.** The cutoff lands at
        // `now - 10 days`, which is 11 days after the old row and 10 days before the recent one, so
        // exactly one of them is inside. The first version of this test asked for 30 days and
        // expected the old row gone - but a 30-day cutoff from day 21 reaches back to day -9, which
        // is before both rows. The arithmetic was right and the expectation was not.
        RequestDb.compact(db, base + 20 * DAY + DAY, keepDays = 10)

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
