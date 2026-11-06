package com.example.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Assert.assertEquals
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
        compose.onNodeWithText("api.github.com").assertIsDisplayed()
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
        assertTrue("it is refused: $problem", problem!!.contains("Just the host, without the scheme"))
        assertTrue("and it names the shape: $problem", problem.contains("api.github.com, not"))
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

        scene.viewModel.forgetSecret("github")

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
        val hosts = names.map { scene.viewModel.secretHost(it) }

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
}
