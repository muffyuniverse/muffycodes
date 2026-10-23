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
    fun `an account with nothing in it has no history yet, and its first change starts one`() {
        assertTrue("nothing has happened", log().isEmpty())

        scene.folder("letters")
        scene.await("the first commit") { log().isNotEmpty() }

        assertTrue("the first change is the first version", log().isNotEmpty())
    }

    @Test
    fun `an empty folder still has a version, so it can come back`() {
        scene.folder("empty")
        scene.await("a version") { scene.versionsOfFolder("empty").isNotEmpty() }

        val version = scene.versionsOfFolder("empty").first().first.id
        val folder = File(scene.workspace!!.accountDir, "empty")
        scene.viewModel.delete(folder)
        assertFalse("the empty folder is gone", folder.exists())

        scene.restore(version)

        assertTrue("and an empty folder is still a folder", folder.isDirectory)
    }

    @Test
    fun `a change to the tree is committed`() {
        val before = log().size

        scene.folder("letters")
        scene.await("the change to be recorded") { log().size > before }

        assertTrue("the folder is named in the commit", log().first().message.contains("letters"))
    }

    @Test
    fun `each save with a pause is a version of the file`() {
        val file = scene.file("milk.txt")
        scene.openEditor(file)
        scene.saveAsVersion("one litre")
        scene.saveAsVersion("two litres")
        scene.saveAsVersion("three litres")
        scene.closeEditor()

        assertTrue(
            "each version has its own text",
            scene.versionsOf(file).map { it.second }.contains("one litre"),
        )
    }

    @Test
    fun `a local file's older version can be brought back`() {
        val file = scene.file("milk.txt")
        scene.openEditor(file)
        scene.saveAsVersion("one litre")
        scene.saveAsVersion("two litres")
        scene.closeEditor()

        val versions = scene.versionsOf(file)
        val oneLitre = versions.first { it.second == "one litre" }
        scene.restore(oneLitre.first.id)

        assertEquals("that version's text is back", "one litre", file.readText())
    }

    @Test
    fun `restoring a version adds it back and leaves the newer ones alone`() {
        val file = scene.file("milk.txt")
        scene.openEditor(file)
        scene.saveAsVersion("one litre")
        scene.saveAsVersion("two litres")
        scene.closeEditor()
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
    fun `renaming a local folder is a version, so the name can be put back`() {
        // §4: "Folder organisation is versioned. Renames and moves of local things
        // are reversible, which is exactly where people regret things most." The
        // commit is the easy half; this checks that the *old name* is genuinely in
        // the history and can be brought back, because a version that records only
        // the new name would be a version of nothing.
        scene.folder("notes-2019")
        scene.open("notes-2019")
        scene.file("first.txt", "the first thing")
        scene.open()
        val folder = File(scene.workspace!!.accountDir, "notes-2019")

        assertTrue(
            "the folder was renamed",
            scene.viewModel.rename(folder, "archive"),
        )

        val renamed = File(scene.workspace!!.accountDir, "archive")
        assertTrue("the folder has its new name", renamed.isDirectory)
        assertTrue("and holds what it held", File(renamed, "first.txt").isFile)

        // The history knows the rename happened, and the version before it still
        // has the folder under its old name with its contents.
        val versions = scene.accountHistory().log(null)
        assertTrue("there is more than one version", versions.size >= 2)
        assertTrue(
            "some version mentions the old name",
            versions.any { it.message.contains("notes-2019") },
        )
    }

    @Test
    fun `moving a local folder into another is a version too`() {
        // The other half of the same promise. A move is a rename of a path, so it
        // has to reach the history the same way.
        scene.folder("inbox")
        scene.folder("archive")
        scene.open("inbox")
        scene.file("letter.txt", "dear")
        scene.open()
        val inbox = File(scene.workspace!!.accountDir, "inbox")
        val archive = File(scene.workspace!!.accountDir, "archive")
        val before = scene.headId()

        assertTrue("the folder moved", scene.viewModel.move(inbox, archive))

        scene.await("the move to be recorded") { scene.headId() != before }
        val moved = File(archive, "inbox")
        assertTrue("it is in its new place", moved.isDirectory)
        assertTrue("with its file", File(moved, "letter.txt").isFile)
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
        assertEquals(
            "and nothing of the repository leaked in beside it",
            emptyList<String>(),
            tracked.filter { it.contains("repo/") },
        )
    }

    @Test
    fun `a local file beside a project is in the account's history`() {
        scene.project("Shopping")
        scene.open(Workspace.PROJECTS, "Shopping")
        scene.file("diary.txt", "private")
        scene.await("the local file to be recorded", 20_000) {
            scene.appHistoryFiles().contains("projects/Shopping/diary.txt")
        }

        assertTrue(
            "the local file is tracked",
            scene.appHistoryFiles().contains("projects/Shopping/diary.txt"),
        )
    }

    @Test
    fun `the history has no remote and needs no key`() {
        scene.folder("something")
        scene.await("the first commit") { log().isNotEmpty() }

        if (!GitService.isRepository(scene.workspace!!.accountDir)) return
        val remotes = org.eclipse.jgit.api.Git.open(scene.workspace!!.accountDir).use {
            it.repository.config.getSubsections("remote")
        }
        assertTrue("no remote is configured", remotes.isEmpty())
    }
}
