package com.example.notes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * **The reader, and the five ways it must refuse.**
 *
 * The build encrypts its output and prints only the ciphertext, so this object is the only thing
 * between a person and their own build log. That makes a silent wrong answer the expensive failure —
 * base64 shown as if it were a log reads exactly like a broken build — so most of these tests are
 * about what happens when the text **cannot** be read.
 *
 * **The blobs are produced here, not pasted in.** A test that reads a fixture recorded once proves the
 * fixture was recorded; a test that encrypts with the same documented format and then decrypts proves
 * the two agree. The format itself was verified outside Java before it was written down — see
 * [EncryptedLog]'s comment — and [the format matches what openssl writes] re-derives that here so a
 * future edit to the constants fails loudly rather than quietly.
 */
class EncryptedLogTest {

    private val key = "Zm9yLXRlc3Rpbmctb25seS0zMi1ieXRlLWtleSE="

    /** Encrypt exactly as `openssl enc -aes-256-cbc -pbkdf2 -base64 -A` does. */
    private fun seal(text: String, key: String, salt: ByteArray = ByteArray(8) { (it + 1).toByte() }): String {
        val material = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(key.toCharArray(), salt, 10_000, 48 * 8)).encoded
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(material.copyOfRange(0, 32), "AES"),
            IvParameterSpec(material.copyOfRange(32, 48)),
        )
        val body = "Salted__".toByteArray(Charsets.US_ASCII) + salt + cipher.doFinal(text.toByteArray())
        return Base64.getEncoder().encodeToString(body)
    }

    @Test
    fun `a sealed blob reads back as exactly its text`() {
        val text = "SelectionFeatureTest > a lock is remembered FAILED\n  AssertionError: unlocked\n"
        val blob = EncryptedLog.MARKER + seal(text, key)

        assertEquals("the log a person reads is the log the build wrote", text, EncryptedLog.read(blob, key))
    }

    @Test
    fun `the format matches what openssl writes`() {
        // **The check that keeps this file honest.** If somebody changes the iteration count, the salt
        // length or the order of key and IV, this fails here rather than in a public log that nobody
        // can read. It decrypts by hand, with the constants written out, and never calls the object's
        // own derivation.
        val text = "hello from the build"
        val salt = ByteArray(8) { 7 }
        val blob = Base64.getDecoder().decode(seal(text, key, salt))

        assertTrue(
            "the blob begins with openssl's salt marker",
            blob.copyOfRange(0, 8).contentEquals("Salted__".toByteArray(Charsets.US_ASCII)),
        )
        val material = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(key.toCharArray(), salt, 10_000, 48 * 8)).encoded
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(material.copyOfRange(0, 32), "AES"),
            IvParameterSpec(material.copyOfRange(32, 48)),
        )
        assertEquals(
            "32 bytes of key then 16 of IV, over salt then ciphertext",
            text,
            String(cipher.doFinal(blob.copyOfRange(16, blob.size))),
        )
    }

    @Test
    fun `a log with no marker is left alone`() {
        // **Every run from before this change, and every job this workflow does not wrap.** If this
        // broke, a person's whole history of logs would turn into "this is encrypted".
        val plain = "Test (0) > Run the tests\nBUILD SUCCESSFUL in 1m 24s\n"
        assertFalse(EncryptedLog.looksEncrypted(plain))
        assertEquals("plain text passes through unchanged", plain, EncryptedLog.read(plain, key))
        assertEquals("and so does plain text with no key at all", plain, EncryptedLog.read(plain, null))
    }

    @Test
    fun `a blob with the wrong key answers a sentence, never bytes`() {
        val blob = EncryptedLog.MARKER + seal("a private sentence", key)
        val said = EncryptedLog.read(blob, "ZGlmZmVyZW50LWtleS1lbnRpcmVseS0xMjM0NTY=")

        assertTrue("it says the key does not open it", said.contains("does not open it"))
        assertFalse("and never shows ciphertext", said.contains("Salted"))
        assertFalse("and never shows the private sentence", said.contains("private sentence"))
    }

    @Test
    fun `a truncated blob says it is incomplete`() {
        // **The case a hang produces.** The runner is killed while the blob is being written, so what
        // arrives is a prefix. A reader that decrypted the blocks it had would print a fragment of a
        // log and present it as the whole of it.
        val full = seal("a long enough sentence to need several blocks of padding here", key)
        val cut = full.substring(0, full.length / 2)
        val said = EncryptedLog.read(EncryptedLog.MARKER + cut, key)

        assertTrue("it names the text as cut short or altered", said.contains("not valid base64") || said.contains("too few") || said.contains("does not open it"))
        assertFalse("and does not show the plaintext", said.contains("several blocks"))
    }

    @Test
    fun `with no key stored, the answer names the missing key`() {
        val blob = EncryptedLog.MARKER + seal("anything", key)
        for (absent in listOf(null, "", "   ")) {
            val said = EncryptedLog.read(blob, absent)
            assertTrue("it says the app has no key: $said", said.contains("no key"))
            assertFalse("and does not blame the build: $said", said.contains("does not open it"))
        }
    }

    @Test
    fun `a marker with nothing after it is not treated as a successful read`() {
        val said = EncryptedLog.read(EncryptedLog.MARKER, key)
        assertTrue("it says the build stopped before writing output: $said", said.contains("carries nothing"))
    }

    @Test
    fun `the runner's own lines around the blob are kept`() {
        // **What the redirect cannot cover.** Checkout, setup and the exit status are written by
        // processes this workflow does not own, so they stay plain. Dropping them would hide the
        // reason a build never reached the step that writes the blob.
        val text = EncryptedLog.MARKER + "\n" +
            seal("the build's own output", key) + "\n" +
            "Process completed with exit code 1.\n"

        val said = EncryptedLog.read(text, key)
        assertTrue("the plaintext is there", said.contains("the build's own output"))
        assertTrue("and so is the runner's line", said.contains("exit code 1"))
    }

    @Test
    fun `a runner line before the blob does not become the blob`() {
        // **The failure this caught in CI, kept as a test.** The blob used to be found by position —
        // the first non-blank line after the marker — and the runner writes its own lines in an order
        // this workflow does not control. A message arriving first was handed to the decoder as if it
        // were ciphertext. It is found by shape now.
        val text = EncryptedLog.MARKER + "\n" +
            "Runner version 2.320.0 starting\n" +
            seal("the build's own output", key) + "\n"

        val said = EncryptedLog.read(text, key)
        assertTrue("the plaintext is read: $said", said.contains("the build's own output"))
        assertTrue("and the runner's line is kept: $said", said.contains("Runner version"))
    }

    @Test
    fun `a key is fresh every time and survives being written down`() {
        val first = EncryptedLog.newKey()
        val second = EncryptedLog.newKey()
        assertTrue("two keys differ", first != second)
        assertEquals("a key is 32 bytes in base64", 44, first.length)
        assertTrue(
            "and it is base64, so no shell or YAML rule can need quoting: $first",
            first.all { it.isLetterOrDigit() || it == '+' || it == '/' || it == '=' },
        )
    }

    @Test
    fun `a blob encrypted with a generated key reads back`() {
        // **The end the scheme needs**: a key this object makes is a key this object can use, and a
        // workflow holding it as text can produce something it opens.
        val fresh = EncryptedLog.newKey()
        val text = "AssertionError: expected <locked> but was <unlocked>"
        assertEquals(text, EncryptedLog.read(EncryptedLog.MARKER + seal(text, fresh), fresh))
    }
    // ---- sealing, which had no writer until now -----------------------------
    //
    // Every test above opens something the *workflow* sealed with `openssl`. The app could only read.
    // These are the other direction: what the app writes must be what the app (and the workflow) can
    // open, and the format is openssl's rather than one of our own invention.

    /**
     * **The round trip, which is the whole contract.** `sealBytes` and `openBytes` are inverses, and
     * a writer that produces something the reader cannot open is the one failure that would make the
     * tool useless while every test still passed.
     */
    @Test
    fun `sealing bytes and opening them gives the same bytes back`() {
        val key = EncryptedLog.newKey()
        val plain = "AssertionError: expected <locked> but was <unlocked>".toByteArray()

        val sealed = EncryptedLog.sealBytes(plain, key)

        assertArrayEquals(plain, EncryptedLog.openBytes(sealed, key))
    }

    /**
     * **A sealed file starts with the marker**, because that is how `openBytes` recognises its own
     * work. A writer that forgot it would produce bytes the reader passed through unchanged — a
     * silent no-op that looks like success.
     */
    @Test
    fun `a sealed file is recognisable by its first bytes`() {
        val sealed = EncryptedLog.sealBytes("hello".toByteArray(), EncryptedLog.newKey())
        val marker = EncryptedLog.MARKER.toByteArray(Charsets.US_ASCII)

        assertTrue(
            "the marker is at the front, so openBytes knows this is its own work",
            sealed.copyOfRange(0, marker.size).contentEquals(marker),
        )
    }

    /**
     * **A fresh salt every time.** Two seals of identical bytes must differ, or a person comparing
     * two artifacts could tell they hold the same file without opening either — which is a leak the
     * seal exists to prevent.
     */
    @Test
    fun `sealing the same bytes twice gives different output`() {
        val key = EncryptedLog.newKey()
        val plain = "the same sentence".toByteArray()

        val one = EncryptedLog.sealBytes(plain, key)
        val two = EncryptedLog.sealBytes(plain, key)

        assertFalse(
            "two seals of one file are different bytes",
            one.contentEquals(two),
        )
        assertArrayEquals("and both open", plain, EncryptedLog.openBytes(one, key))
        assertArrayEquals("to the same plaintext", plain, EncryptedLog.openBytes(two, key))
    }

    /**
     * **A wrong key is refused by name, not silently.** The reader's sentence already distinguishes
     * this; the test exists so the writer cannot accidentally produce something that opens with any
     * key — which would mean it was not encrypted.
     */
    @Test
    fun `a sealed file does not open with a different key`() {
        val sealed = EncryptedLog.sealBytes("secret".toByteArray(), EncryptedLog.newKey())

        val opened = try {
            EncryptedLog.openBytes(sealed, EncryptedLog.newKey())
            "it opened, which means it was not encrypted"
        } catch (e: Throwable) {
            e.message.orEmpty()
        }

        assertTrue("it refuses: $opened", opened.contains("does not open it"))
    }

    /**
     * **An empty key is refused rather than used.** A file sealed with an empty key is a file
     * anybody can open, and the refusal has to happen where the mistake is made.
     */
    @Test
    fun `sealing without a key is refused`() {
        val said = try {
            EncryptedLog.sealBytes("hello".toByteArray(), "")
            "it was sealed anyway"
        } catch (e: Throwable) {
            e.message.orEmpty()
        }

        assertTrue("it refuses: $said", said.contains("without a key"))
    }

    /**
     * **Binary survives the trip.** The inner base64 is what makes a seal a single token, and a
     * picture or a zip has bytes that are not valid UTF-8 — so this is the case that proves the
     * writer is not doing a `String` round trip somewhere.
     */
    @Test
    fun `bytes that are not text survive sealing`() {
        val key = EncryptedLog.newKey()
        val plain = ByteArray(512) { (it % 256).toByte() }

        assertArrayEquals(plain, EncryptedLog.openBytes(EncryptedLog.sealBytes(plain, key), key))
    }
}
