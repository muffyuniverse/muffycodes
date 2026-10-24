package com.example.notes

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The log, tested as something a shell can answer questions with.
 *
 * The format was chosen so that "which taps never got an answer" is a `grep` rather than
 * a judgement, so nearly every test here asserts on the **text that is written**, not on
 * an object in memory. A recorder that paired correctly in memory and wrote something
 * unparseable would pass a test that only looked at its own state, and fail the person
 * reading the file.
 */
class TraceTest {

    private lateinit var dir: File
    private lateinit var file: File

    @Before
    fun fresh() {
        Trace.resetForTest()
        Trace.staleMs = 2_000
        dir = File(System.getProperty("java.io.tmpdir"), "trace-${System.nanoTime()}")
        file = File(dir, "trace.log")
        Trace.attach(file)
    }

    @After
    fun tidy() {
        Trace.resetForTest()
        Trace.attach((null as Trace.Sink?))
        Trace.staleMs = 2_000
        dir.deleteRecursively()
    }

    /**
     * Waits until everything recorded so far is on disk.
     *
     * The writer is a queue on another thread, so polling for a line count is a race
     * dressed as a wait. A marker event goes through the same queue, so when it appears
     * everything before it has already been written — and the number is different every
     * time, so a marker from a previous call cannot be mistaken for this one.
     */
    private fun settle(): List<String> {
        val marker = "settle-${markerAt.incrementAndGet()}"
        Trace.note(marker, "all before this is on disk")
        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until) {
            val lines = if (file.isFile) file.readLines() else emptyList()
            if (lines.any { it.contains(marker) }) return lines.filter { it.isNotBlank() }
            Thread.sleep(10)
        }
        return if (file.isFile) file.readLines().filter { it.isNotBlank() } else emptyList()
    }

    private val markerAt = java.util.concurrent.atomic.AtomicInteger()

    /** The first line of the log, once everything written so far has landed. */
    private fun firstLine(): String =
        settle().firstOrNull { !it.contains("settle-") }.orEmpty()


    // ---- the columns --------------------------------------------------------

    @Test
    fun `a line is time, id, kind, name, detail`() {
        val id = Trace.tap("Save", "milk.txt")
        val line = firstLine()

        val fields = line.split(Regex("\\s+"))
        assertTrue("the time is first: $line", Regex("""^\d\d:\d\d:\d\d\.\d\d\d""").containsMatchIn(line))
        assertEquals("the id is second, which is what a shell counts", id.toString(), fields[1])
        assertEquals("the kind is third", "TAP", fields[2])
        assertEquals("the name is fourth", "Save", fields[3])
        assertTrue("and the detail is the tail, spaces and all", line.contains("milk.txt"))
    }

    @Test
    fun `a name with spaces becomes a slug, so the columns stay columns`() {
        Trace.tap("confirm delete chosen", "3 items")
        val line = firstLine()

        assertTrue("no spaces in the name field: $line", line.contains("confirm-delete-chosen"))
        // The detail is the tail. Splitting on the first double space would find the gap
        // after the time, which is why this asserts where the line ends instead.
        assertTrue("the detail keeps its own spelling: $line", line.endsWith("3 items"))
    }

    @Test
    fun `an answer quotes the id of the tap it answers`() {
        val id = Trace.tap("Save", "milk.txt")
        Trace.changed("at / · 1 rows")

        val lines = settle()
        assertEquals("two lines", 2, lines.size)
        assertTrue("the answer leads with the same id: ${lines[1]}", lines[1].contains(" $id CHANGED"))
    }

    @Test
    fun `a fact that answers nothing carries id zero`() {
        // Zero is not a tap, so a `uniq -c` pass over the ids cannot mistake a note for a
        // promise the app failed to keep.
        Trace.note("app-opened", "Muffy Codes 91")
        val line = firstLine()

        assertTrue("id zero: $line", line.split(Regex("\\s+"))[1] == "0")
        assertTrue("and the kind says it answers nothing", line.contains("NOTE"))
    }

    // ---- the pairing --------------------------------------------------------

    @Test
    fun `the id that appears once is the query the format exists for`() {
        // The whole design, stated as a shell would state it. Two taps, one answer; the
        // answer belongs to the newest tap (the UI serves one at a time, and pretending
        // an old answer was for a new tap is how a missing reaction gets hidden), so the
        // *older* tap is the one left over.
        val older = Trace.tap("Save", "milk.txt")
        val newer = Trace.tap("Sync", "testrepo")
        Trace.changed("at /")

        val lines = settle()
        val ids = lines.mapNotNull { line ->
            val fields = line.split(Regex("\\s+"))
            if (fields.size > 2 && fields[2] == "TAP") fields[1].toIntOrNull() else null
        }
        val counts = lines.mapNotNull { it.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull() }
            .groupingBy { it }.eachCount()

        assertEquals("both taps are in the log, in order", listOf(older, newer), ids)
        assertEquals("the newest is the one the answer quotes", 2, counts[newer])
        assertEquals("the older appears once, which is the finding", 1, counts[older])
        assertEquals(
            "and the app agrees with the shell",
            listOf(older),
            Trace.report().waiting.map { it.id },
        )
    }

    @Test
    fun `an older tap stays unanswered when a newer one is served`() {
        val older = Trace.tap("Save")
        val newer = Trace.tap("close-editor")
        Trace.changed("screen browser")

        val broken = Trace.report().waiting
        assertEquals("one is still waiting", 1, broken.size)
        assertEquals("and it is the older one", older, broken.first().id)
        assertTrue("the newer one was answered", newer != older)
    }

    @Test
    fun `words answer a tap the way a change does`() {
        Trace.tap("add-account")
        Trace.said("Signed in as muffy")

        assertEquals("answered by words", 0, Trace.report().waiting.size)
    }

    @Test
    fun `a refusal answers the tap and keeps the app's own sentence`() {
        Trace.tap("sync", "testrepo")
        Trace.failure("GitHub did not accept this phone's key. Sign in again.", "sync")

        val report = Trace.report()
        assertEquals("the tap was answered", 0, report.waiting.size)
        assertEquals("in red", 1, report.failures.size)
        assertTrue(
            "with the sentence a person can act on: ${report.failures}",
            report.failures.first().contains("did not accept this phone's key"),
        )
        assertFalse("so the screen must not call that healthy", report.isHealthy)
    }

    @Test
    fun `waiting is not broken until it has waited`() {
        Trace.tap("Save")
        Trace.staleMs = 2_000

        assertTrue("nothing is broken yet", Trace.broken().isEmpty())
        // The same tap, read later, is broken — and the clock is the only thing that
        // changed, which is why the waiting time is part of the finding.
        val later = Trace.report(System.currentTimeMillis() + 5_000)
        assertEquals("after five seconds it is", 1, later.waiting.count { it.waitedMs > 2_000 })
        assertTrue("and it says how long", later.waiting.first().line.contains("2410 ms") ||
            later.waiting.first().line.contains("no change and no message after 5"))
    }

    @Test
    fun `an unanswered tap outlives the screen's ring`() {
        // The ring is bounded and the oldest events fall out first. If a broken sequence
        // lived only in that ring, using the app would erase the evidence of what broke
        // it — and the report would look better the longer a person waited.
        Trace.staleMs = -1
        val id = Trace.tap("Save", "the one that broke")
        repeat(Trace.EVENTS + 50) { Trace.note("noise", "line $it") }

        val broken = Trace.broken()
        assertEquals("still exactly one", 1, broken.size)
        assertEquals("and still the same tap", id, broken.first().id)
        assertTrue(
            "even though its own line has fallen out of the ring",
            Trace.report().lines.none { it.contains("Save") },
        )
    }

    // ---- the checkpoint -----------------------------------------------------

    @Test
    fun `a report is a line in the same stream, with the person's own words`() {
        Trace.tap("Save", "milk.txt")
        Trace.checkpoint("I pressed Save and nothing appeared in the history")

        val lines = settle()
        val report = lines.last()
        assertTrue("it is a REPORT line: $report", report.contains("REPORT"))
        assertTrue(
            "carrying the sentence as written, spaces and all: $report",
            report.contains("I pressed Save and nothing appeared in the history"),
        )
        // And it sits after the taps, because a log is ordered: those lines are the
        // context, and nothing had to be captured for them to be.
        assertTrue("after the taps", lines.indexOfFirst { it.contains("TAP") } < lines.lastIndex)
    }

    @Test
    fun `a report with no words still marks the place`() {
        Trace.checkpoint("")

        val line = firstLine()
        assertTrue("the checkpoint exists: $line", line.contains("REPORT"))
        assertTrue(
            "and says it was a report rather than looking like an empty line",
            line.contains("a problem was reported"),
        )
    }

    @Test
    fun `a checkpoint does not answer the tap it was written about`() {
        // The falsification of a tempting mistake: writing a report is not the app
        // responding to the thing that went wrong. If it consumed the waiting tap, the
        // finding would be erased by the very act of reporting it.
        val id = Trace.tap("Save", "milk.txt")
        Trace.checkpoint("Save does nothing")

        // `waiting`, not `broken`: `broken` is the stale filter, and this tap is
        // milliseconds old. The claim being tested is that the checkpoint consumed
        // nothing, which is a different question from how long it has waited.
        assertEquals("still waiting", listOf(id), Trace.report().waiting.map { it.id })
    }

    // ---- the file -----------------------------------------------------------

    @Test
    fun `the log is appended to, never rewritten`() {
        Trace.note("first", "one")
        val before = firstLine()
        Trace.note("second", "two")

        val after = settle()
        assertEquals("the first line is still there, unchanged", before, after.first { it.contains("first") })
        assertTrue("and the second came after it", after.indexOfFirst { it.contains("second") } > after.indexOfFirst { it.contains("first") })
    }

    @Test
    fun `the log is trimmed when it grows, and says where`() {
        // Rather than writing thousands of lines through the recorder to get there, the
        // file is put in the state a long session leaves it in and one more line is
        // recorded — which is the line that finds it.
        val filler = buildString {
            var i = 0
            while (length < Trace.LOG_LIMIT + 8_192) appendLine("12:00:00.000    0 NOTE    filler  line ${i++}")
        }
        file.parentFile?.mkdirs()
        file.writeText(filler)
        assertTrue("the file is over the limit to begin with", file.length() > Trace.LOG_LIMIT)

        Trace.note("after", "the trim")

        val until = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < until && !file.readText().startsWith("… the oldest")) {
            Thread.sleep(25)
        }
        assertTrue("it stays bounded: ${file.length()}", file.length() <= Trace.LOG_LIMIT)
        assertTrue(
            "and the boundary says so, so a reader knows the log has a start: " +
                file.readText().take(60),
            file.readText().startsWith("… the oldest lines were dropped"),
        )
        assertTrue("and the line that caused it survived", file.readText().contains("after"))
    }

    @Test
    fun `a log that cannot be written does not break the app`() {
        // A file whose parent is a plain file: `mkdirs` fails, every append fails. A
        // recorder that threw would take the app down while reporting on it.
        val parent = File(System.getProperty("java.io.tmpdir"), "not-a-dir-${System.nanoTime()}")
        parent.writeText("I am a file, not a folder")
        Trace.attach(File(parent, "trace.log"))

        Trace.tap("Save")
        Trace.changed("at /")
        Trace.failure("something refused")
        Trace.checkpoint("it does not work")

        val report = Trace.report()
        assertEquals("the ring still works", 1, report.answered)
        assertEquals("and still knows about the failure", 1, report.failures.size)
        parent.delete()
    }

    @Test
    fun `clearing the screen keeps the file`() {
        Trace.tap("Save")
        Trace.changed("at /")
        settle()

        Trace.clearScreen()

        // The ring is emptied of what happened; the one line left is the note that says
        // it was cleared, which is itself worth seeing.
        assertTrue(
            "no tap remains on the screen: ${Trace.report().lines}",
            Trace.report().lines.none { it.contains("TAP") },
        )
        assertTrue(
            "and the clearing is on record",
            Trace.report().lines.any { it.contains("screen-cleared") },
        )
        // The file is the evidence. A Clear that deleted it would be the one control in
        // the app that destroys the record of what went wrong.
        val kept = if (file.isFile) file.readText() else ""
        assertTrue("the file still has the tap: $kept", kept.contains("TAP"))
    }

    @Test
    fun `clearing everything wipes the log too`() {
        Trace.tap("Save")
        Trace.changed("at /")
        settle()

        Trace.clear()

        val now = if (file.isFile) file.readText() else ""
        assertFalse("nothing of the old session: $now", now.contains("Save"))
    }

    @Test
    fun `where says where the log is`() {
        assertNotNull("a file sink can name itself", Trace.where)
        Trace.attach((null as Trace.Sink?))
        assertEquals("and no sink means nowhere", null, Trace.where)
    }

    @Test
    fun `nothing is written when no sink is attached`() {
        // The release build's case: no sink, so no file, no directory, nothing on disk.
        Trace.attach((null as Trace.Sink?))
        Trace.tap("Save")
        Trace.changed("at /")
        Trace.checkpoint("this must go nowhere")

        assertFalse("no file was made", file.exists())
        // The ring still works, because the trace screen is part of the app.
        assertTrue("but the screen still shows it", Trace.report().lines.isNotEmpty())
    }
}
