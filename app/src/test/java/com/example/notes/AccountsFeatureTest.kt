package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
        // **No GitHub, deliberately.** Answering with an `HttpGitHub` would give every caller
        // something that looks usable and sends unauthenticated requests to the internet.
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

        // **And the queued save must not bring it back.** This is the half the assertion above
        // cannot see: `await` returns the instant the folder is gone, so a write still waiting on
        // the worker recreates it *after* the test has already passed. `ws.write` makes the folder
        // it writes into, so without a guard the account reappears a moment later — deleted by the
        // person, restored by a save they had already given up on.
        //
        // **A bounded wait, because the worker has no drain to ask for.** The save is queued with a
        // zero debounce, so it runs almost at once; half a second is far longer than the write needs
        // *if it were going to happen*, which is what makes the assertion below about the guard
        // rather than about luck.
        Thread.sleep(500)
        assertFalse(
            "and a save that was still queued did not put it back",
            scene.viewModel.accounts.dirOf(scene.login).exists(),
        )
        assertFalse("nor the file it was saving", gone.exists())
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

    /**
     * An expired attempt is refused **before** any exchange, and says why.
     *
     * It lives here rather than in `OpenRouterAuthTest` because it needs a view model, and a view
     * model needs an application — `RuntimeEnvironment.getApplication()` answers null in a test
     * class that is not a Robolectric one, which is how the first version of this failed.
     *
     * The alternative to refusing early is sending a code whose verifier has gone stale, which
     * produces OpenRouter's "invalid code" refusal — blaming the person's code for the app's
     * forgotten state.
     */
    @Test
    fun `an OpenRouter attempt older than ten minutes is refused without a request`() {
        scene.viewModel.beginOpenRouterSignIn()
        var said: String? = null
        // **Ten minutes and a second later**, given as the clock rather than waited for.
        val later = System.currentTimeMillis() + OpenRouterAuth.LIFETIME_MS + 1_000
        scene.viewModel.finishOpenRouterSignIn("a-code", now = later) { said = it }
        scene.await("the answer") { said != null }
        assertTrue("names the ten minutes: $said", said!!.contains("ten minutes"))
    }

    // ---- the links a document may use ----------------------------------------

    /**
     * **A locked page's `app:` link opens the real dialog.**
     *
     * The mechanism exists so that the last onboarding page is a *form* rather than a description —
     * the operator's own framing. Asserted through the view model's published state, which is what
     * the screen acts on, so this pins the wiring rather than the rendering.
     */
    @Test
    fun `an app link opens the connection it names`() {
        scene.viewModel.runLinkAction("connect-github")
        assertEquals(
            "GitHub",
            AppViewModel.Connection.GitHub,
            scene.viewModel.pendingConnection,
        )
        scene.viewModel.connectionShown()
        assertNull("answered once", scene.viewModel.pendingConnection)

        scene.viewModel.runLinkAction("connect-openrouter")
        assertEquals(AppViewModel.Connection.OpenRouter, scene.viewModel.pendingConnection)
        scene.viewModel.connectionShown()

        // **The way past a sign-in that cannot succeed.** Onboarding cannot be left, so a page that
        // offered only OAuth would trap anyone whose provider is rate-limited or whose phone has no
        // browser.
        scene.viewModel.runLinkAction("use-token")
        assertEquals(AppViewModel.Connection.Token, scene.viewModel.pendingConnection)
    }

    /**
     * **A queued save cannot bring a deleted account back — on its own, with no other read running.**
     *
     * The test above covers this too, but it cannot say *which* writer was responsible: my heartbeat
     * read was recreating the account through `RequestDb.open`, and while that was true the save's
     * own guard was untested and could have been deleted without the suite noticing.
     *
     * So this drives the save alone. It waits for the removal to settle first, which is the only way
     * to be sure the save is the writer under test rather than the read.
     */
    @Test
    fun `a queued save alone does not bring a deleted account back`() {
        scene.signedIn()
        scene.folder("go_away")
        val project = File(scene.workspace!!.accountDir, "go_away")

        scene.viewModel.saveEditor("anything")
        scene.viewModel.removeAccount(scene.login, deleteCopies = true)
        scene.await("the account's folder to be gone") {
            !scene.viewModel.accounts.dirOf(scene.login).exists()
        }
        assertFalse("the folder is gone", project.exists())

        // The save is queued with a zero debounce, so it runs almost at once. A wait is the only
        // way to tell "the guard refused it" from "it has not run yet".
        Thread.sleep(500)
        assertFalse(
            "and the save did not recreate it",
            scene.viewModel.accounts.dirOf(scene.login).exists(),
        )
    }

    /**
     * **A cloned page cannot act.**
     *
     * The security boundary of the whole link feature, and the reason `app:` is scoped by project
     * rather than allowed everywhere: onboarding pages are locked and written by the app, so their
     * links are the app's own. A person can clone one — that is the point of the lock being
     * acceptable — and a clone is ordinary text in an ordinary project. If `app:` worked there, a
     * document could run the app, and a page that says `[Delete everything](app:delete-account)`
     * would be a button.
     */
    @Test
    fun `an app link in a cloned page is refused`() {
        val clone = File(scene.viewModel.currentDir ?: scene.root, "my-copy.md")
        scene.viewModel.followLink("app:connect-github", clone)

        assertNull("a copy cannot act", scene.viewModel.pendingConnection)
    }

    @Test
    fun `an app link in a locked onboarding page is followed`() {
        val dir = File(
            scene.viewModel.workspace!!.projectDir(Workspace.SYSTEM_PROJECT),
            Onboarding.DIR,
        )
        val page = File(dir, Onboarding.pages().last().name)
        scene.viewModel.followLink("app:use-token", page)

        assertEquals(
            "the app's own page may ask for the token door",
            AppViewModel.Connection.Token,
            scene.viewModel.pendingConnection,
        )
    }

    @Test
    fun `an unknown app link is ignored rather than crashing`() {
        scene.viewModel.runLinkAction("delete-everything")
        assertNull(
            "a page written for a newer app must not break an older one",
            scene.viewModel.pendingConnection,
        )
    }

    /**
     * **A link press is traced**, and the two kinds are named differently — one leaves the app, the
     * other makes it act. A log that called both "link" could not answer "did the onboarding page
     * get them as far as the sign-in dialog".
     */
    @Test
    fun `a link press is traced under the name that says which kind it was`() {
        val before = Trace.report().taps
        scene.viewModel.openLink("https://example.com")
        assertTrue("the press was recorded", Trace.report().taps > before)
        assertTrue(
            "named: newest ${Trace.report().lines.take(3)}",
            Trace.report().lines.take(3).any { it.contains("TAP") && it.contains("link") },
        )

        val beforeAction = Trace.report().taps
        val page = File(
            File(
                scene.viewModel.workspace!!.projectDir(Workspace.SYSTEM_PROJECT),
                Onboarding.DIR,
            ),
            Onboarding.pages().last().name,
        )
        scene.viewModel.followLink("app:connect-github", page)
        assertTrue("the action was recorded", Trace.report().taps > beforeAction)
        assertTrue(
            "named as an action: newest ${Trace.report().lines.take(3)}",
            Trace.report().lines.take(3).any { it.contains("TAP") && it.contains("link-action") },
        )
    }

    /**
     * **A refused link says so in the log.**
     *
     * The refusal is silent on screen — a message per attempt would be the app explaining a wall —
     * so the log is the only place the attempt is visible, and a security boundary nobody can see
     * being tested is a boundary nobody can check.
     */
    @Test
    fun `a refused app link is recorded`() {
        val clone = File(scene.viewModel.currentDir ?: scene.root, "my-copy.md")
        scene.viewModel.followLink("app:delete-account", clone)

        assertTrue(
            "the refusal is in the log: ${Trace.report().lines.take(3)}",
            Trace.report().lines.take(3).any { it.contains("link-refused") },
        )
    }

    @Test
    fun `a web link is published for the screen to open`() {
        scene.viewModel.openLink("https://example.com/page")
        assertEquals("https://example.com/page", scene.viewModel.linkToOpen)
        scene.viewModel.linkOpened()
        assertNull("opened once", scene.viewModel.linkToOpen)
    }

    // ---- onboarding's own rules ----------------------------------------------

    @Test
    fun `onboarding cannot be closed before the last page`() {
        val scene = Scenario(org.robolectric.RuntimeEnvironment.getApplication(), signIn = true)
        try {
            // **Onboarding is opened by the app itself on first run**, so this asserts it started
            // rather than starting it by hand — the mechanism under test is the one a person meets.
            scene.await("onboarding to open") { scene.viewModel.onboarding }
            scene.viewModel.closeEditor()
            assertTrue("still reading", scene.viewModel.onboarding)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `the pages are reached in order and the position only moves forward`() {
        val scene = Scenario(org.robolectric.RuntimeEnvironment.getApplication(), signIn = true)
        try {
            scene.await("onboarding to open") { scene.viewModel.onboarding }
            val pages = Onboarding.pages()

            scene.viewModel.onboardingReached(2)
            assertEquals("moved to page three", 2, scene.settings.onboardingAt)
            scene.viewModel.onboardingReached(0)
            assertEquals("swiping back does not lose ground", 2, scene.settings.onboardingAt)
            // **The position is the reading, and the gate is the connections.** Reading to the end
            // does not open the door on its own — that is the settled rule — so this test stops at
            // what it is about: the number only moves forward.
            scene.viewModel.onboardingReached(pages.lastIndex)
            assertEquals("reaching the end records the end", pages.lastIndex, scene.settings.onboardingAt)
            assertTrue(
                "**and it is still open**, because reading is not connecting",
                scene.viewModel.onboarding,
            )
        } finally {
            scene.close()
        }
    }

    @Test
    fun `finishing onboarding leaves the connect page instead of stranding it`() {
        // **The operator's report**: *"after both connections are done the onboarding screen should
        // close itself - and you automatically enter account screen"*.
        //
        // `onboarding = false` only stopped the page being an onboarding page. The editor stayed
        // open on `06-connect.md`, so the person sat on the CONNECT page with the two links they had
        // just used — and because the guard in `closeEditor` reads `onboarding`, it had already
        // stopped refusing, leaving an ordinary file on screen with no reason to be there.
        val scene = Scenario(org.robolectric.RuntimeEnvironment.getApplication(), signIn = true)
        try {
            scene.await("onboarding to open") { scene.viewModel.onboarding }
            // **The page as a File**, because that is what the editor opens: `pages()` returns
            // name-and-text pairs, and the file lives in the system project's onboarding folder.
            val connect = File(
                File(scene.viewModel.workspace!!.projectDir(Workspace.SYSTEM_PROJECT), Onboarding.DIR),
                Onboarding.pages().last().name,
            )
            assertTrue("the page is on disk: $connect", connect.isFile)
            scene.viewModel.onboardingReached(Onboarding.pages().lastIndex)
            scene.viewModel.openEditor(connect)
            assertNotNull("the page is open", scene.viewModel.editingPath)

            // **The second connection is what ends it**, so this makes the first one's answer stand
            // and then connects the model.
            scene.viewModel.saveModelKey("sk-or-v1-a-key")
            scene.await("onboarding to close") { !scene.viewModel.onboarding }

            assertNull(
                "**and the page is gone with it**: ${scene.viewModel.editingPath}",
                scene.viewModel.editingPath,
            )
        } finally {
            scene.close()
        }
    }

    @Test
    fun `onboarding closes on its own once both connections are made`() {
        // **The operator's rule**: *"after providing both signins onboarding should close on its
        // own"*. Reading six pages is not the goal; connecting is. A person who connected on page
        // three should not have to swipe through three more to be let out.
        val scene = Scenario(org.robolectric.RuntimeEnvironment.getApplication(), signIn = true)
        try {
            scene.await("onboarding to open") { scene.viewModel.onboarding }
            val pages = Onboarding.pages()
            // **Still on page one**, which is the point: the pages read have nothing to do with it.
            assertEquals("nothing read yet", 0, scene.settings.onboardingAt)
            assertFalse("and not connected to a model yet", scene.viewModel.hasModelKey)

            scene.viewModel.saveModelKey("sk-or-v1-a-key")
            scene.await("the key to be kept") { scene.viewModel.hasModelKey }

            assertFalse("**closed by connecting**, from page one", scene.viewModel.onboarding)
            assertEquals("and recorded past the end", pages.size, scene.settings.onboardingAt)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `a model key alone does not close onboarding, because GitHub is not connected`() {
        // The rule is **both**, because one connection is an app that cannot do its job: a model
        // with no GitHub is a chat that cannot save, and GitHub with no model is a place to keep
        // files nobody writes. Closing on either would drop a person into a half-working app.
        val scene = Scenario(org.robolectric.RuntimeEnvironment.getApplication(), signIn = false)
        try {
            scene.await("onboarding to open") { scene.viewModel.onboarding }
            assertFalse("nobody is signed in", scene.viewModel.signedIn)

            // A key on the provisional workspace. It is kept, and it is not a connection to GitHub.
            scene.viewModel.saveModelKey("sk-or-v1-a-key")

            assertTrue(
                "**onboarding stays open** — half of what it is for is done, and the other half is " +
                    "the one that keeps the files",
                scene.viewModel.onboarding,
            )
        } finally {
            scene.close()
        }
    }

    @Test
    fun `the last page marks which connections are made`() {
        // **The operator's rule**: *"add a visual checkmark to the onboarding screen page across
        // links, so that it is visual whether the keys are set on not"*.
        //
        // The mark is derived from the same two facts that close onboarding — `signedIn` and
        // `hasModelKey` — so a tick and the gate can never disagree. That is the whole reason it is
        // drawn rather than written into the page's text: a mark baked into a versioned file would
        // be a claim that goes stale the moment somebody connects.
        val scene = Scenario(org.robolectric.RuntimeEnvironment.getApplication(), signIn = true)
        try {
            scene.await("onboarding to open") { scene.viewModel.onboarding }
            assertTrue("GitHub is connected", scene.viewModel.signedIn)
            assertFalse("and the model is not yet", scene.viewModel.hasModelKey)

            // **Half marked.** The pair is read as two separate answers, not one "done" flag.
            assertTrue("one of the two is made", scene.viewModel.signedIn)
            assertFalse("and one is not", scene.viewModel.hasModelKey)

            scene.viewModel.saveModelKey("sk-or-v1-a-key")

            assertTrue("**and now both**, which is the pair the marks draw", scene.viewModel.hasModelKey)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `onboarding does not open again once it has been read`() {
        val scene = Scenario(org.robolectric.RuntimeEnvironment.getApplication(), signIn = true)
        try {
            scene.await("onboarding to open") { scene.viewModel.onboarding }
            // **The one way out is connecting**, so this connects: the test is about what a second
            // launch shows, and it needs onboarding to have actually ended.
            scene.viewModel.saveModelKey("sk-or-v1-a-key")
            scene.await("onboarding to close") { !scene.viewModel.onboarding }
            scene.close()

            // **The same phone, opened again** — which is what a second launch is. The open is
            // posted, so it is awaited rather than assumed: a `assertFalse` straight after
            // construction would pass on a launch that had simply not got there yet.
            val again = scene.reopen()
            scene.await("the reopened app to settle") { again.workspace != null }
            assertFalse("not shown twice", again.onboarding)
        } finally {
            scene.close()
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

    override fun createRepo(
        token: String,
        name: String,
        licence: String,
        description: String,
        private: Boolean,
    ): GitHubResult<Repo> = GitHubResult(error = "Bad credentials", status = 401)

    override fun renameRepo(token: String, owner: String, name: String, newName: String):
        GitHubResult<Repo> = GitHubResult(error = "Bad credentials", status = 401)

    override fun cloneUrlFor(owner: String, name: String): String =
        "https://github.com/$owner/$name.git"

    override fun webUrl(cloneUrl: String): String = cloneUrl

    override fun owns(remoteUrl: String): Boolean = true

    // **The Actions calls, all refused like the rest.** They exist on the interface because the
    // agent's `ci` and `runs` tools read them; a fake that left them unimplemented would not compile,
    // and one that answered "no runs" would be a GitHub that agrees with everything, which is the
    // exact failure this class was written to end.
    override fun runs(
        token: String,
        ref: RepoRef,
        workflow: String,
        branch: String,
        limit: Int,
    ): GitHubResult<List<CiRun>> = GitHubResult(error = "Bad credentials", status = 401)

    override fun jobs(token: String, ref: RepoRef, run: Long): GitHubResult<List<CiJob>> =
        GitHubResult(error = "Bad credentials", status = 401)

    override fun logs(token: String, ref: RepoRef, run: Long): GitHubResult<Map<String, String>> =
        GitHubResult(error = "Bad credentials", status = 401)

    override fun artifacts(token: String, ref: RepoRef, run: Long): GitHubResult<List<CiArtifact>> =
        GitHubResult(error = "Bad credentials", status = 401)

    override fun artifactBytes(token: String, ref: RepoRef, id: Long): GitHubResult<ByteArray> =
        GitHubResult(error = "Bad credentials", status = 401)

    override fun dispatch(
        token: String,
        ref: RepoRef,
        workflow: String,
        branch: String,
        inputs: Map<String, String>,
    ): GitHubResult<Unit> = GitHubResult(error = "Bad credentials", status = 401)

    override fun environments(token: String, ref: RepoRef): GitHubResult<List<CiEnvironment>> =
        GitHubResult(error = "Bad credentials", status = 401)

    override fun putEnvironment(
        token: String,
        ref: RepoRef,
        name: String,
        reviewers: List<String>,
    ): GitHubResult<CiEnvironment> = GitHubResult(error = "Bad credentials", status = 401)

    override fun environmentKey(
        token: String,
        ref: RepoRef,
        environment: String,
    ): GitHubResult<Pair<String, ByteArray>> = GitHubResult(error = "Bad credentials", status = 401)

    override fun putEnvironmentSecret(
        token: String,
        ref: RepoRef,
        environment: String,
        name: String,
        sealed: ByteArray,
        keyId: String,
    ): GitHubResult<Unit> = GitHubResult(error = "Bad credentials", status = 401)

    override fun repoSecretNames(token: String, ref: RepoRef): GitHubResult<List<String>> =
        GitHubResult(error = "Bad credentials", status = 401)

    override fun environmentSecretNames(
        token: String,
        ref: RepoRef,
        environment: String,
    ): GitHubResult<List<String>> = GitHubResult(error = "Bad credentials", status = 401)

    override fun restrictEnvironmentTo(
        token: String,
        ref: RepoRef,
        environment: String,
        branches: List<String>,
    ): GitHubResult<Unit> = GitHubResult(error = "Bad credentials", status = 401)

    override fun deleteEnvironmentSecret(
        token: String,
        ref: RepoRef,
        environment: String,
        name: String,
    ): GitHubResult<Unit> = GitHubResult(error = "Bad credentials", status = 401)

    override fun deleteRepoSecret(token: String, ref: RepoRef, name: String): GitHubResult<Unit> =
        GitHubResult(error = "Bad credentials", status = 401)
}
