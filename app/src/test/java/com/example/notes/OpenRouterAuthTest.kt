package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.security.SecureRandom

/**
 * The OpenRouter sign-in, checked where it can be checked without a network.
 *
 * **The one test that matters most is the RFC vector.** Everything else here asserts this app's own
 * choices; that test asserts the arithmetic against the specification's own worked example, and it is
 * the only thing standing between "the challenge is a hash" and "the challenge is the hash OpenRouter
 * expects".
 */
class OpenRouterAuthTest {

    /**
     * RFC 7636 Appendix B, verbatim.
     *
     * If this ever fails, the app is sending a challenge that no verifier can match and every
     * exchange will be refused — with an error that says the code was invalid, which points at the
     * person's code rather than at the hash.
     */
    @Test
    fun `the challenge matches the RFC's own worked example`() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            OpenRouterAuth.challengeOf("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun `a fresh verifier is long enough to be worth having`() {
        val attempt = OpenRouterAuth.begin()
        // RFC 7636 4.1: 43 to 128 characters from the unreserved set.
        assertTrue("43 or more: ${attempt.verifier.length}", attempt.verifier.length in 43..128)
        assertTrue(
            "unreserved characters only: ${attempt.verifier}",
            attempt.verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' },
        )
    }

    @Test
    fun `two attempts do not share a verifier`() {
        val first = OpenRouterAuth.begin()
        val second = OpenRouterAuth.begin()
        assertFalse("a reused verifier protects nothing", first.verifier == second.verifier)
        assertFalse(first.challenge == second.challenge)
    }

    @Test
    fun `the address carries the challenge, the method, and no callback`() {
        val attempt = OpenRouterAuth.begin()
        val url = attempt.url()
        assertTrue("the challenge: $url", url.contains("code_challenge=${attempt.challenge}"))
        assertTrue("S256: $url", url.contains("code_challenge_method=S256"))
        assertTrue("the label: $url", url.contains("key_label=${OpenRouterAuth.KEY_LABEL}"))
        // **The absence is the design.** A callback_url would need somewhere to return to, and this
        // form exists so that there is nowhere to return to.
        assertFalse("no callback_url: $url", url.contains("callback_url"))
    }

    @Test
    fun `the exchange sends the verifier and the method, and nothing else`() {
        var sentUrl: String? = null
        var sentBody: String? = null
        val key = OpenRouterAuth.exchange("a-code", "a-verifier") { url, body ->
            sentUrl = url
            sentBody = body
            Result.success("""{"key":"sk-or-v1-abc"}""")
        }
        assertEquals("the key comes back", "sk-or-v1-abc", key.getOrNull())
        assertEquals("OpenRouter's exchange endpoint", OpenRouterAuth.EXCHANGE, sentUrl)
        val json = JSONObject(sentBody!!)
        assertEquals("a-code", json.getString("code"))
        assertEquals("a-verifier", json.getString("code_verifier"))
        assertEquals("S256", json.getString("code_challenge_method"))
        // **No credential of any kind**, because this flow has none to send. A `client_secret` here
        // would mean the APK ships one, which is the thing this flow was chosen to avoid.
        assertFalse("no secret in the body", sentBody!!.contains("client_secret", ignoreCase = true))
    }

    @Test
    fun `an answer with no key is a failure, not an empty key`() {
        val result = OpenRouterAuth.exchange("a-code", "a-verifier") { _, _ ->
            Result.success("""{"something":"else"}""")
        }
        assertTrue("must fail: ${result.getOrNull()}", result.isFailure)
        assertNotNull(result.exceptionOrNull())
    }

    @Test
    fun `an empty code is refused before any request is made`() {
        var called = false
        val result = OpenRouterAuth.exchange("   ", "a-verifier") { _, _ ->
            called = true
            Result.success("""{"key":"k"}""")
        }
        assertTrue(result.isFailure)
        assertFalse("nothing was sent", called)
    }

    @Test
    fun `a spent code says so, because the remedy is a new code`() {
        val said = OpenRouterAuth.refusal(403, """{"error":"Invalid code or code_verifier"}""")
        assertTrue("mentions the ten minutes: $said", said.contains("ten minutes"))
        assertTrue("says a code is single use: $said", said.contains("once"))
    }

    @Test
    fun `an expired code says expiry rather than blame`() {
        val said = OpenRouterAuth.refusal(403, """{"error":"Authorization code expired"}""")
        assertTrue("says expired: $said", said.contains("expired"))
        assertFalse("does not blame the person: $said", said.contains("you did wrong"))
    }

    @Test
    fun `a server error is described as theirs, not ours`() {
        val said = OpenRouterAuth.refusal(503, "")
        assertTrue("names OpenRouter: $said", said.contains("OpenRouter"))
        assertTrue("says to try again: $said", said.contains("Try again"))
    }

    @Test
    fun `an unrecognised answer still names the status`() {
        val said = OpenRouterAuth.refusal(418, "")
        assertTrue("names the status: $said", said.contains("418"))
    }

    /**
     * The verifier is what makes a *displayed* code safe.
     *
     * Worth asserting as its own claim rather than leaving implicit: the whole reason the headless
     * form is acceptable is that a code seen by somebody else is useless without this string.
     */
    @Test
    fun `a verifier is not recoverable from the challenge`() {
        val attempt = OpenRouterAuth.begin(SecureRandom())
        assertFalse(
            "the challenge must not contain the verifier",
            attempt.challenge.contains(attempt.verifier),
        )
        assertEquals(
            "and it must be the hash of it",
            attempt.challenge,
            OpenRouterAuth.challengeOf(attempt.verifier),
        )
    }
}
