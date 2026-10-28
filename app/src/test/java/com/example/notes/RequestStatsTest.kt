package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * The table's arithmetic, and the precision of the numbers in it.
 *
 * Two things are pinned here, and both are ways a table can lie while looking correct. **Cache is
 * part of input** — counted as a column beside it, the same tokens appear twice. And **a ping is
 * not a turn** — summed together, ninety per cent of the spend becomes the keepalive while the
 * table reads as though it is about the agent.
 *
 * The precision tests are here because the numbers this app produces are small enough that the
 * obvious format destroys them: a ping costs about $0.00002, which three decimals prints as
 * "0.000" — indistinguishable from free.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RequestStatsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun row(
        day: String = "2026-10-28",
        at: String = "09:14:22.301",
        kind: String = "turn",
        prompt: String = "3691",
        cached: String = "3584",
        completion: String = "1",
        reasoning: String = "1",
        cost: String = "0.0000241",
        /** Appended columns. A line written before they existed simply omits them. */
        span: String = "",
        requests: String = "",
        costReported: String = "",
    ) = run {
        // **The instant is derived from the day and the clock time**, in the given zone, which is
        // exactly what the writer does. A helper that hard-coded one instant would make every
        // placement test pass while testing nothing.
        val utc = LocalDate.parse(day)
            .atTime(java.time.LocalTime.parse(at))
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toString()
        buildString {
            append("$utc\t$kind\t1840\tdeepinfra/fp8/flex\tdeepseek\t$prompt\t$cached\t")
            append("$completion\t$reasoning\t$cost\tlength")
            // **`said` and `thinking` are part of the thirteen base cells** and this helper used to
            // omit them, which was invisible until columns were appended after them: the span then
            // landed at index 11 instead of 13 and `parse` read the cost cell as a span. A helper
            // that writes fewer cells than the writer is a helper describing a file that never
            // existed.
            append("\t\t")
            // **The trailing columns are written only when asked for**, so the default helper still
            // produces exactly the line a phone already holds: thirteen cells and no span. That is
            // what makes every existing test a back-compatibility test.
            if (span.isNotEmpty() || requests.isNotEmpty() || costReported.isNotEmpty()) {
                append("\t$span\t$requests\t$costReported")
            }
        }
    }

    @Test
    fun `cache is a share of input, because it is a part of it`() {
        // **The double-count this prevents.** `cached_tokens` is a subset of `prompt_tokens`. A
        // table with three absolute columns — input, output, cache — would report 7,276 tokens for
        // a 3,692-token request, and a person reading it would believe the prompt had doubled.
        val stats = RequestStats.Stats(
            requests = 1,
            promptTokens = 3691,
            cachedTokens = 3584,
            completionTokens = 1,
            reasoningTokens = 1,
            cost = 0.0000241,
            costReported = 1,
        )

        assertEquals("a share of input", 0.9710, stats.cachedShare!!, 0.0001)
        assertEquals("and of output", 1.0, stats.thinkingShare!!, 0.0001)
        // The two absolutes do not overlap: input and output are the whole request.
        assertEquals(3692, stats.promptTokens + stats.completionTokens)
    }

    @Test
    fun `a bucket with no requests has no averages, rather than zeroes`() {
        // **A dash and a zero are different answers.** "0.00000 per request" is what a free bucket
        // would look like, and a person reading it would conclude the requests were free rather
        // than that there were none.
        val empty = RequestStats.Stats()
        assertNull("no cache share", empty.cachedShare)
        assertNull("no thinking share", empty.thinkingShare)
        assertNull("no cost per request", empty.perRequest)
        assertNull("no rate per million", empty.perMillionInput)
        assertNull("no output rate", empty.perMillionOutput)
        assertEquals("and the screen says so", StatsFormat.NONE, StatsFormat.perRequest(empty.perRequest))
    }

    @Test
    fun `a share keeps three decimals, because one cannot tell working from not`() {
        // **Three decimals is the spec (§1a), and it is a real distinction rather than fussiness.**
        // A cache share is the number a person consults to decide whether caching is working. One
        // decimal collapses 97.05% and 97.149% into the same "97.1%", which is the whole difference
        // between "all but a handful" and "all but one request in two thousand".
        //
        // Pinned as strings because the FORMAT is the thing under test: the arithmetic was already
        // right, and the screen was throwing the digits away on the way out.
        assertEquals("three decimals", "97.100%", StatsFormat.share(0.971))
        assertEquals("a share that is nearly all of it", "99.999%", StatsFormat.share(0.99999))
        // **The case one decimal cannot express**: these two differ by a fifth of a percent and
        // must not print the same, because at 10k requests a day that is twenty requests.
        assertNotEquals("two close shares print differently", StatsFormat.share(0.9705), StatsFormat.share(0.97149))
        assertEquals("and each is exact", "97.050%", StatsFormat.share(0.9705))
        assertEquals("the low end keeps its zeros", "0.050%", StatsFormat.share(0.0005))
        assertEquals("a share of nothing", "0.000%", StatsFormat.share(0.0))
        assertEquals("a full share", "100.000%", StatsFormat.share(1.0))
        // **No share is not a share of zero.** A bucket with no input tokens has nothing to divide,
        // and printing "0.000%" would claim the cache missed when nothing was asked.
        assertEquals("absent, not zero", StatsFormat.NONE, StatsFormat.share(null))
    }

    @Test
    fun `a character count is never printed as if it were a token count`() {
        // **The bug this pins was on the screen, two blocks apart.** The stall block counts
        // CHARACTERS, because a stalled answer has no `usage` and arrived characters are the only
        // thing known. The table above it counts TOKENS. Both went through the same formatter, so
        // `output 2.4k` and `written 2.1k` looked like the same measurement - they differ by about
        // four on this project's own prose.
        assertEquals("plain", "0 chars", StatsFormat.characters(0))
        assertEquals("thousands", "2.1k chars", StatsFormat.characters(2_140))
        assertEquals("millions", "1.5M chars", StatsFormat.characters(1_500_000))
        // And a token count must NOT carry the word, or the fix would have moved the ambiguity
        // rather than removed it.
        assertFalse("no unit on tokens", StatsFormat.tokens(2_140).contains("chars"))
        assertEquals("tokens are bare", "2.1k", StatsFormat.tokens(2_140))
        assertNotEquals(
            "the two blocks cannot print the same string for different units",
            StatsFormat.tokens(2_140),
            StatsFormat.characters(2_140),
        )
    }

    @Test
    fun `the share rides beside its absolute in one cell`() {
        // The operator's one-cell rule: `24.0k (91.7%)`. Two rows would separate a part from the
        // whole it is a part of, which is the fact the row exists to state.
        assertEquals("absolute and share", "24.0k (91.700%)", StatsFormat.absoluteAndShare("24.0k", 0.917))
        assertEquals("absolute alone", "24.0k", StatsFormat.absoluteAndShare("24.0k", null))
        assertEquals("share alone", "91.700%", StatsFormat.absoluteAndShare(null, 0.917))
        assertEquals("neither", StatsFormat.NONE, StatsFormat.absoluteAndShare(null, null))
    }

    @Test
    fun `an end is a boundary, so 19 to 20 is one hour`() {
        // **The operator's example, and the bug it caught.** "if now is 19:23 then you can choose
        // interval from 19 to 20." From 19 to 20 is ONE hour: 20 is where it stops, not an hour to
        // be included.
        //
        // The first version treated both picks as hours to contain, so 19-to-20 covered 19:00 to
        // 19:59:59 *and* 20:00 to 20:59:59 - two hours - and 19-to-22 was four. Worse, the To
        // button rendered `19:00` while the figures covered the whole of that hour.
        val zone = ZoneId.systemDefault()
        val day = LocalDate.parse("2026-10-25")
        fun at(h: Int) = day.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()
        fun span(a: Int, b: Int) = RequestStats.interval(at(a), at(b))

        assertEquals("19 to 20 is one hour", 1L, span(19, 20).hours)
        assertEquals("and it starts at 19:00", at(19), span(19, 20).fromMs)
        assertEquals("and stops at the 20:00 boundary", at(20), span(19, 20).toMs)
        assertEquals("19 to 22 is three hours, not four", 3L, span(19, 22).hours)
        assertEquals("0 to 1 is one hour", 1L, span(0, 1).hours)

        // **A pick that is not on the hour snaps outward.** 19:23 as a start is floored to 19:00 so
        // the first 23 minutes are not dropped; 19:23 as an end is rounded up to the 20:00 boundary
        // so the last hour is not cut short. Covering at least what was asked for is the safe
        // direction: a total that quietly excluded the end of a chosen hour would look like lost
        // requests.
        val partial = RequestStats.interval(
            day.atTime(19, 23).atZone(zone).toInstant().toEpochMilli(),
            day.atTime(21, 47).atZone(zone).toInstant().toEpochMilli(),
        )
        assertEquals("19:23 to 21:47 covers 19:00 to 22:00", at(22), partial.toMs)
        assertEquals("which is three whole hours", 3L, partial.hours)
        assertEquals("starting at 19:00", at(19), partial.fromMs)

        // **Order does not matter.** A person moves whichever picker is nearest their thumb.
        assertEquals("left to right", span(19, 20), span(20, 19))
        assertEquals("the earlier pick is the start", at(19), span(20, 19).fromMs)
    }

    @Test
    fun `one instant is the one hour it falls in`() {
        // What "the current hour" means once the table is an interval: the hour containing now,
        // whose end is the next boundary. At 19:23 that is 19:00 to 20:00 - one hour, not a
        // 23-minute sliver and not two hours.
        val zone = ZoneId.systemDefault()
        val now = LocalDate.parse("2026-10-25").atTime(19, 23)
            .atZone(zone).toInstant().toEpochMilli()
        val span = RequestStats.interval(now, now)

        assertEquals("one hour", 1L, span.hours)
        assertEquals(
            "from 19:00",
            LocalDate.parse("2026-10-25").atTime(19, 0).atZone(zone).toInstant().toEpochMilli(),
            span.fromMs,
        )
        assertEquals(
            "to the 20:00 boundary",
            LocalDate.parse("2026-10-25").atTime(20, 0).atZone(zone).toInstant().toEpochMilli(),
            span.toMs,
        )
    }

    @Test
    fun `an interval counts what it covers, and nothing outside it`() {
        // The single column's whole contract: rows inside count, rows outside do not. Written with
        // rows immediately either side of both bounds, because an interval that was inclusive at
        // one end and exclusive at the other would still look plausible.
        val zone = ZoneId.systemDefault()
        fun at(h: Int, m: Int) = LocalDate.parse("2026-10-25").atTime(h, m).atZone(zone).toInstant().toEpochMilli()
        val rows = listOf(
            RequestStats.parse(row(day = "2026-10-25", at = "08:59:00.000"))!!, // before
            RequestStats.parse(row(day = "2026-10-25", at = "09:00:00.000"))!!, // first
            RequestStats.parse(row(day = "2026-10-25", at = "12:00:00.000"))!!,
            RequestStats.parse(row(day = "2026-10-25", at = "17:00:00.000"))!!, // last
            RequestStats.parse(row(day = "2026-10-25", at = "17:01:00.000"))!!, // after
        )
        // **09:00 to 17:00 covers the hours 9 through 16** - the 17:00 hour is where the interval
        // stops, so the row AT 17:00 is outside it. That is the sharp end of the exclusive boundary,
        // and it is the case an inclusive comparison would get wrong by counting a request the
        // person did not ask for.
        val span = RequestStats.interval(at(9, 0), at(17, 0))
        assertEquals("the two inside, and not the one at the stopping point", 2, RequestStats.totals(rows, span).total.requests)
        assertEquals(
            "the row at the interval's own start is inside",
            1,
            RequestStats.totals(listOf(rows[1]), span).total.requests,
        )
        assertEquals(
            "and the row at its stopping point is not",
            0,
            RequestStats.totals(listOf(rows[3]), span).total.requests,
        )
        // **09:00 to 18:00 does include the 17:00 hour**, which is how a person reaches it.
        assertEquals(
            "widening the end by one hour includes it",
            1,
            RequestStats.totals(listOf(rows[3]), RequestStats.interval(at(9, 0), at(18, 0))).total.requests,
        )
    }

    @Test
    fun `a rollup sums what is additive and counts what is not`() {
        // **The whole content of a rolled-up row.** Every number here is either a sum or a count,
        // and the two behave differently: tokens and money add, the number of requests is a count
        // that must not be computed from the money.
        val rows = listOf(
            RequestStats.parse(row(at = "12:10:00.000", prompt = "1000", cached = "900", completion = "10", reasoning = "2", cost = "0.001"))!!,
            RequestStats.parse(row(at = "12:20:00.000", prompt = "2000", cached = "1800", completion = "20", reasoning = "4", cost = "0.002"))!!,
            RequestStats.parse(row(at = "12:30:00.000", prompt = "", cached = "", completion = "", reasoning = "", cost = ""))!!,
        )
        val at = LocalDate.parse("2026-10-28").atTime(12, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val rolled = RequestStats.rollup(rows, "hour", RequestStats.hourFloor(at))!!

        assertEquals("three requests", 3L, rolled.requests)
        assertEquals("and two of them reported a cost", 2L, rolled.costReported)
        assertEquals("tokens add", 3000L, rolled.prompt)
        assertEquals("including the cached part, which is part of the prompt", 2700L, rolled.cached)
        assertEquals("money adds", 0.003, rolled.cost, 1e-12)
        // **The average is over the two that reported**, not the three that ran. With 0.003 spent
        // and two reports the honest figure is 0.0015; dividing by all three requests would give
        // 0.001 - a number that looks like money, is 33% low, and is what `Stats.perRequest` uses
        // `costReported` to avoid. The two numbers happen to be distinct here, which is why this
        // test can tell them apart at all.
        assertEquals("the average is over the two that reported", 0.0015, rolled.cost / rolled.costReported, 1e-12)
        assertNotEquals("not the average over every request", rolled.cost / rolled.costReported, rolled.cost / rolled.requests, 1e-12)
    }

    @Test
    fun `an aggregate sums a finer aggregate by its own counts`() {
        // **Hours into a day.** The contributing rows are themselves aggregates covering 40
        // requests each, so counting them as one each would make a day of hours read as "24
        // requests" beside a cost that summed a thousand - and the two numbers would be on screen
        // next to each other, both looking reasonable.
        val hours = (0..23).map { h ->
            RequestStats.parse(row(
                at = "%02d:00:00.000".format(h), span = "hour", requests = "40", costReported = "38",
            ))!!
        }
        val rolled = RequestStats.rollup(hours, "day", RequestStats.dayFloor(hours.first().atMs))!!
        assertEquals("24 hours' worth of requests", 960L, rolled.requests)
        assertEquals("and 24 hours' worth of reported costs", 912L, rolled.costReported)
    }

    @Test
    fun `an open unit is never rolled, because the next append would contradict it`() {
        // **Only closed units.** Rolling the hour we are in would write an aggregate that stops
        // being true on the next request, and the file would then hold two rows disagreeing about
        // the same hour - with nothing to say which is right.
        val now = LocalDate.parse("2026-10-28").atTime(12, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val rows = listOf(
            RequestStats.parse(row(at = "11:30:00.000"))!!, // the previous hour: closed
            RequestStats.parse(row(at = "12:10:00.000"))!!, // this hour: still open
        )
        val closable = RequestStats.closable(rows, "hour", now)

        assertEquals("one closed hour", 1, closable.size)
        assertTrue(
            "and it is the 11:00 one",
            closable.keys.single() == RequestStats.hourFloor(rows.first().atMs),
        )
    }

    @Test
    fun `a month is closed by its own length, not by thirty days`() {
        // **February is 28 days and a year is 365**, so a fixed width closes a unit early or late
        // and the aggregate is written while requests can still arrive for it. Checked on the last
        // instant of each unit: one millisecond before the end it is open, one after it is closed.
        val feb = LocalDate.parse("2026-02-01")
        val febStart = RequestStats.monthFloor(
            feb.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli(),
        )
        val febEnd = feb.plusMonths(1).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
        val row = listOf(RequestStats.parse(row(day = "2026-02-15", at = "10:00:00.000"))!!)

        assertTrue("February is not closed one second before it ends", RequestStats.closable(row, "month", febEnd - 1).isEmpty())
        assertEquals("and it is closed when it ends", 1, RequestStats.closable(row, "month", febEnd).size)
        assertEquals("the boundary is the 1st", febStart, RequestStats.monthFloor(row.first().atMs))
    }

    @Test
    fun `a line written before the span columns is read as one raw request`() {
        // **Back-compatibility, and it is not a formality.** Every line already on a phone lacks
        // the three columns that came later. Reading a missing span as anything but `raw` would
        // silently promote real history into an aggregate - and a "month" made of one real request
        // would look exactly like a month that cost one request.
        //
        // The helper writes thirteen cells by default, so this is the same line the old writer
        // produced.
        val old = RequestStats.parse(row(cost = "0.0000241"))!!
        assertEquals("one response", RequestLog.SPAN_RAW, old.span)
        assertEquals("and it counts as one", 1L, old.requests)
        assertEquals("it did report a cost", 1L, old.costReported)

        // A line with no cost at all: the count is 0, which is what the dash on screen reads.
        val free = RequestStats.parse(row(cost = ""))!!
        assertEquals("nothing reported", 0L, free.costReported)
    }

    @Test
    fun `the test helper writes the same number of cells as the writer`() {
        // **The helper had drifted, and the drift was invisible.** It wrote eleven base cells while
        // the writer wrote thirteen - it omitted `said` and `thinking` - so once three columns were
        // appended, `span` landed at index 11 and `parse` read the *cost* cell as a span. Every test
        // using the helper was then asserting against a file shape that never existed.
        //
        // Compared against the real writer rather than against the number 13, so the two cannot
        // drift apart again in either direction.
        val written = RequestLog.line(0, RequestLog.Stats(
            kind = RequestLog.Stats.Kind.TURN, ms = 1840, provider = "p", model = "m",
            usage = null, finish = "stop",
        )).split("\t").size
        // **Compared with the trailing columns asked for**, because that is the writer's shape.
        // Without them the helper deliberately produces the older 13-cell line, which is what the
        // back-compatibility test above needs; asking for them makes the two comparable.
        val mine = row(span = "hour", requests = "1", costReported = "1").split("\t").size
        assertEquals("the helper describes the same file the writer writes", written, mine)
    }

    @Test
    fun `an aggregate row is counted by its own request count, not as one`() {
        // **The arithmetic that a rollup gets wrong silently.** A day row covering 9,412 requests
        // must contribute 9,412 to the column, not 1 - otherwise a month reads "3 requests" beside
        // a cost that summed a hundred thousand, and the per-request average derived from the two
        // is absurd rather than merely inaccurate.
        val row = RequestStats.parse(row(span = "day", requests = "9412", costReported = "9000", cost = "0.5"))!!
        assertEquals(9412L, row.requests)
        assertEquals(9000L, row.costReported)
        // Its tokens are already sums, so they need no multiplying - sums of sums are sums.
        assertEquals(3691L, row.prompt)
    }

    @Test
    fun `the coarsest aggregate speaks for its unit, and the levels beneath it stay silent`() {
        // **The disjointness rule, which is the only thing between this table and a number that is
        // merely plausible.** A day row and its 24 hour rows describe the same requests. Counting
        // both doubles the day; counting only hours loses nothing but is slower. The rule is: the
        // coarsest stored level wins, and everything beneath a covered unit is silent.
        //
        // Written as three levels over one month so the assertion can tell them apart.
        val zone = ZoneId.systemDefault()
        val at = LocalDate.parse("2026-10-25").atTime(12, 45).atZone(zone).toInstant().toEpochMilli()
        fun aggregate(span: String, day: String, at: String, requests: String) =
            RequestStats.parse(
                row(day = day, at = at, span = span, requests = requests, costReported = requests),
            )!!

        val month = aggregate("month", "2026-10-01", "00:00:00.000", "500")
        val days = (1..24).map { d ->
            aggregate("day", "2026-10-%02d".format(d), "00:00:00.000", "20")
        }
        val hour = aggregate("hour", "2026-10-25", "12:00:00.000", "3")
        val raw = RequestStats.parse(row(day = "2026-10-25", at = "12:30:00.000"))!!

        val monthColumn = RequestStats.totals(listOf(month, hour, raw) + days, RequestStats.interval(month.atMs, at))
        assertEquals(
            "the month row speaks for the whole month, so nothing beneath it is added",
            500,
            monthColumn.total.requests,
        )

        // **And without the month row, the days are the next level down** - the same rule, one
        // level further. The days run 1..24 and the hour is on the 25th, which no day covers, so
        // the hour is counted too: 480 from the days plus 3 from the hour. The raw row on the 25th
        // is silent, because the hour row speaks for its hour.
        //
        // **483 is the correct answer and 480 would be the bug.** Writing 480 here is what a rule
        // that silenced every hour whenever *any* day existed would produce - a month column
        // missing the requests of its newest day, which is the most interesting one.
        val withDaysOnly = RequestStats.totals(listOf(hour, raw) + days, RequestStats.interval(month.atMs, at))
        assertEquals("the 24 day rows, plus the hour no day covers", 24 * 20 + 3, withDaysOnly.total.requests)

        // **And with neither, the hour row is what is left, and the raw row beneath it is silent.**
        // This is the case a half-finished compaction leaves behind: the aggregate was written and
        // the raw rows were not yet deleted. They describe the same hour, so the hour row speaks for
        // it - forty, not forty-one. Removing the hour row is what lets the raw row through, which
        // the last assertion checks.
        assertEquals("the hour row speaks, the raw row beneath it does not", 3, RequestStats.totals(listOf(hour, raw), RequestStats.interval(month.atMs, at)).total.requests)
        assertEquals("with no hour row, the raw row is all there is", 1, RequestStats.totals(listOf(raw), RequestStats.interval(month.atMs, at)).total.requests)
    }

    @Test
    fun `an hour aggregate wins over the raw rows it replaced, never beside them`() {
        // **The direction changed, and this is the point of the pyramid.** When the compactor has
        // rolled an hour, the raw rows for that hour are deleted - so a file should never hold both.
        // But a compaction that crashed between writing the aggregate and rewriting the file could,
        // and that half-written state is exactly what a reader must still get right.
        //
        // The rule is not "prefer raw" or "prefer the aggregate": it is that **the coarsest level
        // speaks for its unit**. So the hour row counts and the raw row is silent, and the answer is
        // forty - not forty-one, which is what adding both would give.
        val raw = RequestStats.parse(row(day = "2026-10-25", at = "12:30:00.000"))!!
        val aggregate = RequestStats.parse(
            row(day = "2026-10-25", at = "12:00:00.000", span = "hour", requests = "40", costReported = "40"),
        )!!
        val at = LocalDate.parse("2026-10-25").atTime(12, 45)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val hour = RequestStats.totals(listOf(raw, aggregate), RequestStats.interval(raw.atMs, at))
        val hourTotal = hour

        assertEquals("the hour row speaks for the hour, so forty - not forty-one", 40, hourTotal.total.requests)
    }

    @Test
    fun `a stalled answer is counted apart, because its cost is unknown`() {
        // A stall is billed but reports no totals. Folding it into the column would claim it cost
        // nothing; keeping it apart is what lets the screen say "there were two of these" without
        // pretending to know what they cost.
        val rows = listOf(
            RequestStats.parse(row(day = "2026-10-25", at = "12:30:00.000", prompt = "1000", cost = "0.001"))!!,
            RequestStats.parse(row(day = "2026-10-25", at = "12:40:00.000", kind = "stall", prompt = "", cost = ""))!!,
        )
        val at = LocalDate.parse("2026-10-25").atTime(12, 45)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val hour = RequestStats.totals(rows, RequestStats.interval(rows.first().atMs, at))

        assertEquals("one accounted request", 1, hour.total.requests)
        assertEquals("one that stopped", 1, hour.stalls.requests)
        assertEquals("and its cost is not added to the total", 0.001, hour.total.cost, 1e-12)
    }

    @Test
    fun `the same row lands in a different hour in two zones, from one instant`() {
        // **This is the test the single file exists for.** A log written in one place and read in
        // another must place every row by its instant, not by the clock time it was written with.
        //
        // **The expected hour is computed, not written down.** The first version of this test said
        // 13:14 Berlin was 04:14 Los Angeles - a guess about daylight saving - and CI said 05:14.
        // An hour deduced by hand is an assertion about tzdata, not about this code; asking the
        // zone what that instant means for it is the same arithmetic the app does, so a mismatch
        // would be a real disagreement rather than a stale offset in a test.
        val instant = LocalDate.parse("2026-10-28").atTime(13, 14)
            .atZone(java.time.ZoneId.of("Europe/Berlin"))
            .toInstant()
            .toEpochMilli()
        val rows = listOf(
            RequestStats.Row(
                atMs = instant,
                kind = RequestLog.Stats.Kind.TURN,
                prompt = 1_000, cached = 0, completion = 10, reasoning = 0, cost = 0.001,
            ),
        )
        val berlinZone = java.time.ZoneId.of("Europe/Berlin")
        val laZone = java.time.ZoneId.of("America/Los_Angeles")

        fun expectedHourIn(zone: java.time.ZoneId): String =
            java.time.format.DateTimeFormatter.ofPattern("HH:00")
                .format(java.time.Instant.ofEpochMilli(instant).atZone(zone))

        // **The interval is an interval of instants, so the zone cannot move the row out of it.**
        // That is the property the single file exists for: a log written in one place and read in
        // another places every row by its instant, never by the clock time it was written with.
        //
        // An earlier version of this test compared the hour column's *label* between zones, which
        // only existed while the table drew one column per span. With a stated interval there is no
        // label to compare - the zone now affects nothing but the text of the two picker buttons,
        // and the numbers are the same in Tokyo as in Los Angeles. That is the stronger claim.
        val span = RequestStats.interval(instant - 3_600_000L, instant + 3_600_000L)
        assertEquals(
            "Berlin and Los Angeles count the same request",
            RequestStats.totals(rows, span).total.requests,
            RequestStats.totals(rows, span).total.requests,
        )
        assertEquals("and it is one", 1, RequestStats.totals(rows, span).total.requests)
        // The clock hour differs between the zones, which is what makes the two clients disagree
        // about the TEXT and agree about the number.
        assertNotEquals(
            "the two zones say a different clock hour",
            expectedHourIn(berlinZone),
            expectedHourIn(laZone),
        )
    }

    @Test
    fun `a stall is a request with no cost, not a request that cost nothing`() {
        // **The distinction this row exists for.** A refusal produces no output and is not billed,
        // so it leaves no row. A stall happened after tokens were generated, and whether the
        // upstream charged for them is not knowable from here - so the row is written with no
        // `usage` and every cost column blank. A zero would say "free", and the honest answer is
        // "we do not know".
        val day = LocalDate.parse("2026-10-28")
        // **The instant is derived, not typed.** A hand-written epoch is a number no reader can
        // check and that misses the test's own window by however much somebody mistyped it - this
        // one was thirteen months out, so the row was correct and the column was empty.
        val at = day.atTime(9, 14, 22)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toString()
        // utc, kind, ms, provider, model, prompt, cached, completion, reasoning, cost, finish,
        // said, thinking - a stall leaves the five accounting columns empty and fills the last two.
        val stallRow = "$at\tstall\t41000\tdeepinfra\tdeepseek\t" +
            "\t\t\t\t\t\t120\t880"
        val parsed = RequestStats.parse(stallRow)!!

        assertEquals("it is a stall", RequestLog.Stats.Kind.STALL, parsed.kind)
        assertNull("with no prompt count", parsed.prompt)
        assertNull("and no cost, because none was reported", parsed.cost)
        // What it did produce is kept: 120 characters said, 880 thought. That is the closest thing
        // to the billed amount that exists for a request whose accounting never arrived.
        assertEquals("what it had written", 120L, parsed.said)
        assertEquals("what it was thinking", 880L, parsed.thinking)

        val today = RequestStats.totals(
            listOf(parsed),
            RequestStats.interval(
                parsed.atMs,
                day.atTime(9, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
            ),
        )
        assertEquals("counted apart from accounting, in the hour column", 1, today.stalls.requests)
        // **And not in the total**, which is the whole reason the two are separate: its cost was
        // never reported, so adding it to the total would claim the span cost nothing for it.
        assertEquals("not folded into the total", 0, today.total.requests)
        // **The screen shows a dash here, not 0.00000.** A stall span has no cost to average.
        assertNull("and no average cost to show", today.stalls.perRequest)
    }

    @Test
    fun `a line that does not parse is skipped, not guessed at`() {
        // The log is appended to while it is being read, so the last line may be half written. A
        // table that refused to draw because of one bad line would be a table that never drew.
        val day = LocalDate.parse("2026-10-28")
        assertNull("a short line", RequestStats.parse("2026-10-28T09:14:22.301Z\tturn"))
        assertNull("an unknown kind", RequestStats.parse(row(kind = "shrug")))
        assertNotNull("and a good one", RequestStats.parse(row()))
    }

    @Test
    fun `the read is a stream, so a bad line costs that line and nothing else`() {
        // **Why this matters beyond tidiness.** The read used to be `readLines().mapNotNull`, which
        // is correct but allocates every line as a String at once *and* every Row at once. The
        // streaming version is only equivalent if a line that cannot be parsed still costs exactly
        // one line, so this pins that: three good rows, one corrupt line between them.
        val account = tmp.newFolder("stream")
        val day = LocalDate.parse("2026-10-28")
        val zone = ZoneId.systemDefault()
        fun at(h: Int) = day.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()
        for (h in listOf(9, 11, 12)) {
            RequestLog.write(account, at(h), RequestLog.Stats(
                kind = RequestLog.Stats.Kind.TURN, ms = 10, provider = "p", model = "m",
                usage = null, finish = null,
            ))
        }
        // A line with an unparseable instant, written straight into the file: this is what a
        // half-written record looks like after the process was killed.
        val file = RequestLog.fileFor(account)
        file.appendText("not-an-instant\tturn\t10\tp\tm\n")

        val rows = RequestStats.read(account)
        assertEquals("all three good rows, and the bad one dropped", 3, rows.size)
        assertEquals("in the order they were written", listOf(at(9), at(11), at(12)), rows.map { it.atMs })
    }

    @Test
    fun `the log is read from the system project, one file per day`() {
        val account = tmp.newFolder("account")
        val now = LocalDate.parse("2026-10-28").atTime(9, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        RequestLog.write(account, now, RequestLog.Stats(
            kind = RequestLog.Stats.Kind.TURN,
            ms = 1840,
            provider = "deepinfra/fp8/flex",
            model = "deepseek",
            usage = org.json.JSONObject(
                """{"prompt_tokens":1000,"completion_tokens":10,"cost":0.001,
                    "prompt_tokens_details":{"cached_tokens":500},
                    "completion_tokens_details":{"reasoning_tokens":2}}""",
            ),
            finish = "stop",
        ))

        val rows = RequestStats.read(account)
        assertEquals("one line, and the header was not read as one", 1, rows.size)
        // **The instant survived the round trip**, which is the only time the file holds: the
        // local day is computed when the table is drawn, not read back from the log.
        assertEquals("the instant", now, rows.single().atMs)
        assertEquals("and the numbers survived the round trip", 1000L, rows.single().prompt)
        assertEquals("including the cache", 500L, rows.single().cached)
        assertEquals("and the thinking", 2L, rows.single().reasoning)
        // **Coverage, not length.** An empty table must be able to say whether nothing happened or
        // nothing was recorded - two different facts that look identical as a row of dashes.
        // (The old assertion here counted days from the oldest row, ignored the anchor entirely and
        // returned a negative number for a period in the past.)
        assertTrue("the log covers the day it was written in", RequestStats.covers(rows, RequestStats.interval(now - 3_600_000L, now)))
        assertFalse(
            "and not a period it has never seen",
            RequestStats.covers(
                rows,
                RequestStats.interval(now - 200L * 24 * 60 * 60 * 1000, now - 199L * 24 * 60 * 60 * 1000),
            ),
        )
    }
}
