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
 * ## Why the account's host is a fact rather than a table
 *
 * The app signs in to one kind of host (see [DeviceFlow]), so "the account's own host" is [isAccountHost].
 * Everything else is data: a name the project chooses, and a secret bound to a hostname. A second host
 * family would be a second sign-in door, not a second branch in this file.
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

        /** Send this token. */
        data class Use(val token: String) : Answer

        /** Nothing to send, and nothing wrong: there is no host at all. */
        data object None : Answer

        /** **Nothing will be sent, and this is why** — in words a person can act on. */
        data class Say(val why: String) : Answer
    }

    /**
     * True when [remoteUrl] names the host this phone's account signed in to.
     *
     * The one place the app's own identity provider is named — see the file's comment. A project on any
     * other host is answered by [SECRET] from the vault and never by this.
     */
    fun isAccountHost(remoteUrl: String): Boolean = Secrets.isGitHubHost(remoteUrl)

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
        vault: (host: String) -> String?,
    ): Answer {
        val url = remoteUrl?.trim().orEmpty()
        // **Nowhere to send a key.** `hostOf` answers an empty string for a local path, which is the
        // honest reading: not a network remote, rather than a guess that it is somebody else's server.
        val host = Secrets.hostOf(url)
        if (host.isEmpty()) return Answer.None

        // **The project's own secret first**, and the store already answers narrowest-first, so this
        // is one question rather than three.
        vault(host)?.takeIf { it.isNotBlank() }?.let { return Answer.Use(it) }

        if (isAccountHost(url)) {
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

        return Answer.Say(
            "Nothing was sent: $host is not the host this account signed in to, and no secret " +
                "called `$SECRET` is stored for it. Add one on Settings → Secrets, bound to $host, " +
                "and this project will use it.",
        )
    }
}
