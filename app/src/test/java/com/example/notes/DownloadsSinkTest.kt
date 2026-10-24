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
