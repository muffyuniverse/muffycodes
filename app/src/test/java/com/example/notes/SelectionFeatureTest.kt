package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Choosing several things and doing one thing to all of them.
 *
 * A long press used to open a menu for a single row, which is the wrong question:
 * the reason to press and hold on a list is to mark several entries, and the reason
 * to mark several is to move or delete them together. These tests are about the
 * consequences rather than the gesture — the screen's long press is one line of
 * `combinedClickable` — but they are also about the part that is easy to get wrong:
 * a move across the boundary between the two histories has to be recorded on both
 * sides, once for each repository that lost something.
 */
class SelectionFeatureTest : FeatureTest() {

    private fun chooseAll(vararg names: String) {
        names.forEach { scene.viewModel.toggleSelected(scene.entry(it).file) }
    }

    @Test
    fun `choosing is a set, and a second tap takes an item out again`() {
        scene.file("milk.txt")
        scene.file("bread.txt")

        chooseAll("milk.txt", "bread.txt")
        assertEquals(2, scene.viewModel.selected.size)

        // The same row again: chosen becomes not chosen. A selection that could only
        // grow would make a mistake impossible to undo without starting over.
        scene.viewModel.toggleSelected(scene.entry("milk.txt").file)
        assertEquals(1, scene.viewModel.selected.size)
        assertFalse(scene.viewModel.isSelected(scene.entry("milk.txt").file))
    }

    @Test
    fun `several files move together into one folder`() {
        val milk = scene.file("milk.txt", "two litres")
        val bread = scene.file("bread.txt", "one loaf")
        val recipes = scene.folder("recipes")
        chooseAll("milk.txt", "bread.txt")

        assertTrue("the move worked", scene.viewModel.moveSelected(recipes))

        assertTrue("milk arrived", File(recipes, "milk.txt").isFile)
        assertTrue("bread arrived", File(recipes, "bread.txt").isFile)
        assertFalse("and neither was copied", milk.exists() || bread.exists())
        assertTrue("the selection is finished with", scene.viewModel.selected.isEmpty())
        assertEquals("in one recorded change", "two litres", File(recipes, "milk.txt").readText())
    }

    @Test
    fun `a move of several into a project is recorded by that project`() {
        // The crossing, with more than one file making it. The project's repository has to gain
        // both files and commit them — a file that arrived but was never committed is a change
        // GitHub never learns about, which is what the project's row counts as "to send".
        scene.project("groceries")
        scene.open()
        scene.file("milk.txt", "two litres")
        scene.file("bread.txt", "one loaf")

        scene.open("groceries")
        val worktree = scene.viewModel.currentDir!!
        scene.open()
        chooseAll("milk.txt", "bread.txt")

        assertTrue("moved into the project", scene.viewModel.moveSelected(worktree))
        // The commit happens on the worker, so the repository has not answered yet:
        // what is asked for is the project's own answer, not the app's opinion of
        // what it just did.
        scene.awaitCommits("groceries", 1) { paths ->
            paths.any { it.endsWith("milk.txt") } && paths.any { it.endsWith("bread.txt") }
        }

        val project = try {
            org.eclipse.jgit.api.Git.open(scene.workspace!!.repoOf("groceries"))
        } catch (e: Exception) {
            null
        }
        assertTrue("the project has a repository", project != null)
        project!!.use { git ->
            val tracked = git.status().call()
            assertTrue(
                "both files are tracked by the project: $tracked",
                tracked.untracked.none { it.endsWith("milk.txt") } &&
                    tracked.untracked.none { it.endsWith("bread.txt") } &&
                    tracked.missing.none { it.endsWith("milk.txt") },
            )
            assertTrue(
                "and committed, not merely staged",
                git.log().call().any { commit ->
                    commit.fullMessage.contains("Moved 2 items into")
                },
            )
        }
    }

    @Test
    fun `a move that stops halfway keeps what moved, records it, and says how far it got`() {
        // **A batch has two kinds of failure.** A name that is taken, or a lock, is answered before
        // anything moves. The other kind cannot be: the disk fills, a folder vanishes, a folder is
        // asked to move inside itself. Stopping there with nothing recorded would leave files that
        // moved and no repository that knows about them — the one state this app exists to prevent.
        // So what moved is committed, counted, and said out loud, and the rest stays selected.
        scene.project("groceries")
        scene.open("groceries")
        scene.file("milk.txt", "two litres")
        val recipes = scene.folder("recipes")
        val cakes = File(recipes, "cakes").apply { mkdirs() }
        scene.viewModel.refresh()
        chooseAll("milk.txt", "recipes")

        // `milk.txt` sorts first and moves; `recipes` cannot move inside its own `cakes`.
        assertFalse("the batch did not finish", scene.viewModel.moveSelected(cakes))

        assertTrue("the first one moved", File(cakes, "milk.txt").isFile)
        assertTrue("the one that could not move is where it was", recipes.isDirectory)
        val said = scene.viewModel.message.orEmpty()
        assertTrue("it counts what happened: $said", said.contains("Moved 1 of 2"))
        assertTrue("and says why it stopped: $said", said.contains("inside itself"))
        assertTrue(
            "and the remainder is still chosen, so a second try is one touch",
            scene.viewModel.selected.size == 1 && scene.viewModel.isSelected(recipes),
        )
        // The project recorded the one that moved: a file on disk and in no repository is the
        // half-done state this change is about.
        assertTrue(
            "and the project recorded the one that moved",
            scene.awaitCommits("groceries", 1) { paths -> paths.any { it.endsWith("milk.txt") } },
        )
    }

