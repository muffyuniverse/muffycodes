package com.example.notes

import com.example.notes.browser.Addresses
import java.net.HttpURLConnection
import java.net.URL

/**
 * **Getting bytes from a URL, which nothing in the app could do.**
 *
 * The gap was structural rather than an oversight. [com.example.notes.browser.Browser] has exactly two
 * methods — `text()` and `shot()` — so a page's *text* could arrive and a *screenshot* could be saved,
 * but no URL could become a file. `artifact` looks like the thing and is not: it takes a **run id**,
 * goes through `ciWhere()` to a repository the account owns, and its own refusal says *"Say which
 * run"*. A URL is not a run.
 *
 * An interface rather than a function so the suite never opens a socket — the same rule `Browser`
 * follows, and the same reason: a tool that could only be tested by downloading something is a tool
 * tested by hand or not at all.
 */
interface Fetcher {

    /**
     * The bytes at [url], or a failure whose message is the reason.
     *
     * **A refusal is not an exception here.** A 404, a size past the cap and a connection that never
     * answered are three different things a person may need to hear, so the failure carries a
     * sentence rather than a stack trace.
     */
    fun bytes(url: String, limitBytes: Long): Result<ByteArray>

    /**
     * **One request, with the verb and the body the caller chose** — and the status, kept.
     *
     * [bytes] throws the status away because a download either worked or did not. An API's whole
     * vocabulary is statuses: `201` means a thing was made, `204` means it was and there is nothing
     * to read, `404` means the thing is not there, and `422` means the shape was wrong. A tool that
     * reported "it worked" for all of them would teach the model to stop checking.
     *
     * **The same four rules [bytes] follows, because they are what makes a request safe rather than
     * what makes a download work:**
     *
     * 1. the address check runs on **every redirect hop**, not just the URL the caller typed;
     * 2. the size is counted **while reading**, because `Content-Length` is a claim;
     * 3. the status is returned rather than thrown, so a 4xx body is a diagnostic and not an error;
     * 4. `Accept` and `User-Agent` are always set, because an API may require them.
     *
     * **A redirect on anything but a GET is returned, not followed.** A `POST` redirected to a `GET`
     * silently loses its body, and GitHub's API does exactly that when a repository is renamed. The
     * caller is told the status and where it points, and decides.
     */
    fun request(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: ByteArray?,
        limitBytes: Long,
    ): Result<Answer>

    /**
     * What a request came back with: **the number, the headers, and the bytes.**
     *
     * The headers are kept because two of them are load-bearing for an API client and neither is in
     * the body: `Location` (where a redirect points) and the rate-limit headers, which are how a
     * caller learns it is about to be refused before it is.
     */
    data class Answer(
        val status: Int,
        val headers: Map<String, String>,
        val bytes: ByteArray,
    ) {
        /** The body as text, for a caller that is going to read it rather than file it. */
        fun text(): String = String(bytes, Charsets.UTF_8)

        /** True when the server said the request worked. */
        val ok: Boolean get() = status in 200..299
    }

