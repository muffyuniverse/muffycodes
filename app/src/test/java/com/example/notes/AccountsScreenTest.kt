package com.example.notes

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
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
 * **The main menu: the accounts on this phone** (§13c.5 step 2).
 *
 * The screen exists because the account stopped being a value in a settings page and became the thing
 * the menu is *about*. Three consequences are worth painting rather than asking the view model:
 *
 * - **The list is the menu**, so an account's project count has to be on the row — a bare login would
 *   make two accounts look identical and the count is the only thing that differs.
 * - **`+` is here and not in Settings**, which the operator moved, so its presence is the assertion.
 * - **Removal is a visible button**, not a long press: a row you can add to but never remove from is a
 *   row you work around by reinstalling.
 *
 * The tests are painted because none of these is a fact the view model holds — the bug would be "the
 * button was offered in the wrong place", which a state test cannot see.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class AccountsScreenTest {

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
        // **`fillMaxSize`, because the screen under test is `fillMaxSize` too.** A `Surface` that
        // wraps its content leaves the screen measured against nothing, and every node then exists
        // but is "not displayed" — a failure that reads like a missing element and is really an
        // unbounded parent. Taps passed while display assertions failed, which is what gave it away.
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) { content() }
        }
    }

    /** Paints the menu, recording which act was asked for so a tap can be proven to reach it. */
    private class Taps {
        var entered: String? = null
        var added = 0
        var removed: String? = null
        var connections = 0
        var settings = 0
    }

    private fun accounts(taps: Taps = Taps()) {
        compose.setContent {
            Frame {
                AccountsScreen(
                    viewModel = scene.viewModel,
                    onEnter = { taps.entered = it },
                    onAdd = { taps.added++ },
                    onRemove = { taps.removed = it },
                    onConnection = { taps.connections++ },
                    onSettings = { taps.settings++ },
                )
            }
        }
    }

    @Test
    fun `the menu lists the account, and its project count is the count it really has`() {
        // **The count is asserted against the model's own number**, because the *sentence* is
        // `projectCountText`'s to prove (below). What this adds is that the row is wired to the right
        // account's number and not to nothing — the failure it catches is a blank row, which is what
        // the first version of this screen would have drawn.
        scene.project("Shopping")
        val expected = projectCountText(
            scene.viewModel.accountsWithProjects.first { it.first.login == scene.login }.second,
        )
        accounts()

        compose.onNodeWithText(scene.login).assertIsDisplayed()
        compose.onNodeWithText(expected).assertIsDisplayed()
    }

    // ---- the sentence ------------------------------------------------------

    @Test
    fun `the count is singular for one and plural for the rest`() {
        // **Pure, so it does not depend on how many accounts the phone holds.** The painted version of
        // this assertion is ambiguous the moment there are two rows, and the ambiguity is about rows
        // rather than about the sentence.
        assertEquals("1 project", projectCountText(1))
        assertEquals("0 projects", projectCountText(0))
        assertEquals("2 projects", projectCountText(2))
        assertEquals("11 projects", projectCountText(11))
    }

    @Test
    fun `tapping an account enters it`() {
        // The whole point of the menu: a tap names the account, and the caller does the entering.
        val taps = Taps()
        accounts(taps)

        compose.onNodeWithText(scene.login).performClick()

        assertEquals("the row asked to enter its account", scene.login, taps.entered)
    }

    @Test
    fun `the plus adds an account and is on this screen`() {
        // **`+ Add` is here and not in Settings** — the operator moved it, because accounts are what
        // this screen is about. A settings-only Add would leave this list with no way to grow.
        val taps = Taps()
        accounts(taps)

        compose.onNodeWithText("+ Add").assertIsDisplayed().performClick()

        assertEquals("adding was asked for once", 1, taps.added)
    }

    @Test
    fun `remove is in the row's menu, and names its own account`() {
        // **Offered, not hidden behind a long press** — a row's own menu, which is the same door every
        // other row in this app uses and the only door to a credential. And it must name its own row:
        // a removal that took the wrong login is the one mistake here that loses a workspace.
        val taps = Taps()
        // **The first row in the model's own order**, which is the order the screen draws.
        val first = scene.viewModel.accountsWithProjects.first().first.login
        accounts(taps)

        compose.onAllNodesWithContentDescription("More")[0].performClick()
        compose.onNodeWithText("Remove…").assertIsDisplayed().performClick()

        assertEquals("the row named its own account", first, taps.removed)
    }

    @Test
    fun `every account row has its own menu`() {
        // **One per row, not one on the screen.** With two accounts there must be two menus; the
        // version of this screen that drew a single Remove would have removed whichever account the
        // code happened to pick.
        scene.viewModel.signIn("someone-else", "a-key", "", "")
        accounts()

        compose.onAllNodesWithContentDescription("More").assertCountEquals(2)
    }

    @Test
    fun `an account row offers its own model connection`() {
        // **The widest place a chat model may live.** A project inherits it, a branch may override it,
        // and it is asked of the account because a chat request is made *as* somebody.
        val taps = Taps()
        accounts(taps)

        compose.onAllNodesWithContentDescription("More")[0].performClick()
        compose.onNodeWithText("Model connection…").assertIsDisplayed().performClick()

        assertEquals("the row asked for its own window", 1, taps.connections)
    }

    @Test
    fun `Settings is reachable from the menu`() {
        // The account list is the main menu, so it is the only place Settings can be opened from.
        // A menu without it strands every local setting behind a project.
        val taps = Taps()
        accounts(taps)

        compose.onNodeWithText("Settings").performClick()

        assertEquals("settings was asked for once", 1, taps.settings)
    }

    @Test
    fun `removing the last real account leaves the local workspace, not a blank screen`() {
        // **The empty state is unreachable, and that is the design.** The local account cannot be
        // removed (§3.9: *"make ~local undeletable … so that local always exist"*), so the menu always
        // has at least one row. An empty list would mean a screen that could not act, and this is the
        // assertion that the floor is really there.
        scene.viewModel.removeAccount(scene.login, deleteCopies = true)
        accounts()

        compose.onNodeWithText("~local").assertIsDisplayed()
        compose.onNodeWithText("No accounts yet").assertDoesNotExist()
    }
}
