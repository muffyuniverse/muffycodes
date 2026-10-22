package com.example.notes

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.revwalk.RevCommit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Proves that the pure-Java git implementation (JGit) does the work the app needs:
 * initialise a repository, stage changes, commit them, walk the history and read
 * an old version of a file back.
 */
class GitSpikeTest {

    private fun identity(): PersonIdent =
        PersonIdent("Muffy World", "muffyworld365@gmail.com")

    @Test
    fun `init commit log and read old version`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "spike-${System.nanoTime()}")
        dir.mkdirs()

        Git.init().setDirectory(dir).call().use { git ->
            File(dir, "hello.txt").writeText("version one\n")
            git.add().addFilepattern(".").call()
            git.commit()
                .setMessage("first")
                .setAuthor(identity())
                .setCommitter(identity())
                .call()

            File(dir, "hello.txt").writeText("version two\n")
            git.add().addFilepattern(".").call()
            git.commit()
                .setMessage("second")
                .setAuthor(identity())
                .setCommitter(identity())
                .call()

            // The history has two commits, newest first.
            val log = git.log().call().toList()
            assertEquals(2, log.size)
            val newest: RevCommit = log.first()
            assertEquals("second", newest.fullMessage.trim())
            assertEquals("Muffy World", newest.authorIdent.name)
            assertEquals("muffyworld365@gmail.com", newest.authorIdent.emailAddress)

            // The first commit still holds the first version of the file.
            val oldest = log.last()
            val treeWalk = org.eclipse.jgit.treewalk.TreeWalk.forPath(
                git.repository,
                "hello.txt",
                oldest.tree,
            )
            assertNotNull("the old file should still be in the tree", treeWalk)
            val blob = git.repository.open(treeWalk.getObjectId(0))
            val content = blob.getBytes().toString(Charsets.UTF_8)
            assertEquals("version one\n", content)
        }

        dir.deleteRecursively()
    }

    @Test
    fun `status reports dirty files`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "spike-status-${System.nanoTime()}")
        dir.mkdirs()

        Git.init().setDirectory(dir).call().use { git ->
            File(dir, "a.txt").writeText("a\n")
            git.add().addFilepattern(".").call()
            git.commit()
                .setMessage("init")
                .setAuthor(identity())
                .setCommitter(identity())
                .call()

            assertTrue(git.status().call().isClean)

            File(dir, "a.txt").writeText("changed\n")
            val status = git.status().call()
            assertTrue(!status.isClean)
            assertTrue(status.modified.contains("a.txt"))
        }

        dir.deleteRecursively()
    }
}
