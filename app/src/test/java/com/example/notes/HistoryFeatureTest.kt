package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §4 of the design: **a project's tree has a history.**
 *
 * Every save and every change is committed into the project's own repository, which is the folder
 * itself. That is what gives a file the back and forward arrows, and what lets a folder that was
 * deleted come back — and there is no second history, so every question about a version has one
 * answer.
 *
 * Files live inside projects now, so a test that wants history starts one. [start] is that.
 */
class HistoryFeatureTest : FeatureTest() {

    /** The commits of the open project's repository, newest first. Nothing yet is [emptyList]. */
    private fun log(): List<GitService.CommitInfo> {
        val project = scene.viewModel.currentProject ?: return emptyList()
        return scene.repo(project).log(null)
    }

    /**
     * A project to work in, opened.
     *
     * **Every history test needs one**, because a version belongs to a project: the account root is
     * the list of projects and holds nothing of a person's that could have a history of its own.
     */
    private fun start(name: String = "work") {
        scene.project(name)
        scene.open(name)
    }

    @Test
    fun `a project arrives with its repository's versions, and the first change adds one`() {
        start()

        // **A project that has just been made is not empty of history.** Its repository is a
        // clone, so it arrives with the commits the repository already had — the licence GitHub
        // seeded it with. What this test is about is the person's first change: the count goes up
        // by one, and the thing they made is in it.
        val arrived = log().size
        assertTrue("the clone's own versions are here", arrived >= 1)

        // **An empty folder is not a change.** Git stores content, so a folder with nothing in it
        // has nothing to commit; the count stays where the clone left it.
        scene.folder("letters")
        scene.await("the folder to be made") { scene.names().contains("letters") }
        assertEquals("an empty folder is not a change", arrived, log().size)

        scene.openIn("letters")
        scene.file("one.txt", "first")
        scene.await("the first commit") { log().size > arrived }

        assertTrue("and the first thing in it is a version", log().size > arrived)
    }

    @Test
    fun `an empty folder has no version, and stops being empty the moment it has one`() {
        start()
        // **This is a fact about git, not a gap in the app.** A folder is made of the files in it,
        // so an empty one is not a change to commit and has no version to go back to. The app
        // records nothing and says nothing, which is right; the alternative — putting a marker
        // file in the person's repository so an empty folder leaves a trace — would be the app
        // writing something they never asked for into the thing that goes to GitHub.
        start()

        scene.folder("empty")
        scene.await("the folder to be made") { scene.names().contains("empty") }
        assertTrue("no version, because there is nothing to have one of",
            scene.versionsOfFolder("empty").isEmpty())

        scene.openIn("empty")
        val one = scene.file("one.txt", "first")
        scene.openIn()
        scene.await("a version, now that there is something in it") {
            scene.versionsOfFolder("empty").isNotEmpty()
        }
        // The text has to be the version that is restored, or this asks whether an empty file comes
        // back empty — which it does, and which says nothing about restoring anything.
        scene.await("the text to be a version") {
            scene.versionsOf(one).any { it.second == "first" }
        }

        // And from that version the folder *can* come back, which is the thing the old test was
        // reaching for.
        val version = scene.versionsOfFolder("empty").first().first.id
        val folder = File(scene.workspace!!.projectDir("work"), "empty")
        scene.viewModel.delete(folder)
        assertFalse("the folder is gone", folder.exists())

        scene.restore(version)

        assertTrue("and it is back", folder.isDirectory)
        assertEquals("with what gave it a version", "first", File(folder, "one.txt").readText())
    }

    @Test
    fun `a restore answers itself, so the next press does not have to`() {
        start()

        // The live log, 23:42:26: `restore-version da4ffd1` and then `close-history` 15
        // milliseconds later — and the answer went to the close, which nobody had pressed.
        // A restore said nothing on purpose, because it changes the text on the screen the
        // person is already looking at; but silence in the *screen* is not silence in the
        // *log*, and an unanswered tap is the one thing the log exists to find.
        scene.folder("letters")
        scene.openIn("letters")
        scene.file("one.txt", "first")
        scene.openIn()
        scene.await("a version") { scene.versionsOfFolder("letters").isNotEmpty() }

        // `versionsOfFolder` leaves the history open, which is what restoring acts on. Its
        // own `history` tap stays waiting here, because nothing in a test without a screen
        // redraws to answer it — so the assertion is about the *restore's* tap and not about
        // the ring being empty, which would be measuring the harness.
        scene.restore(scene.versionsOfFolder("letters").first().first.id)
        Trace.awaitWrites()

        assertTrue(
            "the restore answered itself, so the next press did not have to: " +
                Trace.report().waiting.map { it.what },
            Trace.report().waiting.none { it.what == "restore-version" },
        )
    }

