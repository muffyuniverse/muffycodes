package com.example.notes

/**
 * **What a project's remote authenticates with, and the sentence when it cannot.**
 *
 * One decision, in one place, with no Android in it — the same shape as [TokenScopes] and for the same
 * reason: this is where the portability rule lives, and a rule that can be tested with three strings
 * and no device is a rule that survives being moved.
 *
 * ## The rule
 *
 * 1. **A remote with no host gets nothing.** A local path is not a foreign server: a bare repository
 *    beside the project, or a project cloned from a folder on the phone, has nowhere to send a key.
 * 2. **A secret for that host wins, whatever the account is.** The vault is asked by *host*, and it
 *    answers with the narrowest scope that has the name (project, then account, then shared). So a
 *    project on `gitlab.com` carries its own credential, and nothing in this app needs to know that
 *    `gitlab.com` exists.
 * 3. **Otherwise the account's own token, but only for the account's own host.** That token is the
 *    identity the person signed in with; giving it to a repository that quietly points elsewhere is the
 *    one thing this app has always refused. What is new is that the refusal is now a **sentence**
 *    instead of a null that let the server answer 401 about a key that was never offered.
 *
 * ## Why the account's host is data rather than a table
 *
 * An account is made against one host, and that hostname is stored on the account — so "the account's
 * own host" is [isAccountHost] with a value, never a constant. Everything else is data too: a name the
 * project chooses, and a secret bound to a hostname, so supporting a host is an act in the vault rather
 * than a branch in this file.
 */
object GitCredentials {

    /**
     * The secret a project's remote reads its credential from.
     *
     * **A name, not a table.** Anywhere a person can type a secret they can type this one, bound to
     * whatever host the project is on — so supporting a host is an act in the vault rather than a
     * commit here.
     */
    const val SECRET = "git"

    /** The three answers a remote can get. */
    sealed interface Answer {

        /**
         * Send this token, and present it as [user].
         *
         * **[user] is null when the credential did not name one**, which is the account's own
         * username — the one place "who am I on this host" is recorded. A connection from the scoped
         * vault names its own (`gitlab.com` wants `oauth2` beside a personal token, and the account's
         * name would be a different person's), and a blank one is a person who cleared it rather than
         * an absence to be filled in: the window shows the name it would use, so "leave the field
         * alone" means the stored value, not a second inheritance rule.
         */
        data class Use(val token: String, val user: String? = null) : Answer

        /** Nothing to send, and nothing wrong: there is no host at all. */
        data object None : Answer

        /** **Nothing will be sent, and this is why** — in words a person can act on. */
        data class Say(val why: String) : Answer
    }

    /**
     * True when [remoteUrl] names the host the account's key belongs to.
     *
     * **The host is an argument, not a constant.** It used to read `Secrets.isGitHubHost`, which made
     * "the account's host" mean "GitHub" — so a key made for `gitlab.com` would have been offered to
     * `github.com`, and the reverse. An account is tied to one host (`Account.host`), and this is
     * where that fact is compared with the address the project actually has.
     */
    fun isAccountHost(accountHost: String, remoteUrl: String): Boolean =
        accountHost.isNotBlank() && Secrets.isHost(remoteUrl, accountHost)

    /** The seam the sentence needs when [remoteUrl] is nobody's host. */
    private fun withheld(host: String, accountHost: String): String = if (accountHost.isBlank()) {
        "Nothing was sent: no host is recorded for this account, so it has no key it may offer."
    } else {
        "Nothing was sent: $host is not the host this account signed in to, and no secret " +
            "called `$SECRET` is stored for it. Add one on Settings → Secrets, bound to $host, " +
            "and this project will use it."
    }

    /**
     * What to send to [remoteUrl].
     *
     * [vault] is asked by host and answers the stored secret for it, or null. It is a function rather
     * than a [Credentials] so that this decision can be tested without a store, and so that the *scope*
     * rule (project, then account, then shared) stays where it already is, in the store.
     */
    fun forRemote(
        remoteUrl: String?,
        accountToken: String?,
        accountHost: String,
        vault: (host: String) -> String?,
        /**
         * The scoped connection for this host, or null — Vault B's git kind.
         *
         * A function rather than a store, so this stays a decision over three strings and one look-up,
         * and so the *scope* rule (branch, then project, then account) stays where it lives.
         */
        connection: (host: String) -> Connection? = { null },
    ): Answer {
        val url = remoteUrl?.trim().orEmpty()
        // **Nowhere to send a key.** `hostOf` answers an empty string for a local path, which is the
        // honest reading: not a network remote, rather than a guess that it is somebody else's server.
        val host = Secrets.hostOf(url)
        if (host.isEmpty()) return Answer.None

        // **The scope's own connection first.** It is what a person sets in a row's ⋮, and it carries
        // the username with the token — the two are one credential, because a token presented under
        // the wrong name is a different identity.
        connection(host)?.takeIf { it.secret.isNotBlank() }?.let {
            return Answer.Use(it.secret, it.user)
        }

        // **Then a secret named `git` bound to this host**, which is the way a person reached a host
        // this app knows nothing about before there was a scoped window. Kept, because a credential
        // somebody stored must not stop working because a second door onto the same host was added.
        vault(host)?.takeIf { it.isNotBlank() }?.let { return Answer.Use(it) }

        if (isAccountHost(accountHost, url)) {
            // **The account's own host, and no token**: not a portability problem but a sign-in one,
            // and the two are answered differently or the person fixes the wrong thing.
            return if (!accountToken.isNullOrBlank()) {
                Answer.Use(accountToken)
            } else {
                Answer.Say(
                    "Nothing was sent, because $host is where this account signed in and this phone " +
                        "has no key for it. Sign in first.",
                )
            }
        }

        return Answer.Say(withheld(host, accountHost))
    }
}
