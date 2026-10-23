package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §6 and §7 of the design: **the Projects screen, and what a project is.**
 *
 * A project is a folder holding a `repo/` worktree and whatever a person keeps
 * beside it. The row says the account, the project, how many changes are waiting,
 * and carries one button that is always in the same place.
 */
class ProjectsFeatureTest : FeatureTest() {

    @Test
    fun `making a project makes a repository first, then the folder with a clone in it`() {
        scene.project("Shopping")

        assertTrue("the repository is on the pretend GitHub", scene.remote("Shopping").isDirectory)
        assertTrue("the folder exists", scene.workspace!!.repoOf("Shopping").isDirectory)
        assertTrue(
            "and the clone brought the seeded files down",
            File(scene.workspace!!.repoOf("Shopping"), "README.md").isFile,
        )
    }

    @Test
    fun `a new project is opened when it is made`() {
        scene.project("Shopping")

        assertEquals(listOf(Workspace.PROJECTS, "Shopping"), scene.viewModel.path)
    }

    @Test
    fun `the seeded licence lands in the clone, because GitHub writes it`() {
        scene.project("Shopping", licence = "mit")

        assertTrue(
            "the licence came down with the clone",
            File(scene.workspace!!.repoOf("Shopping"), "LICENSE").isFile,
        )
    }

    @Test
    fun `a project's row counts the changes waiting inside its worktree`() {
        scene.project("Shopping")
        scene.open(Workspace.PROJECTS, "Shopping")
        scene.worktreeFile("milk.txt", "two litres")
        scene.viewModel.refreshChanges()
        scene.await("the row to count the change", 20_000) {
            scene.viewModel.changesOf("Shopping") == 1
        }

        assertEquals("one file waiting", 1, scene.viewModel.changesOf("Shopping"))
    }

    @Test
    fun `a file beside the worktree is not a change the project has to explain`() {
        scene.project("Shopping")
        scene.open(Workspace.PROJECTS, "Shopping")
        scene.file("local.txt", "not for GitHub")
        scene.viewModel.refreshChanges()

        assertEquals("nothing waiting", 0, scene.viewModel.changesOf("Shopping"))
    }

    @Test
    fun `a project cannot be made with a name that is already taken`() {
        scene.project("Shopping")
        scene.viewModel.message = null

        scene.viewModel.createProject("shopping", "mit")

        assertTrue(
            "the app says why",
            scene.viewModel.message!!.contains("already a project"),
        )
        assertEquals("and made nothing", 1, scene.viewModel.projectNames.size)
    }

    @Test
    fun `a project cannot be made with a name a file could not have`() {
        scene.viewModel.createProject("bad/name", "mit")

        assertTrue("the app says no", scene.viewModel.message != null)
        assertTrue("and made nothing", scene.viewModel.projectNames.isEmpty())
    }

    @Test
    fun `renaming a project renames the folder and the repository together`() {
        scene.project("Shopping")

        scene.viewModel.renameProject("Shopping", "Groceries")
        scene.await("the rename to finish") { !scene.viewModel.busy }

        assertFalse("the old folder is gone", File(scene.workspace!!.projectsDir, "Shopping").exists())
        assertTrue("the new folder is there", File(scene.workspace!!.projectsDir, "Groceries").isDirectory)
        assertFalse("the old repository is gone", scene.remote("Shopping").exists())
        assertTrue("the new repository is there", scene.remote("Groceries").isDirectory)
    }

    @Test
    fun `renaming a project keeps the clone inside it`() {
        scene.project("Shopping")
        scene.open(Workspace.PROJECTS, "Shopping")
        scene.worktreeFile("milk.txt", "two litres")

        scene.viewModel.renameProject("Shopping", "Groceries")
        scene.await("the rename to finish") { !scene.viewModel.busy }

        assertTrue(
            "the worktree moved with the folder",
            File(scene.workspace!!.repoOf("Groceries"), "milk.txt").isFile,
        )
    }

    @Test
    fun `deleting a project from this phone only leaves the repository alone`() {
        scene.project("Shopping")

        scene.viewModel.deleteProject("Shopping", everywhere = false)
        scene.await("the delete to finish") { !scene.viewModel.busy }

        assertFalse("the folder is gone", File(scene.workspace!!.projectsDir, "Shopping").exists())
        assertTrue("the repository is untouched", scene.remote("Shopping").isDirectory)
    }

    @Test
    fun `deleting a project everywhere removes the repository too`() {
        scene.project("Shopping")

        scene.viewModel.deleteProject("Shopping", everywhere = true)
        scene.await("the delete to finish") { !scene.viewModel.busy }

        assertFalse("the folder is gone", File(scene.workspace!!.projectsDir, "Shopping").exists())
        assertFalse("and the repository with it", scene.remote("Shopping").exists())
    }

    @Test
    fun `the add window offers the account's repositories`() {
        scene.project("Shopping")
        var offered: List<String> = emptyList()
        var problem: String? = null

        scene.viewModel.listRepositories { repos, error ->
            offered = repos.map { it.name }
            problem = error
        }
        scene.await("the list to arrive") { offered.isNotEmpty() || problem != null }

        assertTrue("the repository is offered", offered.contains("Shopping"))
        assertNull("and nothing went wrong", problem)
    }

    @Test
    fun `pulling a repository in makes a project of it`() {
        scene.project("Shopping")
        scene.viewModel.deleteProject("Shopping", everywhere = false)
        scene.await("the delete to finish") { !scene.viewModel.busy }
        assertTrue("nothing is here", scene.viewModel.projectNames.isEmpty())

        scene.viewModel.pullRepository(Repo("Shopping", "$scene.login/Shopping", false, ""))
        scene.await("the pull to finish") { !scene.viewModel.busy }
        scene.await("the repository's files to be here", 30_000) {
            File(scene.workspace!!.repoOf("Shopping"), "README.md").isFile
        }

        assertTrue("the project came back", scene.viewModel.projectNames.contains("Shopping"))
        assertTrue(
            "with the repository's files",
            File(scene.workspace!!.repoOf("Shopping"), "README.md").isFile,
        )
    }

    @Test
    fun `the licences a project can be made with come from the GitHub in use`() {
        var licences: List<String> = emptyList()

        scene.viewModel.listLicences { licences = it.map { licence -> licence.key } }
        scene.await("the licences to arrive") { licences.isNotEmpty() }

        assertTrue("MIT is offered", licences.contains("mit"))
    }
}
