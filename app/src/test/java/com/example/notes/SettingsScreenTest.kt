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

        // Below the fold now that the agent's block sits directly under the account, and a screen
        // is not "not showing" something because it is further down the same page.
        compose.onNodeWithText("Change history").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Versions kept").performScrollTo().assertIsDisplayed()
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
    fun `a command in the conversation holds the turn for three minutes, and one going to a file is left alone`() {
        // **The two defaults are different because the two acts are.** A foreground result is a row
        // the model is waiting to read, so three minutes is the operator's number; a background
        // result is a file nothing is waiting on, so it is left to finish.
        assertEquals("three minutes", 180, scene.viewModel.settings.foregroundSeconds)
        assertEquals("which is a limit", 180_000L, scene.viewModel.settings.foregroundMs)
        assertEquals("off for a background command", 0, scene.viewModel.settings.backgroundSeconds)
        assertEquals("and no limit there", 0L, scene.viewModel.settings.backgroundMs)
        settings()
        // **The words say what the numbers bound, which is the whole of report 23.** "Stop a
        // command after" described a stop that no longer happens - a command past this now carries
        // on in the background - and neither label said which machine was being timed.
        compose.onNodeWithText("A command may hold the conversation for", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Stop a background command after", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `the two clocks are named, so a person can tell which one they are setting`() {
        // **Report 23 was a question, not a complaint:** "how wait for the model is different from
        // stop command after?". Three timeouts in a row, in two units, with "wait" and "stop" used
        // for both, could not answer it. Two named groups and one unit can.
        settings()

        compose.onNodeWithText("While the app works on this phone").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("While it talks to the model").performScrollTo().assertIsDisplayed()
        // And the model wait is in seconds, like its neighbours rather than minutes apart from them.
        compose.onNodeWithText("Wait for an answer for (seconds)", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        // **The limit of the number is said, not hidden.** With `stream: false` a dropped request and
        // a thinking model really are the same silence, so the screen says so rather than implying
        // the number is enough. When streaming lands this sentence is deleted - and deleting it is
        // part of that work, recorded in docs/PLAN-streaming.md, not something this test should
        // silently require.
        compose.onNodeWithText("look the same from here", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `the keepalive is a switch, and its two numbers are behind it`() {
        // **On by default, because it was already happening**: an agent that said `wait` was polled
        // every four minutes, and this replaces that poll with something cheaper. Off, the two fields
        // go with it — a number that nothing reads is a number a person sets twice.
        assertEquals("on out of the box", true, scene.viewModel.settings.keepAlive)
        assertEquals("an hour of it", 60, scene.viewModel.settings.warmMinutes)
        settings()

        compose.onNodeWithText("Keepalive").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Keep the conversation warm").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Warm it every", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("For at most", substring = true).performScrollTo().assertIsDisplayed()

        compose.onNodeWithText("Keep the conversation warm").performScrollTo().performClick()
        compose.waitForIdle()

        assertEquals("switched off", false, scene.viewModel.settings.keepAlive)
        compose.onNodeWithText("Warm it every", substring = true).assertDoesNotExist()
        assertEquals("and nothing in a store was disturbed", 240_000L, scene.viewModel.settings.pollMs)
    }

    @Test
    fun `the record's own limit is a row, and it starts as "keep everything"`() {
        // The question from the live log — "where is max session log size? because context trim just
        // switches row out of context, and session log limit removes them altogether" — was a
        // question about a field that did not exist. It exists now, it defaults to keeping the whole
        // record, and the screen says which act is which.
        assertEquals("keeping everything", 0, scene.viewModel.settings.sessionMb)
        assertEquals("which is no bytes at all", 0L, scene.viewModel.settings.sessionBytes)
        settings()
        compose.onNodeWithText("The record").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Keep the record under", substring = true)
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
