package com.example.notes

import org.eclipse.jgit.api.Git
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * **The order, and the silence.**
 *
 * Two facts are worth a test each, and neither needs GitHub:
 *
 * 1. **What a project contains is read from the tree**, not from the disk — so a picture whose bytes
 *    never arrived is still known to the project, which is what makes it reportable.
 * 2. **A pointer left in the working tree is named**, which is the failure JGit produces silently on a
 *    private repository: 127 bytes on disk, zero objects fetched, no error.
 *
 * The transfer itself is tested in [LfsClientTest] against a local server. What cannot be tested
 * offline is GitHub's own behaviour — that is the manual gate the plan states.
 */
class LfsSyncTest {

    private lateinit var root: File
    private lateinit var repo: org.eclipse.jgit.lib.Repository

    @Before
    fun makeRepository() {
        root = File(System.getProperty("java.io.tmpdir"), "lfssync-${System.nanoTime()}")
        root.mkdirs()
        Git.init().setDirectory(root).setInitialBranch("main").call().use { }
        repo = org.eclipse.jgit.storage.file.FileRepositoryBuilder()
            .setGitDir(File(root, ".git"))
            .setWorkTree(root)
            .build()
        // **Registered as well as enabled**, because the SPI is what makes the clean filter exist
        // at all; without it the attribute is ignored and the tree stores the raw picture.
        GitService.registerLfs()
        GitService.enableLfs(repo)
        // **The attribute is what tells git the file is a pointer.** Without it the clean filter never
        // runs, whatever is registered, and the tree stores the raw picture — which is the silent
        // no-op this test file would otherwise be asserting against.
        GitService.ensureLfsAttributes(root)
    }

    @After
    fun cleanUp() {
        repo.close()
        root.deleteRecursively()
    }

    @Test
    fun `a picture is known by the tree even before its bytes arrive`() {
        // **The fact `missing` rests on.** A clone writes the pointer into the working tree and has
        // no bytes for it; the project still has to know the picture exists, or nothing can ever
        // report that it did not arrive. So the pointer set is read from `HEAD`'s tree, not the disk.
        commitPicture("page.png", PNG)

        assertEquals(
            "one pointer in the tree, naming the bytes and the place",
            listOf(LfsSync.Pointer(sha256(PNG), PNG.size.toLong(), "page.png")),
            LfsSync.pointersIn(repo),
        )
        assertTrue("and its bytes are in the store", LfsSync.fileOf(repo, sha256(PNG))!!.isFile)
    }

    @Test
    fun `a pointer with no bytes is named as missing`() {
        // **The silent failure, made loud.** The bytes are deleted from the store and the pointer is
        // left in the working tree — exactly what a private clone produces. `missing` must name the
        // path, so the app can say "this picture did not arrive" instead of showing a file that will
        // not open.
        commitPicture("page.png", PNG)
        LfsSync.fileOf(repo, sha256(PNG))!!.delete()
        // **The working tree is left holding the pointer**, which is what a clone produces: git
        // writes the pointer and the smudge is supposed to replace it with the bytes. Writing the
        // pointer here is the honest reconstruction of that state — deleting the store alone would
        // leave the real picture on disk, and the app would rightly say nothing was missing.
        File(root, "page.png").writeText(
            "version https://git-lfs.github.com/spec/v1\n" +
                "oid sha256:${sha256(PNG)}\n" +
                "size ${PNG.size}\n",
        )

        assertEquals("the path is named", listOf("page.png"), LfsSync.missing(repo))
    }

    @Test
    fun `a picture whose bytes are present is not reported`() {
        // **The other half, and it is what keeps the report meaningful.** A project whose pictures
        // all arrived must answer nothing at all, or the message becomes noise a person learns to
        // ignore.
        commitPicture("page.png", PNG)

        assertTrue("nothing is missing", LfsSync.missing(repo).isEmpty())
    }

    @Test
    fun `a text file is never a pointer and is never reported`() {
        // **The guard that keeps the feature off the product.** A text file's bytes are its content;
        // treating one as a pointer would hide the thing a person came to read.
        write("milk.txt", "the milk went sour\n")
        commit("a note")

        assertTrue("no pointers", LfsSync.pointersIn(repo).isEmpty())
        assertTrue("and nothing missing", LfsSync.missing(repo).isEmpty())
    }

    @Test
    fun `with no GitHub address a picture that has to go says so rather than trying`() {
        // **A failure has to say what happened.** A project that stays on the phone has no endpoint,
        // and the answer names that rather than reporting a network error that never occurred.
        //
        // **The bytes are deleted first, so there is genuinely something to send.** A project whose
        // store already holds everything answers "nothing to do" before it looks for an address —
        // which is right, and is a different case from this one.
        commitPicture("page.png", PNG)
        LfsSync.fileOf(repo, sha256(PNG))!!.delete()

        val sent = LfsSync.push(repo, "a-token", remoteUrl = null)
        assertFalse("it did not succeed", sent.ok)
        assertTrue("and it says why: ${sent.why}", sent.why!!.contains("no GitHub address"))
    }

    @Test
    fun `a picture that is missing from the store needs an address to be fetched from`() {
        // The same on the way in: the pointer is in the tree, the bytes are not in the store, and
        // there is nowhere to ask.
        commitPicture("page.png", PNG)
        LfsSync.fileOf(repo, sha256(PNG))!!.delete()

        val got = LfsSync.pull(repo, "a-token", remoteUrl = null)
        assertFalse("it did not succeed", got.ok)
        assertTrue("with the reason: ${got.why}", got.why!!.contains("no GitHub address"))
    }

    @Test
    fun `with no token a picture that has to go asks for a sign-in rather than sending anonymously`() {
        // **Anonymous is a 401 dressed up as a network problem.** The real cause is that nobody is
        // signed in, so that is what the sentence says.
        commitPicture("page.png", PNG)
        LfsSync.fileOf(repo, sha256(PNG))!!.delete()

        val sent = LfsSync.push(repo, null, "https://github.com/owner/repo.git")

        assertFalse("it did not succeed", sent.ok)
        assertTrue("and it asks for a sign-in: ${sent.why}", sent.why!!.contains("Signing in again"))
    }

    @Test
    fun `a project whose pictures are all present has nothing to send and nothing to fetch`() {
        // **The case that made every text project fail its sync.** Nothing to move means no address
        // and no token are needed, so the answer is success — not a sentence about pictures the
        // project does not have to send.
        commitPicture("page.png", PNG)

        val sent = LfsSync.push(repo, null, remoteUrl = null)
        assertTrue("nothing to send is not a failure: ${sent.why}", sent.ok)

        val got = LfsSync.pull(repo, null, remoteUrl = null)
        assertTrue("and nothing to fetch either: ${got.why}", got.ok)
    }

    // ---- helpers ---------------------------------------------------------------

    private fun commitPicture(name: String, bytes: ByteArray) {
        java.nio.file.Files.write(File(root, name).toPath(), bytes)
        commit("a picture")
    }

    private fun commit(message: String) {
        Git(repo).use { git ->
            git.add().addFilepattern(".").call()
            git.commit()
                .setMessage(message)
                .setAuthor("Test", "test@example.com")
                .setCommitter("Test", "test@example.com")
                .call()
        }
    }

    private fun write(name: String, text: String) {
        File(root, name).writeText(text)
    }

    private fun sha256(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        /** Real PNG magic, so a reader that guesses by content agrees with the name. */
        val PNG: ByteArray =
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + "a drawing".toByteArray()
    }
}