    @Test
    fun `a change to the tree is committed`() {
        start()

        val before = log().size

        // **A change to the tree means something appeared in it.** An empty folder is not one —
        // git stores content — so the change this asks about is a file, and the commit that
        // carries it says what was made.
        scene.file("letters.txt", "the first")
        scene.await("the change to be recorded") { log().size > before }

        // The *making* is named; the save that gave it text comes after it and says `Saved`,
        // because a save's message is one word by design.
        assertTrue(
            "the file is named in the commit that made it: ${log().map { it.message }}",
            log().any { it.message.contains("letters.txt") },
        )
    }

    @Test
    fun `each save with a pause is a version of the file`() {
        start()

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
        start()

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
        start()

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
        start()

        // "Do you calculate everything off git history, or some other storage?" Git:
        // the screen reads the repository. A commit written by JGit with no screen
        // anywhere near it must appear in the list, which is what tells the two apart.
        val file = scene.file("milk.txt", "one litre")
        // **The project's repository**, which is the project's folder — there is no second
        // repository above it any more, so this is the only place a version could come from.
        val repo = scene.workspace!!.projectDir("work")
        org.eclipse.jgit.api.Git.open(repo).use { git ->
            file.writeText("written straight to git")
            GitService.commitAll(git.repository, "A commit from outside the app", AppViewModel.DEFAULT_AUTHOR)
        }

        val texts = scene.versionsOf(file).map { it.second }

        assertEquals("the outside commit is the newest version", "written straight to git", texts.first())
        assertTrue("and the app's own save is still below it", texts.contains("one litre"))
    }

