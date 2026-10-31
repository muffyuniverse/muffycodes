package com.example.notes

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The outcome of a call to a GitHub, whichever one is behind it.
 *
 * [status] is the HTTP status when there was one, and 0 when the call never got
 * that far — an offline phone, a deleted `DiskGitHub` repository. It is carried
 * structurally because callers kept deciding things from [error] as text: the
 * "repository already deleted on GitHub is not an error" rule was a
 * `contains("404")`, which would also swallow a message that mentioned 404 for
 * some other reason and would treat "the repository is gone" as a failure if it
 * were ever phrased without the number.
 *
 * [isGone] is that rule, said once, where the status is known.
 */
data class GitHubResult<T>(
    val value: T? = null,
    val error: String? = null,
    val status: Int = 0,
) {
    val isSuccess: Boolean get() = error == null && value != null

    /** True when GitHub says the thing is not there any more. */
    val isGone: Boolean get() = status == 404

    /** True when the request never reached a server at all. */
    val isOffline: Boolean get() = status == 0 && error != null
}

/** A repository of the signed-in account. */
data class Repo(
    val name: String,
    val fullName: String,
    val private: Boolean,
    val cloneUrl: String,
    val updatedAt: String = "",
)

/** A licence a new repository can be seeded with. */
data class Licence(val key: String, val name: String)

/**
 * One workflow run, as GitHub describes it.
 *
 * **`conclusion` is null while the run is still going**, and that is a real state rather than an
 * error: a run that has been queued and not started has no outcome yet, and answering `""` for it
 * would be indistinguishable from a run that finished with nothing to say.
 */
data class CiRun(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String?,
    val branch: String,
    val event: String,
    val sha: String,
    val createdAt: String,
    val url: String,
) {
    /** True while GitHub has not finished with it. */
    val running: Boolean get() = conclusion == null || status != "completed"
}

/** One job inside a run: the unit that has a log. */
data class CiJob(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String?,
)

/** One file a run produced. */
data class CiArtifact(
    val id: Long,
    val name: String,
    val bytes: Long,
    val expired: Boolean,
)

/**
 * `owner/name` from a clone address, or null when it is not one.
 *
 * **Every GitHub call below needs this pair**, and until now nothing in the app did: the sync
 * pushed to a remote and never asked whose it was. A remote can be written three ways —
 * `https://github.com/owner/name.git`, `git@github.com:owner/name.git`, and with a trailing slash
 * or no `.git` — so the parsing lives here once rather than in each caller.
 */
data class RepoRef(val owner: String, val name: String) {
    /** `owner/name`, as the API writes it in a path. */
    val slug: String get() = "$owner/$name"

    companion object {
        /**
         * `owner/name` from a remote, or null.
         *
         * The three shapes a remote is written in, all of which this app can meet: a clone URL
         * (`https://github.com/owner/name.git`), an ssh one (`git@github.com:owner/name.git`), and
         * a folder (`file:///…/owner/name.git`) — the last is [DiskGitHub], and it must parse too or
         * every test of these tools would have to invent a remote.
         */
        fun of(remote: String?): RepoRef? {
            val url = remote?.trim().orEmpty()
            if (url.isEmpty()) return null
            // Everything after the host: `https://host/a/b.git`, `git@host:a/b.git`, `file:/a/b.git`.
            val rest = when {
                url.startsWith("git@") -> url.substringAfter('@').substringAfter(':', "")
                url.contains("://") -> url.substringAfter("://").substringAfter('/', "")
                // **`file:/a/b.git`, with ONE slash** — which is what `File.toURI()` writes, and so
                // what [DiskGitHub]'s clone addresses look like. Without this branch every test of
                // these tools would have to invent a remote, and the fake would be untestable by
                // the same code the phone runs.
                url.startsWith("file:") -> url.removePrefix("file:")
                else -> ""
            }
            val parts = rest.trim('/').removeSuffix(".git").split('/').filter { it.isNotEmpty() }
            if (parts.size < 2) return null
            // **The last two, not the first two**: a path may hold more segments, and the repository
            // is always the last pair.
            return RepoRef(parts[parts.size - 2], parts.last())
        }
    }
}


