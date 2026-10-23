package com.example.notes

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The key must not be findable anywhere, and this is the test that keeps it
 * that way.
 *
 * It runs a clone and a push with a token that looks exactly like a real one,
 * then goes through every file git wrote — the working tree, `.git/config`, the
 * logs, all of it — and fails if the token appears so much as once. It also
 * checks the messages the app would show, because a message that quotes an
 * address is the classic way a key escapes.
 */
class KeySafetyTest {

    private val token = "ghp_" + "S3cretValueNotARealToken0123456789"
    private val author = GitAuthor("Test", "test@example.com")
    private lateinit var root: File

    @Before
    fun makeSpace() {
        root = File(System.getProperty("java.io.tmpdir"), "keysafety-${System.nanoTime()}")
        root.mkdirs()
    }

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    /** Every file below [dir], including everything git keeps hidden. */
    private fun allFiles(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile }.toList()

    @Test
    fun scrubMasksCredentialsInsideUrls() {
        val message = "Cloned https://$token@github.com/muffyuniverse/muffycodes.git"
        val scrubbed = Secrets.scrub(message)
        assertFalse(scrubbed.contains(token))
        assertTrue(scrubbed.contains("https://***@github.com"))
        assertTrue(scrubbed.contains("muffyuniverse/muffycodes.git"))
    }

    @Test
    fun scrubMasksLooseTokens() {
        val scrubbed = Secrets.scrub("failed with token $token, sorry")
        assertFalse(scrubbed.contains(token))
        assertTrue(scrubbed.contains("***"))
    }

    @Test
    fun scrubLeavesOrdinaryTextAlone() {
        val message = "Cloned https://github.com/a/b.git — commit 1a2b3c4, 3 files"
        assertEquals(message, Secrets.scrub(message))
    }

    @Test
    fun onlyGitHubIsGivenTheKey() {
        assertTrue(Secrets.isGitHubHost("https://github.com/a/b.git"))
        assertTrue(Secrets.isGitHubHost("https://$token@github.com/a/b.git"))
        assertTrue(Secrets.isGitHubHost("https://api.github.com"))
        assertFalse(Secrets.isGitHubHost("https://github.com.evil.example/a/b.git"))
        assertFalse(Secrets.isGitHubHost("https://evil.example/a/b.git"))
        assertFalse(Secrets.isGitHubHost("/some/local/path"))
    }

    @Test
    fun secretCannotBePrintedByAccident() {
        val secret = Secret(token)
        assertEquals("***", secret.toString())
        assertEquals(token, secret.reveal())
    }

    @Test
    fun clonePutsTheKeyNowhere() {
        val remote = File(root, "remote")
        val remoteRepo = GitService.initOrOpen(remote, author)
        File(remote, "note.txt").writeText("milk")
        GitService.commitAll(remoteRepo, "note", author)
        remoteRepo.close()

        val target = File(root, "clone")
        val result = GitService.clone(remote.absolutePath, target, author, token)

        assertTrue(result.message, result.success)
        assertFalse("the message must not carry the key", result.message.contains(token))
        val leaky = allFiles(target).filter { it.readText().contains(token) }
        assertTrue("the key was written into: ${leaky.map { it.relativeTo(target) }}", leaky.isEmpty())
    }

    @Test
    fun pushPutsTheKeyNowhere() {
        val remote = File(root, "remote")
        val bare = GitService.initOrOpen(remote, author)
        bare.close()

        val work = File(root, "work")
        val repo = GitService.initOrOpen(work, author)
        File(work, "note.txt").writeText("milk")
        GitService.commitAll(repo, "note", author)
        GitService.setRemoteUrl(repo, remote.absolutePath)

        val result = GitService.push(repo, token)

        assertTrue(result.message, result.success)
        assertFalse("the message must not carry the key", result.message.contains(token))
        val leaky = (allFiles(work) + allFiles(remote))
            .filter { it.readText().contains(token) }
        assertTrue("the key was written into: ${leaky.map { it.relativeTo(root) }}", leaky.isEmpty())
    }

    @Test
    fun anOldLeakingAddressIsHealedWhenSeen() {
        val work = File(root, "work")
        val repo = GitService.initOrOpen(work, author)
        // What an older version of the app wrote to disk.
        GitService.setRemoteUrl(repo, "https://$token@github.com/a/b.git")

        GitService.scrubRemoteUrls(repo)

        assertEquals("https://github.com/a/b.git", GitService.remoteUrl(repo))
        repo.close()
    }

    @Test
    fun aMessageFromAGitExceptionIsScrubbedBeforeItIsShown() {
        // The shape of a JGit exception: it repeats the address it failed on.
        val exception = RuntimeException("https://$token@github.com/a/b.git: not authorized")
        val shown = Secrets.scrub(exception.message.orEmpty())
        assertFalse(shown.contains(token))
        assertTrue(shown.contains("not authorized"))
    }
}
