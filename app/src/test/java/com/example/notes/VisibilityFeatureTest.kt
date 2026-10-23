package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §3 of the design: **everything you made is visible; everything the app made is
 * not.**
 *
 * One rule decides what the screen draws, and it decides it in one place. These
 * are the promises it makes.
 */
class VisibilityFeatureTest : FeatureTest() {

    @Test
    fun `the root shows projects and the files a person made, and nothing the app made`() {
        scene.workspace!!.pins()
        File(scene.workspace!!.accountDir, "notes.txt").writeText("mine")

        assertEquals(listOf("projects", "notes.txt"), scene.names())
    }

    @Test
    fun `a name that begins with a dot is the app's, and is never drawn`() {
        val dir = scene.viewModel.currentDir!!

        File(dir, ".gitignore").writeText("a")
        File(dir, ".muffy").mkdirs()
        File(dir, ".anything").writeText("b")
        File(dir, "shown.txt").writeText("c")
        scene.viewModel.refresh()

        assertEquals(listOf("projects", "shown.txt"), scene.names())
    }

    @Test
    fun `projects is a folder of the app's, and cannot be renamed, moved or deleted`() {
        val projects = File(scene.workspace!!.accountDir, Workspace.PROJECTS)

        assertTrue("projects is structural", scene.workspace!!.isStructural(projects))
        assertFalse("a person's file is not", scene.workspace!!.isStructural(
            File(scene.workspace!!.accountDir, "notes.txt")
        ))
    }

    @Test
    fun `a project's repo folder is the worktree and is not a thing to move`() {
        scene.project("Shopping")
        val repo = scene.workspace!!.repoOf("Shopping")

        assertTrue("repo is structural", scene.workspace!!.isStructural(repo))
    }

    @Test
    fun `everything else inside a project is a file a person made`() {
        scene.project("Shopping")
        scene.open(Workspace.PROJECTS, "Shopping")
        scene.folder("ideas")

        val project = File(scene.workspace!!.projectsDir, "Shopping")
        assertTrue("the project's own folder", scene.workspace!!.isProjectFolder(project))
        assertFalse("a folder inside it is not", scene.workspace!!.isProjectFolder(
            File(project, "ideas")
        ))
    }

    @Test
    fun `folders are listed before files, each in name order`() {
        scene.folder("zebra")
        scene.file("Milk.txt")
        scene.file("apple.txt")

        assertEquals(listOf("projects", "zebra", "apple.txt", "Milk.txt"), scene.names())
    }

    @Test
    fun `a hidden file cannot be renamed, moved or deleted, even by name`() {
        val dir = scene.viewModel.currentDir!!
        val secret = File(dir, ".gitignore").apply { writeText("x") }

        assertTrue("renaming is refused", scene.viewModel.rename(secret, "shown.txt").not())
        assertTrue("deleting is refused", scene.viewModel.delete(secret).not())
        assertTrue("the file is untouched", secret.isFile)
    }

    @Test
    fun `a structural folder cannot be renamed or deleted either`() {
        val projects = File(scene.workspace!!.accountDir, Workspace.PROJECTS)

        assertTrue("renaming is refused", scene.viewModel.rename(projects, "elsewhere").not())
        assertTrue("deleting is refused", scene.viewModel.delete(projects).not())
        assertTrue("it is still there", projects.isDirectory)
    }

    @Test
    fun `search covers the whole tree except the app's own files`() {
        scene.folder("letters").let { scene.open("letters"); scene.file("note.txt", "a") }
        scene.open()
        File(scene.workspace!!.accountDir, "loose.txt").writeText("b")
        scene.viewModel.refresh()

        scene.viewModel.query = "note"
        assertTrue(
            "the buried file is found",
            scene.viewModel.visibleEntries.any { it.name == "note.txt" },
        )

        scene.viewModel.query = "gitignore"
        assertTrue("the app's files are not searched", scene.viewModel.visibleEntries.isEmpty())
    }
}