/**
 * Who a key belongs to, as GitHub itself says.
 *
 * Name and email are the account's own, so signing in asks for nothing but the
 * key: a commit is written as the person whose account it is, and they have
 * already told GitHub who that is.
 */
data class Identity(
    val login: String,
    val name: String,
    val email: String,
)

/**
 * The part of GitHub the app uses: who the key belongs to, the account's
 * repositories, and making, renaming and deleting one.
 *
 * It is an interface for one reason: the advanced features of this app — making
 * a project, syncing it, pulling one in — are only reachable once somebody is
 * signed in, and a test run must never be given a real key. [HttpGitHub] talks to
 * GitHub; [DiskGitHub] talks to a folder that behaves like one. Everything above
 * this line is the same code either way, which is what makes the tests worth
 * anything.
 */
interface GitHub {

    /** Who the key belongs to: the login, and the name and email to commit as. */
    fun identity(token: String): GitHubResult<Identity>

    /** The account's repositories, most recently changed first. */
    fun listRepos(token: String): GitHubResult<List<Repo>>

    /** The licences a repository can be created with. */
    fun licences(token: String): GitHubResult<List<Licence>>

    /** Creates a public repository seeded with a README and, when asked, a licence. */
    fun createRepo(
        token: String,
        name: String,
        licence: String = "",
        description: String = "",
    ): GitHubResult<Repo>

    /** Renames a repository, so the project and its repository keep one name. */
    fun renameRepo(token: String, owner: String, name: String, newName: String): GitHubResult<Repo>

    /** Deletes a repository. Used only by "delete everywhere". */
    fun deleteRepo(token: String, owner: String, name: String): GitHubResult<Unit>

    /** The address a repository is cloned from, from its owner and name. */
    fun cloneUrlFor(owner: String, name: String): String

    /** The URL used to open a repository in the browser. */
    fun webUrl(cloneUrl: String): String

    /** True when a clone address belongs to this GitHub. */
    fun owns(remoteUrl: String): Boolean

    /**
     * The runs of a repository, newest first — **the answer to "did my push go green".**
     *
     * [workflow] narrows it to one workflow's file name (`build-and-release.yml`), which is what a
     * person means by "the build". [branch] narrows it to one branch's runs.
     */
    fun runs(
        token: String,
        ref: RepoRef,
        workflow: String = "",
        branch: String = "",
        limit: Int = 10,
    ): GitHubResult<List<CiRun>>

    /** The jobs of one run, which is how a log is found: a job is what has one. */
    fun jobs(token: String, ref: RepoRef, run: Long): GitHubResult<List<CiJob>>

    /** The jobs of a run, with their logs, as plain text keyed by job name. */
    fun logs(token: String, ref: RepoRef, run: Long): GitHubResult<Map<String, String>>

    /** The files a run produced. */
    fun artifacts(token: String, ref: RepoRef, run: Long): GitHubResult<List<CiArtifact>>

    /**
     * One artifact's bytes, by id.
     *
     * **Bytes, not text.** An artifact is a zip of an APK, a screenshot or a report — reading one
     * as UTF-8 is how it arrives corrupted.
     */
    fun artifactBytes(token: String, ref: RepoRef, id: Long): GitHubResult<ByteArray>
}

/**
 * GitHub itself, over HTTPS.
 *
 * The `gh` command-line tool cannot run inside an Android app (it is a native
 * binary for desktop operating systems), so the app talks to the same API
 * directly.
 *
 * Every method takes a personal access token; nothing is stored or sent without
 * one, and the token is only ever attached to GitHub itself.
 */
class HttpGitHub(private val api: String = DEFAULT_API) : GitHub {

    override fun identity(token: String): GitHubResult<Identity> {
        val response = get("GET", "/user", token)
        if (response.error != null) {
            return GitHubResult(error = response.error, status = response.status)
        }
        val user = response.value
        val login = user?.optString("login").orEmpty()
        if (login.isEmpty()) {
            return GitHubResult(error = "GitHub did not return an account name")
        }
        // A name is optional on GitHub, and so is a public email. Both have a
        // truthful fallback: the login is what the person is called here, and
        // GitHub's own noreply address is what it uses for a commit that must not
        // expose a real one.
        val name = user?.optString("name").orEmpty().ifBlank { login }
        val email = user?.optString("email").orEmpty().ifBlank {
            noreplyEmail(login, user?.optLong("id", 0L) ?: 0L)
        }
        return GitHubResult(value = Identity(login = login, name = name, email = email))
    }

