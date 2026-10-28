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
    fun `one pick is five columns, and each runs from its own start to the pick`() {
        // **This is the table.** Pick 12:45 on 25 October 2026 and there are exactly three
        // columns, all ending at 12:45 and each starting at the boundary of its own span:
        //
        //     hour   12:00 - 12:45
        //     day    00:00 - 12:45
        //     month  the 1st - 12:45
        //
        // Cumulative windows, not whole spans. A whole day column at 12:45 would count an
        // afternoon that has not happened.
        //
        // The shape is asserted as a list rather than as a count, because five labels say which
        // five spans are shown and a count of five does not.
        val at = LocalDate.parse("2026-10-25").atTime(12, 45)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val buckets = RequestStats.buckets(listOf(), at)
        assertEquals(
            "what each column is - five nested spans of one instant",
            listOf("12:00–12:45", "25 Oct", "Oct 2026", "2026", "total"),
            buckets.map { it.label },
        )
        assertEquals(
            "and where it starts: the hour names both ends, the rest only their start",
            listOf("", "from 00:00", "from the 1st", "from 1 Jan", "from the first row"),
            buckets.map { it.sub },
        )
    }

    @Test
    fun `every column stops at the pick, and starts at its own boundary`() {
        // The bounds themselves, not just the labels - a label can agree with a bug.
        val local = LocalDate.parse("2026-10-25").atTime(12, 45)
        val zone = ZoneId.systemDefault()
        val at = local.atZone(zone).toInstant().toEpochMilli()
        // **Five spans, so index them rather than destructuring three.** The first three are the
        // ones pinned here; year and total start at the 1st of January and at the oldest row.
        val spans = RequestStats.SPANS.map { RequestStats.container(it, at, zone) }
        val hour = spans[0]
        val day = spans[1]
        val month = spans[2]

        fun ms(l: java.time.LocalDateTime) = l.atZone(zone).toInstant().toEpochMilli()

        assertEquals("the hour starts at 12:00", ms(local.withMinute(0).withSecond(0).withNano(0)), hour.fromMs)
        assertEquals("the day starts at midnight", ms(local.toLocalDate().atStartOfDay()), day.fromMs)
        assertEquals("the month starts on the 1st", ms(local.withDayOfMonth(1).toLocalDate().atStartOfDay()), month.fromMs)

        // **And all three end on the same instant**, which is what makes them comparable: the hour
        // is what happened inside the day, and the day inside the month, up to the question.
        assertEquals("the hour stops at the pick", at, hour.toMs)
        assertEquals("so does the day", at, day.toMs)
        assertEquals("so does the month", at, month.toMs)
    }

    @Test
    fun `picking the last hour of 2025 renders all of 2025 in the year column`() {
        // **The operator's own example**, and the reason there is no year picker: to look at 2025,
        // pick 23:00 on 31 December 2025. One pick, every column - 23:00-23:59 of that hour, the
        // whole of the 31st, the whole of December, the whole of 2025, and everything up to 23:00
        // on that day - the total ends at the pick too, it is not all of time.
        //
        // A year-only picker would have to answer "the 31st of which month?" for the day column.
        // Picking an *instant* answers it for all five at once, which is why the picker stays one
        // control and the columns are chosen by checkboxes.
        val zone = ZoneId.systemDefault()
        val local = LocalDate.parse("2025-12-31").atTime(23, 0)
        val at = local.atZone(zone).toInstant().toEpochMilli()
        val buckets = RequestStats.buckets(listOf(), at, zone)

        assertEquals(
            "five columns of one instant",
            listOf("23:00–23:00", "31 Dec", "Dec 2025", "2025", "total"),
            buckets.map { it.label },
        )

        // Boundaries, which the labels alone cannot prove.
        fun ms(l: java.time.LocalDateTime) = l.atZone(zone).toInstant().toEpochMilli()
        val spans = RequestStats.SPANS.map { RequestStats.container(it, at, zone) }
        assertEquals("the year begins on 1 January 2025", ms(LocalDate.parse("2025-01-01").atStartOfDay()), spans[3].fromMs)
        assertEquals("and ends at the pick, so it is the whole year", at, spans[3].toMs)
        assertEquals("the month is all of December", ms(LocalDate.parse("2025-12-01").atStartOfDay()), spans[2].fromMs)
        assertEquals("the day is all of the 31st", ms(LocalDate.parse("2025-12-31").atStartOfDay()), spans[1].fromMs)
        assertEquals("the total reaches back past any row", 0L, spans[4].fromMs)
        assertEquals("and every column stops at the same instant",
            listOf(at, at, at, at, at), spans.map { it.toMs })
    }

    @Test
    fun `the month column is the 1st to the pick's own day, at clock time`() {
        // **Said in one sentence: "the 1st to n", where n is the day of the month picked.** All
        // three spans are clock time - not elapsed milliseconds, not a rolling window - so the
        // month of a pick at 12:45 on the 25th runs from 1 October 00:00 to 25 October 12:45.
        //
        // Pinned at three different days because the interesting cases are different: an early
        // day, a day in the middle, and the last day of the month (where "to n" is the whole month
        // up to the pick, which is the case most likely to be got wrong by adding one).
        val zone = ZoneId.systemDefault()
        for (n in listOf(1, 25, 31)) {
            val local = LocalDate.parse("2026-10-%02d".format(n)).atTime(12, 45)
            val at = local.atZone(zone).toInstant().toEpochMilli()
            val month = RequestStats.container(RequestStats.Span.MONTH, at, zone)

            assertEquals(
                "day $n: the column starts on the 1st",
                local.withDayOfMonth(1).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli(),
                month.fromMs,
            )
            assertEquals("day $n: and stops at the pick, not at month end", at, month.toMs)
        }
    }

    @Test
    fun `the day column is clock midnight to the pick, not the last twelve hours`() {
        // A day column that reached back twelve hours from 12:45 would begin at 00:45 and quietly
        // include the previous evening's requests in "the 25th". It begins at midnight.
        val zone = ZoneId.systemDefault()
        val local = LocalDate.parse("2026-10-25").atTime(12, 45)
        val at = local.atZone(zone).toInstant().toEpochMilli()
        val day = RequestStats.container(RequestStats.Span.DAY, at, zone)

        assertEquals(
            "midnight",
            local.toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli(),
            day.fromMs,
        )
        assertEquals("and the pick", at, day.toMs)
    }

    @Test
    fun `a request after the pick is not in any column`() {
        // **The whole reason to cut at the pick.** At 12:45 the rest of the hour, the rest of the
        // day and the rest of the month had not happened, so counting them would report spending
        // that has not occurred.
        val rows = listOf(
            RequestStats.parse(row(day = "2026-10-25", at = "09:00:00.000"))!!,
            RequestStats.parse(row(day = "2026-10-25", at = "12:30:00.000"))!!,
            RequestStats.parse(row(day = "2026-10-25", at = "12:50:00.000"))!!,
            RequestStats.parse(row(day = "2026-10-25", at = "18:00:00.000"))!!,
        )
        val at = LocalDate.parse("2026-10-25").atTime(12, 45)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val buckets = RequestStats.buckets(rows, at)

        assertEquals("only the 12:30 request is in the 12:00-12:45 hour", 1, buckets[0].total.requests)
        assertEquals("the day has 09:00 and 12:30", 2, buckets[1].total.requests)
        assertEquals("the month has the same two", 2, buckets[2].total.requests)
    }

    @Test
    fun `the total column is everything up to the pick, not everything in the log`() {
        // **The one span wide enough to look like it has no end.** It has one: the same pick as the
        // other four. A total that ran to "now" would answer a different question from the column
        // beside it, and the five are comparable only because they share an end.
        //
        // Pinned with a row after the pick because that is the only way a wrong implementation
        // shows: a total filtered by "before now" would include it and still look plausible.
        val rows = listOf(
            RequestStats.parse(row(day = "2025-12-30", at = "10:00:00.000"))!!,
            RequestStats.parse(row(day = "2025-12-31", at = "22:00:00.000"))!!,
            RequestStats.parse(row(day = "2025-12-31", at = "23:30:00.000"))!!, // after the pick
            RequestStats.parse(row(day = "2026-06-01", at = "10:00:00.000"))!!, // long after
        )
        val at = LocalDate.parse("2025-12-31").atTime(23, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val buckets = RequestStats.buckets(rows, at)

        assertEquals("the two rows before the pick", 2, buckets[4].total.requests)
        assertEquals("and the year column agrees", 2, buckets[3].total.requests)
        // **And it is not merely "fewer than everything"**: every column counts the same two rows
        // except the hour, which counts none of them.
        assertEquals("the hour at 23:00 has nothing yet", 0, buckets[0].total.requests)
        assertEquals("the month has the 30th and 22:00 on the 31st", 2, buckets[2].total.requests)
    }

    @Test
    fun `picking midnight leaves the hour and the day empty, honestly`() {
        // **A consequence of one rule, and it should be pinned** so a later "fix" does not special-
        // case it. At 00:00 nothing has been spent today and nothing in this hour; the month still
        // holds its earlier days. The table says zero rather than reaching into the future.
        val rows = listOf(
            RequestStats.parse(row(day = "2026-10-24", at = "22:00:00.000"))!!,
            RequestStats.parse(row(day = "2026-10-25", at = "06:00:00.000"))!!,
        )
        val at = LocalDate.parse("2026-10-25").atTime(0, 0)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val buckets = RequestStats.buckets(rows, at)

        assertEquals("nothing this hour yet", 0, buckets[0].total.requests)
        assertEquals("nothing today yet", 0, buckets[1].total.requests)
        assertEquals("but yesterday's request is in the month", 1, buckets[2].total.requests)
    }

    @Test
    fun `the columns overlap, because an hour is part of its day`() {
        // **Overlap is the point, not a bug.** A request at 12:30 on the 25th belongs to all three
        // columns: it is in that hour, that day, and that month. Three disjoint windows could not
        // answer "what did this hour cost, and what is it part of".
        val rows = listOf(
            RequestStats.parse(row(day = "2026-10-25", at = "12:30:00.000", prompt = "1000", cost = "0.001"))!!,
        )
        val at = LocalDate.parse("2026-10-25").atTime(12, 45)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val buckets = RequestStats.buckets(rows, at)

        assertEquals("counted in the hour", 1, buckets[0].total.requests)
        assertEquals("and in the day", 1, buckets[1].total.requests)
        assertEquals("and in the month", 1, buckets[2].total.requests)
        // And the money is counted once per column, which is what makes them comparable.
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
        val at = LocalDate.parse("2026-10-25").atTime(12, 45)
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
        // The distinction shows up inside the day as well: a row at 09:00 and a row at 12:30 are
        // both "today" and both "October", but only one of them is in the 12:00-12:45 column. A
        // table that placed rows by the opening day could not tell them apart.
        val rows = listOf(
            RequestStats.parse(row(day = "2026-10-25", at = "09:00:00.000"))!!,
            RequestStats.parse(row(day = "2026-10-25", at = "12:30:00.000"))!!,
        )
        val at = LocalDate.parse("2026-10-25").atTime(12, 45)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val buckets = RequestStats.buckets(rows, at)

        assertEquals("only the 12:30 row is in the 12:00-12:45 hour", 1, buckets[0].total.requests)
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

        // **The hour column is index 0**, and it is read directly rather than searched for: a row
        // outside the hour column would have been dropped, and asking the hour column is what
        // catches that.
        //
        // The column is a **range** ending at the pick (`13:00–13:14`), so the assertion is against
        // the label's start, not the whole label - the zone decides where the hour begins, and the
        // pick decides where it ends in either zone.
        val berlinHour = RequestStats.buckets(rows, instant, berlinZone)[0].label.substringBefore('–')
        val laHour = RequestStats.buckets(rows, instant, laZone)[0].label.substringBefore('–')

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
