package com.example.notes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * What the browser offers, and where — the chrome around the files.
 *
 * These are the four things a person pointed at while using the app, each of which was a
 * control in a place that did not make sense: Sync one level up from the files it syncs, a
 * Sync button that would do nothing, an Add button on a level that holds nothing a person
 * may add to, and a project that opened itself.
 *
 * They are painted rather than asked of the view model because *where a control appears*
 * is not a fact the view model holds. The bug was never "sync returned the wrong thing";
 * it was "sync was offered here".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
// **Native graphics, and it is what makes a dialog testable at all.** `AlertDialog` composes into a
// window of its own; under the default legacy graphics that window never draws, so every semantics
// read waits for an idle that never arrives. `ScenariosTest` has always had this annotation, which is
// why its thirteen window scenarios pass while this class's first one hung - the difference was never
// the text field, the `Surface`, or the clock, all of which were tried and none of which mattered.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
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
                            // **The real act, not an empty one.** This harness stubs every
                            // callback, and a stub here made the lock test tap a no-op and then
                            // assert the app had done nothing — which it had. The bug being tested
                            // is that the *screen* does not redraw, so the write has to happen.
                            onToggleLock = { scene.viewModel.toggleLocked(it.file) },
                            onHistory = {},
                            onSelect = {},
                        ),
                        onAddProject = {},
                        onRowChat = {},
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

    /** The same question about an icon, which carries a description and no text. */
    private fun described(text: String): Boolean =
        compose.onAllNodesWithContentDescription(text, substring = true)
            .fetchSemanticsNodes().isNotEmpty()

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
    fun `saving a file leaves the project with something to send`() {
        // Reported from the live log, twice: "changed a file, saved but sync is still grey
        // and inactive and new change isnt reported". Every change is committed as it
        // happens, so git reports a clean worktree the instant a save lands — and the row
        // was built from that, so a save looked like nothing at all and Sync stayed grey.
        // A save is exactly the thing a person syncs.
        scene.signedIn()
        scene.project("groceries")
        // Written through the harness, which needs the screen to be *inside* the project
        // before it can say which worktree to put the file in.
        scene.open("groceries")
        val file = scene.worktreeFile("milk.txt", "two litres")
        scene.viewModel.refreshChanges()
        scene.await("the new file to be counted") { scene.viewModel.changesOf("groceries") > 0 }

        scene.viewModel.openEditor(file)
        scene.viewModel.saveEditor("three litres")
        scene.await("the save to be committed", 20_000) { file.readText() == "three litres" }

        // The worktree is now clean by git's lights — the commit happened — and the work is
        // still only on this phone. Both are true at once, and the row has to say the
        // second.
        assertEquals(
            "a saved file is still work GitHub does not have",
            1,
            scene.viewModel.changesOf("groceries"),
        )
    }

    @Test
    fun `the sync row appears inside a project and not at the root`() {
        scene.signedIn()
        scene.project("groceries")

        // The root is the projects list: the sync of one project said from there would be a
        // sentence about a repository nobody is looking at. **The words "up to date" are not the
        // test** — a project's row at the root says them too, about itself. What must not be here
        // is the slim row and its button.
        browser()
        compose.waitForIdle()
        assertFalse("no sync row at the root", there("Sync"))

        // Inside the project, where its files are.
        scene.open("groceries")
        compose.waitUntil(20_000) { there("up to date") }
        assertTrue("the row is there with the project", there("up to date"))
    }

    @Test
    fun `sync cannot be pressed when nothing has changed`() {
        scene.signedIn()
        scene.project("groceries")
        scene.open("groceries")
        browser()

        awaitText("up to date")
        compose.onNodeWithText("Sync").assertIsNotEnabled()
    }

    @Test
    fun `sync is a pressable button when something has changed, and it carries no number`() {
        scene.signedIn()
        scene.project("groceries")
        scene.open("groceries")
        scene.worktreeFile("milk.txt", "two litres")
        scene.viewModel.refreshChanges()
        scene.await("the change to be counted", 20_000) {
            scene.viewModel.changesOf("groceries") > 0
        }
        browser()

        // **One thing, and it is a button.** There used to be a caption reading "1 to send" and a
        // button reading "See changes" beside it — the same fact twice, and the half a person would
        // want to press was the one without the number. Report 30 asked for them to be one:
        // "see <n> changes" *is* the button, and there is no separate caption any more.
        awaitText("See 1 change")
        compose.onNodeWithText("Sync").assertIsEnabled()
        compose.onNodeWithText("Sync").performClick()
    }

    @Test
    fun `the count is every change, not just "something"`() {
        // Reported from the live log: "sync always shows 1 to send even when where are many
        // changes". The first fix recorded a flag, so any number of changes read as one.
        scene.signedIn()
        scene.project("groceries")
        scene.open("groceries")
        val ws = scene.workspace!!
        val worktree = ws.repoOf("groceries")
        // Three saves, through the app's own path — a save is the change, and it is what
        // records one. Writing the files behind its back would leave the count at zero and
        // the test would be measuring the harness.
        listOf("milk.txt", "bread.txt", "eggs.txt").forEachIndexed { i, name ->
            val file = File(worktree, name)
            ws.write(file, "first")
            scene.viewModel.openEditor(file)
            scene.viewModel.saveEditor("second")
            // **One more file, one more to send.** Counted from the repository rather than from a
            // number the app keeps: a diff against the last push, so a batch that moved ten files
            // reads "10" and two saves of one file read "1".
            scene.await("save ${i + 1} to be recorded", 20_000) {
                ProjectGit(ws, { null }, { GitAuthor("Muffy", "muffy@example.com") })
                    .changes("groceries").ahead == i + 1
            }
        }
        scene.viewModel.closeEditor()
        scene.viewModel.refreshChanges()
        scene.await("the row to count all three", 20_000) {
            scene.viewModel.changesOf("groceries") == 3
        }

        assertEquals("three changes, three to send", 3, scene.viewModel.changesOf("groceries"))
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
        scene.open("groceries")
        val only = scene.folder("only")
        // Written through the model, because `worktreeFile` puts a file at the root of the
        // worktree and the folder that is open is one level below it.
        val file = File(only, "milk.txt")
        scene.workspace!!.write(file, "two litres")
        scene.open("groceries", "only")
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
    fun `the lock icon changes on the tap, not on the next visit`() {
        // **Reported from the live log:** "lock button changes show only after reentering the
        // screen". The row asked `.muffy/locked.json` on every draw, so the icon it showed was a
        // fact Compose had no reason to recompose for: the file changed, nothing observable did,
        // and the answer only appeared when the screen was rebuilt.
        //
        // This test is the recomposition, not the write — `isLocked` already passed while the
        // screen was wrong, which is why the bug survived.
        scene.signedIn()
        scene.project("groceries")
        scene.open("groceries")
        val file = scene.worktreeFile("milk.txt", "two litres")
        // **The list is built from `entries`, which is a snapshot.** A file written behind the
        // screen's back is not in it until something refreshes — that is the app working as
        // designed, and without this the row the test taps does not exist.
        scene.viewModel.refresh()
        browser()
        compose.waitUntil(20_000) { there("milk.txt") }

        // Before: unlocked, so the content description offers the lock. Asked as a description
        // rather than as text, because the control is an icon.
        assertTrue(
            "the row starts unlocked",
            described("Lock milk.txt, so it can only be viewed"),
        )

        // The tap, made on the screen rather than through the view model, because the bug was
        // that the screen did not notice. No `refresh()` after it: that is the thing a person
        // could not do.
        compose.onNodeWithContentDescription("Lock milk.txt, so it can only be viewed")
            .performClick()
        compose.waitForIdle()

        assertTrue("and it is locked", scene.viewModel.isLocked(file))
        assertTrue(
            "the row says so without leaving the screen",
            described("milk.txt is locked; tap to unlock"),
        )
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
        scene.project("groceries")
        scene.open()
        browser()

        // The root is the list of projects. A project is made by adding one — from GitHub,
        // or on this phone — not by making a file to stand beside the projects, which nothing
        // on this screen would show. So no Add button here is a decision, not an omission.
        awaitText("groceries")
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
        scene.open("groceries")
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
        scene.open("groceries")
        // A folder of the person's own, one level below the project, with something in it — an
        // empty folder is not a version, and this test is about a place a person can add to.
        scene.folder("letters")
        scene.open("groceries", "letters")
        scene.file("note.txt", "in here")
        browser()

        awaitText("note.txt")
        assertTrue("a real folder keeps its Add", thereIcon("Add"))
    }

    // ---- the three windows nothing was asserting the words of ------------------

    @Test
    fun `the publishing window names the direction and the licence it will use`() {
        // **The direction is the whole risk.** The same row offers the opposite act when a project
        // is already synced, so a window that did not say which way it goes could publish something
        // a person meant to keep private. And a default licence that is not shown is a decision made
        // on their behalf.
        compose.setContent {
            MaterialTheme {
                PublishProjectDialog(
                    project = "groceries",
                    licences = listOf(
                        Licence("gpl-3.0", "GNU General Public License v3.0"),
                        Licence("mit", "MIT License"),
                    ),
                    onDismiss = {},
                    onPublish = {},
                )
            }
        }

        compose.waitForIdle()
        assertTrue("it says where the project is going", there("Put groceries on GitHub"))
        // The licence's own NAME, not its key: the window says "Licence: GNU General Public
        // License v3.0", and a test that looked for "gpl-3.0" would be asserting the wire format
        // rather than the sentence a person reads.
        assertTrue("and which licence it will publish under", there("Licence: GNU General Public"))
        assertFalse("and it is not the keep-local window", there("only on this phone"))
    }

    @Test
    fun `the keep-local window promises that nothing is deleted`() {
        // **The sentence that makes the choice safe.** A person deciding to stop syncing needs to
        // know their history survives and their repository is still there; a window that only said
        // "keep local" would read as "delete the remote".
        compose.setContent {
            MaterialTheme {
                KeepLocalDialog(project = "groceries", onDismiss = {}, onKeepLocal = {})
            }
        }

        compose.waitForIdle()
        assertTrue("it names the project", there("groceries"))
        assertTrue("and says nothing is removed", there("stays here"))
        assertTrue("including the repository", there("stays on GitHub"))
        assertTrue("while pointing at the act that does delete", there("Delete everywhere"))
        assertFalse("and it is not the publishing window", there("Put groceries on GitHub"))
    }

    @Test
    fun `the sign-in window masks the token and says where it is kept`() {
        // **The one secret the app asks for.** A field that echoed the token would leak it to
        // anybody glancing at the screen, and a window that did not say where it goes would leave a
        // person guessing whether it is being uploaded somewhere.
        //
        // **Built the way the twelve working window scenarios build theirs**: a `Box` behind the
        // dialog inside a `Surface`, which is what `window {}` in the scenario file expands to. That
        // shape matters - `AlertDialog` composes into a window of its own, and a test that put it
        // alone in `setContent` waited sixty seconds for an idle that never came, three times, with
        // the clock frozen and unfrozen and with a bare `Surface` behind it. The difference was never
        // the text field: `ModelKeyDialog` has a password field in a dialog and its scenario passes.
        compose.setContent {
            MaterialTheme {
                Surface {
                    Box(Modifier.fillMaxSize()) {}
                    SignInDialog(onDismiss = {}, onSignIn = {})
                }
            }
        }
        compose.waitForIdle()

        assertTrue("it asks for the key", there("Personal access key"))
        assertTrue("and names the scopes it needs", there("repo and delete_repo"))
        assertTrue("and says whose commits they will be", there("belongs to"))
        assertTrue("it names GitHub in the title", there("Sign in to GitHub"))
        // **The button is "Sign in", the title is "Sign in to GitHub"** - two different strings,
        // and the button is disabled until something is typed, which is the rule worth stating:
        // a window that let you press it empty would ask GitHub a question with no answer.
        assertTrue("and offers the act", there("Sign in"))
    }
}