    override fun listRepos(token: String): GitHubResult<List<Repo>> {
        val response = get("GET", "/user/repos?per_page=100&sort=updated", token)
        if (response.error != null) {
            return GitHubResult(error = response.error, status = response.status)
        }
        val array = response.array ?: return GitHubResult(error = "GitHub did not return a list")
        val repos = mutableListOf<Repo>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            repos.add(repo(obj))
        }
        return GitHubResult(value = repos)
    }

    override fun licences(token: String): GitHubResult<List<Licence>> {
        val response = get("GET", "/licenses", token)
        if (response.error != null) {
            return GitHubResult(error = response.error, status = response.status)
        }
        val array = response.array ?: return GitHubResult(error = "GitHub did not return a list")
        val licences = mutableListOf<Licence>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val key = obj.optString("key")
            if (key.isNotEmpty()) licences.add(Licence(key, obj.optString("name")))
        }
        return GitHubResult(value = licences.sortedBy { it.name.lowercase() })
    }

    override fun createRepo(
        token: String,
        name: String,
        licence: String,
        description: String,
    ): GitHubResult<Repo> {
        val body = JSONObject().apply {
            put("name", name)
            put("private", false)
            put("auto_init", true)
            if (licence.isNotBlank()) put("license_template", licence)
            if (description.isNotBlank()) put("description", description)
        }
        val response = get("POST", "/user/repos", token, body)
        if (response.error != null) {
            return GitHubResult(error = response.error, status = response.status)
        }
        val obj = response.value
            ?: return GitHubResult(error = "GitHub did not return the new repository")
        return GitHubResult(value = repo(obj))
    }

    override fun renameRepo(
        token: String,
        owner: String,
        name: String,
        newName: String,
    ): GitHubResult<Repo> {
        val body = JSONObject().put("name", newName)
        val response = get("PATCH", "/repos/$owner/$name", token, body)
        if (response.error != null) {
            return GitHubResult(error = response.error, status = response.status)
        }
        val obj = response.value ?: return GitHubResult(error = "GitHub did not return the repository")
        return GitHubResult(value = repo(obj))
    }

    override fun deleteRepo(token: String, owner: String, name: String): GitHubResult<Unit> {
        val response = get("DELETE", "/repos/$owner/$name", token)
        if (response.error != null) {
            return GitHubResult(error = response.error, status = response.status)
        }
        return GitHubResult(value = Unit)
    }

    override fun cloneUrlFor(owner: String, name: String): String =
        "https://github.com/$owner/${urlPart(name)}.git"

    override fun webUrl(cloneUrl: String): String = cloneUrl.removeSuffix(".git")

    override fun owns(remoteUrl: String): Boolean = remoteUrl.startsWith(API)

    // ---- Actions ----------------------------------------------------------

    override fun runs(
        token: String,
        ref: RepoRef,
        workflow: String,
        branch: String,
        limit: Int,
    ): GitHubResult<List<CiRun>> {
        // **A workflow is addressed by its FILE NAME, not by its id.** The API accepts either, but a
        // file name is what a person has in front of them — it is the name in the tree — and an id is
        // a number that changes when a workflow is deleted and made again.
        val path = if (workflow.isBlank()) {
            "/repos/${ref.owner}/${ref.name}/actions/runs?per_page=$limit" +
                (if (branch.isBlank()) "" else "&branch=" + urlPart(branch))
        } else {
            "/repos/${ref.owner}/${ref.name}/actions/workflows/" + urlPart(workflow) +
                "/runs?per_page=$limit" +
                (if (branch.isBlank()) "" else "&branch=" + urlPart(branch))
        }
        val response = get("GET", path, token)
        if (response.error != null) {
            return GitHubResult(error = response.error, status = response.status)
        }
        val array = response.value?.optJSONArray("workflow_runs")
            ?: response.array
            ?: return GitHubResult(error = "GitHub did not return any runs")
        val runs = mutableListOf<CiRun>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            runs.add(
                CiRun(
                    id = obj.optLong("id"),
                    name = obj.optString("name").ifBlank { obj.optString("display_title") },
                    status = obj.optString("status"),
                    // **`null` when the key is absent, and `""` when it is present but empty** — the
                    // difference between "not finished" and "finished with no conclusion" is the
                    // whole reason this is not `optString`.
                    conclusion = if (obj.isNull("conclusion")) null else obj.optString("conclusion"),
                    branch = obj.optString("head_branch"),
                    event = obj.optString("event"),
                    sha = obj.optString("head_sha").take(7),
                    createdAt = obj.optString("created_at"),
                    url = obj.optString("html_url"),
                ),
            )
        }
        return GitHubResult(value = runs)
    }

    override fun jobs(token: String, ref: RepoRef, run: Long): GitHubResult<List<CiJob>> {
        val response = get("GET", "/repos/${ref.owner}/${ref.name}/actions/runs/$run/jobs?per_page=100", token)
        if (response.error != null) {
            return GitHubResult(error = response.error, status = response.status)
        }
        val array = response.value?.optJSONArray("jobs")
            ?: response.array
            ?: return GitHubResult(error = "GitHub did not return any jobs")
        val jobs = mutableListOf<CiJob>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            jobs.add(
                CiJob(
                    id = obj.optLong("id"),
                    name = obj.optString("name"),
                    status = obj.optString("status"),
                    conclusion = if (obj.isNull("conclusion")) null else obj.optString("conclusion"),
                ),
            )
        }
        return GitHubResult(value = jobs)
    }

    /**
     * Every job's log, as text.
     *
     * **A log is not JSON**, so it cannot go through [get]: that function parses a body as an object
     * or an array and would throw on a page of test output. It is also not small — a job log is tens
     * of kilobytes of timestamps — so this reads it as a string and lets the caller decide what to do
     * with the size.
     *
     * **A job with no log is not a failure.** A job that never started has none, and answering an
     * error for it would make a cancelled run look like a broken token.
     */
    override fun logs(token: String, ref: RepoRef, run: Long): GitHubResult<Map<String, String>> {
        val found = jobs(token, ref, run)
        if (found.error != null) return GitHubResult(error = found.error, status = found.status)
        val out = LinkedHashMap<String, String>()
        for (job in found.value.orEmpty()) {
            val text = raw("GET", "/repos/${ref.owner}/${ref.name}/actions/jobs/${job.id}/logs", token)
            // A log that cannot be read is recorded as such rather than dropped: a job missing from
            // the answer reads as a job that does not exist.
            out[job.name] = text.getOrElse { "(" + (it.message ?: "no log") + ")" }
        }
        return GitHubResult(value = out)
    }

    override fun artifacts(token: String, ref: RepoRef, run: Long): GitHubResult<List<CiArtifact>> {
        val response = get("GET", "/repos/${ref.owner}/${ref.name}/actions/runs/$run/artifacts", token)
        if (response.error != null) {
            return GitHubResult(error = response.error, status = response.status)
        }
        val array = response.value?.optJSONArray("artifacts")
            ?: response.array
            ?: return GitHubResult(error = "GitHub did not return any artifacts")
        val files = mutableListOf<CiArtifact>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            files.add(
                CiArtifact(
                    id = obj.optLong("id"),
                    name = obj.optString("name"),
                    bytes = obj.optLong("size_in_bytes"),
                    expired = obj.optBoolean("expired"),
                ),
            )
        }
        return GitHubResult(value = files)
    }

    /**
     * An artifact's bytes.
     *
     * ## The redirect, which is the whole difficulty
     *
     * `GET /actions/artifacts/{id}/zip` does not answer with the file. It answers **302** to a
     * pre-signed blob URL on `*.blob.core.windows.net`, and that URL carries its own credentials in
     * its query string.
     *
     * **Sending our token along is not merely useless, it is a leak and it fails.** Measured against
     * this repository: the forwarded request answers `401` with a 397-byte body, because Azure
     * rejects a bearer token it did not issue. So the naive implementation — which is what
     * `HttpURLConnection` does by default, since it follows redirects *and keeps the request
     * headers* — writes a 397-byte "APK" and hands the person a file that will not install. And
     * before it fails, it has sent GitHub's token to Microsoft's storage.
     *
     * So: no automatic redirects, read `Location`, and fetch it **without** the `Authorization`
     * header. That is a security control first and a correctness fix second.
     */
    override fun artifactBytes(token: String, ref: RepoRef, id: Long): GitHubResult<ByteArray> {
        var connection: HttpURLConnection? = null
        var redirect: HttpURLConnection? = null
        return try {
            connection = (URL(API + "/repos/${ref.owner}/${ref.name}/actions/artifacts/$id/zip")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 120_000
                // **The line the whole comment above is about.**
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "MuffyCodes")
                if (token.isNotBlank() && Secrets.isGitHubHost(API)) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }
            val code = connection.responseCode
            if (code == 302 || code == 301 || code == 307 || code == 308) {
                val location = connection.getHeaderField("Location")
                    ?: return GitHubResult(error = "GitHub did not say where the artifact is")
                // The signature is in the URL itself, so no header is set here at all. It is a
                // different host on purpose and the token must not travel to it.
                redirect = (URL(location).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 20_000
                    readTimeout = 120_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "MuffyCodes")
                }
                val got = redirect.responseCode
                if (got !in 200..299) {
                    return GitHubResult(
                        error = "The artifact could not be downloaded (HTTP $got)",
                        status = got,
                    )
                }
                return GitHubResult(value = redirect.inputStream.use { it.readBytes() })
            }
            if (code !in 200..299) {
                return GitHubResult(
                    error = "The artifact could not be downloaded (HTTP $code)",
                    status = code,
                )
            }
            GitHubResult(value = connection.inputStream.use { it.readBytes() })
        } catch (e: Throwable) {
            GitHubResult(error = Secrets.scrub(e.message ?: "The artifact could not be downloaded"))
        } finally {
            connection?.disconnect()
            redirect?.disconnect()
        }
    }

    /**
     * A request whose answer is text that is not JSON — a job log.
     *
     * The log endpoint also redirects (to a signed blob URL), so this follows the same rule as
     * [artifactBytes]: no automatic redirects, and no token on the second hop.
     */
    private fun raw(method: String, path: String, token: String): Result<String> {
        var connection: HttpURLConnection? = null
        var redirect: HttpURLConnection? = null
        return try {
            connection = (URL(API + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "MuffyCodes")
                if (token.isNotBlank() && Secrets.isGitHubHost(API)) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }
            val code = connection.responseCode
            val from = when (code) {
                in 200..299 -> connection
                302, 301, 307, 308 -> {
                    val location = connection.getHeaderField("Location")
                        ?: return Result.failure(IllegalStateException("No location for the log"))
                    redirect = (URL(location).openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 20_000
                        readTimeout = 60_000
                        instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "MuffyCodes")
                    }
                    redirect
                }
                else -> {
                    val why = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    return Result.failure(
                        IllegalStateException(
                            Secrets.scrub(
                                if (why.isBlank()) "HTTP $code" else "HTTP $code: " + why.take(200),
                            ),
                        ),
                    )
                }
            }
            Result.success(from.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
        } catch (e: Throwable) {
            Result.failure(IllegalStateException(Secrets.scrub(e.message ?: "The log could not be read")))
        } finally {
            connection?.disconnect()
            redirect?.disconnect()
        }
    }

    private fun repo(obj: JSONObject) = Repo(
        name = obj.optString("name"),
        fullName = obj.optString("full_name"),
        private = obj.optBoolean("private"),
        cloneUrl = obj.optString("clone_url"),
        updatedAt = obj.optString("updated_at"),
    )

    /** A response that may be an object or an array, as the API returns both. */
    private class Response(
        val value: JSONObject? = null,
        val array: JSONArray? = null,
        val error: String? = null,
        val status: Int = 0,
    )

    private fun get(
        method: String,
        path: String,
        token: String,
        body: JSONObject? = null,
    ): Response {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(API + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "MuffyCodes")
                // The key is only ever attached to GitHub itself. A request
                // that would go anywhere else is sent without it.
                if (token.isNotBlank() && Secrets.isGitHubHost(API)) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
                if (body != null) {
                    doOutput = true
                    setRequestProperty("Content-Type", "application/json")
                }
            }
            if (body != null) {
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()

            if (code !in 200..299) {
                val message = try {
                    val obj = JSONObject(text)
                    obj.optString("message").ifBlank { "GitHub returned error $code" }
                } catch (e: Exception) {
                    "GitHub returned error $code"
                }
                return Response(error = Secrets.scrub(message))
            }

            val trimmed = text.trim()
            when {
                trimmed.isEmpty() -> Response(value = JSONObject())
                trimmed.startsWith("[") -> Response(array = JSONArray(trimmed))
                else -> Response(value = JSONObject(trimmed))
            }
        } catch (e: Exception) {
            Response(error = Secrets.scrub(e.message ?: "Could not reach GitHub"))
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        const val API = "https://api.github.com"

        /** GitHub's default, for when no base is given. */
        const val DEFAULT_API = API

        /** Escapes a repository name for use in a path or an address. */
        fun urlPart(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}

/**
 * A GitHub that is a folder.
 *
 * Each repository is a real, bare git repository on disk, addressed by a
 * `file://` URL, so cloning, committing, force-pushing and resetting all happen
 * for real — the same JGit code runs, the same refs move, the same statuses come
 * back. What is missing is the network and the key, and that is the point: the
 * parts of the app that need an account can be exercised by a test run, on a
 * machine that must never hold one.
 *
 * This is only ever reached through the test account of a debug build. A release
 * build has no way to name it.
 */
class DiskGitHub(
    private val root: File,
    private val owner: String = "dev",
) : GitHub {

    private val ownerDir: File get() = File(root, owner)

    /** Where one repository lives on disk. */
    fun repoDir(name: String): File = File(ownerDir, "$name.git")

    override fun identity(token: String): GitHubResult<Identity> =
        // A key is required, exactly as GitHub requires one. Returning the owner
        // regardless made this seam unable to say no, so nothing ever exercised the
        // half of signing in that reports a refusal.
        if (token.isBlank()) {
            GitHubResult(error = "Bad credentials", status = 401)
        } else {
            GitHubResult(
                value = Identity(
                    login = owner,
                    name = "Test Account",
                    email = "$owner@example.com",
                )
            )
        }

    override fun listRepos(token: String): GitHubResult<List<Repo>> {
        val dirs = ownerDir.listFiles()
            ?.filter { it.isDirectory && it.name.endsWith(".git") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        return GitHubResult(
            value = dirs.map { dir ->
                val name = dir.name.removeSuffix(".git")
                Repo(
                    name = name,
                    fullName = "$owner/$name",
                    private = false,
                    cloneUrl = cloneUrlFor(owner, name),
                    updatedAt = "",
                )
            }
        )
    }

    override fun licences(token: String): GitHubResult<List<Licence>> = GitHubResult(
        value = listOf(
            Licence("mit", "MIT License"),
            Licence("apache-2.0", "Apache License 2.0"),
            Licence("gpl-3.0", "GNU General Public License v3.0"),
        )
    )

    override fun createRepo(
        token: String,
        name: String,
        licence: String,
        description: String,
    ): GitHubResult<Repo> {
        if (name.isBlank()) return GitHubResult(error = "A repository needs a name")
        val dir = repoDir(name)
        if (dir.exists()) {
            return GitHubResult(error = "There is already a repository called \"$name\"")
        }
        return try {
            seed(dir, name, licence)
            GitHubResult(
                value = Repo(
                    name = name,
                    fullName = "$owner/$name",
                    private = false,
                    cloneUrl = cloneUrlFor(owner, name),
                )
            )
        } catch (e: Exception) {
            dir.deleteRecursively()
            GitHubResult(error = "Could not make the repository: ${e.message}")
        }
    }

    override fun renameRepo(
        token: String,
        owner: String,
        name: String,
        newName: String,
    ): GitHubResult<Repo> {
        val from = repoDir(name)
        val to = repoDir(newName)
        if (!from.exists()) {
            return GitHubResult(error = "There is no repository called \"$name\"", status = 404)
        }
        if (to.exists()) return GitHubResult(error = "There is already a repository called \"$newName\"")
        return if (from.renameTo(to)) {
            GitHubResult(
                value = Repo(newName, "$owner/$newName", false, cloneUrlFor(owner, newName))
            )
        } else {
            GitHubResult(error = "Could not rename the repository")
        }
    }

    override fun deleteRepo(token: String, owner: String, name: String): GitHubResult<Unit> {
        val dir = repoDir(name)
        // 404, because that is what the real GitHub answers for a repository that is
        // not there — and the caller's "already gone is not an error" rule reads the
        // status. A fake that answered differently would make the tests pass while
        // the phone behaved another way.
        if (!dir.exists()) {
            return GitHubResult(error = "There is no repository called \"$name\"", status = 404)
        }
        return if (dir.deleteRecursively()) {
            GitHubResult(value = Unit)
        } else {
            GitHubResult(error = "Could not delete the repository")
        }
    }

    override fun cloneUrlFor(owner: String, name: String): String =
        repoDir(name).toURI().toString()

    override fun webUrl(cloneUrl: String): String = cloneUrl

    override fun owns(remoteUrl: String): Boolean = remoteUrl.startsWith(root.toURI().toString()) ||
        remoteUrl.startsWith("file:")

    // ---- Actions, out of a folder ----------------------------------------
    //
    // **A fake that answers "there is nothing" would make every test of the CI tools pass while the
    // phone behaved differently**, which is the one thing this seam exists to prevent. So a run is a
    // directory under the repository, and its parts are read from files a test writes:
    //
    //     <repo>.git/ci/<run id>/run.json      name, status, conclusion, branch, event, sha
    //     <repo>.git/ci/<run id>/jobs/<job>.log
    //     <repo>.git/ci/<run id>/artifacts/<name>
    //
    // That is enough to exercise listing, log reading, artifact listing and the download, without a
    // network, a token, or a real workflow.

    private fun ciDir(ref: RepoRef, run: Long): File =
        File(File(repoDir(ref.name), "ci"), run.toString())

    override fun runs(
        token: String,
        ref: RepoRef,
        workflow: String,
        branch: String,
        limit: Int,
    ): GitHubResult<List<CiRun>> {
        if (!repoDir(ref.name).exists()) {
            return GitHubResult(error = "There is no repository called \"${ref.name}\"", status = 404)
        }
        val dirs = File(repoDir(ref.name), "ci").listFiles()
            ?.filter { it.isDirectory && it.name.toLongOrNull() != null }
            ?.sortedByDescending { it.name.toLongOrNull() ?: 0L }
            .orEmpty()
        val runs = dirs.mapNotNull { dir ->
            val id = dir.name.toLongOrNull() ?: return@mapNotNull null
            val meta = try {
                JSONObject(File(dir, "run.json").readText())
            } catch (e: Throwable) {
                JSONObject()
            }
            CiRun(
                id = id,
                name = meta.optString("name", "run $id"),
                status = meta.optString("status", "completed"),
                conclusion = if (meta.isNull("conclusion")) null else meta.optString("conclusion"),
                branch = meta.optString("branch"),
                event = meta.optString("event"),
                sha = meta.optString("sha"),
                createdAt = meta.optString("created_at"),
                url = meta.optString("url"),
            )
        }.filter { branch.isBlank() || it.branch == branch }.take(limit)
        return GitHubResult(value = runs)
    }

    override fun jobs(token: String, ref: RepoRef, run: Long): GitHubResult<List<CiJob>> {
        val dir = File(ciDir(ref, run), "jobs")
        if (!dir.isDirectory) {
            return if (ciDir(ref, run).exists()) {
                GitHubResult(value = emptyList())
            } else {
                GitHubResult(error = "There is no run $run", status = 404)
            }
        }
        return GitHubResult(
            value = dir.listFiles().orEmpty().sortedBy { it.name }.mapIndexed { i, file ->
                CiJob(
                    id = run * 100 + i,
                    name = file.name.removeSuffix(".log"),
                    status = "completed",
                    conclusion = "success",
                )
            },
        )
    }

    override fun logs(token: String, ref: RepoRef, run: Long): GitHubResult<Map<String, String>> {
        val found = jobs(token, ref, run)
        if (found.error != null) return GitHubResult(error = found.error, status = found.status)
        val out = LinkedHashMap<String, String>()
        for (job in found.value.orEmpty()) {
            out[job.name] = try {
                File(File(ciDir(ref, run), "jobs"), job.name + ".log").readText()
            } catch (e: Throwable) {
                "(no log)"
            }
        }
        return GitHubResult(value = out)
    }

    override fun artifacts(token: String, ref: RepoRef, run: Long): GitHubResult<List<CiArtifact>> {
        val dir = File(ciDir(ref, run), "artifacts")
        if (!dir.isDirectory) {
            return if (ciDir(ref, run).exists()) {
                GitHubResult(value = emptyList())
            } else {
                GitHubResult(error = "There is no run $run", status = 404)
            }
        }
        return GitHubResult(
            value = dir.listFiles().orEmpty().mapIndexed { i, file ->
                CiArtifact(
                    id = run * 100 + i,
                    name = file.name,
                    bytes = file.length(),
                    expired = false,
                )
            },
        )
    }

    override fun artifactBytes(token: String, ref: RepoRef, id: Long): GitHubResult<ByteArray> {
        val run = id / 100
        val index = (id % 100).toInt()
        val dir = File(ciDir(ref, run), "artifacts")
        val file = dir.listFiles().orEmpty().sortedBy { it.name }.getOrNull(index)
            ?: return GitHubResult(error = "There is no artifact $id", status = 404)
        return try {
            GitHubResult(value = file.readBytes())
        } catch (e: Throwable) {
            GitHubResult(error = "The artifact could not be read: ${e.message}")
        }
    }

    /**
     * Makes one repository the way GitHub does when it is asked to initialise:
     * a `main` branch and one commit holding the README and, when asked for, the
     * licence.
     *
     * The commit is made in a throwaway working folder and pushed into the bare
     * repository, which is how a person would do it and how every other write in
     * this app is done. The folder is removed either way.
     */
    private fun seed(dir: File, name: String, licence: String) {
        dir.parentFile?.mkdirs()
        val work = File(dir.parentFile, "${dir.name}.seed")
        work.deleteRecursively()
        try {
            Git.init().setBare(true).setInitialBranch("main").setDirectory(dir).call().close()
            work.mkdirs()
            Git.init().setInitialBranch("main").setDirectory(work).call().use { git ->
                File(work, "README.md").writeText(
                    "# $name\n\nA test repository, made by the app on this phone.\n"
                )
                if (licence.isNotBlank()) {
                    File(work, "LICENSE").writeText("The $licence licence applies here.\n")
                }
                val who = PersonIdent(owner, "$owner@example.com")
                git.add().addFilepattern(".").call()
                git.commit()
                    .setMessage("Initial commit")
                    .setAuthor(who)
                    .setCommitter(who)
                    .call()
                val pushed = git.push()
                    .setRemote(dir.toURI().toString())
                    .setRefSpecs(RefSpec("refs/heads/main:refs/heads/main"))
                    .call()
                // A push that was refused answers with a status, not an exception,
                // and a repository seeded with nothing would clone into an empty
                // project that looks broken for no visible reason.
                val landed = pushed.flatMap { it.remoteUpdates }.any {
                    it.status == RemoteRefUpdate.Status.OK ||
                        it.status == RemoteRefUpdate.Status.UP_TO_DATE
                }
                if (!landed) {
                    val said = pushed.flatMap { it.remoteUpdates }
                        .mapNotNull { it.message ?: it.status?.name }
                        .joinToString("; ")
                    error("The first commit did not reach the repository: $said")
                }
            }
        } finally {
            work.deleteRecursively()
        }
    }

}
