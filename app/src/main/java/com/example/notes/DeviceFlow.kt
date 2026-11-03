package com.example.notes

import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * **GitHub's device flow: the secretless way to sign in, and the one a phone can actually do.**
 *
 * The decision is recorded in `3f213fd` (D5). The short version, because the alternatives keep
 * looking reasonable:
 *
 * - **PKCE does not remove the client secret from GitHub's token exchange** — the exchange lists
 *   `client_secret` as required, and PKCE sits on top. A secret in an APK is not a secret.
 * - **The device flow is the only secretless flow GitHub offers for a public client**, and PKCE
 *   explicitly excludes it. That is not a limitation to work around; it is the flow designed for
 *   exactly this case.
 * - **Routing across many OAuth Apps buys nothing here**: the 5,000/h primary limit is per *user*,
 *   and a user-token app does not use the installation pool that would be multiplied.
 *
 * ## What the person sees
 *
 * 1. A short code — `ABCD-1234` — and the address `github.com/login/device`.
 * 2. They type the code on GitHub, on any device, and approve.
 * 3. The app is polling in the background and finishes by itself.
 *
 * **Foreground only.** The poll stops when the app is not on screen: a background poll is a battery
 * cost and a rate-limit cost for a flow that needs a person in front of it anyway.
 *
 * ## The three limits, because they decide the poll's shape
 *
 * | limit | scope | what it means here |
 * |---|---|---|
 * | 50/h device-code requests | **per application** | counts *new* auths; a returning user costs zero |
 * | 2,000/h OAuth token requests | per application | **polling spends this** — an abandoned attempt costs up to 180 polls |
 * | 5,000/h primary API | per user | not ours to multiply |
 *
 * The middle one is why [Poll.intervalMs] is a floor that only ever grows: `slow_down` means the
 * server is telling us we are spending it too fast.
 */
object DeviceFlow {

    /**
     * **The OAuth App's client ID.**
     *
     * A client ID is public by design — it identifies the app, it does not authorise anything — so it
     * lives here rather than in the keystore. What must never be in an APK is the *client secret*,
     * which is exactly why this flow exists instead of the PKCE one.
     *
     * **Empty until the app is registered**, and the flow refuses with a sentence saying so rather
     * than showing a key box and pretending. Registering it is two things outside this repo: make the
     * OAuth App, and tick *Enable Device Flow* in its settings — the second is what `device_flow_disabled`
     * looks like when it is missed.
     */
    const val CLIENT_ID = ""

    /** The two addresses, and the scope. `repo` because the app writes to repositories. */
    const val CODE_URL = "https://github.com/login/device/code"
    const val TOKEN_URL = "https://github.com/login/oauth/access_token"
    const val SCOPE = "repo read:user"

    /** Where a person types the code. Shown with it, because the code is useless alone. */
    const val WHERE = "github.com/login/device"

    /**
     * **What GitHub said, in the shape the flow needs.** Every field is from the response; none is
     * assumed, because the interval and the lifetime are GitHub's to set and they change.
     */
    data class Code(
        /** **What the person types.** Short, human-sized, and useless on its own. */
        val code: String,
        val url: String,
        /**
         * **What the app polls with**, and the half that is never shown.
         *
         * Issued in the same answer as [code] and paired with it — which is why they travel
         * together rather than in two places that could disagree about which sign-in they belong to.
         */
        val device: String,
        /** Seconds between polls. **A floor, not a suggestion** — GitHub asks for 5. */
        val intervalMs: Long,
        /** When the code stops working. `expires_in`, in seconds, turned into a span. */
        val expiresInMs: Long,
    )

    /**
     * **One poll's answer.** Four outcomes, because "not yet" and "no" are different things and a
     * flow that treated them alike would either spin for ever or give up on the first ask.
     */
    sealed interface Poll {
        /** The person has not approved yet. Keep polling, on [intervalMs]. */
        data class Waiting(val intervalMs: Long) : Poll

        /** Approved. [token] is the credential, and it is the only copy — never logged. */
        data class Done(val token: String) : Poll

        /** **`slow_down`: the server is telling us we are polling too fast.** Wait longer. */
        data class Slow(val intervalMs: Long) : Poll

        /** Over: [why] is a sentence a person can act on. */
        data class Failed(val why: String) : Poll
    }

