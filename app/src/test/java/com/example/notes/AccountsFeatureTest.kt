package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * §5 of the design: **accounts — Add, Switch, Remove.**
 *
 * An account is a folder, and that folder is the root the app shows. Signing in
 * asks GitHub who the key belongs to; removing an account asks what should happen
 * to the files on this phone, and never touches the repository.
 */
class AccountsFeatureTest : FeatureTest() {

    /** This feature is about making accounts, so it starts with none. */
    override val signIn: Boolean get() = false

    @Test
    fun `a key GitHub refuses is reported, and no account is kept`() {
        // The half of signing in that used to be untestable, and therefore was never
        // run: `identity` always said yes. A refused key must say so out loud — the
        // dialog keeps itself open and shows the reason — and must not leave an
        // account behind that cannot reach anything.
        val phone = Scenario(
            RuntimeEnvironment.getApplication(),
            signIn = false,
            signInGitHub = { RefusingGitHub() },
        )
        try {
            var answered: Boolean? = null
            var sawMessage: String? = null

            phone.viewModel.signInWithToken("a-key-github-does-not-like") { ok ->
                answered = ok
                sawMessage = phone.viewModel.message
            }
            phone.await("the answer to arrive") { answered != null }

            assertEquals("the key was refused", false, answered)
            assertEquals("and it said so", "Bad credentials", sawMessage)
            // **The provisional account, not an absence.** A refused key leaves the app where it
            // started: working as ~local, with its own real folder. The claim is that the REFUSED
            // login was not kept - not that there is no account at all.
            assertNotEquals("the refused login was not kept", "a-key-github-does-not-like", phone.viewModel.activeLogin)
            assertTrue("still working locally", phone.viewModel.isProvisionalAccount)
        } finally {
            phone.close()
        }
    }

    @Test
    fun `with nobody signed in the app works as the provisional account`() {
        // **This test used to assert the opposite**, and the reversal is the whole feature: before
        // a sign-in the app now has a real workspace - `~local` - so local projects, the system
        // project and the request history all work with no account at all.
        //
        // What is still true is the thing that mattered: nothing of a *GitHub account's* is here,
        // and nothing reaches the network.
        assertTrue("there is an account", scene.viewModel.account != null)
        assertTrue("and it is the provisional one", scene.viewModel.isProvisionalAccount)
        assertTrue("with a real workspace", scene.viewModel.workspace != null)
        assertFalse("but nobody is signed in to GitHub", scene.viewModel.signedIn)
        assertNull("so there is no GitHub to talk to", scene.viewModel.github)
        // Nothing of the person's yet: the system project is all a fresh provisional account has.
        assertTrue(
            "nothing of the person's is listed",
            scene.names().all { Workspace.isSystemLocked(it) || it == Workspace.SYSTEM_PROJECT },
        )
    }

    @Test
    fun `the test account is a real account, with its own folder and identity`() {
        scene.signedIn()

        val account = scene.viewModel.account!!
        assertTrue("it is the test account", account.dev)
        assertEquals("its own login", "test-account", account.login)
        assertTrue(
            "its folder exists",
            scene.viewModel.accounts.dirOf(account.login).isDirectory,
        )
        assertEquals("its own identity", "test-account@example.com", account.commitEmail)
    }

    @Test
    fun `a test account's GitHub is a folder on this phone, not the internet`() {
        scene.signedIn()

        val github = scene.viewModel.github!!
        assertTrue("it is the disk one", github is DiskGitHub)
        assertTrue(
            "its repositories live inside the account's folder",
            (github as DiskGitHub).repoDir("anything").absolutePath
                .startsWith(scene.viewModel.accounts.dirOf(scene.login).absolutePath),
        )
    }

    @Test
    fun `signing in gives the account its own folder, and the list starts empty`() {
        // **The account root is the projects list**, so signing in shows an empty list rather than
        // a `projects/` folder to walk into. There is nothing between a person and their work.
        scene.signedIn()

        assertTrue("the account's folder is there", scene.workspace!!.accountDir.isDirectory)
        // **Two rows, and neither is the person's project.** A brand-new account has the settings
        // project, because that is where the system prompt is written and a prompt with nowhere to
        // live is one nobody can edit — and `.muffy/`, the app's own flags, which report 28 asked to
        // stop hiding. Folders sort by lowercase name, so the dot comes first.
        assertEquals("and no project of their own yet", listOf(".muffy", "system"), scene.names())
    }

    @Test
    fun `switching accounts shows the other account's tree`() {
        scene.signedIn()
        scene.folder("only_in_the_first")
        val first = File(scene.workspace!!.accountDir, "only_in_the_first")

        scene.viewModel.signIn("second", "a-key", "Second", "second@example.com")
        assertTrue("the second account is active", scene.viewModel.activeLogin == "second")
        assertFalse("the first account's file is not shown", scene.names().contains("only_in_the_first"))
        assertTrue("but it is still on the phone", first.isDirectory)

        scene.viewModel.switchTo("test-account")
        assertTrue("switching back shows it again", scene.names().contains("only_in_the_first"))
    }

