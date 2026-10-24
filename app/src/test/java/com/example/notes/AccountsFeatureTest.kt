package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // run: `whoami` always said yes. A refused key must say so out loud — the
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

            phone.viewModel.signInWithToken("a-key-github-does-not-like", "", "") { ok ->
                answered = ok
                sawMessage = phone.viewModel.message
            }
            phone.await("the answer to arrive") { answered != null }

            assertEquals("the key was refused", false, answered)
            assertEquals("and it said so", "Bad credentials", sawMessage)
            assertNull("no account was kept", phone.viewModel.account)
            assertEquals("nobody is signed in", null, phone.viewModel.activeLogin)
        } finally {
            phone.close()
        }
    }

    @Test
    fun `with nobody signed in there is nothing to show and nothing to make`() {
        assertNull("no account", scene.viewModel.account)
        assertNull("no workspace", scene.viewModel.workspace)
        assertTrue("nothing is listed", scene.names().isEmpty())
        assertFalse("and nothing can be made", scene.viewModel.signedIn)
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
    fun `signing in gives the account its projects folder straight away`() {
        scene.signedIn()

        assertTrue(
            "projects is there",
            File(scene.workspace!!.accountDir, Workspace.PROJECTS).isDirectory,
        )
        assertEquals(listOf("projects"), scene.names())
    }

    @Test
    fun `switching accounts shows the other account's tree`() {
        scene.signedIn()
        scene.folder("only-in-the-first")
        val first = File(scene.workspace!!.accountDir, "only-in-the-first")

        scene.viewModel.signIn("second", "a-key", "Second", "second@example.com")
        assertTrue("the second account is active", scene.viewModel.activeLogin == "second")
        assertFalse("the first account's file is not shown", scene.names().contains("only-in-the-first"))
        assertTrue("but it is still on the phone", first.isDirectory)

        scene.viewModel.switchTo("test-account")
        assertTrue("switching back shows it again", scene.names().contains("only-in-the-first"))
    }

    @Test
    fun `removing an account keeping the copies leaves every file on the phone`() {
        scene.signedIn()
        scene.folder("keep-me")
        val kept = File(scene.workspace!!.accountDir, "keep-me")

        scene.viewModel.removeAccount(scene.login, deleteCopies = false)

        assertNull("nobody is signed in", scene.viewModel.activeLogin)
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
        scene.folder("go-away")
        val gone = File(scene.workspace!!.accountDir, "go-away")
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
        scene.folder("second-folder")
        val second = File(scene.viewModel.workspace!!.accountDir, "second-folder")

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
    fun `an account's identity is what its commits are written with`() {
        scene.signedIn()
        scene.viewModel.updateIdentity("A Writer", "writer@example.com")

        val author = scene.viewModel.account!!.author()
        assertEquals("A Writer", author.name)
        assertEquals("writer@example.com", author.email)
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
        scene.viewModel.updateIdentity("A Writer", "writer@example.com")

        val reopened = AppViewModel(
            scene.app, rootDir = scene.root, credentials = scene.credentials,
            settings = scene.settings, saveDebounceMs = 0,
        )

        assertEquals("test-account", reopened.account?.login)
        assertEquals("A Writer", reopened.account?.commitName)
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
    override fun whoami(token: String): GitHubResult<String> =
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
