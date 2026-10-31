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

/**
 * An environment, as GitHub describes it.
 *
 * **This is where a secret can be put so that only some workflows reach it.** GitHub: "Secrets stored
 * in an environment are only available to workflow jobs that reference the environment. If the
 * environment requires approval, a job cannot access environment secrets until one of the required
 * reviewers approves it."
 */
data class CiEnvironment(
    val name: String,
    /** How many reviewers must approve a job before it may read this environment's secrets. */
    val reviewers: Int,
) {
    /** True when a human stands between a run and this environment's secrets. */
    val protected: Boolean get() = reviewers > 0
}

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

    /**
     * Starts a workflow.
     *
     * **This is the only call in the app that makes GitHub do work on somebody's account**, and it is
     * reached through [Workflows] rather than directly: a workflow may only be dispatched when its
     * bytes are the ones a person approved. The guard lives above this call, in the tool, because the
     * interface cannot see a project folder and the guard has nothing to check without one.
     *
     * [workflow] is the **file name** (`build-and-release.yml`), not the numeric id: an id changes when
     * a workflow is deleted and made again, and the file name is what a person has in front of them.
     */
    fun dispatch(
        token: String,
        ref: RepoRef,
        workflow: String,
        branch: String,
        inputs: Map<String, String> = emptyMap(),
    ): GitHubResult<Unit>

    // ---- secrets and environments ----------------------------------------
    //
    // **Every secret this app writes goes to an ENVIRONMENT, never to the repository.** A repository
    // secret is readable by every workflow in the repository, so it would defeat the check that refuses
    // an unlocked workflow naming an environment. An environment secret is reachable only by a job that
    // names that environment — which is the whole mechanism.

    /** The environments a repository has, with how many reviewers each requires. */
    fun environments(token: String, ref: RepoRef): GitHubResult<List<CiEnvironment>>

    /**
     * Creates or updates an environment, **with reviewers when asked for**.
     *
     * A reviewer is what makes the environment a gate rather than a label: without one, a workflow that
     * names it reads its secrets immediately.
     */
    fun putEnvironment(
        token: String,
        ref: RepoRef,
        name: String,
        reviewers: List<String>,
    ): GitHubResult<CiEnvironment>

    /**
     * Writes a secret into an environment.
     *
     * [sealed] is the value already encrypted with the environment's public key — see [SealedBox].
     * **The plaintext never reaches this interface**, which is the point: an argument that is a secret
     * is an argument that can be logged, and this one cannot be.
     */
    fun putEnvironmentSecret(
        token: String,
        ref: RepoRef,
        environment: String,
        name: String,
        sealed: ByteArray,
        keyId: String,
    ): GitHubResult<Unit>

    /**
     * The public key an environment's secrets must be sealed with.
     *
     * **Per environment, not per repository.** GitHub hands out a different key for each, and sealing
     * with the repository's key produces a value the environment will not decrypt.
     */
    fun environmentKey(
        token: String,
        ref: RepoRef,
        environment: String,
    ): GitHubResult<Pair<String, ByteArray>>

    /**
     * The **names** of a repository's own secrets. Never a value — GitHub does not return one.
     *
     * **This is the check that matters most, and it is a check for the wrong thing to exist.** A
     * repository secret is readable by every workflow in the repository, including one the agent wrote
     * and started, so a single one at this scope defeats the environment design entirely. The app can
     * do nothing about it — only the person can move it — so the honest answer is to name it.
     */
    fun repoSecretNames(token: String, ref: RepoRef): GitHubResult<List<String>>

    /** The **names** of an environment's secrets. */
    fun environmentSecretNames(
        token: String,
        ref: RepoRef,
        environment: String,
    ): GitHubResult<List<String>>

    /**
     * Restricts an environment to named branches.
     *
     * **This is the gate that replaces a reviewer, and it is better than one.** The threat is the agent
     * publishing a release; the agent pushes and dispatches on `development` only, so an environment that
     * only `main` and `development` may use is unreachable by anything the agent can do — structurally,
     * with nothing for a person to approve. A reviewer would ask a human to tap a button for a decision
     * the branch rule already made.
     *
     * Both branches, because `build-release` runs on both: `development` builds an installable APK signed
     * with the same key, on purpose, so a dev build installs over the release one.
     */
    fun restrictEnvironmentTo(
        token: String,
        ref: RepoRef,
        environment: String,
        branches: List<String>,
    ): GitHubResult<Unit>
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
     * Starts a workflow.
     *
     * **`204 No Content` is the success answer**, which is why this cannot go through [get]: that
     * function parses a body, and there is none. It also cannot use [raw], which reads bytes; the
     * answer to a dispatch is the status alone.
     *
     * **`inputs` are not secret and must never be.** They are visible in the run, in the UI and in
     * the API — which is why the vault design says a secret goes to a repository secret, never to a
     * dispatch input. The doc says so in as many words; this comment exists because the next person
     * to add a `secret` key here will be adding a leak.
     */
    override fun dispatch(
        token: String,
        ref: RepoRef,
        workflow: String,
        branch: String,
        inputs: Map<String, String>,
    ): GitHubResult<Unit> {
        val body = JSONObject().apply {
            put("ref", branch)
            if (inputs.isNotEmpty()) {
                put("inputs", JSONObject().apply { inputs.forEach { (k, v) -> put(k, v) } })
            }
        }
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(
                API + "/repos/${ref.owner}/${ref.name}/actions/workflows/" +
                    urlPart(workflow) + "/dispatches",
            ).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20_000
                readTimeout = 30_000
                doOutput = true
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "MuffyCodes")
                setRequestProperty("Content-Type", "application/json")
                if (token.isNotBlank() && Secrets.isGitHubHost(API)) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            if (code in 200..299) return GitHubResult(value = Unit)
            val why = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val said = try {
                JSONObject(why).optString("message")
            } catch (e: Throwable) {
                ""
            }
            GitHubResult(
                error = Secrets.scrub(said.ifBlank { "GitHub returned error $code" }),
                status = code,
            )
        } catch (e: Throwable) {
            GitHubResult(error = Secrets.scrub(e.message ?: "The workflow could not be started"))
        } finally {
            connection?.disconnect()
        }
    }

    // ---- secrets and environments ----------------------------------------

    override fun environments(token: String, ref: RepoRef): GitHubResult<List<CiEnvironment>> {
        val response = get("GET", "/repos/${ref.owner}/${ref.name}/environments?per_page=100", token)
        if (response.error != null) {
            return GitHubResult(error = response.error, status = response.status)
        }
        val array = response.value?.optJSONArray("environments")
            ?: response.array
            ?: return GitHubResult(value = emptyList())
        val out = mutableListOf<CiEnvironment>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val name = obj.optString("name")
            if (name.isEmpty()) continue
            // **The reviewers count is nested, and its absence means unprotected.** GitHub answers
            // `protection_rules` as an array whose entries have a `type`; only `required_reviewers`
            // holds a list of people. Counting the rules instead of the reviewers would report an
            // environment with a branch rule as "protected by a human", which it is not.
            val rules = obj.optJSONArray("protection_rules")
            var reviewers = 0
            if (rules != null) {
                for (r in 0 until rules.length()) {
                    val rule = rules.optJSONObject(r) ?: continue
                    if (rule.optString("type") != "required_reviewers") continue
                    reviewers += rule.optJSONArray("reviewers")?.length() ?: 0
                }
            }
            out.add(CiEnvironment(name, reviewers))
        }
        return GitHubResult(value = out)
    }

    override fun putEnvironment(
        token: String,
        ref: RepoRef,
        name: String,
        reviewers: List<String>,
    ): GitHubResult<CiEnvironment> {
        val body = JSONObject().apply {
            if (reviewers.isNotEmpty()) {
                put(
                    "reviewers",
                    JSONArray().apply {
                        reviewers.forEach { login ->
                            put(
                                JSONObject().apply {
                                    // **A `User`, not a `Team`**, because a login is what the person
                                    // typed. GitHub rejects a reviewer that is not a real account, so
                                    // an invented one fails loudly here rather than silently granting
                                    // access to nobody.
                                    put("type", "User")
                                    put("id", 0)
                                    put("login", login)
                                },
                            )
                        }
                    },
                )
            }
        }
        val response = get("PUT", "/repos/${ref.owner}/${ref.name}/environments/" +
            urlPart(name), token, body)
        if (response.error != null) {
            return GitHubResult(error = response.error, status = response.status)
        }
        return GitHubResult(value = CiEnvironment(name, reviewers.size))
    }

    override fun environmentKey(
        token: String,
        ref: RepoRef,
        environment: String,
    ): GitHubResult<Pair<String, ByteArray>> {
        val response = get(
            "GET",
            "/repos/${ref.owner}/${ref.name}/environments/" + urlPart(environment) +
                "/secrets/public-key",
            token,
        )
        if (response.error != null) {
            return GitHubResult(error = response.error, status = response.status)
        }
        val obj = response.value
            ?: return GitHubResult(error = "GitHub did not return a public key")
        val id = obj.optString("key_id")
        val key = obj.optString("key")
        if (id.isEmpty() || key.isEmpty()) {
            return GitHubResult(error = "GitHub did not return a usable public key")
        }
        return try {
            GitHubResult(value = id to java.util.Base64.getDecoder().decode(key))
        } catch (e: Throwable) {
            GitHubResult(error = "GitHub's public key was not base64")
        }
    }

    override fun putEnvironmentSecret(
        token: String,
        ref: RepoRef,
        environment: String,
        name: String,
        sealed: ByteArray,
        keyId: String,
    ): GitHubResult<Unit> {
        // **The sealed value is base64 in the body, and the plaintext is nowhere.** The encrypted bytes
        // are the only thing this function has ever been given.
        val body = JSONObject().apply {
            put("encrypted_value", java.util.Base64.getEncoder().encodeToString(sealed))
            put("key_id", keyId)
        }
        val response = get(
            "PUT",
            "/repos/${ref.owner}/${ref.name}/environments/" + urlPart(environment) +
                "/secrets/" + urlPart(name),
            token,
            body,
        )
        if (response.error != null) {
            return GitHubResult(error = response.error, status = response.status)
        }
        return GitHubResult(value = Unit)
    }

    override fun repoSecretNames(token: String, ref: RepoRef): GitHubResult<List<String>> =
        secretNames(token, "/repos/${ref.owner}/${ref.name}/actions/secrets")

    override fun environmentSecretNames(
        token: String,
        ref: RepoRef,
        environment: String,
    ): GitHubResult<List<String>> = secretNames(
        token,
        "/repos/${ref.owner}/${ref.name}/environments/" + urlPart(environment) + "/secrets",
    )

    override fun restrictEnvironmentTo(
        token: String,
        ref: RepoRef,
        environment: String,
        branches: List<String>,
    ): GitHubResult<Unit> {
        // **The policy flag first, then the names.** GitHub refuses a branch name on an environment
        // whose policy is "all branches", and refuses the policy change while custom names exist — so
        // the order is: turn on custom policies, then add each branch. The update is idempotent, which
        // is what makes this safe to run on every send.
        val policy = JSONObject().put(
            "deployment_branch_policy",
            JSONObject()
                .put("protected_branches", false)
                .put("custom_branch_policies", true),
        )
        val set = get("PUT", "/repos/${ref.owner}/${ref.name}/environments/" +
            urlPart(environment), token, policy)
        if (set.error != null) {
            return GitHubResult(error = set.error, status = set.status)
        }
        val existing = get(
            "GET",
            "/repos/${ref.owner}/${ref.name}/environments/" + urlPart(environment) +
                "/deployment-branch-policies",
            token,
        )
        if (existing.error != null) {
            return GitHubResult(error = existing.error, status = existing.status)
        }
        val have = mutableSetOf<String>()
        val array = existing.value?.optJSONArray("branch_policies") ?: JSONArray()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.optString("name")?.let { have.add(it) }
        }
        for (branch in branches) {
            // **Only what is missing**, because GitHub answers 409 for a name that is already there and
            // a second send would otherwise look like a failure.
            if (branch in have) continue
            val added = get(
                "POST",
                "/repos/${ref.owner}/${ref.name}/environments/" + urlPart(environment) +
                    "/deployment-branch-policies",
                token,
                JSONObject().put("name", branch),
            )
            if (added.error != null) {
                return GitHubResult(error = added.error, status = added.status)
            }
        }
        return GitHubResult(value = Unit)
    }

    /**
     * The `name` of every entry in a secrets listing.
     *
     * **The endpoint returns names and timestamps and nothing else**, which is why this is safe to call
     * and worth calling: it answers "what exists here" without touching a value.
     */
    private fun secretNames(token: String, path: String): GitHubResult<List<String>> {
        val response = get("GET", path, token)
        if (response.error != null) {
            return GitHubResult(error = response.error, status = response.status)
        }
        val array = response.value?.optJSONArray("secrets")
            ?: response.array
            ?: return GitHubResult(value = emptyList())
        val names = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val name = obj.optString("name")
            if (name.isNotEmpty()) names.add(name)
        }
        return GitHubResult(value = names.sorted())
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
     * Starts a workflow — **by writing the run a real dispatch would create.**
     *
     * A fake that answered "started" and created nothing would make every test of the guard pass
     * while proving nothing about what a person would see afterwards. So a dispatch here appends a run
     * directory, and the next `ci` call reads it back, which is exactly the loop a real one has.
     */
    override fun dispatch(
        token: String,
        ref: RepoRef,
        workflow: String,
        branch: String,
        inputs: Map<String, String>,
    ): GitHubResult<Unit> {
        if (token.isBlank()) return GitHubResult(error = "Bad credentials", status = 401)
        if (!repoDir(ref.name).exists()) {
            return GitHubResult(error = "There is no repository called \"${ref.name}\"", status = 404)
        }
        val ci = File(repoDir(ref.name), "ci")
        val next = (ci.listFiles().orEmpty().mapNotNull { it.name.toLongOrNull() }.maxOrNull()
            ?: 0L) + 1
        val dir = File(ci, next.toString())
        File(dir, "jobs").mkdirs()
        File(dir, "run.json").writeText(
            JSONObject().apply {
                put("name", workflow)
                put("status", "in_progress")
                put("conclusion", JSONObject.NULL)
                put("branch", branch)
                put("event", "workflow_dispatch")
                put("sha", "")
                put("created_at", "now")
                put("url", "")
            }.toString(),
        )
        return GitHubResult(value = Unit)
    }

    // ---- secrets and environments, out of a folder -----------------------
    //
    // **A fake that stored nothing would make every test of the secret path pass while the phone
    // behaved differently**, which is the failure this seam exists to prevent. So an environment is a
    // folder and a secret is a file holding what was sealed for it:
    //
    //     <repo>.git/ci-environments/<env>/env.json
    //     <repo>.git/ci-environments/<env>/secrets/<name>.bin
    //
    // The sealed bytes are stored exactly as given, so a test can seal a value, put it, and read it
    // back to prove the plaintext never travelled.

    private fun environmentsDir(ref: RepoRef): File =
        File(repoDir(ref.name), "ci-environments")

    override fun environments(token: String, ref: RepoRef): GitHubResult<List<CiEnvironment>> {
        val dir = environmentsDir(ref)
        if (!dir.isDirectory) return GitHubResult(value = emptyList())
        return GitHubResult(
            value = dir.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }.map {
                val meta = try {
                    JSONObject(File(it, "env.json").readText())
                } catch (e: Throwable) {
                    JSONObject()
                }
                CiEnvironment(it.name, meta.optJSONArray("reviewers")?.length() ?: 0)
            },
        )
    }

    override fun putEnvironment(
        token: String,
        ref: RepoRef,
        name: String,
        reviewers: List<String>,
    ): GitHubResult<CiEnvironment> {
        if (token.isBlank()) return GitHubResult(error = "Bad credentials", status = 401)
        val dir = File(environmentsDir(ref), name)
        dir.mkdirs()
        File(dir, "env.json").writeText(
            JSONObject().apply {
                put("name", name)
                put("reviewers", JSONArray().apply { reviewers.forEach { put(it) } })
            }.toString(),
        )
        return GitHubResult(value = CiEnvironment(name, reviewers.size))
    }

    /**
     * A **stable** key for an environment, derived from its name.
     *
     * **Random per call would make the fake lie.** A test that sealed with one call's key and read the
     * stored bytes back could never open them, so the only assertion left would be a length — which
     * proves the value was *sealed* and not that it was sealed *correctly*. Deriving the pair from the
     * name means a test can ask for the key again and actually open what was stored.
     */
    override fun environmentKey(
        token: String,
        ref: RepoRef,
        environment: String,
    ): GitHubResult<Pair<String, ByteArray>> {
        if (token.isBlank()) return GitHubResult(error = "Bad credentials", status = 401)
        return GitHubResult(value = "key-$environment" to fakeKeyPair(environment).second)
    }

    /** The secret half of [fakeKeyPair], so a test can open what the fake stored. */
    fun fakeSecretKey(environment: String): ByteArray = fakeKeyPair(environment).first

    /**
     * A deterministic X25519 pair for [environment].
     *
     * A private key is 32 random bytes, so seeding from the name's SHA-256 gives a pair that is stable
     * across calls and different per environment. **It is a fake and is never a real key**: the seed is
     * the environment's own name, which anybody can guess.
     */
    private fun fakeKeyPair(environment: String): Pair<ByteArray, ByteArray> {
        val seed = java.security.MessageDigest.getInstance("SHA-256")
            .digest(environment.toByteArray())
        val cached = fakeKeys[environment]
        if (cached != null) return cached
        // `Keys.generatePublicKey` is a pure function of the private key, so a fixed private key gives a
        // fixed pair — and caching keeps the derivation off every call.
        val public = com.codahale.xsalsa20poly1305.Keys.generatePublicKey(seed)
        return (seed to public).also { fakeKeys[environment] = it }
    }

    private val fakeKeys = java.util.concurrent.ConcurrentHashMap<String, Pair<ByteArray, ByteArray>>()

    override fun putEnvironmentSecret(
        token: String,
        ref: RepoRef,
        environment: String,
        name: String,
        sealed: ByteArray,
        keyId: String,
    ): GitHubResult<Unit> {
        if (token.isBlank()) return GitHubResult(error = "Bad credentials", status = 401)
        val dir = File(File(environmentsDir(ref), environment), "secrets")
        dir.mkdirs()
        return try {
            File(dir, "$name.bin").writeBytes(sealed)
            GitHubResult(value = Unit)
        } catch (e: Throwable) {
            GitHubResult(error = "The secret could not be stored: ${e.message}")
        }
    }

    override fun repoSecretNames(token: String, ref: RepoRef): GitHubResult<List<String>> {
        if (token.isBlank()) return GitHubResult(error = "Bad credentials", status = 401)
        val dir = File(repoDir(ref.name), "ci-secrets")
        if (!dir.isDirectory) return GitHubResult(value = emptyList())
        return GitHubResult(
            value = dir.listFiles().orEmpty().filter { it.isFile }
                .map { it.name.removeSuffix(".bin") }.sorted(),
        )
    }

    override fun environmentSecretNames(
        token: String,
        ref: RepoRef,
        environment: String,
    ): GitHubResult<List<String>> {
        if (token.isBlank()) return GitHubResult(error = "Bad credentials", status = 401)
        val dir = File(File(environmentsDir(ref), environment), "secrets")
        if (!dir.isDirectory) return GitHubResult(value = emptyList())
        return GitHubResult(
            value = dir.listFiles().orEmpty().filter { it.isFile }
                .map { it.name.removeSuffix(".bin") }.sorted(),
        )
    }

    /** The branches an environment is restricted to, for a test to read back. */
    fun branchesOf(ref: RepoRef, environment: String): List<String> {
        val file = File(File(environmentsDir(ref), environment), "branches.json")
        if (!file.isFile) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            (0 until array.length()).mapNotNull { array.optString(it)?.takeIf(String::isNotEmpty) }
        } catch (e: Throwable) {
            emptyList()
        }
    }

    override fun restrictEnvironmentTo(
        token: String,
        ref: RepoRef,
        environment: String,
        branches: List<String>,
    ): GitHubResult<Unit> {
        if (token.isBlank()) return GitHubResult(error = "Bad credentials", status = 401)
        val dir = File(environmentsDir(ref), environment)
        dir.mkdirs()
        return try {
            File(dir, "branches.json").writeText(
                JSONArray().apply { branches.forEach { put(it) } }.toString(),
            )
            GitHubResult(value = Unit)
        } catch (e: Throwable) {
            GitHubResult(error = "The branch policy could not be stored: ${e.message}")
        }
    }

    /**
     * Puts a secret at REPOSITORY scope, so a test can create the state the app warns about.
     *
     * **Only the fake has this.** Nothing in the app writes a repository secret, which is the point: the
     * app's whole secret path goes to an environment, and the one thing it does about repository secrets
     * is report them.
     */
    fun putRepoSecret(repo: String, name: String, sealed: ByteArray) {
        val dir = File(repoDir(repo), "ci-secrets")
        dir.mkdirs()
        File(dir, "$name.bin").writeBytes(sealed)
    }

    /** The sealed bytes of a secret, for a test that wants to prove what was stored. */
    fun sealedSecret(ref: RepoRef, environment: String, name: String): ByteArray? =
        File(File(File(environmentsDir(ref), environment), "secrets"), "$name.bin")
            .takeIf { it.isFile }?.readBytes()

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
