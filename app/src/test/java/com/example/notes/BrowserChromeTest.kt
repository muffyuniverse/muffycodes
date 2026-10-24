package com.example.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * What the browser offers, and where — the chrome around the files.
 *
 * These are the four things a person pointed at while using the app, each of which was a
 * control in a place that did not make sense: Sync one level above the worktree it syncs,
 * a Sync button that would do nothing, an Add button on a level that holds nothing a
 * person may add to, and a project that opened itself.
 *
 * They are painted rather than asked of the view model because *where a control appears*
 * is not a fact the view model holds. The bug was never "sync returned the wrong thing";
 * it was "sync was offered here".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class BrowserChromeTest {

    @get:Rule
    val compose = createComposeRule()

    @After
    fun putThePhoneAway() {
        scene.close()
    }

    private val scene: Scenario by lazy {
        Scenario(RuntimeEnvironment.getApplication(), signIn = true)
    }

    private fun browser() {
        compose.setContent {
            MaterialTheme {
                Surface {
                    BrowserScreen(
                        viewModel = scene.viewModel,
                        username = scene.viewModel.activeLogin,
                        onOpenSettings = {},
                        actions = BrowserActions(
                            onOpen = {},
                            onRename = {},
                            onMove = {},
                            onDelete = {},
                            onTogglePin = {},
                            onToggleLock = {},
                            onHistory = {},
                            onSelect = {},
                        ),
                        onAddProject = {},
                        onRowSync = {},
                        onRowRename = {},
                        onRowDelete = {},
                        onCreateFile = {},
                        onCreateFolder = {},
                        onUploadFiles = {},
                        onUploadFolder = {},
                        onRestoreDeleted = {},
                        onMoveSelected = {},
                        onDeleteSelected = {},
                        onReport = {},
                    )
                }
            }
        }
    }

    /** Compose keeps its own thread busy; "not there" needs the frame to have happened. */
    private fun there(text: String): Boolean =
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    private fun awaitText(text: String, present: Boolean = true) {
        compose.waitUntil(20_000) { there(text) == present }
    }

    @Test
    fun `the sync row appears inside the repo and not one level above it`() {
        scene.signedIn()
        scene.project("groceries")

        // The project's own folder: the wrapper. Nothing of the project is on screen,
        // so nothing can be said about what syncing it would send.
        scene.open(Workspace.PROJECTS, "groceries")
        browser()
        awaitText("up to date", present = false)
        assertFalse("no sync row in the wrapper", there("Sync"))

        // Inside the worktree, where the files are.
        scene.open(Workspace.PROJECTS, "groceries", Workspace.REPO)
        compose.waitUntil(20_000) { there("up to date") }
        assertTrue("the row is there with the worktree", there("up to date"))
    }

    @Test
    fun `sync cannot be pressed when nothing has changed`() {
        scene.signedIn()
        scene.project("groceries")
        scene.open(Workspace.PROJECTS, "groceries", Workspace.REPO)
        browser()

        awaitText("up to date")
        compose.onNodeWithText("Sync").assertIsNotEnabled()
    }

    @Test
    fun `sync is a counted, pressable button when something has changed`() {
        scene.signedIn()
        scene.project("groceries")
        scene.open(Workspace.PROJECTS, "groceries", Workspace.REPO)
        scene.worktreeFile("milk.txt", "two litres")
        scene.viewModel.refreshChanges()
        scene.await("the change to be counted", 20_000) {
            scene.viewModel.changesOf("groceries") > 0
        }
        browser()

        // The count is on the button, because it is the reason the button is there.
        awaitText("Sync 1")
        compose.onNodeWithText("Sync 1").assertIsEnabled()
        compose.onNodeWithText("Sync 1").performClick()
    }

    @Test
    fun `the account root offers nothing to add`() {
        scene.signedIn()
        scene.open()
        browser()

        // The root holds `projects/` and the app's own state. Both are the app's, so an
        // Add button there would promise something it cannot do.
        awaitText("projects")
        assertFalse("no Add at the account root", there("Add"))
    }

    @Test
    fun `a folder one level down does offer Add`() {
        // The falsification for the test above: hiding it everywhere would pass that
        // one and break the app.
        scene.signedIn()
        scene.project("groceries")
        scene.open(Workspace.PROJECTS, "groceries")
        browser()

        awaitText(Workspace.REPO)
        assertTrue("a real folder keeps its Add", there("Add"))
    }
}
