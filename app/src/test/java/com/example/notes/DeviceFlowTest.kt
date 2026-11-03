package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The device flow's rules, as functions of GitHub's answers.**
 *
 * Every test here drives [DeviceFlow] through an injected `post`, because nothing that talks to
 * github.com should run in CI — the flow's decisions are what can be wrong, and they are all made
 * from the response body.
 */
class DeviceFlowTest {

    private fun json(vararg pairs: Pair<String, Any>): String =
        pairs.joinToString(",", "{", "}") { (k, v) ->
            if (v is Int || v is Long) "\"$k\":$v" else "\"$k\":\"$v\""
        }

    // ---- asking for a code --------------------------------------------------

    @Test
    fun `a code is read with the interval and lifetime GitHub set`() {
        val asked = mutableListOf<String>()
        val code = DeviceFlow.requestCode("abc123") { _, body ->
            asked += body
            Result.success(
                json(
                    "device_code" to "dev-1",
                    "user_code" to "ABCD-1234",
                    "verification_uri" to "https://github.com/login/device",
                    "expires_in" to 900,
                    "interval" to 5,
                ),
            )
        }.getOrThrow()

        assertEquals("the code the person types", "ABCD-1234", code.code)
        assertEquals("and where they type it", "https://github.com/login/device", code.url)
        assertEquals("**five seconds is the floor**", 5_000L, code.intervalMs)
        assertEquals("and it lives fifteen minutes", 900_000L, code.expiresInMs)
        assertTrue("the client id went out", asked.single().contains("client_id=abc123"))
    }

    @Test
    fun `an interval below five seconds is raised to five, because that is the floor`() {
        // **GitHub asks for 5 and punishes faster with `slow_down`.** A response that says 1 is a
        // response this app should not obey: obeying it spends the 2,000/h token-request limit at
        // five times the rate the server allows.
        val code = DeviceFlow.requestCode("abc123") { _, _ ->
            Result.success(
                json("device_code" to "d", "user_code" to "U", "interval" to 1, "expires_in" to 900),
            )
        }.getOrThrow()

        assertEquals("raised to the floor", 5_000L, code.intervalMs)
    }

    @Test
    fun `a build with no client id says so instead of showing a key box`() {
        // **The bug this prevents**: the onboarding link said "Connect GitHub" and opened a token
        // field, because the device flow was never built and nothing said so. An unregistered build
        // now answers with the sentence that sends a person to the token page on purpose.
        val result = DeviceFlow.requestCode("") { _, _ -> Result.success("{}") }

        assertTrue("it failed", result.isFailure)
        assertEquals(
            "with the sentence that says what to do",
            DeviceFlow.NOT_REGISTERED,
            result.exceptionOrNull()?.message,
        )
    }

    @Test
    fun `an error in a 200 answer is a refusal, not an empty code`() {
        // **GitHub answers 200 with an `error` field for a refused request.** A flow that only
        // checked the status would read that as a code with no fields in it.
        val result = DeviceFlow.requestCode("abc123") { _, _ ->
            Result.success(
                json("error" to "unauthorized_client", "error_description" to "that app is not registered"),
            )
        }

        assertTrue("it failed", result.isFailure)
        assertTrue(
            "and said why: ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message?.contains("that app is not registered") == true,
        )
    }

    // ---- polling ------------------------------------------------------------

    @Test
    fun `waiting is not a failure, and keeps the interval`() {
        val poll = DeviceFlow.poll("dev-1", "abc123") { _, _ ->
            Result.success(json("error" to "authorization_pending", "interval" to 5))
        }

        assertTrue("still waiting: $poll", poll is DeviceFlow.Poll.Waiting)
        assertEquals("on GitHub's interval", 5_000L, (poll as DeviceFlow.Poll.Waiting).intervalMs)
    }

    @Test
    fun `slow down waits longer rather than the same`() {
        // **`slow_down` is "too fast", not "not yet".** Treating the two alike is what spends the
        // token-request limit at the rate that caused the complaint.
        val poll = DeviceFlow.poll("dev-1", "abc123") { _, _ ->
            Result.success(json("error" to "slow_down", "interval" to 5))
        }

        assertTrue("it is its own answer: $poll", poll is DeviceFlow.Poll.Slow)
        assertEquals("ten seconds, not five", 10_000L, (poll as DeviceFlow.Poll.Slow).intervalMs)
    }

    @Test
    fun `an approved code gives the token`() {
        val poll = DeviceFlow.poll("dev-1", "abc123") { _, _ ->
            Result.success(json("access_token" to "gho_secret", "token_type" to "bearer"))
        }

        assertEquals(
            "the token, and the only copy of it",
            "gho_secret",
            (poll as DeviceFlow.Poll.Done).token,
        )
    }

