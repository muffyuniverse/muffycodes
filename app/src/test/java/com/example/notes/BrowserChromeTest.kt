package com.example.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
import java.io.File

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
                            onCopy = {},
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
                        onCopySelected = {},
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

    /**
     * The add button is an **icon**, so it carries a content description and no text.
     *
     * The first version of this test looked for the word "Add" among the *text* nodes and
     * found none anywhere — which made "no Add at the root" pass for the wrong reason and
     * "a folder does offer Add" fail against a screen that was right. Asking the same
     * question of the right thing is what makes either of them mean anything.
     */
    private fun thereIcon(description: String): Boolean =
        compose.onAllNodesWithContentDescription(description, substring = true)
            .fetchSemanticsNodes().isNotEmpty()


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
    fun `a locked file's menu offers copy and not rename`() {
        // Given a locked file
        scene.signedIn()
        scene.project("groceries")
        // **A folder of its own, with one file in it.** The worktree already holds the
        // clone's README and LICENSE, and `onAllNodesWithContentDescription("More")[0]`
        // is the *first row's* menu — which was the unlocked README, so the test was
        // reading a menu for a file it had not locked and concluding the lock leaked.
        scene.open(Workspace.PROJECTS, "groceries", Workspace.REPO)
        val only = scene.folder("only")
        // Written through the model, because `worktreeFile` puts a file at the root of the
        // worktree and the folder that is open is one level below it.
        val file = File(only, "milk.txt")
        scene.workspace!!.write(file, "two litres")
        scene.open(Workspace.PROJECTS, "groceries", Workspace.REPO, "only")
        scene.viewModel.toggleLocked(file)
        scene.viewModel.refresh()
        // **The premise, asserted.** Without this the test can only say "the menu offered
        // Rename", which is true both when the menu is wrong and when the file was never
        // locked — and those want different fixes.
        assertTrue("the file really is locked before the menu is opened", scene.viewModel.isLocked(file))
        browser()

        // When its menu is opened
        compose.waitUntil(20_000) { there("milk.txt") }
        // The only row, so "the first More" is the right one — see above.
        compose.onAllNodesWithContentDescription("More")[0].performClick()
        compose.waitForIdle()

        // Then copy is there — it changes nothing — and rename, which could only be
        // refused, is not
        assertTrue("copy is offered on a locked file", there("Copy…"))
        assertFalse("rename is not, because it would be refused", there("Rename…"))
    }

    @Test
    fun `the copy window calls the act copy, never move`() {
        // Given the window that picks a place, told the act is a copy
        val root = scene.workspace!!.accountDir
        compose.setContent {
            MaterialTheme {
                PlaceDialog(
                    title = "Copy milk.txt",
                    verb = "Copy",
                    root = root,
                    forbidden = emptyList(),
                    canChoose = { true },
                    onDismiss = {},
                    onPick = {},
                )
            }
        }

        // Then every word in it agrees, because one window serves both acts and a
        // wrong word would tell a person their original had gone
        compose.waitForIdle()
        assertTrue("the title says copy", there("Copy milk.txt"))
        assertFalse("and nothing says move", there("Move"))
    }

    @Test
    fun `the place window does not offer the app's own folders`() {
        // Reported from the live log: "move and clone allow to choose hidden folders at
        // the account root". `.muffy/` is where every flag lives and both acts refuse it,
        // so listing it could only end in a refusal after the person had picked it.
        val root = scene.workspace!!.accountDir
        java.io.File(root, ".muffy").mkdirs()
        java.io.File(root, "ordinary").mkdirs()

        compose.setContent {
            MaterialTheme {
                PlaceDialog(
                    title = "Move milk.txt",
                    root = root,
                    forbidden = emptyList(),
                    canChoose = { true },
                    onDismiss = {},
                    onPick = {},
                )
            }
        }

        compose.waitForIdle()
        assertTrue("a folder that can be moved into is offered", there("ordinary/"))
        assertFalse("the app's own bookkeeping is not", there(".muffy/"))
    }

    @Test
    fun `the account root offers nothing to add`() {
        scene.signedIn()
        scene.open()
        browser()

        // The root holds `projects/` and the app's own state. Both are the app's, so an
        // Add button there would promise something it cannot do.
        awaitText("projects")
        // Wait for the row to be painted before reading the absence as a decision.
        compose.waitForIdle()
        assertFalse("no Add at the account root", thereIcon("Add"))
    }

    @Test
    fun `a row does not call itself local`() {
        // Reported from the live log: "still have 'local' word under filenames ... 'local'
        // under GitHub/ is strange either way". At a project's root every row but the
        // clone is local by definition, so the word marked everything and said something
        // false about the one row that is not.
        scene.signedIn()
        scene.project("groceries")
        scene.open(Workspace.PROJECTS, "groceries")
        scene.folder("notes")
        browser()

        compose.waitUntil(20_000) { there("notes/") }
        assertFalse("no row is labelled local", there("local"))
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
        assertTrue("a real folder keeps its Add", thereIcon("Add"))
    }
}
