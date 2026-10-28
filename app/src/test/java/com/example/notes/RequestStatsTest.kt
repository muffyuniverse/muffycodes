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
    ) = run {
        // **The instant is derived from the day and the clock time**, in the given zone, which is
        // exactly what the writer does. A helper that hard-coded one instant would make every
        // placement test pass while testing nothing.
        val utc = LocalDate.parse(day)
            .atTime(java.time.LocalTime.parse(at))
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toString()
        "$utc\t$kind\t1840\tdeepinfra/fp8/flex\tdeepseek\t$prompt\t$cached\t" +
            "$completion\t$reasoning\t$cost\tlength"
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
    fun `one pick is three columns, and they are the hour, the day and the month`() {
        // **This is the table.** Pick 12:00 on 25 October 2026 and there are exactly three
        // columns: the hour that instant is in, the day it is in, and the month it is in.
        //
        // The shape is asserted as a list rather than as a count, because three labels say which
        // three spans are shown and a count of three does not.
        val at = LocalDate.parse("2026-10-25").atTime(12, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val buckets = RequestStats.buckets(listOf(), at)
        assertEquals(
            "narrowest first: the hour, the day, the month",
            listOf("12:00", "25 Oct", "Oct 2026"),
            buckets.map { it.label },
        )
    }

    @Test
    fun `the three columns overlap, because an hour is part of its day`() {
        // **Overlap is the point, not a bug.** A request at 12:30 on the 25th belongs to all three
        // columns: it is in that hour, that day, and that month. Three disjoint windows could not
        // answer "what did this hour cost, and what is it part of".
        val rows = listOf(
            RequestStats.parse(row(day = "2026-10-25", at = "12:30:00.000", prompt = "1000", cost = "0.001"))!!,
        )
        val at = LocalDate.parse("2026-10-25").atTime(12, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val buckets = RequestStats.buckets(rows, at)

        assertEquals("counted in the hour", 1, buckets[0].total.requests)
        assertEquals("and in the day", 1, buckets[1].total.requests)
        assertEquals("and in the month", 1, buckets[2].total.requests)
        // And the money is counted once per column, which is what makes the three comparable.
        assertEquals(0.001, buckets[0].total.cost, 1e-12)
        assertEquals(0.001, buckets[2].total.cost, 1e-12)
    }

    @Test
    fun `a request outside a column is not in it`() {
        // The hour column holds one hour, not the day. A request at 09:00 must not appear in the
        // 12:00 column, or every column would show the same totals.
        val rows = listOf(
            RequestStats.parse(row(day = "2026-10-25", at = "09:00:00.000"))!!,
            RequestStats.parse(row(day = "2026-10-20", at = "12:30:00.000"))!!,
        )
        val at = LocalDate.parse("2026-10-25").atTime(12, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val buckets = RequestStats.buckets(rows, at)

        assertEquals("nothing in the 12:00 hour", 0, buckets[0].total.requests)
        assertEquals("one in the day", 1, buckets[1].total.requests)
        assertEquals("two in the month", 2, buckets[2].total.requests)
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
        val at = LocalDate.parse("2026-10-25").atTime(12, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val hour = RequestStats.buckets(rows, at)[0]

        assertEquals("one accounted request", 1, hour.total.requests)
        assertEquals("one that stopped", 1, hour.stalls.requests)
        assertEquals("and its cost is not added to the total", 0.001, hour.total.cost, 1e-12)
    }

    @Test
    fun `a row is placed by its own instant, not by the day the screen opened`() {
        // **The bug this pins was live for one edit.** The clock time was being combined with the
        // day the screen opened, which put every line from every file into today.
        //
        // With three columns the distinction shows up inside the day as well: a row at 09:00 and a
        // row at 12:30 are both "today" and both "October", but only one of them is in the 12:00
        // column. A table that placed rows by the opening day could not tell them apart.
        val rows = listOf(
            RequestStats.parse(row(day = "2026-10-25", at = "09:00:00.000"))!!,
            RequestStats.parse(row(day = "2026-10-25", at = "12:30:00.000"))!!,
        )
        val at = LocalDate.parse("2026-10-25").atTime(12, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val buckets = RequestStats.buckets(rows, at)

        assertEquals("only the 12:30 row is in the 12:00 hour", 1, buckets[0].total.requests)
        assertEquals("both are in the day", 2, buckets[1].total.requests)
        assertEquals("and both in the month", 2, buckets[2].total.requests)
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

        // **The hour column is index 0**, and the whole three-column table is compared rather than
        // a label searched for: a row outside the hour column would have been dropped, and asking
        // the hour column directly is what catches that.
        val berlinHour = RequestStats.buckets(rows, instant, berlinZone)[0].label
        val laHour = RequestStats.buckets(rows, instant, laZone)[0].label

        assertEquals("Berlin's own hour", expectedHourIn(berlinZone), berlinHour)
        assertEquals("and the hour that instant is in Los Angeles", expectedHourIn(laZone), laHour)
        // **And they must differ**, or the test proves nothing: a table that ignored the zone
        // entirely would put both rows in one hour and pass the two assertions above.
        assertNotEquals("the two zones disagree, which is the point", berlinHour, laHour)

        // Both count the request once - the conversion moves it, it does not duplicate it.
        // Each zone counts the row once per column, and never drops it: the hour column must hold
        // it in both zones or the row was placed outside the span it is actually in.
        assertEquals("Berlin's hour column has it", 1, RequestStats.buckets(rows, instant, berlinZone)[0].total.requests)
        assertEquals("Los Angeles' hour column has it", 1, RequestStats.buckets(rows, instant, laZone)[0].total.requests)
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

        val buckets = RequestStats.buckets(
            listOf(parsed),
            day.atTime(9, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
        )
        val today = buckets[0]
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
        // **Coverage, not length.** The old assertion counted days from the oldest row to now,
        // which ignored the anchor and returned a negative number for a container in the past.
        assertTrue(
            "the log covers the day it was written in",
            RequestStats.covers(rows, RequestStats.container(RequestStats.Span.DAY, now)),
        )
        assertFalse(
            "and not a month it has never seen",
            RequestStats.covers(
                rows,
                RequestStats.container(RequestStats.Span.DAY, now - 200L * 24 * 60 * 60 * 1000),
            ),
        )
    }
}
