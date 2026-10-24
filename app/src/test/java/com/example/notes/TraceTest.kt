package com.example.notes

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The recorder, tested as a thing that decides rather than a thing that stores.
 *
 * The question this suite exists for is the one an audit asks: *did the tap reach
 * anything?* So nearly every test here is a pair — the same trace read two ways, once
 * with an answer and once without — because a recorder that called everything healthy
 * and one that called everything broken would each pass a suite that only ever looked
 * at one case.
 */
class TraceTest {

    @Before
    fun fresh() {
        Trace.resetForTest()
    }

    @After
    fun tidy() {
        Trace.resetForTest()
        Trace.attach(null)
        Trace.staleMs = 2_000
    }

    /** An hour from now, so nothing looks stale by accident. */
    private fun soon() = System.currentTimeMillis() + 60_000

    @Test
    fun `a tap the app answered is not a broken sequence`() {
        Trace.tap("Save", "milk.txt")
        Trace.changed("at / · 1 rows · milk.txt")

        val report = Trace.report()
        assertEquals("nothing is waiting", 0, report.waiting.size)
        assertEquals("one act, one answer", 1, report.answered)
        assertTrue("and it is healthy: ${report.summary}", report.isHealthy)
    }

    @Test
    fun `a tap nobody answered is named, and says how long it has waited`() {
        // The falsification of the test above: the identical tap, with nothing after it.
        // If this came back healthy, the pairing would be decorative.
        Trace.tap("Save", "milk.txt")

        val report = Trace.report()
        assertEquals("one act is waiting", 1, report.waiting.size)
        assertEquals("and it is the tap", "Save", report.waiting.first().act.what)
        assertEquals("with the row it was about", "milk.txt", report.waiting.first().act.detail)
        assertEquals("and nothing answered", 0, report.answered)
        assertFalse("so the screen must not say healthy", report.isHealthy)
    }

    @Test
    fun `waiting is not broken until it has waited`() {
        // A tap that is half a second old is not a bug yet: every real action in this
        // app takes a moment, and flagging them all as broken would make the flag
        // worthless.
        Trace.tap("Save")
        Trace.staleMs = 2_000

        assertTrue("nothing is broken yet", Trace.broken().isEmpty())
        // The same trace, read later, is broken — and that is the only thing that
        // changed.
        val later = Trace.report(System.currentTimeMillis() + 5_000)
        assertEquals("after five seconds it is", 1, later.waiting.count { it.waitedMs > 2_000 })
    }

    @Test
    fun `words answer a tap the way a change does`() {
        // Every message the app raises goes through `Trace.said`, so a tap answered by
        // a sentence ("Signed in as muffy") must not read as unanswered.
        Trace.tap("add account")
        Trace.said("Signed in as muffy")

        val report = Trace.report()
        assertEquals("answered", 0, report.waiting.size)
        assertEquals("and by words", 1, report.answered)
    }

    @Test
    fun `a failure answers the tap and keeps the app's own words`() {
        Trace.tap("confirm sync", "testrepo")
        Trace.failure("GitHub did not accept this phone's key. Sign in again and try once more.")

        val report = Trace.report()
        assertEquals("the tap was answered", 0, report.waiting.size)
        assertEquals("in red", 1, report.failures.size)
        assertTrue(
            "with the sentence the person needs: ${report.failures}",
            report.failures.first().contains("did not accept this phone's key"),
        )
        assertFalse("and the screen must not call that healthy", report.isHealthy)
    }

    @Test
    fun `an older tap stays unanswered when a newer one is served`() {
        // Two taps in a row with one answer. The older one got nothing, and saying so
        // is the point: the newer answer belongs to the newer tap, and pretending
        // otherwise would hide exactly the missing reaction being looked for.
        Trace.tap("Save")
        Trace.tap("close editor")
        Trace.changed("screen browser dialog None")

        val report = Trace.report()
        assertEquals("one is still waiting", 1, report.waiting.size)
        assertEquals("and it is the older one", "Save", report.waiting.first().act.what)
        assertEquals("the newer one was answered", 1, report.answered)
    }

    @Test
    fun `a broken sequence is not forgotten when older lines fall out of the buffer`() {
        // The falsification of the whole design: the ring is bounded, and the oldest
        // events are dropped first. If an unanswered tap lived only in that ring, the
        // act of using the app would erase the evidence of the thing that broke it —
        // and the report would look better the longer a person waited.
        // -1, so that even an answer in the same millisecond counts as waited-for: the
        // subject here is what survives the ring, not the clock.
        Trace.staleMs = -1
        Trace.tap("Save", "the one that broke")
        repeat(Trace.EVENTS + 50) { Trace.note("noise", "line $it") }

        val broken = Trace.broken()
        assertEquals("still exactly one", 1, broken.size)
        assertEquals("and still the same tap", "Save", broken.first().act.what)
        assertTrue(
            "even though its first line has fallen out of the log",
            Trace.report().lines.none { it.contains("TAP     Save") },
        )
    }

