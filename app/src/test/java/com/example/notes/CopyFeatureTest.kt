package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Copying (§3): **the original stays, and a second one appears where it was asked for.**
 *
 * One behaviour per test, stated as given / when / then, because a copy has several things
 * that can be wrong in isolation and a test that asserted all of them at once would pass
 * while one of them was broken:
 *
 * - the original is untouched — the one thing that separates copy from move;
 * - the copy is a *new* thing, so a taken name is renamed rather than refused, and
 *   `milk.txt` becomes `milk 2.txt` rather than `milk.txt 2`;
 * - a folder arrives complete, and not inside itself;
 * - only the receiving history is told, because nothing left anywhere.
 *
 * The falsification each test carries is its own negative: "the original is still there"
 * is written beside "and the copy is a different file", because a `move` would satisfy
 * the first if the second were left out.
 */
class CopyFeatureTest : FeatureTest() {

    /** A file in the folder that is open, with a known body. */
    private fun aFile(name: String, text: String): File = scene.file(name, text)

    /** A folder in the folder that is open. */
    private fun aFolder(name: String): File = scene.folder(name)

    // ---- a file -------------------------------------------------------------

    @Test
    fun `a copied file is a second file, and the first one is still there`() {
        // Given a file and a folder beside it
        val original = aFile("milk.txt", "two litres")
        val into = aFolder("drafts")

        // When it is copied into the folder
        assertTrue("the copy is reported as done", scene.viewModel.copy(original, into))

        // Then both exist, and they are different files
        assertTrue("the original is still there", original.isFile)
        assertTrue("the copy is there too", File(into, "milk.txt").isFile)
        assertFalse(
            "and they are not the same file, which is what a move would have left",
            original.canonicalPath == File(into, "milk.txt").canonicalPath,
        )
    }

    @Test
    fun `a copy holds the same text as what it was copied from`() {
        // Given a file with a body
        val original = aFile("milk.txt", "two litres")
        val into = aFolder("drafts")

        // When it is copied
        scene.viewModel.copy(original, into)

        // Then the copy's text is the original's
        assertEquals("two litres", File(into, "milk.txt").readText())
    }

    @Test
    fun `the original does not change when the copy is written to`() {
        // Given a copied file
        val original = aFile("milk.txt", "two litres")
        val into = aFolder("drafts")
        scene.viewModel.copy(original, into)

        // When the copy is changed
        File(into, "milk.txt").writeText("three litres")

        // Then the original is untouched: a copy is not a link
        assertEquals("the original kept its own text", "two litres", original.readText())
    }

    @Test
    fun `a name that is taken becomes a numbered name, and the extension stays on the end`() {
        // Given a folder that already holds a file of that name
        val into = aFolder("drafts")
        File(into, "milk.txt").writeText("one litre")
        val original = aFile("milk.txt", "two litres")

        // When a file of the same name is copied in
        scene.viewModel.copy(original, into)

        // Then the copy is renamed, extension last, and nothing is overwritten
        assertEquals("the file that was there is untouched", "one litre", File(into, "milk.txt").readText())
        assertTrue("the copy arrived beside it", File(into, "milk 2.txt").isFile)
        assertEquals("with its own text", "two litres", File(into, "milk 2.txt").readText())
    }

    @Test
    fun `a second copy of the same name is numbered again rather than reusing 2`() {
        // Given a folder that already holds milk.txt and milk 2.txt
        val into = aFolder("drafts")
        File(into, "milk.txt").writeText("one")
        File(into, "milk 2.txt").writeText("two")
        val original = aFile("milk.txt", "three")

        // When it is copied in
        scene.viewModel.copy(original, into)

        // Then the next free number is used
        assertTrue("milk 3.txt is the new one", File(into, "milk 3.txt").isFile)
        assertEquals("one", File(into, "milk.txt").readText())
        assertEquals("two", File(into, "milk 2.txt").readText())
    }

    @Test
    fun `the app says when a copy was renamed, because that is what the person cannot see`() {
        // Given a taken name
        val into = aFolder("drafts")
        File(into, "milk.txt").writeText("one")

        // When a copy lands under a new name
        scene.viewModel.copy(aFile("milk.txt", "two"), into)

        // Then the message names both the folder and the new name
        val said = scene.viewModel.message.orEmpty()
        assertTrue("it says a copy happened: $said", said.contains("Copied"))
        assertTrue("it names the folder: $said", said.contains("drafts"))
        assertTrue("and the new name: $said", said.contains("milk 2.txt"))
    }

