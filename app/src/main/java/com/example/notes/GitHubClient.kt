package com.example.notes

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * A small client for the GitHub REST API.
 *
 * The `gh` command-line tool cannot run inside an Android app (it is a native
 * binary for desktop operating systems), so the app talks to the same API
 * directly: who is signed in, the account's repositories, creating one with a
 * licence, renaming one, deleting one.
 *
 * Every method takes a personal access token; nothing is stored or sent without
 * one, and the token is only ever attached to GitHub itself.
 */
object GitHubClient {

    private const val API = "https://api.github.com"

    /** The outcome of an API call. */
    data class Result<T>(
        val value: T? = null,
        val error: String? = null,
    ) {
        val isSuccess: Boolean get() = error == null && value != null
    }

    /** A repository of the signed-in account. */
    data class Repo(
        val name: String,
        val fullName: String,
        val private: Boolean,
        val cloneUrl: String,
        val updatedAt: String = "",
    )

    /** A licence the API can seed a new repository with. */
    data class Licence(val key: String, val name: String)

    /** Checks the token and returns the name of the signed-in account. */
    fun whoami(token: String): Result<String> {
        val response = get("GET", "/user", token)
        if (response.error != null) return Result(error = response.error)
        val login = response.value?.optString("login").orEmpty()
        if (login.isEmpty()) {
            return Result(error = "GitHub did not return an account name")
        }
        return Result(value = login)
    }

    /** The repositories of the signed-in account, most recently changed first. */
    fun listRepos(token: String): Result<List<Repo>> {
        val response = get("GET", "/user/repos?per_page=100&sort=updated", token)
        if (response.error != null) return Result(error = response.error)
        val array = response.array ?: return Result(error = "GitHub did not return a list")
        val repos = mutableListOf<Repo>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            repos.add(repo(obj))
        }
        return Result(value = repos)
    }

    /** The licences a repository can be created with (§4.1 of the design). */
    fun licences(token: String): Result<List<Licence>> {
        val response = get("GET", "/licenses", token)
        if (response.error != null) return Result(error = response.error)
        val array = response.array ?: return Result(error = "GitHub did not return a list")
        val licences = mutableListOf<Licence>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val key = obj.optString("key")
            if (key.isNotEmpty()) licences.add(Licence(key, obj.optString("name")))
        }
        return Result(value = licences.sortedBy { it.name.lowercase() })
    }

    /**
     * Creates a public repository seeded by GitHub itself — `LICENSE` and
     * `README.md`, in one initial commit — and returns it.
     */
    fun createRepo(
        token: String,
        name: String,
        licence: String = "",
        description: String = "",
    ): Result<Repo> {
        val body = JSONObject().apply {
            put("name", name)
            put("private", false)
            put("auto_init", true)
            if (licence.isNotBlank()) put("license_template", licence)
            if (description.isNotBlank()) put("description", description)
        }
        val response = get("POST", "/user/repos", token, body)
        if (response.error != null) return Result(error = response.error)
        val obj = response.value ?: return Result(error = "GitHub did not return the new repository")
        return Result(value = repo(obj))
    }

    /** Renames a repository, so the project and its repository keep one name. */
    fun renameRepo(token: String, owner: String, name: String, newName: String): Result<Repo> {
        val body = JSONObject().put("name", newName)
        val response = get("PATCH", "/repos/$owner/$name", token, body)
        if (response.error != null) return Result(error = response.error)
        val obj = response.value ?: return Result(error = "GitHub did not return the repository")
        return Result(value = repo(obj))
    }

    /** Deletes a repository. Used only by "delete everywhere". */
    fun deleteRepo(token: String, owner: String, name: String): Result<Unit> {
        val response = get("DELETE", "/repos/$owner/$name", token)
        if (response.error != null) return Result(error = response.error)
        return Result(value = Unit)
    }

    /** The URL used to open a repository in the browser. */
    fun webUrl(cloneUrl: String): String = cloneUrl.removeSuffix(".git")

    /** The address a repository is cloned from, from its owner and name. */
    fun cloneUrlFor(owner: String, name: String): String =
        "https://github.com/$owner/${urlPart(name)}.git"

    /** Escapes a repository name for use in a path or an address. */
    fun urlPart(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

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
}