    @Test
    fun `a batch that fails on its first item commits nothing and says nothing moved`() {
        scene.file("milk.txt", "two litres")
        val recipes = scene.folder("recipes")
        val cakes = File(recipes, "cakes").apply { mkdirs() }
        scene.viewModel.refresh()
        scene.viewModel.toggleSelected(recipes)

        assertFalse(scene.viewModel.moveSelected(cakes))

        assertTrue("still where it was", recipes.isDirectory)
        val said = scene.viewModel.message.orEmpty()
        assertTrue("it says nothing happened: $said", said.contains("Nothing was moved"))
        assertFalse("and does not claim a partial move: $said", said.contains("Moved 0"))
    }

    @Test
    fun `several files delete together, and the selection goes with them`() {
        scene.file("milk.txt")
        scene.file("bread.txt")
        val dir = scene.viewModel.currentDir!!
        chooseAll("milk.txt", "bread.txt")

        assertTrue("the delete worked", scene.viewModel.deleteSelected())

        assertFalse(File(dir, "milk.txt").exists())
        assertFalse(File(dir, "bread.txt").exists())
        assertTrue("nothing is left chosen", scene.viewModel.selected.isEmpty())
        assertTrue(
            "and neither is in the folder any more",
            scene.names().none { it == "milk.txt" || it == "bread.txt" },
        )
    }

    @Test
    fun `a folder and a file inside it can be deleted together`() {
        // Choosing a folder and one of its files is what a person does by accident
        // when the rows are next to each other — the row inside is not even on this
        // screen. Deleting the child after the folder has gone either fails or
        // deletes something that was made again in between.
        val folder = scene.folder("letters")
        val inside = File(folder, "one.txt").apply { writeText("the first") }
        scene.viewModel.refresh()
        scene.viewModel.toggleSelected(folder)
        scene.viewModel.toggleSelected(inside)

        assertTrue(scene.viewModel.deleteSelected())

        assertFalse("the folder is gone", folder.exists())
        assertFalse("and nothing was left inside it", inside.exists())
    }

    @Test
    fun `a selected folder cannot be moved into itself`() {
        val folder = scene.folder("recipes")
        val inner = File(folder, "cakes").apply { mkdirs() }
        scene.viewModel.refresh()
        scene.viewModel.toggleSelected(folder)

        // The window refuses to offer it; the model refuses to do it. Both, because
        // a rule held only by a screen is a rule the next screen can break.
        assertFalse(scene.viewModel.moveSelected(inner))
        assertTrue("the folder is where it was", folder.isDirectory)
    }

    @Test
    fun `a locked file cannot be written or renamed, even from the model`() {
        // The screen greys Save out, and that is not enough: a lock honoured only by
        // the screen is a lock the next screen will not honour.
        val file = scene.file("milk.txt", "two litres")
        scene.viewModel.toggleLocked(file)
        scene.viewModel.openEditor(file)

        scene.viewModel.saveEditor("two litres and a lemon")
        // The refusal is silent on purpose: the row and the editor both show a lock,
        // and a message per attempt would be the app saying the same thing over the
        // screen that already says it. What matters is that nothing was written.
        assertEquals("the text did not change", "two litres", file.readText())

        assertTrue("no rename happened", scene.viewModel.renameTo(file, "groceries") == null)
        assertTrue("the file kept its name", file.isFile)
        scene.viewModel.closeEditor()
    }

    @Test
    fun `a locked file cannot be deleted`() {
        // A lock that stopped writing but allowed deleting would be a lock on the
        // small act and not the large one: the file would go, and its versions with
        // it would be all that was left.
        val file = scene.file("milk.txt", "two litres")
        scene.viewModel.toggleLocked(file)

        assertFalse("the model refuses it", scene.viewModel.delete(file))

        assertTrue("and the file is still there", file.isFile)
        assertTrue(
            "and it said why: ${scene.viewModel.message}",
            scene.viewModel.message?.contains("locked") == true,
        )
    }

    @Test
    fun `an unlocked file deletes as it always did`() {
        // The falsification: a "locked" state that quietly refused everything would
        // pass the test above by never deleting anything at all.
        val file = scene.file("milk.txt", "two litres")
        scene.viewModel.toggleLocked(file)
        scene.viewModel.toggleLocked(file)

        assertTrue("it deletes", scene.viewModel.delete(file))

        assertFalse("and it is gone", file.exists())
    }

    @Test
    fun `one locked file stops the delete of several`() {
        // Deleting the rest and leaving one behind reads as a delete that worked,
        // and the person finds out later which half of it happened.
        val milk = scene.file("milk.txt", "two litres")
        val bread = scene.file("bread.txt", "a loaf")
        scene.viewModel.toggleLocked(bread)
        scene.viewModel.toggleSelected(milk)
        scene.viewModel.toggleSelected(bread)

        assertFalse("nothing is deleted", scene.viewModel.deleteSelected())

        assertTrue("not even the unlocked one", milk.isFile)
        assertTrue("and the locked one is where it was", bread.isFile)
        assertTrue(
            "and it said which: ${scene.viewModel.message}",
            scene.viewModel.message?.contains("bread.txt") == true,
        )
    }

    @Test
    fun `a lock is remembered, and it is not part of the file`() {
        // The lock lives in `.muffy`, so it changes nothing in the file and makes no
        // commit. Locking a note and then syncing must not carry the lock to GitHub,
        // and unlocking must not undo anything.
        val file = scene.file("milk.txt", "two litres")
        val before = scene.headId()

        scene.viewModel.toggleLocked(file)

        assertTrue("locked", scene.viewModel.isLocked(file))
        assertEquals("the file is untouched", "two litres", file.readText())
        assertEquals("and nothing was committed", before, scene.headId())

        // It survives the app looking at the tree again, which is what a fresh
        // screen does.
        scene.viewModel.refresh()
        assertTrue("still locked", scene.viewModel.isLocked(file))
    }
}
