package com.example.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The settings screen, painted for real.
 *
 * Three things were asked of this screen and each of them is about words on the
 * glass, which a ViewModel test cannot answer: **Switch** instead of an identity
 * editor, the window saying to use Add when there is nothing to switch to, and the
 * history field saying plainly what it counts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class SettingsScreenTest {

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

    private fun settings(
        onSignIn: () -> Unit = {},
        onRemove: () -> Unit = {},
        /** The phone the test is testing: the exemption is a fact of the system, not of the screen. */
        exempt: (android.content.Context) -> Boolean = { false },
        onAskBattery: () -> Unit = {},
    ) {
        compose.setContent {
            Frame {
                SettingsScreen(
                    viewModel = scene.viewModel,
                    onBack = {},
                    onSignIn = onSignIn,
                    onRemoveAccount = onRemove,
                    onTestAccount = {},
                    exempt = exempt,
                    onAskBattery = onAskBattery,
                )
            }
        }
    }

    @Test
    fun `Switch says to use Add when there is no other account`() {
        // One account, so there is nothing to move to. An empty window would look
        // broken; naming the button that fixes it is the whole answer.
        settings()

        compose.onNodeWithText("Switch").performClick()

        compose.onNodeWithText("There are no other accounts on this phone yet.")
            .assertIsDisplayed()
        compose.onNodeWithText("Use Add to sign in to another one first.")
            .assertIsDisplayed()
    }

    @Test
    fun `Identity is gone from the screen`() {
        // The commit name and email come from the account the key belongs to, so
        // there is nothing here to edit — and a button that stayed would be an
        // invitation to commit as somebody else.
        settings()

        compose.onNodeWithText("Identity").assertDoesNotExist()
    }

    @Test
    fun `the history field says what it counts`() {
        settings()

        compose.onNodeWithText("Change history").assertIsDisplayed()
        compose.onNodeWithText("Changes kept").assertIsDisplayed()
    }

    @Test
    fun `the battery row says what the system is doing, and offers the only fix`() {
        // **The one thing on this screen the app cannot do for the person.** In Doze the network is
        // suspended for apps that are not exempt, so a poll fails and — with no fallback provider by
        // design — the session stops rather than waiting. The row says which state the phone is in.
        var asked = 0
        settings(exempt = { false }, onAskBattery = { asked++ })

        // The row is below the fold on a phone-shaped window, and a screen is not "not showing"
        // something because it is further down the same page.
        compose.onNodeWithText("Battery").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Not exempt · polls may fail").assertIsDisplayed()
        compose.onNodeWithText("Ask Android").performClick()

        assertEquals("and the button asks", 1, asked)
    }

    @Test
    fun `an exempt phone is told so, and is not asked again`() {
        // A button that goes on asking after the answer is yes is a button that teaches people to
        // dismiss the dialog — so the row must not offer it once the phone already allows polls.
        settings(exempt = { true })

        compose.onNodeWithText("Exempt · the agent can poll").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Ask Android").assertDoesNotExist()
    }

    @Test
    fun `Switch lists the other account and moves to it`() {
        // A second account on this phone. The dialog offers it, and picking it
        // actually changes who is active — the screen must not be showing a list that
        // does nothing.
        scene.viewModel.signIn("someone-else", "a-key", "", "")
        scene.viewModel.switchTo(scene.login)
        settings()

        compose.onNodeWithText("Switch").performClick()
        compose.onNodeWithText("someone-else").performClick()

        assertEquals("the other account is active", "someone-else", scene.viewModel.activeLogin)
    }
}
