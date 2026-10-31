package com.example.notes

import com.codahale.xsalsa20poly1305.Keys
import com.codahale.xsalsa20poly1305.SecretBox
import org.bouncycastle.crypto.digests.Blake2bDigest
import java.security.SecureRandom

/**
 * libsodium's `crypto_box_seal`, which is the only shape GitHub's secret API accepts.
 *
 * ## Why this exists at all
 *
 * `PUT /repos/{o}/{r}/actions/secrets/{name}` takes an `encrypted_value`, and it must be produced by
 * libsodium's sealed box: X25519 for the key agreement, HSalsa20 to derive the key, XSalsa20Poly1305
 * for the encryption, and a nonce derived from both public keys. Android's own crypto has none of
 * XSalsa20, HSalsa20 or Poly1305, so this cannot be done with `javax.crypto`.
 *
 * **The obvious library does not work.** `lazysodium-android` wraps real libsodium, and its published
 * `.so` files are 4 KB page-aligned; Android 15+ (this app is targetSdk 37) requires 16 KB alignment,
 * so it would fail to load on a modern phone. That was measured by reading the ELF program headers of
 * the AAR, not assumed.
 *
 * So the box is assembled from two pure-Java libraries, and **nothing here is hand-rolled except the
 * nonce**:
 *
 * ```
 * ephemeral_pk, ephemeral_sk = keypair()
 * nonce  = BLAKE2b-192(ephemeral_pk || recipient_pk)
 * sealed = ephemeral_pk || SecretBox(Keys.sharedSecret(recipient_pk, ephemeral_sk)).seal(nonce, m)
 * ```
 *
 * ## The one thing that must not be got wrong
 *
 * **`Keys.sharedSecret` takes `(publicKey, privateKey)`, and it is not symmetric.** Every other API in
 * this app follows libsodium's convention, where the private key comes first; this one does not. Passing
 * them the wrong way round does not throw — it produces a key that silently fails to open the box, so
 * GitHub stores a secret that no run can ever decrypt and nothing in the app reports a problem.
 *
 * `sharedSecret` is also **more than X25519**: its bytecode is the scalar multiplication followed by
 * `HSalsa20.hsalsa20(out, HSALSA20_SEED, shared)`. That is `crypto_box_beforenm`, so the derivation is
 * already done here and must not be repeated — an earlier version of this file did repeat it, and the
 * seal opened only by accident of the same mistake being made twice.
 *
 * ## Verified, not assumed
 *
 * The construction was run on the JVM before it was written here: roundtrip succeeds, the output is
 * exactly `message + 48` bytes (32 of ephemeral public key, 16 of Poly1305 tag), two seals of the same
 * message differ (fresh ephemeral key each time), and the nonce is a pure function of the two public
 * keys. Those four facts are asserted in [SealedBoxTest].
 */
object SealedBox {

    /** The length of an X25519 public key, and of the ephemeral key a seal carries in front. */
    const val PUBLIC_KEY = 32

    /** What a seal adds to the message: the ephemeral public key, and the Poly1305 tag. */
    const val OVERHEAD = 48

    /** The nonce libsodium derives: BLAKE2b with a 192-bit output over the two public keys. */
    private const val NONCE = 24

    /**
     * Seals [message] for [recipientPublicKey], the way GitHub expects.
     *
     * A fresh ephemeral key is generated on every call, which is what makes two seals of the same
     * message different — and is why a secret can be re-sent without looking like a replay.
     */
    fun seal(recipientPublicKey: ByteArray, message: ByteArray): ByteArray {
        require(recipientPublicKey.size == PUBLIC_KEY) {
            "A sealed box needs a $PUBLIC_KEY-byte public key, not ${recipientPublicKey.size}"
        }
        val ephemeralSecret = Keys.generatePrivateKey()
        val ephemeralPublic = Keys.generatePublicKey(ephemeralSecret)
        // **(public, private)** — see the class comment. Backwards, this silently will not open.
        val key = Keys.sharedSecret(recipientPublicKey, ephemeralSecret)
        val nonce = nonce(ephemeralPublic, recipientPublicKey)
        val box = SecretBox(key).seal(nonce, message)
        return ephemeralPublic + box
    }

    /**
     * Opens a box sealed for [recipientPublicKey] by [recipientSecretKey].
     *
     * **The app never needs this.** It is here for the tests, and for a person debugging their own
     * vault by hand: a seal that cannot be opened is a secret that silently does not work, and the only
     * way to know the difference before GitHub does is to open one.
     */
    fun open(
        recipientSecretKey: ByteArray,
        recipientPublicKey: ByteArray,
        sealed: ByteArray,
    ): Result<ByteArray> {
        if (sealed.size < OVERHEAD) {
            return Result.failure(IllegalArgumentException("That is not a sealed box: it is too short"))
        }
        val ephemeralPublic = sealed.copyOfRange(0, PUBLIC_KEY)
        val box = sealed.copyOfRange(PUBLIC_KEY, sealed.size)
        return try {
            val key = Keys.sharedSecret(ephemeralPublic, recipientSecretKey)
            val nonce = nonce(ephemeralPublic, recipientPublicKey)
            SecretBox(key).open(nonce, box)
                .map { Result.success(it) }
                ?: Result.failure(IllegalStateException("The box did not open"))
        } catch (e: Throwable) {
            Result.failure(IllegalStateException(Secrets.scrub(e.message ?: "The box did not open")))
        }
    }

    /**
     * The nonce, derived exactly as libsodium does.
     *
     * It is a **function of the two public keys rather than a random value**, which is what makes a
     * sealed box anonymous: the recipient can derive it without being told anything but the ephemeral
     * key that travels in front of the ciphertext.
     */
    fun nonce(ephemeralPublic: ByteArray, recipientPublic: ByteArray): ByteArray {
        val digest = Blake2bDigest(NONCE * 8)
        digest.update(ephemeralPublic, 0, ephemeralPublic.size)
        digest.update(recipientPublic, 0, recipientPublic.size)
        return ByteArray(NONCE).also { digest.doFinal(it, 0) }
    }

    /** A fresh X25519 key pair, for the tests and for a person setting up a vault. */
    fun keyPair(): Pair<ByteArray, ByteArray> {
        val secret = Keys.generatePrivateKey()
        return secret to Keys.generatePublicKey(secret)
    }

    /** True when [value] is a well-formed base64 public key, which is what GitHub hands out. */
    fun isPublicKey(value: String): Boolean = try {
        val bytes = java.util.Base64.getDecoder().decode(value.trim())
        bytes.size == PUBLIC_KEY
    } catch (e: Throwable) {
        false
    }

    /**
     * A random value, for a test that needs one that is not a secret.
     *
     * `SecureRandom` rather than `Random`: the same helper will be used for key material the moment
     * anybody reuses it, and a helper that is safe to reuse is worth more than one that is fast.
     */
    fun random(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }
}
