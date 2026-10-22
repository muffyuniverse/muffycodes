package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The git behaviour of a folder: initialising a repository, committing, reading
 * the history back and restoring an old version.
 */
class GitProjectsTest {

    private val author = GitAuthor("Muffy World", "muffyworld365@gmail.com")

    private fun tempDir(name: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "$name-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    private fun projects(dir: File) = GitProjects(
        rootDir = dir,
        tokenProvider = { null },
        authorProvider = { author },
    )

    @Test
    fun `a folder can be made into a repository`() {
        val dir = tempDir("init")
        val git = projects(dir)

        assertFalse(git.isRepository(null))
        assertTrue(git.initRepository(null).isSuccess)
        assertTrue(git.isRepository(null))
    }

    @Test
    fun `the first commit is the change that is committed`() {
        val dir = tempDir("commit")
        val git = projects(dir)
        git.initRepository(null)

        // Nothing has changed yet, so there is nothing to commit.
        assertNull(git.commit(null, "first").getOrNull())
        assertEquals(0, git.changes(null).total)

        File(dir, "hello.md").writeText("version one\n")
        assertEquals(1, git.changes(null).total)

        val id = git.commit(null, "first commit").getOrNull()
        assertNotNull(id)
        assertEquals(0, git.changes(null).total)
    }

    @Test
    fun `the history of a file holds every version of it`() {
        val dir = tempDir("history")
        val git = projects(dir)
        git.initRepository(null)

        File(dir, "note.md").writeText("one\n")
        git.commit(null, "version one")

        File(dir, "note.md").writeText("two\n")
        git.commit(null, "version two")

        File(dir, "note.md").writeText("three\n")
        git.commit(null, "version three")

        val versions = git.versions(null, "note.md")
        assertEquals(3, versions.size)

        // Newest first.
        assertEquals("version three", versions[0].first.message)
        assertEquals("three\n", versions[0].second)
        assertEquals("version two", versions[1].first.message)
        assertEquals("two\n", versions[1].second)
        assertEquals("version one", versions[2].first.message)
        assertEquals("one\n", versions[2].second)
    }

    @Test
    fun `an old version can be restored and is then a change waiting to be committed`() {
        val dir = tempDir("restore")
        val git = projects(dir)
        git.initRepository(null)

        File(dir, "note.md").writeText("original\n")
        git.commit(null, "original")

        File(dir, "note.md").writeText("changed\n")
        git.commit(null, "changed")

        // Go back to the first version.
        val versions = git.versions(null, "note.md")
        val original = versions.last().first
        git.restore(null, "note.md", original.id)

        // The file is back, and the change is waiting to be committed.
        assertEquals("original\n", File(dir, "note.md").readText())
        assertEquals(1, git.changes(null).total)
    }

    @Test
    fun `the contents of a folder can be read at an old revision`() {
        val dir = tempDir("tree")
        val git = projects(dir)
        git.initRepository(null)

        File(dir, "one.md").writeText("one\n")
        File(dir, "two.md").writeText("two\n")
        val first = git.commit(null, "two files").getOrNull()
        File(dir, "one.md").delete()
        File(dir, "three.md").writeText("three\n")
        git.commit(null, "changed set")

        // At the first commit there were two files.
        val tree = git.treeAt(null, null, first)
        val names = tree.map { it.name }.sorted()
        assertEquals(listOf("one.md", "two.md"), names)

        // At the latest commit one file is gone and one is new.
        val latest = git.treeAt(null, null, null)
        assertEquals(listOf("three.md", "two.md"), latest.map { it.name })
    }

    @Test
    fun `the commits carry the identity of the account`() {
        val dir = tempDir("identity")
        val git = projects(dir)
        git.initRepository(null)

        File(dir, "a.md").writeText("a\n")
        git.commit(null, "with identity")

        val history = git.history(null, null)
        assertTrue(history.isNotEmpty())
        assertEquals("Muffy World", history.first().author)
        assertEquals("muffyworld365@gmail.com", history.first().authorEmail)
    }

    @Test
    fun `the history of a file only holds the commits that touched it`() {
        val dir = tempDir("path-history")
        val git = projects(dir)
        git.initRepository(null)

        File(dir, "a.md").writeText("a\n")
        git.commit(null, "add a")

        File(dir, "b.md").writeText("b\n")
        git.commit(null, "add b")

        File(dir, "a.md").writeText("a changed\n")
        git.commit(null, "change a")

        val historyForA = git.history(null, "a.md")
        assertEquals(2, historyForA.size)
        assertEquals("change a", historyForA.first().message)
    }

    @Test
    fun `a folder without a repository reports no changes and no history`() {
        val dir = tempDir("no-repo")
        val git = projects(dir)

        assertEquals(0, git.changes(null).total)
        assertTrue(git.history(null, null).isEmpty())
        assertTrue(git.versions(null, "a.md").isEmpty())
        assertNull(git.remoteUrl(null))
    }

    @Test
    fun `the remote can be set and read back`() {
        val dir = tempDir("remote")
        val git = projects(dir)
        git.initRepository(null)

        assertNull(git.remoteUrl(null))
        assertTrue(git.setRemote(null, "https://github.com/muffyworld365/demo.git").isSuccess)
        assertEquals("https://github.com/muffyworld365/demo.git", git.remoteUrl(null))
    }
}
