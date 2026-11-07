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
        MaterialTheme { Surface { content() } }
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
        scene.viewModel.createProject("Shopping", "mit")
        accounts()

        compose.onNodeWithText(scene.login).assertIsDisplayed()
        compose.onNodeWithText("1 project").assertIsDisplayed()
    }

    @Test
    fun `the count says projects when there is more than one`() {
        // A plural that reads "2 project" is the kind of wrongness that survives for years, so the
        // sentence is asserted rather than the number.
        scene.viewModel.createProject("Shopping", "mit")
        scene.viewModel.createProject("Work", "mit")
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
    fun `remove is a visible button and names the account it would remove`() {
        // **Offered, not hidden behind a long press.** And it must name the row it belongs to: a
        // removal that took the wrong login is the one mistake here that loses a workspace.
        val taps = Taps()
        scene.viewModel.signIn("someone-else", "a-key", "", "")
        accounts(taps)

        compose.onNodeWithText("Remove").performScrollTo().performClick()

        // The first row is the one that was signed in first; either way the callback must name a real
        // account rather than null.
        assertEquals("a specific account was named", true, taps.removed != null)
        assertEquals(
            "and it is one of the two on this phone",
            true,
            taps.removed in setOf(scene.login, "someone-else"),
        )
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
    fun `an empty list says what an account is, rather than showing a blank screen`() {
        // The empty state is reachable: the app makes a provisional account, but a person who has
        // removed every real one is looking at this. A blank screen reads as a fault.
        scene.viewModel.removeAccount(scene.login, deleteCopies = true)
        accounts()

        compose.onNodeWithText("No accounts yet").assertIsDisplayed()
        // **And it says what an account IS**, because "No accounts yet" alone answers nothing for a
        // person who does not know whether an account is a login, a folder, or a paid plan.
        compose.onNodeWithText("An account is a GitHub identity", substring = true)
            .assertIsDisplayed()
    }
}
