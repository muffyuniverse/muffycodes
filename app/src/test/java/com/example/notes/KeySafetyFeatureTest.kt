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

        // **No account at all.** A release build may not make a TEST account, and there is no
        // account an app can ship (§3.11) — so the phone is exactly as it was: empty, with the
        // sentence saying why.
        assertNull("no account was made", releaseLike.activeLogin)
        assertTrue(
            "and no test account was written",
            releaseLike.accounts.accounts().none { it.dev },
        )
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

        val who = releaseLike.whoFor(stored.login)

        assertTrue("the build asks GitHub, not a folder", who is HttpWho)
    }

    @Test
    fun `the app is allowed to reach the network at all`() {
        // The tests above prove the app *would* talk to GitHub rather than to a
        // folder. None of them could catch the app not being *permitted* to: the
        // folder-backed GitHub never opens a socket, so every test passed while the
        // shipped APK had no INTERNET permission and could not sign in, list
        // repositories, clone or push. Signing in simply did nothing, because the
        // SecurityException became an error message nobody had asked the UI to show.
        //
        // Read from the manifest that actually ships, not from a constant that could
        // drift from it.
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(
            "the manifest declares INTERNET, so git can reach GitHub",
            manifest.contains("android.permission.INTERNET"),
        )
    }

    @Test
    fun `a signed-in account with a key talks to GitHub`() {
        scene.viewModel.signIn("muffyuniverse", "a-key", "Muffy World", "muffy@example.com")

        assertTrue(scene.viewModel.whoFor("muffyuniverse") is HttpWho)
    }

    @Test
    fun `no address is ever composed from an account any more`() {
        // **This test used to assert that `cloneUrlFor` put no key in the address.** The function that
        // composed one is gone: an address is what a person pasted from their host's page, and the app
        // has no convention for spelling one. So the fact worth asserting is the absence — a composed
        // address would be a host convention the app had guessed, which is exactly what the cut removed.
        scene.viewModel.signIn("muffyuniverse", "ghp_secret_value", "Muffy World", "muffy@example.com")

        val who = scene.viewModel.whoFor("muffyuniverse")
        assertTrue("the door is only asked who a key is", who is HttpWho)
        assertNull("and it has no way to spell an address", scene.viewModel.remoteUrlOf("Shopping"))
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