    @Test
    fun `the log is capped and the newest lines are the ones kept`() {
        repeat(Trace.EVENTS + 100) { Trace.note("line", "number $it") }

        val lines = Trace.report().lines
        assertTrue("no more than the cap", lines.size <= Trace.EVENTS)
        assertTrue(
            "the newest is first: ${lines.first()}",
            lines.first().contains("number ${Trace.EVENTS + 99}"),
        )
    }

    @Test
    fun `every line says what it was and when it happened`() {
        Trace.tap("open file", "milk.txt")
        Trace.changed("at / · editing milk.txt")

        val lines = Trace.report().lines
        assertEquals("two lines", 2, lines.size)
        assertTrue("the answer first: ${lines[0]}", lines[0].contains("CHANGED"))
        assertTrue("naming what changed", lines[0].contains("editing milk.txt"))
        assertTrue("the tap second: ${lines[1]}", lines[1].contains("TAP"))
        assertTrue("with the row", lines[1].contains("milk.txt"))
        assertTrue(
            "and how long the answer took: ${lines[0]}",
            Regex("""\+\d+ms""").containsMatchIn(lines[0]),
        )
    }

    @Test
    fun `the durable copy is written, and is capped`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "trace-test-" + System.nanoTime())
        val file = File(dir, "trace.txt")
        Trace.attach(file)
        Trace.tap("Save", "milk.txt")
        Trace.changed("at /")

        // The writer is on its own thread, so the file answers a moment later.
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !file.isFile) Thread.sleep(20)
        assertTrue("the trace has a durable copy", file.isFile)
        assertTrue("with the tap in it", file.readText().contains("Save"))

        // A trace that grows without a ceiling fills a phone. Rather than writing tens
        // of thousands of lines through the recorder to get there, the file is put in
        // the state a long session leaves it in — past the limit — and one more line is
        // recorded, which is the line that finds it.
        val filler = buildString {
            var i = 0
            while (length < Trace.FILE_LIMIT + 8_192) appendLine("12:00:00.000  NOTE    filler  line ${i++}")
        }
        file.writeText(filler)
        assertTrue("the file is over the limit to begin with", file.length() > Trace.FILE_LIMIT)

        Trace.note("after", "the trim")
        // Waited for on the thing being asserted, not on a proxy: the writer truncates
        // and then writes, so a read that lands between the two sees a file that is
        // short enough and has no marker in it yet.
        val marker = "… earlier lines were dropped …"
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until && !file.readText().startsWith(marker)) {
            Thread.sleep(25)
        }
        assertTrue("the file stays bounded: ${file.length()}", file.length() <= Trace.FILE_LIMIT)
        assertTrue(
            "and says some lines went: ${file.readText().take(60)}",
            file.readText().startsWith(marker),
        )
        dir.deleteRecursively()
    }

    @Test
    fun `tracing cannot break the app it is watching`() {
        // A file that cannot be opened: the "parent" is a plain file, so `mkdirs` fails
        // and the write fails with it. The recorder must swallow that — a trace that
        // throws is worse than no trace, because it takes the app down while reporting.
        val parent = File(System.getProperty("java.io.tmpdir"), "not-a-dir-" + System.nanoTime())
        parent.writeText("I am a file, not a folder")
        Trace.attach(File(parent, "trace.txt"))

        Trace.tap("Save")
        Trace.changed("at /")
        Trace.failure("something refused")
        Trace.note("a note")

        val report = Trace.report()
        assertEquals("the trace still works", 1, report.answered)
        assertEquals("and still knows about the failure", 1, report.failures.size)
        Trace.attach(null)
        parent.delete()
    }

    @Test
    fun `clearing starts again`() {
        Trace.tap("Save")
        Trace.changed("at /")
        Trace.tap("another")

        Trace.clear()

        val report = Trace.report()
        assertEquals("no acts", 0, report.total)
        assertEquals("nothing waiting", 0, report.waiting.size)
        assertTrue("no lines", report.lines.isEmpty())
        assertTrue("and it says so rather than claiming health", report.isHealthy)
        assertEquals("with the words for an empty trace", "Nothing has happened yet.", report.summary)
    }
}
