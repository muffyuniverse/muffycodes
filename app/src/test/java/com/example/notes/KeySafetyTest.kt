package com.example.notes

import org.eclipse.jgit.storage.file.FileRepositoryBuilder
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

    /** True when a file holds the key — searched byte-wise, so binary objects count. */
    private fun File.containsToken(): Boolean =
        readBytes().toString(Charsets.ISO_8859_1).contains(token)

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
        val leaky = allFiles(target).filter { it.containsToken() }
        assertTrue("the key was written into: ${leaky.map { it.relativeTo(target) }}", leaky.isEmpty())
    }

    @Test
    fun pushPutsTheKeyNowhere() {
        // A bare repository — the way a push target really is.
        val remote = File(root, "remote")
        org.eclipse.jgit.api.Git.init()
            .setDirectory(remote)
            .setBare(true)
            .setInitialBranch("main")
            .call()
            .close()

        val work = File(root, "work")
        val repo = GitService.initOrOpen(work, author)
        File(work, "note.txt").writeText("milk")
        GitService.commitAll(repo, "note", author)
        GitService.setRemoteUrl(repo, remote.absolutePath)

        val result = GitService.push(repo, token)

        assertFalse("the message must not carry the key", result.message.contains(token))
        // The push really moved the commit.
        FileRepositoryBuilder().setGitDir(remote).build().use { remoteRepo ->
            assertTrue("the push left nothing behind", remoteRepo.resolve("refs/heads/main") != null)
        }
        val leaky = (allFiles(work) + allFiles(remote))
            .filter { it.containsToken() }
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
    fun aRefusedKeyIsSaidInWordsAndNotAsAnException() {
        // Every sync that failed with "not authorized" and nothing else sent a person
        // looking for a broken app. The key is the thing to fix, so the key is what
        // the message has to be about.
        val refused = org.eclipse.jgit.errors.TransportException(
            "https://$token@github.com/a/b.git: not authorized",
        )
        val said = Secrets.explain(refused, "Push failed")
        assertFalse("the key is not in the message", said.contains(token))
        assertTrue("it says the key was refused: $said", said.contains("did not accept this phone's key"))
        assertTrue("and what to do: $said", said.contains("Sign in again"))
    }

    @Test
    fun anOfflinePhoneIsSaidAsBeingOffline() {
        // The shape JGit reports it in: its own words first, the network failure
        // underneath as the cause.
        val offline = RuntimeException(
            "cannot open git-upload-pack",
            java.net.UnknownHostException("github.com"),
        )
        val said = Secrets.explain(offline, "Push failed")
        assertTrue("it names the network: $said", said.contains("Could not reach GitHub"))
        assertFalse("and does not pretend a file went: $said", said.contains("Pushed"))
    }

    @Test
    fun anErrorRatherThanAnExceptionStillSaysWhatItSaw() {
        // What a phone actually threw: a class JGit needs that the runtime cannot
        // resolve arrives as an Error, and `catch (Exception)` let it out of the
        // worker with no project, no file and no action in it.
        val notResolved = NoClassDefFoundError("Failed resolution of: Lorg/eclipse/jgit/Foo;")
        val said = Secrets.explain(notResolved, "Push failed")
        assertTrue("it keeps what it saw: $said", said.contains("Failed resolution"))
        assertTrue("and says what kind of failure it was: $said", said.contains("NoClassDefFoundError"))
    }

    @Test
    fun aFailureWithNothingToAddKeepsTheAppsOwnWords() {
        // The falsification of all of the above: a failure that is not one of the
        // four known shapes must not be swallowed into a friendly sentence that says
        // nothing. JGit's own words are what a person can search for.
        val odd = RuntimeException("Unknown command: frobnicate")
        assertEquals("Unknown command: frobnicate", Secrets.explain(odd, "Push failed"))
        assertEquals("Push failed", Secrets.explain(RuntimeException(), "Push failed"))
    }

    @Test
    fun aLockIsSaidAsSomethingThePersonCanDo() {
        // A lock is the one failure whose own words are useless here: "Ensure that no
        // other process has an open file handle on /data/user/0/..." is an instruction
        // for a terminal, and a phone has no terminal. It gets a sentence instead.
        val locked = RuntimeException("Cannot lock /data/user/0/x/.git/index.lock")
        assertEquals(
            "This project was locked by another copy of the app, so nothing was changed. " +
                "Close the app completely and try again.",
            Secrets.explain(locked, "Push failed"),
        )
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
