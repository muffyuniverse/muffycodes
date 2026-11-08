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
 * BouncyCastle and no libsodium — the sealed box that needed both left with the environment-secret
 * API it was written for.
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

    /**
     * The name the key is kept under, in two places that must agree: the account's secret store on
     * this phone, and the repository's **environment** secret that the workflow reads.
     *
     * One constant rather than a literal in each place, because a mismatch between the two is
     * invisible: the workflow would encrypt under an empty value and every run would be unreadable,
     * with nothing reporting a problem.
     */
    const val KEY_NAME = "MUFFY_LOG_KEY"

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
        val lines = body.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()

        // **The blob is found by its shape, not by its position.** The runner writes its own lines
        // and this workflow does not control the order they arrive in, so "the first non-blank line"
        // would mistake a runner message for the blob and then hand a sentence to the decoder. A blob
        // is one long unbroken run of base64; a runner line has spaces in it.
        val blobLine = lines.firstOrNull { looksLikeBlob(it) }.orEmpty()
        val around = lines.filter { !looksLikeBlob(it) }.joinToString("\n")

        val opened = open(blobLine, key)
        // **A log that cannot be opened is its reason, plus whatever the runner wrote around it.**
        // The surrounding lines are still worth reading — they are often why the build stopped — so
        // the reason leads and they follow, rather than the reason replacing them.
        val text = if (opened.ok) opened.text else opened.reason.orEmpty()
        return if (around.isEmpty()) text else text + "\n" + around
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
    /**
     * What an attempt to open a blob produced — **the text, or the reason there is none.**
     *
     * This is a pair rather than a sentence-as-sentinel because a sentence was being *decoded*.
     * [openBytes] base64-decodes whatever [open] returns, and `getMimeDecoder` skips every character
     * outside the base64 alphabet — so "This log is encrypted and this key does not open it" decoded
     * to a short run of garbage bytes **without an exception**, and a caller holding a wrong key got
     * a successful-looking answer full of noise. A failure has to say what happened; it must not be
     * something a later step can mistake for data.
     */
    private data class Opened(val text: String = "", val reason: String? = null) {
        val ok: Boolean get() = reason == null
    }

    private fun open(blobLine: String, key: String?): Opened {
        if (key.isNullOrBlank()) {
            return Opened(
                reason = "This log is encrypted, and the app has no key for it. The key is the one " +
                    "that was made when this project's CI was set up; without it the build's output " +
                    "cannot be read.",
            )
        }
        if (blobLine.isEmpty()) {
            return Opened(
                reason = "This log says it is encrypted but carries nothing after the marker. The " +
                    "build most likely stopped before it could write its output.",
            )
        }

        val blob = try {
            java.util.Base64.getMimeDecoder().decode(blobLine)
        } catch (e: IllegalArgumentException) {
            return Opened(
                reason = "This log is encrypted and the text after the marker is not valid base64, " +
                    "so it was cut short or altered on its way here. " + (e.message ?: ""),
            )
        }

        if (blob.size < SALTED.size + SALT + 16) {
            return Opened(
                reason = "This log is encrypted and only " + blob.size + " bytes arrived, which is " +
                    "too few to hold a salt and a single block. The build stopped part-way through " +
                    "writing it.",
            )
        }
        if (!blob.copyOfRange(0, SALTED.size).contentEquals(SALTED)) {
            return Opened(
                reason = "This log is encrypted, but what follows the marker is not the shape " +
                    "openssl writes (it does not begin with its salt marker). It was not produced " +
                    "by the step that this app knows how to read.",
            )
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
            Opened(String(cipher.doFinal(cipherText), Charsets.UTF_8))
        } catch (e: Throwable) {
            // **A wrong key and a corrupt blob land here together, and that is honest.** Both mean
            // "this text cannot be opened with what I have", and the difference between them is not
            // knowable from the bytes: PKCS#5 padding fails for a wrong key exactly as it fails for a
            // damaged block. Claiming to tell them apart would be a guess dressed as a diagnosis.
            Opened(
                reason = "This log is encrypted and this key does not open it. The text is " +
                    "unreadable rather than wrong — a key from a different project, or a blob that " +
                    "arrived damaged, both look like this.",
            )
        }
    }

    /**
     * PBKDF2-HMAC-SHA256, 48 bytes: 32 for the key, 16 for the IV.
     *
     * **`PBEKeySpec`'s last argument is a length in BITS, not bytes.** Passing `KEY + IV` asked for
     * 48 bits — six bytes — and every derivation then failed with an index out of range rather than
     * with anything that mentioned a length. `openssl -pbkdf2` derives 48 *bytes*, so the number here
     * must be `(KEY + IV) * 8`.
     */
    private fun derive(key: String, salt: ByteArray): ByteArray {
        val spec = javax.crypto.spec.PBEKeySpec(
            key.toCharArray(),
            salt,
            ITERATIONS,
            (KEY + IV) * 8,
        )
        return javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec)
            .encoded
    }

    /**
     * The bytes of a sealed file, or the reason there are none.
     *
     * **A sealed file and a sealed log are the same bytes** — marker, `Salted__`, salt, ciphertext —
     * so the format, the derivation and every refusal are shared. What differs is only the carrier: a
     * log is text with the marker on a line, and an artifact is a byte array whose first bytes are the
     * marker. One reader, two carriers, because two implementations of "open this" would drift in the
     * ways that are hardest to notice.
     *
     * Returns the plaintext, or throws with a sentence a person can act on. Throwing rather than
     * returning a `Result` keeps the caller's error handling in one shape — the caller already has a
     * `refusal(...)` path for every other way an artifact can fail.
     */
    fun openBytes(sealed: ByteArray, key: String?): ByteArray {
        val marker = MARKER.toByteArray(Charsets.US_ASCII)
        if (sealed.size < marker.size || !sealed.copyOfRange(0, marker.size).contentEquals(marker)) {
            // **Not sealed is not a failure.** An artifact this app made before the seal existed, or
            // one a different workflow uploaded, is returned as it arrived.
            return sealed
        }
        if (key.isNullOrBlank()) {
            throw IllegalStateException(
                "This artifact is sealed, and the app has no key for it. The key is the one made " +
                    "when this project's CI was set up.",
            )
        }
        // The blob is base64 text after the marker, and the workflow writes a note file beside it, so
        // only the first line of the remainder is the ciphertext.
        // **Whitespace between the marker and the ciphertext is expected, not an error.** The
        // workflow writes `printf 'MUFFYLOG1 '` and then the blob on the same line, but a caller that
        // writes the marker and a newline gets the same thing — and a sealed file whose first bytes
        // are the marker followed by a line break is not a different format. Only the ciphertext has
        // to be found; how it was laid out beside the marker is not a fact this reader cares about.
        val body = String(sealed, marker.size, sealed.size - marker.size, Charsets.US_ASCII)
        val blobLine = body.lineSequence().map { it.trim() }.firstOrNull { looksLikeBlob(it) }
            ?: throw IllegalStateException(
                "This artifact says it is sealed but carries no ciphertext after the marker.",
            )
        val opened = open(blobLine, key)
        // **A reason is thrown, never decoded.** This is the bug the pair exists to stop: a sentence
        // put through a mime decoder becomes bytes without an error, so a wrong key used to answer a
        // pile of noise and call it a file.
        if (!opened.ok) throw IllegalStateException(opened.reason)

        // **`open` answers the plaintext, and the workflow sealed a FILE — so the plaintext is
        // base64 of the file's bytes.** `openssl ... -base64` writes base64, which is what makes the
        // blob one token that survives a log line; the file itself is the base64 decoded. Decoding
        // here rather than in `open` keeps one implementation for both carriers.
        return try {
            java.util.Base64.getMimeDecoder().decode(opened.text.trim())
        } catch (e: IllegalArgumentException) {
            // A log's plaintext is text, not base64, and `openBytes` is never given a log. Reaching
            // here means the bytes after the marker were not what this workflow writes.
            throw IllegalStateException(
                "This artifact is sealed, but what it holds is not the base64 a sealed file " +
                    "carries. It was not produced by the step that seals one.",
            )
        }
    }

    /**
     * Seal [plain] so that [openBytes] can open it — **the exact inverse, and the format is the
     * workflow's.**
     *
     * `openssl enc -aes-256-cbc -pbkdf2 -pass env:KEY -base64` writes: the marker, then the base64
     * of `Salted__`, a random 8-byte salt, and the AES-CBC ciphertext of the *base64 of the file*.
     * This writes the same bytes from Kotlin so the app can seal what it produces — which it could
     * not do before, and which is why "encrypt" had no tool: there was a reader and no writer.
     *
     * **The salt is fresh every call.** Reusing one would make two seals of the same file identical,
     * and a person comparing two artifacts could tell they were the same bytes without opening
     * either.
     *
     * Returns the bytes a file may hold directly; the marker is at the front, which is how
     * [openBytes] recognises its own work.
     */
    fun sealBytes(
        plain: ByteArray,
        key: String,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        if (key.isBlank()) {
            throw IllegalArgumentException(
                "A file cannot be sealed without a key: the key is what makes it readable again.",
            )
        }
        val salt = ByteArray(SALT)
        random.nextBytes(salt)
        val material = derive(key, salt)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(material.copyOfRange(0, KEY), "AES"),
            IvParameterSpec(material.copyOfRange(KEY, KEY + IV)),
        )
        // **The inner base64 is not decoration.** The workflow seals the base64 of the file, because
        // `openssl ... -base64` is what makes the ciphertext one token that survives a log line — and
        // `openBytes` decodes it after opening. Sealing the raw bytes would produce something this
        // app's own reader could not open, which is the one thing a writer must never do.
        val inner = java.util.Base64.getEncoder().encode(plain)
        val cipherText = cipher.doFinal(inner)
        val blob = SALTED + salt + cipherText
        val base64 = java.util.Base64.getEncoder().encode(blob)
        return MARKER.toByteArray(Charsets.US_ASCII) + base64 + '\n'.code.toByte()
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
