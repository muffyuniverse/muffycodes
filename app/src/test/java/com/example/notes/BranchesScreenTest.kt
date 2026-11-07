package com.example.notes

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * **A project's branches, and the two refusals that matter** (§13d).
 *
 * The operator's shape: *"when you tap a project you are shown branch screen where you need to select a
 * branch (and main branch exists by default even for newly created projects)."*
 *
 * Two halves, each asked of the thing that can answer it:
 *
 * - **The screen** is painted, because what it *offers* is not a fact the view model holds. The row for
 *   `main` must not offer Remove, and a branch with unsent work must say so *before* it is entered —
 *   that sentence is the reason the confirmation exists.
 * - **The navigation** is asked of the view model, because "which screen am I on" is state: a project
 *   tap must not also open its files, and leaving must clear the choice so the next project does not
 *   inherit it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class BranchesScreenTest {

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
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) { content() }
        }
    }

    private class Taps {
        var entered: String? = null
        var added = 0
        var removed: String? = null
        var backed = 0
    }

    private fun branches(
        list: List<String> = listOf("main", "feature"),
        unmerged: Set<String> = emptySet(),
        taps: Taps = Taps(),
    ) {
        compose.setContent {
            Frame {
                BranchesScreen(
                    project = "Shopping",
                    branches = list,
                    unmerged = unmerged,
                    onEnter = { taps.entered = it },
                    onAdd = { taps.added++ },
                    onRemove = { taps.removed = it },
                    onBack = { taps.backed++ },
                )
            }
        }
    }

    @Test
    fun `the screen lists the branches and names the project`() {
        branches()

        compose.onNodeWithText("Shopping").assertIsDisplayed()
        compose.onNodeWithText("main").assertIsDisplayed()
        compose.onNodeWithText("feature").assertIsDisplayed()
    }

    @Test
    fun `main does not offer Remove, because it is what a project is made of`() {
        // **A button that always refuses is worse than no button.** `deleteBranch` refuses `main`, so
        // drawing Remove beside it would offer an act that cannot happen.
        val t = Taps()
        branches(taps = t)

        // One Remove and one only: the row that is not main. `onNodeWithText` throws when several
        // match, so this passing at all is half the assertion.
        compose.onNodeWithText("Remove").assertIsDisplayed().performClick()

        assertEquals("the only removable branch was named", "feature", t.removed)
    }

    @Test
    fun `tapping a branch enters it`() {
        val t = Taps()
        branches(taps = t)

        compose.onNodeWithText("feature").performClick()

        assertEquals("the row named itself", "feature", t.entered)
    }

    @Test
    fun `a branch with work that is not on main says so before it is entered`() {
        // **The sentence that decides everything after it.** Whether the work can be merged, and
        // whether removing the branch would lose it, both follow from this one fact.
        branches(unmerged = setOf("feature"))

        compose.onNodeWithText("has work that is not on main").assertIsDisplayed()
    }

    @Test
    fun `a branch that is on main says nothing about unsent work`() {
        // The negative half: a caption drawn on every row would be noise, and the point of the
        // caption is that it is unusual.
        branches(unmerged = emptySet())

        compose.onNodeWithText("has work that is not on main").assertDoesNotExist()
    }

    @Test
    fun `the plus asks for a branch and the back button leaves`() {
        val t = Taps()
        branches(taps = t)

        compose.onNodeWithText("+ Branch").performClick()
        assertEquals("adding was asked for", 1, t.added)

        compose.onNodeWithText("‹ Projects").performClick()
        assertEquals("leaving was asked for", 1, t.backed)
    }

    // ---- the navigation (§13d) ---------------------------------------------

    @Test
    fun `tapping a project shows its branches and not its files`() {
        // **The operator's shape, as state.** A tap that also opened the project would show the files
        // underneath and the branch screen only on the way back — which is what the first draft did.
        scene.project("Shopping")

        scene.viewModel.openBranches("Shopping")

        assertEquals("the branch screen names the project", "Shopping", scene.viewModel.choosingBranchFor)
        assertTrue(
            "and the files were not opened",
            scene.viewModel.path.isEmpty(),
        )
        assertEquals("and main is one of them", "main", scene.viewModel.choosingBranches.first())
    }

    @Test
    fun `leaving the branch screen forgets which project it was for`() {
        // **The stale-project bug this prevents**: a second project opened after leaving must not
        // inherit the first one's rows, and a cleared choice is the only thing that makes that true.
        scene.project("Shopping")
        scene.viewModel.openBranches("Shopping")

        scene.viewModel.closeBranches()

        assertNull("nothing is being chosen", scene.viewModel.choosingBranchFor)
        assertTrue("and no rows are left over", scene.viewModel.choosingBranches.isEmpty())
    }

    @Test
    fun `switching accounts forgets the branch, because a branch belongs to a project in one account`() {
        scene.project("Shopping")
        scene.viewModel.openBranches("Shopping")
        scene.viewModel.signIn("someone-else", "a-key", "", "")

        assertNull("the branch screen is closed by the switch", scene.viewModel.choosingBranchFor)
    }

    @Test
    fun `a new branch is made and appears in the list`() {
        // **The whole loop, through the door a person uses**: the view model makes it, re-reads the
        // list, and the list is state so the screen redraws.
        scene.project("Shopping")
        scene.viewModel.openBranches("Shopping")

        scene.viewModel.newBranch("feature")

        assertTrue(
            "it is in the list: ${scene.viewModel.choosingBranches}",
            "feature" in scene.viewModel.choosingBranches,
        )
        assertNotNull("and the app said what happened", scene.viewModel.message)
    }

    @Test
    fun `a branch name git would reject is refused with a sentence, not silence`() {
        scene.project("Shopping")
        scene.viewModel.openBranches("Shopping")

        scene.viewModel.newBranch("a b")

        val said = scene.viewModel.message.orEmpty()
        assertTrue("the refusal is said: $said", said.contains("space"))
        assertTrue(
            "and nothing was made: ${scene.viewModel.choosingBranches}",
            scene.viewModel.choosingBranches.none { it == "a b" },
        )
    }

    @Test
    fun `removing a branch with nothing of its own goes, and the list is re-read`() {
        // **The half that must succeed**, or the refusal becomes a button that never does anything.
        // The unmerged half is `ProjectGitTest`'s, where real history can be built.
        scene.project("Shopping")
        scene.viewModel.openBranches("Shopping")
        scene.viewModel.newBranch("feature")
        scene.viewModel.message = null

        // No commits on it, so it goes.
        scene.viewModel.dropBranch("feature")

        assertTrue(
            "it is gone: ${scene.viewModel.choosingBranches}",
            scene.viewModel.choosingBranches.none { it == "feature" },
        )
        assertTrue("and the app said so", scene.viewModel.message.orEmpty().contains("Removed"))
    }

    @Test
    fun `main cannot be removed, and the refusal is said`() {
        scene.project("Shopping")
        scene.viewModel.openBranches("Shopping")
        scene.viewModel.message = null

        scene.viewModel.dropBranch("main")

        assertTrue("main is still there", "main" in scene.viewModel.choosingBranches)
        assertTrue(
            "and the refusal names the reason: ${scene.viewModel.message}",
            scene.viewModel.message.orEmpty().contains("main is the branch"),
        )
    }

    // ---- a branch is a folder ---------------------------------------------

    @Test
    fun `entering a branch opens that branch's own folder`() {
        // **The claim the whole feature rests on**: `main/notes.txt` and `feature/notes.txt` are two
        // files. Before this, every branch was the same folder and choosing one changed only a label.
        scene.project("Shopping")
        scene.viewModel.openBranches("Shopping")
        scene.viewModel.newBranch("feature")
        scene.viewModel.openBranches("Shopping")

        scene.viewModel.enterBranch("feature")

        assertEquals("the workspace is on the branch", "feature", scene.viewModel.activeBranch)
        assertEquals(
            "and the files shown are the project's",
            "Shopping",
            scene.viewModel.currentProject,
        )
        assertTrue(
            "which live in the branch's own folder: ${scene.viewModel.currentDir}",
            scene.viewModel.currentDir!!.absolutePath.replace('\\', '/')
                .contains(".muffy/worktrees/Shopping/feature"),
        )
    }

    @Test
    fun `a file made on a branch is not the file main has`() {
        // **Two real files, written from the app's own door.** A test that only checked the folder path
        // would pass while both folders pointed at the same place - which is exactly what an unfixed
        // `fileAt` would do.
        scene.project("Shopping")
        scene.viewModel.open(listOf("Shopping"))
        val onMain = scene.file("notes.txt", "main's copy")

        scene.viewModel.openBranches("Shopping")
        scene.viewModel.newBranch("feature")
        scene.viewModel.openBranches("Shopping")
        scene.viewModel.enterBranch("feature")
        val onBranch = scene.file("notes.txt", "the branch's copy")

        assertNotEquals("two different files", onMain.absolutePath, onBranch.absolutePath)
        assertEquals("main keeps its text", "main's copy", onMain.readText())
        assertEquals("and the branch has its own", "the branch's copy", onBranch.readText())
    }

    @Test
    fun `main has no worktree folder, because the project root is already its tree`() {
        // Picking `main` must be the path every screen took before branches existed — no folder made, no
        // migration, nothing to migrate.
        scene.project("Shopping")

        scene.viewModel.openBranches("Shopping")
        scene.viewModel.enterBranch("main")

        val dir = scene.viewModel.currentDir!!
        assertEquals("the project folder itself", scene.workspace!!.projectDir("Shopping"), dir)
        assertFalse(
            "and nothing was made under .muffy",
            File(scene.workspace!!.stateDir, "worktrees/Shopping").exists(),
        )
    }

    @Test
    fun `a file on a branch is still recognised as being inside its project`() {
        // **The inverse mapping, which is not cosmetic.** Read literally, a worktree path names no
        // project — so every rule that asks "is this inside a project?" would stop applying the moment a
        // person opened a branch: including the ones that stop an agent rewriting .git or leaving.
        scene.project("Shopping")
        scene.viewModel.openBranches("Shopping")
        scene.viewModel.newBranch("feature")
        scene.viewModel.openBranches("Shopping")
        scene.viewModel.enterBranch("feature")

        val file = File(scene.viewModel.currentDir, "on-the-branch.txt").apply { writeText("x") }

        assertEquals(
            "the path the screen calls it",
            listOf("Shopping", "on-the-branch.txt"),
            scene.workspace!!.relative(file),
        )
        assertEquals("and it is inside the project", "Shopping", scene.workspace!!.projectOf(file))
        assertTrue("so the project rules apply to it", scene.workspace!!.inWorktree(file))
    }

    @Test
    fun `leaving the account puts the workspace back on main`() {
        // A branch belongs to a project in one account, so it cannot survive an account change — and the
        // silent version of this bug is editing main's files while the title still says `feature`.
        scene.project("Shopping")
        scene.viewModel.openBranches("Shopping")
        scene.viewModel.newBranch("feature")
        scene.viewModel.openBranches("Shopping")
        scene.viewModel.enterBranch("feature")

        scene.viewModel.leaveAccount()

        assertEquals("back on main", "main", scene.viewModel.activeBranch)
    }
}
