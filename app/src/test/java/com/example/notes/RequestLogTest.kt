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

        // The count is the contract: a column added without the header being changed would shift
        // every value one place to the right of its name.
        // **Thirteen: eleven accounting columns and the two counts a stall row carries.** There is
        // one time column now and it is an instant - the local clock time and local day the first
        // version also wrote are gone, because a time written into a file is a fact that stops
        // agreeing with the instant the moment somebody flies somewhere.
        assertEquals("one cell per column", 13, cells.size)
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
        assertEquals(13, cells.size)
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