    @Test
    fun `removing an account keeping the copies leaves every file on the phone`() {
        scene.signedIn()
        scene.folder("keep_me")
        val kept = File(scene.workspace!!.accountDir, "keep_me")

        scene.viewModel.removeAccount(scene.login, deleteCopies = false)

        // **The provisional account, not nothing.** Removing the last real account leaves `~local`
        // rather than an absence, so the screen always has a workspace to be in.
        assertTrue("back to working locally", scene.viewModel.isProvisionalAccount)
        assertFalse("the account is no longer known", scene.viewModel.known(scene.login))
        assertFalse(
            "and it is not something to switch to",
            scene.viewModel.accountList.any { it.login == scene.login },
        )
        assertTrue("the files stayed", kept.isDirectory)
        assertTrue("and so did the folder that held them", kept.parentFile!!.isDirectory)
    }

    @Test
    fun `removing an account and its copies leaves nothing of it on the phone`() {
        scene.signedIn()
        scene.folder("go_away")
        val gone = File(scene.workspace!!.accountDir, "go_away")
        // A save is still on its way to the worker when the account is removed.
        scene.viewModel.saveEditor("anything")

        scene.viewModel.removeAccount(scene.login, deleteCopies = true)
        scene.await("the account's folder to be gone") {
            !scene.viewModel.accounts.dirOf(scene.login).exists()
        }

        assertFalse("the folder is gone", gone.exists())
        assertFalse(
            "and so is everything the account owned",
            scene.viewModel.accounts.dirOf(scene.login).exists(),
        )
    }

    @Test
    fun `removing one account leaves the others exactly as they were`() {
        scene.signedIn()
        scene.viewModel.refresh()
        scene.viewModel.signIn("second", "a-key", "Second", "second@example.com")
        scene.folder("second_folder")
        val second = File(scene.viewModel.workspace!!.accountDir, "second_folder")

        scene.viewModel.removeAccount("second", deleteCopies = false)

        assertTrue("the other account is still known", scene.viewModel.known(scene.login))
        assertTrue("and its files are untouched", second.isDirectory)
        assertEquals(
            "the app fell back to the account that is left",
            scene.login,
            scene.viewModel.activeLogin,
        )
    }

    @Test
    fun `signing in asks GitHub who you are, and commits as that`() {
        // Name and email are not typed any more: they come back from the key's own
        // account, so a commit is written as the person whose account it is and
        // there is nothing to keep in step with GitHub by hand.
        // A GitHub that answers, rather than the network: this asserts what the app
        // does with the answer, not that github.com is reachable from a test.
        val phone = Scenario(
            RuntimeEnvironment.getApplication(),
            signIn = false,
            signInGitHub = { DiskGitHub(File(scene.root, "gh"), "muffy") },
        )
        try {
            var answered: Boolean? = null
            phone.viewModel.signInWithToken("a-key") { answered = it }
            phone.await("the answer") { answered != null }

            assertEquals("signed in", true, answered)
            val author = phone.viewModel.account!!.author()
            assertEquals("the account's own name", "Test Account", author.name)
            assertEquals("and its own email", "muffy@example.com", author.email)
        } finally {
            phone.close()
        }
    }

    @Test
    fun `an account with no identity still writes commits under its login`() {
        scene.viewModel.signIn("bare", "a-key", "", "")

        val author = scene.viewModel.account!!.author()
        assertEquals("bare", author.name)
        assertEquals("bare@users.noreply.github.com", author.email)
    }

    @Test
    fun `the account on disk outlives the app being started again`() {
        scene.signedIn()

        val reopened = AppViewModel(
            scene.app, rootDir = scene.root, credentials = scene.credentials,
            settings = scene.settings, saveDebounceMs = 0,
        )

        assertEquals("test-account", reopened.account?.login)
        assertEquals("Test Account", reopened.account?.commitName)
        assertTrue("it is still the test account", reopened.account!!.dev)
    }
}

/**
 * A GitHub that refuses every key.
 *
 * The other GitHub in these tests, [DiskGitHub], is a folder that accepts whatever
 * it is given — which made the refusal path unreachable and therefore unrun. This
 * one exists only to say no, the way GitHub does to an expired or mistyped key.
 */
private class RefusingGitHub : GitHub {
    override fun identity(token: String): GitHubResult<Identity> =
        GitHubResult(error = "Bad credentials", status = 401)

    override fun listRepos(token: String): GitHubResult<List<Repo>> =
        GitHubResult(error = "Bad credentials", status = 401)

    override fun licences(token: String): GitHubResult<List<Licence>> =
        GitHubResult(error = "Bad credentials", status = 401)

    override fun createRepo(token: String, name: String, licence: String, description: String):
        GitHubResult<Repo> = GitHubResult(error = "Bad credentials", status = 401)

    override fun renameRepo(token: String, owner: String, name: String, newName: String):
        GitHubResult<Repo> = GitHubResult(error = "Bad credentials", status = 401)

    override fun deleteRepo(token: String, owner: String, name: String): GitHubResult<Unit> =
        GitHubResult(error = "Bad credentials", status = 401)

    override fun cloneUrlFor(owner: String, name: String): String =
        "https://github.com/$owner/$name.git"

    override fun webUrl(cloneUrl: String): String = cloneUrl

    override fun owns(remoteUrl: String): Boolean = true
}
