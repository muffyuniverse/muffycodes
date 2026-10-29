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
        email = commitEmail.ifBlank { noreplyEmail(login) },
    )
}

/**
 * The address a commit by [login] gets when GitHub gives us none.
 *
 * **GitHub's own form**, and one rule rather than three: the account screen, the sign-in wrote it,
 * and a fetched identity. It carries the numeric id when one is known, because that is what GitHub
 * itself puts in a commit's address and what makes the address belong to the account rather than to
 * whoever took the login later.
 */
internal fun noreplyEmail(login: String, id: Long = 0L): String = if (id > 0) {
    "$id+$login@users.noreply.github.com"
} else {
    "$login@users.noreply.github.com"
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
     * **The account that exists before anybody signs in, and again whenever the last one is
     * removed.**
     *
     * Its folder is a real account folder in every respect - projects, git repositories, the
     * system project, the request history - so every part of the app that asks "is there a
     * workspace" answers yes and nothing had to learn about a second kind of place to keep files.
     * That is the whole point of it being an account rather than a special case.
     *
     * **The name contains `~`, which a GitHub login cannot.** So it can never collide with a real
     * account, and a folder called `~local` in a log or a path is unmistakably not somebody's.
     */
    val provisional: Account = Account(
        login = PROVISIONAL,
        commitName = "",
        commitEmail = "",
    )

    /** True for the provisional account's name. */
    fun isProvisional(login: String?): Boolean = login == PROVISIONAL

    /** The folder of [login]. */
    fun dirOf(login: String): File = File(accountsDir, login)

    /**
     * Makes sure the provisional account exists on disk, and answers its folder.
     *
     * Called when the app starts and after the last account is removed. **It writes nothing if the
     * folder is already there**, because that folder may hold months of a person's work - this is
     * the one account whose contents are never disposable.
     */
    fun ensureProvisional(): Account {
        val dir = dirOf(PROVISIONAL)
        if (!detailsOf(PROVISIONAL).isFile) {
            dir.mkdirs()
            save(provisional)
        }
        return provisional
    }

    /**
     * Hands everything in the provisional account to [login], **by renaming its folder.**
     *
     * **A rename, not a copy.** The two folders are both under `accountsDir`, so this is one
     * atomic step: either the projects are the new account's or they are still the provisional
     * account's, and there is no moment at which both hold them or neither does. A copy would
     * leave two workspaces claiming the same projects if it failed halfway, and nothing on screen
     * would say which one was real.
     *
     * **Refuses when [login] already has a folder.** Merging two workspaces is not a rename - it
     * needs a rule for two projects of the same name, and two git repositories that disagree about
     * their history. Refusing and leaving the provisional account untouched is the only answer that
     * cannot lose work; the caller decides what to tell the person.
     *
     * Answers true when the provisional content is now the account's.
     */
    fun adoptProvisional(login: String): Boolean {
        val from = dirOf(PROVISIONAL)
        val to = dirOf(login)
        // **Nothing to hand over**: no provisional folder, or one that never got anything in it.
        // The second case still removes it, so a sign-in does not leave an empty `~local` behind.
        val hasContent = from.isDirectory &&
            (from.listFiles() ?: emptyArray()).any { it.name != STATE }
        if (!hasContent) {
            from.deleteRecursively()
            return false
        }
        if (to.exists()) return false
        to.parentFile?.mkdirs()
        return try {
            from.renameTo(to)
        } catch (t: Throwable) {
            // A rename that throws leaves the provisional folder where it was, which is the safe
            // half of the failure: the person's projects are still reachable.
            false
        }
    }

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
        /**
         * The provisional account's name.
         *
         * **`~` because a GitHub login cannot contain one**, so this is provably not a real
         * account and can never be shadowed by somebody who registers the name.
         */
        const val PROVISIONAL = "~local"
        /** Every file the app keeps for an account, in one hidden folder. */
        const val STATE = ".muffy"
        const val DETAILS = "account.json"
        const val DEV_GITHUB = "test-github"
    }
}
