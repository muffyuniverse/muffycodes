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
 * - **a taken name is refused, not numbered.** A copy that quietly became `milk 2.txt` put a
 *   file somewhere the person did not ask for, under a name they will not recognise in a list,
 *   and said "Copied". The refusal is one sentence, and it is the same sentence a move gives;
 * - a folder arrives complete, and not inside itself;
 * - **a batch is all or nothing**: a taken name anywhere means nothing moves, and the message
 *   names the names that are taken;
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
    fun `a name that is taken is refused, and nothing is numbered`() {
        // Given a folder that already holds a file of that name
        val into = aFolder("drafts")
        File(into, "milk.txt").writeText("one litre")
        val original = aFile("milk.txt", "two litres")

        // When a file of the same name is copied in
        val done = scene.viewModel.copy(original, into)

        // Then the app refuses rather than inventing a name, and says which name
        assertFalse("the copy is refused", done)
        val said = scene.viewModel.message.orEmpty()
        assertTrue("it names the file: $said", said.contains("milk.txt"))
        assertTrue("and says it is already there: $said", said.contains("already"))
        assertFalse("no numbered name was made: $said", File(into, "milk 2.txt").exists())
        assertEquals("and the file that was there is untouched", "one litre", File(into, "milk.txt").readText())
        assertEquals("as is the original", "two litres", original.readText())
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
        assertFalse("and nothing was made inside it", File(source, "notes").exists())
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
    fun `a copy into a project is recorded by that project, with the version that holds it`() {
        // Given a project, and a file outside it at the account's root
        scene.project("groceries")
        val beside = aFile("shopping.txt", "milk, bread")
        val into = scene.workspace!!.repoOf("groceries")
        scene.await("the making of the file to be recorded", 30_000) { !scene.viewModel.busy }

        // When the file is copied into the project
        scene.viewModel.copy(beside, into)
        scene.await("the project to record the arrival", 30_000) {
            scene.repo("groceries").log(null).isNotEmpty() &&
                scene.trackedFiles("groceries").any { it.endsWith("shopping.txt") }
        }

        // Then the project's own history holds the copy — a file that existed but was never
        // committed is exactly what "no version" means — and the original stayed outside.
        assertTrue("the project holds the copy", File(into, "shopping.txt").isFile)
        assertTrue("and the original is where it was", beside.isFile)
        // **Committed and still to send, both at once.** The copy is a change like any
        // other: it is committed to the project's own history the moment it lands, and that
        // commit is what the row counts as "to send" until a push puts it on GitHub.
        // Reading the count as "nothing left to send" was the same mistake the row itself
        // made — a clean worktree is not a synced project.
        scene.await("the copy to be waiting to be sent", 30_000) {
            scene.viewModel.changesOf("groceries") == 1
        }
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
    fun `a batch with one taken name copies nothing, and names what is taken`() {
        // Given a folder that already holds one of the two names
        val into = aFolder("drafts")
        File(into, "milk.txt").writeText("old")
        val milk = aFile("milk.txt", "two litres")
        val bread = aFile("bread.txt", "one loaf")
        scene.viewModel.toggleSelected(milk)
        scene.viewModel.toggleSelected(bread)

        // When they are copied together
        val done = scene.viewModel.copySelected(into)

        // Then the whole batch is refused: the one that would have worked is not a partial answer
        assertFalse("nothing was copied", done)
        assertFalse("the free name did not arrive either", File(into, "bread.txt").exists())
        assertEquals("and the file that was there is untouched", "old", File(into, "milk.txt").readText())
        val said = scene.viewModel.message.orEmpty()
        assertTrue("it says nothing was copied: $said", said.contains("Nothing was copied"))
        assertTrue("and names the taken name: $said", said.contains("milk.txt"))
        assertFalse("without inventing a numbered one: $said", File(into, "milk 2.txt").exists())
    }

    @Test
    fun `an import still numbers a taken name, because the person never chose it`() {
        // Given a folder already holding `photo.png` and `photo 2.png`
        val dir = scene.viewModel.currentDir!!
        File(dir, "photo.png").writeText("one")
        File(dir, "photo 2.png").writeText("two")

        // When an import needs a free name for a file it is bringing in
        val byCopy = Workspace.freeName(dir, "photo.png")

        // Then it takes the next number, in the app's one name shape. A copy and a move refuse
        // instead — the person named those — but an import has no name to contradict.
        val byImport = Imports.freeName(dir, "photo.png").name
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
