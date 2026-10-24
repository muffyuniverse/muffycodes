package com.example.notes

/**
 * Keeps keys out of everything that can be seen.
 *
 * A personal access token is as good as a password, and the ways one leaks are
 * dull and reliable: a message that quotes the address it was working on, an
 * exception that repeats the remote URL, a log line, a file git wrote. The rule
 * this object exists to make easy is short — **the key is never part of an
 * address or a message in the first place**, and anything that could still
 * carry one is scrubbed before it is shown or stored.
 */
object Secrets {

    /**
     * Masks anything in [text] that could be a credential: the user info of a
     * URL is replaced by `***` (`https://token@host/…` comes out with `***` in
     * place of the token), and the shapes GitHub issues tokens in are replaced
     * outright. Ordinary text — including a commit hash, which only looks like
     * a secret — is left alone.
     */
    fun scrub(text: String): String =
        URL_USER_INFO.replace(text) { "${it.groupValues[1]}***@" }
            .replace(TOKEN_SHAPE, "***")

    /**
     * A failure, in a sentence a person can act on.
     *
     * JGit reports a failure as a chain of nested exception messages, and some of
     * what it can throw on Android is not an `Exception` at all: a class it needs but
     * the phone cannot resolve arrives as a `NoClassDefFoundError` whose message is
     * "Failed resolution of: ...", which named no file, no project and no action.
     * Naming the *kind* of failure is what turns that into something reportable.
     */
    fun describe(error: Throwable): String {
        val said = scrub(error.message ?: "").trim()
        val kind = error.javaClass.simpleName
        // A plain message is the answer; a bare class name, or an Error, is not.
        return when {
            said.isEmpty() -> kind
            error !is Exception -> "$said ($kind)"
            else -> said
        }
    }

    /**
     * A network or repository failure, as the sentence a person needs.
     *
     * The raw chain is the app's problem, not the reader's. What they can act on is
     * *which* of the four things went wrong: the key was refused, the phone is not
     * on a network, GitHub is not there, or the repository has no branch to talk
     * about. Everything else keeps JGit's own words, because a message that says
     * something specific is better than one that says "Sync failed" — a failure that
     * does not say what it saw is the thing this app keeps fixing.
     */
    fun explain(error: Throwable, fallback: String): String {
        // **The class name is part of the chain.** `UnknownHostException("github.com")`
        // says nothing about being offline in its message — the name is the only
        // thing that identifies it, and reading messages alone made an offline phone
        // look like an unexplained failure.
        val chain = generateSequence(error as Throwable?) { it.cause }
            .map { "${it?.javaClass?.simpleName.orEmpty()} ${it?.message.orEmpty()}" }
            .joinToString(" ")
            .lowercase()
        val kind = error.javaClass.simpleName
        val said = scrub(error.message ?: "").trim()
        return when {
            // "not authorized", "Authentication is required but no
            // CredentialsProvider has been registered", a 401, a 403 on push.
            listOf(
                "not authorized", "authentication is required", "authentication failed",
                "invalid credentials", "401", "403", "permission denied",
            ).any { it in chain } ->
                "GitHub did not accept this phone's key. Sign in again and try once more."

            listOf(
                "unknownhost", "unable to resolve host", "connection", "timed out",
                "network is unreachable", "ssl", "broken pipe", "handshake",
            ).any { it in chain } ->
                "Could not reach GitHub. The phone may be offline — nothing was changed here."

            // A lock that survived a clear-and-retry: something really is holding the
            // repository open, and saying "cannot lock /data/user/0/…" to a person is
            // telling them to fix something they cannot reach.
            "cannot lock" in chain || "lock file" in chain ->
                "This project was locked by another copy of the app, so nothing was " +
                    "changed. Close the app completely and try again."

            "non-fast-forward" in chain || "rejected" in chain ->
                "GitHub refused the push: $said"

            // An `Error` rather than an `Exception`: its message is often a
            // resolution failure with no verb in it, so the kind is part of the
            // sentence. `catch (Exception)` used to let these out of the worker with
            // no project, no file and no action named.
            error !is Exception -> "$said ($kind)"

            said.isNotEmpty() -> scrub(said)
            error !is Exception -> "$fallback ($kind)"
            else -> fallback
        }
    }

    /** [url] with any credentials removed from its address. */
    fun stripCredentials(url: String): String =
        URL_USER_INFO.replace(url) { it.groupValues[1] }

    /**
     * True when [url] points at GitHub — the only host a key is ever given to.
     * Checked before the key is attached to a request or a clone, so that a
     * repository that quietly points somewhere else cannot collect it.
     */
    fun isGitHubHost(url: String): Boolean {
        val authority = stripCredentials(url)
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("git://")
            .substringBefore('/')
            .substringAfterLast('@')
            .substringBefore(':')
            .trim()
            .lowercase()
        return authority == "github.com" || authority.endsWith(".github.com")
    }

    /** `scheme://userinfo@` — the user info is the part that must not survive. */
    private val URL_USER_INFO = Regex("""([a-zA-Z][a-zA-Z0-9+.-]*://)[^/@\s]+@""")

    /** The token shapes GitHub hands out, for when one travels without a URL. */
    private val TOKEN_SHAPE =
        Regex("""(?:gh[pousr]_[A-Za-z0-9]{16,}|github_pat_[A-Za-z0-9_]{16,})""")
}

/**
 * A value that cannot leak by being printed.
 *
 * Tokens travel inside this rather than in a bare `String`, so that the day
 * somebody writes `"the token was $token"` into a message, what appears is
 * `***`. `reveal()` is the one door out, and it is deliberate every time.
 */
@JvmInline
value class Secret(private val value: String) {
    fun reveal(): String = value

    override fun toString(): String = "***"
}
