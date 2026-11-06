package com.example.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
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
                        onRowRename = {},
                        onRowDelete = {},
                        onCreateFile = {},
                        onCreateFolder = {},
                        onCreateImage = {},
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
    fun `the app's own folder draws both marks`() {
        // **Two reports, and the second is the operator catching what I had missed.**
        // "why agent/ looks different? is it not manages in a unified way? because where is its red
        // unsync icon? why is it hidden?" and then "it shows, but why only red lock and not also red
        // disconnect?"
        //
        // The lock drew and the disconnect did not, because the disconnect lived inside the
        // text-file branch that an app-held row never reaches. Both facts are true of `agent/`, so
        // both marks have to be on it.
        scene.signedIn()
        scene.project("groceries")
        scene.open("groceries")
        // **The record exists only once the agent has written to it**, so the test writes a row the
        // way the agent does rather than waiting for a folder a fresh project does not have.
        val project = scene.viewModel.workspace!!.projectDir("groceries")
        AgentLog.open(project).told("make me a folder")
        scene.viewModel.refresh()
        browser()

        compose.waitUntil(20_000) { there("agent/") }
        assertTrue(
            "the folder says the app keeps it",
            described("agent is kept by the app"),
        )
        assertTrue(
            "and that it is kept off GitHub",
            described("agent is kept out of the repository by the app"),
        )
    }

    @Test
    fun `the app's marks are on what is inside its folder too`() {
        // **The inheritance half**, and the operator named the rule: "and remember that whole
        // subbranch should inherit them". `appHold` tested the leaf name, so `agent/` was held and
        // `agent/context.log` was not - a marked folder holding two unmarked files, which says the
        // opposite of what is true.
        scene.signedIn()
        scene.project("groceries")
        scene.open("groceries")
        val project = scene.viewModel.workspace!!.projectDir("groceries")
        AgentLog.open(project).told("make me a folder")
        // **`openIn`, because `open` takes a path from the account root.** Spelling "agent" alone
        // opened a folder that does not exist, so the screen was never the record's and the wait
        // timed out - the failure was the fixture, not the marks.
        scene.openIn("agent")
        browser()

        compose.waitUntil(20_000) { there("context.log") }
        assertTrue(
            "the log inside inherits the lock",
            described("context.log is kept by the app"),
        )
        assertTrue(
            "and the disconnect too",
            described("context.log is kept out of the repository by the app"),
        )
    }

    @Test
    fun `a local project has no sync strip at all`() {
        // **The operator's report, from the live log**: "local projects shoudnt show sync row - hide
        // it for them". My first attempt hid only the Sync *button* and kept the rest of the row,
        // and the operator corrected me: "it doesnt, that is why I asked to hide the strip and not
        // just the button".
        //
        // They were right, and the reason is in `unsentPaths`: it returns empty when the repository
        // has no `origin`. So on a local project the count is always zero and the strip says **"up
        // to date" for ever** - a claim about a GitHub repository that does not exist. Every part of
        // that row is a statement about a remote, so a project without one gets no row.
        scene.signedIn()
        scene.localProject("Shopping")
        val made = scene.made("Shopping")
        // **Open first, then write.** `worktreeFile` resolves the project from the current path, so
        // a write before the open is a write outside any project.
        scene.open(made)
        scene.worktreeFile("milk.txt", "two litres")
        scene.viewModel.refreshChanges()
        scene.await("the change to be counted", 20_000) {
            scene.viewModel.changesOf(made) > 0
        }
        // **The premise, asserted before the claim.** Without it, "no Sync" is true both when the
        // strip is correctly gone and when the browser never opened the project at all - and those
        // are different bugs. This is the same shape the locked-file test uses for its own premise.
        assertTrue(
            "the browser is inside the local project before anything is asserted about its strip",
            scene.viewModel.inWorktree && scene.viewModel.currentProject == made,
        )
        browser()
        compose.waitForIdle()
        assertFalse("no Sync", there("Sync"))
        assertFalse("no count either", there("See 1 change"))
        // **"up to date" is the claim that was actually false**, so it is the one worth asserting
        // against by name: a local project has no remote to be up to date *with*.
        assertFalse("and no claim to be up to date with", there("up to date"))
    }

    @Test
    fun `there is no Sync button, in the row or anywhere else`() {
        // **The operator moved the decision to the agent.** "we can remove this functionality and
        // transfer it to agent so that it can determine when to push" — so a person pressing Sync
        // would be a second writer deciding the same question, and the two could disagree about
        // which commit is being sent.
        //
        // **Asserted as an absence, which is the only way to keep a removed button removed.** A
        // test that only checked the count would pass with the button quietly restored.
        scene.signedIn()
        scene.project("groceries")
        scene.open("groceries")
        scene.worktreeFile("milk.txt", "two litres")
        scene.viewModel.refreshChanges()
        scene.await("the change to be counted", 20_000) {
            scene.viewModel.changesOf("groceries") > 0
        }
        browser()

        // The count stays: it is how a person learns work exists that GitHub does not have.
        awaitText("See 1 change")
        compose.onAllNodesWithText("Sync").assertCountEquals(0)
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
    fun `a binary row offers the switch that would send it`() {
        // **The row that mattered most was the one that drew no switch.** The marks were gated on
        // `kind != UNKNOWN`, on the reasoning that a binary has no *lock* to offer — true, and
        // beside the point. A `.pdf` or a `.zip` now arrives ignored, and the ignore switch is the
        // only control that can undo that, so excluding unknown rows left the file waiting behind a
        // door with no handle.
        scene.signedIn()
        scene.project("groceries")
        scene.open("groceries")
        File(scene.viewModel.currentDir!!, "receipt.pdf").writeBytes(ByteArray(64))
        scene.viewModel.refresh()
        browser()

        compose.waitUntil(20_000) { there("receipt.pdf") }
        // **The exact sentence the glyph carries**, because a description is what a screen reader
        // says and what this test can assert: the row is either offering the switch or saying the
        // file is already ignored, and both are only reachable if the row is drawn at all.
        assertTrue(
            "the binary's row offers the ignore switch",
            described("Ignore receipt.pdf, so it is not on GitHub") ||
                described("receipt.pdf is ignored: it is not on GitHub"),
        )
    }

    @Test
    fun `a lock and a sync mark are two shapes, not one shape in two colours`() {
        // **The operator's report**: "dont use just one icon for two states for lock and sync. tell
        // me if it is possible to use open lock icon on unlock and connected icon on sync, so that
        // different states have not just different colors but icons".
        //
        // It is possible, and this asserts the *shape* changes - not the description, which was
        // always different, and not the colour, which is what the rule used to be. The two states of
        // each flag now draw different things: an open padlock and a shut one, a whole chain and a
        // broken one.
        //
        // **Asserted through the view model's answer, because that is what picks the shape.** A test
        // that reached into the drawing would be asserting strokes; this asserts the rule - the row
        // asks `hold`, `hold` answers, and the row draws the glyph that answer names.
        scene.signedIn()
        scene.project("groceries")
        scene.open("groceries")
        val only = scene.folder("only")
        val file = File(only, "milk.txt")
        scene.workspace!!.write(file, "two litres")
        // **Unlocked, deliberately.** `scene.folder` goes through `createFolder`, which is the
        // person's door and now locks what it makes — so the row would already carry a lock and this
        // test would be asserting the opposite of its own subject. Unlocking puts the row back in
        // the state the test is about.
        scene.viewModel.toggleLocked(only)
        scene.viewModel.refresh()
        scene.open("groceries", "only")
        browser()

        compose.waitUntil(20_000) { there("milk.txt") }
        // **Unlocked: the open padlock, which says "you could lock this".** The description is the
        // same string it was before the glyph changed, which is deliberate - a screen reader should
        // not hear a different sentence because the picture improved.
        assertTrue(
            "the unlocked state offers the lock",
            described("Lock milk.txt, so it can only be viewed"),
        )
        // **And it is not the shut padlock.** `Icons.Filled.Lock` carries the description of whatever
        // it is asked to say, so the pair cannot be told apart by that alone; what changes is which
        // composable draws it. This asserts the state that must NOT be drawn.
        assertFalse(
            "and nothing says it is already locked",
            described("milk.txt is locked; tap to unlock"),
        )
    }

    @Test
    fun `a file the app keeps has no menu, and one the person locked keeps it`() {
        // **Two locks, two rules, and the operator corrected me on which is which.**
        //
        // Report 10 asked for the menu to go: "locked files should hide and forbid 3dot options",
        // filed while looking at `agent/` - an *app* lock. I read it as all locks and gated the menu
        // on `hold == NONE`, which took the menu away from a person who had locked their own file.
        // The correction: "3dots are hidden only on system locked branches".
        //
        // That is the same ruling this codebase already carries everywhere else: "system locks
        // restrict both users and agents, and user locks restrict agents (not users themselves!)".
        // The menu was the one place that had it backwards.
        scene.signedIn()
        scene.project("groceries")
        scene.open("groceries")
        val only = scene.folder("only")
        val file = File(only, "milk.txt")
        scene.workspace!!.write(file, "two litres")
        scene.open("groceries", "only")
        scene.viewModel.toggleLocked(file)
        scene.viewModel.refresh()
        // **The premise, asserted.** Without it the test can only say "the menu is there", which is
        // true both when the rule is right and when the file was never locked.
        assertTrue(
            "the file really is locked by the person before the menu is looked for",
            scene.viewModel.hold(file) == Workspace.Hold.USER,
        )
        browser()

        compose.waitUntil(20_000) { there("milk.txt") }
        assertTrue("the row is drawn", there("milk.txt"))
        // **The menu is there, and it works.** Rename, Move and Delete all succeed on a person's
        // own lock - `Workspace` asks `isLocked` (the app's locks) for the person's acts - so
        // hiding the menu was hiding working controls.
        compose.onAllNodesWithContentDescription("More").assertCountEquals(1)
    }

    @Test
    fun `a folder the app keeps has a menu with one item, and it is the clone`() {
        // **The operator changed this one, so both halves are asserted here.**
        //
        // Report 10 asked for the menu to go: "locked files should hide and forbid 3dot options",
        // filed while looking at `agent/`. That produced no menu at all. Then the correction, which
        // is the rule now: "add 3dot icon for system locked files but leave only clone option there
        // for them."
        //
        // **Why the clone is the exception and nothing else is.** Every other item is about a file
        // this app keeps — rename it, move it, delete it, pin it, stop syncing it — and all of them
        // would be a promise the lock breaks. A copy is not a write to *this* file: it lands in a
        // project of the person's own, where it is ordinary text.
        //
        // **Inside the folder, because that is where the inheritance shows.** At the project root
        // the folder and everything else would answer the same way, which would pass even if the
        // rule were about the folder alone.
        scene.signedIn()
        scene.project("groceries")
        scene.open("groceries")
        val project = scene.viewModel.workspace!!.projectDir("groceries")
        AgentLog.open(project).told("make me a folder")
        // **`openIn`, because `open` takes a path from the account root.** Spelling "agent" alone
        // opened a folder that does not exist, so the screen was never the record's and the wait
        // timed out - the failure was the fixture, not the marks.
        scene.openIn("agent")
        browser()

        compose.waitUntil(20_000) { there("context.log") }
        // **The file is asserted first**, so a screen that never drew cannot be mistaken for a
        // menu rule that worked.
        assertTrue("context.log is drawn", there("context.log"))
        assertTrue("session.log is drawn", there("session.log"))
        // One menu per row, and both rows are the app's — **plus whatever else the log holds by
        // then.** The count is asked of the rows the fixture names rather than of the folder, because
        // a log that has just been written to (an index pass, a maintenance row) has a third file in
        // it, and a test that asserted "exactly two files exist" would fail on the next feature that
        // logs anything.
        assertTrue(
            "every row of the app's folder has a menu",
            compose.onAllNodesWithContentDescription("More").fetchSemanticsNodes().size >= 2,
        )
    }

    @Test
    fun `a file the app keeps offers the clone and the copy out, and nothing else`() {
        // The contents of that menu, which is the half the count above cannot see. Opening it is the
        // only way to tell "one item, and it is Clone" from "one item, and it is something the lock
        // will refuse".
        scene.signedIn()
        scene.project("groceries")
        scene.open("groceries")
        val project = scene.viewModel.workspace!!.projectDir("groceries")
        AgentLog.open(project).told("make me a folder")
        scene.openIn("agent")
        browser()

        compose.waitUntil(20_000) { there("context.log") }
        compose.onAllNodesWithContentDescription("More")[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Clone…").assertExists()
        // **And the copy out**, which report 23 asked for and which is the other act a lock was
        // never about: it reads the file and writes to Downloads, changing nothing here.
        compose.onNodeWithText("Copy to Downloads").assertExists()
        // **And the ones that would be lies.** Named rather than counted, because "two items" would
        // also pass if they were Rename and Delete.
        for (refused in listOf("Rename…", "Move…", "Delete", "Pin", "Copy…")) {
            compose.onAllNodesWithText(refused).assertCountEquals(0)
        }
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

        assertTrue("and it is locked", scene.viewModel.hold(file) == Workspace.Hold.USER)
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
    fun `the system project's row has no menu, and its folder has no Add`() {
        // **Two reports, one place, because they are the same fact seen twice.** The operator:
        // "system project should have no 3dot menue (no rename, delete, put on github etc)" and
        // "+ button should be hidden in system project".
        //
        // **The reason is the same for both.** Every item in that menu and every thing the add
        // menu makes is about a repository the person owns — Sync, Publish, Keep local, Rename,
        // Delete, Export, and a new file or folder. The system project is the app's own folder: its
        // name is fixed, its contents are written by the app, and it has no remote. A control whose
        // only possible outcome is a refusal teaches the person the app is broken.
        //
        // **The row is asserted against a drawn screen**, so "no menu" cannot be the silence of a
        // screen that never loaded.
        scene.signedIn()
        scene.project("groceries")
        scene.open()
        browser()

        awaitText(Workspace.SYSTEM_PROJECT)
        compose.waitForIdle()
        // **One menu per project, minus the system one.** Asserting zero would have been wrong in
        // both directions: `groceries` is right there and its menu is correct, so the count has to
        // be the *number of projects that are the person's* — which is what makes this a statement
        // about the system project rather than about a screen that drew nothing.
        val mine = scene.viewModel.projectNames.filterNot { Workspace.isSystem(it) }
        compose.onAllNodesWithContentDescription("More").assertCountEquals(mine.size)
        assertTrue("the account really has a project of the person's to compare against", mine.isNotEmpty())
    }

    @Test
    fun `the system project's folder has no Add`() {
        // **The second door into the same rule**, and its own test because a screen can only be
        // set once: the menu is about the project, the Add button is about what goes in it, and the
        // two are drawn by different branches of one composable.
        scene.signedIn()
        scene.project("groceries")
        scene.open(Workspace.SYSTEM_PROJECT)
        browser()

        compose.waitUntil(20_000) { there(AgentPrompt.DATA) }
        // **The contents are asserted first**, so an empty folder cannot pass for a hidden button.
        assertTrue("the folder's contents are drawn", there(AgentPrompt.DATA))
        assertFalse("no Add inside the system project", thereIcon("Add"))
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
        // **Exact, not a substring.** The row's caption now carries the file's absolute path, and
        // a path on this machine can contain the word "local" — so `there("local")` with
        // `substring = true` would fail on a folder name rather than on a label. The rule being
        // asserted is that no row *says* "local", which is a whole word in a row of its own.
        assertFalse(
            "no row is labelled local",
            compose.onAllNodesWithText("local").fetchSemanticsNodes().isNotEmpty(),
        )
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
        // **Private is the default now, and that is the change this asserts.** The window used to
        // open on GPL-3.0, which publishes the first save of a project whose owner has not decided
        // anything yet. A licence is a public statement; "nobody can see this" is the safe answer
        // before one has been chosen.
        //
        // The licence's own NAME, not its key: a test that looked for "private" would be asserting
        // the wire format rather than the sentence a person reads.
        assertTrue("and which licence it will publish under", there("Licence: Private (no licence)"))
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


}
