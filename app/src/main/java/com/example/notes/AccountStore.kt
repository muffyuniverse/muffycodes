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

/**
 * What is said when somebody tries to give a project in the local account a remote.
 *
 * **The local account is not an identity, so it has no host to push to.** It stores no host and no key,
 * and `whoFor` answers null for it — so an address here would be a repository this phone cannot speak
 * to. The sentence names the way out rather than the prohibition: move the project into an account,
 * where the key it already has can reach the address.
 *
 * Three doors ask this — `setAddress`, `addProjectFromUrl` and a transfer arriving connected — because
 * they are three ways to write `remote.origin.url`, and a rule kept at one of them is a rule with a
 * hole.
 */
fun localOriginSaid(): String =
    "Projects in Local stay on this phone: it has no host and no key, so there is nothing to " +
        "reach an address with. Move the project into an account and set the address there."

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
     * **The account that exists before anybody signs in, and that is never removed.**
     *
     * It is made once, on a phone that has no account at all, and from then on it is as fixed as the
     * app's own folder: `remove` and `forget` refuse it. That is the operator's 2026-11-09 ruling —
     * *"make ~local undeletable … so that local always exist and we dont need machinery for its
     * recreation"* — and its consequence is the good part: there is no code that remakes it, because
     * there is no state in which it is gone.
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
     * **The app's own folder: one per phone, beside the accounts rather than inside one** (§3.11).
     *
     * It holds what is nobody's — the agent's system prompt, the onboarding pages, the request library,
     * the app's README — so it has to exist on a phone that has never signed in, and it must not be
     * listed as an account. Two siblings under the app's files directory is the whole of that.
     */
    val sharedDir: File get() = File(filesDir, Workspace.SYSTEM_PROJECT)

    /**
     * Makes sure the provisional account exists on disk — **on a phone that has no account at all.**
     *
     * Called when the app starts. It is *creation*, not recreation: the account cannot be removed, so
     * the only state this answers to is a fresh install, and nothing has to remake it later. **It
     * writes nothing if the folder is already there**, and that matters more now than it did: the
     * folder it would otherwise touch holds the only copy of work on a phone that has never signed in.
     */
    fun ensureProvisional(): Account {
        val dir = dirOf(PROVISIONAL)
        if (!detailsOf(PROVISIONAL).isFile) {
            dir.mkdirs()
            save(provisional)
        }
        return provisional
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
        // **The provisional account is not removable, and it is the store that says so.** Every other
        // answer about a removal — the sentence on screen, the second attempt on the worker — is a
        // report; this is the rule. `~local` is the account this phone always has: work that has never
        // been signed in lives in it, and a phone with no account at all would be a phone with nowhere
        // to type. Its projects leave by `Transfer`, one at a time, and the folder stays.
        if (isProvisional(login)) return false
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
        // **Kept as a workspace, and that is the same rule the other half follows**: "forget" removes
        // an identity this phone can act as, and `~local` never had one.
        if (isProvisional(login)) return false
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
        /**
         * The provisional account's name.
         *
         * **`~` because a GitHub login cannot contain one**, so this is provably not a real
         * account and can never be shadowed by somebody who registers the name.
         *
         * **Public, because a caller needs the name when it is not choosing an account but naming the
         * one that is always there** — `removeAccount`'s takeover chain answers with it when no real
         * account is left, and asking the store to *make* it there would be asking for something that
         * cannot be missing.
         */
        const val PROVISIONAL = "~local"
        /** Every file the app keeps for an account, in one hidden folder. */
        private const val STATE = ".muffy"
        private const val DETAILS = "account.json"
        private const val DEV_GITHUB = "test-github"
    }
}
