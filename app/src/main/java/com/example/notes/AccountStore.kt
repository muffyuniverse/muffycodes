package com.example.notes

import org.json.JSONObject
import java.io.File

/** One GitHub account stored on this phone. */
data class Account(
    /** The GitHub login, which also names the account's folder. */
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
) {
    fun author(): GitAuthor = GitAuthor(
        name = commitName.ifBlank { login },
        email = commitEmail.ifBlank { "$login@users.noreply.github.com" },
    )
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

    /** The folder of [login]. */
    fun dirOf(login: String): File = File(accountsDir, login)

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
            login = json.optString("login").ifBlank { dir.name },
            commitName = json.optString("commitName"),
            commitEmail = json.optString("commitEmail"),
            dev = json.optBoolean("dev"),
        )
    } catch (e: Exception) {
        Account(login = dir.name, commitName = "", commitEmail = "")
    }

    /** Writes an account into its folder, making the folder if needed. */
    fun save(account: Account): Account {
        val dir = dirOf(account.login)
        stateDir(account.login).mkdirs()
        val json = JSONObject().apply {
            put("login", account.login)
            put("commitName", account.commitName)
            put("commitEmail", account.commitEmail)
            if (account.dev) put("dev", true)
        }
        File(stateDir(account.login), DETAILS).writeText(json.toString())
        return account
    }

    /** Removes everything this phone holds for [login]. */
    fun remove(login: String) {
        dirOf(login).deleteRecursively()
    }

    /**
     * Forgets [login] and leaves everything the person made.
     *
     * Removing an account offers two outcomes: "from this phone only" keeps the
     * files, and deleting the copies takes them too. Keeping them cannot mean
     * keeping the account — the account is what was removed — so what goes is the
     * app's own folder inside it: the identity, the history, the pretend GitHub.
     */
    fun forget(login: String) {
        stateDir(login).deleteRecursively()
        val dir = dirOf(login)
        if (dir.isDirectory && dir.listFiles().isNullOrEmpty()) dir.delete()
    }

    /** True when the phone holds any trace of [login]. */
    fun exists(login: String): Boolean = dirOf(login).isDirectory

    /** Where the test account's pretend GitHub keeps its repositories. */
    fun devGitHubDir(login: String): File = File(stateDir(login), DEV_GITHUB)

    /** The folder the app keeps its own things in, inside an account. */
    fun stateDir(login: String): File = File(dirOf(login), STATE)

    /** The identity file of [login]. */
    fun detailsOf(login: String): File = File(stateDir(login), DETAILS)

    private companion object {
        const val ACCOUNTS = "accounts"
        /** Every file the app keeps for an account, in one hidden folder. */
        const val STATE = ".muffy"
        const val DETAILS = "account.json"
        const val DEV_GITHUB = "test-github"
    }
}
