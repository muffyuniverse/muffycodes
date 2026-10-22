package com.example.notes

import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * A small client for the GitHub REST API.
 *
 * The `gh` command-line tool cannot run inside an Android app (it is a native
 * binary for desktop operating systems), so the app talks to the same API
 * directly. That gives it the same capabilities: check who is signed in, list
 * the user's repositories and create a new one.
 *
 * Every method takes a personal access token; nothing is stored or sent
 * without one.
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

    /** A repository of the signed-in user. */
    data class Repo(
        val name: String,
        val fullName: String,
        val private: Boolean,
        val cloneUrl: String,
    )

    /**
     * Checks the token and returns the name of the signed-in account.
     */
    fun whoami(token: String): Result<String> {
        val response = request("GET", "/user", token)
        if (response.error != null) return Result(error = response.error)
        val login = response.value?.optString("login").orEmpty()
        if (login.isEmpty()) {
            return Result(error = response.error ?: "GitHub did not return an account name")
        }
        return Result(value = login)
    }

    /** The repositories of the signed-in account. */
    fun listRepos(token: String): Result<List<Repo>> {
        val response = request("GET", "/user/repos?per_page=100&sort=updated", token)
        if (response.error != null) return Result(error = response.error)
        val array = response.value?.optJSONArray("repos")
            ?: return Result(error = "GitHub did not return a list of repositories")
        val repos = mutableListOf<Repo>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            repos.add(
                Repo(
                    name = obj.optString("name"),
                    fullName = obj.optString("full_name"),
                    private = obj.optBoolean("private"),
                    cloneUrl = obj.optString("clone_url"),
                )
            )
        }
        return Result(value = repos)
    }

    /**
     * Creates a new repository owned by the signed-in account and returns it.
     * The repository is created empty (no automatic README), so the first
     * commit in the app is the first commit in the repository.
     */
    fun createRepo(
        token: String,
        name: String,
        private: Boolean,
        description: String = "",
    ): Result<Repo> {
        val body = JSONObject().apply {
            put("name", name)
            put("private", private)
            put("auto_init", false)
            if (description.isNotBlank()) put("description", description)
        }
        val response = request("POST", "/user/repos", token, body)
        if (response.error != null) return Result(error = response.error)
        val obj = response.value ?: return Result(error = "GitHub did not return the new repository")
        return Result(
            value = Repo(
                name = obj.optString("name"),
                fullName = obj.optString("full_name"),
                private = obj.optBoolean("private"),
                cloneUrl = obj.optString("clone_url"),
            )
        )
    }

    /** The URL to clone a repository over HTTPS with the token embedded. */
    fun authenticatedCloneUrl(cloneUrl: String, token: String): String {
        if (token.isBlank()) return cloneUrl
        // https://github.com/owner/repo.git -> https://TOKEN@github.com/owner/repo.git
        return cloneUrl.replaceFirst("https://", "https://$token@")
    }

    /** The URL used to open a repository in the browser. */
    fun webUrl(cloneUrl: String): String =
        cloneUrl.replaceFirst("https://", "https://")
            .removeSuffix(".git")

    /** The raw response from the API. */
    private fun request(
        method: String,
        path: String,
        token: String,
        body: JSONObject? = null,
    ): Result<JSONObject> {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(API + path).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "MuffyCodes-Notes")
                if (token.isNotBlank()) {
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
                    JSONObject(text).optJSONObject("message")?.optString("error")
                        ?: JSONObject(text).optString("message")
                } catch (e: Exception) {
                    null
                } ?: "GitHub returned error $code"
                Result(error = message)
            } else {
                Result(value = if (text.isBlank()) JSONObject() else JSONObject(text))
            }
        } catch (e: Exception) {
            Result(error = e.message ?: "Could not reach GitHub")
        } finally {
            connection?.disconnect()
        }
    }
}
