package com.example.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
    fun `the agent's settings are one block, bounded by a rule at each end`() {
        // **Reports 20 and 21, which are one complaint about the same thing:** the screen said
        // "everything the agent needs is in one place" and then drew reminders, the trace and the
        // theme at the same rhythm, so the block had a beginning nobody could see and no end at all.
        //
        // What can be checked from a test is that the block still *reads* as a block: its title, the
        // sentence that names the rules, and the first thing outside it. A rule's colour and weight
        // are not visible to `onNodeWithText`, so this pins the words - which is what tells a person
        // where the block starts and stops. The divider itself is checked by the eye, and the sentence
        // is the thing that must not drift out of step with it.
        settings()

        compose.onNodeWithText("AGENT").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Everything between this rule", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        // The first thing the block does not own. It is on the same screen and after the block's
        // closing rule, which is what the sentence above promises.
        compose.onNodeWithText("Reminders").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the signed-in rows are one block, so the buttons sit with the name`() {
        // **Report 21, twice reported:** "between user account and buttons below it is too much
        // space", then "signed in as block still has gaps between rows that should be halved at
        // least". The second is the useful one: what was left was the gap between the three *lines*.
        //
        // A spacing value is not visible to a text query, so what this pins is that all three lines
        // are present and in order - the name, the address, the buttons - which is the grouping the
        // spacing exists to express. A change that separated them again would have to move one of
        // these out of the column.
        settings()

        // **The signed-in scene, not the signed-out one.** `Scenario` signs in, so the two fallback
        // strings ("Nobody yet", "Sign in to create projects") never render here - and asserting
        // them would have been a test that passes only by never being run against this scene. What
        // the grouping promises is that the label, the account and the buttons are one thing, and
        // that is checkable with what is on the screen.
        compose.onNodeWithText("Signed in as").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Switch").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Remove").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Add").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the two clocks are named, so a person can tell which one they are setting`() {
        // **Report 23 was a question, not a complaint:** "how wait for the model is different from
        // stop command after?". Three timeouts in a row, in two units, with "wait" and "stop" used
        // for both, could not answer it. Two named groups and one unit can.
        settings()

        compose.onNodeWithText("While the app works on this phone").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("While it talks to the model").performScrollTo().assertIsDisplayed()
        // And the model waits are in seconds, like their neighbours rather than minutes apart from
        // them. **Two of them now**: streaming split one silence into "nothing came back at all"
        // and "it went quiet halfway through", which want opposite answers and so cannot share a
        // number.
        compose.onNodeWithText("Wait for an answer to begin (seconds)", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Wait between pieces of an answer (seconds)", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        // **And the sentence that apologised for the old limit is gone.** It said a dropped request
        // and a model thinking hard look the same from here, which was true of a non-streamed
        // request. It is not true now, and leaving it would be the screen explaining a limitation
        // the app no longer has. This assertion is the one that would have caught the split landing
        // without the sentence being deleted - the work is only half done while both are on screen.
        compose.onAllNodesWithText("look the same from here", substring = true)
            .assertCountEquals(0)
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
    fun `a dev build names itself in the trace, so a report cannot be mistaken for a release`() {
        // **The build column is how a report is placed in time.** A dev build carries the live
        // trace, can make a test account, and is where a bug is actually found - so a log reading
        // `149` for both the shipped app and the dev copy would make "which build produced this"
        // unanswerable at the moment somebody is trying to answer it.
        //
        // The suite runs against a debug build, which is the case asserted here. The release half is
        // the same expression with `DEBUG` false, so what is checked is that the suffix is present
        // and that it does not disturb the column's shape.
        val name = scene.viewModel.buildName

        assertTrue("a dev build is marked: $name", name.endsWith("-dev"))
        // **One token, because it is a column.** A space would make the build read as part of the
        // state, which is the next column along.
        assertEquals("and it is still one word", 1, name.split(" ").size)
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
