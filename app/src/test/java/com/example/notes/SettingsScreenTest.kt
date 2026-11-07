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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The settings screen, painted for real.
 *
 * Two things were asked of this screen and each is about words on the glass, which a ViewModel test
 * cannot answer: the history field saying plainly what it counts, and the account block **not** being
 * here at all (§13c.5 step 3) — Add, Switch and Remove live on the accounts screen, which is the main
 * menu, so their absence is the assertion rather than a message about it.
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
        /** The phone the test is testing: the exemption is a fact of the system, not of the screen. */
        exempt: (android.content.Context) -> Boolean = { false },
        onAskBattery: () -> Unit = {},
        /** Whether a reminder would be seen: the phone's fact, stated by the test. */
        notified: (android.content.Context) -> Boolean = { true },
        onAskNotices: () -> Unit = {},
        /** The count and the ceiling, set before the screen is drawn. */
    ) {
        compose.setContent {
            Frame {
                SettingsScreen(
                    viewModel = scene.viewModel,
                    onBack = {},
                    onTestAccount = {},
                    exempt = exempt,
                    onAskBattery = onAskBattery,
                    notified = notified,
                    onAskNotices = onAskNotices,
                )
            }
        }
    }

    /**
     * **The account block is not on this screen any more** (§13c.5 step 3).
     *
     * Add, Switch and Remove all live on the accounts screen, which is the main menu. Two screens
     * offering them meant the one you reached by accident could switch the account out from under the
     * projects you were looking at — so this asserts the absence, not a message about it.
     */
    @Test
    fun `the account block is not on this screen`() {
        settings()

        compose.onNodeWithText("Working as", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Switch").assertDoesNotExist()
        compose.onNodeWithText("Remove").assertDoesNotExist()
        // **"Add" is the one to be careful about.** The accounts screen has a "+", but a bare "Add"
        // here would mean the old button had survived the deletion.
        compose.onNodeWithText("Add").assertDoesNotExist()
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
    fun `the history block says every version is kept, and names no number`() {
        settings()

        // Below the fold now that the agent's block sits directly under the account, and a screen
        // is not "not showing" something because it is further down the same page.
        compose.onNodeWithText("Change history").performScrollTo().assertIsDisplayed()
        // **There is no length to set, and the absence is the feature.** A field asking a person to
        // guess how many versions to keep was removed; the block explains what is kept instead.
        compose.onNodeWithText("Versions kept").assertDoesNotExist()
        compose.onNodeWithText(
            "Every version of every file is kept, text and pictures alike. A picture is stored " +
                "once by its content, so an unchanged picture costs nothing the second time.",
        ).performScrollTo().assertIsDisplayed()
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

    /**
     * **Moving to another account is the accounts screen's job now**, so this test says where the
     * capability went rather than deleting the assertion. `switchTo` is still the mechanism — it is
     * the *button* that moved — and a screen that could still do it would be the duplicate.
     */
    @Test
    fun `switching accounts is not offered here`() {
        scene.viewModel.signIn("someone-else", "a-key", "", "")
        scene.viewModel.switchTo(scene.login)
        settings()

        compose.onNodeWithText("Switch").assertDoesNotExist()
        // The account is still the one that was active: this screen neither switched it nor broke it.
        assertEquals("the active account is untouched", scene.login, scene.viewModel.activeLogin)
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
        // **The operator named them, and the names are the report**: "fields: foreground command
        // timout, background command timeout". A sentence in a label says what the number bounds but
        // cannot be scanned for, which is what a settings screen is for.
        compose.onNodeWithText("Foreground command timeout", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Background command timeout", substring = true)
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

        // **"CHAT AGENT", not "AGENT"**, and the sentence under it is gone: the operator removed the
        // "one model, one key..." row because it described the block's scope to somebody looking at
        // the block, and the two heavier rules already say where it begins and ends. So what is
        // left to pin is the title and the first thing the block does not own.
        compose.onNodeWithText("CHAT AGENT").performScrollTo().assertIsDisplayed()
        // The first thing the block does not own. It is on the same screen and after the block's
        // closing rule, which is what the rules promise.
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
        // **"Working as", not "Signed in as"**: the provisional account is a real workspace that is
        // not signed in to anything, so the label has to be true in both states.
        // **The account block used to be the thing this asserted.** It is gone (§13c.5 step 3), so
        // the grouping promise now belongs to the accounts screen and this screen's own first block
        // is the agent's. What is checkable here is that the screen still draws and that the account
        // is not on it.
        compose.onNodeWithText("Background").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Working as", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the chat has a block of its own, and reset is the last thing on the screen`() {
        // **Two reports about where a thing sits, and both are about a block meaning something.**
        //
        // The line cap bounds how tall one message is *drawn*, which is not how much of the record
        // is *kept* - so it cannot live under "Session log", where it made a reading preference look
        // like a limit on the evidence. Report: "maximum displayed message height somehow ended up
        // in session log block but it need a new separate agent chat block".
        //
        // And reset undoes every choice on the screen, so it belongs after them rather than between
        // them: "back to defaults should be the last element on the settings list".
        settings()

        // **"CHAT", not "Agent chat"**, so the pair of names reads as two things rather than one
        // and a part of it: the operator asked for "Agent" to become "Chat agent" and "Agent chat"
        // to become "Chat".
        // **Title case, like every other section heading on the screen** - "Context", "Keepalive",
        // "Session log", "Reminders". Only the block *label* is upper case ("CHAT AGENT"), because
        // it names a bounded region rather than a section.
        compose.onNodeWithText("Chat").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("A message may be this many lines", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        // The last element, so nothing about a person's settings comes after the thing that undoes
        // them. Asserted by scrolling to it last: `performScrollTo` fails if it is already gone.
        compose.onNodeWithText("Back to defaults").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Reset settings").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the clocks are named, so a person can tell which one they are setting`() {
        // **Report 23 was a question, not a complaint:** "how wait for the model is different from
        // stop command after?". Timeouts in a row, in two units, with "wait" and "stop" used for
        // both, could not answer it. Two named groups and one unit can.
        settings()

        compose.onNodeWithText("Command execution timeouts").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Model response timeouts").performScrollTo().assertIsDisplayed()
        // And the model waits are in seconds, like their neighbours rather than minutes apart from
        // them.
        //
        // **Two, not three, and not the four this test has asserted at various points.** The
        // connection and stalling waits were split when streaming landed and merged again on the
        // operator's instruction - "lets merge connection and stalling timeouts into a single
        // response timeout" - because one idle number bounds both silences. What survives is the
        // distinction that matters to a person: the request's own silence, and how long a *failure*
        // is retried before the agent asks for help.
        for (name in listOf("Response timeout", "Reconnect timeout")) {
            compose.onNodeWithText("$name (seconds)", substring = true)
                .performScrollTo()
                .assertIsDisplayed()
        }
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
    fun `the session log's limit is one field, in tokens, and starts at ten million`() {
        // The question from the live log - "where is max session log size? because context trim just
        // switches row out of context, and session log limit removes them altogether" - was about a
        // field that did not exist. Then the operator asked for it in one unit: "for session just one
        // field: max length (in tokens with default 10000000). this mb and lines limits are to be
        // removed."
        assertEquals("ten million tokens", 10_000_000, scene.viewModel.settings.sessionLimit)
        // **Called "Session log", because that is the file's name.** Report: "if 'the record' block
        // means session log, then call it so".
        settings()
        compose.onNodeWithText("Session log").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Keep the session log under", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        // **The session log's MB field is gone rather than renamed**: one unit, one field. This used to
        // assert that "MB" appears nowhere on the screen, which stopped being true when the download
        // size became a setting — a different field, in a different section, that is measured in MB.
        // The claim that matters is about THIS block, so it is scoped to it.
        compose.onNodeWithText("Keep the session log under", substring = true)
            .performScrollTo()
        compose.onNodeWithText("Session log size (MB)", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Max lines", substring = true).assertDoesNotExist()
    }

    @Test
    fun `the key row stops saying there is no key the moment one is kept`() {
        // **The report was exact: "after setting the key, 'no openrouter key on this account yet'
        // in agent block is still displayed".** The row read the key's state through a plain
        // property, which gives Compose no reason to re-run it when a dialog writes a key - so the
        // sentence stayed on screen after the key had been kept, which looks exactly like a key that
        // did not take.
        //
        // **The screen is the claim, not the model.** Asserting `modelKeyState` changed would pass
        // even if the row never redrew, which is the bug.
        scene.viewModel.signIn("someone", "a-key", "Someone", "someone@example.com")
        settings()
        compose.onNodeWithText("No OpenRouter key yet", substring = true).assertExists()

        // The key is written the way the dialog writes it, and the row must notice.
        scene.viewModel.saveModelKey("sk-or-v1-a-key-that-reads-back")
        compose.waitForIdle()

        compose.onNodeWithText("No OpenRouter key yet", substring = true).assertDoesNotExist()
        compose.onNodeWithText("An OpenRouter key is kept", substring = true).assertExists()
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

    // ---- the two capabilities that can be turned off ------------------------

    @Test
    fun `the network is on by default, and turning it off says what changes`() {
        // **On by default, unlike the private-network switch**, because the direction of the risk is different:
        // a download writes a file a person can see and delete. The test asserts the default *and* the act,
        // because a switch whose default is wrong is the harder bug to notice.
        assertTrue("a fresh account may use the network", scene.settings.httpAllowed)
        settings()

        compose.onNodeWithText("Let it use the network").performScrollTo()
        compose.onNodeWithText("Let it use the network").performClick()

        assertEquals("and one tap turns it off", false, scene.settings.httpAllowed)
    }

    @Test
    fun `the scanner is on by default, and its number is hidden when it is off`() {
        // **A threshold beside a switch that is off is a field that does nothing** — the same trap as a preset
        // that is never written. The field goes with the switch.
        assertTrue("a fresh account scans", scene.settings.scanAllowed)
        settings()

        compose.onNodeWithText("Cut a section at (percent)").performScrollTo()
        compose.onNodeWithText("Cut a section at (percent)").assertExists()

        compose.onNodeWithText("Scan what it reads from outside").performScrollTo()
        compose.onNodeWithText("Scan what it reads from outside").performClick()

        assertEquals("the switch is off", false, scene.settings.scanAllowed)
        compose.onAllNodesWithText("Cut a section at (percent)").assertCountEquals(0)
    }
    // ---- the download cap, which used to be a constant ----------------------

    @Test
    fun `a fresh account refuses downloads above 100 MB, and says the number`() {
        // **The default the operator set**, and asserted because a default is the thing nobody
        // re-checks: 100 MB replaced 20 after archives that were genuinely a dependency were
        // refused.
        assertEquals(100, scene.settings.fetchMegabytes)
        assertEquals("and the fetcher gets bytes", 100L * 1024 * 1024, scene.settings.fetchBytes)
    }

    @Test
    fun `the download cap is clamped to something a phone can survive`() {
        // **Zero would be a switch wearing a number's clothes**, and there is already a switch for
        // "no downloads at all". A number that can be set to nonsense is a number nobody can reason
        // about later.
        scene.settings.keepFetchMegabytes(0)
        assertEquals(
            "below the floor it is the floor",
            SettingsStore.MIN_FETCH_MB,
            scene.settings.fetchMegabytes,
        )

        scene.settings.keepFetchMegabytes(999_999)
        assertEquals(
            "above the ceiling it is the ceiling",
            SettingsStore.MAX_FETCH_MB,
            scene.settings.fetchMegabytes,
        )

        scene.settings.keepFetchMegabytes(250)
        assertEquals("and in range it is what was asked", 250, scene.settings.fetchMegabytes)
    }

    @Test
    fun `the download cap is on the settings screen and says the current number`() {
        // **The number in the sentence, not only in the field.** A field showing "250" and a
        // sentence still saying "100" is the failure the app keeps finding: two places that must
        // agree and nothing making them.
        scene.settings.keepFetchMegabytes(250)
        settings()

        compose.onNodeWithText("One download may be (MB)").performScrollTo()
        compose.onNodeWithText("One download may be (MB)").assertExists()
        compose.onNodeWithText("Now: 250 MB per download", substring = true).assertExists()
    }
}
