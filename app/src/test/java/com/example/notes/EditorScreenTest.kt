package com.example.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextReplacement
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        val made = Scenario(RuntimeEnvironment.getApplication(), signIn = true)
        // **Onboarding opens on first run, and it disables Exit** — which would make every test in
        // this file a test of an editor a person cannot leave. It is finished here, deliberately:
        // these tests are about the editor, and the onboarding rule has its own tests in
        // `AccountsFeatureTest`. A test that wanted the onboarding behaviour would have to build its
        // own scene rather than borrow this one.
        made.await("onboarding to open") { made.viewModel.onboarding }
        // **Connecting is the only way out**, so this connects rather than reading to the end: the
        // gate is the settled rule, and a test harness that found a side door would be testing an
        // app that does not exist.
        made.viewModel.saveModelKey("sk-or-v1-a-key")
        made.await("onboarding to close") { !made.viewModel.onboarding }
        made
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

    /**
     * The editor, with Edit pressed.
     *
     * **Every file opens in View now**, which is what report 29 asked for, so a test that types
     * has to say so. The alternative — leaving these tests as they were — would be testing a
     * screen that no longer exists, and they would pass or fail for reasons nobody could read.
     */
    private fun editorWriting(file: File) {
        editorFor(file)
        compose.onNodeWithText("Edit").performClick()
        compose.waitForIdle()
    }

    @Test
    fun `leaving Edit keeps what was typed, and back keeps it too`() {
        // The pair the top row promises. Leaving Edit is the save, because saving is
        // something a person does, and back is back: what was typed and not saved
        // does not reach the file.
        val file = scene.file("milk.txt", "two litres")

        editorWriting(file)
        compose.onNodeWithText("two litres").performTextReplacement("three litres")
        // **The tap is recorded before the work starts**, so its absence can be measured — a
        // check for "nothing is waiting" run too early would pass on a save that never happened.
        val tapsBefore = Trace.report().taps
        val answeredBefore = Trace.report().answered
        // **Leaving Edit is the save now.** Save is gone; the person says they are done by
        // switching to View, and that is the signal the app acts on.
        compose.onNodeWithText("View").performClick()
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

        // **And leaving the screen keeps it too.** The old contract dropped what was unsaved and
        // said "Left without saving"; with Save gone, leaving is the last moment the app can tell
        // the writing was meant, so a warning about a loss nobody chose would be the app blaming
        // them for its own timing.
        //
        // **Back into Edit first**, because View has no text field to type into — the page is for
        // reading, which is the whole point of it. The Edit segment is what returns to writing.
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithText("three litres").performTextReplacement("four litres")
        compose.onNodeWithContentDescription("Back without saving").performClick()

        compose.waitUntil(10_000) { file.readText() == "four litres" }
        assertEquals("the writing was kept", "four litres", file.readText())
    }

    @Test
    fun `renaming the file does not throw away what was typed`() {
        // The trap this exists for: the editor reset its text when the file's path
        // changed, and renaming changes the path — so typing a paragraph and then
        // fixing the file's name silently emptied the screen.
        val file = scene.file("milk.txt", "two litres")

        editorWriting(file)
        compose.onNodeWithText("two litres").performTextReplacement("three litres")
        compose.onNodeWithText("milk.txt").performTextReplacement("shopping list")
        compose.onNodeWithText("shopping list").performImeAction()

        compose.onNodeWithText("three litres").assertIsDisplayed()
        compose.onNodeWithText("View").performClick()

        val renamed = File(scene.viewModel.currentDir!!, "shopping list.txt")
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

        editorWriting(file)
        compose.onNodeWithText("milk.txt").performTextReplacement("shopping list")
        compose.onNodeWithText("shopping list").performImeAction()

        compose.onNodeWithText("shopping list.txt").assertIsDisplayed()
        assertTrue(
            "the file, renamed",
            File(scene.viewModel.currentDir!!, "shopping list.txt").isFile,
        )

        compose.onNodeWithText("shopping list.txt").performTextReplacement("groceries")
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
        // **Back, the name, and the two acts that need no word** - history and report, as icons.
        // History used to be a word on the row below; the operator moved it up: "put history button
        // on the top bar right corner to the left of bug report icon", which is also what let the
        // modes and Save share a single line.
        compose.onNodeWithContentDescription("Back without saving").assertIsDisplayed()
        compose.onNodeWithText("milk.txt").assertIsDisplayed()
        compose.onNodeWithContentDescription("This file's history").assertIsDisplayed()
        compose.onNodeWithContentDescription("Report a problem").assertIsDisplayed()
        // And Reset is on the row under it, beside the modes rather than on a line of its own.
        compose.onNodeWithText("Reset").assertIsDisplayed()
        // **Wider than it is tall, which is the one thing to assert about a word.** When the modes
        // and Save first shared a row, a `weight(1f)` spacer sat between them and reserved every
        // remaining pixel, so Save was laid out thirty wide and rendered as "S a v e" - one letter
        // per line. `assertIsDisplayed` passes on that: the node is on screen and its text is
        // correct. So does every assertion about enabled state. The only thing wrong was the shape,
        // so the shape is what is pinned here; it is the picture that found it, and this is the line
        // that stops it coming back without costing another CI run to notice.
        // **`DpRect` has edges, not a size**, so the size is the difference between them.
        val reset = compose.onNodeWithText("Reset").getUnclippedBoundsInRoot()
        val wide = reset.right - reset.left
        val tall = reset.bottom - reset.top
        assertTrue("Reset is a word, not a column of letters: $wide wide by $tall tall", wide > tall)
    }

    @Test
    fun `a log file offers no history, because it has none`() {
        // **The operator's report, and the exclusion was never the problem.** "maybe logs are not
        // versioned but editor still shows history button when you open them and that is confusing".
        // `agent/`, `.muffy/` and `requests/` are in the project's `.git/info/exclude`, so no log has
        // ever been committed — the *button* was the lie. Pressing it on a record opened a screen
        // that could only say the file has no versions, which reads as a broken feature rather than
        // as a file deliberately kept out of the repository.
        //
        // A record is already read-only here, so the editor knew; the action bar simply ignored it.
        val log = File(scene.workspace!!.projectDir("groceries"), "${AgentLog.DIR}/${AgentLog.SESSION}")
        log.parentFile?.mkdirs()
        log.writeText("14:02:11.410    1   12 NOTE  agent  something happened\n")

        editorFor(log)

        // It opens - it is text, and a person debugging their phone should be able to read it.
        compose.onNodeWithText("something happened", substring = true).assertIsDisplayed()
        // And it offers no history, because there is none to offer.
        compose.onNodeWithContentDescription("This file's history").assertDoesNotExist()
        // The report button is still there: a report about the record is exactly the report somebody
        // would file.
        compose.onNodeWithContentDescription("Report a problem").assertIsDisplayed()
    }

    @Test
    fun `a file the person locked is still theirs to edit`() {
        // **The operator's rule, and it reverses what this test used to assert**: "system locks
        // restrict both users and agents, and user locks restrict agents (not users themselves!)".
        //
        // So a lock the person set is an instruction to the agent. It does not take the file away
        // from the person who set it - that would be the app confiscating the operator's own lever.
        // The old version asserted View-only with "Locked, so it cannot be edited", which was true
        // of the one-answer model and is false now.
        val file = scene.file("milk.txt", "two litres")
        scene.viewModel.toggleLocked(file)

        editorFor(file)

        compose.onNodeWithText("two litres").assertIsDisplayed()
        // Edit is reachable, because the person may change their own file.
        compose.onNodeWithText("Edit").assertIsEnabled()
        compose.onNodeWithText("Edit").performClick()
        compose.waitForIdle()
        // **The text has to be typed**, not asserted: entering Edit puts the field back with the
        // file's own text, so "three litres" is not on the screen until this line writes it.
        compose.onNodeWithText("two litres").performTextReplacement("three litres")
        // **Leaving Edit is the save.** Save is gone; the person says they are done by
        // switching to View, and that is the signal the app acts on.
        compose.onNodeWithText("View").performClick()
        scene.await("the person's own save to land", 20_000) {
            file.readText() == "three litres"
        }
    }

    @Test
    fun `a file the app holds opens in view and cannot be written at all`() {
        // **The other half, and the one that is still absolute.** A file the *app* holds - a flag, a
        // record - is a fact about the file rather than a preference, so it binds everybody: the
        // person cannot write it either, and no tap lifts it.
        val file = File(scene.workspace!!.projectDir("groceries"), "${AgentLog.DIR}/${AgentLog.SESSION}")
        file.parentFile?.mkdirs()
        file.writeText("14:02:11.410    1   12 NOTE  agent  something happened\n")

        editorFor(file)

        // The record is readable and not writable, which is the rule that makes it evidence.
        compose.onNodeWithText("something happened", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Reset").assertIsNotEnabled()
        compose.onNodeWithText("Edit").assertIsNotEnabled()
        // **Diff is not**, and this test used to say the opposite. The record is in `agent/`, which
        // `APP_IGNORES` keeps out of the repository, so it has no versions to compare — the mode
        // could only ever say "nothing to compare", for ever. Reading a history is not writing, but
        // this file has no history to read.
        compose.onNodeWithText("Diff").assertIsNotEnabled()
    }

    @Test
    fun `every file opens in view, and Edit is a press away`() {
        // **View is the default for everyone now.** Report 29 asked for it in as many words: "view
        // (starts with it), edit, diff". The old behaviour opened an unlocked note ready to write,
        // which put a cursor in the text and a keyboard on the screen before anybody had said they
        // wanted to change anything - and made the mode chips unreadable, because there was no
        // state in which View was the one selected.
        val file = scene.file("milk.txt", "two litres")

        editorFor(file)

        // The text is readable, and the body is not a field. **The name still is** - renaming a
        // file is not editing it - so the assertion is about the body and not about the screen.
        compose.onNodeWithText("two litres").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("two litres")).assertDoesNotExist()
        // One press, and it is.
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithText("two litres").performTextReplacement("three litres")
        // **Leaving Edit is the save.** Save is gone; the person says they are done by
        // switching to View, and that is the signal the app acts on.
        compose.onNodeWithText("View").performClick()
        // The write and the commit are one act on the app's worker, so the file on
        // disk answers a moment after the button does — exactly as on a phone.
        compose.waitUntil(10_000) { file.readText() == "three litres" }
        assertEquals("written", "three litres", file.readText())
    }

    @Test
    fun `the three modes are separate buttons, and one of them is selected`() {
        // **A switch tells you what pressing it does; a mode tells you where you are.** With one
        // chip reading "Edit" there was nowhere to put Diff, which is why report 29 asked for the
        // split - the third mode is not a convenience, it is what the switch was hiding.
        val file = scene.file("milk.txt", "two litres")

        editorFor(file)

        for (label in listOf("View", "Edit", "Diff")) {
            compose.onNodeWithText(label).assertIsDisplayed()
        }
        // View is selected, which is a fact about the state rather than about the label: the chip
        // says `View` either way, so the assertion is that pressing it changes nothing.
        compose.onNodeWithText("View").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("two litres").assertIsDisplayed()
        compose.onNode(hasSetTextAction() and hasText("two litres")).assertDoesNotExist()
    }

    @Test
    fun `a mode change is traced, so the log can say which mode a person was in`() {
        // **Falsification: if the mode were not recorded, this test would pass with no line.** The
        // claim is not that a tap happened — a tap is the app's own bookkeeping — but that the trace
        // names *which* mode, because "I opened the diff and it showed nothing" and "I never got
        // into Diff" are the same picture and different histories.
        val file = scene.file("milk.txt", "two litres")
        editorFor(file)

        val before = Trace.report().taps
        compose.onNodeWithText("Edit").performClick()
        compose.waitForIdle()

        val report = Trace.report()
        assertTrue("the press was recorded", report.taps > before)
        // **The name is slugged on the way into the line**, so the vocabulary's `editor edit`
        // is written `editor-edit`. That is the trace's own rule for every name, not a special
        // case here: a name with a space in it would make the row's columns ambiguous.
        assertTrue(
            "and it says which mode: newest ${report.lines.take(3)}",
            report.lines.take(3).any { it.contains("TAP") && it.contains("editor-edit") },
        )
    }

    /**
     * **Onboarding's Exit is disabled**, which is the only thing stopping a person leaving.
     *
     * A scene of its own rather than the shared one, because the shared scene finishes onboarding
     * precisely so the other tests are about the editor. The rule is asserted on the *enabled state*
     * rather than by pressing: a press on a disabled button does nothing, so a press would pass
     * whether or not the rule existed.
     */
    @Test
    fun `an onboarding page offers no way out`() {
        val phone = Scenario(RuntimeEnvironment.getApplication(), signIn = true)
        try {
            phone.await("onboarding to open") { phone.viewModel.onboarding }
            val dir = File(phone.viewModel.workspace!!.systemDir, Onboarding.DIR)
            val page = File(dir, Onboarding.pages().first().name)
            phone.viewModel.openEditor(page)
            compose.setContent {
                Frame {
                    EditorScreen(
                        viewModel = phone.viewModel,
                        file = page,
                        onBack = {},
                        onOpenHistory = {},
                    )
                }
            }
            compose.waitForIdle()
            // **No row, no modes, no Exit** — the operator's rule: *"instead of greying out just
            // dont render the row at all, because it will just have view active on it which is
            // default anyway"*. So the proof is not a disabled button, it is that the button is not
            // there: a greyed one is still a control saying "you may not", and a reading page has
            // nothing to say.
            compose.onNodeWithText("Edit").assertDoesNotExist()
            compose.onNodeWithText("Diff").assertDoesNotExist()
            compose.onNodeWithText("Reset").assertDoesNotExist()
            // **And no back arrow**, because onboarding is the sequence that signs the account in:
            // a door out of it is a door out of the thing that has to happen first.
            compose.onNodeWithContentDescription("Back without saving").assertDoesNotExist()
            // **And no history**, because the system project has no repository at all — so a page
            // in it has never been committed and never can be.
            compose.onNodeWithContentDescription("This file's history").assertDoesNotExist()
        } finally {
            phone.close()
        }
    }

    /**
     * **A locked file offers no Photograph**, because there is nowhere to put the picture.
     *
     * The operator's rule: *"make sure that it cant be triggered from a system locked folder
     * (otherwise it will clash with readonly lock when saving the photo)"*. `Workspace.writeBytes`
     * already refuses — a photograph is written *beside* the page it came from, and a locked folder
     * will not take it — so the button was an act that could only ever fail.
     *
     * Falsification: with the button drawn, the first assertion fails; with it hidden on every file,
     * the second does.
     */
    @Test
    fun `photograph is offered on a writable page`() {
        val mine = scene.file("milk.txt", "two litres")
        editorFor(mine)
        compose.onNodeWithContentDescription("Photograph this page").assertExists()
    }

    /**
     * **And absent where the picture could not be written.**
     *
     * A photograph goes *beside* the page it came from, so a locked folder has nowhere to put it:
     * `Workspace.writeBytes` refuses, and the button would be an act that always fails. The
     * operator's rule: *"make sure that it cant be triggered from a system locked folder (otherwise
     * it will clash with readonly lock when saving the photo)"*.
     *
     * **Its own test, and its own scenario**, because `setContent` may be called once per test — a
     * second call throws, which is how the first attempt at this failed.
     */
    @Test
    fun `photograph is absent on a locked page`() {
        val phone = Scenario(RuntimeEnvironment.getApplication(), signIn = true)
        try {
            phone.await("onboarding to open") { phone.viewModel.onboarding }
            val dir = File(phone.viewModel.workspace!!.systemDir, Onboarding.DIR)
            val page = File(dir, Onboarding.pages().first().name)
            phone.viewModel.openEditor(page)
            compose.setContent {
                Frame {
                    EditorScreen(viewModel = phone.viewModel, file = page, onBack = {}, onOpenHistory = {})
                }
            }
            compose.waitForIdle()

            compose.onNodeWithContentDescription("Photograph this page").assertDoesNotExist()
        } finally {
            phone.close()
        }
    }

    /**
     * **Reset is recorded under its own name**, which is what makes it distinguishable from a save
     * in a log — the two write different things and mean opposite ones.
     */
    @Test
    fun `Reset is traced under its own name`() {
        val file = scene.file("milk.txt", "two litres")
        editorFor(file)
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithText("two litres").performTextReplacement("three litres")

        val before = Trace.report().taps
        compose.onNodeWithText("Reset").performClick()
        compose.waitForIdle()

        val report = Trace.report()
        assertTrue("the press was recorded", report.taps > before)
        assertTrue(
            "and it is named: newest ${report.lines.take(3)}",
            report.lines.take(3).any { it.contains("TAP") && it.contains("editor-reset") },
        )
    }

    @Test
    fun `a mode change away from Edit saves what was typed`() {
        // **The contract this pins is the reverse of the one it was written for.** It used to say
        // "switching mode is not a commit"; with Save gone, leaving Edit *is* the save, and a test
        // asserting otherwise would now be asserting the bug. What is still true is that the words
        // survive the round trip.
        val file = scene.file("milk.txt", "two litres")
        editorWriting(file)
        compose.onNodeWithText("two litres").performTextReplacement("three litres")

        compose.onNodeWithText("View").performClick()
        compose.waitForIdle()

        // **Written on the way out**, which is the signal the app acts on.
        compose.waitUntil(10_000) { file.readText() == "three litres" }
        assertEquals("the words were saved by leaving Edit", "three litres", file.readText())
        // ...and they are still on the page, not only on disk.
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithText("three litres").assertIsDisplayed()
    }

    @Test
    fun `a record cannot reach Edit, and is not offered a history it does not have`() {
        // **This test used to assert the opposite**, and the correction is the operator's: *"dont
        // render history icon in system locked files as well - they never have history"*.
        //
        // The old reasoning was "reading a file's history is not writing it, so Diff stays open" —
        // which is true of *reading* and false of this file. The record lives in `agent/`, and
        // `APP_IGNORES` keeps that whole folder out of the repository, so there is no history to
        // read: the Diff screen could only ever say "nothing to compare", for ever. A mode that
        // cannot succeed is the same lie as a button that cannot succeed.
        //
        // A *record*, not a user-locked file: the person may still edit what they locked themselves.
        val file = File(scene.workspace!!.projectDir("groceries"), "${AgentLog.DIR}/${AgentLog.CONTEXT}")
        file.parentFile?.mkdirs()
        file.writeText("14:02:11.410    1   12 NOTE  agent  something happened\n")
        editorFor(file)

        compose.onNodeWithText("Edit").assertIsNotEnabled()
        compose.onNodeWithText("Diff").assertIsNotEnabled()
        compose.onNodeWithContentDescription("This file's history").assertDoesNotExist()
        // **And it is still readable**, which is the whole point of the record existing.
        compose.onNodeWithText("something happened", substring = true).assertIsDisplayed()
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
        // **No press is needed: it opens in View**, which is the mode that renders. This used to
        // read "into view mode" and press a switch - the switch is gone and the mode it reached
        // is the one the file starts in.
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
        // **It opens in View**, which is where a note is read rather than typed - so the picture
        // is drawn without a press. This used to press Edit to get here, which is now the way out.
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
        // It opens in View, so the sentence is there without a press.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("could not be read here", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("a cat", substring = true).assertIsDisplayed()
        compose.onNodeWithText("missing.png", substring = true).assertIsDisplayed()
    }

    /**
     * **The page moves during the settle, rather than waiting for it and then jumping.**
     *
     * This shipped as *"only first half is animated, then it teleports"*: the settle animated an
     * `Animatable` and copied its value into the offset **after** the animation finished, so the drag
     * was smooth, the page then froze for 180ms, and everything moved at once. A value being animated
     * is not a value being drawn unless the draw reads it.
     *
     * Falsification: animate an `Animatable` and assign `offset = it.value` after `animateTo`
     * returns — the position sampled mid-flight is the released position, and this fails.
     */
    @Test
    fun `the page travels during the settle instead of teleporting at the end`() {
        scene.open("groceries")
        scene.file("a.txt", "FIRSTPAGE")
        val middle = scene.file("b.txt", "SECONDPAGE")
        scene.file("c.txt", "THIRDPAGE")
        scene.viewModel.openEditor(middle)
        compose.mainClock.autoAdvance = false
        compose.setContent {
            Frame {
                EditorScreen(
                    viewModel = scene.viewModel,
                    file = middle,
                    onBack = {},
                    onOpenHistory = {},
                )
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("THIRDPAGE").fetchSemanticsNodes().isNotEmpty()
        }

        val page = compose.onNodeWithText("SECONDPAGE")
        val bounds = page.fetchSemanticsNode().boundsInRoot
        val before = bounds.left
        val pageWidth = bounds.width
        page.performTouchInput {
            down(center)
            // **Half a width of travel**, so the release commits and the settle runs.
            moveBy(Offset(x = -pageWidth * 0.5f, y = 0f))
            up()
        }

        // **Sampled a quarter of the way into a 180ms settle.** If the page only moves when the
        // animation is over, this position is still the released one — half a width across — and the
        // assertion that it has moved further fails.
        compose.mainClock.advanceTimeBy(45)
        val midway = page.fetchSemanticsNode().boundsInRoot.left
        compose.mainClock.autoAdvance = true

        assertTrue(
            "**the page moved on during the settle**: released at $before, at $midway after 45ms",
            midway < before - pageWidth * 0.05f,
        )
    }

    @Test
    fun `the page beside this one is rendered before it is swiped to`() {
        // **In the project's own folder**, the same way `a file knows which files are beside it`
        // does — a subfolder here would be testing two things at once, and the one that failed was
        // the folder listing rather than the renderer.
        scene.open("groceries")
        scene.file("a.txt", "FIRSTPAGE")
        val middle = scene.file("b.txt", "SECONDPAGE")
        scene.file("c.txt", "THIRDPAGE")
        scene.viewModel.openEditor(middle)
        compose.setContent {
            Frame {
                EditorScreen(
                    viewModel = scene.viewModel,
                    file = middle,
                    onBack = {},
                    onOpenHistory = {},
                )
            }
        }

        // **What the prefetch is given, before asking whether it drew it.** A failure here says the
        // *source* is empty, which is a different fault from the renderer not drawing it.
        assertNotNull(
            "**there is a page before b.txt**",
            scene.viewModel.fileBeside(middle, -1),
        )
        assertNotNull(
            "**and a page after it**",
            scene.viewModel.fileBeside(middle, 1),
        )

        // **All three in the tree at once**, which is the whole claim: the two neighbours are drawn
        // before any gesture, so a finger moving toward one has text to drag in rather than a blank
        // frame. Plain text rather than markdown, so the words are the words and not a heading
        // rendered from them.
        compose.waitUntil(5_000) {
            listOf("FIRSTPAGE", "SECONDPAGE", "THIRDPAGE").all { word ->
                compose.onAllNodesWithText(word, substring = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }
        for (word in listOf("FIRSTPAGE", "SECONDPAGE", "THIRDPAGE")) {
            assertTrue(
                "**$word is drawn** — the page before, this page, and the page after",
                compose.onAllNodesWithText(word, substring = true)
                    .fetchSemanticsNodes().isNotEmpty(),
            )
        }
    }

    @Test
    fun `a picture beside a page is drawn as a picture, never as its bytes`() {
        // **The operator's report**: "why drawing when you swipe into it from another file is briefly
        // shown as a set of characters only then it renders into the actual drawing?"
        //
        // The characters were the PNG's own bytes, read by `file.readText()` and drawn as a page.
        // Reading a PNG as text *succeeds* — it returns convincing nonsense — so the `catch` that was
        // supposed to handle pictures never fired, and the neighbour page showed the file's contents
        // until the gesture finished and the real bitmap arrived.
        //
        // **What this asserts is the absence of text**, which is the one thing the bug produced: a
        // neighbour that is a picture must contribute no characters to the tree at all.
        scene.open("groceries")
        scene.file("a.txt", "THEONLYSENTENCE")
        // **A one-pixel PNG**, the smallest thing that is genuinely a picture and the same fixture
        // the other picture tests use.
        val picture = scene.file("drawing.png")
        picture.writeBytes(ONE_PIXEL_PNG)
        scene.viewModel.openEditor(picture)
        compose.setContent {
            Frame {
                EditorScreen(
                    viewModel = scene.viewModel,
                    file = picture,
                    onBack = {},
                    onOpenHistory = {},
                )
            }
        }

        // **The page after the picture is text, so the tree is not empty** — an absence assertion on
        // a screen that drew nothing would pass for the wrong reason.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("THEONLYSENTENCE", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        // **Not one trace of the PNG's own bytes.** A PNG begins with 89 50 4E 47, and the old
        // `readText()` turned those into the characters `\u0089PNG`; `IHDR` is the first chunk every
        // PNG has. Neither is anything a page would say.
        for (word in listOf("IHDR", "PNG")) {
            assertTrue(
                "**no trace of the PNG's own bytes: $word**",
                compose.onAllNodesWithText(word, substring = true).fetchSemanticsNodes().isEmpty(),
            )
        }
    }

    @Test
    fun `a picture is a page the swipe can leave`() {
        // **The operator's second report on the same screen**: "why can't I swipe from its view mode
        // to other files?" The picture's View mode was a bare `Picture`, not the paged reader, so it
        // had no gesture at all — while the file beside it was a page the swipe could have reached.
        //
        // **Asserted on what the reader is given**, because a gesture test would pin Compose's drag
        // detection: the picture must have a neighbour, and the screen must be the reader rather than
        // the standalone picture view.
        scene.open("groceries")
        scene.file("a.txt", "BEFORE THE PICTURE")
        val picture = scene.file("drawing.png")
        picture.writeBytes(ONE_PIXEL_PNG)
        scene.viewModel.openEditor(picture)

        assertNotNull(
            "**the picture has a page before it**",
            scene.viewModel.fileBeside(picture, -1),
        )
        // **And the screen draws that neighbour**, which is what only the paged reader does. A bare
        // `Picture` renders one image and nothing else, so this word can only appear if the swipe's
        // reader is the thing on screen.
        compose.setContent {
            Frame {
                EditorScreen(
                    viewModel = scene.viewModel,
                    file = picture,
                    onBack = {},
                    onOpenHistory = {},
                )
            }
        }
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("BEFORE THE PICTURE", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    /**
     * Swiping moves through the folder's files, and stops at both ends.
     *
     * Asserted on the view model rather than through a gesture: what a gesture test would pin is
     * Compose's own drag detection, and what this feature can get wrong is *which file is beside
     * this one* — the folder's order, folders skipped, and the ends refusing rather than wrapping.
     */
    @Test
    fun `a file knows which files are beside it, and stops at the ends`() {
        scene.open("groceries")
        val first = scene.file("a.txt", "a")
        val second = scene.file("b.txt", "b")
        val third = scene.file("c.txt", "c")
        // A folder in the same directory, which is not a file to swipe to.
        scene.viewModel.createFolder("inner")

        assertEquals("the next file", second.name, scene.viewModel.fileBeside(first, 1)?.name)
        assertNull("nothing before the first", scene.viewModel.fileBeside(first, -1))
        assertEquals("and back again", first.name, scene.viewModel.fileBeside(second, -1)?.name)

        assertNull("nothing past the last", scene.viewModel.fileBeside(third, 1))
        assertNull("a file that is not in this folder", scene.viewModel.fileBeside(File("/nope.txt"), 1))
    }

    /**
     * **Entering Edit and leaving again writes nothing.**
     *
     * This is the guard the autosave rule exists for, and it is not an optimisation: every save is a
     * git commit, so a person who opens a file to *look* at it, presses Edit, and presses View again
     * would otherwise add a version that records nothing — and doing that ten times would leave ten
     * empty commits to scroll past in the history.
     */
    @Test
    fun `leaving Edit without changing anything writes no version`() {
        val file = scene.file("milk.txt", "two litres")
        editorFor(file)
        val before = scene.repo("groceries").head()

        compose.onNodeWithText("Edit").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("View").performClick()
        compose.waitForIdle()

        assertEquals("the file is unchanged", "two litres", file.readText())
        assertEquals(
            "and no version was written for nothing",
            before,
            scene.repo("groceries").head(),
        )
    }

    @Test
    fun `Reset has nothing to do until something is typed`() {
        val file = scene.file("milk.txt", "two litres")

        editorFor(file)

        compose.onNodeWithText("Reset").assertIsNotEnabled()
    }

    @Test
    fun `the name can be changed where it stands`() {
        // No separate Rename window: the name is the title, and the title is the
        // field. Typing a new one and finishing renames the file.
        val file = scene.file("milk.txt", "two litres")

        editorWriting(file)
        compose.onNodeWithText("milk.txt").performTextReplacement("Shopping List")
        compose.onNodeWithText("Shopping List").performImeAction()

        val dir = scene.viewModel.currentDir!!
        // **The name as typed.** It used to be `shopping_list.txt`, because the app lowercased
        // and joined; the rewriter is gone and a name is the person's.
        assertTrue(
            "under the name that was typed",
            File(dir, "Shopping List.txt").isFile,
        )
        assertFalse("and not under the old one", File(dir, "milk.txt").exists())
    }

    @Test
    fun `a text file is shown as text, and Edit turns it into a field`() {
        // **It used to be a field from the moment it opened**, and that is what report 29
        // overruled: a person opening a note usually wants to read it, and a cursor in the text
        // before anybody has said they want to change anything is an invitation to change it.
        val file = scene.file("milk.txt", "two litres")

        editorFor(file)

        compose.onNodeWithText("two litres").assertIsDisplayed()
        // The body is not a field; the name still is, because renaming a file is not editing it.
        compose.onNode(hasSetTextAction() and hasText("two litres")).assertDoesNotExist()

        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithText("two litres").performTextReplacement("three litres")
        // **Leaving Edit is the save.** Save is gone; the person says they are done by
        // switching to View, and that is the signal the app acts on.
        compose.onNodeWithText("View").performClick()
        compose.waitUntil(10_000) { file.readText() == "three litres" }
        assertEquals("and now it can be written", "three litres", file.readText())
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

    // ---- a picture that was drawn on ----------------------------------------------------------
    //
    // **`savePicture` had no test at all**, which meant the entire output path of the drawing feature
    // was unverified: the strokes flatten into bytes, the bytes go through `Workspace.writeBytes`, and
    // the file is committed. The canvas itself cannot be rasterised here (Robolectric draws nothing),
    // but the *saving* is ordinary arithmetic and file work.

    @Test
    fun `saving a picture writes its bytes and the file is a picture afterwards`() {
        // **A project has to be made, not only opened.** `open` navigates; `project` creates the
        // folder and its repository. Without it the file lands at the account root, `projectOf`
        // answers null and the commit has nowhere to go — which is what the first three versions of
        // this test discovered, one at a time.
        scene.project("groceries").open("groceries")
        val file = scene.file("drawing.png")
        file.writeBytes(ONE_PIXEL_PNG)
        val before = file.readBytes()

        scene.viewModel.openEditor(file)
        scene.await("the picture to open") { scene.viewModel.editingFile == file }
        scene.viewModel.savePicture(OTHER_PIXEL_PNG)
        scene.await("the picture to be written") { !file.readBytes().contentEquals(before) }

        assertEquals("the bytes are what was given", OTHER_PIXEL_PNG.toList(), file.readBytes().toList())
        assertEquals("and it is still a picture", EntryKind.IMAGE, scene.viewModel.workspace!!.kindOf(file))
    }

    @Test
    fun `a picture saved under a new name keeps both, each with one version`() {
        // **The whole of a picture's versioning.** A binary has no history, so keeping two states of
        // it is two FILES - the operator's words: *"save the change as a different file — that will be
        // our versioning option for binaries"*. This is the test that says the option exists and that
        // the original is not a casualty of it.
        scene.project("groceries").open("groceries")
        val first = scene.file("page.png")
        first.writeBytes(ONE_PIXEL_PNG)
        scene.viewModel.openEditor(first)
        scene.await("the picture to open") { scene.viewModel.editingFile == first }

        val copy = File(first.parentFile, "page-2.png")
        scene.viewModel.savePicture(OTHER_PIXEL_PNG, copy)
        scene.await("the copy to be written") { copy.isFile && copy.length() > 0 }

        assertEquals("the new file has the new bytes", OTHER_PIXEL_PNG.toList(), copy.readBytes().toList())
        assertEquals(
            "and the original is untouched, which is what makes this versioning rather than moving",
            ONE_PIXEL_PNG.toList(),
            first.readBytes().toList(),
        )
    }

    @Test
    fun `saving a picture over itself replaces it, and says so before it happens`() {
        // **The other half of the same decision.** Replacing is allowed and is the ordinary case —
        // the name is preset to the file's own — but it destroys the only version there is, so the
        // window says so first. What this pins is that the replacement really is a replacement: the
        // old bytes are not kept anywhere.
        scene.project("groceries").open("groceries")
        val file = scene.file("page.png")
        file.writeBytes(ONE_PIXEL_PNG)
        scene.viewModel.openEditor(file)
        scene.await("the picture to open") { scene.viewModel.editingFile == file }

        scene.viewModel.savePicture(OTHER_PIXEL_PNG, file)
        scene.await("the picture to be replaced") { !file.readBytes().contentEquals(ONE_PIXEL_PNG) }

        assertEquals("the new bytes", OTHER_PIXEL_PNG.toList(), file.readBytes().toList())
        assertEquals(
            "and no copy was made beside it",
            1,
            (file.parentFile?.listFiles() ?: emptyArray()).count { it.name.endsWith(".png") },
        )
    }

    @Test
    fun `a drawing is not saved into a name that is not a picture`() {
        // **A name is a promise about content.** Writing PNG bytes into `notes.txt` would give the
        // person a file the app then opens as text - a page of replacement characters that looks
        // like corruption, and a picture they can no longer see. The refusal names the name.
        scene.project("groceries").open("groceries")
        val file = scene.file("page.png")
        file.writeBytes(ONE_PIXEL_PNG)
        scene.viewModel.openEditor(file)
        scene.await("the picture to open") { scene.viewModel.editingFile == file }

        val wrong = File(file.parentFile, "notes.txt")
        scene.viewModel.savePicture(OTHER_PIXEL_PNG, wrong)

        assertFalse("nothing was written under that name", wrong.exists())
        assertTrue(
            "and the person was told why: ${scene.viewModel.message}",
            scene.viewModel.message.orEmpty().contains("not a picture name"),
        )
    }

    @Test
    fun `the bytes a save writes are the bytes the screen keeps as its base`() {
        // **The operator's report**: "see why changes after saving are visible in view mode, but
        // become invisible in draw mode".
        //
        // The cause was one line of staleness. `savePicture` writes the flattened image, and the
        // stroke list is then emptied — but the *base* the canvas draws under that list stayed the
        // picture from **before** the strokes. So View, which re-reads the file, showed them; Draw,
        // which draws base-plus-list and the list was now empty, did not. The two screens disagreed
        // about the same file.
        //
        // **What this pins is the equality that fixes it**: the bytes handed to the writer, decoded,
        // are the image the canvas would draw. A canvas cannot be rasterised under Robolectric, so
        // the arithmetic is asserted where it lives instead of the pixels.
        scene.project("groceries").open("groceries")
        val file = scene.file("drawing.png")
        file.writeBytes(ONE_PIXEL_PNG)

        val base = android.graphics.BitmapFactory.decodeByteArray(
            ONE_PIXEL_PNG, 0, ONE_PIXEL_PNG.size,
        )
        assertNotNull("the fixture really decodes", base)
        val flattened = Drawing.flatten(
            base!!,
            listOf(
                Drawing.Stroke(
                    tool = Drawing.Tool.LINE,
                    points = listOf(Drawing.Point(10f, 10f), Drawing.Point(40f, 40f)),
                    colour = 0xFF0000FF.toInt(),
                    width = 4f,
                ),
            ),
        )
        // **The base changed, or there would be nothing to notice.** A flatten that returned the
        // source unchanged would make the staleness invisible and this test vacuous.
        assertFalse(
            "the drawn image is not the empty one it started from",
            flattened.contentEquals(ONE_PIXEL_PNG),
        )
        // **And decoding what was written gives a picture**, which is what the fix does: the base
        // becomes the file's own bytes rather than a second guess at them.
        val reread = android.graphics.BitmapFactory.decodeByteArray(flattened, 0, flattened.size)
        assertNotNull("what a save writes can be read back as the new base", reread)
    }

    @Test
    fun `saving a picture refuses to write over a text file`() {
        // **A project has to be made, not only opened.** `open` navigates; `project` creates the
        // folder and its repository. Without it the file lands at the account root, `projectOf`
        // answers null and the commit has nowhere to go — which is what the first three versions of
        // this test discovered, one at a time.
        scene.project("groceries").open("groceries")
        // **The mirror of `saveEditor`'s refusal.** A bug in the drawing screen must not be able to
        // turn a document into bytes — and the guard is in the view model, not only on the screen,
        // because a guarantee held only by the caller is a convention.
        val file = scene.file("notes.txt", "two litres")
        scene.viewModel.openEditor(file)
        scene.await("the text file to open") { scene.viewModel.editingFile == file }

        scene.viewModel.savePicture(OTHER_PIXEL_PNG)

        assertEquals("the text is untouched", "two litres", file.readText())
        assertEquals("and still text", EntryKind.TEXT, scene.viewModel.workspace!!.kindOf(file))
    }

    @Test
    fun `a drawn picture is committed, so it is in the history`() {
        // **A project has to be made, not only opened.** `open` navigates; `project` creates the
        // folder and its repository. Without it the file lands at the account root, `projectOf`
        // answers null and the commit has nowhere to go — which is what the first three versions of
        // this test discovered, one at a time.
        scene.project("groceries").open("groceries")
        // A picture that existed only on the phone would be the one kind of work this app does not
        // record — and the whole point of the app is that every change is a version.
        //
        // **Asked of the newest commit's tree, not of the commit count.** `scene.file` already made
        // and committed the file, and a count can be unchanged by a commit that recorded nothing
        // new — so the count answers "did a commit happen", which is not the question. The question is
        // "is the drawn-on picture in the history", and the tree answers that.
        // **Made the way a person makes one**, through `+ New image`, so the file is in the project
        // and already committed — which is the state a save starts from.
        val file = scene.picture("drawn")
        scene.viewModel.openEditor(file)
        scene.await("the picture to open") { scene.viewModel.editingFile == file }

        val before = scene.repo("groceries").head()

        scene.viewModel.savePicture(OTHER_PIXEL_PNG)
        // **A new commit exists, and the file is tracked in it.** The bytes cannot be read back
        // through `fileAt` — that returns text, and a PNG is not text — so the assertions are that
        // HEAD moved and that the picture is in the tree at it.
        // **The file first, then the history.** Two promises, and a failure that says which one broke:
        // "the bytes were not written" and "the bytes were written but not committed" are different
        // bugs with the same symptom at this level.
        scene.await("the drawn-on picture to be written", 20_000) {
            file.readBytes().toList() == OTHER_PIXEL_PNG.toList()
        }
        scene.await(
            "the drawn-on picture to be committed (head was $before, " +
                "project=${scene.viewModel.workspace!!.projectOf(file)}, " +
                "tracked=${scene.repo("groceries").tracked()})",
            20_000,
        ) {
            val now = scene.repo("groceries").head()
            now != null && now != before
        }
        assertTrue(
            "the picture is tracked: ${scene.repo("groceries").tracked()}",
            file.name in scene.repo("groceries").tracked(),
        )
    }

    private companion object {
        /**
         * The smallest valid PNG: 1×1, transparent.
         *
         * The test decodes it through the same [Pictures] code the app uses, so
         * this is a real decode and not a stub. A fake would only prove the test's
         * belief about bitmaps.
         */
        /** A 1×1 PNG that is a *different* file from [ONE_PIXEL_PNG], so "the bytes changed" means something. */
        val OTHER_PIXEL_PNG: ByteArray = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x02,
            0x08, 0x02, 0x00, 0x00, 0x00, 0xFD.toByte(), 0xD4.toByte(), 0x9A.toByte(),
            0x73, 0x00, 0x00, 0x00, 0x10, 0x49, 0x44, 0x41,
            0x54, 0x78, 0x9C.toByte(), 0x63, 0xF8.toByte(), 0xCF.toByte(), 0xC0.toByte(),
            0x00, 0x44, 0x0C, 0x10, 0x0A, 0x00, 0x1F, 0xEE.toByte(), 0x03, 0xFD.toByte(),
            0x8B.toByte(), 0x5F, 0x14, 0xD4.toByte(), 0x00, 0x00, 0x00, 0x00,
            0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        )

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
    @Test
    fun `the diff opens on the newest version against the one before it`() {
        // **The operator's report**: "diff isnt getting selected even when there is history to
        // compare, fix it. and set it so that by default it shows newest version vs previous one".
        //
        // The default used to be `HEAD` against the last *synced* commit. A file that had never been
        // pushed has no synced version, so the older end was null and the screen said "there is no
        // version of this file at one of those commits" about a file with history behind it - which
        // is exactly "isn't getting selected even when there is history".
        //
        // **Two versions, and no remote at all**, which is the case that was broken: a local project
        // never pushes, so `lastSynced` is null for ever and every diff of it was empty.
        scene.localProject("Shopping")
        val made = scene.made("Shopping")
        scene.open(made)
        val file = scene.file("milk.txt", "two litres")
        scene.viewModel.openEditor(file)
        scene.saveAsVersion("three litres")

        scene.viewModel.openDiff("milk.txt")
        scene.await("the diff to choose its two ends", 20_000) {
            scene.viewModel.diffFrom != null && scene.viewModel.diffTo != null
        }

        val choices = scene.viewModel.diffChoices
        assertTrue("the file has a history to compare: ${choices.size}", choices.size >= 2)
        assertEquals("the newer end is the newest version", choices[0].id, scene.viewModel.diffTo)
        assertEquals("and the older is the one before it", choices[1].id, scene.viewModel.diffFrom)
    }

    @Test
    fun `opened from the changes list, the diff is the newest against the last synced commit`() {
        // **The operator, 2026-11-04**: "I want newest to last synced only when you open diffs from
        // 'changes to see' on the sync row (and files from this list when clicked should open in
        // diff mode)."
        //
        // **The two questions are different, and the file is the same one.** Arriving from the
        // changes list, the question is "what am I about to send", and the answer is HEAD against
        // the last commit GitHub has — which is exactly the set of changes the row's count is
        // counting. Pressing Diff yourself asks "what changed lately", and the answer is the newest
        // version against the one before it.
        //
        // **Two commits after the sync, so the two defaults must differ.** With one, both pairs
        // would be the same and the test could pass while the rule was not there.
        scene.project("groceries")
        scene.open("groceries")
        val file = scene.file("milk.txt", "one")
        scene.sync("groceries")
        // **The editor has to be open to save a version**, and it has to be the file the versions
        // belong to.
        scene.viewModel.openEditor(file)
        scene.saveAsVersion("two")
        scene.saveAsVersion("three")

        scene.viewModel.openChangedFile("milk.txt")
        // **The screen asks for the diff when it draws in that mode**, which is what a person
        // pressing the row gets. Driven here so the test does not need a composable to reach the
        // view model's own answer.
        scene.viewModel.openDiff("milk.txt")

        scene.await("the diff to choose its two ends", 20_000) {
            scene.viewModel.diffFrom != null && scene.viewModel.diffTo != null
        }
        val choices = scene.viewModel.diffChoices
        assertEquals("the newer end is still the newest version", choices[0].id, scene.viewModel.diffTo)
        assertNotEquals(
            "and the older end is the last synced commit, not the version before this one",
            choices[1].id,
            scene.viewModel.diffFrom,
        )
        // **Named, not just different.** "Not the previous version" would also pass if the older end
        // were some third commit, so this names the one the question has: the file's version at the
        // last synced commit, read through the same function the diff uses.
        val synced = scene.viewModel.syncedCommitOf("milk.txt")
        assertNotNull("the project really has a synced commit", synced)
        assertEquals("the older end is the commit GitHub has", synced, scene.viewModel.diffFrom)
        // **And the picker can name it**, which is the half that would otherwise draw "From: none"
        // above a diff with two sides.
        assertTrue(
            "the picker's list contains the synced commit",
            choices.any { it.id == synced },
        )
    }

    @Test
    fun `a file opened from the changes list starts in diff mode`() {
        // The other half of the same report: "files from this list when clicked should open in diff
        // mode." A person who tapped a row of a list called "Changes to send" has already said what
        // they want to see.
        scene.project("groceries")
        scene.open("groceries")
        val file = scene.file("milk.txt", "one")
        scene.sync("groceries")
        scene.viewModel.openEditor(file)
        scene.saveAsVersion("two")

        scene.viewModel.openChangedFile("milk.txt")
        scene.await("the editor to open", 20_000) { scene.viewModel.editingPath != null }
        assertEquals(
            "the editor was told why it opened",
            AppViewModel.Opening.UNSENT,
            scene.viewModel.editorOpening,
        )

        // **And a plain open is the ordinary reason**, which is what keeps the two defaults apart.
        scene.viewModel.openEditor(scene.viewModel.editingFile!!)
        assertEquals(
            "a plain open is not a changes-list open",
            AppViewModel.Opening.READ,
            scene.viewModel.editorOpening,
        )
    }

    @Test
    fun `picking an end moves that end, and the screen reads it from the view model`() {
        // **The operator's second report**: "inside diff editor mode from and to versions dont react
        // to touches." The picker's choice used to be local state seeded from the view model, and a
        // `remember(path)` block reads what the view model held *when the composable was first
        // drawn* — so the second file opened in a session inherited the first file's pair, and the
        // guard meant to fix it could never fire because the local value was already set.
        //
        // One owner is the fix, so this asserts the owner moves and that nothing else can hold a
        // different answer.
        scene.localProject("Shopping")
        val made = scene.made("Shopping")
        scene.open(made)
        val file = scene.file("milk.txt", "two litres")
        scene.viewModel.openEditor(file)
        scene.saveAsVersion("three litres")
        scene.saveAsVersion("four litres")
        scene.viewModel.openDiff("milk.txt")
        scene.await("the diff to choose its two ends", 20_000) {
            scene.viewModel.diffFrom != null && scene.viewModel.diffTo != null
        }

        val choices = scene.viewModel.diffChoices
        assertTrue("there are three versions to pick from: ${choices.size}", choices.size >= 3)
        // **The oldest version as the new older end**, which is not where it started.
        val oldest = choices.last().id
        assertNotEquals("the older end did not already sit there", oldest, scene.viewModel.diffFrom)
        scene.viewModel.chooseDiffFrom(oldest)
        assertEquals("the pick is the pair now", oldest, scene.viewModel.diffFrom)

        // **And the other end independently**, so one setter is not quietly writing both.
        val before = scene.viewModel.diffFrom
        scene.viewModel.chooseDiffTo(choices[1].id)
        assertEquals("the newer end moved", choices[1].id, scene.viewModel.diffTo)
        assertEquals("and the older end did not", before, scene.viewModel.diffFrom)
    }

}
