package com.example.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The vault's door, painted for real.
 *
 * **The question this screen answers is "can a person put a secret in at all".** Before it existed
 * `setSecret` was encrypted, tested and called by nothing in the app — a tested function with no
 * caller — so the `http` tool's `auth:` could not be used by anybody however well it worked. A
 * ViewModel test cannot see whether the screen is reachable, whether the fields are in an order a
 * person can follow, or whether the Save button explains itself when it will not press.
 *
 * **And the one assertion that is about safety rather than use:** the value must never appear on the
 * list. Not masked, not truncated — absent. A screen that could show it would undo the store.
 *
 * ## What this file deliberately does not do
 *
 * **It does not drive the `AlertDialog`.** No test in this repository has ever done so, and the reason
 * took three CI rounds to find: a Compose dialog's enter animation never reports idle under
 * Robolectric, so the first query against a just-opened dialog waits sixty seconds, fails with
 * `AppNotIdleException` on whichever line came next, and takes the suite's memory with it. Disabling
 * `autoAdvance` fixes the waiting and reveals that the dialog's own state is still not observable
 * reliably.
 *
 * So the split is: **the screen is painted and asserted** (what a person sees), and **the rules are
 * called as functions** (`secretProblem`, `saveSecret`, `forgetSecret` — what the app decides). The
 * dialog itself is three text fields and a button bound to those, and binding is the part a compiler
 * checks.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class SecretsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @After
    fun putThePhoneAway() {
        scene.close()
    }

    private val scene: Scenario by lazy {
        Scenario(RuntimeEnvironment.getApplication(), signIn = true)
    }

    @Composable
    private fun Frame(content: @Composable () -> Unit) {
        MaterialTheme { Surface { content() } }
    }

    private fun screen(onBack: () -> Unit = {}) {
        compose.setContent {
            Frame { SecretsScreen(viewModel = scene.viewModel, onBack = onBack) }
        }
    }

    /**
     * **An empty vault says so in the error colour and names what is missing.** The sentence has to
     * point at the consequence — an article or a tool has nothing to use — because a person who has
     * never made a secret does not yet know what one is for.
     */
    @Test
    fun `an empty vault says so, and offers the way to fill it`() {
        screen()

        compose.onNodeWithText("No secrets on this phone yet.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Add").assertIsDisplayed()
    }

    /** A secret that is added is listed **by name and host**, and the value is nowhere. */
    @Test
    fun `a secret is listed by name and host, and its value is never shown`() {
        val problem = scene.viewModel.saveSecret("github", "ghp_the_real_token", "api.github.com")
        assertEquals("the secret was kept: $problem", null, problem)
        assertEquals("and it is in the store", listOf("github"), scene.viewModel.secretNames())
        screen()

        compose.onNodeWithText("github").assertIsDisplayed()
        // **The host and the scope are one line**, so the host is found inside it rather than as a
        // node of its own — the row reads "api.github.com · all projects" and both halves matter.
        compose.onNodeWithText("api.github.com", substring = true).assertIsDisplayed()
        // **The assertion the whole store exists for.** Not masked, not shortened: absent.
        assertEquals(
            "the value is on the screen, which undoes the store",
            0,
            compose.onAllNodesWithText("ghp_the_real_token", substring = true).fetchSemanticsNodes().size,
        )
    }

    /**
     * **A secret with no host cannot be saved, and the sentence names the field.**
     *
     * Tested as the function rather than by filling the dialog, and the reason is a finding rather
     * than a shortcut: **no test in this repository has ever driven a Compose `AlertDialog`**, because
     * its enter animation never reports idle under Robolectric. Three CI rounds went on that — the
     * symptom is `AppNotIdleException` on whichever line came next, and the suite runs out of memory
     * waiting. The rule is a pure function; the screen's part is that Save is bound to it, which
     * [the add row is on the screen] asserts.
     */
    @Test
    fun `a secret with no host cannot be saved, and the sentence names the field`() {
        val problem = secretProblem("github", "ghp_the_real_token", "")
        assertTrue("it is refused: $problem", problem!!.contains("Say which host may use it"))
        assertNull("and a good one passes", secretProblem("github", "ghp_the_real_token", "api.github.com"))
    }

    /**
     * **A URL in the host field is refused with the shape to use.**
     *
     * It looks right and would never match: the vault compares hosts, and `https://api.github.com/x`
     * is not one. Catching it where the person can still fix it is the difference between a sentence
     * now and a request that mysteriously gets no token later.
     */
    @Test
    fun `a whole url in the host field is refused with the shape to use`() {
        val problem = secretProblem("github", "ghp_the_real_token", "https://api.github.com/user")
        val said = problem.orEmpty()
        assertTrue("it is refused: $said", said.contains("Just the host, without the scheme"))
        assertTrue("and it names the shape: $said", said.contains("api.github.com, not"))
    }

    /**
     * **A name a value and a host are enough to keep one**, through the view model — the same call the
     * dialog's Save makes. What the dialog adds is three fields and a button; what matters is that the
     * three facts produce a stored secret bound to its host.
     */
    @Test
    fun `a name a value and a host are enough to keep one`() {
        val problem = scene.viewModel.saveSecret("github", "ghp_the_real_token", "api.github.com")

        assertNull("it was kept: $problem", problem)
        assertEquals("it is in the store", listOf("github"), scene.viewModel.secretNames())
        assertEquals("bound to its host", "api.github.com", scene.viewModel.secretHost("github"))
        assertEquals(
            "and the value is the one that was given",
            "ghp_the_real_token",
            scene.credentials.secret("github", "api.github.com"),
        )
    }

    /**
     * **Removing is a call, and it forgets.** The confirmation is the dialog's business, and the
     * dialog is not drivable here; the fact a person needs is that the value is gone afterwards.
     */
    @Test
    fun `removing forgets the value as well as the name`() {
        scene.viewModel.saveSecret("github", "ghp_the_real_token", "api.github.com")
        assertEquals("it is there first", listOf("github"), scene.viewModel.secretNames())

        scene.viewModel.forgetSecret("github", SecretScope.Account)

        assertEquals("the name is gone", emptyList<String>(), scene.viewModel.secretNames())
        assertNull("and so is the value", scene.credentials.secret("github", "api.github.com"))
    }

    /**
     * **The list shows a name and a host and never a value**, which is the one assertion about safety
     * rather than use. It reads the view model's answers, because those are what the screen paints.
     */
    @Test
    fun `the list shows the name and the host and never the value`() {
        scene.viewModel.saveSecret("github", "ghp_the_real_token", "api.github.com")

        val names = scene.viewModel.secretNames()
        val hosts = names.map { scene.viewModel.secretHost(it).orEmpty() }

        assertEquals("the name is listed", listOf("github"), names)
        assertEquals("with the host it is for", listOf("api.github.com"), hosts)
        assertTrue(
            "and no value is among them, which is the whole point of the store",
            (names + hosts).none { it.contains("ghp_the_real_token") },
        )
    }

    /**
     * **The sentences that stop a save are the screen's, so they are tested where they are read.**
     *
     * `secretProblem` is a plain function and could be tested alone, but every one of these is a
     * message a person sees at a particular moment, and the point of each is that it names the field
     * to fix rather than saying "invalid".
     */
    @Test
    fun `each missing field is named rather than reported as invalid`() {
        assertTrue(secretProblem("", "v", "h")!!.contains("name"))
        assertTrue(secretProblem("a/b", "v", "h")!!.contains("slash"))
        assertTrue(secretProblem("n", "", "h")!!.contains("value"))
        assertTrue(secretProblem("n", "v", "")!!.contains("host"))
        assertTrue(secretProblem("n", "v", "https://x/y")!!.contains("scheme"))
        assertNull("and a good one passes", secretProblem("n", "v", "api.github.com"))
    }
    // ---- scope: where the value is kept, chosen by walking to it --------------------------

    /**
     * **The root level: the account itself, then every project, each a way in.**
     *
     * The account line is first and is not a way in — it is the level, and the only row that can say
     * "every project on this account". Without it the widest scope a person could reach would be a
     * project, which is not what most secrets are.
     */
    @Test
    fun `the account level offers the account and every project`() {
        val lines = scopeLines(ScopeHere.Root, listOf("shop", "acme")) { listOf("main") }

        assertEquals("the account is first", "Every project on this account", lines.first().label)
        assertEquals("and it chooses the account scope", SecretScope.Account, lines.first().chooses)
        assertFalse("and it is not a folder to walk into", lines.first().walks)
        assertEquals(
            "then every project, in order: ${lines.map { it.label }}",
            listOf("acme/", "shop/"),
            lines.drop(1).map { it.label },
        )
        assertTrue(
            "and a project is both a scope and a way in",
            lines.drop(1).all { it.walks && it.chooses is SecretScope.Project },
        )
    }

    /**
     * **Inside a project: the project itself, then its branches** — the level that makes the branch
     * scope reachable at all.
     */
    @Test
    fun `inside a project the project comes first, then its branches`() {
        val lines = scopeLines(ScopeHere.InProject("acme"), listOf("acme")) {
            listOf("main", "feature")
        }

        assertEquals(
            "the project's own scope is first",
            SecretScope.Project("acme"),
            lines.first().chooses,
        )
        assertFalse("and it is not a way in", lines.first().walks)
        assertEquals(
            "then the branches: ${lines.map { it.label }}",
            listOf("main", "feature"),
            lines.drop(1).map { it.label },
        )
        assertEquals(
            "each choosing its own branch",
            listOf(SecretScope.Branch("acme", "main"), SecretScope.Branch("acme", "feature")),
            lines.drop(1).map { it.chooses },
        )
    }

    /**
     * **A tap looks inside; a long press chooses.** The Move window's rule, and the two answers are
     * tested here rather than in a dialog nobody can drive.
     */
    @Test
    fun `a tap walks into a project and a long press chooses it`() {
        val project = scopeLines(ScopeHere.Root, listOf("acme")) { listOf("main") }.last()
        val tap = scopeTap(project)

        assertTrue("a tap on a project walks into it: $tap", tap is ScopeTap.Into)
        assertEquals(
            "and it is that project",
            ScopeHere.InProject("acme"),
            (tap as ScopeTap.Into).here,
        )
        assertEquals(
            "while a long press chooses the project itself",
            SecretScope.Project("acme"),
            scopeLongPress(project),
        )
    }

    /**
     * **A branch cannot be walked into, so a tap says what to do instead.**
     *
     * A row that looks pressable and does nothing is the failure this app keeps removing; the answer
     * names the row, in the same words the Move window uses.
     */
    @Test
    fun `a tap on a branch says to press and hold`() {
        val branch = scopeLines(ScopeHere.InProject("acme"), listOf("acme")) { listOf("main") }.last()
        val tap = scopeTap(branch)

        assertTrue("a tap says something: $tap", tap is ScopeTap.Hint)
        val said = (tap as ScopeTap.Hint).said
        assertTrue("and it names the row: $said", said.contains("\"main\""))
        assertTrue("and the gesture: $said", said.contains("Press and hold"))
        assertEquals(
            "while a long press chooses that very branch",
            SecretScope.Branch("acme", "main"),
            scopeLongPress(branch),
        )
    }

    /** **The footer says what is chosen, or says nothing is** — the Save button depends on it. */
    @Test
    fun `the footer names the choice or says there is none`() {
        assertEquals("Nothing chosen yet", scopeChosenSaid(null))
        assertEquals("Keeping it for this account", scopeChosenSaid(SecretScope.Account))
        assertEquals(
            "Keeping it for only acme/feature",
            scopeChosenSaid(SecretScope.Branch("acme", "feature")),
        )
    }

    /** **All four labels**, because they are four different facts and a person must tell them apart. */
    @Test
    fun `each scope reads as its own fact`() {
        assertEquals("this account", scopeLabel(SecretScope.Account))
        assertEquals("all projects, every account", scopeLabel(SecretScope.General))
        assertEquals("only acme", scopeLabel(SecretScope.Project("acme")))
        assertEquals("only acme/feature", scopeLabel(SecretScope.Branch("acme", "feature")))
    }

    /**
     * **A branch's value shadows the project's, and is invisible from a sibling branch** — the whole
     * reason the scope exists, and the one thing a fake that merged its maps could not show.
     */
    @Test
    fun `a branch secret answers only for its own branch`() {
        scene.project("acme")
        scene.viewModel.saveSecret("stripe", "acct", "api.stripe.com")
        scene.viewModel.saveSecret(SecretScope.Project("acme"), "stripe", "proj", "api.stripe.com")
        val kept = scene.viewModel.saveSecret(
            SecretScope.Branch("acme", "feature"),
            "stripe",
            "branch",
            "api.stripe.com",
        )
        assertEquals("the branch value was kept: $kept", null, kept)

        assertEquals(
            "on that branch it wins",
            "branch",
            scene.credentials.secret("acme", "feature", "stripe", "api.stripe.com"),
        )
        assertEquals(
            "on main the project's answers",
            "proj",
            scene.credentials.secret("acme", "main", "stripe", "api.stripe.com"),
        )
        assertEquals(
            "and with no branch named, so does the project's",
            "proj",
            scene.credentials.secret("acme", null, "stripe", "api.stripe.com"),
        )
    }

    /**
     * **A nearer scope bound to another host does not hide the wider one.**
     *
     * The project's value says nothing about `api.github.com`, so the account's still answers there —
     * otherwise adding a key for one service would silently take away the account's key for every
     * other, which is a refusal with no sentence explaining it.
     */
    @Test
    fun `a nearer value for one host leaves the account's answer for another`() {
        scene.project("acme")
        scene.viewModel.saveSecret("git", "acct", "api.github.com")
        scene.viewModel.saveSecret(SecretScope.Project("acme"), "git", "proj", "gitlab.com")

        assertEquals(
            "the project's own host still wins in the project",
            "proj",
            scene.credentials.secret("acme", "main", "git", "gitlab.com"),
        )
        assertEquals(
            "and the account's answers for the host the project's value is not for",
            "acct",
            scene.credentials.secret("acme", "main", "git", "api.github.com"),
        )
    }

    /**
     * **A project-scoped secret says so on its row.** The scope decides who can read the value, so it
     * belongs beside the host rather than behind a tap — a scope nobody can see is a scope nobody
     * checks.
     */
    @Test
    fun `a project-scoped secret shows its scope on the row`() {
        // **The project must exist**, or the row would be a claim about a project that is not there.
        scene.project("acme")
        val problem = scene.viewModel.saveSecret("acme-api", "tok_1", "api.acme.test", "acme")
        assertEquals("the secret was kept: $problem", null, problem)
        // **And it is listed**, which it was not: `secretNames()` used to filter project-scoped keys
        // out entirely, so a project secret was stored, readable by the agent, and invisible in the
        // window — it could never be seen or removed.
        assertTrue(
            "the project secret is listed: ${scene.viewModel.secretNames()}",
            "acme-api" in scene.viewModel.secretNames(),
        )
        screen()

        compose.onNodeWithText("acme-api").assertIsDisplayed()
        compose.onNodeWithText("only acme", substring = true).assertIsDisplayed()
    }

    /**
     * **A general secret is a row like any other, and the window used to hide it.**
     *
     * The store listed only keys under `secret_<login>_`, so `secret_general_*` never appeared: the
     * agent could *use* a general secret, the person could create one, and then it was invisible —
     * impossible to see, rotate or remove. Measured, not suspected: the fake in `Scenario` merged the
     * three scopes into its name list, so the suite could not see the difference between the fake and
     * the phone.
     */
    @Test
    fun `a general secret is listed with its own host, and can be removed`() {
        scene.project("acme")
        val problem = scene.viewModel.saveGeneralSecret("shared", "k_1", "api.shared.test")
        assertEquals("the secret was kept: $problem", null, problem)
        assertTrue(
            "and it is a row: ${scene.viewModel.secretRows()}",
            scene.viewModel.secretRows().any { it.name == "shared" },
        )
        screen()

        compose.onNodeWithText("shared").assertIsDisplayed()
        compose.onNodeWithText("api.shared.test", substring = true).assertIsDisplayed()
        compose.onNodeWithText("all projects, every account", substring = true).assertIsDisplayed()

        val row = scene.viewModel.secretRows().first { it.name == "shared" }
        assertEquals("the row knows its scope", SecretScope.General, row.scope)
        scene.viewModel.forgetSecret(row.name, row.scope)
        assertTrue(
            "and Remove removes it, which it could not before: ${scene.viewModel.secretRows()}",
            scene.viewModel.secretRows().none { it.name == "shared" },
        )
    }

    /**
     * **A project's row names the host the value is really bound to.**
     *
     * It said "used by this phone only" for every project secret, because the screen asked
     * `secretHost(name)`, which only ever read the account's key — a sentence saying a value goes
     * nowhere, on the screen whose whole purpose is to say where values may go.
     */
    @Test
    fun `a project secret's row names its own host`() {
        scene.project("acme")
        val problem = scene.viewModel.saveSecret("acme-api", "tok_1", "api.acme.test", "acme")
        assertEquals("the secret was kept: $problem", null, problem)
        screen()

        compose.onNodeWithText("api.acme.test", substring = true).assertIsDisplayed()
        assertTrue(
            "and nothing claims it stays on the phone: ${scene.viewModel.secretRows()}",
            compose.onAllNodesWithText("used by this phone only", substring = true)
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    /**
     * **A name held in two scopes is two rows, and Remove takes the one it is beside.**
     *
     * The window used to call `forgetSecret(name)`, which always removed the *account's* value — so
     * pressing Remove on a project's secret deleted a different secret, or nothing at all, and said
     * nothing either way.
     */
    @Test
    fun `removing a project's secret leaves the account's one of the same name`() {
        scene.project("acme")
        scene.viewModel.saveSecret("stripe", "acct_key", "api.stripe.com")
        scene.viewModel.saveSecret("stripe", "proj_key", "api.stripe.com", "acme")
        assertEquals(
            "both rows exist: ${scene.viewModel.secretRows()}",
            2,
            scene.viewModel.secretRows().count { it.name == "stripe" },
        )

        val project = scene.viewModel.secretRows().first { it.scope is SecretScope.Project }
        scene.viewModel.forgetSecret(project.name, project.scope)

        val left = scene.viewModel.secretRows().filter { it.name == "stripe" }
        assertEquals("one row is left: $left", 1, left.size)
        assertEquals("and it is the account's", SecretScope.Account, left.single().scope)
        assertEquals(
            "whose value still opens",
            "acct_key",
            scene.credentials.secret("stripe", "api.stripe.com"),
        )
        assertEquals(
            "and the project now falls back to the account's, rather than losing it",
            "acct_key",
            scene.credentials.secret("acme", "stripe", "api.stripe.com"),
        )
    }

    /**
     * **An account-wide secret says "this account"**, which is now a different fact from "all
     * projects, every account" — before the general scope existed, "all projects" was the widest thing
     * there was and the wording was honest. Now it would be a lie: this secret is not visible from
     * another account's projects.
     */
    @Test
    fun `an account-wide secret says this account`() {
        scene.viewModel.saveSecret("stripe", "tok_1", "api.stripe.com")
        screen()

        compose.onNodeWithText("this account", substring = true).assertIsDisplayed()
    }

}
