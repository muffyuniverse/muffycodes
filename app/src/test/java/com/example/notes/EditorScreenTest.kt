package com.example.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * What the editor screen actually puts on the glass, for the three kinds of file
 * §9 defines.
 *
 * The rest of the suite is behavioural: it drives the ViewModel and asks what
 * happened on disk. That is the right shape for almost everything, but it cannot
 * answer "is the picture shown" — the promise in §9 is about a screen, and a
 * ViewModel test would pass while the screen drew nothing at all. So these few
 * tests paint the real composable and look at it.
 *
 * The one that matters most is the third: a file the app does not understand must
 * not offer a text field. Every other kind of mistake here is cosmetic; that one
 * rewrites somebody's `.pdf` and loses it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @After
    fun putThePhoneAway() {
        scene.close()
    }

    /**
     * A signed-in phone: §9 is about files, and a file needs an account's tree to
     * live in. The test account is the one that needs no key.
     */
    private val scene: Scenario by lazy {
        Scenario(RuntimeEnvironment.getApplication(), signIn = true)
    }

    @Composable
    private fun Frame(content: @Composable () -> Unit) {
        MaterialTheme { Surface { content() } }
    }

    private fun editorFor(file: File) {
        // The screen is painted on its own, but saving goes through the view model,
        // which has to know which file is open — exactly as the app tells it when a
        // row is tapped.
        scene.viewModel.openEditor(file)
        compose.setContent {
            Frame {
                EditorScreen(
                    viewModel = scene.viewModel,
                    file = file,
                    onBack = {},
                    onOpenHistory = {},
                )
            }
        }
    }

    @Test
    fun `Save keeps what was typed, and back leaves it as it was`() {
        // The pair the top row promises. Save is a button because saving is
        // something a person does, and back is back: what was typed and not saved
        // does not reach the file.
        val file = scene.file("milk.txt", "two litres")

        editorFor(file)
        compose.onNodeWithText("two litres").performTextReplacement("three litres")
        // **The tap is recorded before the work starts**, so its absence can be measured — a
        // check for "nothing is waiting" run too early would pass on a save that never happened.
        val tapsBefore = Trace.report().taps
        val answeredBefore = Trace.report().answered
        compose.onNodeWithText("Save").performClick()
        // Saving writes on the app's worker, so the file answers a moment later.
        compose.waitUntil(10_000) { file.readText() == "three litres" }
        assertTrue("the save was recorded as a tap", Trace.report().taps > tapsBefore)
        // **Waited on a counter that only the answer moves.** The message is no use here:
        // `scene.file(...)` saved this same file a moment ago, so it already says "Saved
        // milk.txt" — waiting for it returned at once and the assertion below measured the
        // *previous* save's row. `answered` is monotone and counts only taps that were
        // answered, which is exactly the question.
        scene.await("the save to answer its tap") {
            Trace.report().answered > answeredBefore
        }
        // And the row is the save's own: the words the app said, under the press that did it.
        val report = Trace.report()
        assertTrue(
            "the log says the save was answered: newest ${report.lines.take(3)}",
            report.lines.take(3).any { it.contains("SAID") && it.contains("Saved milk.txt") },
        )

        assertEquals("saved", "three litres", file.readText())

        compose.onNodeWithText("three litres").performTextReplacement("four litres")
        compose.onNodeWithContentDescription("Back without saving").performClick()

        assertEquals("and the unsaved change went with it", "three litres", file.readText())
        assertTrue("with a note saying so", scene.viewModel.message == "Left without saving")
    }

    @Test
    fun `renaming the file does not throw away what was typed`() {
        // The trap this exists for: the editor reset its text when the file's path
        // changed, and renaming changes the path — so typing a paragraph and then
        // fixing the file's name silently emptied the screen.
        val file = scene.file("milk.txt", "two litres")

        editorFor(file)
        compose.onNodeWithText("two litres").performTextReplacement("three litres")
        compose.onNodeWithText("milk.txt").performTextReplacement("shopping list")
        compose.onNodeWithText("shopping list").performImeAction()

        compose.onNodeWithText("three litres").assertIsDisplayed()
        compose.onNodeWithText("Save").performClick()

        val renamed = File(scene.viewModel.currentDir!!, "shopping_list.txt")
        assertTrue("the file was renamed", renamed.isFile)
        // Saving happens on the app's worker, so the file answers just after the
        // button does — the same wait a person never notices.
        compose.waitUntil(10_000) { renamed.readText() == "three litres" }
        assertEquals("with the text that was on the screen", "three litres", renamed.readText())
    }

    @Test
    fun `renaming twice shows the name the app wrote, both times`() {
        // The lag, exactly: the field showed the *previous* name after every
        // rename, because "done" commits the name and then moving focus commits it
        // again — and the second attempt compared the new name against the file as
        // it was before the first, failed, and put the old name back.
        val file = scene.file("milk.txt", "two litres")

        editorFor(file)
        compose.onNodeWithText("milk.txt").performTextReplacement("shopping list")
        compose.onNodeWithText("shopping list").performImeAction()

        compose.onNodeWithText("shopping_list.txt").assertIsDisplayed()
        assertTrue(
            "the file, renamed",
            File(scene.viewModel.currentDir!!, "shopping_list.txt").isFile,
        )

        compose.onNodeWithText("shopping_list.txt").performTextReplacement("groceries")
        compose.onNodeWithText("groceries").performImeAction()

        compose.onNodeWithText("groceries.txt").assertIsDisplayed()
        assertTrue(
            "renamed again from the name it now has",
            File(scene.viewModel.currentDir!!, "groceries.txt").isFile,
        )
    }

    @Test
    fun `the top row is the way back and the name, and nothing else`() {
        // The delete command used to live behind a three-dot button up here, beside
        // Save: the most destructive thing in the app, one press away from the most
        // ordinary one, in a screen with no rows to show what "it" even is.
        val file = scene.file("milk.txt", "two litres")

        editorFor(file)

        compose.onNodeWithContentDescription("More").assertDoesNotExist()
        compose.onNodeWithText("Delete").assertDoesNotExist()
        // The four things that are still there, in the order the row gives them.
        compose.onNodeWithContentDescription("Back without saving").assertIsDisplayed()
        compose.onNodeWithText("milk.txt").assertIsDisplayed()
        compose.onNodeWithText("History").assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsDisplayed()
    }

    @Test
    fun `a locked file opens in view and offers no way to write it`() {
        // Locked means "can only be viewed". The switch is what would otherwise
        // put the text field back, so it is the switch that has to refuse.
        val file = scene.file("milk.txt", "two litres")
        scene.viewModel.toggleLocked(file)

        editorFor(file)

        compose.onNodeWithText("two litres").assertIsDisplayed()
        compose.onNodeWithContentDescription("Locked, so it cannot be edited")
            .assertIsDisplayed()
        compose.onNodeWithText("Save").assertIsNotEnabled()
        // The switch cannot put it back into editing.
        compose.onNodeWithText("View").assertIsNotEnabled()
    }

    @Test
    fun `an unlocked file opens ready to be written`() {
        // "switch enabled on enter": opening a note is asking to write it.
        val file = scene.file("milk.txt", "two litres")

        editorFor(file)

        compose.onNodeWithText("Edit").assertIsDisplayed()
        compose.onNodeWithText("two litres").performTextReplacement("three litres")
        compose.onNodeWithText("Save").performClick()
        // The write and the commit are one act on the app's worker, so the file on
        // disk answers a moment after the button does — exactly as on a phone.
        compose.waitUntil(10_000) { file.readText() == "three litres" }
        assertEquals("written", "three litres", file.readText())
    }

    @Test
    fun `markdown is shown as the thing it describes in view mode`() {
        // A `.md` file read as bytes still has its `#` and its `**`, which looks
        // like a draft rather than a finished page. The reader is only for view
        // mode: editing is editing, and the markers are the text there.
        val file = scene.file("notes.md", "# Shopping\n\n- milk\n- bread")

        scene.viewModel.openEditor(file)
        compose.setContent {
            Frame {
                EditorScreen(
                    viewModel = scene.viewModel,
                    file = file,
                    onBack = {},
                    onOpenHistory = {},
                )
            }
        }
        // Into view mode.
        compose.onNodeWithText("Edit").performClick()

        compose.onNodeWithText("Shopping").assertIsDisplayed()
        compose.onNodeWithText("# Shopping", substring = true).assertDoesNotExist()
        compose.onNodeWithText("- milk", substring = true).assertDoesNotExist()
        compose.onNodeWithText("milk", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a picture written in a note is drawn where the note says`() {
        // The reported bug: a `.md` file with `![](cat.png)` beside it showed the
        // address instead of the picture. A picture in a note is the one part of a
        // Markdown file that a reader cannot get from the text alone.
        val picture = scene.file("cat.png")
        picture.writeBytes(ONE_PIXEL_PNG)
        val note = scene.file("notes.md", "# A cat\n\n![a cat](cat.png)\n")

        scene.viewModel.openEditor(note)
        compose.setContent {
            Frame {
                EditorScreen(
                    viewModel = scene.viewModel,
                    file = note,
                    onBack = {},
                    onOpenHistory = {},
                )
            }
        }
        // Into view mode, which is where a note is read rather than typed.
        compose.onNodeWithText("Edit").performClick()

        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("a cat").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("a cat").assertIsDisplayed()
        // The address is not left lying on the page as if it were the picture.
        compose.onNodeWithText("cat.png", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a note whose picture is not there says so in words`() {
        // The falsification: a reader that drew nothing for a missing picture would
        // leave a gap that looks like a broken screen, and the person would have no
        // idea which picture they meant. The alt text and the address are shown.
        val note = scene.file("notes.md", "![a cat](missing.png)\n")

        scene.viewModel.openEditor(note)
        compose.setContent {
            Frame {
                EditorScreen(
                    viewModel = scene.viewModel,
                    file = note,
                    onBack = {},
                    onOpenHistory = {},
                )
            }
        }
        compose.onNodeWithText("Edit").performClick()

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("could not be read here", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("a cat", substring = true).assertIsDisplayed()
        compose.onNodeWithText("missing.png", substring = true).assertIsDisplayed()
    }

    @Test
    fun `Save has nothing to do until something is typed`() {
        val file = scene.file("milk.txt", "two litres")

        editorFor(file)

        compose.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun `the name can be changed where it stands`() {
        // No separate Rename window: the name is the title, and the title is the
        // field. Typing a new one and finishing renames the file.
        val file = scene.file("milk.txt", "two litres")

        editorFor(file)
        compose.onNodeWithText("milk.txt").performTextReplacement("Shopping List")
        compose.onNodeWithText("Shopping List").performImeAction()

        val dir = scene.viewModel.currentDir!!
        assertTrue(
            "under the app's one name shape",
            File(dir, "shopping_list.txt").isFile,
        )
        assertFalse("and not under the old one", File(dir, "milk.txt").exists())
    }

    @Test
    fun `a text file is shown in a field that can be typed into`() {
        val file = scene.file("milk.txt", "two litres")

        editorFor(file)

        compose.onNodeWithText("two litres").assertIsDisplayed()
    }

    @Test
    fun `an unknown file says it is not text, and offers no field to type in`() {
        // The destructive case. A `.zip` must not be editable, and the screen must
        // say why rather than show an empty editor that invites a mistake.
        // Written straight to disk, not through the scenario helper: the helper
        // writes text through `saveEditor`, and the app now rightly refuses to write
        // text into a .zip. A test that could still do it would be testing a door
        // that has been closed.
        val file = File(scene.viewModel.currentDir!!, "archive.zip")
        file.writeBytes(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00))
        scene.viewModel.refresh()

        editorFor(file)

        compose.onNodeWithText(
            "This file is not text, so it is not opened here.",
        ).assertIsDisplayed()
        // And the extension is named back, so a person can see what the app thinks
        // it is looking at — which is the difference between "this app is broken"
        // and "this app does not edit zips".
        compose.onNodeWithText("ends in .zip", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a picture that cannot be read says so instead of showing an empty frame`() {
        // A truncated download, or a format the phone has no decoder for. Silence
        // would be indistinguishable from the app being broken.
        // Same reason: bytes, not text.
        val file = File(scene.viewModel.currentDir!!, "broken.png")
        file.writeBytes("this is not a png".toByteArray())
        scene.viewModel.refresh()

        editorFor(file)

        // **The decode happens off the main thread,** so the first frame is a spinner
        // and the answer arrives after it. Asserting straight away caught whichever of
        // the two the scheduler happened to reach first, which is why this passed for
        // months and then stopped — not because the app changed, but because the
        // machine did. Waiting for the thing being asserted is the fix.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("This picture could not be read on this phone.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(
            "This picture could not be read on this phone.",
        ).assertIsDisplayed()
    }

    @Test
    fun `a picture deleted before it opens says so rather than throwing`() {
        // Ordinary in use: the list is drawn, the file goes away, the screen opens.
        // The answer is "cannot read it", not a crash — and NATIVE graphics mode is
        // what makes this a real decode rather than a shadow's opinion.
        val file = scene.file("gone.png")
        file.delete()

        editorFor(file)

        // The same wait as the test above, and for the same reason: the answer to "can
        // this be decoded" arrives after the first frame.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("This picture could not be read on this phone.")
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(
            "This picture could not be read on this phone.",
        ).assertIsDisplayed()
    }

    @Test
    fun `a real picture is drawn as a picture`() {
        // A one-pixel PNG, which is the smallest thing that is genuinely a picture.
        val file = scene.file("dot.png")
        file.writeBytes(ONE_PIXEL_PNG)

        editorFor(file)

        // The Image carries the file's name as its description, which is how a
        // screen reader announces it and how this test finds it. Waiting is needed
        // because the decode happens off the main thread.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("dot.png").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("dot.png").assertIsDisplayed()
        // And it is not showing the "could not be read" message.
        compose.onNodeWithText(
            "This picture could not be read on this phone.",
        ).assertDoesNotExist()
    }

    private companion object {
        /**
         * The smallest valid PNG: 1×1, transparent.
         *
         * The test decodes it through the same [Pictures] code the app uses, so
         * this is a real decode and not a stub. A fake would only prove the test's
         * belief about bitmaps.
         */
        val ONE_PIXEL_PNG: ByteArray = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15.toByte(), 0xC4.toByte(),
            0x89.toByte(), 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
            0x54, 0x78.toByte(), 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00,
            0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
            0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        )
    }
}
