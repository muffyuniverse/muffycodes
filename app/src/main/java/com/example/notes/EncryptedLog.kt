package com.example.notes

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A build log that a stranger can see but cannot read.
 *
 * ## The problem this answers
 *
 * A failing test prints the expected value and the actual value, because that is what makes a failure
 * useful. In a **public** repository that sentence is readable by any GitHub account, for as long as
 * the log is kept — so running a private project's tests on free public runners would publish
 * sentences about the project's contents. The operator's question was whether a secret could be the
 * key, and the answer is yes: the key is read from `env:` mapped to a secret, GitHub masks a secret's
 * exact value in output, and the plaintext never reaches the output stream at all because the build
 * redirects it to a file first.
 *
 * ## The format, measured rather than assumed
 *
 * `openssl enc -aes-256-cbc -pbkdf2 -iter 10000 -base64 -A` writes a blob that decodes to:
 *
 * ```
 *   "Salted__"  (8 bytes)   the ASCII marker openssl has used since SSLeay
 *   salt        (8 bytes)   random, fresh per encryption
 *   ciphertext  (n bytes)   AES-256-CBC, PKCS#5 padding
 * ```
 *
 * and the key material is
 *
 * ```
 *   PBKDF2-HMAC-SHA256(password = the key string's bytes, salt, iterations = 10000, length = 48)
 *   first 32 bytes = AES key      last 16 bytes = IV
 * ```
 *
 * **This was reproduced outside Java before it was written here**, which is the only reason the
 * constants are trustworthy: the derivation was recomputed in Python and the resulting key and IV were
 * handed to `openssl enc -d -K … -iv …`, which printed the original text. An earlier attempt used the
 * legacy `EVP_BytesToKey` hash loop and produced `bad decrypt` — so the difference between the two
 * derivations is a difference that shows up as an error, not as a plausible-looking wrong answer.
 *
 * **Nothing here is hand-rolled.** `PBKDF2WithHmacSHA256` and `AES/CBC/PKCS5Padding` are both in
 * `javax.crypto` on every API level this app supports (minSdk 26), which is why this needs no
 * BouncyCastle and no libsodium — unlike [SealedBox], whose XSalsa20 has no platform implementation.
 *
 * ## Why a marker, and why a version number
 *
 * Without a marker, **"this log is not encrypted"** and **"this log is encrypted and I cannot read
 * it"** are the same bytes to a reader, and the second one silently becomes the first. The marker is
 * what makes the difference sayable. The `1` is there so that a future change of format is a
 * **refusal** ("this is a version I do not know") rather than a wrong answer from a parser that
 * assumed the old shape.
 */
object EncryptedLog {

    /** What an encrypted log starts with. The trailing space is part of it. */
    const val MARKER = "MUFFYLOG1 "

    /** The eight bytes openssl puts in front of the salt. */
    private val SALTED = "Salted__".toByteArray(Charsets.US_ASCII)

    private const val SALT = 8
    private const val KEY = 32
    private const val IV = 16
    private const val ITERATIONS = 10_000

    /** Whether this text is a blob this object is meant to open. */
    fun looksEncrypted(text: String): Boolean = text.trimStart().startsWith(MARKER)

    /**
     * The text a person should read, whatever was found in the log.
     *
     * **Every answer is either the plaintext or a sentence.** There is no path that returns
     * ciphertext and no path that returns a half-decrypted body: a reader that shows base64 is
     * indistinguishable from a broken build, and a reader that shows a truncated plaintext is worse,
     * because it looks like an answer.
     */
    fun read(text: String, key: String?): String {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith(MARKER)) return text

        // **The rest of the log is kept, not discarded.** The marker covers the build's own output,
        // and the runner's lines around it — checkout, setup, the exit status — are not encrypted
        // because this workflow does not own the processes that write them. Dropping them would hide
        // the reason a build never got as far as printing a blob.
        val body = trimmed.removePrefix(MARKER)
        val blobLine = body.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val around = body.lineSequence().drop(1)
            .filter { it.isNotBlank() && !looksLikeBlob(it) }
            .joinToString("\n")

        val opened = open(blobLine, key)
        return if (around.isEmpty()) opened else opened + "\n" + around
    }

    /** A blob is one long unbroken run of base64; anything with spaces is a sentence. */
    private fun looksLikeBlob(line: String): Boolean =
        line.length > 40 && line.none { it.isWhitespace() }

    /**
     * Open one blob, or say why not.
     *
     * The sentences are written for the person reading a failed build, and each names the one thing
     * that is actually true — never "could not decrypt", which would leave them to guess whether the
     * key was missing, wrong, or the blob was cut short.
     */
    private fun open(blobLine: String, key: String?): String {
        if (key.isNullOrBlank()) {
            return "This log is encrypted, and the app has no key for it. The key is the one that was " +
                "made when this project's CI was set up; without it the build's output cannot be read."
        }
        if (blobLine.isEmpty()) {
            return "This log says it is encrypted but carries nothing after the marker. The build " +
                "most likely stopped before it could write its output."
        }

        val blob = try {
            java.util.Base64.getMimeDecoder().decode(blobLine)
        } catch (e: IllegalArgumentException) {
            return "This log is encrypted and the text after the marker is not valid base64, so it " +
                "was cut short or altered on its way here. " + (e.message ?: "")
        }

        if (blob.size < SALTED.size + SALT + 16) {
            return "This log is encrypted and only " + blob.size + " bytes arrived, which is too few " +
                "to hold a salt and a single block. The build stopped part-way through writing it."
        }
        if (!blob.copyOfRange(0, SALTED.size).contentEquals(SALTED)) {
            return "This log is encrypted, but what follows the marker is not the shape openssl " +
                "writes (it does not begin with its salt marker). It was not produced by the step " +
                "that this app knows how to read."
        }

        val salt = blob.copyOfRange(SALTED.size, SALTED.size + SALT)
        val cipherText = blob.copyOfRange(SALTED.size + SALT, blob.size)

        return try {
            val material = derive(key, salt)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(material.copyOfRange(0, KEY), "AES"),
                IvParameterSpec(material.copyOfRange(KEY, KEY + IV)),
            )
            String(cipher.doFinal(cipherText), Charsets.UTF_8)
        } catch (e: Throwable) {
            // **A wrong key and a corrupt blob land here together, and that is honest.** Both mean
            // "this text cannot be opened with what I have", and the difference between them is not
            // knowable from the bytes: PKCS#5 padding fails for a wrong key exactly as it fails for a
            // damaged block. Claiming to tell them apart would be a guess dressed as a diagnosis.
            "This log is encrypted and this key does not open it. The text is unreadable rather " +
                "than wrong — a key from a different project, or a blob that arrived damaged, both " +
                "look like this."
        }
    }

    /** PBKDF2-HMAC-SHA256, 48 bytes: 32 for the key, 16 for the IV. */
    private fun derive(key: String, salt: ByteArray): ByteArray {
        val spec = javax.crypto.spec.PBEKeySpec(
            key.toCharArray(),
            salt,
            ITERATIONS,
            KEY + IV,
        )
        return javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec)
            .encoded
    }

    /**
     * A fresh 32-byte key, as the text the workflow will hold.
     *
     * Base64 rather than hex or raw bytes, because the value has to survive being put in a secret,
     * read back through `env:`, and passed to `openssl -pass`. A base64 string contains no character
     * that a shell, a YAML file or an `env:` mapping treats specially — which is the whole reason the
     * key's safety does not depend on quoting.
     */
    fun newKey(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return java.util.Base64.getEncoder().encodeToString(bytes)
    }
}