    @Test
    fun `an expired code says to start again, and a declined one says nothing happened`() {
        // **The two endings a person can cause**, and each needs its own sentence: "start again" is
        // an instruction, "you declined" is a statement that no sign-in occurred.
        val expired = DeviceFlow.poll("d", "abc123") { _, _ ->
            Result.success(json("error" to "expired_token"))
        }
        val declined = DeviceFlow.poll("d", "abc123") { _, _ ->
            Result.success(json("error" to "access_denied"))
        }

        assertTrue("expired fails: $expired", expired is DeviceFlow.Poll.Failed)
        assertTrue(
            "and says to start again: ${(expired as DeviceFlow.Poll.Failed).why}",
            expired.why.contains("Start again"),
        )
        assertTrue("declined fails: $declined", declined is DeviceFlow.Poll.Failed)
        assertTrue(
            "and says nothing was signed in: ${(declined as DeviceFlow.Poll.Failed).why}",
            declined.why.contains("declined"),
        )
    }

    @Test
    fun `a network failure is not a verdict, so the poll keeps going`() {
        // **A dead network must not become a dead session.** The caller's own lifetime bounds the
        // flow, so a transport failure is "not yet" rather than "no".
        val poll = DeviceFlow.poll("d", "abc123") { _, _ ->
            Result.failure(IllegalStateException("Could not reach GitHub"))
        }

        assertTrue("still waiting: $poll", poll is DeviceFlow.Poll.Waiting)
    }

    @Test
    fun `a missing device-flow setting is named as a setting, not as a phone problem`() {
        // `device_flow_disabled` is the one failure the *developer* causes by forgetting to tick a
        // box on the OAuth App. A person reading "GitHub refused" would try again for ever.
        val poll = DeviceFlow.poll("d", "abc123") { _, _ ->
            Result.success(json("error" to "device_flow_disabled"))
        }

        val why = (poll as DeviceFlow.Poll.Failed).why
        assertTrue("it names the setting: $why", why.contains("setting on the OAuth App"))
        // **"Not something this phone can fix" is the point**, not a phrase to avoid — it tells a
        // person not to keep trying on their side. The assertion is on the words that send them to
        // the developer, not on a phrase that happens to contain them.
        assertFalse("and does not read as a retryable failure: $why", why.startsWith("GitHub refused"))
    }

    @Test
    fun `the shipped build names a registered app, and refuses to name a secret`() {
        // **The bug this prevents is the one that shipped**: the link said "Connect GitHub", the flow
        // was never built, and the person got a key box. A build with an empty client ID is that
        // state again, and it fails *silently* — `NOT_REGISTERED` reads like a build problem rather
        // than the app being unregistered, so nobody notices for a release.
        assertTrue(
            "the build carries a client ID: ${DeviceFlow.CLIENT_ID}",
            DeviceFlow.CLIENT_ID.isNotBlank(),
        )
        // **A client ID is public by design** — it is in the URL of every authorize request. A
        // client *secret* is the opposite, and this repository is public: the device flow exists
        // precisely because GitHub makes the secret unnecessary for it.
        assertTrue(
            "and it is a client ID, not something secret: ${DeviceFlow.CLIENT_ID}",
            DeviceFlow.CLIENT_ID.startsWith("Iv1.") || DeviceFlow.CLIENT_ID.startsWith("Ov"),
        )
    }

    @Test
    fun `a refused client id is not reported as an unconfigured build`() {
        // **Two different sentences for two different things.** "Not set up in this build" is false
        // when the build names an app, and it sends a person looking for a build problem instead of
        // checking the app's settings.
        val poll = DeviceFlow.poll("d", "Ov23liwrong") { _, _ ->
            Result.success(json("error" to "incorrect_client_credentials"))
        }

        val why = (poll as DeviceFlow.Poll.Failed).why
        assertEquals("its own sentence", DeviceFlow.ID_REFUSED, why)
        assertTrue("which points at the app, not the build: $why", why.contains("OAuth App is still there"))
    }

    @Test
    fun `the poll sends the grant type GitHub requires`() {
        // The three fields are the whole request, and a wrong `grant_type` is
        // `unsupported_grant_type` — a refusal that looks like a bug in the app.
        var sent = ""
        DeviceFlow.poll("dev-1", "abc123") { _, body ->
            sent = body
            Result.success(json("error" to "authorization_pending"))
        }

        assertTrue(
            "**the device grant type, spelled exactly**: $sent",
            sent.contains("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Adevice_code"),
        )
        assertTrue("the device code", sent.contains("device_code=dev-1"))
        assertTrue("and the client", sent.contains("client_id=abc123"))
    }
}