    @Test
    fun `a name that was free is not renamed, and the message does not pretend otherwise`() {
        // Given an empty folder
        val into = aFolder("drafts")

        // When a copy lands with its own name
        scene.viewModel.copy(aFile("milk.txt", "two"), into)

        // Then nothing is said about renaming
        val said = scene.viewModel.message.orEmpty()
        assertTrue("the copy is reported: $said", said.contains("Copied milk.txt"))
        assertFalse("and no rename is claimed: $said", said.contains(" as "))
    }

    // ---- a folder -----------------------------------------------------------

    @Test
    fun `a copied folder arrives with everything inside it`() {
        // Given a folder holding a file and a nested folder with a file
        val source = aFolder("notes")
        File(source, "milk.txt").writeText("two litres")
        val inner = File(source, "inner")
        inner.mkdirs()
        File(inner, "bread.txt").writeText("one loaf")
        val into = aFolder("drafts")

        // When the folder is copied
        scene.viewModel.copy(source, into)

        // Then the whole tree came with it
        val copy = File(into, "notes")
        assertTrue("the folder", copy.isDirectory)
        assertEquals("its file", "two litres", File(copy, "milk.txt").readText())
        assertTrue("the nested folder", File(copy, "inner").isDirectory)
        assertEquals("and the file inside that", "one loaf", File(copy, "inner/bread.txt").readText())
        assertTrue("the original still holds its own", File(source, "inner/bread.txt").isFile)
    }

    @Test
    fun `a folder cannot be copied into itself`() {
        // Given a folder
        val source = aFolder("notes")

        // When it is copied into itself
        val done = scene.viewModel.copy(source, source)

        // Then the app refuses, and says why
        assertFalse("the copy is refused", done)
        assertTrue(
            "and the reason names the problem",
            scene.viewModel.message.orEmpty().contains("cannot be copied inside itself"),
        )
        assertEquals("nothing was made inside it", 0, File(source, "notes").let { if (it.exists()) 1 else 0 })
    }

    @Test
    fun `a folder cannot be copied into a folder of its own`() {
        // Given a folder with a folder inside it
        val source = aFolder("notes")
        val inner = File(source, "inner")
        inner.mkdirs()

        // When the outer folder is copied into the inner one
        val done = scene.viewModel.copy(source, inner)

        // Then it is refused, for the same reason
        assertFalse("a copy into its own descendant is refused", done)
        assertFalse("and nothing was made", File(inner, "notes").exists())
    }

    // ---- where it lands -----------------------------------------------------

    @Test
    fun `a copy into a project's worktree is recorded by the project, and the account is not told`() {
        // Given a project with a worktree, and a file beside it
        scene.project("groceries")
        scene.open(Workspace.PROJECTS, "groceries")
        val beside = aFile("shopping.txt", "milk, bread")
        val before = scene.commitCount("groceries")

        // When the file is copied into the worktree
        scene.viewModel.copy(beside, scene.workspace!!.repoOf("groceries"))
        scene.await("the project to record the arrival", 30_000) {
            scene.commitCount("groceries") > before
        }

        // Then the project has it, and the original stayed outside
        assertTrue(
            "the worktree holds the copy",
            File(scene.workspace!!.repoOf("groceries"), "shopping.txt").isFile,
        )
        assertTrue("and the original is where it was", beside.isFile)
        assertEquals("the count it reports to Sync", 0, scene.viewModel.changesOf("groceries"))
    }

    @Test
    fun `a copy that stays on this phone records the account's history`() {
        // Given a file and a folder, both outside any project
        val original = aFile("milk.txt", "two litres")
        val into = aFolder("drafts")
        scene.await("the making of both to be recorded", 30_000) { !scene.viewModel.busy }
        val before = scene.headId()

        // When it is copied
        scene.viewModel.copy(original, into)

        // Then the account's history has a version for it
        scene.await("the account to record the copy", 30_000) { scene.headId() != before }
        assertTrue("the new file is in a version", File(into, "milk.txt").isFile)
    }

    // ---- what a lock means --------------------------------------------------