    companion object {

        /** No fetcher, for a tool set built without one — and every test that is not about `fetch`. */
        val NONE: Fetcher = object : Fetcher {
            override fun bytes(url: String, limitBytes: Long): Result<ByteArray> =
                Result.failure(
                    IllegalStateException(
                        "This app was not given a way to download, so nothing can be fetched.",
                    ),
                )

            override fun request(
                url: String,
                method: String,
                headers: Map<String, String>,
                body: ByteArray?,
                limitBytes: Long,
            ): Result<Answer> = Result.failure(
                IllegalStateException(
                    "This app was not given a way to reach the network, so nothing can be sent.",
                ),
            )
        }

        /**
         * The real one, over `HttpURLConnection`.
         *
         * ## Why the size is checked while reading rather than from the header
         *
         * `Content-Length` is a claim. A server that does not send one, or sends a small one and then
         * keeps writing, is exactly the case a cap exists for — so the count is kept as the bytes
         * arrive and the read **stops** at the limit. A refusal that arrives after the phone has
         * already written two gigabytes is not a cap.
         *
         * ## What it follows
         *
         * Redirects, because `github.com/o/r/archive/...` redirects to a CDN and refusing that would
         * make the one documented use of this tool fail. `HttpURLConnection` follows them itself, and
         * **the address check runs again on each hop** — see [Addresses.problem]'s doc: a redirect to
         * `127.0.0.1` is the SSRF this rule exists for, and checking only the URL the agent typed
         * would miss it.
         */
        fun http(privateAllowed: () -> Boolean = { false }): Fetcher = object : Fetcher {
            override fun bytes(url: String, limitBytes: Long): Result<ByteArray> = try {
                read(url, limitBytes, privateAllowed)
            } catch (e: Exception) {
                Result.failure(IllegalStateException(Secrets.explain(e, "The download failed")))
            }

            override fun request(
                url: String,
                method: String,
                headers: Map<String, String>,
                body: ByteArray?,
                limitBytes: Long,
            ): Result<Answer> = try {
                send(url, method, headers, body, limitBytes, privateAllowed)
            } catch (e: Exception) {
                Result.failure(IllegalStateException(Secrets.explain(e, "The request failed")))
            }
        }

        /**
         * One request, with [read]'s four rules and one of its own.
         *
         * **The address rule runs before anything is opened, on every hop.** That is the same
         * reason [read] follows redirects by hand: the request is the harm, so a check that ran
         * after the connection would already have reached the operator's router.
         *
         * **A redirect is followed only for a GET or a HEAD.** For anything with a body, a `302` to
         * a `GET` is a request that silently became a different request — GitHub's API does exactly
         * that on a rename — so the 3xx is returned with its `Location` and the caller decides.
         */
        private fun send(
            url: String,
            method: String,
            headers: Map<String, String>,
            body: ByteArray?,
            limitBytes: Long,
            privateAllowed: () -> Boolean,
        ): Result<Answer> {
            val verb = method.trim().uppercase().ifEmpty { "GET" }
            var target = url.trim()
            var hops = 0
            while (true) {
                Addresses.problem(target, privateAllowed())?.let {
                    return Result.failure(IllegalStateException(it))
                }
                var connection: HttpURLConnection? = null
                try {
                    connection = (URL(target).openConnection() as HttpURLConnection).apply {
                        requestMethod = verb
                        instanceFollowRedirects = false
                        connectTimeout = 20_000
                        readTimeout = 60_000
                        // **Set first, so a caller's own header wins.** A caller that sends
                        // `Accept: application/vnd.github+json` must get it, and a caller that
                        // sends none still gets something an API will accept.
                        setRequestProperty("Accept", "*/*")
                        setRequestProperty("User-Agent", "Muffycodes")
                        for ((name, value) in headers) setRequestProperty(name, value)
                        if (body != null) {
                            doOutput = true
                            setFixedLengthStreamingMode(body.size)
                        }
                    }
                    if (body != null) connection.outputStream.use { it.write(body) }
                    val code = connection.responseCode
                    val answer = Answer(
                        status = code,
                        headers = connection.headerFields
                            .filterKeys { it != null }
                            .mapValues { (_, values) -> values.firstOrNull().orEmpty() },
                        bytes = ByteArray(0),
                    )
                    // **A redirect is followed only when repeating it is free.** A GET or a HEAD has
                    // no body to lose, so the hop is taken and checked. Anything else is answered
                    // as it stands: a `POST` that became a `GET` is not the request the caller made.
                    if (code in 300..399 && (verb == "GET" || verb == "HEAD")) {
                        val to = connection.getHeaderField("Location")
                            ?: return Result.failure(
                                IllegalStateException("The server redirected without saying where."),
                            )
                        if (++hops > 5) {
                            return Result.failure(
                                IllegalStateException("Too many redirects — five is the limit."),
                            )
                        }
                        target = URL(URL(target), to).toString()
                        continue
                    }
                    // **The body, capped as it arrives.** Read for every status, because an error
                    // body is the diagnostic — a `422` from an API says which field was wrong, and
                    // dropping it would leave the caller with a number and no reason.
                    val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                    val out = java.io.ByteArrayOutputStream()
                    if (stream != null) {
                        stream.use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val got = input.read(buffer)
                                if (got < 0) break
                                if (out.size().toLong() + got > limitBytes) {
                                    return Result.failure(
                                        IllegalStateException(
                                            "The answer passed the ${size(limitBytes)} limit and was " +
                                                "stopped. Nothing was kept.",
                                        ),
                                    )
                                }
                                out.write(buffer, 0, got)
                            }
                        }
                    }
                    return Result.success(answer.copy(bytes = out.toByteArray()))
                } finally {
                    connection?.disconnect()
                }
            }
        }

        /**
         * Reads [url], following redirects by hand so **every hop is checked**.
         *
         * `HttpURLConnection.setInstanceFollowRedirects(true)` would follow a redirect to loopback
         * without asking, which is the whole attack: a public host answers `302 → http://127.0.0.1:…`
         * and the fetcher connects to the phone. So redirects are followed here, five at most, and
         * the address rule runs on each one.
         */
        private fun read(url: String, limitBytes: Long, privateAllowed: () -> Boolean): Result<ByteArray> {
            var target = url.trim()
            var hops = 0
            while (true) {
                Addresses.problem(target, privateAllowed())?.let {
                    return Result.failure(IllegalStateException(it))
                }
                var connection: HttpURLConnection? = null
                try {
                    connection = (URL(target).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        // **Not the default `true`**, because that follows a redirect this code
                        // cannot see. The hop is taken below, where it can be checked.
                        instanceFollowRedirects = false
                        connectTimeout = 20_000
                        readTimeout = 60_000
                        setRequestProperty("Accept", "*/*")
                        // A GitHub API read wants a user agent; a CDN does not care.
                        setRequestProperty("User-Agent", "Muffycodes")
                    }
                    val code = connection.responseCode
                    if (code in 300..399) {
                        val to = connection.getHeaderField("Location")
                            ?: return Result.failure(
                                IllegalStateException("The server redirected without saying where."),
                            )
                        if (++hops > 5) {
                            return Result.failure(
                                IllegalStateException("Too many redirects — five is the limit."),
                            )
                        }
                        // **Relative redirects are real**, and a `Location` of `/x.zip` is common.
                        target = URL(URL(target), to).toString()
                        continue
                    }
                    if (code !in 200..299) {
                        val body = connection.errorStream?.bufferedReader()?.use { it.readText() }
                            .orEmpty()
                            .trim()
                            .take(300)
                        return Result.failure(
                            IllegalStateException(
                                "The server answered $code" + if (body.isEmpty()) "." else ": $body",
                            ),
                        )
                    }
                    // **The cap is checked as the bytes arrive**, not from `Content-Length`, which is
                    // a claim a server can get wrong in the direction that fills the phone.
                    val declared = connection.contentLengthLong
                    if (declared > limitBytes) {
                        return Result.failure(
                            IllegalStateException(
                                "That file is ${size(declared)} and the limit is ${size(limitBytes)}. " +
                                    "Nothing was downloaded.",
                            ),
                        )
                    }
                    val out = java.io.ByteArrayOutputStream()
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val got = input.read(buffer)
                            if (got < 0) break
                            if (out.size().toLong() + got > limitBytes) {
                                return Result.failure(
                                    IllegalStateException(
                                        "That download passed the ${size(limitBytes)} limit and was " +
                                            "stopped. Nothing was kept.",
                                    ),
                                )
                            }
                            out.write(buffer, 0, got)
                        }
                    }
                    return Result.success(out.toByteArray())
                } finally {
                    connection?.disconnect()
                }
            }
        }

        /** Bytes in words a person reads, because "2147483648" is not a size anyone can judge. */
        fun size(bytes: Long): String = when {
            bytes >= 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
            bytes >= 1024L -> String.format(java.util.Locale.US, "%.1f kB", bytes / 1024.0)
            else -> "$bytes bytes"
        }
    }
}

