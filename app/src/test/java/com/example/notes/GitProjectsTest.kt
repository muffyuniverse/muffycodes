package com.example.notes

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A folder used as a git project: making one, committing to it, reading its
 * history back, and going back to an older version.
 *
 * Every test starts from an empty directory of its own and leaves nothing
 * behind, so the tests know nothing about each other and can be run in any
 * order or at the same time.
 *
 * The parts that talk to a real remote (pushing, pulling, cloning) are not
 * here: they need the network and an account, and a test that quietly depends
 * on either is a test that fails for reasons that have nothing to do with the
 * code.
 */
class GitProjectsTest {

    private val author = GitAuthor("Muffy World", "muffyworld365@gmail.com")
    private val made = mutableListOf<File>()

    /** A fresh directory, remembered so that it is removed even if a test fails. */
    private fun temporaryDirectory(): File {
        val dir = File(
            System.getProperty("java.io.tmpdir"),
            "git-projects-${System.nanoTime()}",
        )
        dir.mkdirs()
        made.add(dir)
        return dir
    }

    private fun projectsIn(dir: File) = GitProjects(
        rootDir = dir,
        tokenProvider = { null },
        authorProvider = { author },
    )

    /**
     * Given a repository that already holds one commit per item, where each item
     * is a file and the content to write to it for that commit.
     */
    private fun repositoryWith(vararg commits: Map<String, String>): Pair<File, GitProjects> {
        val dir = temporaryDirectory()
        val git = projectsIn(dir)
        git.initRepository(null)
        commits.forEachIndexed { index, files ->
            files.forEach { (path, content) -> File(dir, path).writeText(content) }
            assertNotNull(
                "the setup for commit ${index + 1} must really commit something",
                git.commit(null, "commit ${index + 1}").getOrNull(),
            )
        }
        return dir to git
    }

    @After
    fun removeWhatWasMade() {
        made.forEach { it.deleteRecursively() }
    }

    // ---- making a repository ----------------------------------------------

    @Test
    fun `a plain folder is not a repository until it is made into one`() {
        // Given a folder that is not a git project
        val dir = temporaryDirectory()
        val git = projectsIn(dir)

        // When nothing has been done to it
        val before = git.isRepository(null)

        // Then it is not a repository, and making it one really changes that
        assertFalse(before)
        assertTrue(git.initRepository(null).isSuccess)
        assertTrue(git.isRepository(null))
    }

    @Test
    fun `a new repository starts with one commit that has nothing in it`() {
        // Given a folder just made into a repository
        val dir = temporaryDirectory()
        val git = projectsIn(dir)
        git.initRepository(null)

        // When its history is read
        val history = git.history(null, null)

        // Then there is exactly one commit and it is empty. This is on purpose:
        // a repository with no commit has no branch to be on, so the app makes
        // one to stand on.
        //
        // Worth a decision rather than a test, though: this empty commit ends up
        // on GitHub on the first sync, where it is noise in a history that
        // otherwise only holds files somebody wrote.
        assertEquals(listOf("Initial commit"), history.map { it.message })
        assertTrue("the starting commit should be empty", git.treeAt(null, null, history.first().id).isEmpty())
    }

    // ---- committing --------------------------------------------------------

    @Test
    fun `committing with nothing changed makes no commit`() {
        // Given a repository with one committed file
        val (_, git) = repositoryWith(mapOf("hello.md" to "version one\n"))

        // When committing again without touching anything
        val id = git.commit(null, "nothing to say").getOrNull()

        // Then there is no commit, because an empty commit would fill the
        // history with noise every time the button was pressed
        assertNull(id)
        assertEquals(0, git.changes(null).total)
    }

    @Test
    fun `a changed file is a waiting change until it is committed`() {
        // Given a repository with one committed file
        val (_, git) = repositoryWith(mapOf("hello.md" to "version one\n"))

        // When the file is changed
        File(git.dirFor(null), "hello.md").writeText("version two\n")

        // Then it is a waiting change, and committing clears it
        assertEquals(1, git.changes(null).total)
        assertNotNull(git.commit(null, "changed").getOrNull())
        assertEquals(0, git.changes(null).total)
    }

    @Test
    fun `a deleted file is a waiting change too`() {
        // Given a repository with a committed file
        val (dir, git) = repositoryWith(mapOf("gone.md" to "content\n"))

        // When the file is deleted
        File(dir, "gone.md").delete()

        // Then the deletion is itself a change to commit, rather than nothing
        // to do: this is the case a plain "add everything" misses
        assertEquals(1, git.changes(null).total)
        git.commit(null, "deleted")
        assertEquals(0, git.changes(null).total)
    }

    @Test
    fun `the commits carry the identity of the account`() {
        // Given a repository committed to by this account
        val (_, git) = repositoryWith(mapOf("a.md" to "a\n"))

        // When the history is read
        val history = git.history(null, null)

        // Then the commit says who made it, because the same repository may be
        // used from more than one account
        assertTrue(history.isNotEmpty())
        assertEquals("Muffy World", history.first().author)
        assertEquals("muffyworld365@gmail.com", history.first().authorEmail)
    }

