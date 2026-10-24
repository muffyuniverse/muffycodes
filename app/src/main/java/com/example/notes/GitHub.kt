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
            val id = user?.optLong("id", 0L) ?: 0L
            if (id > 0) "$id+$login@users.noreply.github.com" else "$login@users.noreply.github.com"
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
