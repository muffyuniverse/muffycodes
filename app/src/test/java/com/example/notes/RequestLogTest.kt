package com.example.notes

import org.json.JSONObject
import org.junit.Assert.assertEquals
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
 * The request log: one line per response, and the columns a person will read.
 *
 * The point of these tests is the reading end. A log is worth keeping only if the numbers in it can
 * be trusted when someone comes back to it a week later asking what the keepalive cost — so what is
 * pinned here is the format, the "not reported" case, and the fact that the file is per day.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RequestLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun usage(json: String) = JSONObject(json)

    private val full = usage(
        """
        {
          "prompt_tokens": 3691,
          "completion_tokens": 1,
          "total_tokens": 3692,
          "cost": 0.0000241,
          "prompt_tokens_details": { "cached_tokens": 3584 },
          "completion_tokens_details": { "reasoning_tokens": 1 }
        }
        """.trimIndent(),
    )

    private fun stats(usage: JSONObject?, kind: RequestLog.Stats.Kind = RequestLog.Stats.Kind.PING) =
        RequestLog.Stats(
            kind = kind,
            ms = 1840,
            provider = "deepinfra/fp8/flex",
            model = "deepseek/deepseek-v4.1-flash",
            usage = usage,
            finish = "length",
        )

    @Test
    fun `a line carries every number, in the order the header names them`() {
        val line = RequestLog.line(0, stats(full))
        val cells = line.split("\t")

        // **Sixteen: the instant, the response, twelve numbers, and the three that describe what
        // the line covers.** There is one time column and it is an instant - the local clock time
        // and local day the first version also wrote are gone, because a time written into a file
        // is a fact that stops agreeing with the instant the moment somebody flies somewhere.
        //
        // The count is the contract: a column added without the header being changed would shift
        // every value one place to the right of its name.
        assertEquals("one cell per column", 16, cells.size)
        assertEquals("the instant, in the one form that says so", "1970-01-01T00:00:00Z", cells[0])
        assertEquals("ping", cells[1])
        assertEquals("1840", cells[2])
        assertEquals("deepinfra/fp8/flex", cells[3])
        assertEquals("deepseek/deepseek-v4.1-flash", cells[4])
        assertEquals("3691", cells[5])
        assertEquals("the part the provider did not have to read", "3584", cells[6])
        assertEquals("1", cells[7])
        assertEquals("the column the keepalive is watched with", "1", cells[8])
        // **Plain decimal, not Java's shortest form.** `Double.toString` would write 2.41E-5, which
        // the statistics table then has to parse back - so the shape is part of the contract.
        assertEquals("0.0000241000", cells[9])
        assertEquals("length", cells[10])
    }

    @Test
    fun `a number the provider did not report is blank, not zero`() {
        // **This is the distinction the whole log turns on.** A cached response reports every usage
        // counter as 0, so a zero written where the truth is "not reported" would make an ordinary
        // ping look like a cache hit — the one reading a person might act on.
        val bare = usage("""{ "prompt_tokens": 100 }""")
        val cells = RequestLog.line(0, stats(bare)).split("\t")

        assertEquals("100", cells[5])
        assertEquals("cached was not reported", "", cells[6])
        assertEquals("completion was not reported", "", cells[7])
        assertEquals("reasoning was not reported", "", cells[8])
        assertEquals("cost was not reported", "", cells[9])
    }

    @Test
    fun `no usage block at all still writes a readable line`() {
        // A ping whose answer was refused, or a provider that simply omits `usage`: the response
        // happened and its cost is unknown. The line has to survive that without a crash.
        val cells = RequestLog.line(0, stats(null)).split("\t")
        assertEquals(16, cells.size)
        assertEquals("ping", cells[1])
        assertEquals("", cells[5])
        assertNull("and nothing was invented", RequestLog.Stats(
            kind = RequestLog.Stats.Kind.PING,
            ms = 0,
            provider = "p",
            model = "m",
            usage = null,
            finish = null,
        ).cost)
    }

    @Test
    fun `one file, one header, however many responses`() {
        val account = tmp.newFolder("account")
        // **No day boundary in the file**, which is the whole reason the shape changed: a file
        // named after a local day spans a run of UTC hours that starts and ends somewhere else.
        RequestLog.write(account, 1_000L, stats(full))
        RequestLog.write(account, 2_000L, stats(full))

        val file = RequestLog.fileFor(account)
        assertTrue("one file", file.isFile)
        val lines = file.readLines()
        assertEquals("a header and two responses", 3, lines.size)
        assertTrue("the header names the columns", lines[0].startsWith("utc\tkind\tms"))
        assertEquals("written once", 1, lines.count { it.startsWith("utc\t") })
    }

    @Test
    fun `retention drops old rows by their instant, and keeps the newest`() {
        val account = tmp.newFolder("account")
        val day = 86_400_000L
        val now = 100 * day
        // One row from long ago, one from inside the window, and the newest.
        RequestLog.write(account, now - 40 * day, stats(full), keepDays = 30)
        RequestLog.write(account, now - 1 * day, stats(full), keepDays = 30)
        RequestLog.write(account, now, stats(full), keepDays = 30)

        val body = RequestLog.fileFor(account).readLines().drop(1)
        assertEquals("the old one is gone", 2, body.size)
        assertTrue(
            "and the recent ones are kept",
            body.all {
                java.time.Instant.parse(it.substringBefore('\t')).toEpochMilli() >= now - 30 * day
            },
        )
    }

    @Test
    fun `compaction rolls a closed hour into one row and drops the raw rows`() {
        val account = tmp.newFolder("compact")
        val hour = 3_600_000L
        // **Mid-hour, deliberately.** With `now` on the hour, "an hour ago" and "a minute ago" both
        // fall in units that have already ended - so a test written that way passes whether or not
        // the code respects an open unit, which is the one thing it is here to check.
        val now = 100 * 86_400_000L + 12 * hour + 30 * 60_000L
        // Three responses in the 10:00 hour (closed), one at 12:29 (this hour, still open).
        for (i in 1..3) RequestLog.write(account, now - 2 * hour + i * 60_000L, stats(full))
        RequestLog.write(account, now - 60_000L, stats(full))

        val removed = RequestLog.compact(account, now)
        assertEquals("three raw rows let go", 3, removed)

        val rows = RequestStats.read(account)
        val hours = rows.filter { it.span == "hour" }
        assertEquals("one hour aggregate", 1, hours.size)
        assertEquals("covering three requests", 3L, hours.single().requests)
        // **Against the fixture's own number**, not a literal: `full` says 3691 and a test that
        // hard-codes a different value asserts a file the fixture does not produce.
        assertEquals("with every token summed", 3L * 3691L, hours.single().prompt)
        // **And the open hour is still raw.** Rolling it would store a number the next request
        // contradicts.
        assertEquals("the current hour is untouched", 1, rows.count { it.span == RequestLog.SPAN_RAW })
    }

    @Test
    fun `compaction keeps a line it cannot read, and every other line as it was`() {
        // **The destructive step, on the file that is the evidence.** A line this code cannot parse
        // is not parsed into a Row and rendered back - it is written back byte for byte, because
        // rendering it back would silently drop whatever it held. Pinned by writing a line nothing
        // can parse and checking it survives a compaction unchanged.
        val account = tmp.newFolder("keep")
        val now = 100 * 86_400_000L + 30 * 60_000L
        RequestLog.write(account, now - 3 * 3_600_000L, stats(full))
        val odd = "this is not a row at all\tand it must survive"
        RequestLog.fileFor(account).appendText(odd + "\n")

        RequestLog.compact(account, now)

        val lines = RequestLog.fileFor(account).readLines()
        assertTrue("the unreadable line is still there, verbatim", odd in lines)
        assertTrue("and the header is still first", lines.first().startsWith("utc\tkind\tms"))
        // Nothing to compact for the day/month/year levels yet beyond the hour, and no row may be
        // duplicated by the rewrite.
        assertEquals("no row was written twice", lines.size, lines.distinct().size)
    }

    @Test
    fun `a day is rebuilt from its hours, so a wrong aggregate is repairable`() {
        // **Why the hour rows are kept.** The windows were revised twice while this was being
        // built; if aggregates replaced hours, every old row would be frozen under an abandoned
        // rule. Here the day is derived, so recompacting recomputes it - and this asserts the one
        // property that makes that true: the day equals the sum of its own hours.
        val account = tmp.newFolder("derive")
        val day = 86_400_000L
        // **Mid-day, not midnight.** Rolling needs the DAY to be closed, and the day holding `now`
        // is not. Writing yesterday's 24 hours and compacting at the boundary of today is the
        // arrangement that makes yesterday closed and today open.
        val now = 100 * day + 12 * 3_600_000L
        // **Anchored to the start of yesterday**, not to "a day before now": the latter is a moment
        // *inside* yesterday and its first hours would reach back into the day before, so a
        // count of 24 would be a count of two days' worth and the assertion would be about the
        // wrong thing even while passing.
        val yesterday = (now / day) * day - day
        for (h in 0..23) RequestLog.write(account, yesterday + h * 3_600_000L + 60_000L, stats(full))
        RequestLog.compact(account, now)
        RequestLog.compact(account, now)

        val rows = RequestStats.read(account)
        val hourRows = rows.filter { it.span == "hour" }
        val dayRows = rows.filter { it.span == "day" }
        assertEquals("a day of hours", 24, hourRows.size)
        assertEquals("and one day", 1, dayRows.size)
        assertEquals(
            "the day is the sum of its hours, not a second opinion",
            hourRows.sumOf { it.requests },
            dayRows.single().requests,
        )
        assertEquals(
            "and its money agrees too",
            hourRows.sumOf { it.cost ?: 0.0 },
            dayRows.single().cost ?: 0.0,
            1e-9,
        )
        // Compacting twice must not double anything: the second pass has no closed hours left.
        assertEquals("the second compaction is a no-op", 0, RequestLog.compact(account, now))
    }

    @Test
    fun `a retention of zero keeps everything, which is the default`() {
        val account = tmp.newFolder("account")
        val day = 86_400_000L
        RequestLog.write(account, 1L, stats(full))
        RequestLog.write(account, 100 * day, stats(full))
        assertEquals("both rows survive", 2, RequestLog.fileFor(account).readLines().drop(1).size)
    }

    @Test
    fun `the log lives in the system project and is kept out of its repository`() {
        // **The reason this is a test and not a comment.** The system project is a real project with
        // a real repository, so a log written into it is a change waiting to be committed. It is in
        // APP_IGNORES for that reason; if that line were ever dropped, every response would show up
        // as "1 changed" and the log would be the first thing anyone deletes.
        val account = tmp.newFolder("account")
        RequestLog.write(account, 0, stats(full))

        val file = RequestLog.fileFor(account)
        assertTrue("inside the system project", file.absolutePath.contains("/system/"))
        assertTrue("in the folder the constant names", file.absolutePath.contains("/requests/"))
        assertTrue(
            "and the app's own ignore list covers it",
            Workspace.APP_IGNORES.any { "${RequestLog.DIR}/" == it },
        )
    }

    @Test
    fun `a turn and a ping are told apart, because they cost different things`() {
        val turn = RequestLog.line(0, stats(full, RequestLog.Stats.Kind.TURN)).split("\t")
        val ping = RequestLog.line(0, stats(full, RequestLog.Stats.Kind.PING)).split("\t")
        assertEquals("turn", turn[1])
        assertEquals("ping", ping[1])
    }
}
