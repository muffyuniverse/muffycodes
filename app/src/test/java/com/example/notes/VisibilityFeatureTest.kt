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
        // Written straight to disk, the way another app or a cable would. The root is the projects
        // list now, so what it shows is exactly what is on disk there.
        // A project has a repository in it, because that is what makes it a project; a bare folder
        // at the root is only a folder, and says as much.
        File(scene.workspace!!.accountDir, "notes.txt").writeText("mine")
        File(scene.workspace!!.accountDir, "Shopping").mkdirs()
        File(scene.workspace!!.accountDir, "Shopping/.git").mkdirs()
        File(scene.workspace!!.accountDir, "loose").mkdirs()
        scene.viewModel.refresh()

        assertEquals(listOf("loose", "Shopping", "notes.txt"), scene.names())
    }

    @Test
    fun `a name that begins with a dot is the app's, and is never drawn`() {
        val dir = scene.viewModel.currentDir!!

        File(dir, ".gitignore").writeText("a")
        File(dir, ".muffy").mkdirs()
        File(dir, ".anything").writeText("b")
        File(dir, "shown.txt").writeText("c")
        scene.viewModel.refresh()

        assertEquals(listOf("shown.txt"), scene.names())
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
        assertEquals(listOf("zebra", "apple.txt", "milk.txt"), scene.names())
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

        val offered = (target.parentFile!!.listFiles() ?: emptyArray())
            .filter { it.isDirectory && it.name != target.name }
            .filterNot { ws.isHidden(it.name) || ws.isStructural(it) }
            .map { it.name }

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
