package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **What a remote authenticates with** — the decision alone, with no device and no network.
 *
 * It is a separate file because it is a rule rather than a mechanism (the same reason [TokenScopes] has
 * one): the app's accounts are one host's, every other host is a secret, and the moment that sentence is
 * wrong the app either refuses a project it could have reached or offers a key to a server that should
 * not have it.
 */
class GitCredentialsTest {

    private fun accountToken() = "ghp_the-account-key"

    /**
     * The decision, with GitHub's host unless a test says otherwise.
     *
     * **Every test below is about the same question — what does this remote get — so only the ones that
     * are *about* the host mention it.** The default is what the app had before an account could be made
     * against another host, which makes the tests that do not mention it the regression suite for it.
     */
    private fun forRemote(
        remoteUrl: String?,
        accountToken: String?,
        accountHost: String = GITHUB_HOST,
        vault: (host: String) -> String? = { null },
        connection: (host: String) -> Connection? = { null },
    ): GitCredentials.Answer =
        GitCredentials.forRemote(remoteUrl, accountToken, accountHost, vault, connection)

    @Test
    fun `the account's own host, with a key, uses it`() {
        val answer = forRemote("https://github.com/o/r.git", accountToken())

        assertEquals(GitCredentials.Answer.Use(accountToken()), answer)
    }

    @Test
    fun `the account's own host with no key says to sign in, not to add a secret`() {
        // **Two different failures, and they are fixed in two different places.** No token for the host
        // this phone signed in to is a sign-in problem; no token for somebody else's host is a secret to
        // add. Collapsing them sends the person to the wrong screen, which is the failure shape this app
        // treats as a defect everywhere else.
        val answer = forRemote("https://github.com/o/r.git", null)

        assertTrue("it is a sentence: $answer", answer is GitCredentials.Answer.Say)
        assertTrue(
            "and it names the fix: $answer",
            (answer as GitCredentials.Answer.Say).why.contains("Sign in"),
        )
    }

    @Test
    fun `an account on another host does not offer its key to GitHub`() {
        // **The falsifying test for `Account.host`.** Before the host was data this answered `Use`: the
        // decision read "is this address GitHub's", which was true, and the account's own host — the whole
        // point of the account — was never consulted. A person with a GitLab key who adds a GitHub project
        // would then have had their GitLab token sent to GitHub, and GitHub's 401 would have been the only
        // explanation.
        val answer = forRemote(
            "https://github.com/o/r.git",
            "glpat-the-gitlab-key",
            accountHost = "gitlab.com",
        )

        assertTrue("nothing is sent: $answer", answer is GitCredentials.Answer.Say)
        val why = (answer as GitCredentials.Answer.Say).why
        assertTrue("it names the host it will not send to: $why", why.contains("github.com"))
        assertFalse("and never the key", why.contains("glpat-the-gitlab-key"))
    }

    @Test
    fun `an account on another host offers its key to that host`() {
        // The other half, and the one that makes the app worth using on another host: the same account,
        // the same key, and an address that *is* its own host — no vault secret needed, because the
        // account is the credential for the host it was made against.
        val answer = forRemote(
            "https://gitlab.com/someone/r.git",
            "glpat-the-gitlab-key",
            accountHost = "gitlab.com",
        )

        assertEquals(GitCredentials.Answer.Use("glpat-the-gitlab-key"), answer)
    }

    @Test
    fun `a subdomain of the account's host is the account's host`() {
        // Enterprise installs answer on `github.example.com` and `gitlab.example.org`, and a hostname is
        // written into the account by hand — so the comparison is the same one `isGitHubHost` always did,
        // with the host as an argument rather than a constant.
        assertEquals(
            GitCredentials.Answer.Use(accountToken()),
            forRemote("https://github.example.com/o/r.git", accountToken(), "example.com"),
        )
        assertTrue(
            "and a suffix that is not a subdomain is not the host",
            forRemote("https://notexample.com/o/r.git", accountToken(), "example.com")
                is GitCredentials.Answer.Say,
        )
    }

    @Test
    fun `an account with no host recorded offers its key nowhere`() {
        // Not reachable through the app — `Account.host` defaults to GitHub's — but the decision must not
        // treat "unknown" as "anywhere", which is what an empty string would do if it were compared as a
        // prefix. This is the shape of the mistake, refused.
        assertTrue(
            forRemote("https://github.com/o/r.git", accountToken(), accountHost = "")
                is GitCredentials.Answer.Say,
        )
    }

    @Test
    fun `another host with a secret of its own uses that secret`() {
        // **The whole point of the phase**: nothing in the app knows that `gitlab.com` exists. A person
        // stored a secret for that host and named it `git`, and the project picks it up.
        val answer = forRemote("https://gitlab.com/someone/r.git", null, vault = { host ->
            if (host == "gitlab.com") "glpat-the-project-key" else null
        })

        assertEquals(GitCredentials.Answer.Use("glpat-the-project-key"), answer)
    }

    @Test
    fun `a connection supplies the username with the token`() {
        // **The pair the scoped vault exists to keep together.** A token presented under the wrong name
        // is a different identity — `gitlab.com` with a personal token wants `oauth2`, and the account's
        // name would be somebody else's — so `Use` carries the two, and null means "the account's own".
        val answer = forRemote(
            "https://gitlab.com/someone/r.git",
            accountToken(),
            connection = { host ->
                if (host == "gitlab.com") {
                    Connection(address = "gitlab.com", secret = "glpat-scoped", user = "oauth2")
                } else {
                    null
                }
            },
        )

        assertEquals(GitCredentials.Answer.Use("glpat-scoped", "oauth2"), answer)
    }

