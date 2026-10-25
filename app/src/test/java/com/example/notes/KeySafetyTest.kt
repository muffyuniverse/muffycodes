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
    fun aNumberInAPathIsNotAnHttpStatus() {
        // The bug this lives beside: the classifier matched a bare "401" anywhere in the
        // chain, and a lock's message is a path — `/tmp/projectgit-1761300401234/...`.
        // A nanosecond timestamp containing "401" told a person their key was wrong when
        // their repository was locked. Status codes are matched as words now.
        val locked = RuntimeException(
            "Exception caught during execution of add command",
            RuntimeException("Cannot lock /tmp/projectgit-1761300401234/repo/.git/index.lock"),
        )
        assertTrue(
            "a lock is a lock whatever digits its path holds",
            Secrets.explain(locked, "Sync failed").startsWith("This project was locked"),
        )
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
    // ---- a locked phone is not a dead key -------------------------------------

    // **These are shapes, not the Android classes themselves.** A plain JVM test cannot construct a
    // stub from `android.jar` (its constructor is the mock that throws), and the store on a real phone
    // reports a locked key either as `UserNotAuthenticatedException` or as the keystore's own words in
    // a `KeyStoreException`. Both are here as text and as the one JDK class that really is thrown
    // when a ciphertext will not open.
    @Test
    fun `a locked phone is not evidence the key is gone`() {
        // **The failure this is here for.** The store's own key is created with
        // `setUnlockedDeviceRequired`, so on a locked phone decryption throws — and the agent polls
        // with the screen off, which is exactly when it happens. One `catch (e: Exception)` used to
        // treat that as "the key is dead", delete it, and answer "No OpenRouter key on this account
        // yet" for ever after. A person then sets the key again, and it works until the next poll
        // with the screen off.
        val locked = IllegalStateException(
            "UserNotAuthenticatedException: Key user not authenticated",
        )

        assertFalse("a locked phone is not a dead key", KeyVerdict.isDead(locked))
        assertTrue("and it is the locked case", KeyVerdict.isLocked(locked))
    }

    @Test
    fun `a key that cannot be opened is dead, however deeply the failure is wrapped`() {
        val wrapped = IllegalStateException(
            "could not decrypt",
            javax.crypto.AEADBadTagException("mac check in GCM failed"),
        )
        assertTrue("the bytes did not open", KeyVerdict.isDead(wrapped))

        assertTrue(
            "and an invalidated keystore entry is dead too",
            KeyVerdict.isDead(IllegalStateException("KeyPermanentlyInvalidatedException: gone")),
        )
    }

    @Test
    fun `a failure nobody recognises is not a reason to throw a key away`() {
        // The destructive answer has to be earned. A store that says "something went wrong" leaves
        // the bytes where they are: a person who types the key again overwrites them anyway, and a
        // person who does not has not lost anything.
        val odd = IllegalStateException("keystore busy")

        assertFalse("not dead", KeyVerdict.isDead(odd))
        assertFalse("not locked either", KeyVerdict.isLocked(odd))
    }

}
