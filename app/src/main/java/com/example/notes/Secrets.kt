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
/**
 * The host this app was written for, and the one every account that existed before hosts were
 * recorded belongs to.
 *
 * **A named constant, because "the account's host is GitHub's" is now a comparison rather than an
 * assumption**, and a string literal in six places is a rule with six spellings.
 */
const val GITHUB_HOST = "github.com"

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
            // **A lock is checked first, and by its own words.** It is a local
            // condition whose message is a path — and a path is full of digits. Matching
            // a bare "401" anywhere in the chain read `/tmp/…-1761` as an HTTP status and
            // told a person their key was wrong when their repository was locked.
            "cannot lock" in chain || "lock file" in chain ->
                "This project was locked by another copy of the app, so nothing was " +
                    "changed. Close the app completely and try again."

            // "not authorized", "Authentication is required but no
            // CredentialsProvider has been registered", a 401 or a 403 on push. The
            // numbers are matched as words for the same reason: a status code is only a
            // status code when something says so.
            listOf(
                "not authorized", "authentication is required", "authentication failed",
                "invalid credentials", "permission denied", "401 unauthorized",
                "403 forbidden", "unauthorized (401", "forbidden (403",
            ).any { it in chain } ->
                "GitHub did not accept this phone's key. Sign in again and try once more."

            listOf(
                "unknownhost", "unable to resolve host", "connection", "timed out",
                "network is unreachable", "ssl", "broken pipe", "handshake",
            ).any { it in chain } ->
                "Could not reach GitHub. The phone may be offline — nothing was changed here."

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
     * The username a remote URL names, or null when it names none.
     *
     * **`https://x-token-auth@bitbucket.org/team/repo.git` is how git itself says which username a
     * token belongs to**, and it is the only portable place to say it: a host that wants a name other
     * than the token's own is a fact about *that remote*, not a table of providers this app keeps.
     * Adding it is one edit to the project's address; teaching the app would be a compile.
     *
     * A `user:password@` form answers the user, because the password half is the token and it belongs
     * in the vault rather than in a URL.
     */
    fun userOf(url: String): String? {
        val found = URL_USER_INFO.find(url) ?: return null
        val user = found.groupValues.getOrNull(2) ?: return null
        return user.substringBefore(':').takeIf { it.isNotBlank() }
    }

    /**
     * True when [url] points at GitHub.
     *
     * **This is now a question about GitHub's API, not about where a key may go.** A key goes to
     * whatever host its account is tied to, which `GitCredentials.isAccountHost` answers; this stays
     * for the places that really are calling `api.github.com` and will not work anywhere else.
     */
    fun isGitHubHost(url: String): Boolean = isHost(url, GITHUB_HOST)

    /** True when [url] points at [host] — the same host or one of its subdomains. */
    fun isHost(url: String, host: String): Boolean {
        val authority = hostOf(url)
        if (host.isBlank() || authority.isEmpty()) return false
        return authority == host || authority.endsWith(".$host")
    }

    /**
     * Why this app cannot speak to [url], or null when it can.
     *
     * **Asked before a credential is chosen, not after.** This app packages no SSH transport
     * (`org.eclipse.jgit.ssh.*` is not a dependency), so a `git@host:path` address fails deep inside
     * JGit with a null-shaped error that names nothing. Worse, `hostOf("git@github.com:o/r.git")`
     * answers `github.com` quite happily — so the address would *look* usable right up to the
     * moment a key was attached to it.
     *
     * The scp form is the one that matters: it is what every host's own page shows first, so a person
     * pasting a clone address will paste that one.
     */
    fun transportProblem(url: String): String? {
        val address = url.trim()
        if (address.isEmpty()) return "There is no address."
        if (SCP_FORM.matches(address) || address.startsWith("ssh://", ignoreCase = true) ||
            address.startsWith("git+ssh://", ignoreCase = true)
        ) {
            val host = hostOf(address).ifBlank { "the server" }
            return "This app sends over HTTPS only, so it cannot use an SSH address. " +
                "Use https://$host/<owner>/<repository>.git instead."
        }
        return null
    }

    /**
     * The host of [url], lowercased and stripped of credentials, port and path.
     *
     * **Extracted so that "which host is this" is answered once.** [isGitHubHost] and the clone
     * refusal that has to name the host it will not send a key to were spelling the same six
     * chained calls separately, and a rule with two spellings is a rule that disagrees with itself
     * eventually.
     *
     * **A URL with no authority has no host, and that is not the same as having one called
     * `file`.** `file:///tmp/x` is the case that taught this: taking the first segment after the
     * scheme answered `file`, which reads as a hostname and is not one — and a `file:` clone, which
     * has nowhere to send a key, was refused as though it were somebody else's server. So the
     * scheme is removed **with its `//`**, and what follows is the authority only when it is not
     * itself empty or a path.
     *
     * Empty is the answer for a bare path and for a scheme with no authority, and a caller that
     * needs a host has to handle it rather than being handed a guess.
     */
    fun hostOf(url: String): String {
        val stripped = stripCredentials(url.trim())
        // The scheme, if there is one, and the `//` that may or may not follow it.
        val afterScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").find(stripped)
            ?.let { stripped.substring(it.range.last + 1) }
            ?: stripped
        val authority = afterScheme.removePrefix("//").substringBefore('/')
        // **`file:///tmp` has an empty authority**, which is the case this whole branch exists for.
        if (authority.isEmpty()) return ""
        return authority.substringAfterLast('@').substringBefore(':').trim().lowercase()
    }

    /**
     * Why [text] is not a hostname, or null when it is one.
     *
     * **A person will paste a URL into this field**, because every host's page offers a clone address
     * and that is the thing on the clipboard. Refusing it with the host it *does* contain is the
     * actionable answer: the address belongs on a project, and the host belongs here.
     */
    fun hostProblem(text: String): String? {
        val host = text.trim()
        if (host.isEmpty()) return "A host is needed: the name the key is for, like ${GITHUB_HOST}"
        if (host.any { it.isWhitespace() }) return "A host has no spaces in it"
        if (host.contains("://") || host.contains('/') || host.startsWith("git@")) {
            val found = hostOf(host)
            return if (found.isNotEmpty() && found != host) {
                "The host is `$found`. The whole address goes on a project, not here."
            } else {
                "That is an address, not a host. A host is just the name, like ${GITHUB_HOST}"
            }
        }
        // **No scheme, no path, no port, no credentials** — all of which [hostOf] would strip, and a
        // field that silently accepts `gitlab.com:8443` while storing `gitlab.com` is a field that
        // changes what was typed. **Case is not one of those things**: a hostname is
        // case-insensitive, so `GitLab.com` is the same host and is folded rather than refused.
        val folded = host.lowercase()
        if (folded != hostOf("https://$folded/")) {
            return "A host is a name only, like ${GITHUB_HOST} — no port, and no user"
        }
        if (!HOSTNAME.matches(folded)) return "`$host` is not a hostname"
        return null
    }

    /**
     * A hostname: labels of letters, digits and hyphens, at least one dot.
     *
     * **A dot is required, and that is deliberate.** `localhost` is a real host and a real thing to
     * type, but it is also what a half-finished sentence looks like, and this field decides which
     * server a key may be sent to — so an entry with no dot is far more likely to be a mistake than a
     * local server. A self-hosted install on a name without a dot is reached by putting one in DNS.
     */
    private val HOSTNAME = Regex("""^(?=.{1,253}$)[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$""")

    /**
     * `scheme://userinfo@` — two groups, because the part that must not survive is also the part a
     * token may belong to.
     */
    private val URL_USER_INFO = Regex("""([a-zA-Z][a-zA-Z0-9+.-]*://)([^/@\s]+)@""")

    /**
     * The token shapes that travel without a URL: GitHub's, and OpenRouter's.
     *
     * The model key is a second secret under the same rule as the first: an OpenRouter key in a
     * trace line or a chat message is a key somebody can spend. It is matched here, once, rather
     * than in each place that builds a string — those are the places the mistake happens.
     */
    private val TOKEN_SHAPE = Regex(
        """(?:gh[pousr]_[A-Za-z0-9]{16,}|github_pat_[A-Za-z0-9_]{16,}|sk-or-v1-[A-Za-z0-9]{16,})"""
    )

    /**
     * The scp-shaped address: `git@host:path`.
     *
     * **`user@host:path` and nothing else.** A `https://host/path` address also contains an `@` in
     * some forms and a `:` in others, so the three parts are pinned: no scheme, a user, an `@`, a
     * host without a slash, a colon, and a path. Without the "no scheme" part, `https://a@b:c` would
     * match, and that is a URL this app can send a key to.
     */
    private val SCP_FORM = Regex("""^[^/@:\s]+@[^/:\s]+:[^\s]+$""")
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