    /**
     * Asks GitHub for a code, and returns it with the two numbers that govern the poll.
     *
     * [post] is injected so a test can drive every branch — the real one is [postForm], and nothing
     * that talks to github.com should run in CI.
     */
    fun requestCode(
        clientId: String = CLIENT_ID,
        post: (String, String) -> Result<String> = ::postForm,
    ): Result<Code> {
        if (clientId.isBlank()) return Result.failure(IllegalStateException(NOT_REGISTERED))
        val body = form(
            "client_id" to clientId,
            "scope" to SCOPE,
        )
        return post(CODE_URL, body).mapCatching { text ->
            val json = JSONObject(text)
            // **GitHub answers 200 with an `error` field for a refused request.** A flow that only
            // checked the status would read a refusal as a code with no fields in it.
            json.optString("error").takeIf { it.isNotEmpty() }?.let { error ->
                throw IllegalStateException(refusal(error, json.optString("error_description")))
            }
            val code = json.optString("user_code")
            val device = json.optString("device_code")
            if (code.isEmpty() || device.isEmpty()) {
                throw IllegalStateException("GitHub answered without a code, so there is nothing to enter.")
            }
            Code(
                code = code,
                url = json.optString("verification_uri", "https://$WHERE"),
                device = device,
                // **Five seconds is GitHub's floor and the default when it says nothing.** Polling
                // faster is what `slow_down` exists to punish.
                intervalMs = json.optLong("interval", 5L).coerceAtLeast(5L) * 1000L,
                expiresInMs = json.optLong("expires_in", 900L).coerceAtLeast(60L) * 1000L,
            )
        }
    }

    /**
     * **One poll.** Called repeatedly by the caller, which owns the waiting.
     *
     * The caller waits [Poll.Waiting.intervalMs] between calls; this function does not sleep, so it is
     * a pure function of the answer and a test can drive it without a clock.
     */
    fun poll(
        deviceCode: String,
        clientId: String = CLIENT_ID,
        post: (String, String) -> Result<String> = ::postForm,
    ): Poll {
        if (clientId.isBlank()) return Poll.Failed(NOT_REGISTERED)
        val body = form(
            "client_id" to clientId,
            "device_code" to deviceCode,
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
        )
        val answer = post(TOKEN_URL, body).getOrElse { failure ->
            // **A transport failure is not a verdict.** The flow keeps polling; the caller's own
            // lifetime bounds it, so a dead network does not become a dead session.
            return Poll.Waiting(5_000L)
        }
        val json = try {
            JSONObject(answer)
        } catch (t: Throwable) {
            return Poll.Failed("GitHub's answer could not be read, so the sign-in did not finish.")
        }
        json.optString("access_token").takeIf { it.isNotEmpty() }?.let { return Poll.Done(it) }
        return when (val error = json.optString("error")) {
            // **The two waiting answers**, and they are different: `authorization_pending` is "not
            // yet", `slow_down` is "too fast". Treating the second as the first would keep spending
            // the 2,000/h token-request limit at the rate that caused it.
            "authorization_pending" -> Poll.Waiting(json.optLong("interval", 5L) * 1000L)
            "slow_down" -> Poll.Slow((json.optLong("interval", 5L) + 5L) * 1000L)
            "expired_token" -> Poll.Failed(
                "The code expired before it was entered. Start again to get a new one.",
            )
            "access_denied" -> Poll.Failed("You declined the request on GitHub, so nothing was signed in.")
            "device_flow_disabled" -> Poll.Failed(
                "This app is not allowed to use the device flow. That is a setting on the OAuth App, " +
                    "not something this phone can fix.",
            )
            "incorrect_client_credentials" -> Poll.Failed(NOT_REGISTERED)
            "" -> Poll.Failed("GitHub answered without a token and without a reason.")
            else -> Poll.Failed(refusal(error, json.optString("error_description")))
        }
    }

    /** The sentence for a person when the app was never registered, which is the state today. */
    const val NOT_REGISTERED =
        "Signing in with GitHub is not set up in this build yet — the app has no OAuth App registered. " +
            "Paste a personal access key instead: github.com/settings/tokens"

    /** One error name turned into something a person can act on. */
    private fun refusal(error: String, description: String): String {
        val said = description.trim()
        return when {
            said.isNotEmpty() -> "GitHub refused: $said"
            error == "unauthorized_client" -> "This app is not registered for the device flow."
            error == "unsupported_grant_type" -> "GitHub did not accept the request's shape, which is a bug in this app."
            else -> "GitHub refused the request ($error)."
        }
    }

    /** `application/x-www-form-urlencoded`, which is what GitHub's two endpoints take. */
    private fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (key, value) -> "$key=${java.net.URLEncoder.encode(value, "UTF-8")}" }

    /**
     * The real request: one `POST`, a form body, and **no credentials of any kind**.
     *
     * `Accept: application/json` is what makes GitHub answer JSON rather than a form-encoded body —
     * the default is the query string, which is a second parser for one response.
     */
    private fun postForm(url: String, body: String): Result<String> {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 30_000
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                setRequestProperty("Accept", "application/json")
            }
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body) }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            // **A non-2xx is still a success here**, because GitHub puts `error` in the body either
            // way and the caller's `when` has a better sentence for each name than a status number
            // does. The status is only read to choose the stream; it is not a verdict.
            Result.success(text)
        } catch (e: Exception) {
            Result.failure(
                IllegalStateException("Could not reach GitHub: ${e.message ?: "no answer"}"),
            )
        } finally {
            connection?.disconnect()
        }
    }
}
