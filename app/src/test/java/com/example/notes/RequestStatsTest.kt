package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        // **The instant is derived from the day and the clock time**, in the device's zone, which
        // is exactly what the writer does. A helper that hard-coded one instant would make every
        // placement test pass while testing nothing.
        val utc = LocalDate.parse(day)
            .atTime(java.time.LocalTime.parse(at))
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        "$utc\t$at\t$day\t$kind\t1840\tdeepinfra/fp8/flex\tdeepseek\t$prompt\t$cached\t" +
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
    fun `the two kinds are summed apart, and their sum is the total`() {
        val rows = listOf(
            RequestStats.parse(LocalDate.parse("2026-10-28"), row(kind = "turn", prompt = "1000", cost = "0.001"))!!,
            RequestStats.parse(LocalDate.parse("2026-10-28"), row(kind = "ping", prompt = "3691", cost = "0.0000241"))!!,
            RequestStats.parse(LocalDate.parse("2026-10-28"), row(kind = "ping", prompt = "3691", cost = "0.0000241"))!!,
        )
        val at = LocalDate.parse("2026-10-28").atTime(9, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val buckets = RequestStats.buckets(rows, RequestStats.Axis.DAY, at)

        val today = buckets.last()
        assertEquals("one turn", 1, today.turns.requests)
        assertEquals("two pings", 2, today.pings.requests)
        assertEquals("and the total is both", 3, today.total.requests)
        assertEquals("the turn's input", 1000, today.turns.promptTokens)
        assertEquals("the pings' input", 7382, today.pings.promptTokens)
        // **The number that makes the split necessary.** Two pings outweigh one turn seven to one
        // on input, which is why a blended table would be a table about the keepalive.
        assertTrue("pings dominate input", today.pings.promptTokens > today.turns.promptTokens * 5)
    }

    @Test
    fun `the buckets come from the clock, so an idle day is still a column`() {
        // A table that only showed the hours something happened would hide the shape of the day,
        // which is the thing a person is looking for. Twenty-four columns, always.
        val at = LocalDate.parse("2026-10-28").atTime(9, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val buckets = RequestStats.buckets(emptyList(), RequestStats.Axis.HOUR, at)
        assertEquals("one column per hour", 24, buckets.size)
        assertEquals("the last one is the hour we are in", "09:00", buckets.last().label)
        assertEquals("the first is a day back", "10:00", buckets.first().label)
        assertTrue("and every one is empty rather than absent", buckets.all { it.total.requests == 0 })
    }

    @Test
    fun `a row is placed by its own day and its own clock time`() {
        // **The bug this pins was live for one edit.** The clock time was being combined with the
        // day the screen opened, which put every line from every file into today and turned a
        // thirty-day table into one column.
        val rows = listOf(
            // **The day goes to the builder, not to the parser.** `parse`'s day argument only
            // labels the row; the instant that places it comes from the line's own first column,
            // which is computed from the day given here.
            RequestStats.parse(LocalDate.parse("2026-10-26"), row(day = "2026-10-26", at = "14:00:00.000"))!!,
            RequestStats.parse(LocalDate.parse("2026-10-28"), row(day = "2026-10-28", at = "09:14:22.301"))!!,
        )
        val at = LocalDate.parse("2026-10-28").atTime(9, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val buckets = RequestStats.buckets(rows, RequestStats.Axis.DAY, at)

        assertEquals("the 26th has its own request", 1, buckets.first { it.label == "26 Oct" }.total.requests)
        assertEquals("and the 28th has its own", 1, buckets.first { it.label == "28 Oct" }.total.requests)
    }

    @Test
    fun `the same rows bucket differently in two zones, because the instant is the truth`() {
        // **This is the test the single file exists for.** A log written in one place and read in
        // another must place every row by its instant, not by the clock time it was written with -
        // "09:14" in Berlin is "00:14" in Los Angeles, and a table that bucketed the stored clock
        // time would count a Berlin morning as a Los Angeles evening.
        val instant = LocalDate.parse("2026-10-28").atTime(9, 14)
            .atZone(java.time.ZoneId.of("Europe/Berlin"))
            .toInstant()
            .toEpochMilli()
        val rows = listOf(
            RequestStats.Row(
                atMs = instant,
                day = LocalDate.parse("2026-10-28"),
                kind = RequestLog.Stats.Kind.TURN,
                prompt = 1_000, cached = 0, completion = 10, reasoning = 0, cost = 0.001,
            ),
        )
        val now = instant + 60_000L

        val berlin = RequestStats.buckets(
            rows, RequestStats.Axis.DAY, now, java.time.ZoneId.of("Europe/Berlin"),
        )
        val la = RequestStats.buckets(
            rows, RequestStats.Axis.DAY, now, java.time.ZoneId.of("America/Los_Angeles"),
        )

        // **A day, not an hour: one column per date is unambiguous**, while an hour label sits at
        // the edge of a 24-hour window and would test the window rather than the timezone.
        assertEquals("Berlin counted it on its own 28th", 1, berlin.first { it.label == "28 Oct" }.total.requests)
        // The same instant is still the 27th in Los Angeles - nine hours earlier - and that is the
        // whole point: one request, one instant, and the local day depends on where you stand.
        assertEquals("Los Angeles counted it on its 27th", 1, la.first { it.label == "27 Oct" }.total.requests)
        // And it is one request in both, counted once rather than duplicated by the conversion.
        assertEquals("one request either way", 1, berlin.sumOf { it.total.requests })
        assertEquals("one request either way", 1, la.sumOf { it.total.requests })
    }

    @Test
    fun `a line that does not parse is skipped, not guessed at`() {
        // The log is appended to while it is being read, so the last line may be half written. A
        // table that refused to draw because of one bad line would be a table that never drew.
        val day = LocalDate.parse("2026-10-28")
        assertNull("a short line", RequestStats.parse(day, "09:14:22.301\tturn"))
        assertNull("an unknown kind", RequestStats.parse(day, row(kind = "shrug")))
        assertNotNull("and a good one", RequestStats.parse(day, row()))
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
        assertEquals("the day came from the file's name", LocalDate.parse("2026-10-28"), rows.single().day)
        assertEquals("and the numbers survived the round trip", 1000L, rows.single().prompt)
        assertEquals("including the cache", 500L, rows.single().cached)
        assertEquals("and the thinking", 2L, rows.single().reasoning)
        assertEquals("one day kept", 1, RequestStats.days(rows, now))
    }
}
