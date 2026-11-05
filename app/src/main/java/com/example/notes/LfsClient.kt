package com.example.notes

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.eclipse.jgit.lfs.Protocol
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * **The half of LFS that JGit cannot do: the conversation over HTTPS.**
 *
 * JGit's own client is used for everything local — the pointer format, the clean filter that moves
 * bytes into `.git/lfs/objects`, the loader that reads them back. Its network half is unusable
 * against a private repository, and the reason is one missing header:
 *
 * [org.eclipse.jgit.lfs.internal.LfsConnectionFactory] builds its batch request with `Accept`,
 * `Content-Type` and `Accept-Encoding` and **no `Authorization`**. Its only authentication path is SSH
 * (`git-lfs-authenticate`), and [Protocol.HDR_AUTH_SSH_PREFIX] is the constant `"Ssh: "`. The
 * `CredentialsProvider` given to a clone authenticates git's *transport*; the LFS connection never
 * sees it.
 *
 * **Measured, and the failure is silent.** A private repository cloned with a valid token and the
 * filter switched on leaves the 127-byte pointer in the working tree, downloads zero objects, and
 * throws nothing. The same clone against a public repository downloads correctly. So the code path
 * works and the credential is the only missing part — which is exactly what this class supplies.
 *
 * Upstream: eclipse-jgit/jgit#11, open, no fix version, reporting the same `rc=401`.
 *
 * ## The three rules this class exists to obey
 *
 * 1. **The token goes on the batch call and nowhere else.** The upload and download URLs are
 *    pre-signed S3 links: their signature is the credential, and adding ours is a signature mismatch,
 *    not merely a redundancy.
 * 2. **A redirect is followed without the token.** The same rule [GitHub.artifactBytes] learned the
 *    hard way — a redirect goes to another host, and sending GitHub's token there is a leak first and
 *    a `401` second.
 * 3. **Nothing here returns a success it did not observe.** Every function answers a [Result] whose
 *    failure names what happened, because a pointer left in a working tree with no message is the
 *    failure mode this whole design exists to remove.
 */
object LfsClient {

    /** What one object's fate was, so a caller can say "three of five arrived". */
    data class Outcome(val oid: String, val ok: Boolean, val why: String? = null)

    /** What a batch call answered: the objects the server already has, and the ones it needs or holds. */
    data class Batch(
        val missing: List<String> = emptyList(),
        val actions: Map<String, Action> = emptyMap(),
        val failed: List<Outcome> = emptyList(),
    )

    /** One object's transfer instructions, as the batch response gives them. */
    data class Action(val href: String, val headers: Map<String, String>)

    /**
     * Where a repository's LFS endpoint is.
     *
     * GitHub's own form is `https://github.com/<owner>/<repo>.git/info/lfs`, which is the remote URL
     * with `.git` and then `/info/lfs`. **Not derived by string surgery on the remote alone**: the
     * remote may carry credentials, a port, or no `.git` at all, so the URL is parsed and rebuilt.
     */
    fun endpointOf(remoteUrl: String): String? = try {
        val url = URL(remoteUrl.trim())
        if (url.protocol != "https" && url.protocol != "http") return null
        // **`.git` is kept, not stripped.** GitHub's own endpoint is
        // `https://github.com/<owner>/<repo>.git/info/lfs` — the suffix goes after `.git`, and a
        // clone URL with `.git` is what the app stores. Stripping it asks a URL that does not exist.
        val path = url.path.trimEnd('/')
        if (path.count { it == '/' } < 2) return null
        val port = if (url.port > 0) ":${url.port}" else ""
        "${url.protocol}://${url.host}$port$path/info/lfs"
    } catch (e: Throwable) {
        null
    }

    /**
     * Asks the server which of [oids] it already holds, and where to put the rest.
     *
     * [operation] is [Protocol.OPERATION_UPLOAD] or [Protocol.OPERATION_DOWNLOAD]; the same endpoint
     * answers both, and the answer's shape is the same: an object with no `actions` and no `error` is
     * one the server already has.
     *
     * **A `401` here is the failure this class was written for**, so it is named rather than passed
     * through: "the token was refused" is actionable, `HTTP 401` is not.
     */
    fun batch(
        endpoint: String,
        token: String,
        operation: String,
        objects: List<Pair<String, Long>>,
    ): Result<Batch> {
        if (objects.isEmpty()) return Result.success(Batch())
        val body = JsonObject().apply {
            addProperty("operation", operation)
            add("transfers", com.google.gson.JsonArray().apply { add("basic") })
            add("objects", com.google.gson.JsonArray().apply {
                objects.forEach { (oid, size) ->
                    add(JsonObject().apply {
                        addProperty("oid", oid)
                        addProperty("size", size)
                    })
                }
            })
        }
        val (code, text) = post(
            "$endpoint/objects/batch",
            body.toString(),
            mapOf(
                "Accept" to Protocol.CONTENTTYPE_VND_GIT_LFS_JSON,
                "Content-Type" to Protocol.CONTENTTYPE_VND_GIT_LFS_JSON,
                // **The one header JGit never sends.** Basic, because that is what the LFS spec
                // defines and what GitHub answered `200` to when measured with curl.
                Protocol.HDR_AUTH to basic(token),
            ),
        )
        if (code == 401 || code == 403) {
            return Result.failure(IllegalStateException(
                "GitHub refused the token for this project's pictures (HTTP $code). " +
                    "Signing in again usually fixes it.",
            ))
        }
        if (code !in 200..299) {
            return Result.failure(IllegalStateException(
                "GitHub would not answer about the pictures (HTTP $code)",
            ))
        }
        return try {
            val root = JsonParser.parseString(text).asJsonObject
            val missing = mutableListOf<String>()
            val actions = mutableMapOf<String, Action>()
            val failed = mutableListOf<Outcome>()
            root.getAsJsonArray("objects")?.forEach { element ->
                val object0 = element.asJsonObject
                val oid = object0.get("oid")?.asString ?: return@forEach
                val error = object0.get("error")
                if (error != null && !error.isJsonNull) {
                    // **A per-object refusal is not a whole-call failure.** Four pictures can arrive
                    // and one be too large; the caller has to be able to say which.
                    failed += Outcome(oid, false, error.asJsonObject.get("message")?.asString)
                    return@forEach
                }
                missing += oid
                val action = object0.getAsJsonObject("actions")?.getAsJsonObject("upload")
                    ?: object0.getAsJsonObject("actions")?.getAsJsonObject("download")
                if (action != null) {
                    actions[oid] = Action(
                        href = action.get("href")?.asString ?: return@forEach,
                        headers = headersOf(action.getAsJsonObject("header")),
                    )
                }
            }
            Result.success(Batch(missing, actions, failed))
        } catch (e: Throwable) {
            Result.failure(IllegalStateException(
                "GitHub's answer about the pictures could not be read: ${Secrets.scrub(e.message ?: "")}",
            ))
        }
    }

