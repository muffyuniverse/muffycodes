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
        assertEquals("one cell per column", 11, cells.size)
        assertEquals("00:00:00.000", cells[0])
        assertEquals("ping", cells[1])
        assertEquals("1840", cells[2])
        assertEquals("deepinfra/fp8/flex", cells[3])
        assertEquals("deepseek/deepseek-v4.1-flash", cells[4])
        assertEquals("3691", cells[5])
        assertEquals("the part the provider did not have to read", "3584", cells[6])
        assertEquals("1", cells[7])
        assertEquals("the column the keepalive is watched with", "1", cells[8])
        assertEquals("0.0000241", cells[9])
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
        assertEquals(11, cells.size)
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
    fun `one file per day, and the header is written once`() {
        val account = tmp.newFolder("account")
        // Two responses on one day, one on the next. The day is taken from the timestamp, so the
        // file a response lands in is a fact about the response rather than about when the test ran.
        val day1 = LocalDate.of(2026, 10, 28)
        val at = { h: Int, m: Int ->
            day1.atTime(h, m).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }

        RequestLog.write(account, at(9, 0), stats(full))
        RequestLog.write(account, at(9, 5), stats(full))
        RequestLog.write(account, at(9, 10).plusMillis(86_400_000), stats(full))

        val first = RequestLog.fileFor(account, day1)
        assertTrue("the day's file exists", first.isFile)
        val lines = first.readLines()
        assertEquals("a header and two responses", 3, lines.size)
        assertTrue("the header names the columns", lines[0].startsWith("time\tkind\tms"))
        assertEquals("and only once", 1, lines.count { it.startsWith("time\t") })

        val next = RequestLog.fileFor(account, day1.plusDays(1))
        assertTrue("the next day is its own file", next.isFile)
        assertEquals("with its own header", 2, next.readLines().size)
    }

    @Test
    fun `the log lives in the system project and is kept out of its repository`() {
        // **The reason this is a test and not a comment.** The system project is a real project with
        // a real repository, so a log written into it is a change waiting to be committed. It is in
        // APP_IGNORES for that reason; if that line were ever dropped, every response would show up
        // as "1 changed" and the log would be the first thing anyone deletes.
        val account = tmp.newFolder("account")
        RequestLog.write(account, 0, stats(full))

        val file = RequestLog.fileFor(account, LocalDate.of(1970, 1, 1))
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
