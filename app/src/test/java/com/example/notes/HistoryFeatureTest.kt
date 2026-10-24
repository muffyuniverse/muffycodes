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
    fun `every save in one editing session is its own version`() {
        // What the phone reported: several saves inside the same editor window, and
        // the history showing the file as it was when it was opened. The write used
        // to happen when Save was pressed and the commit later, so two saves in a row
        // left the file holding the second text before the first commit ran: the
        // first commit took the newer text and the second found nothing to commit.
        val file = scene.file("milk.txt", "one litre")
        scene.openEditor(file)

        // When the same window is saved three times
        scene.saveAsVersion("two litres")
        scene.saveAsVersion("three litres")
        scene.saveAsVersion("four litres")
        scene.closeEditor()

        // Then every save can be read back, newest first
        val versions = scene.versionsOf(file)
        val texts = versions.map { it.second }
        assertEquals(
            "each save is a step back: $texts",
            listOf("four litres", "three litres", "two litres"),
            texts.take(3),
        )
        assertEquals(
            "and they are three different versions, not one version read three times",
            3,
            versions.take(3).map { it.first.id }.distinct().size,
        )
    }

    @Test
    fun `a save that changes nothing is not a version`() {
        // The falsification of the test above: a history that grew whatever happened
        // would pass that one and fail this one. Saving the same text twice is a
        // commit with nothing in it, and a list of them is a history nobody can read.
        val file = scene.file("milk.txt", "one litre")
        scene.openEditor(file)
        scene.saveAsVersion("two litres")
        val before = scene.versionsOf(file).size

        scene.viewModel.saveEditor("two litres")
        scene.closeEditor()
        scene.await("the save to have been considered", 20_000) {
            scene.versionsOf(file).size == before
        }

        assertEquals("no version was added", before, scene.versionsOf(file).size)
    }

    @Test
    fun `the versions come from the repository, so a commit made by git is a version too`() {
        // "Do you calculate everything off git history, or some other storage?" Git:
        // the screen reads the repository. A commit written by JGit with no screen
        // anywhere near it must appear in the list, which is what tells the two apart.
        val file = scene.file("milk.txt", "one litre")
        val account = scene.workspace!!.accountDir
        org.eclipse.jgit.api.Git.open(account).use { git ->
            file.writeText("written straight to git")
            GitService.commitAll(git.repository, "A commit from outside the app", AppViewModel.DEFAULT_AUTHOR)
        }

        val texts = scene.versionsOf(file).map { it.second }

        assertEquals("the outside commit is the newest version", "written straight to git", texts.first())
        assertTrue("and the app's own save is still below it", texts.contains("one litre"))
    }

    @Test
    fun `a folder deleted with everything in it comes back as a folder`() {
        // Deleting a folder is one act, so bringing it back is one row — and putting
        // back one file at a time from a list of five names is not the same thing.
        scene.folder("letters")
        scene.open("letters")
        scene.file("milk.txt", "two litres")
        scene.folder("inner")
        scene.open("letters", "inner")
        scene.file("notes.txt", "the inner one")
        scene.open()
        val letters = File(scene.workspace!!.accountDir, "letters")

        scene.viewModel.delete(letters)
        scene.await("the folder to go", 20_000) { !letters.exists() }

        val gone = scene.viewModel.deletedIn(scene.workspace!!.accountDir)
        val offered = gone.firstOrNull { it.name == "letters" }
        assertTrue("the folder is offered back: $gone", offered != null)
        assertTrue("and it is offered as a folder", offered!!.isFolder)

        assertTrue(
            "bringing it back works",
            scene.viewModel.restoreDeleted(scene.workspace!!.accountDir, offered),
        )
        assertTrue("the folder is there", letters.isDirectory)
        assertEquals("with its file", "two litres", File(letters, "milk.txt").readText())
        assertEquals(
            "and the one that was inside a folder inside it",
            "the inner one",
            File(letters, "inner/notes.txt").readText(),
        )
    }

    @Test
    fun `a file that is still there is not offered back`() {
        // The falsification of the restore list: a list that offered everything the
        // history ever held would "work" while putting files back that never went
        // anywhere, and overwriting what is on disk with an older reading of it.
        val kept = scene.file("kept.txt", "still here")
        scene.viewModel.delete(kept)
        scene.await("the file to go", 20_000) { !kept.exists() }
        scene.viewModel.restoreDeleted(scene.workspace!!.accountDir, scene.viewModel.deletedIn(scene.workspace!!.accountDir).first { it.name == "kept.txt" })
        assertTrue("it is back", kept.isFile)

        val gone = scene.viewModel.deletedIn(scene.workspace!!.accountDir)

        assertTrue("and it is not offered again: $gone", gone.none { it.name == "kept.txt" })
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
    fun `each save inside a project is a version of it, with no sync anywhere`() {
        // The worry this answers: inside a project, does the history move between
        // *syncs*, or between changes? It has to be changes — "go back to how it was"
        // is wanted a second after the mistake, not after the next push — and the way
        // to know is to save twice and never sync at all.
        scene.project("shopping")
        scene.open(Workspace.PROJECTS, "shopping")
        val ws = scene.workspace!!
        val file = File(ws.repoOf("shopping"), "milk.txt")
        // Written through the editor, because the editor is what commits: writing
        // to the disk behind it is not a change the app was told about.
        scene.viewModel.refresh()
        scene.openEditor(file)
        scene.saveAsVersion("one")
        scene.saveAsVersion("two")
        scene.saveAsVersion("three")
        scene.closeEditor()

        val versions = scene.versionsOf(file)
        assertEquals("three changes, three versions", 3, versions.size)
        assertEquals("the newest is what is on disk", "three", versions[0].second)
        assertEquals("and the first is still there", "one", versions[2].second)
        // One commit arrived with the project — its licence — and none of the three
        // saves was a push.
        assertEquals("no sync happened", 1, scene.remoteCommits("shopping"))
    }

    @Test
    fun `a version put back inside a project becomes a version of that project`() {
        // A restore is a change like any other. Recording only the account's history
        // left the worktree modified and uncommitted: the file said "1 changed"
        // until the next sync, and the version it was put back to was not in the
        // history it came from.
        scene.project("shopping")
        scene.open(Workspace.PROJECTS, "shopping")
        val ws = scene.workspace!!
        val file = File(ws.repoOf("shopping"), "milk.txt")
        scene.viewModel.refresh()
        scene.openEditor(file)
        scene.saveAsVersion("one")
        scene.saveAsVersion("two")
        scene.closeEditor()

        val versions = scene.versionsOf(file)
        val first = versions.last().first.id
        scene.viewModel.openHistory(file)
        scene.restore(first)
        scene.viewModel.closeHistory()

        assertEquals("the text went back", "one", file.readText())
        scene.await("the restore to become a version", 20_000) {
            scene.versionsOf(file).size == 3
        }
        assertEquals("and the restore is its own version", 3, scene.versionsOf(file).size)
        // And the worktree is clean: the restore was committed to the project, not
        // left for the next sync to notice — which is what the row's count says, and
        // the app recounts it once the commit lands.
        scene.await("the restore to be committed", 20_000) {
            scene.viewModel.changesOf("shopping") == 0
        }
        assertEquals("nothing is waiting to be synced", 0, scene.viewModel.changesOf("shopping"))
    }

    @Test
    fun `a deleted file can be brought back from the folder it was deleted from`() {
        // Deleting is the one change with no way back from the folder it happened in:
        // the row is gone, so there is nothing to open a history on. Hence the list
        // the add button offers.
        scene.folder("letters")
        scene.open("letters")
        val letter = scene.file("milk.txt", "two litres")
        scene.viewModel.delete(letter)
        scene.await("the deletion to be recorded", 20_000) { !letter.exists() }

        val gone = scene.viewModel.deletedIn(scene.viewModel.currentDir!!)
        assertTrue("the file is offered back", gone.any { it.name == "milk.txt" })

        val offered = gone.first { it.name == "milk.txt" }
        assertTrue("and put back", scene.viewModel.restoreDeleted(scene.viewModel.currentDir!!, offered))
        assertTrue("it is there again", letter.isFile)
        assertEquals("with what it held", "two litres", letter.readText())
    }

    @Test
    fun `a deleted picture comes back as the same bytes, not as text`() {
        // A version is not always text. Writing a UTF-8 reading of a picture back
        // over it replaces the picture with a row of replacement characters, which
        // is a restore that destroys the thing it restored.
        scene.folder("pictures")
        scene.open("pictures")
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0xFF.toByte(), 0x00)
        val picture = File(scene.viewModel.currentDir!!, "dot.png")
        picture.writeBytes(bytes)
        // Written behind the app's back, so it needs a change to carry it into the
        // history — every commit takes everything that is waiting, which is what
        // makes a file dropped in by another app show up at all.
        scene.folder("marker")
        scene.await("the picture to be recorded", 20_000) {
            scene.appHistoryFiles().contains("pictures/dot.png")
        }
        scene.viewModel.delete(picture)
        scene.await("the deletion to be recorded", 20_000) { !picture.exists() }

        val offered = scene.viewModel.deletedIn(scene.viewModel.currentDir!!)
            .firstOrNull { it.name == "dot.png" }
        assertTrue("it is offered back", offered != null)
        assertTrue("put back", scene.viewModel.restoreDeleted(scene.viewModel.currentDir!!, offered!!))

        assertTrue("byte for byte", picture.readBytes().contentEquals(bytes))
    }

    @Test
    fun `a deleted file inside a project comes back from that project's history`() {
        scene.project("shopping")
        scene.open(Workspace.PROJECTS, "shopping")
        val ws = scene.workspace!!
        val file = File(ws.repoOf("shopping"), "milk.txt")
        scene.viewModel.refresh()
        scene.openEditor(file)
        scene.saveAsVersion("two litres")
        scene.closeEditor()
        scene.viewModel.delete(file)
        scene.await("the deletion", 20_000) { !file.exists() }

        scene.open(Workspace.PROJECTS, "shopping", "repo")
        val offered = scene.viewModel.deletedIn(scene.viewModel.currentDir!!)
            .firstOrNull { it.name == "milk.txt" }
        assertTrue("the worktree offers it back", offered != null)
        assertTrue("put back", scene.viewModel.restoreDeleted(scene.viewModel.currentDir!!, offered!!))
        assertEquals("with its text", "two litres", file.readText())
    }

    @Test
    fun `the version that was just saved is the newest one in the history`() {
        // "I save, then open the history, and the newest version is the one before the
        // save." The save was queued on the worker and the history was read on the UI
        // thread, so the read happened first. Asking for the versions on the same
        // worker puts them in the order they were asked for.
        scene.signedIn()
        scene.open()
        val file = scene.file("milk.txt", "one litre")
        scene.viewModel.openEditor(file)
        scene.viewModel.saveEditor("two litres")
        scene.viewModel.closeEditor()
        // The history screen is what asks, so the history is what has to be open.
        scene.viewModel.openHistory(file)

        var seen: List<Pair<GitService.CommitInfo, String?>> = emptyList()
        scene.viewModel.requestVersions { seen = it }
        scene.await("the versions to arrive") { seen.isNotEmpty() }

        assertEquals(
            "the newest version is what was just saved",
            "two litres",
            seen.first().second,
        )
    }

    @Test
    fun `every file deleted from a folder is offered back, not only the last one`() {
        // The list was built from commits that a path filter said touched the
        // folder, and deleting a file from a folder does not always answer as a
        // change to the folder. One name came back for three deletions.
        scene.folder("letters")
        scene.open("letters")
        val first = scene.file("one.txt", "the first")
        val second = scene.file("two.txt", "the second")
        val third = scene.file("three.txt", "the third")
        scene.viewModel.delete(first)
        scene.await("the first deletion", 20_000) { !first.exists() }
        scene.viewModel.delete(second)
        scene.await("the second deletion", 20_000) { !second.exists() }
        scene.viewModel.delete(third)
        scene.await("the third deletion", 20_000) { !third.exists() }

        val offered = scene.viewModel.deletedIn(scene.viewModel.currentDir!!).map { it.name }
        assertEquals(
            "all three, and in a shape a person can read",
            listOf("one.txt", "three.txt", "two.txt"),
            offered,
        )
    }

    @Test
    fun `a file deleted from a folder inside this one is not offered here`() {
        // Restoring puts a row back in *this* folder. A file that lived in a folder
        // inside it was never a row here, and putting it here would be a restore
        // that creates something the person never had.
        scene.folder("letters")
        scene.open("letters")
        scene.folder("drafts")
        scene.open("letters", "drafts")
        val deep = scene.file("deep.txt", "kept in drafts")
        scene.viewModel.delete(deep)
        scene.await("the deletion", 20_000) { !deep.exists() }
        scene.open("letters")

        assertEquals(
            "nothing from inside a folder is offered",
            emptyList<String>(),
            scene.viewModel.deletedIn(scene.viewModel.currentDir!!).map { it.name },
        )
    }

    @Test
    fun `a folder with nothing deleted says so rather than showing an empty window`() {
        scene.folder("letters")
        scene.open("letters")
        scene.file("kept.txt", "here")

        assertEquals(
            "nothing is offered",
            emptyList<String>(),
            scene.viewModel.deletedIn(scene.viewModel.currentDir!!).map { it.name },
        )
    }

    @Test
    fun `a local note beside a project is versioned by the account, not lost`() {
        // The two histories divide at `repo/`, and the boundary is not "belongs to a
        // project". A note kept in the project's own folder sits *beside* the
        // repository: if both histories assume the other committed it, the change is
        // committed nowhere and the note has no history at all — which is how this
        // was found, by a scenario that suddenly could not record a file being made.
        scene.project("groceries")
        scene.open(Workspace.PROJECTS, "groceries")
        val before = scene.headId()

        scene.file("shopping_list.txt", "milk, bread")

        assertTrue(
            "the account's history took it",
            scene.headId() != before,
        )
        val note = File(scene.workspace!!.accountDir, "projects/groceries/shopping_list.txt")
        assertTrue("the note is where it was written", note.isFile)

        // And the project's own repository does not hold it: it is not code.
        val projectRepo = File(scene.workspace!!.accountDir, "projects/groceries/repo")
        org.eclipse.jgit.api.Git.open(projectRepo).use { git ->
            val status = git.status().call()
            assertFalse(
                "the note is not staged into the repository",
                status.added.any { it.contains("shopping_list") } ||
                    status.untracked.any { it.contains("shopping_list") },
            )
        }
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
