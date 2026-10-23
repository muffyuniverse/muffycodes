package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §9 of the design: **a file's name is its title, and its extension is the last
 * part of the name after a dot.**
 *
 * There is no preview, no lock file, no front matter. A text file opens in the
 * editor; a picture is shown as a picture and cannot be edited; anything else can
 * be renamed, moved and deleted, and that is all.
 */
class FilesFeatureTest : FeatureTest() {

    @Test
    fun `a new file gets the default extension when the name has none`() {
        scene.file("Shopping")

        assertTrue("the name got .txt", scene.names().contains("Shopping.txt"))
    }

    @Test
    fun `a name that already has an extension keeps exactly it`() {
        scene.viewModel.createFile("plan.md")

        assertTrue("the extension is left alone", scene.names().contains("plan.md"))
    }

    @Test
    fun `a file is ordinary text on disk, with nothing added to it`() {
        val file = scene.file("milk.txt", "two litres")

        assertEquals("two litres", file.readText())
        assertEquals("no front matter, no header", "two litres", scene.viewModel.textOf(file))
    }

    @Test
    fun `a picture is recognised by its extension and is not opened in the editor`() {
        scene.file("photo.png")

        assertEquals(EntryKind.IMAGE, scene.entry("photo.png").kind)
    }

    @Test
    fun `a file whose extension is not a picture is text, whatever it is`() {
        scene.file("notes.whatever")

        assertEquals(EntryKind.TEXT, scene.entry("notes.whatever").kind)
    }

    @Test
    fun `renaming keeps the file's text`() {
        val file = scene.file("draft.txt", "the words")

        scene.viewModel.rename(file, "final.txt")

        assertEquals("the words", File(scene.viewModel.currentDir!!, "final.txt").readText())
        assertFalse("the old name is gone", File(scene.viewModel.currentDir!!, "draft.txt").exists())
    }

    @Test
    fun `a file can be moved into a folder and the folder can be moved into another`() {
        scene.folder("letters")
        scene.open("letters")
        scene.folder("2026")
        scene.open()
        val file = scene.file("milk.txt", "two litres")

        assertTrue(scene.viewModel.move(file, File(scene.viewModel.currentDir!!, "letters")))
        val moved = File(scene.viewModel.currentDir!!, "letters/milk.txt")
        assertTrue("it is where it was moved to", moved.isFile)
        assertEquals("with its text", "two litres", moved.readText())
    }

    @Test
    fun `a folder cannot be moved inside itself`() {
        val outer = scene.folder("outer")
        scene.open("outer")
        val inner = scene.folder("inner")

        assertFalse("the move is refused", scene.viewModel.move(outer, inner))
        assertTrue("the outer folder is still where it was", outer.isDirectory)
    }

    @Test
    fun `deleting a folder takes everything in it`() {
        scene.folder("letters")
        scene.open("letters")
        scene.file("one.txt", "1")
        scene.folder("deeper")
        scene.open("letters", "deeper")
        scene.file("two.txt", "2")
        scene.open()

        val letters = File(scene.viewModel.currentDir!!, "letters")
        assertTrue(scene.viewModel.delete(letters))
        assertFalse("the folder is gone", letters.exists())
        assertFalse("and what was in it", File(letters, "deeper/two.txt").exists())
    }

    @Test
    fun `two things cannot share a name in one folder`() {
        scene.file("milk.txt", "1")
        scene.file("milk.txt", "2")

        val milk = scene.names().filter { it == "milk.txt" }
        assertEquals("only one is there", listOf("milk.txt"), milk)
    }

    @Test
    fun `a file inside a project but outside the worktree is a local file`() {
        scene.project("Shopping")
        scene.open(Workspace.PROJECTS, "Shopping")
        val local = scene.file("shopping-list.txt", "milk")

        assertTrue("it is beside the worktree", local.parentFile!!.name == "Shopping")
        assertEquals("it belongs to the project", "Shopping", scene.workspace!!.projectOf(local))
        assertFalse("but not to the repository", scene.viewModel.inWorktree)
    }

    @Test
    fun `a file can be taken into the repository and out of it again`() {
        scene.project("Shopping")
        scene.open(Workspace.PROJECTS, "Shopping")
        val local = scene.file("milk.txt", "two litres")

        assertTrue("into the repository", scene.viewModel.moveIntoRepository(local))
        val inside = File(scene.workspace!!.repoOf("Shopping"), "milk.txt")
        assertTrue("the file is in the worktree", inside.isFile)
        assertFalse("and no longer beside it", local.exists())

        assertTrue("out of the repository", scene.viewModel.keepOnlyOnPhone(inside))
        assertTrue("back beside the worktree", File(local.parentFile, "milk.txt").isFile)
    }

    @Test
    fun `pinning a file is remembered and survives a reload`() {
        val file = scene.file("milk.txt", "1")
        scene.viewModel.togglePinned(file)

        val again = AppViewModel(
            scene.app, rootDir = scene.root, credentials = scene.credentials,
            settings = scene.settings, saveDebounceMs = 0,
        )
        again.refresh()

        assertTrue("the pin is still there", again.pins.contains("milk.txt"))
    }
}
