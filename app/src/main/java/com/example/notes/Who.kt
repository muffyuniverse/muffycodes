package com.example.notes

import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * **The one question this app asks a host's own API: whose key is this?**
 *
 * Everything else that used to live beside it — listing an account's repositories, making one,
 * renaming one, CI runs and their artifacts, environment secrets — is a vendor's way of doing
 * something the git protocol cannot express, and every one of them is now the host's website or the
 * agent's `http` tool. What is left is the question a *sign-in* cannot avoid: after the key is
 * minted, who is it, and what may it do. That answer decides the account's name and the two lines
 * that go on its commits, and there is no git command that can tell us.
 *
 * **The name is the question, not the vendor.** One call to one host remains vendor-shaped, and
 * calling the file `GitHub.kt` would keep a 1,762-line name alive for 150 lines of it. `Who` is what
 * the app asks; [HttpWho] is GitHub's answer and [DiskWho] is the answer a test build gives.
 *
 * **This is why a door knows its own host.** GitHub's door fills the account's name, host and commit
 * identity from [Identity]; another host's door asks the person, because there is no address it could
 * call. That asymmetry is the design, not an unfinished corner.
 */
interface Who {
    /** Who [token] belongs to, or why it was refused. */
    fun identity(token: String): Reply<Identity>
}

/**
 * The outcome of asking, whichever host is behind it.
 *
 * [status] is the HTTP status when there was one, and 0 when the call never got that far — an
 * offline phone, a mock that says no. It is carried structurally because the sign-in door decides
 * "the key was refused" from it rather than from the text of [error].
 */
data class Reply<T>(
    val value: T? = null,
    val error: String? = null,
    val status: Int = 0,
)

/**
 * Who a key belongs to, as the host itself says.
 *
 * Name and email are the account's own, so signing in asks for nothing but the key: a commit is
 * written as the person whose account it is, and they have already told the host who that is.
 */
data class Identity(
    val login: String,
    val name: String,
    val email: String,
    /**
     * **What the token that produced this identity can do**, as the host reports it.
     *
     * Null when no scope header came back — a fine-grained token, or an instance that does not
     * report them. Null is "cannot tell", not "nothing", and [TokenScopes.judge] treats the two
     * differently: a token it cannot judge is accepted, because refusing one on the strength of a
     * missing header would lock out every fine-grained user.
     */
    val scopes: Set<String>? = null,
)

/**
 * GitHub itself, over HTTPS — **one endpoint.**
 *
 * The `gh` command-line tool cannot run inside an Android app (it is a native binary for desktop
 * operating systems), so the app asks `GET /user` directly. That is the whole of this class now:
 * making a repository, listing them, dispatching a workflow and reading an artifact are gone, and
 * what replaced them is the host's website and the agent's `http` tool.
 */
class HttpWho(private val api: String = API) : Who {

    override fun identity(token: String): Reply<Identity> {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("$api/user").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 30_000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "MuffyCodes")
                // The key is only ever attached to GitHub itself. A request that would go anywhere
                // else is sent without it.
                if (token.isNotBlank() && Secrets.isGitHubHost(api)) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (code !in 200..299) {
                val message = try {
                    JSONObject(text).optString("message").ifBlank { "GitHub returned error $code" }
                } catch (e: Exception) {
                    "GitHub returned error $code"
                }
                return Reply(error = Secrets.scrub(message), status = code)
            }
            // **Read before the body is parsed**, because the header is on the connection and the
            // connection is closed in the `finally` below. `null` when the header is absent, which is
            // what a fine-grained token looks like: GitHub sends no scope header for those.
            val scopes = connection.getHeaderField("x-oauth-scopes")?.let { header ->
                header.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            }
            val user = JSONObject(text)
            val login = user.optString("login")
            if (login.isEmpty()) return Reply(error = "GitHub did not return an account name")
            // A name is optional on GitHub, and so is a public email. Both have a truthful fallback:
            // the login is what the person is called here, and GitHub's own noreply address is what it
            // uses for a commit that must not expose a real one.
            val name = user.optString("name").ifBlank { login }
            val email = user.optString("email").ifBlank {
                noreplyEmail(login, user.optLong("id", 0L), GITHUB_HOST)
            }
            Reply(value = Identity(login = login, name = name, email = email, scopes = scopes))
        } catch (e: Exception) {
            Reply(error = Secrets.scrub(e.message ?: "Could not reach GitHub"))
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        const val API = "https://api.github.com"
    }
}

/**
 * A host that answers who a key belongs to — **and has no repositories, no CI and no pages.**
 *
 * A debug build gives the test account this instead of [HttpWho], so every path that needs a
 * sign-in can be walked on a machine that must never hold a real key. Its remaining job is the one
 * question the interface asks; a repository for the fixtures to clone is a bare folder the test makes
 * by hand, which is exactly what a host's website does.
 *
 * This is only ever reached through the test account of a debug build. A release build has no way to
 * name it.
 */
class DiskWho(private val owner: String = "dev") : Who {

    override fun identity(token: String): Reply<Identity> =
        // A key is required, exactly as GitHub requires one. Returning the owner regardless made this
        // seam unable to say no, so nothing ever exercised the half of signing in that reports a
        // refusal.
        if (token.isBlank()) {
            Reply(error = "Bad credentials", status = 401)
        } else {
            Reply(
                value = Identity(
                    login = owner,
                    name = "Test Account",
                    email = "$owner@example.com",
                )
            )
        }
}
