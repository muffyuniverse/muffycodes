package com.example.notes

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * Where the log lands when Downloads is not available to it.
 *
 * ## Why this branch, and not the other one
 *
 * `MediaStore` is the path a phone actually takes, and it is the path this file cannot
 * honestly test: Robolectric answers `insert` and `openOutputStream` from a fake provider,
 * so a test here would prove that the fake worked. What it can prove is the promise both
 * paths make — **a line, once written, stays written** — and that is what a person
 * reads the log for.
 *
 * The bug this sits beside was not a wrong line, it was a missing file: the folder
 * appeared in Downloads and stayed empty, because append mode needs a file that already
 * exists. A test asserting "the file is there and holds what was written" is the shape
 * that would have caught it, which is why it is written here even though the branch under
 * it is the fallback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w411dp-h891dp")
class DownloadsSinkTest {

    private val app get() = RuntimeEnvironment.getApplication()

    private fun logFile(): File = File(app.getExternalFilesDir("trace"), DownloadsSink.NAME)

    @After
    fun tidy() {
        logFile().delete()
    }

    @Test
    fun `the name says which build wrote it, so no two builds collide`() {
        // **The operator's report**: *"the log name is dangerous, it duplicates itself and adds
        // suffixes (n). probably some more conventional naming where all trace logs are named after
        // the app build will be better."*
        //
        // The suffixes were not cosmetic. `trace.log.txt`, `trace.log (1).txt`, `trace.log (2).txt`,
        // `trace.log (3).txt` sat in Downloads — one per launch — and the newest was four days old
        // when a fresh report was expected. `MediaStore` appends ` (n)` rather than overwrite, so a
        // name that did not identify the build turned every failed lookup into another file.
        assertTrue(
            "the name carries the version: ${DownloadsSink.NAME}",
            DownloadsSink.NAME.contains(BuildConfig.VERSION_NAME),
        )
        assertTrue(
            "and the build number, because two dev builds share a version name: ${DownloadsSink.NAME}",
            DownloadsSink.NAME.contains(BuildConfig.VERSION_CODE.toString()),
        )
        assertTrue(
            "and it is a .log, not a name that grows a second extension: ${DownloadsSink.NAME}",
            DownloadsSink.NAME.endsWith(".log"),
        )
        // **The prefix is what a lookup searches by**, and it must match this build's own name or an
        // upgrade would never find the log it just wrote.
        assertTrue(
            "**the search prefix matches the name**: ${DownloadsSink.NAME} vs ${DownloadsSink.PREFIX}",
            DownloadsSink.NAME.startsWith(DownloadsSink.PREFIX),
        )
    }

    @Test
    fun `a fallback says why it fell back`() {
        // **The week this cost.** `dump()` printed no "also at" line when it had no file, and the
        // sink swallowed twelve exceptions in silence — seven of them saying nothing at all. So a
        // paste from a phone whose Downloads was refusing looked exactly like a paste from a phone
        // where everything worked, and the reason was discarded at the point it was known.
        //
        // sdk 28 takes the private route on purpose, so this asserts the reason exists on the path
        // that always falls back.
        val sink = DownloadsSink(app)
        sink.append(listOf("12:00:00.000    0 NOTE    app-opened  hi"))

        assertEquals(
            "**the fallback names its cause**: ${sink.refused}",
            "This Android is too old for Downloads; writing beside the app.",
            sink.refused,
        )
    }

    @Test
    fun `the report says when the log never reached a file`() {
        // A paste with no "also at" line was the only clue, and it took a week to notice. The paste
        // says it now, where a person reporting a bug is already reading.
        val sink = DownloadsSink(app)
        Trace.attach(sink)
        try {
            sink.append(listOf("12:00:00.000    0 NOTE    app-opened  hi"))
            val pasted = Trace.dump()

            assertTrue(
                "**the paste admits it is only memory**: ${pasted.lines().take(4)}",
                pasted.contains("**Not written to a file**"),
            )
            assertTrue(
                "and gives the reason: $pasted",
                pasted.contains("too old for Downloads"),
            )
        } finally {
            Trace.attach(null as Trace.Sink?)
        }
    }

    @Test
    fun `the first line makes the file`() {
        DownloadsSink(app).append(listOf("12:00:00.000    0 NOTE    app-opened  hi"))

        assertTrue("the file exists after the first line", logFile().isFile)
        assertEquals(
            "and holds exactly that line",
            "12:00:00.000    0 NOTE    app-opened  hi\n",
            logFile().readText(),
        )
    }

    @Test
    fun `a second line is added, not written over the first`() {
        val sink = DownloadsSink(app)
        sink.append(listOf("12:00:00.000    1 TAP     Save  milk.txt"))
        sink.append(listOf("12:00:01.000    1 CHANGED at / · 1 rows"))

        val text = logFile().readText()
        assertTrue("the tap survived: $text", text.contains("TAP"))
        assertTrue("and the answer came after it", text.endsWith("CHANGED at / · 1 rows\n"))
    }

    @Test
    fun `it says where the file it wrote actually is`() {
        val sink = DownloadsSink(app)
        sink.append(listOf("12:00:00.000    0 NOTE    app-opened  hi"))

        val where = sink.where()
        assertNotNull("it names a place", where)
        assertTrue(
            "and the place exists, rather than being a path to nothing: $where",
            File(where!!).isFile,
        )
    }

    @Test
    fun `clearing empties the file and keeps it`() {
        val sink = DownloadsSink(app)
        sink.append(listOf("12:00:00.000    0 NOTE    app-opened  hi"))
        sink.clear()

        assertTrue("the file is still there", logFile().isFile)
        assertEquals("and it is empty", "", logFile().readText())
    }
}