    @Test
    fun `a connection is asked before a secret of the same name`() {
        // **Two doors onto one host, and the newer one answers first.** Vault B is what a person sets in
        // a row's ⋮; the `git`-named secret is the older way to reach a host this app knows nothing
        // about. Both are kept — a credential somebody stored must not stop working — and when both
        // exist the one that was set at the narrower scope wins, because that is what the scopes mean.
        val answer = forRemote(
            "https://gitlab.com/someone/r.git",
            accountToken(),
            vault = { "glpat-the-named-secret" },
            connection = { Connection(address = "gitlab.com", secret = "glpat-the-connection") },
        )

        assertEquals(GitCredentials.Answer.Use("glpat-the-connection", ""), answer)
    }

    @Test
    fun `a blank connection is no credential, so the next door is tried`() {
        // **A connection with no secret is a row somebody emptied**, and an empty token sent as a bearer
        // is a 401 that explains nothing. The account's own key is what a remote gets instead — the same
        // answer it would have got if the row had never been made.
        val answer = forRemote(
            "https://github.com/o/r.git",
            accountToken(),
            connection = { Connection(address = "github.com", secret = "") },
        )

        assertEquals(GitCredentials.Answer.Use(accountToken()), answer)
    }

    @Test
    fun `a connection never answers for a host it was not asked about`() {
        // **The store decides which connection is offered, and this is the decision refusing to invent
        // one.** A lambda that answered for every host would be a vault with no host binding at all.
        val answer = forRemote(
            "https://github.com/o/r.git",
            null,
            connection = { host -> if (host == "gitlab.com") Connection("gitlab.com", "glpat") else null },
        )

        assertTrue("nothing is sent: $answer", answer is GitCredentials.Answer.Say)
    }

    @Test
    fun `another host with no secret says which host and which secret`() {
        val answer = forRemote("https://git.example.org/team/r.git", accountToken())

        assertTrue("it is a sentence: $answer", answer is GitCredentials.Answer.Say)
        val why = (answer as GitCredentials.Answer.Say).why
        assertTrue("it names the host: $why", why.contains("git.example.org"))
        assertTrue("and the secret to add: $why", why.contains(GitCredentials.SECRET))
    }

    @Test
    fun `the account's key is never offered to another host`() {
        // **The rule that has been in this app since the first clone**, and the one a portable design must
        // not quietly lose: the key on this phone is the account's, and a repository that points at
        // another server does not get it. Here it is asserted as the *decision*, so the answer cannot be
        // `Use` however the caller is written.
        val answer = forRemote("https://evil.example/o/r.git", accountToken())

        assertTrue("nothing is sent: $answer", answer !is GitCredentials.Answer.Use)
        assertFalse(
            "and the key does not appear in the sentence either",
            (answer as GitCredentials.Answer.Say).why.contains(accountToken()),
        )
    }

    @Test
    fun `a project's own secret wins over the account's on the account's own host`() {
        // A person with two GitHub identities: the project's secret is the narrower scope and the account
        // token is the wider one, so the narrow one wins. This is the same rule the store already follows
        // (project, then account, then shared) applied to the credential.
        val answer = forRemote("https://github.com/o/r.git", accountToken(), vault = { host ->
            if (host == "github.com") "ghp_the-project-key" else null
        })

        assertEquals(GitCredentials.Answer.Use("ghp_the-project-key"), answer)
    }

    @Test
    fun `a remote with no host gets nothing, because it has nowhere to send a key`() {
        // A bare repository beside the project is how a folder-to-folder clone works, and how most of the
        // suite talks to a "remote". Refusing it would break the one case that is obviously safe.
        assertEquals(
            GitCredentials.Answer.None,
            forRemote("/tmp/some/repo", accountToken()),
        )
        assertEquals(
            "and an address that is not there is the same answer",
            GitCredentials.Answer.None,
            forRemote(null, accountToken()),
        )
        assertEquals(
            "and an empty one",
            GitCredentials.Answer.None,
            forRemote("  ", accountToken()),
        )
    }

    @Test
    fun `the username a remote names is read from its address`() {
        // **`x-token-auth@` is git's own way of saying which username a token belongs to**, and it is the
        // portable alternative to a table of hosts in this app: a host that wants a name other than the
        // token's own is a fact about *that remote*, and adding it is an edit to one project's address.
        assertEquals(
            "the name from the address",
            "x-token-auth",
            Secrets.userOf("https://x-token-auth@bitbucket.org/team/r.git"),
        )
        assertEquals(
            "a user and a password answers the user, because the password is the token",
            "someone",
            Secrets.userOf("https://someone:hunter2@example.org/r.git"),
        )
        assertNull(
            "an address with no user names none",
            Secrets.userOf("https://github.com/o/r.git"),
        )
        assertNull("and neither does a local path", Secrets.userOf("/tmp/repo"))
    }

    @Test
    fun `the username is not left in the address, so it cannot reach a message`() {
        // The half that was already true and must stay true: the userinfo is stripped wherever a URL is
        // shown. The capture has two groups now, so this is the test that would fail if the replacement
        // ever pasted the whole match back.
        assertEquals(
            "https://bitbucket.org/team/r.git",
            Secrets.stripCredentials("https://x-token-auth@bitbucket.org/team/r.git"),
        )
    }
}
