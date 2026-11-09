package com.example.notes

import org.json.JSONObject
import java.io.File

/** One account stored on this phone: a name, the host it is tied to, and a key. */
data class Account(
    /** The login, which also names the account's folder. */
    val login: String,
    /** The name written into commits made while this account is active. */
    val commitName: String,
    /** The address written into commits made while this account is active. */
    val commitEmail: String,
    /**
     * True when this account is the test account of a debug build: it has no key
     * and talks to a GitHub that is a folder on this phone. A release build can
     * never make one, and no account stored by a release build ever has this set.
     */
    val dev: Boolean = false,
    /**
     * **The host this account's key belongs to** — part of its identity, not a detail of a remote.
     *
     * A credential may only be sent to the host it was made for, so an account has to say which host
     * that is; that is what `GitCredentials.forRemote` reads instead of assuming GitHub. The default is
     * GitHub because that is the one host this phone can sign in to without being told an address.
     */
    val host: String = GITHUB_HOST,
    /**
     * The username sent with this account's key, when the host needs one.
     *
     * **Data, because there is no universal answer.** GitHub and Gitea take the token as the username,
     * GitLab documents `oauth2:<token>`, Bitbucket uses `x-token-auth` or a person's own name. Blank
     * means the token is the username, which is the commonest shape; a URL that names a user
     * (`https://x-token-auth@host/…`) still wins over this, because the person wrote it on that project.
     */
    val gitUser: String = "",
) {
    fun author(): GitAuthor = GitAuthor(
        name = commitName.ifBlank { login },
        // **The account's own host**, so a commit made through `gitlab.com` never claims a GitHub
        // `users.noreply` address that belongs to somebody else.
        email = commitEmail.ifBlank { noreplyEmail(login, host = host) },
    )
}

/**
 * The address a commit by [login] gets when the host gave us none.
 *
 * **GitHub's own form, and only for GitHub.** The `users.noreply.github.com` domain belongs to one
 * host; writing it into a commit made through a `gitlab.com` account would attribute the work to a
 * stranger who happens to hold that domain's login — and the address is permanent once a commit exists,
 * so it is decided here rather than left to be noticed later.
 *
 * It carries the numeric id when one is known, because that is what GitHub itself puts in a commit's
 * address and what makes the address belong to the account rather than to whoever took the login later.
 * Any other host answers the login at the host's own domain, which is a convention rather than a
 * promise — and a local project, which has no host at all, is left to [fallbackEmail].
 */
/**
 * What is said when an account is still on the phone after two attempts to remove it.
 *
 * **Named, not implied.** The person pressed a button that promised the account would go; the one
 * thing that must not happen is a screen that has forgotten it while the disk has not. The sentence
 * says which account, that it is still there, and what to do — the same three things every refusal
 * in this app says.
 */
fun removalProblemSaid(login: String): String =
    "\"$login\" is still on this phone: something was still writing into it. Remove it again."

internal fun noreplyEmail(login: String, id: Long = 0L, host: String = GITHUB_HOST): String {
    if (!Secrets.isHost("https://$host/", GITHUB_HOST) && host.isNotBlank()) {
        // Not GitHub's domain, so not GitHub's address form. `user@host` is what every mail-shaped
        // identity on another host looks like, and it is the honest thing to write.
        return "$login@$host"
    }
    return if (id > 0) "$id+$login@users.noreply.github.com" else "$login@users.noreply.github.com"
}

/**
 * The accounts stored on this phone, and where each one's tree lives.
 *
 * The account folder is the root the app shows, and the account's own details
 * are kept inside it in `account.json`, so an account folder is self-contained:
 * delete the folder and nothing of that account is left on the phone.
 *
 * ```
 * files/
 *   accounts/
 *     muffyuniverse/
 *       account.json      this file
 *       projects/         the projects, and everything else the user keeps
 * ```
 */
class AccountStore(private val filesDir: File) {

    val accountsDir: File get() = File(filesDir, ACCOUNTS)

    /**
     * **There is no account an app can ship.**
     *
     * The operator, 2026-11-09: *"scrapping local account so that there are no prebuilt accounts and
     * projects"*. There used to be a `~local` account — a workspace with no key and no host, kept
     * undeletable so it could always exist. It is gone, and with it the whole idea: an account is an
     * identity, an identity is something a person has, and one that cannot be deleted is a credential
     * that cannot be revoked.
     *
     * What a phone has before anybody signs in is **the app's own folder** ([sharedDir]) and nothing
     * else — which is enough for the introduction to be readable, and not enough to write a note,
     * because a note is a file in a repository and a repository belongs to somebody.
     */

    /** The folder of [login]. */
    fun dirOf(login: String): File = File(accountsDir, login)

    /**
     * **The app's own folder: one per phone, beside the accounts rather than inside one** (§3.11).
     *
     * It holds what is nobody's — the agent's system prompt, the onboarding pages, the request library,
     * the app's README — so it has to exist on a phone that has never signed in, and it must not be
     * listed as an account. Two siblings under the app's files directory is the whole of that.
     */
    val sharedDir: File get() = File(filesDir, Workspace.SYSTEM_PROJECT)

