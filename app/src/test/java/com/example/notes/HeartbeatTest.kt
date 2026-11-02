package com.example.notes

import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * The heartbeat's arithmetic, and the four ways it can be quietly wrong.
 *
 * **Every test here is about a window boundary or an absence**, because those are the two things a
 * graph cannot show you are wrong: a bar that covers the wrong hour looks exactly like a bar that
 * covers the right one, and a bar drawn for an hour with no data looks exactly like a bar for an
 * hour that spent nothing.
 *
 * **The database is driven directly rather than through `RequestDb.write`**, which opens a
 * connection of its own - so a test that used it would roll on one handle and assert on another,
 * which reads as "compaction did not happen". [SQLiteDatabase] here is the same handle the reader
 * gets, and [RequestDb.insert] and [RequestDb.compact] are the two steps `write` takes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HeartbeatTest {

    private val app = RuntimeEnvironment.getApplication()
    private lateinit var account: File
    private lateinit var db: SQLiteDatabase

    private val t0 = 1_767_000_000_000L // an arbitrary instant; only differences matter here
    private val hour = 3_600_000L
    private val minute = 60_000L

    @Before
    fun open() {
        account = File(app.cacheDir, "heartbeat-${System.nanoTime()}")
        account.mkdirs()
        db = RequestDb.open(account)
    }

    @After
    fun close() {
        db.close()
        account.deleteRecursively()
    }

    /** One request, priced at a round number so a total can be checked by arithmetic. */
    private fun stats(
        cost: Double = 0.01,
        prompt: Int = 1_000,
        cached: Int = 500,
        completion: Int = 250,
        reasoning: Int = 0,
    ) = RequestLog.Stats(
        kind = RequestLog.Stats.Kind.TURN,
        ms = 100,
        provider = "test",
        model = "test/model",
        // **Cost and the cached/reasoning counts live inside the usage JSON**, which is where the
        // provider puts them - `RequestDb.insert` reads them from there rather than taking separate
        // fields. A helper that invented its own shape would be testing the helper.
        usage = JSONObject(
            """{"prompt_tokens":$prompt,"completion_tokens":$completion,"cost":$cost,
               "prompt_tokens_details":{"cached_tokens":$cached},
               "completion_tokens_details":{"reasoning_tokens":$reasoning}}""",
        ),
        finish = "stop",
    )

    /** The four measures, read the way the card reads them. */
    private fun measures(now: Long): List<Heartbeat.Measure> =
        RequestDb.read(account).use { Heartbeat.of(it, now) }

    private fun measure(now: Long, id: Heartbeat.Id): Heartbeat.Measure =
        measures(now).first { it.id == id }

    @Test
    fun `there are forty-eight points, and every one is a real sixty minutes`() {
        // **One request an hour for two days**, which makes each window's expected total exactly
        // one request - so a window that covered the wrong span would show two or none.
        for (h in 0 until Heartbeat.HOURS) RequestDb.insert(db, t0 + h * hour, stats())

        val now = t0 + (Heartbeat.HOURS - 1) * hour + 30 * minute
        val spend = measure(now, Heartbeat.Id.SPEND)

        assertEquals("forty-eight windows", Heartbeat.HOURS, spend.points.size)
        // The requests are an hour apart and each window is an hour wide, so exactly one lands in
        // each. A window twice as wide would catch two; a clock-hour graph would lose one at the end.
        assertEquals("one request per window", Heartbeat.HOURS, spend.points.count { it != null })
    }

    /**
     * **The newest point is the number above it.**
     *
     * The operator's correction, made into a test: *"they are not clock hours but intervals computed
     * from minutes, so that last entry shows real 60 min"*. With clock hours the last bar would hold
     * however much of the current hour had elapsed - at half past, thirty minutes - and the number
     * would disagree with the bar it sits over.
     */
    @Test
    fun `the newest point covers the same sixty minutes as the number`() {
        val now = t0 + 10 * hour + 30 * minute // half past the hour, deliberately
        RequestDb.insert(db, now - 59 * minute, stats(cost = 0.25))
        RequestDb.insert(db, now - 61 * minute, stats(cost = 9.99))

        val spend = measure(now, Heartbeat.Id.SPEND)
        assertEquals("the number is the last hour only", 0.25, spend.recent!!, 1e-9)
        assertEquals("and the bar over it agrees", spend.recent, spend.points.last())
    }

    /**
     * **A window with no requests is null, not zero.**
     *
     * A zero says "nothing was spent"; a null says "nothing was recorded". The card draws them
     * differently - a gap versus a bar at the floor - and conflating them would draw a flat line
     * through a phone that was switched off, which reads as "it was free" rather than "it was off".
     */
    @Test
    fun `an hour with nothing recorded is absent rather than zero`() {
        val now = t0 + 10 * hour
        RequestDb.insert(db, now - 30 * minute, stats(cost = 0.5))

        val spend = measure(now, Heartbeat.Id.SPEND)
        assertNull("the quiet hours are absent", spend.points.first())
        assertEquals("the busy one is present", 0.5, spend.points.last()!!, 1e-9)
    }

    /**
     * **Totals are compared per hour; rates are compared as themselves.**
     *
     * The bug this pins: dividing a rate by forty-eight turns a 90% cache share into 1.9%, so every
     * ordinary hour would report a badge of about +4700%. Totals need the division to become an
     * average hour; rates must not be divided at all.
     */
    @Test
    fun `a rate is compared against the rate, not against a rate per hour`() {
        for (h in 0 until Heartbeat.HOURS) {
            RequestDb.insert(db, t0 + h * hour + minute, stats(cost = 0.01, prompt = 1_000, cached = 500))
        }
        val now = t0 + (Heartbeat.HOURS - 1) * hour + 30 * minute

        val cache = measure(now, Heartbeat.Id.CACHE)
        assertEquals("the share is half", 0.5, cache.baseline!!, 1e-9)
        assertEquals("so an ordinary hour is no change", 0.0, cache.badge!!, 1e-9)

        // And a total does get divided, or the badge would read -97.9% for a normal hour.
        val spend = measure(now, Heartbeat.Id.SPEND)
        assertEquals("the average hour", 0.01, spend.baseline!!, 1e-9)
        assertEquals("a normal hour is no change", 0.0, spend.badge!!, 1e-9)
    }

    /** The operator's definition, verbatim: `completion / (prompt + completion)`. */
    @Test
    fun `output share is completion over prompt plus completion`() {
        val now = t0 + hour
        RequestDb.insert(db, now - minute, stats(prompt = 3_000, cached = 1_000, completion = 1_000, reasoning = 500))

        val share = measure(now, Heartbeat.Id.SHARE)
        // 1000 / (3000 + 1000) = 0.25. Reasoning is not in the denominator, and neither is cached -
        // cached is part of prompt and would be counted twice.
        assertEquals(0.25, share.recent!!, 1e-9)
    }

    /**
     * **An empty period has a baseline of zero, and no badge.**
     *
     * The two are different facts and the distinction is the whole point: `0.0` says the period was
     * measured and nothing happened, which is true and worth drawing. A *badge* needs a denominator,
     * and dividing by that zero has no answer - so the badge is absent while the baseline is not.
     * Collapsing them would either hide a real zero or invent a percentage.
     */
    @Test
    fun `an empty period has a zero baseline and no badge`() {
        val measures = measures(t0 + hour)
        for (m in measures) {
            // **A total over an empty period is a measured zero; a rate has no denominator at all.**
            // The two differ because `0/0` has no answer while `sum of nothing` is zero - and a card
            // that treated them alike would either hide a real zero or invent a percentage.
            if (m.id.additive) {
                assertEquals("${m.id} was measured and found empty", 0.0, m.baseline!!, 1e-9)
            } else {
                assertNull("${m.id} has no denominator, so no baseline", m.baseline)
            }
            assertNull("${m.id} has no recent value", m.recent)
            assertNull("${m.id} has no badge", m.badge)
            assertEquals("and says so", "—", Heartbeat.badge(m.badge))
        }
    }

    /**
     * **A minute is counted once, whether it is raw or rolled.**
     *
     * The bug this pins is the one that reads as correct: `minutes()` reads the rolled minutes *and*
     * the raw tail, and if the raw query started at the window's start rather than where rolling
     * stopped, every still-raw minute would be counted twice. Nothing about the graph would look
     * wrong - it would simply be twice as tall, which is indistinguishable from having done twice
     * the work.
     */
    @Test
    fun `a minute is not counted twice when it is both raw and rolled`() {
        val now = t0 + hour
        RequestDb.insert(db, now - 30 * minute, stats(cost = 1.0))
        RequestDb.compact(db, now) // rolls that minute and deletes its raw row

        assertEquals("counted once, not twice", 1.0, measure(now, Heartbeat.Id.SPEND).recent!!, 1e-9)
    }

    /**
     * **A minute that has not been rolled yet is still counted.**
     *
     * The other half of the same boundary: compaction leaves the current minute alone, because its
     * row would be contradicted by the next request. A graph that read only the rollup tables would
     * therefore lose the most recent minute - which is the one a person is looking at.
     */
    @Test
    fun `the minute that has not been rolled yet is still counted`() {
        val now = t0 + hour
        RequestDb.insert(db, now - 10 * minute, stats(cost = 0.25))
        RequestDb.insert(db, now - minute, stats(cost = 0.75))
        RequestDb.compact(db, now)

        assertEquals("both minutes", 1.0, measure(now, Heartbeat.Id.SPEND).recent!!, 1e-9)
    }

    @Test
    fun `a quiet hour still gets a badge, because it is a real comparison`() {
        // The operator's choice: show `—` only when the hour is *entirely* empty. One request
        // against a busy baseline is a genuine -99.5%, not noise to be hidden.
        for (h in 0 until Heartbeat.HOURS - 1) RequestDb.insert(db, t0 + h * hour, stats(cost = 0.05))
        val now = t0 + (Heartbeat.HOURS - 1) * hour + 30 * minute
        RequestDb.insert(db, now - minute, stats(cost = 0.0002))

        val spend = measure(now, Heartbeat.Id.SPEND)
        assertTrue("there is a badge", spend.badge != null)
        assertTrue("and it is a fall", spend.badge!! < 0)
        assertTrue("but not hidden", spend.recent != null)
    }

    // ---- formatting -------------------------------------------------------------------------

    @Test
    fun `a large count keeps three significant figures at every scale`() {
        // The operator's own list: 999, 9.99K, 99.9K, 999K, 9.9M, 99.9B.
        assertEquals("999", Heartbeat.compact(999.0))
        assertEquals("9.99K", Heartbeat.compact(9_994.0))
        assertEquals("99.9K", Heartbeat.compact(99_900.0))
        assertEquals("999K", Heartbeat.compact(999_400.0))
        assertEquals("9.99M", Heartbeat.compact(9_994_000.0))
        assertEquals("99.9B", Heartbeat.compact(99_900_000_000.0))
    }

    @Test
    fun `a thousand does not read as one hundred percent of nothing`() {
        // Below a thousand is the integer, because 1.00K for exactly a thousand reads as a rounder
        // number than it is.
        assertEquals("1000", Heartbeat.compact(1_000.0))
        assertEquals("0", Heartbeat.compact(0.0))
    }

    @Test
    fun `a badge is capped at both ends of the scale`() {
        assertEquals("+50.00%", Heartbeat.badge(0.5))
        assertEquals("-50.00%", Heartbeat.badge(-0.5))
        assertEquals("+0.00%", Heartbeat.badge(0.0))
        // Nothing can improve by more than everything, and the operator asked for `>99.99%` at the
        // top because beyond that the figure is not something anyone acts on.
        assertEquals(">+99.99%", Heartbeat.badge(19.0))
        assertEquals("-100%", Heartbeat.badge(-1.0))
        assertEquals("-100%", Heartbeat.badge(-5.0))
        assertEquals("—", Heartbeat.badge(null))
    }

    @Test
    fun `each measure is drawn in its own units`() {
        assertEquals("1.23$", Heartbeat.format(Heartbeat.Id.SPEND, 1.234))
        assertEquals("97.10%", Heartbeat.format(Heartbeat.Id.CACHE, 0.9710))
        assertEquals("9.99K", Heartbeat.format(Heartbeat.Id.OUTPUT, 9_994.0))
        assertEquals("16.67%", Heartbeat.format(Heartbeat.Id.SHARE, 1.0 / 6))
        // An absent value is a dash in every unit, never a zero in that unit's clothes.
        for (id in Heartbeat.Id.entries) assertEquals("—", Heartbeat.format(id, null))
    }
}
