package com.example.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * The bug button, and what a person's words look like once they are in the log.
 *
 * The point of every test here is the reading end. A report is worth having only if
 * whoever reads the log can tell which screen it was about, what the app already knew,
 * and whether anything was still waiting for an answer — so these assert on the text
 * that is written, not on the fact that a callback fired.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ReportTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var dir: File
    private lateinit var file: File

    /** The theme the screens are painted in, as the other screen tests do it. */
    @Composable
    private fun Frame(content: @Composable () -> Unit) {
        MaterialTheme { Surface { content() } }
    }

    @Before
    fun fresh() {
        Trace.resetForTest()
        Trace.staleMs = 2_000
        dir = File(System.getProperty("java.io.tmpdir"), "report-${System.nanoTime()}")
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

    /** The log, once everything recorded so far has been written. */
    private fun log(): String {
        Trace.awaitWrites()
        return if (file.isFile) file.readText() else ""
    }

    // ---- what a report is ---------------------------------------------------

    @Test
    fun `a report is a checkpoint in the log, not a separate document`() {
        Trace.tap("Save", "milk.txt")
        Trace.changed("at / · 1 rows · milk.txt")

        Trace.checkpoint("I pressed Save and nothing appeared in the history")

        val text = log()
        assertTrue("the tap is in the same file: $text", text.contains("TAP"))
        assertTrue("the answer too", text.contains("CHANGED"))
        assertTrue("and the report, last", text.contains("REPORT"))
        assertTrue(
            "with the person's sentence as written: $text",
            text.contains("I pressed Save and nothing appeared in the history"),
        )
        assertTrue(
            "after the events it describes, which is what makes them context",
            text.indexOf("CHANGED") < text.indexOf("REPORT"),
        )
    }

    @Test
    fun `the report did not answer the tap, so the finding survives it`() {
        // A tempting mistake: treating "the person reported a problem" as the app
        // responding. It would erase the very finding they pressed the button about.
        val id = Trace.tap("Save", "milk.txt")

        Trace.checkpoint("Save does nothing")

        // `waiting`, not `broken`: `broken` is the "waited too long" filter, and this tap
        // is milliseconds old. The claim is that the checkpoint consumed nothing, which is
        // a different question from how long it has been waiting.
        assertEquals("still unanswered", listOf(id), Trace.report().waiting.map { it.id })
    }

    @Test
    fun `a report written in the same second still sorts after its cause`() {
        // Millisecond timestamps, and a line order that does not depend on them: the file
        // is appended to, so position is the order. Two events in one millisecond must
        // still read in the order they happened.
        Trace.tap("Sync")
        Trace.failure("GitHub refused the push: rejected", "sync")
        Trace.checkpoint("sync fails")

        val lines = log().lines().filter { it.isNotBlank() }
        assertEquals("tap, refusal, report", 3, lines.size)
        assertTrue(lines[0].contains("TAP"))
        assertTrue(lines[1].contains("FAIL"))
        assertTrue(lines[2].contains("REPORT"))
    }

    @Test
    fun `the checkpoint is recoverable as the end of the log`() {
        // The workflow the whole thing exists for: a person writes their sentence, and a
        // reader can take the slice up to it without knowing anything else.
        Trace.tap("open-file", "milk.txt")
        Trace.changed("editor milk.txt")
        Trace.checkpoint("the editor opened empty")

        val text = log()
        val upToReport = text.substringBeforeLast("REPORT")
        assertTrue("the context is before the report", upToReport.contains("open-file"))
        assertTrue("and the report is the boundary", text.contains("the editor opened empty"))
    }

    // ---- the button, and the window it opens --------------------------------

    @Test
    fun `the button says what it is and opens the report when pressed`() {
        var opened = 0
        compose.setContent {
            Frame { ReportButton { opened++ } }
        }

        compose.onNodeWithContentDescription("Report a problem").assertIsDisplayed()
        compose.onNodeWithContentDescription("Report a problem").performClick()

        assertEquals("the press reaches the app", 1, opened)
    }

    @Test
    fun `the window names what has no answer, in the app's own words`() {
        val id = Trace.tap("Save", "milk.txt")
        // Five seconds in the future, so the tap is past the stale threshold and the
        // window's own filter shows it — the same thing a person sees after a tap that
        // did not work, twenty seconds ago.
        val waiting = Trace.report(System.currentTimeMillis() + 5_000).waiting

        compose.setContent {
            Frame {
                ReportBody(waiting = waiting, failures = emptyList(), note = "", onNote = {})
            }
        }

        compose.onNodeWithText("These have no answer, and are already in the log:")
            .assertIsDisplayed()
        compose.onNodeWithText("Save (milk.txt)", substring = true).assertIsDisplayed()
        // And the id is on screen, which is how the person can point at the line directly.
        assertTrue("the tap is the one that is waiting", waiting.single().id == id)
    }

    @Test
    fun `the window says so when everything was answered`() {
        // The falsification: the same window over a healthy log. If it still warned about
        // unanswered taps the section would be decoration, and a person would learn to
        // ignore it — which is how a real finding gets dismissed.
        Trace.tap("Save")
        Trace.changed("at /")

        compose.setContent {
            Frame {
                ReportBody(
                    waiting = Trace.report().waiting,
                    failures = emptyList(),
                    note = "",
                    onNote = {},
                )
            }
        }

        compose.onNodeWithText("Every tap so far was answered.").assertIsDisplayed()
        compose.onNodeWithText("These have no answer, and are already in the log:")
            .assertDoesNotExist()
    }

    @Test
    fun `the window keeps the app's words for what it refused`() {
        compose.setContent {
            Frame {
                ReportBody(
                    waiting = emptyList(),
                    failures = listOf("FAIL sync: GitHub refused the push: rejected"),
                    note = "",
                    onNote = {},
                )
            }
        }

        compose.onNodeWithText("And these were refused:").assertIsDisplayed()
        compose.onNodeWithText("GitHub refused the push", substring = true).assertIsDisplayed()
    }

    @Test
    fun `what is typed is what is written down`() {
        var typed = ""
        compose.setContent {
            Frame {
                ReportBody(waiting = emptyList(), failures = emptyList(), note = typed, onNote = { typed = it })
            }
        }

        compose.onNodeWithText("What went wrong?").performTextReplacement("sync fails on testrepo")

        assertEquals("the field took it", "sync fails on testrepo", typed)
        // And through the real path: the callback's words end up in the file.
        Trace.checkpoint(typed)
        assertTrue(
            "the log carries the sentence: ${log()}",
            log().contains("sync fails on testrepo"),
        )
    }

    @Test
    fun `a name is slugged for the column, and the detail is not`() {
        // The columns have to stay columns for `awk`, but a sentence in the detail must
        // not be mangled: it is the part a person reads.
        Trace.tap("confirm delete chosen", "3 items, one locked")
        val line = log().lines().firstOrNull { it.isNotBlank() }.orEmpty()

        assertTrue("the name is a slug: $line", line.contains("confirm-delete-chosen"))
        assertTrue("the detail keeps its spaces", line.contains("3 items, one locked"))
        assertFalse("and nothing in the name field has a space", line.split("  ")[0].contains("confirm delete chosen"))
    }
}
