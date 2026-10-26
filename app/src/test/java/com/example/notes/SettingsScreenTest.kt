package com.example.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
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
        /** Whether a reminder would be seen: the phone's fact, stated by the test. */
        notified: (android.content.Context) -> Boolean = { true },
        onAskNotices: () -> Unit = {},
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
                    notified = notified,
                    onAskNotices = onAskNotices,
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
        compose.onNodeWithText("Versions kept").assertIsDisplayed()
    }

    @Test
    fun `the battery row says what the system is doing, and offers the only fix`() {
        // **The one thing on this screen the app cannot do for the person.** In Doze the network is
        // suspended for apps that are not exempt, so a poll fails and — with no fallback provider by
        // design — the session stops rather than waiting. The row says which state the phone is in.
        var asked = 0
        settings(exempt = { false }, onAskBattery = { asked++ })

        // The row is below the fold on a phone-shaped window, and a screen is not "not showing"
        // something because it is further down the same page. It is called **Background** now: it
        // is inside the agent's block, and "Battery" described the setting rather than the thing it
        // is for.
        compose.onNodeWithText("Background").performScrollTo().assertIsDisplayed()
        // Each node is scrolled to before it is asked about: a page taller than the window shows
        // one screenful, and "further down the same page" is not "not there".
        compose.onNodeWithText("Not exempt · polls may fail").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Ask Android").performScrollTo().performClick()

        assertEquals("and the button asks", 1, asked)
    }

    @Test
    fun `an exempt phone is told so, and is not asked again`() {
        // A button that goes on asking after the answer is yes is a button that teaches people to
        // dismiss the dialog — so the row must not offer it once the phone already allows polls.
        settings(exempt = { true })

        compose.onNodeWithText("Exempt · it can poll").performScrollTo().assertIsDisplayed()
        // The row is on the page; the button is not. Scrolling to where the button would be, and
        // still finding nothing, is the assertion — existence alone would pass on a page nobody
        // could reach.
        compose.onNodeWithText("Background").performScrollTo()
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
    @Test
    fun `the notification permission is offered only while it is missing`() {
        // **A declared permission that was never granted is a reminder posted into nowhere.** The row
        // says which way it is, and offers the button exactly when there is something to ask for.
        var asked = 0
        settings(notified = { false }, onAskNotices = { asked++ })

        compose.onNodeWithText("Off · nothing is shown").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Allow").performScrollTo().performClick()

        assertEquals("the dialog was asked for once", 1, asked)
    }

    @Test
    fun `the command handover is off on a phone that never chose it`() {
        // **The setting's own default, read through the store**, which is where the number lives.
        // Zero is "wait for the command however long it takes", and that is what this app did before
        // the handover existed — so an update must not change an agent nobody configured.
        assertEquals("off", 0, scene.viewModel.settings.commandSeconds)
        assertEquals("and no wait, which means wait for it", 0L, scene.viewModel.settings.commandMs)
        settings()
        compose.onNodeWithText("Hand a command over after", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `every agent can be switched off from settings`() {
        // The brief assumed this existed. The button is there when there is nothing on, and it is
        // greyed out — a switch that does nothing is worse than no switch.
        settings()

        compose.onNodeWithText("Nothing is running.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Stop all").performScrollTo().assertIsNotEnabled()
    }

}
