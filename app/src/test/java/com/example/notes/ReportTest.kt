package com.example.notes

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
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
 * The bug button, and what a report actually contains when it leaves the phone.
 *
 * The point of every test here is the reading end: a report is only worth having if
 * whoever gets it can tell which screen it was about, what the app already knew, and
 * whether anything was waiting for an answer. So these assert on the text that ships,
 * not on the fact that a callback fired.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ReportTest {

    /** The theme the screens are painted in, as the other screen tests do it. */
    @Composable
    private fun Frame(content: @Composable () -> Unit) {
        MaterialTheme { Surface { content() } }
    }

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun fresh() {
        Trace.resetForTest()
        Trace.staleMs = 2_000
    }

    @After
    fun tidy() {
        Trace.resetForTest()
        Trace.attach(null)
        Trace.staleMs = 2_000
    }

    // ---- the file that lands in Downloads ---------------------------------

    @Test
    fun `a report's name sorts by when it was made`() {
        val first = Reports.name("report", at = 1_761_300_000_000, slug = "save did nothing")
        val second = Reports.name("report", at = 1_761_300_600_000, slug = "save did nothing")

        assertTrue("it says what it is", first.startsWith("report-"))
        assertTrue("and keeps the words", first.contains("save-did-nothing"))
        assertTrue("and ends as a document", first.endsWith(".md"))
        assertTrue(
            "and two reports an hour apart sort in order: $first / $second",
            first < second,
        )
    }

    @Test
    fun `a trace and a report are told apart before either is opened`() {
        // A folder full of files called "report-…" would make the app's own record
        // indistinguishable from a person's description of what broke.
        assertTrue(Reports.name("trace", at = 1_761_300_000_000).startsWith("trace-"))
        assertTrue(Reports.name("report", at = 1_761_300_000_000).startsWith("report-"))
    }

    @Test
    fun `a name survives punctuation that would break a file name`() {
        val name = Reports.name("report", at = 1_761_300_000_000, slug = "save: did/nothing?!")

        assertFalse("no slashes", name.contains('/'))
        assertFalse("no colons", name.contains(':'))
        assertFalse("no question marks", name.contains('?'))
        assertTrue("still readable: $name", name.contains("save-did-nothing"))
    }

    @Test
    fun `the slug is the first few words the person wrote`() {
        assertEquals(
            "sync-keeps-failing",
            Reports.slugOf("Sync keeps failing on this project, every time I try."),
        )
        assertEquals("a blank note has no slug", "", Reports.slugOf("   \n  "))
    }

    @Test
    fun `a saved report can be read back where it says it is`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val text = "# what went wrong\nthe sync kept failing"

        val saved = Reports.save(context, text, slug = "sync", what = "report")

        assertTrue("it was written somewhere: ${saved.where}", saved.ok)
        if (!saved.shared) {
            // Not Downloads (an emulated device where MediaStore is not there), so the
            // app's own external folder — and the address must be a real file, because
            // an answer that names nothing is how "restore deleted did nothing" felt.
            val file = File(saved.where!!)
            assertTrue("the file is there", file.isFile)
            assertEquals("with the report in it", text, file.readText())
            file.delete()
        }
    }

    // ---- what a report contains --------------------------------------------

    @Test
    fun `a report leads with the words and the unanswered taps`() {
        // The app already knows a save never answered; the person types one sentence.
        // Both must be in what ships, and the person's own words must come first.
        // -1, so the tap counts as unanswered even in the millisecond it was made: the
        // subject is what the report contains, not the clock.
        Trace.staleMs = -1
        Trace.tap("Save", "milk.txt")
        Trace.note("a fact", "worth keeping")
        val frozen = Trace.freeze(listOf("screen" to "editor of milk.txt", "account" to "muffy"))

        val text = Trace.reportText(frozen, "I pressed Save and nothing happened")

        val lines = text.lines()
        assertEquals("the heading", "Muffy Codes — problem report", lines[0])
        assertTrue(
            "the person's sentence is the first thing in it: ${lines.take(6)}",
            lines.drop(1).first { it.isNotBlank() }.contains("I pressed Save"),
        )
        assertTrue("the app's own finding is named", text.contains("Save (milk.txt)"))
        assertTrue("with how long it waited", text.contains("no change and no message after"))
        assertTrue("and where it happened", text.contains("screen: editor of milk.txt"))
        assertTrue("and whose account", text.contains("account: muffy"))
        assertTrue("and the record itself", text.contains("worth keeping"))
    }

    @Test
    fun `a report with nothing stuck says so instead of going quiet`() {
        // The falsification of the test above: an app that was answering everything must
        // not send an empty "Refused:" section, because a reader cannot tell an empty
        // section from a section the sender forgot to fill in.
        Trace.tap("Save")
        Trace.changed("at /")
        val text = Trace.reportText(Trace.freeze(), "just checking")

        assertTrue(
            "it says the app was answering: $text",
            text.contains("Nothing was waiting for an answer"),
        )
        assertFalse("and does not invent a broken sequence", text.contains("Taps with no answer"))
        assertFalse("and does not invent a refusal", text.contains("Refused:"))
    }

    @Test
    fun `a report keeps the app's words for what it refused`() {
        Trace.tap("Confirm sync", "testrepo")
        Trace.failure("GitHub did not accept this phone's key. Sign in again and try once more.")

        val text = Trace.reportText(Trace.freeze(), "sync fails")

        assertTrue(
            "the sentence a person can act on is in the report: $text",
            text.contains("did not accept this phone's key"),
        )
    }

    // ---- the button, and the window it opens -------------------------------

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
    fun `the window shows what the app already noticed`() {
        Trace.staleMs = -1
        Trace.tap("Save", "milk.txt")
        val frozen = Trace.freeze()

        compose.setContent {
            Frame {
                ReportDialog(frozen = frozen, onDismiss = {}, onSend = {})
            }
        }

        compose.onNodeWithText("The app had already noticed these:").assertIsDisplayed()
        compose.onNodeWithText("Save (milk.txt)", substring = true).assertIsDisplayed()
    }

    @Test
    fun `what is typed is what is sent`() {
        var sent: String? = null
        // Frozen once, outside the composition: a `freeze()` called from inside a
        // composable would take a new snapshot on every recomposition, which is
        // precisely the thing `Frozen` exists to prevent.
        val frozen = Trace.freeze()
        compose.setContent {
            Frame {
                ReportDialog(frozen = frozen, onDismiss = {}, onSend = { sent = it })
            }
        }

        compose.onNodeWithText("What went wrong?").performTextReplacement("sync fails on testrepo")
        compose.onNodeWithText("Send").performClick()

        assertEquals("the words travel with the report", "sync fails on testrepo", sent)
    }
}
