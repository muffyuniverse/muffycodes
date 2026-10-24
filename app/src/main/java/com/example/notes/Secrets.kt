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
