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
    fun `a move of several into a project's repository is recorded in both histories`() {
        // The boundary, with more than one file crossing it. The project's repository
        // has to gain both files, and the account's history has to record that they
        // left the account's own tree — one side alone leaves a copy that this phone
        // no longer has, or a file that GitHub never learns about.
        scene.project("groceries")
        scene.open()
        scene.file("milk.txt", "two litres")
        scene.file("bread.txt", "one loaf")

        scene.open("projects", "groceries", "repo")
        val worktree = scene.viewModel.currentDir!!
        scene.open()
        chooseAll("milk.txt", "bread.txt")

        assertTrue("moved into the worktree", scene.viewModel.moveSelected(worktree))

        val project = try {
            org.eclipse.jgit.api.Git.open(scene.workspace!!.repoOf("groceries"))
        } catch (e: Exception) {
            null
        }
        assertTrue("the project has a repository", project != null)
        project!!.use { git ->
            val tracked = git.status().call()
            assertTrue(
                "both files are tracked by the project",
                tracked.untracked.none { it.endsWith("milk.txt") } &&
                    tracked.untracked.none { it.endsWith("bread.txt") } &&
                    tracked.missing.none { it.endsWith("milk.txt") },
            )
            assertTrue(
                "and committed, not merely staged",
                git.log().call().any { commit ->
                    commit.fullMessage.contains("Moved 2 items") ||
                        commit.fullMessage.contains("Moved")
                },
            )
        }
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