    /**
     * Sends one object's bytes to the URL the batch call named.
     *
     * **No `Authorization`.** The URL is pre-signed; its query string is the credential.
     */
    fun upload(action: Action, bytes: ByteArray): Result<Unit> = try {
        val connection = (URL(action.href).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 20_000
            // A picture is megabytes over a phone's network, so this is generous on purpose.
            readTimeout = 120_000
            setFixedLengthStreamingMode(bytes.size)
            action.headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            if (code in 200..299) Result.success(Unit)
            else Result.failure(IllegalStateException("a picture could not be sent (HTTP $code)"))
        } finally {
            connection.disconnect()
        }
    } catch (e: Throwable) {
        Result.failure(IllegalStateException("a picture could not be sent: ${Secrets.scrub(e.message ?: "")}"))
    }

    /**
     * Fetches one object's bytes into the store, **and writes them atomically.**
     *
     * A half-written object is worse than a missing one: the smudge would find the file, treat it as
     * present, and hand a truncated picture to the screen. So the bytes land in a temporary file
     * beside the target and are renamed into place.
     *
     * **Redirects are followed without the token**, for the reason in the class comment.
     */
    fun download(action: Action, target: File, expectedSize: Long): Result<Unit> = try {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".part")
        var first: HttpURLConnection? = null
        var second: HttpURLConnection? = null
        try {
            first = (URL(action.href).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 120_000
                // **Read `Location` ourselves**, so the token cannot travel to the storage host.
                instanceFollowRedirects = false
                action.headers.forEach { (k, v) -> setRequestProperty(k, v) }
            }
            var code = first.responseCode
            var source = first
            if (code in 300..399) {
                val location = first.getHeaderField("Location")
                    ?: return Result.failure(IllegalStateException("GitHub did not say where the picture is"))
                second = (URL(location).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 20_000
                    readTimeout = 120_000
                    instanceFollowRedirects = true
                }
                code = second.responseCode
                source = second
            }
            if (code !in 200..299) {
                return Result.failure(IllegalStateException("a picture could not be fetched (HTTP $code)"))
            }
            temp.outputStream().use { out -> source.inputStream.use { it.copyTo(out) } }
            // **The size is checked, because a redirect body is a valid HTTP 200.** A short object is
            // a corrupt picture that would otherwise be shown as one.
            if (expectedSize > 0 && temp.length() != expectedSize) {
                temp.delete()
                return Result.failure(IllegalStateException(
                    "a picture arrived incomplete (${temp.length()} of $expectedSize bytes)",
                ))
            }
            if (!temp.renameTo(target)) {
                temp.delete()
                return Result.failure(IllegalStateException("a picture could not be stored"))
            }
            Result.success(Unit)
        } finally {
            first?.disconnect()
            second?.disconnect()
        }
    } catch (e: Throwable) {
        Result.failure(IllegalStateException("a picture could not be fetched: ${Secrets.scrub(e.message ?: "")}"))
    }

    /** `Basic base64("x-access-token:<token>")`, which is the form GitHub answered `200` to. */
    fun basic(token: String): String {
        val pair = "x-access-token:$token"
        // **`java.util.Base64`, not `android.util.Base64`.** The latter is a stub in a JVM unit
        // test — it throws "not mocked" — and this class is tested against a local server. The
        // result is the same string; only the dependency on a device changes.
        return "Basic " + java.util.Base64.getEncoder().encodeToString(pair.toByteArray())
    }

    /** The batch response's header object as a plain map, since it is not always present. */
    private fun headersOf(json: JsonObject?): Map<String, String> {
        if (json == null) return emptyMap()
        val out = mutableMapOf<String, String>()
        json.entrySet().forEach { (k, v) ->
            if (v != null && !v.isJsonNull) out[k] = v.asString
        }
        return out
    }

    /** A POST with a body, answering the status and the text. */
    private fun post(url: String, body: String, headers: Map<String, String>): Pair<Int, String> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "MuffyCodes")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return try {
            connection.outputStream.use { it.write(body.toByteArray()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { String(it.readBytes()) } ?: ""
            code to text
        } finally {
            connection.disconnect()
        }
    }
}