/**
 * The folder a fetched file always lands in, **which is not a parameter.**
 *
 * The operator's answer to *"where does a fetch land"* was *"everything can land there"* — and
 * `agent/` is already git-excluded, so a scratch download never reaches a commit. The consequence is
 * the good one: **vendoring is `fetch` then `unpack`**, not a `fetch` that writes into `vendor/` and
 * commits 40 MB on the strength of one call. `unpack` is the step where files enter the project, and
 * that is the step a person can see in the history.
 */
object Fetched {
    /** The folder, inside a project. */
    const val DIR: String = "${AgentLog.DIR}/fetched"

    /**
     * How large a download may be, **and it is a refusal, never a truncation.**
     *
     * A truncated file is worse than no file: the agent believes it has the archive, `unpack` fails
     * halfway through it, and the cause is three steps back.
     *
     * **100 MB, raised from 20 on the operator's word** (report 36: *"make it so that fetch can load
     * up to 100MB at a time"*). Twenty refused archives that were genuinely a dependency — a vendored
     * library's source tarball is tens of megabytes once it carries its tests and fixtures — and the
     * cap was the tool's opinion rather than the person's. A hundred is still a refusal and still not
     * a truncation, and it is still small enough that a 2 GB ISO cannot arrive by accident.
     *
     * **Every sentence that mentions the cap reads this constant**, so the number cannot go stale in a
     * description, a test or a refusal — the same rule as `SettingsStore.DEFAULT_MAX_RESULT`.
     */
    const val LIMIT_BYTES: Long = 100L * 1024 * 1024

    /**
     * A safe file name for [url] — **the last path segment, cleaned.**
     *
     * The project naming rule is wrong here for the reason `artifact` already found: it
     * lowercases and turns separators into `_`, so `lib-1.2.tar.gz` would arrive as `lib_1_2_tar_gz`
     * and the extension a person needs to recognise the file would be gone. So the name is kept and
     * only made safe, and a URL with no usable last segment falls back to a name from the host.
     */
    fun nameFor(url: String): String {
        val path = try {
            URL(url).path.orEmpty()
        } catch (e: Exception) {
            ""
        }
        // **`URL.path` already excludes the query**, so there is nothing to strip here.
        val last = path.substringAfterLast('/')
        // **A leading dot is kept**, because `.env` is a real file name and trimming it to `env`
        // would name the download something the person did not ask for. Only the names that are
        // *entirely* dots are refused, since those are the two path segments that mean "here" and
        // "up" and neither is a file.
        val cleaned = last.map { ch ->
            if (ch.isLetterOrDigit() || ch == '.' || ch == '-' || ch == '_') ch else '_'
        }.joinToString("")
        if (cleaned.isNotEmpty() && cleaned != "." && cleaned != "..") return cleaned
        // **A name from the host**, so `https://example.com/` lands as `example.com` rather than as
        // nothing — a file has to be called something, and an empty name is a folder.
        val host = try {
            URL(url).host.orEmpty()
        } catch (e: Exception) {
            ""
        }
        return host.map { if (it.isLetterOrDigit() || it == '.' || it == '-') it else '_' }
            .joinToString("")
            .ifEmpty { "fetched" }
    }
}
