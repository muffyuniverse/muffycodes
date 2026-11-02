package com.example.notes

import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.json.JSONObject

/**
 * OpenRouter's PKCE sign-in: the code the person copies back becomes an API key.
 *
 * **Why this flow and not a callback.** OpenRouter supports a `callback_url`, and every version of it
 * needs somewhere for the browser to return to — a deep link with an intent filter, or a Custom Tab,
 * or a server. All three are machinery that has to exist before the first person can connect. The
 * **headless** form has none of it: the code is displayed on OpenRouter's own page, the person copies
 * it into the app, and the app exchanges it. One text field instead of an intent filter, a manifest
 * change and a redirect URI that has to be registered and kept correct.
 *
 * **The PKCE verifier is what makes the copied code safe.** A code shown on screen could otherwise be
 * used by anybody who saw it; with `S256`, exchanging it also requires the verifier, which never
 * leaves this phone. OpenRouter's own documentation says the challenge is "required in this mode" for
 * exactly that reason.
 *
 * **What is deliberately absent: `state`.** The callback form returns `state` so a client can match a
 * response to a request and refuse a forged one. In headless mode OpenRouter does not return it at
 * all — there is no redirect to forge. The verifier is doing that job, and saying so here is the
 * difference between a missing check and a check that has no place to run.
 */
object OpenRouterAuth {

    /** Where the person authorises, and what the app labels the key it asks for. */
    const val AUTHORISE = "https://openrouter.ai/auth"
    const val EXCHANGE = "https://openrouter.ai/api/v1/auth/keys"

    /**
     * The name given to the key OpenRouter mints.
     *
     * **Set explicitly, because the alternative is a key the person cannot identify.** OAuth mints a
     * *new* key on every connection, so a person who connects twice has two live keys; without a
     * label they are indistinguishable in the dashboard, and revoking the right one becomes
     * guesswork. This is the one field that makes the litter findable.
     */
    const val KEY_LABEL = "Muffycodes"

    /**
     * A verifier and the challenge derived from it, both for one attempt.
     *
     * [verifier] stays on this phone and is spent once; [challenge] travels in the URL.
     */
    data class Attempt(val verifier: String, val challenge: String) {
        /**
         * The address to open. No `callback_url`, which is what makes this the headless form.
         */
        fun url(): String =
            "$AUTHORISE?code_challenge=$challenge" +
                "&code_challenge_method=S256" +
                "&key_label=$KEY_LABEL"
    }

    /** How long an attempt is worth keeping: OpenRouter's code expires in ten minutes. */
    const val LIFETIME_MS = 10 * 60 * 1000L

    /**
     * A fresh attempt.
     *
     * **43 to 128 characters from the unreserved set**, per RFC 7636 §4.1, and generated from
     * [SecureRandom] because a verifier that can be guessed protects nothing. 32 random bytes
     * base64url-encoded is 43 characters — the shortest length the RFC allows, which is also the
     * least to type if it ever has to be shown.
     */
    fun begin(random: SecureRandom = SecureRandom()): Attempt {
        val bytes = ByteArray(32).also { random.nextBytes(it) }
        val verifier = base64Url(bytes)
        return Attempt(verifier = verifier, challenge = challengeOf(verifier))
    }

    /**
     * `BASE64URL(SHA256(verifier))`, which is the whole of the `S256` method.
     *
     * Base64url without padding: the standard alphabet's `+` and `/` are not safe in a query string
     * without escaping, and `=` is padding that the RFC says to omit.
     */
    fun challengeOf(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return base64Url(digest)
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /**
     * The key that [code] is worth, or the sentence to show when it is not.
     *
     * **The exchange is a `POST` with no credentials.** There is no client id and no secret in this
     * flow: the verifier is the proof, and OpenRouter ties the new key to whichever account
     * authorised the code. That is what makes this safe to ship in an APK.
     */
    fun exchange(
        code: String,
        verifier: String,
        post: (String, String) -> Result<String> = ::postJson,
    ): Result<String> {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalStateException("There is no code to exchange."))
        }
        val body = JSONObject()
            .put("code", trimmed)
            .put("code_verifier", verifier)
            .put("code_challenge_method", "S256")
            .toString()
        val answer = post(EXCHANGE, body)
        return answer.mapCatching { text ->
            val key = JSONObject(text).optString("key").orEmpty()
            if (key.isEmpty()) {
                throw IllegalStateException("OpenRouter answered without a key.")
            }
            key
        }
    }

    /**
     * What a failed exchange means, in words a person can act on.
     *
     * **Three failures, three sentences**, because the remedy differs and one of them is not a
     * failure at all: a code is single-use and dies after ten minutes, so "start again" is the
     * answer there, while a verifier mismatch means the app lost the attempt it began.
     */
    fun refusal(status: Int, body: String): String = when {
        body.contains("Invalid code or code_verifier", ignoreCase = true) ->
            "That code was not accepted. A code can only be used once and expires after ten " +
                "minutes — ask OpenRouter for a new one."
        body.contains("expired", ignoreCase = true) ->
            "That code has expired. Codes last ten minutes; ask OpenRouter for a new one."
        body.contains("Invalid code_challenge_method", ignoreCase = true) ->
            "OpenRouter refused the challenge method, which is a bug in this app rather than " +
                "something you did."
        status == 403 -> "OpenRouter refused the exchange. The code may already have been used."
        status == 405 -> "OpenRouter refused the request's shape, which is a bug in this app."
        status in 500..599 -> "OpenRouter is having trouble right now. Try again in a moment."
        else -> "OpenRouter answered $status, so the code was not exchanged."
    }

    /** The real exchange: one `POST`, no credentials, a JSON body. */
    private fun postJson(url: String, body: String): Result<String> {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 30_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body) }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status in 200..299) {
                Result.success(text)
            } else {
                Result.failure(IllegalStateException(refusal(status, text)))
            }
        } catch (e: Exception) {
            // A connection that never answered is a different sentence from a refusal, and the
            // person can do something about this one: try again.
            Result.failure(
                IllegalStateException("Could not reach OpenRouter: ${e.message ?: "no answer"}"),
            )
        } finally {
            connection?.disconnect()
        }
    }
}
