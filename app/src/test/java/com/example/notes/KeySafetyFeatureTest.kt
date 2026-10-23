package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The promises that are about the key, restated as behaviour.
 *
 * The rule is one sentence: **the key is never in an address, never in a file that
 * is shown, and never anywhere a release build can point at a folder instead of
 * GitHub.** These are the checks that keep it true while the test account exists,
 * because a test account is the one feature that could quietly undo it.
 */
class KeySafetyFeatureTest : FeatureTest() {

    /** A phone with nobody signed in, and a build that may keep a test account. */
    override val signIn: Boolean get() = false

    @Test
    fun `a build that may not keep a test account cannot make one`() {
        val releaseLike = AppViewModel(
            scene.app,
            rootDir = scene.root,
            credentials = scene.credentials,
            settings = scene.settings,
            saveDebounceMs = 0,
            testAccountsAllowed = false,
        )

        releaseLike.signInAsTestAccount()

        assertNull("nobody is signed in", releaseLike.activeLogin)
        assertTrue("no account was written", releaseLike.accounts.accounts().isEmpty())
        assertEquals(
            "and the app says why",
            "Only a test build can do that",
            releaseLike.message,
        )
    }

    @Test
    fun `a stored test account is not treated as one by a build that may not keep it`() {
        scene.signedIn()
        val stored = scene.viewModel.account!!
        assertTrue("the account says it is a test account", stored.dev)

        val releaseLike = AppViewModel(
            scene.app,
            rootDir = scene.root,
            credentials = scene.credentials,
            settings = scene.settings,
            saveDebounceMs = 0,
            testAccountsAllowed = false,
        )

        val github = releaseLike.githubFor(stored.login)

        assertTrue("the build talks to GitHub, not to a folder", github is HttpGitHub)
    }

    @Test
    fun `a signed-in account with a key talks to GitHub`() {
        scene.viewModel.signIn("muffyuniverse", "a-key", "Muffy World", "muffy@example.com")

        assertTrue(scene.viewModel.githubFor("muffyuniverse") is HttpGitHub)
    }

    @Test
    fun `the key never reaches the address a repository is cloned from`() {
        scene.viewModel.signIn("muffyuniverse", "ghp_secret_value", "Muffy World", "muffy@example.com")

        val url = scene.viewModel.githubFor("muffyuniverse")!!.cloneUrlFor("muffyuniverse", "Shopping")

        assertFalse("the key is not in the address", url.contains("ghp_"))
        assertFalse("and nothing that looks like a credential is", url.contains("@"))
    }

    @Test
    fun `a project's stored remote never holds the key`() {
        scene.signedIn()
        scene.project("Shopping")

        val config = File(scene.workspace!!.repoOf("Shopping"), ".git/config").readText()

        assertFalse("no key in the repository's config", config.contains("ghp_"))
        assertTrue("the address is the plain one", config.contains("url = "))
    }

    @Test
    fun `a key written into an address by hand is scrubbed out of what is shown`() {
        val noisy = "https://ghp_secret_value@github.com/owner/name.git"

        val shown = Secrets.scrub(Secrets.stripCredentials(noisy))

        assertFalse("the key is gone", shown.contains("ghp_secret_value"))
        assertTrue("the address is still readable", shown.contains("github.com"))
    }

    @Test
    fun `the app only attaches a key to GitHub itself`() {
        assertTrue(Secrets.isGitHubHost("https://api.github.com/user"))
        assertTrue(Secrets.isGitHubHost("https://github.com/owner/name.git"))
        assertFalse("a look-alike host", Secrets.isGitHubHost("https://api.github.com.evil.example/"))
        assertFalse("a folder", Secrets.isGitHubHost("file:///sdcard/repo.git"))
        assertFalse("another host", Secrets.isGitHubHost("https://example.com/owner/name.git"))
    }

    @Test
    fun `a test account holds no key at all`() {
        scene.signedIn()

        assertFalse(
            "no key is stored for it",
            scene.credentials.hasToken(scene.login),
        )
        assertNull(
            "and nothing is stored under its name",
            scene.viewModel.tokenOf(scene.login)?.takeIf { it.isNotEmpty() },
        )
    }

    @Test
    fun `a test account's repositories are inside its own folder, and go when it goes`() {
        scene.signedIn()
        scene.project("Shopping")
        val repo = scene.remote("Shopping")
        assertTrue("the repository was made", repo.isDirectory)

        scene.viewModel.removeAccount(scene.login, deleteCopies = true)

        assertFalse("the repository went with the account", repo.exists())
    }
}