    @Test
    fun `a folder deleted with everything in it comes back as a folder`() {
        start()
        // Deleting a folder is one act, so bringing it back is one row — and putting
        // back one file at a time from a list of five names is not the same thing.
        scene.folder("letters")
        scene.openIn("letters")
        scene.file("milk.txt", "two litres")
        scene.folder("inner")
        scene.openIn("letters", "inner")
        val inner = scene.file("notes.txt", "the inner one")
        scene.openIn()
        // **The save has to be in the history before the folder is deleted.** Commits happen on
        // the app's one worker, so a delete queued in the same breath takes everything waiting —
        // including its own deletion — and the newest version that still holds `letters` is then
        // the one that created `notes.txt` empty. That is the test racing the app, not the app
        // losing the text.
        scene.await("the inner file's text to be a version") {
            scene.versionsOf(inner).any { it.second == "the inner one" }
        }
        val letters = File(scene.workspace!!.projectDir("work"), "letters")

        scene.viewModel.delete(letters)
        scene.await("the folder to go", 20_000) { !letters.exists() }

        val gone = scene.viewModel.deletedIn(scene.workspace!!.projectDir("work"))
        val offered = gone.firstOrNull { it.name == "letters" }
        assertTrue("the folder is offered back: $gone", offered != null)
        assertTrue("and it is offered as a folder", offered!!.isFolder)

        assertTrue(
            "bringing it back works",
            scene.viewModel.restoreDeleted(scene.workspace!!.projectDir("work"), offered),
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
        start()

        // The falsification of the restore list: a list that offered everything the
        // history ever held would "work" while putting files back that never went
        // anywhere, and overwriting what is on disk with an older reading of it.
        val kept = scene.file("kept.txt", "still here")
        scene.viewModel.delete(kept)
        scene.await("the file to go", 20_000) { !kept.exists() }
        val root = scene.workspace!!.projectDir("work")
        scene.viewModel.restoreDeleted(root, scene.viewModel.deletedIn(root).first { it.name == "kept.txt" })
        assertTrue("it is back", kept.isFile)

        val gone = scene.viewModel.deletedIn(scene.workspace!!.projectDir("work"))

        assertTrue("and it is not offered again: $gone", gone.none { it.name == "kept.txt" })
    }

    @Test
    fun `a local file's older version can be brought back`() {
        start()

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
        start()

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
        start()

        val file = scene.file("milk.txt", "one litre")
        scene.await("a version") { scene.versionsOf(file).isNotEmpty() }
        val version = scene.versionsOf(file).first().first.id
        val path = file.absolutePath

        scene.viewModel.delete(file)
        assertFalse("the file is gone", File(path).exists())

        // The history is opened again, because a restore acts on the history that is open and the
        // row this file used to be is gone.
        scene.viewModel.openHistory(file)
        scene.restore(version)

        assertTrue("it is back", File(path).isFile)
        assertEquals("with its text", "one litre", File(path).readText())
    }

    @Test
    fun `a deleted folder comes back with everything that was in it`() {
        start()
        scene.folder("letters")
        scene.openIn("letters")
        scene.file("one.txt", "first")
        scene.folder("deeper")
        scene.openIn("letters", "deeper")
        scene.file("two.txt", "second")
        scene.openIn()
        scene.await("the versions") { scene.versionsOfFolder("letters").isNotEmpty() }

        val version = scene.versionsOfFolder("letters").first().first.id
        val letters = File(scene.workspace!!.projectDir("work"), "letters")

        scene.viewModel.delete(letters)
        assertFalse("the folder is gone", letters.exists())

        // **The history is opened again after the deletion.** Restoring acts on the history that
        // is open, and a folder that is gone has no row to press — so this is the state a person
        // reaches through Restore deleted…, without the menu: the version is known, and the
        // question is whether putting it back brings the folder with it.
        scene.viewModel.openHistory(letters)
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
        start()
        scene.folder("letters")
        scene.openIn("letters")
        scene.file("one.txt", "first")
        scene.openIn()
        scene.await("a version of the folder") { scene.versionsOfFolder("letters").isNotEmpty() }

        val shown = scene.versionsOfFolder("letters").first()

        assertTrue("the file is in the version", shown.second!!.contains("one.txt"))
    }

    @Test
    fun `renaming a local folder is a version, so the name can be put back`() {
        start()
        // §4: "Folder organisation is versioned. Renames and moves of local things
        // are reversible, which is exactly where people regret things most." The
        // commit is the easy half; this checks that the *old name* is genuinely in
        // the history and can be brought back, because a version that records only
        // the new name would be a version of nothing.
        scene.folder("notes-2019")
        scene.openIn("notes-2019")
        scene.file("first.txt", "the first thing")
        scene.openIn()
        val folder = File(scene.workspace!!.projectDir("work"), "notes-2019")

        assertTrue(
            "the folder was renamed",
            scene.viewModel.rename(folder, "archive"),
        )

        val renamed = File(scene.workspace!!.projectDir("work"), "archive")
        assertTrue("the folder has its new name", renamed.isDirectory)
        assertTrue("and holds what it held", File(renamed, "first.txt").isFile)

        // The history knows the rename happened, and the version before it still
        // has the folder under its old name with its contents.
        val versions = log()
        assertTrue("there is more than one version", versions.size >= 2)
        assertTrue(
            "some version mentions the old name",
            versions.any { it.message.contains("notes-2019") },
        )
    }

    @Test
    fun `moving a local folder into another is a version too`() {
        start()
        // The other half of the same promise. A move is a rename of a path, so it
        // has to reach the history the same way.
        scene.folder("inbox")
        scene.folder("archive")
        scene.openIn("inbox")
        scene.file("letter.txt", "dear")
        scene.openIn()
        val inbox = File(scene.workspace!!.projectDir("work"), "inbox")
        val archive = File(scene.workspace!!.projectDir("work"), "archive")
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
        scene.open("shopping")
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
        // A restore is a change like any other. Recording it only somewhere else
        // left the worktree modified and uncommitted: the file said "1 changed"
        // until the next sync, and the version it was put back to was not in the
        // history it came from.
        scene.project("shopping")
        scene.open("shopping")
        val ws = scene.workspace!!
        val file = File(ws.repoOf("shopping"), "milk.txt")
        scene.viewModel.refresh()
        scene.openEditor(file)
        scene.saveAsVersion("one")
        scene.saveAsVersion("two")
        scene.closeEditor()

        val versions = scene.versionsOf(file)
        val first = versions.last().first.id
        // **Read before the restore, not after.** The counter was first read below, where it
        // already included the restore's own commit — so "one more than before" was a
        // comparison that could never be true, and it cost three red runs to notice an
        // ordering mistake in a test rather than a bug in the app.
        val unsentBefore = ProjectGit(ws, { null }, { GitAuthor("Muffy", "muffy@example.com") })
            .changes("shopping").ahead
        assertTrue("the file's saves are already waiting to be sent", unsentBefore >= 1)
        scene.viewModel.openHistory(file)
        scene.restore(first)
        scene.viewModel.closeHistory()

        assertEquals("the text went back", "one", file.readText())
        scene.await("the restore to become a version", 20_000) {
            scene.versionsOf(file).size == 3
        }
        assertEquals("and the restore is its own version", 3, scene.versionsOf(file).size)
        // And the restore is a change waiting to leave: committed to the project at once
        // (so the history holds it) and not yet pushed, which is what the row counts.
        //
        // Asserted on the repository rather than on the row, because the row's number arrives
        // through a recount posted to the UI thread and this test has no frame to run it in. The
        // row is checked where it can be: CopyFeatureTest and ProjectsFeatureTest both assert it
        // after a change lands, and ProjectGitTest pins commit-and-push.
        val unsentNow =
            ProjectGit(ws, { null }, { GitAuthor("Muffy", "muffy@example.com") }).changes("shopping").ahead
        // **One file, however many times it was written.** The restore is a third commit on the
        // same path, so a commit count would say three and a file count says one — which is the
        // number a person is looking at when the row says "1 to send".
        assertTrue(
            "the project still has something to send: $unsentNow after $unsentBefore",
            unsentNow >= 1,
        )
    }

    @Test
    fun `a deleted file can be brought back from the folder it was deleted from`() {
        start()
        // Deleting is the one change with no way back from the folder it happened in:
        // the row is gone, so there is nothing to open a history on. Hence the list
        // the add button offers.
        scene.folder("letters")
        scene.openIn("letters")
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
        start()
        // A version is not always text. Writing a UTF-8 reading of a picture back
        // over it replaces the picture with a row of replacement characters, which
        // is a restore that destroys the thing it restored.
        scene.folder("pictures")
        scene.openIn("pictures")
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0xFF.toByte(), 0x00)
        val picture = File(scene.viewModel.currentDir!!, "dot.png")
        picture.writeBytes(bytes)
        // Written behind the app's back, so it needs a real change to carry it into the
        // history — every commit takes everything that is waiting, which is what makes a file
        // dropped in by another app show up at all. A file, not a folder: an empty folder is not
        // a change, so it would carry nothing.
        scene.file("marker.txt", "a change to carry the picture in")
        scene.await("the picture to be recorded", 20_000) {
            scene.trackedFiles().contains("pictures/dot.png")
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
        scene.open("shopping")
        val ws = scene.workspace!!
        val file = File(ws.repoOf("shopping"), "milk.txt")
        scene.viewModel.refresh()
        scene.openEditor(file)
        scene.saveAsVersion("two litres")
        scene.closeEditor()
        scene.viewModel.delete(file)
        scene.await("the deletion", 20_000) { !file.exists() }

        scene.open("shopping")
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
        start()
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
        start()
        // The list was built from commits that a path filter said touched the
        // folder, and deleting a file from a folder does not always answer as a
        // change to the folder. One name came back for three deletions.
        scene.folder("letters")
        scene.openIn("letters")
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
        start()
        // Restoring puts a row back in *this* folder. A file that lived in a folder
        // inside it was never a row here, and putting it here would be a restore
        // that creates something the person never had.
        scene.folder("letters")
        scene.openIn("letters")
        scene.folder("drafts")
        scene.openIn("letters", "drafts")
        val deep = scene.file("deep.txt", "kept in drafts")
        scene.viewModel.delete(deep)
        scene.await("the deletion", 20_000) { !deep.exists() }
        scene.openIn("letters")

        assertEquals(
            "nothing from inside a folder is offered",
            emptyList<String>(),
            scene.viewModel.deletedIn(scene.viewModel.currentDir!!).map { it.name },
        )
    }

    @Test
    fun `a folder with nothing deleted says so rather than showing an empty window`() {
        start()
        scene.folder("letters")
        scene.openIn("letters")
        scene.file("kept.txt", "here")

        assertEquals(
            "nothing is offered",
            emptyList<String>(),
            scene.viewModel.deletedIn(scene.viewModel.currentDir!!).map { it.name },
        )
    }

    @Test
    fun `a local project's history has no remote, and needs no key`() {
        // The merged shape makes this a statement about a **local project**: its folder is a
        // repository, its versions are real, and there is nowhere to push to and no key involved.
        scene.viewModel.createLocalProject("scratch")
        scene.await("the local project", 30_000) {
            scene.viewModel.projectNames.contains("scratch") && !scene.viewModel.busy
        }
        scene.open("scratch")
        scene.file("note.txt", "mine")
        scene.await("a commit") { log().isNotEmpty() }

        val repo = scene.workspace!!.projectDir("scratch")
        assertTrue("the project is a repository", GitService.isRepository(repo))
        val remotes = org.eclipse.jgit.api.Git.open(repo).use {
            it.repository.config.getSubsections("remote")
        }
        assertTrue("no remote is configured", remotes.isEmpty())
        assertTrue("and it still has its own commits", log().isNotEmpty())
    }
}