    // ---- reading the history back -----------------------------------------

    @Test
    fun `every version of a file is in its history, newest first`() {
        // Given a file committed three times
        val (_, git) = repositoryWith(
            mapOf("note.md" to "one\n"),
            mapOf("note.md" to "two\n"),
            mapOf("note.md" to "three\n"),
        )

        // When its versions are read
        val versions = git.versions(null, "note.md")

        // Then all three are there, newest first, each with its own content
        assertEquals(3, versions.size)
        assertEquals("three\n", versions[0].second)
        assertEquals("two\n", versions[1].second)
        assertEquals("one\n", versions[2].second)
    }

    @Test
    fun `the history of one file leaves out the commits that did not touch it`() {
        // Given two files where only one is changed afterwards
        val (_, git) = repositoryWith(
            mapOf("a.md" to "a\n", "b.md" to "b\n"),
            mapOf("a.md" to "a changed\n"),
        )

        // When the history of each file is read
        val historyOfA = git.history(null, "a.md")
        val historyOfB = git.history(null, "b.md")

        // Then each holds only its own commits, so a file's history is its own
        // story and not the story of the whole folder
        assertEquals(2, historyOfA.size)
        assertEquals(1, historyOfB.size)
        assertEquals("commit 2", historyOfA.first().message)
    }

    @Test
    fun `a file can be read at an older version`() {
        // Given a file committed twice
        val (_, git) = repositoryWith(
            mapOf("note.md" to "original\n"),
            mapOf("note.md" to "changed\n"),
        )
        val oldest = git.versions(null, "note.md").last().first

        // When the file is read at that older commit
        val then = git.fileAt(null, "note.md", oldest.id)

        // Then it is the content of that commit, not what is on disk now
        assertEquals("original\n", then)
    }

    @Test
    fun `a file that did not exist at an older commit reads as nothing`() {
        // Given a file that was added after the first commit
        val (_, git) = repositoryWith(
            mapOf("first.md" to "one\n"),
            mapOf("later.md" to "two\n"),
        )
        val first = git.versions(null, "first.md").last().first

        // When the later file is read at the earlier commit
        val then = git.fileAt(null, "later.md", first.id)

        // Then it reads as nothing rather than as an error or as the wrong
        // file's content
        assertNull(then)
    }

    @Test
    fun `the contents of the folder can be read at an older commit`() {
        // Given a folder that had two files, then lost one and gained another
        val (dir, git) = repositoryWith(
            mapOf("one.md" to "one\n", "two.md" to "two\n"),
            mapOf("three.md" to "three\n"),
        )
        File(dir, "one.md").delete()
        git.commit(null, "removed one")

        val first = git.history(null, null).first { it.message == "commit 1" }.id

        // When both the old and the current folder are listed
        val then = git.treeAt(null, null, first).map { it.name }.sorted()
        val now = git.treeAt(null, null, null).map { it.name }.sorted()

        // Then each shows the files of its own time
        assertEquals(listOf("one.md", "two.md"), then)
        assertEquals(listOf("three.md", "two.md"), now)
    }

    // ---- going back to an older version ------------------------------------

    @Test
    fun `restoring an old version puts the file back and waits to be committed`() {
        // Given a file whose content was changed after an earlier commit
        val (dir, git) = repositoryWith(
            mapOf("note.md" to "original\n"),
            mapOf("note.md" to "changed\n"),
        )
        val original = git.versions(null, "note.md").last().first

        // When that earlier version is restored
        git.restore(null, "note.md", original.id)

        // Then the file holds it again, and the restore is a change to commit
        // rather than a silent rewrite of history
        assertEquals("original\n", File(dir, "note.md").readText())
        assertEquals(1, git.changes(null).total)
    }

    // ---- a folder with no repository ---------------------------------------

    @Test
    fun `a folder with no repository reports nothing rather than failing`() {
        // Given a folder that was never made a repository
        val git = projectsIn(temporaryDirectory())

        // When its git state is asked for
        // Then every question has a quiet answer, so the screen can simply show
        // nothing instead of an error
        assertEquals(0, git.changes(null).total)
        assertTrue(git.history(null, null).isEmpty())
        assertTrue(git.versions(null, "a.md").isEmpty())
        assertNull(git.fileAt(null, "a.md", null))
        assertNull(git.remoteUrl(null))
    }

    // ---- the remote --------------------------------------------------------

    @Test
    fun `the remote of a repository can be set and read back`() {
        // Given a repository with no remote yet
        val (_, git) = repositoryWith(mapOf("a.md" to "a\n"))
        assertNull(git.remoteUrl(null))

        // When a remote is set
        val address = "https://github.com/muffyuniverse/demo.git"
        assertTrue(git.setRemote(null, address).isSuccess)

        // Then it is remembered, which is what lets a folder be synced at all
        assertEquals(address, git.remoteUrl(null))
    }
}
