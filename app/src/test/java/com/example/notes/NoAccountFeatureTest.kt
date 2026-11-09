package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §3.11 N2 — **a phone with no account, and what every door does there.**
 *
 * Until §3.11 this state could not be reached: a provisional account always existed, so the answer to
 * "what does this button do when there is no account" was never asked. Now it is the state a phone
 * starts in, so every act has to answer it — and the two halves of the answer are different:
 *
 * - **An act says what happened.** Nothing was created, moved or deleted, and the sentence says so.
 *   A silent `return` here is a button that does nothing, which is indistinguishable from a broken app.
 * - **A read stays quiet.** These run while a screen is being drawn. A message set in composition
 *   redraws the screen, which sets it again — so a read that speaks is not a chatty screen, it is a
 *   loop. The test asserts the stronger property: a read leaves a message that is already there alone.
 */
class NoAccountFeatureTest : FeatureTest() {

    /** This feature's whole subject is a phone with no account. */
    override val signIn: Boolean get() = false

    private val nowhere = File("/nonexistent/nowhere")
    private val name = "notes.md"

    private fun press(what: String, act: () -> Unit) {
        scene.viewModel.message = null
        act()
        // **Empty rather than null, so the assertions below are about the sentence.** A `said!!` would
        // compile and leave the next line calling `contains` on something the compiler still holds to be
        // nullable - which is how a message test fails as a type error instead of as a missing message.
        val said = scene.viewModel.message.orEmpty()
        assertTrue("$what said nothing at all", said.isNotEmpty())
        assertTrue("$what: $said", said.contains("No account is open"))
        assertTrue("$what does not say what happened: $said", said.contains("nothing was"))
    }

    @Test
    fun `every door that acts says nothing was done`() {
        val vm = scene.viewModel

        press("new file") { vm.createFile(name) }
        press("new folder") { vm.createFolder("drafts") }
        press("new picture") { vm.createImage("sketch", Look.Canvas("sketch", 1024, 1024)) }
        press("rename") { vm.renameTo(nowhere, "other.md") }
        press("move") { vm.move(nowhere, nowhere) }
        press("copy") { vm.copy(nowhere, nowhere) }
        press("delete") { vm.delete(nowhere) }
        press("lock") { vm.toggleLocked(nowhere) }
        press("ignore in git") { vm.ignoreInGit(nowhere) }
        press("pin") { vm.togglePinned(nowhere) }
        press("select") { vm.toggleSelected(nowhere) }
        press("move selected") { vm.moveSelected(nowhere) }
        press("copy selected") { vm.copySelected(nowhere) }
        press("delete selected") { vm.deleteSelected() }
        press("open a diff") { vm.openDiff("notes.md") }
        press("open a changed file") { vm.openChangedFile("notes.md") }
        press("photograph") { vm.photograph("some text") }
        press("save a picture") { vm.savePicture(ByteArray(0)) }
        press("move a project") { vm.moveProject("notes", "acme") }
        press("delete a project") { vm.deleteProject("notes") }
        press("tag a release") { vm.tagRelease("1.0") }
        press("remove a branch") { vm.dropBranch("feature") }
        press("restore a file") { vm.restoreDeleted(nowhere, Gone("notes.md", "abc1234")) }
        press("restore a version") { vm.restoreVersion("abc1234") }
        press("reset the agent") { vm.resetAgent("notes") }
    }

    @Test
    fun `every door that only reads leaves the message alone`() {
        // **The claim is not "nothing is shown" but "nothing is overwritten".** A read that spoke would
        // replace a sentence the person still needs — and on a redraw it would do it again, which is the
        // loop the rule exists to prevent. The message is set first so a silent read is visible as an
        // unchanged one rather than as an absent one.
        val vm = scene.viewModel
        val kept = "A sentence the person has not read yet."
        vm.message = kept

        vm.historyKind
        vm.canHoldFiles(nowhere)
        vm.host("notes", AgentSession.MAIN)
        vm.isSelected(nowhere)
        vm.textOf(nowhere)
        vm.fileBeside(nowhere, 1)
        vm.versions()
        vm.deletedIn(nowhere)
        vm.readChat()
        vm.measureChatContext()

        assertEquals("a read overwrote the message", kept, vm.message)
        assertNull("and there is still no workspace to read", vm.workspace)
    }
}
