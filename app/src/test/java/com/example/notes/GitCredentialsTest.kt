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

    @Test
    fun `the account's own host, with a key, uses it`() {
        val answer = GitCredentials.forRemote("https://github.com/o/r.git", accountToken()) { null }

        assertEquals(GitCredentials.Answer.Use(accountToken()), answer)
    }

    @Test
    fun `the account's own host with no key says to sign in, not to add a secret`() {
        // **Two different failures, and they are fixed in two different places.** No token for the host
        // this phone signed in to is a sign-in problem; no token for somebody else's host is a secret to
        // add. Collapsing them sends the person to the wrong screen, which is the failure shape this app
        // treats as a defect everywhere else.
        val answer = GitCredentials.forRemote("https://github.com/o/r.git", null) { null }

        assertTrue("it is a sentence: $answer", answer is GitCredentials.Answer.Say)
        assertTrue(
            "and it names the fix: $answer",
            (answer as GitCredentials.Answer.Say).why.contains("Sign in"),
        )
    }

    @Test
    fun `another host with a secret of its own uses that secret`() {
        // **The whole point of the phase**: nothing in the app knows that `gitlab.com` exists. A person
        // stored a secret for that host and named it `git`, and the project picks it up.
        val answer = GitCredentials.forRemote("https://gitlab.com/someone/r.git", null) { host ->
            if (host == "gitlab.com") "glpat-the-project-key" else null
        }

        assertEquals(GitCredentials.Answer.Use("glpat-the-project-key"), answer)
    }

    @Test
    fun `another host with no secret says which host and which secret`() {
        val answer = GitCredentials.forRemote(
            "https://git.example.org/team/r.git",
            accountToken(),
        ) { null }

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
        val answer = GitCredentials.forRemote("https://evil.example/o/r.git", accountToken()) { null }

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
        val answer = GitCredentials.forRemote("https://github.com/o/r.git", accountToken()) { host ->
            if (host == "github.com") "ghp_the-project-key" else null
        }

        assertEquals(GitCredentials.Answer.Use("ghp_the-project-key"), answer)
    }

    @Test
    fun `a remote with no host gets nothing, because it has nowhere to send a key`() {
        // A bare repository beside the project is how a folder-to-folder clone works, and how most of the
        // suite talks to a "remote". Refusing it would break the one case that is obviously safe.
        assertEquals(
            GitCredentials.Answer.None,
            GitCredentials.forRemote("/tmp/some/repo", accountToken()) { null },
        )
        assertEquals(
            "and an address that is not there is the same answer",
            GitCredentials.Answer.None,
            GitCredentials.forRemote(null, accountToken()) { null },
        )
        assertEquals(
            "and an empty one",
            GitCredentials.Answer.None,
            GitCredentials.forRemote("  ", accountToken()) { null },
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
