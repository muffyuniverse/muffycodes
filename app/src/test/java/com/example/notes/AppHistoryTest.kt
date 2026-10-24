package com.example.notes

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The account's own history (§4) and the length it is kept to.
 *
 * These are the promises the app-wide history makes: a local file has versions, a
 * deleted folder can come back, a project's repository is not duplicated inside
 * it, and the length setting actually shortens the chain without touching a
 * single byte of the working tree.
 */
class AppHistoryTest {

    private val author = GitAuthor("Test", "test@example.com")
    private lateinit var root: File
    private lateinit var workspace: Workspace
    private lateinit var history: AppHistory

    @Before
    fun makeSpace() {
        root = File(System.getProperty("java.io.tmpdir"), "history-${System.nanoTime()}")
        workspace = Workspace(File(root, "muffyuniverse"))
        workspace.accountDir.mkdirs()
        workspace.projectsDir.mkdirs()
        history = AppHistory(workspace.accountDir) { author }
    }

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun anEditBecomesAVersionOfTheFile() {
        val file = File(workspace.accountDir, "ideas.txt")
        workspace.write(file, "first")
        history.commit("Created ideas.txt")
        workspace.write(file, "second")
        history.commit("Edited ideas.txt")

        val path = workspace.pathString(workspace.relative(file)!!)
        val versions = history.log(path)
        assertTrue("expected a version per save, got $versions", versions.size >= 2)
        assertEquals("second", history.fileAt(path, versions[0].id))
        assertEquals("first", history.fileAt(path, versions[1].id))
    }

    @Test
    fun aDeletedFolderCanBeBroughtBack() {
        val folder = File(workspace.accountDir, "Drafts").apply { mkdirs() }
        val file = File(folder, "list.txt").apply { writeText("milk") }
        history.commit("Created Drafts/")
        val path = workspace.pathString(workspace.relative(file)!!)
        val present = history.log(path).first().id

        folder.deleteRecursively()
        history.commit("Deleted Drafts/")

        assertEquals("milk", history.fileAt(path, present))
    }

    @Test
    fun aProjectsRepositoryIsNotStoredTwice() {
        workspace.createProjectFolders("Shopping").getOrThrow()
        val repo = workspace.repoOf("Shopping")
        File(repo, "Milk.txt").writeText("milk")

        history.commit("Nothing much")

        // The worktree is ignored, so its files have no versions in this history.
        assertTrue(history.log("projects/Shopping/${Workspace.REPO}/Milk.txt").isEmpty())
        // A local file beside it does.
        File(File(workspace.projectsDir, "Shopping"), "ideas.txt").writeText("later")
        history.commit("Created ideas.txt")
        assertFalse(history.log("projects/Shopping/ideas.txt").isEmpty())
    }

    @Test
    fun nothingIsCommittedWhenNothingChanged() {
        workspace.write(File(workspace.accountDir, "a.txt"), "x")
        assertTrue(history.commit("Created a.txt") != null)
        assertNull("an empty commit must not be made", history.commit("Again"))
    }

    @Test
    fun theHistoryIsShortenedAndTheFilesAreUntouched() {
        val file = File(workspace.accountDir, "count.txt")
        repeat(12) { i ->
            workspace.write(file, "value $i")
            history.commit("Edited count.txt")
        }
        val before = history.log().size
        assertTrue("expected a dozen versions, got $before", before >= 12)

        // The setting is applied at the next change, exactly as the design says.
        workspace.write(file, "value last")
        history.commit("Edited count.txt", keep = 5)

        val after = history.log()
        assertEquals("the oldest versions are gone; left ${after.size}", 5, after.size)
        assertEquals("the file's content is untouched", "value last", file.readText())
    }

    @Test
    fun keepingEverythingIsTheDefault() {
        val file = File(workspace.accountDir, "count.txt")
        repeat(8) { i ->
            workspace.write(file, "value $i")
            history.commit("Edited count.txt", keep = 0)
        }
        assertEquals(8, history.log().size)
    }

    @Test
    fun restoringPutsAnOldVersionBack() {
        val file = File(workspace.accountDir, "note.txt")
        workspace.write(file, "original")
        history.commit("Created note.txt")
        workspace.write(file, "changed")
        history.commit("Edited note.txt")

        val path = workspace.pathString(workspace.relative(file)!!)
        val original = history.log(path).last().id
        assertTrue(history.restore(path, original))

        assertEquals("original", file.readText())
    }

    @Test
    fun theMessageIsWrittenFromTheNamesThatChanged() {
        assertEquals("Changed Milk.txt", CommitMessage.sync(listOf("Milk.txt")))
        assertEquals(
            "Changed a.txt, b.txt, c.txt +2 more",
            CommitMessage.sync(listOf("a.txt", "b.txt", "c.txt", "d.txt", "e.txt")),
        )
        // A folder path is named by its last part: that is what the eye scans.
        assertEquals("Changed Milk.txt, Photos", CommitMessage.sync(listOf("d/Milk.txt", "d/Photos/")))
    }
}
