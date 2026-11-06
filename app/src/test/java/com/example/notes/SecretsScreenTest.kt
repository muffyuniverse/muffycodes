package com.example.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
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

    /**
     * Types into the [index]th text field of the dialog.
     *
     * **By position, because the label is not the field.** `onNodeWithText("Name")` finds the label's
     * own node, which takes no text — and `performTextInput` on it is what left the dialog waiting
     * for an idle state it never reached. The order is fixed by the dialog itself: name, value, host.
     */
    private fun type(index: Int, text: String) {
        compose.onAllNodes(hasSetTextAction())[index].performTextInput(text)
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
     * **A secret with no host cannot be saved, and the button says why it will not press.**
     *
     * The rule is that a value is bound to a host, so a name and a value alone describe something the
     * vault would have to guess about. A disabled button with no sentence would leave the person
     * unable to tell whether the app was broken or waiting for them — the failure this app keeps
     * finding.
     */
    @Test
    fun `the save button refuses a secret with no host, and says which field is missing`() {
        screen()
        compose.onNodeWithText("Add").performClick()

        type(0, "github")
        type(1, "ghp_the_real_token")

        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Say which host may use it.", substring = true).assertIsDisplayed()
    }

    /** With all three filled, the button presses and the secret is kept. */
    @Test
    fun `a name a value and a host are enough to keep one`() {
        screen()
        compose.onNodeWithText("Add").performClick()

        type(0, "github")
        type(1, "ghp_the_real_token")
        type(2, "api.github.com")

        compose.onNodeWithText("Save").assertIsEnabled()
        compose.onNodeWithText("Save").performClick()

        assertEquals("it is in the store", listOf("github"), scene.viewModel.secretNames())
        assertEquals("bound to its host", "api.github.com", scene.viewModel.secretHost("github"))
    }

    /**
     * **A URL in the host field is refused where the person can still fix it.**
     *
     * It looks right and would never match: the vault compares hosts, and `https://api.github.com/x`
     * is not one. Catching it here is the difference between a sentence now and a request that
     * mysteriously gets no token later.
     */
    @Test
    fun `a whole url in the host field is refused with the shape to use`() {
        screen()
        compose.onNodeWithText("Add").performClick()

        type(0, "github")
        type(1, "ghp_the_real_token")
        type(2, "https://api.github.com/user")

        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Just the host, without the scheme", substring = true).assertIsDisplayed()
    }

    /** Removing a secret asks first, because the value cannot be recovered — only re-entered. */
    @Test
    fun `removing asks before it forgets`() {
        scene.viewModel.saveSecret("github", "ghp_the_real_token", "api.github.com")
        screen()

        compose.onNodeWithText("Remove").performClick()
        compose.onNodeWithText("Remove github?", substring = true).assertIsDisplayed()

        compose.onNodeWithText("Keep").performClick()
        assertEquals("keeping leaves it", listOf("github"), scene.viewModel.secretNames())

        compose.onNodeWithText("Remove").performClick()
        compose.onAllNodesWithText("Remove")[1].performClick()
        assertEquals("and removing forgets it", emptyList<String>(), scene.viewModel.secretNames())
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