    @Test
    fun `a locked file can be copied, because copying does not change it`() {
        // Given a locked file
        val original = aFile("milk.txt", "two litres")
        scene.viewModel.toggleLocked(original)
        assertTrue("it really is locked", scene.viewModel.isLocked(original))
        val into = aFolder("drafts")

        // When it is copied
        val done = scene.viewModel.copy(original, into)

        // Then the copy happens: a lock is about changing *this* file
        assertTrue("the copy was made", done)
        assertTrue("and the copy is there", File(into, "milk.txt").isFile)
        assertEquals("the original still holds its text", "two litres", original.readText())
    }

    @Test
    fun `a copy of a locked file is not itself locked`() {
        // Given a locked file that has been copied
        val original = aFile("milk.txt", "two litres")
        scene.viewModel.toggleLocked(original)
        val into = aFolder("drafts")
        scene.viewModel.copy(original, into)

        // Then the new file is a new file: the lock was on the one it came from
        assertFalse(
            "the copy can be written",
            scene.viewModel.isLocked(File(into, "milk.txt")),
        )
    }

    // ---- several at once ----------------------------------------------------

    @Test
    fun `several chosen things copy together into one folder`() {
        // Given three files and a folder to put them in
        val milk = aFile("milk.txt", "two litres")
        val bread = aFile("bread.txt", "one loaf")
        val eggs = aFile("eggs.txt", "six")
        val into = aFolder("drafts")
        listOf(milk, bread, eggs).forEach { scene.viewModel.toggleSelected(it) }

        // When they are copied together
        assertTrue("the copy is reported as done", scene.viewModel.copySelected(into))

        // Then all three arrived and all three are still where they were
        listOf("milk.txt", "bread.txt", "eggs.txt").forEach { name ->
            assertTrue("$name arrived", File(into, name).isFile)
            assertTrue("and $name is still in the folder it came from", File(scene.viewModel.currentDir!!, name).isFile)
        }
    }

    @Test
    fun `copying several clears the selection, because the act is done`() {
        // Given two chosen files
        val milk = aFile("milk.txt", "two litres")
        val bread = aFile("bread.txt", "one loaf")
        val into = aFolder("drafts")
        scene.viewModel.toggleSelected(milk)
        scene.viewModel.toggleSelected(bread)

        // When they are copied
        scene.viewModel.copySelected(into)

        // Then nothing is chosen any more, and both copies came with their text
        assertTrue("the selection is empty", scene.viewModel.selected.isEmpty())
        assertEquals("two litres", File(into, "milk.txt").readText())
        assertEquals("one loaf", File(into, "bread.txt").readText())
    }

    @Test
    fun `copying several says how many were renamed rather than quietly numbering them`() {
        // Given a folder that already holds one of the two names
        val into = aFolder("drafts")
        File(into, "milk.txt").writeText("old")
        val milk = aFile("milk.txt", "two litres")
        val bread = aFile("bread.txt", "one loaf")
        scene.viewModel.toggleSelected(milk)
        scene.viewModel.toggleSelected(bread)

        // When they are copied
        scene.viewModel.copySelected(into)

        // Then one was renamed and the message says so
        val said = scene.viewModel.message.orEmpty()
        assertTrue("it says two arrived: $said", said.contains("Copied 2 items"))
        assertTrue("and that one was renamed: $said", said.contains("1 renamed"))
    }

    @Test
    fun `a copy and an import number a taken name the same way`() {
        // Given a file already called `photo.png`
        val dir = scene.viewModel.currentDir!!
        File(dir, "photo.png").writeText("one")
        File(dir, "photo 2.png").writeText("two")

        // When a copy needs a free name, and an import needs one for the same name
        val byCopy = Workspace.freeName(dir, "photo.png")
        val byImport = Imports.freeName(dir, "photo.png").name

        // Then they agree. They used to differ — `photo 3.png` from a copy and
        // `photo_3.png` from an import — which is two answers to one question.
        assertEquals("photo 3.png", byCopy)
        assertEquals("the import asks the same rule", byCopy, byImport)
    }

    @Test
    fun `copying with nothing chosen says so instead of doing nothing`() {
        // Given nothing chosen
        val into = aFolder("drafts")

        // When a copy of the selection is asked for
        val done = scene.viewModel.copySelected(into)

        // Then it says why, rather than reporting a copy of zero things as success
        assertFalse("it is not a success", done)
        assertEquals("Nothing is selected", scene.viewModel.message)
    }
}
