package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        // Written straight to disk, the way another app or a cable would. The root is the projects
        // list now, so what it shows is exactly what is on disk there.
        // A project has a repository in it, because that is what makes it a project; a bare folder
        // at the root is only a folder, and says as much.
        File(scene.workspace!!.accountDir, "notes.txt").writeText("mine")
        File(scene.workspace!!.accountDir, "Shopping").mkdirs()
        File(scene.workspace!!.accountDir, "Shopping/.git").mkdirs()
        File(scene.workspace!!.accountDir, "loose").mkdirs()
        scene.viewModel.refresh()

        // **The system project is at the root too**, because it is a project like any other — it
        // holds the settings that belong to the whole account, and a row is how a person reaches
        // them. Folders sort before files, then by name, so it lands after `Shopping`.
        // **`.muffy` is a folder at the root now**, because report 28 asked for the app's own things
        // to be visible and locked rather than hidden. Folders sort first by lowercase name.
        assertEquals(listOf(".muffy", "loose", "Shopping", "system", "notes.txt"), scene.names())
    }

    @Test
    fun `the app's own folder is shown, and only git's store and the folder marker are not`() {
        // **Report 28, and it changed this test's answer.** "Can you make all hidden files visible
        // but readonly? with system lock" — an app that hides its own bookkeeping from the person
        // whose phone it is has made itself unaccountable, so `.muffy/` is a row now.
        //
        // What stays hidden is two things, and neither is information: `.git` is git's own store
        // (every tool treats it as opaque), and `.gitkeep` exists only so git can record an empty
        // folder. A person's own dot-file is *their* file, so it is shown too — this was never a
        // rule about dots, it was a rule about the app's things, and the app's things are now
        // visible and locked instead of invisible.
        val dir = scene.viewModel.currentDir!!

        File(dir, ".git").mkdirs()
        File(dir, ".gitkeep").writeText("")
        File(dir, ".muffy").mkdirs()
        File(dir, ".anything").writeText("b")
        File(dir, "shown.txt").writeText("c")
        scene.viewModel.refresh()

        // Folders first, then files, each by lowercase name - so `.muffy` comes before `system`.
        assertEquals(listOf(".muffy", "system", ".anything", "shown.txt"), scene.names())
    }

    @Test
    fun `a file the app keeps is shown, opens, and refuses to be written`() {
        // **Visible but readonly**, which is the whole of report 28. Reading is the point: a person
        // debugging their own phone should see what the app recorded. Writing is refused because
        // every one of these files is a flag the app reads back.
        val ws = scene.workspace!!
        val state = File(ws.accountDir, Workspace.STATE_DIR).apply { mkdirs() }
        val flag = File(state, "locked.json")
        flag.writeText("[]")

        assertTrue("the folder is shown", ws.list(ws.accountDir).any { it.name == Workspace.STATE_DIR })
        assertTrue("the file is the app's own", ws.isAppFlag(flag))
        assertFalse("so it cannot be written", ws.write(flag, "[\"secret.txt\"]"))
        assertEquals("and the app's own content is untouched", "[]", flag.readText())
        assertNotNull("while it can still be read", ws.read(flag))
    }

    @Test
    fun `the record's folder is the app's, and cannot be renamed, moved or deleted`() {
        val project = File(scene.workspace!!.accountDir, "Shopping").apply { mkdirs() }
        val records = File(project, AgentLog.DIR).apply { mkdirs() }

        assertTrue("agent/ is structural", scene.workspace!!.isStructural(records))
        assertFalse("a project is not", scene.workspace!!.isStructural(project))
        assertFalse("a person's file is not", scene.workspace!!.isStructural(
            File(scene.workspace!!.accountDir, "notes.txt")
        ))
    }

    @Test
    fun `the record's folder in a project is structural and is not a thing to move`() {
        // **The project's own folder is the person's.** It used to hold a repository folder the
        // app owned, and that was the structural thing here; it is gone, because a project *is*
        // the repository. What the app still owns inside a project is the record.
        scene.project("Shopping")
        val records = File(scene.workspace!!.projectDir("Shopping"), AgentLog.DIR).apply { mkdirs() }

        assertTrue("the record's folder is the app's", scene.workspace!!.isStructural(records))
        assertFalse(
            "and the project's folder is not",
            scene.workspace!!.isStructural(scene.workspace!!.projectDir("Shopping")),
        )

        // The name of this test says "not a thing to move", so the move is what it has
        // to try: the flag alone would still pass if delete and move stopped asking.
        val elsewhere = File(scene.workspace!!.accountDir, "elsewhere").apply { mkdirs() }
        val refused = scene.workspace!!.move(records, elsewhere)
        assertTrue("and moving it is refused", refused.isFailure)
        assertTrue("with the record still where it was", records.isDirectory)
    }

    @Test
    fun `everything else inside a project is a file a person made`() {
        scene.project("Shopping")
        scene.open("Shopping")
        scene.folder("ideas")

        val project = File(scene.workspace!!.accountDir, "Shopping")
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

        // Called for in lowercase, because that is how the app writes a name it makes.
        assertEquals(listOf(".muffy", "system", "zebra", "apple.txt", "milk.txt"), scene.names())
    }

    @Test
    fun `a file the app keeps cannot be renamed, moved or deleted, even by name`() {
        // **The refusal moved with the rule.** This used to be about a dot-name, because a dot-name
        // was the app's. Report 28 made the app's things visible instead of hidden, so the thing
        // that must be refused is no longer "starts with a dot" but "is one of the app's own files" -
        // and a person's own `.notes` is theirs to rename now.
        val ws = scene.workspace!!
        val state = File(ws.accountDir, Workspace.STATE_DIR).apply { mkdirs() }
        val flag = File(state, "locked.json").apply { writeText("[]") }

        assertTrue("renaming is refused", scene.viewModel.rename(flag, "shown.txt").not())
        assertTrue("deleting is refused", scene.viewModel.delete(flag).not())
        assertTrue("the file is untouched", flag.isFile)

        // And a person's own dot-file is not the app's: it can be renamed.
        val dir = scene.viewModel.currentDir!!
        val theirs = File(dir, ".notes").apply { writeText("mine") }
        assertTrue("their own dot-file can be renamed", scene.viewModel.rename(theirs, "notes.txt"))
    }

    @Test
    fun `a structural folder cannot be renamed or deleted either`() {
        val project = File(scene.workspace!!.accountDir, "Shopping").apply { mkdirs() }
        val records = File(project, AgentLog.DIR).apply { mkdirs() }

        assertTrue("renaming is refused", scene.viewModel.rename(records, "elsewhere").not())
        assertTrue("deleting is refused", scene.viewModel.delete(records).not())
        assertTrue("it is still there", records.isDirectory)
    }

    @Test
    fun `search covers a project whole tree, however deep`() {
        scene.project("Shopping")
        scene.open("Shopping")
        scene.folder("letters")
        scene.open("Shopping", "letters")
        scene.folder("2026")
        scene.open("Shopping", "letters", "2026")
        scene.file("note.txt", "an appointment")

        scene.viewModel.query = "appointment"

        assertTrue(
            "the buried file is found by its text",
            scene.viewModel.visibleEntries.any { it.name == "note.txt" },
        )
    }

    @Test
    fun `search offers nothing the app owns, and does find what it should`() {
        scene.project("Shopping")
        scene.open("Shopping")
        scene.file("README-ish.txt", "index")

        scene.workspace!!.write(File(scene.workspace!!.repoOf("Shopping"), "muffy.txt"), "muffy")
        scene.viewModel.query = "muffy"
        scene.viewModel.refresh()

        // **The old name of this test was wrong, and the wrongness hid the bug.** It
        // said "search never reaches into the worktree", which is the opposite of what
        // the app does and should do — the worktree is inside the project, so it is
        // searched. What search must never reach is what the *app* owns: `.muffy/`,
        // `.git/`, the markers. And the positive half has to come first, because
        // "nothing from .muffy was offered" passes when the search offers nothing at
        // all, which is the other way this test could be vacuous.
        assertTrue(
            "the search does find something, so the next assertion means something: " +
                scene.viewModel.visibleEntries.map { it.name },
            scene.viewModel.visibleEntries.any { it.name == "muffy.txt" },
        )
        assertTrue(
            "and nothing from .muffy or .git is offered",
            scene.viewModel.visibleEntries.none {
                it.file.path.contains("/.muffy/") || it.file.path.contains("/.git/")
            },
        )
    }

    @Test
    fun `search is scoped to the project that is open, because the list is the index`() {
        scene.project("Shopping")
        scene.folder("letters")
        scene.open("Shopping", "letters")
        scene.file("note.txt", "an appointment")
        scene.open("Shopping")

        // A second project, with a file that would match the same word. The test's
        // name is about *scoping*, and half of scoping is what must not be found.
        scene.project("Work")
        scene.open("Work")
        scene.file("appointment-elsewhere.txt", "appointment")

        scene.open("Shopping")
        scene.viewModel.query = "appointment"

        assertTrue(
            "a file deeper in this project is found",
            scene.viewModel.visibleEntries.any { it.name == "note.txt" },
        )
        assertTrue(
            "and the other project's file is not: " +
                scene.viewModel.visibleEntries.map { it.name },
            scene.viewModel.visibleEntries.none { it.name == "appointment-elsewhere.txt" },
        )
    }

    @Test
    fun `the app's own folders are not offered as a place to move something`() {
        // §3: "Nothing the app owns appears in a file list, a search result or a
        // move picker, and no command can target one. That is a guarantee, not a
        // convention."
        //
        // The picker used to re-implement the rule as `!name.startsWith(".")`, which
        // hid the dot-names but still offered `projects/` — so the guarantee held
        // for one of the two ways the app owns a folder. It now asks Workspace.
        val ws = scene.workspace!!
        val target = scene.file("move_me.txt")

        val project = File(ws.accountDir, "Shopping").apply { mkdirs() }
        val records = File(project, AgentLog.DIR).apply { mkdirs() }
        assertTrue("agent/ is structural", ws.isStructural(records))
        assertFalse("an ordinary file is not", ws.isStructural(target))

        // **The picker's own rule, asked of the app rather than re-implemented here.** This test
        // used to rebuild the filter by hand, which meant it could agree with a broken picker; it
        // asks `canHoldFiles`, the function the dialog actually calls.
        val offered = (target.parentFile!!.listFiles() ?: emptyArray())
            .filter { it.isDirectory && it.name != target.name }
            .filter { scene.viewModel.canHoldFiles(it) }
            .map { it.name }

        // **Still not a destination, and now for a stated reason.** Report 28 made `.muffy` a row, so
        // it is visible - but "shown" and "somewhere to put a person's file" are different questions.
        // The app's own folder is not a place a document goes, which is why the move command refuses
        // it as well as the picker not offering it.
        assertFalse(".muffy is not a destination", offered.contains(".muffy"))
        assertFalse("nor is the record's folder", offered.contains(AgentLog.DIR))
    }

    @Test
    fun `a move into a folder the app owns is refused by the command, not only hidden`() {
        // The picker is one guard; the command is the other. §3 promises both.
        val ws = scene.workspace!!
        val target = scene.file("move_me.txt")
        // The app's own folder inside a project: the record's. There is nothing structural at the
        // root any more, because the root is the person's list of projects.
        val project = File(ws.accountDir, "Shopping").apply { mkdirs() }
        val records = File(project, AgentLog.DIR).apply { mkdirs() }

        val refused = scene.viewModel.move(target, records)

        assertFalse("the command refused", refused)
        assertTrue("and the file is where it was", target.exists())
        assertTrue("with a message saying why", !scene.viewModel.message.isNullOrBlank())
    }
}
