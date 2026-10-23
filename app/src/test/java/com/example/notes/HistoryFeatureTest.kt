package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §4 of the design: **the account's whole tree has a history.**
 *
 * Every save and every change to the tree is committed into the account's own
 * repository, which has no remote and no key. That is what gives a local file the
 * back and forward arrows, and what lets a folder that was deleted come back.
 */
class HistoryFeatureTest : FeatureTest() {

    /** The commits of the account's own history, newest first. Nothing yet is [emptyList]. */
    private fun log(): List<GitService.CommitInfo> {
        val dir = scene.workspace!!.accountDir
        if (!GitService.isRepository(dir)) return emptyList()
        return try {
            org.eclipse.jgit.api.Git.open(dir).use { GitService.log(it.repository, null) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    @Test
    fun `signing in starts the history`() {
        scene.await("the first commit") { log().isNotEmpty() }

        assertTrue("the account's history is a repository of its own", log().isNotEmpty())
    }

    @Test
    fun `a change to the tree is committed`() {
        val before = log().size

        scene.folder("letters")
        scene.await("the change to be recorded") { log().size > before }

        assertTrue("the folder is named in the commit", log().first().message.contains("letters"))
    }

    @Test
    fun `every save of a local file is a version of it`() {
        val file = scene.file("milk.txt", "one litre")
        scene.openEditor(file)
        scene.saveEditor("two litres")
        scene.saveEditor("three litres")
        scene.closeEditor()

        scene.await("three versions") { scene.versionsOf(file).size >= 3 }

        assertTrue(
            "each version has its own text",
            scene.versionsOf(file).map { it.second }.contains("one litre"),
        )
    }

    @Test
    fun `a local file's older version can be brought back`() {
        val file = scene.file("milk.txt", "one litre")
        scene.openEditor(file)
        scene.saveEditor("two litres")
        scene.closeEditor()
        scene.await("two versions") { scene.versionsOf(file).size >= 2 }

        val oldest = scene.versionsOf(file).last().first.id
        scene.restore(oldest)

        assertEquals("the old text is back", "one litre", file.readText())
    }

    @Test
    fun `restoring a version adds it back and leaves the newer ones alone`() {
        val file = scene.file("milk.txt", "one litre")
        scene.openEditor(file)
        scene.saveEditor("two litres")
        scene.closeEditor()
        scene.await("two versions") { scene.versionsOf(file).size >= 2 }
        val count = scene.versionsOf(file).size

        scene.restore(scene.versionsOf(file).last().first.id)
        scene.await("the restore to be recorded") { scene.versionsOf(file).size > count }

        assertTrue("nothing was deleted from the history", scene.versionsOf(file).size > count)
    }

    @Test
    fun `a deleted file's version brings it back`() {
        val file = scene.file("milk.txt", "one litre")
        scene.await("a version") { scene.versionsOf(file).isNotEmpty() }
        val version = scene.versionsOf(file).first().first.id
        val path = file.absolutePath

        scene.viewModel.delete(file)
        assertFalse("the file is gone", File(path).exists())

        scene.restore(version)

        assertTrue("it is back", File(path).isFile)
        assertEquals("with its text", "one litre", File(path).readText())
    }

    @Test
    fun `a deleted folder comes back with everything that was in it`() {
        scene.folder("letters")
        scene.open("letters")
        scene.file("one.txt", "first")
        scene.folder("deeper")
        scene.open("letters", "deeper")
        scene.file("two.txt", "second")
        scene.open()
        scene.await("the versions") { scene.versionsOfFolder("letters").isNotEmpty() }

        val version = scene.versionsOfFolder("letters").first().first.id
        val letters = File(scene.workspace!!.accountDir, "letters")

        scene.viewModel.delete(letters)
        assertFalse("the folder is gone", letters.exists())

        scene.restore(version)

        assertTrue("the folder is back", letters.isDirectory)
        assertEquals("with the file that was in it", "first", File(letters, "one.txt").readText())
        assertEquals(
            "and the one a level down",
            "second",
            File(letters, "deeper/two.txt").readText(),
        )
    }

    @Test
    fun `a folder's history lists what was in it at each version`() {
        scene.folder("letters")
        scene.open("letters")
        scene.file("one.txt", "first")
        scene.open()
        scene.await("a version of the folder") { scene.versionsOfFolder("letters").isNotEmpty() }

        val shown = scene.versionsOfFolder("letters").first()

        assertTrue("the file is in the version", shown.second!!.contains("one.txt"))
    }

    @Test
    fun `a project's changes go to the project's history, not the account's`() {
        scene.project("Shopping")
        scene.await("the project to be recorded") { log().isNotEmpty() }
        val before = log().size

        scene.open(Workspace.PROJECTS, "Shopping")
        scene.worktreeFile("milk.txt", "two litres")
        scene.sync("Shopping")

        assertEquals("the account's history did not grow", before, log().size)
        assertEquals("the project's did", 2, scene.remoteCommits("Shopping"))
    }

    @Test
    fun `the repository folder is never copied into the account's history`() {
        scene.project("Shopping")
        scene.open(Workspace.PROJECTS, "Shopping")
        scene.worktreeFile("milk.txt", "two litres")
        scene.sync("Shopping")
        scene.folder("something-else")
        scene.await("a commit") { log().isNotEmpty() }

        val tracked = scene.appHistoryFiles()
        assertFalse(
            "the worktree is not part of the account's history",
            tracked.any { it.startsWith("projects/Shopping/repo/") },
        )
        assertTrue("the project folder is", tracked.contains("projects/Shopping"))
    }

    @Test
    fun `a local file beside a project is in the account's history`() {
        scene.project("Shopping")
        scene.open(Workspace.PROJECTS, "Shopping")
        scene.file("diary.txt", "private")
        scene.await("a commit") { log().isNotEmpty() }

        assertTrue(
            "the local file is tracked",
            scene.appHistoryFiles().contains("projects/Shopping/diary.txt"),
        )
    }

    @Test
    fun `the history has no remote and needs no key`() {
        scene.await("the first commit") { log().isNotEmpty() }

        if (!GitService.isRepository(scene.workspace!!.accountDir)) return
        val remotes = org.eclipse.jgit.api.Git.open(scene.workspace!!.accountDir).use {
            it.repository.config.getSubsections("remote")
        }
        assertTrue("no remote is configured", remotes.isEmpty())
    }
}