    /** Every account with an `account.json` on disk. */
    fun accounts(): List<Account> =
        (accountsDir.listFiles() ?: emptyArray())
            .filter { it.isDirectory && detailsOf(it.name).isFile }
            .map { read(it) }
            .filter { it.login.isNotBlank() }
            .sortedBy { it.login.lowercase() }

    /** The account [login], or null when this phone has no such account. */
    fun account(login: String?): Account? {
        if (login.isNullOrBlank()) return null
        return if (detailsOf(login).isFile) read(dirOf(login)) else null
    }

    private fun read(dir: File): Account = try {
        val json = JSONObject(File(stateDir(dir.name), DETAILS).readText())
        Account(
            // **`optString` is not a substitute for a null check.** Android's `org.json`
            // answers the four-character string "null" for a JSON null, because it
            // stringifies the value instead of treating it as absent. That put the word
            // "null" on the Settings screen where a person's email belongs — and, worse,
            // it went into commits as the author's address. [text] asks for a String and
            // nothing else.
            login = text(json, "login").ifBlank { dir.name },
            commitName = text(json, "commitName"),
            commitEmail = text(json, "commitEmail"),
            dev = json.optBoolean("dev"),
            // **`ifBlank` rather than a null check.** A file with no host describes the account a
            // sign-in makes, which is a GitHub one.
            host = text(json, "host").ifBlank { GITHUB_HOST },
            gitUser = text(json, "gitUser"),
        )
    } catch (e: Exception) {
        Account(login = dir.name, commitName = "", commitEmail = "")
    }

    /** A field that is only ever a string, or empty when it is missing or null. */
    private fun text(json: JSONObject, key: String): String {
        val value = json.opt(key)
        return if (value is String && value != "null") value else ""
    }

    /** Writes an account into its folder, making the folder if needed. */
    fun save(account: Account): Account {
        val dir = dirOf(account.login)
        stateDir(account.login).mkdirs()
        val json = JSONObject().apply {
            put("login", account.login)
            put("commitName", account.commitName)
            put("commitEmail", account.commitEmail)
            put("host", account.host)
            if (account.gitUser.isNotBlank()) put("gitUser", account.gitUser)
            if (account.dev) put("dev", true)
        }
        File(stateDir(account.login), DETAILS).writeText(json.toString())
        return account
    }

    /**
     * Removes everything this phone holds for [login], and says whether it is gone.
     *
     * **The answer is the point.** It used to be discarded, and the one case where it mattered is
     * the one this app keeps finding: a save still on its way to the worker writes into the tree
     * while this walks it, `delete` fails on the folder that has just been re-made, and the person
     * is told the account was removed while it is still on the phone. A caller that cannot see the
     * `false` cannot ask again or say so.
     */
    fun remove(login: String): Boolean {
        // The whole account goes, so following a link here would delete nothing that is not already
        // being deleted - but the same function is used for both, so it uses the safe walk.
        return Workspace.deleteTree(dirOf(login))
    }

    /**
     * Forgets [login] and leaves everything the person made.
     *
     * Removing an account offers two outcomes: "from this phone only" keeps the
     * files, and deleting the copies takes them too. Keeping them cannot mean
     * keeping the account — the account is what was removed — so what goes is the
     * app's own folder inside it: the identity, the history, the pretend GitHub.
     */
    fun forget(login: String): Boolean {
        // **`Workspace.deleteTree`, because this folder holds links to something that survives.**
        // `.muffy/worktrees/<project>/<branch>/system` points at the account's own system folder, and
        // `deleteRecursively()` follows a symlink - so forgetting an account while keeping its files
        // would have emptied the settings folder that "keep the files" is supposed to leave alone.
        Workspace.deleteTree(stateDir(login))
        val dir = dirOf(login)
        if (dir.isDirectory && dir.listFiles().isNullOrEmpty()) dir.delete()
        // **The account is gone when its identity is, not when its folder is.** Keeping the copies
        // is a decision to leave a folder full of projects behind, so `dir` staying is the success
        // case here - and `accounts()` reads exactly this file to decide what this phone has.
        return !detailsOf(login).isFile
    }

    /** True when the phone holds any trace of [login]. */
    fun exists(login: String): Boolean = dirOf(login).isDirectory

    /** Where the test account's pretend GitHub keeps its repositories. */
    fun devGitHubDir(login: String): File = File(stateDir(login), DEV_GITHUB)

    /** The folder the app keeps its own things in, inside an account. */
    fun stateDir(login: String): File = File(dirOf(login), STATE)

    /** The identity file of [login]. */
    fun detailsOf(login: String): File = File(stateDir(login), DETAILS)

    companion object {
        const val ACCOUNTS = "accounts"
        /** Every file the app keeps for an account, in one hidden folder. */
        private const val STATE = ".muffy"
        private const val DETAILS = "account.json"
        private const val DEV_GITHUB = "test-github"
    }
}
