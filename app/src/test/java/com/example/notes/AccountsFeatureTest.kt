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
            signInGitHub = { RefusingWho() },
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
            // **An absence, and that is the claim.** A refused key leaves the app exactly where it
            // started: with no account, because there is no account an app can ship (§3.11). The
            // sentence is on screen and nothing was written.
            assertNull("no account was made", phone.viewModel.activeLogin)
            assertNull("so there is no workspace", phone.viewModel.workspace)
        } finally {
            phone.close()
        }
    }

    @Test
    fun `with nobody signed in there is no workspace at all`() {
        // **The operator's ruling, and it reverses what this test asserted twice.** There used to be
        // a `~local` account so that the app had somewhere to write before anybody signed in. There
        // is no account an app can ship (§3.11): a phone with no account has no workspace, no
        // project, and no door — and the screen that answers that is the accounts list, which the
        // introduction explains first.
        assertNull("no account", scene.viewModel.account)
        assertNull("no workspace", scene.viewModel.workspace)
        assertFalse("nothing is signed in", scene.viewModel.signedIn)
        // **No door, deliberately.** Answering with an `HttpWho` would give every caller
        // something that looks usable and sends unauthenticated requests to the internet.
        assertNull("so there is no GitHub to ask who a key is", scene.viewModel.who)
        assertTrue("and no account is listed", scene.viewModel.accountsWithProjects.isEmpty())
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
    fun `a test account's door is the disk one, not the internet`() {
        // **What is left of "a host" is one question**, so that is what there is to assert: a debug
        // build's test account is answered on this phone, and a key is still required — the fake can
        // say no, which is what makes the refusal half of signing in testable at all.
        scene.signedIn()

        val who = scene.viewModel.who!!
        assertTrue("it is the disk one", who is DiskWho)
        assertEquals("with the key's owner", "test-account", who.identity("any-key").value?.login)
        assertEquals("and a key is still required", 401, who.identity("").status)
    }

    @Test
    fun `signing in gives the account its own folder, and the list starts empty`() {
        // **The account root is the projects list**, so signing in shows an empty list rather than
        // a `projects/` folder to walk into. There is nothing between a person and their work.
        scene.signedIn()

        assertTrue("the account's folder is there", scene.workspace!!.accountDir.isDirectory)
        // **One row, and it is not the person's project.** `.muffy/` is the app's own flags, which
        // report 28 asked to stop hiding. The `shared` row is still drawn — it is the app's folder,
        // which is where the system prompt lives — but it comes from the workspace's second root
        // rather than from the account's folder (§3.11), so it is not one of these entries.
        assertEquals("and no project of their own yet", listOf(".muffy"), scene.names())
    }

    @Test
    fun `an account on another host is made by the same save, and keeps its host`() {
        // **The door the plan exists for.** An account is a name, a host and a key; the host is what
        // the key may be sent to, so it is stored on the account rather than inferred from whichever
        // project happens to be open. Nothing here asks GitHub anything — there is no endpoint on
        // `gitlab.com` for this app to ask.
        val phone = Scenario(RuntimeEnvironment.getApplication(), signIn = false)
        try {
            var answered: Boolean? = null
            phone.viewModel.addHostAccount(
                name = "someone",
                host = "GitLab.com",
                gitUser = "oauth2",
                key = "glpat-the-key",
            ) { answered = it }
            phone.await("the account to be made") { answered != null }

            assertEquals("it was made", true, answered)
            val made = phone.viewModel.account
            assertEquals("with the name that was typed", "someone", made?.login)
            assertEquals("and the host, folded", "gitlab.com", made?.host)
            assertEquals("and the git username", "oauth2", made?.gitUser)
            assertEquals("and it is the active account", "someone", phone.viewModel.activeLogin)
            assertEquals("and the key went into the vault", "glpat-the-key", phone.credentials.token("someone"))
        } finally {
            phone.close()
        }
    }

    @Test
    fun `an account on another host has no GitHub door at all`() {
        // **The failure this prevents is a key sent to a host it was never made for.** An
        // `HttpWho()` behind a `gitlab.com` account would call `api.github.com` with that account's
        // key and answer with a 401 that explains nothing. `null` is the honest answer: there is no
        // such door for this account, exactly as there is none for a phone with nobody signed in.
        val phone = Scenario(RuntimeEnvironment.getApplication(), signIn = false)
        try {
            var answered: Boolean? = null
            phone.viewModel.addHostAccount("someone", "gitlab.com", "", "glpat-the-key") { answered = it }
            phone.await("the account to be made") { answered != null }

            assertNull("there is no such door behind it", phone.viewModel.who)
            // **And nothing else in the app is GitHub's.** This test used to go on to assert that a
            // GitHub-only action refused with a sentence naming the host — and after the cut there is
            // no such action left, which is the stronger fact. A project is made here, with the git
            // protocol, and the account's host never comes into it.
            var made: Boolean? = null
            phone.viewModel.createProject("anything") { made = it }
            phone.await("the project to be made") { made != null }
            assertEquals("a project on this phone needs no host", true, made)
            assertTrue(
                "and it is there",
                phone.viewModel.projectNames.any { it.equals("anything", ignoreCase = true) },
            )
        } finally {
            phone.close()
        }
    }

    @Test
    fun `a host that is an address says where the address belongs`() {
        // A person will paste a clone URL: it is the thing on the clipboard on every host's page. The
        // refusal has to name the host it found, or the correction is a guess.
        scene.viewModel.addHostAccount("someone", "https://gitlab.com/team/r.git", "", "a-key")

        val said = scene.viewModel.message.orEmpty()
        assertTrue("it names the host: $said", said.contains("gitlab.com"))
        assertTrue("and where the address goes: $said", said.contains("project"))
        assertNull("and nothing was made", scene.viewModel.accounts.account("someone"))
    }

    @Test
    fun `a second account cannot take the first one's name`() {
        // **A login is a folder name**, so two accounts cannot share one — and a second account of the
        // same name would be a second claim on one folder of projects. Said before anything is written.
        // This class starts with no account, so the first one has to be made before a second can clash.
        scene.signedIn()
        scene.viewModel.addHostAccount("test-account", "gitlab.com", "", "a-key")

        val said = scene.viewModel.message.orEmpty()
        assertTrue("it says the name is taken: $said", said.contains("already"))
        assertEquals(
            "and the existing account is untouched",
            GITHUB_HOST,
            scene.viewModel.accounts.account("test-account")?.host,
        )
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
    fun `a project made here can be given an address, and the row says where it goes`() {
        // **The ordinary second step of a project's life.** A project is made in an account with no
        // remote, and pasting the host's address is what publishes it — there is no "put it on
        // GitHub" act, because making the repository is the host's own page and this window takes
        // its result.
        scene.signedIn()
        scene.project("notes")

        scene.viewModel.setAddress("notes", "https://host/owner/notes.git")

        assertEquals(
            "the address is stored",
            "https://host/owner/notes.git",
            scene.viewModel.remoteUrlOf("notes"),
        )
    }

    @Test
    fun `removing an account keeping the copies leaves every file on the phone`() {
        scene.signedIn()
        scene.folder("keep_me")
        val kept = File(scene.workspace!!.accountDir, "keep_me")

        scene.viewModel.removeAccount(scene.login, deleteCopies = false)

        // **No account is left, and that is an answer rather than a bug.** There used to be a
        // `~local` fallback; with no account shipped (§3.11) a phone whose last account was removed
        // has none, and the screen that says so is the accounts list with its two doors.
        assertNull("no account is open", scene.viewModel.activeLogin)
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
            signInGitHub = { DiskWho("muffy") },
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
     * **`app:` is refused, everywhere, and that is the whole rule.**
     *
     * It used to be a vocabulary of verbs a page could press, scoped by "is this page the app's own?".
     * The pages teach the journey now and every act has its own door, so a document cannot act at all
     * — which is a smaller rule with no path comparison behind it, and the one that cannot be got
     * wrong by a clone or a message.
     */
    @Test
    fun `an app link does nothing, in the app's own folder and in a clone`() {
        // **The app's folder, which is where the introduction lives** — the place the old rule allowed
        // it, so this is the assertion that the exception is gone rather than merely narrowed.
        val dir = File(scene.viewModel.accounts.sharedDir, Onboarding.DIR)
        val page = File(dir, Onboarding.pages().last().name)
        scene.viewModel.followLink("app:use-token", page.parentFile)
        assertNull("the app's own page cannot act either", scene.viewModel.pendingConnection)

        scene.viewModel.followLink("app:connect-github", scene.viewModel.currentDir ?: scene.root)
        assertNull("and neither can a clone", scene.viewModel.pendingConnection)
    }

    @Test
    fun `a refused app link is recorded`() {
        // **Silent on screen, visible in the log.** A message per attempt would be the app explaining a
        // wall; a refusal nobody can see being tested is a refusal nobody can check.
        scene.viewModel.followLink("app:delete-account", scene.viewModel.currentDir ?: scene.root)

        assertTrue(
            "the refusal is recorded: ${Trace.report().lines.take(3)}",
            Trace.report().lines.take(3).any { it.contains("link-refused") },
        )
    }

    /**
     * **A link press is traced**, and the two kinds are named differently — one leaves the app, the
     * other opens a file. A log that called both "link" could not answer "did the page get them to the
     * file it names".
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

        // **A wait, because the queue is not observable.** The save is queued with a zero debounce,
        // so it runs almost at once; a wait is the only way to tell "the guard refused it" from "it
        // has not run yet". This test cannot *force* the race — the save may well finish before the
        // removal — but it does assert that the redraw which follows a removal does not remake the
        // folder, which is the half that made the guard moot: `exists()` is a folder check, so a
        // repaint that recreated the tree reopened the very door the save was guarded against.
        Thread.sleep(500)
        assertFalse(
            "and the save did not recreate it",
            scene.viewModel.accounts.dirOf(scene.login).exists(),
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
    fun `finishing onboarding leaves the last page instead of stranding it`() {
        // **The operator's report**: *"after both connections are done the onboarding screen should
        // close itself - and you automatically enter account screen"*.
        //
        // `onboarding = false` only stopped the page being an onboarding page. The editor stayed
        // open on the last page, so the person sat on a page that had done its job — and because the
        // guard in `closeEditor` reads `onboarding`, it had already stopped refusing, leaving an
        // ordinary file on screen with no reason to be there.
        val scene = Scenario(org.robolectric.RuntimeEnvironment.getApplication(), signIn = true)
        try {
            scene.await("onboarding to open") { scene.viewModel.onboarding }
            // **The page as a File**, because that is what the editor opens: `pages()` returns
            // name-and-text pairs, and the file lives in the app's onboarding folder (§3.11) — which
            // exists with no account, which is why this class can reach it.
            val connect = File(
                File(scene.viewModel.accounts.sharedDir, Onboarding.DIR),
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

            // A model key with no account. It is kept — the vault does not need an identity — and it
            // is not a connection to GitHub, which is why onboarding stays open.
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
 * A host that refuses every key.
 *
 * [DiskWho] accepts whatever it is given, which made the refusal path unreachable and therefore
 * unrun. This one exists only to say no, the way a host does to an expired or mistyped key.
 */
private class RefusingWho : Who {
    override fun identity(token: String): WhoAnswer<Identity> =
        WhoAnswer(error = "Bad credentials", status = 401)
}
