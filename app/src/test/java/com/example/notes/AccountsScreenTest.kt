package com.example.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
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
            Surface(modifier = androidx.compose.foundation.layout.fillMaxSize) { content() }
        }
    }

    /** Paints the menu, recording which act was asked for so a tap can be proven to reach it. */
    private class Taps {
        var entered: String? = null
        var added = 0
        var removed: String? = null
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
                    onSettings = { taps.settings++ },
                )
            }
        }
    }

    @Test
    fun `the menu lists each account with how many projects it has`() {
        // **The count is the row's whole content beyond the login.** Two accounts with one project
        // each and two accounts with none look identical without it, and the person is choosing
        // between workspaces, not between names.
        scene.project("Shopping")
        accounts()

        compose.onNodeWithText(scene.login).assertIsDisplayed()
        compose.onNodeWithText("1 project").assertIsDisplayed()
    }

    @Test
    fun `the count says projects when there is more than one`() {
        // A plural that reads "2 project" is the kind of wrongness that survives for years, so the
        // sentence is asserted rather than the number.
        scene.project("Shopping")
        scene.project("Work")
        accounts()

        compose.onNodeWithText("2 projects").assertIsDisplayed()
    }

    @Test
    fun `an account with no projects says so, rather than showing nothing`() {
        // The row has to exist and read as an account with an empty workspace — not vanish, which
        // would make a fresh account look like it had not been created.
        accounts()

        compose.onNodeWithText(scene.login).assertIsDisplayed()
        compose.onNodeWithText("0 projects").assertIsDisplayed()
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
    fun `remove is a visible button that names its own account`() {
        // **Offered, not hidden behind a long press.** And it must name its own row: a removal that
        // took the wrong login is the one mistake here that loses a workspace.
        val taps = Taps()
        accounts(taps)

        compose.onNodeWithText("Remove").performScrollTo().performClick()

        assertEquals("the row named its own account", scene.login, taps.removed)
    }

    @Test
    fun `every account row offers its own remove`() {
        // **One per row, not one on the screen.** With two accounts there must be two buttons; the
        // version of this screen that drew a single Remove would have removed whichever account the
        // code happened to pick.
        scene.viewModel.signIn("someone-else", "a-key", "", "")
        accounts()

        compose.onAllNodesWithText("Remove").assertCountEquals(2)
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
        // **The empty state is unreachable, and that is the design.** `removeAccount` makes sure the
        // provisional account exists rather than assuming it does, because a sign-in can adopt it
        // away — so the menu always has at least one row. An empty list would mean a screen that
        // could not act, and this is the assertion that the floor is really there.
        scene.viewModel.removeAccount(scene.login, deleteCopies = true)
        accounts()

        compose.onNodeWithText("~local").assertIsDisplayed()
        compose.onNodeWithText("No accounts yet").assertDoesNotExist()
    }
}
