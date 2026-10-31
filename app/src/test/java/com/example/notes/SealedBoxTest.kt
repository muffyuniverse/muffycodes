package com.example.notes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The sealed box, proved rather than assumed.
 *
 * **This is the one piece of the secret path that can be verified completely offline**, and it must be:
 * a box that seals but does not open is a secret GitHub stores and no run can read, and nothing in the
 * app would report a problem. The four facts below were measured on the JVM before any of this was
 * written into the app, and they are the four that would catch every mistake this file could make.
 *
 * The construction was checked against the shape libsodium documents — X25519, HSalsa20, a
 * BLAKE2b-192 nonce over both public keys — but **not against libsodium itself**, which is not
 * installed in this container. What is asserted here is self-consistency and the arithmetic; the
 * compatibility with GitHub's endpoint is the one thing a test cannot prove, and the first real push is
 * what will.
 */
class SealedBoxTest {

    @Test
    fun `a sealed box opens again`() {
        val (secret, public) = SealedBox.keyPair()
        val message = "my-signing-password".toByteArray()

        val sealed = SealedBox.seal(public, message)
        val opened = SealedBox.open(secret, public, sealed).getOrThrow()

        assertArrayEquals("the message comes back", message, opened)
    }

    @Test
    fun `a seal is the message plus exactly forty-eight bytes`() {
        // **The arithmetic that says the layout is right**: 32 bytes of ephemeral public key in front,
        // 16 bytes of Poly1305 tag behind. A different total would mean the ephemeral key is the wrong
        // size or the tag is missing, and GitHub would reject the value or store one that cannot open.
        val (_, public) = SealedBox.keyPair()
        for (size in listOf(0, 1, 19, 100, 4096)) {
            val sealed = SealedBox.seal(public, ByteArray(size))
            assertEquals(
                "a $size-byte message seals to $size + ${SealedBox.OVERHEAD}",
                size + SealedBox.OVERHEAD,
                sealed.size,
            )
        }
    }

    @Test
    fun `two seals of the same message are different`() {
        // **A fresh ephemeral key every time**, which is what makes the scheme anonymous and what lets
        // the same secret be re-sent without looking like a replay. A cached ephemeral key would make
        // this test fail and would also mean two secrets shared a nonce.
        val (secret, public) = SealedBox.keyPair()
        val message = "the same value twice".toByteArray()

        val first = SealedBox.seal(public, message)
        val second = SealedBox.seal(public, message)

        assertFalse("the ephemeral key is fresh", first.contentEquals(second))
        // **And both still open.** A different ephemeral key means a different derived nonce, so this
        // is the assertion that would fail if the nonce were computed from the wrong pair.
        assertArrayEquals(message, SealedBox.open(secret, public, first).getOrThrow())
        assertArrayEquals(message, SealedBox.open(secret, public, second).getOrThrow())
    }

    @Test
    fun `a box sealed for somebody else does not open`() {
        // **The property that makes it a seal.** If this passed, the recipient key would not be in the
        // derivation at all.
        val (secret, public) = SealedBox.keyPair()
        val (otherSecret, _) = SealedBox.keyPair()
        val sealed = SealedBox.seal(public, "not for you".toByteArray())

        assertTrue(
            "another key cannot open it",
            SealedBox.open(otherSecret, public, sealed).isFailure,
        )
    }

    @Test
    fun `a tampered box does not open`() {
        // XSalsa20Poly1305 is authenticated, so a single flipped bit must be caught rather than
        // decrypted into rubbish. A silent wrong answer here would be a wrong password written into a
        // secret that then signs nothing.
        val (secret, public) = SealedBox.keyPair()
        val sealed = SealedBox.seal(public, "the real value".toByteArray())
        // Past the ephemeral key, so the change lands in the ciphertext rather than the header.
        sealed[SealedBox.PUBLIC_KEY + 2] = (sealed[SealedBox.PUBLIC_KEY + 2].toInt() xor 0x01).toByte()

        assertTrue("the tag catches it", SealedBox.open(secret, public, sealed).isFailure)
    }

    @Test
    fun `the nonce is a function of the two public keys, and of nothing else`() {
        // It is derived rather than random — that is what lets the recipient work it out from the
        // ephemeral key travelling in front of the ciphertext. Two calls must therefore agree, and two
        // different ephemeral keys must not.
        val (_, one) = SealedBox.keyPair()
        val (_, two) = SealedBox.keyPair()

        assertArrayEquals(
            "the same pair gives the same nonce",
            SealedBox.nonce(one, two),
            SealedBox.nonce(one, two),
        )
        assertFalse(
            "a different pair gives a different one",
            SealedBox.nonce(one, two).contentEquals(SealedBox.nonce(two, one)),
        )
        assertEquals("and it is 24 bytes", 24, SealedBox.nonce(one, two).size)
    }

    @Test
    fun `a public key that is the wrong size is refused before anything is sealed`() {
        // **A guard rather than a courtesy.** `Keys.sharedSecret` would read 32 bytes out of a shorter
        // array and produce a key nobody can open, so the size is checked where the failure can still
        // be reported — and a refusal is the assertion, not merely "it did not crash".
        val refused = try {
            SealedBox.seal(ByteArray(31), "x".toByteArray())
            false
        } catch (e: IllegalArgumentException) {
            true
        }
        assertTrue("31 bytes is not a public key", refused)
    }

    @Test
    fun `a base64 public key is recognised by its decoded length`() {
        // GitHub hands the key over as base64, so this is the shape the app actually receives.
        val (_, public) = SealedBox.keyPair()
        val encoded = Base64.getEncoder().encodeToString(public)

        assertTrue("a real key is accepted", SealedBox.isPublicKey(encoded))
        assertFalse("a short one is not", SealedBox.isPublicKey(Base64.getEncoder().encodeToString(ByteArray(16))))
        assertFalse("and neither is nonsense", SealedBox.isPublicKey("not base64 at all!!"))
        assertFalse("nor an empty string", SealedBox.isPublicKey(""))
    }
}
