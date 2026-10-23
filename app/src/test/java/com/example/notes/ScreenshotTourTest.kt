package com.example.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import org.robolectric.RuntimeEnvironment
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws the screens and saves them as pictures, on the plain JVM.
 *
 * This is the only tour there is, and it ships with every build: a screen is
 * built, drawn to a bitmap and written to a file in milliseconds. There is no
 * device, no emulator and no waiting.
 *
 * The walk goes through **the whole app**, including the half that needs an
 * account. It signs in as the test account — the one a debug build can make,
 * whose GitHub is a folder of real bare repositories ([DiskGitHub]) — makes a
 * project, writes into its worktree and opens the history. That is the same code
 * the release ships, on the same JVM the tests run on, so the pictures cover the
 * features the redesign is about rather than only the signed-out screens.
 *
 * What these pictures are for is the question "does the feature work, and is the
 * screen I expected actually there". What they are NOT for is "does it look
 * right": a JVM draws its own fonts and spacing, and the truth of appearance is
 * the operator's eyes on the real install, opened from `bundle`. Read these as
 * evidence that the features are there — not as the final word on how they look.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTourTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun onlyWhenRecording() {
        // A picture is only taken when the tour is asked for. Ordinary test runs
        // skip this class entirely, so they stay fast and leave no files behind.
        Assume.assumeTrue(System.getProperty("roborazzi.test.record") == "true")
    }

    /** One phone with nothing on it, and the walk that fills it. */
    private val scene: Scenario by lazy {
        Scenario(RuntimeEnvironment.getApplication(), signIn = false)
    }

    private val viewModel: AppViewModel get() = scene.viewModel

    /** Draws [content] with the app's theme and writes it to the tour's folder. */
    private fun shot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            val settings = viewModel.settings
            MaterialTheme(
                colorScheme = if (settings.useDark) settings.theme.darkScheme()
                else settings.theme.lightScheme(),
            ) {
                Surface { content() }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name")
    }

    /** The browser, with whatever the app is currently showing. */
    private fun browser(name: String) = shot(name) {
        BrowserScreen(
            viewModel = viewModel,
            username = viewModel.activeLogin,
            onOpenSettings = {},
            onOpenAccount = {},
            actions = noActions(),
            onAddProject = {},
            onRowSync = {},
            onRowRename = {},
            onRowDelete = {},
            onCreateFile = {},
            onCreateFolder = {},
        )
    }

    @Test
    fun theBrowserWithNoAccountYet() = browser("tour-01-no-account.png")

    @Test
    fun theSettingsScreen() {
        shot("tour-02-settings.png") {
            SettingsScreen(
                viewModel = viewModel,
                onBack = {},
                onSignIn = {},
                onRemoveAccount = {},
                onEditIdentity = {},
            )
        }
    }

    @Test
    fun theAccountRootOnceSignedIn() {
        scene.signedIn()

        browser("tour-03-account-root.png")
    }

    @Test
    fun theProjectsScreenWithNothingInIt() {
        scene.signedIn()
        scene.open(Workspace.PROJECTS)

        browser("tour-04-projects-empty.png")
    }

    @Test
    fun theProjectsScreenWithAProjectAndChangesWaiting() {
        scene.signedIn()
        scene.project("groceries")
        scene.open(Workspace.PROJECTS, "groceries")
        scene.worktreeFile("milk.txt", "two litres")
        scene.worktreeFile("bread.txt", "one loaf")
        scene.open(Workspace.PROJECTS)
        scene.viewModel.refreshChanges()
        scene.await("the row to count the changes") { viewModel.changesOf("groceries") > 0 }

        browser("tour-05-projects-with-changes.png")
    }

    @Test
    fun theProjectFolderShowingItsWorktreeAndALocalFile() {
        scene.signedIn()
        scene.project("groceries")
        scene.open(Workspace.PROJECTS, "groceries")
        scene.file("shopping-list.txt", "milk, bread")

        browser("tour-06-project-folder.png")
    }

    @Test
    fun insideTheWorktreeWithAFileWritten() {
        scene.signedIn()
        scene.project("groceries")
        scene.open(Workspace.PROJECTS, "groceries", Workspace.REPO)
        scene.worktreeFile("milk.txt", "two litres")
        scene.viewModel.refresh()

        browser("tour-07-worktree.png")
    }

    @Test
    fun theWorktreeAfterASync() {
        scene.signedIn()
        scene.project("groceries")
        scene.open(Workspace.PROJECTS, "groceries")
        scene.worktreeFile("milk.txt", "two litres")
        scene.sync("groceries")
        scene.open(Workspace.PROJECTS, "groceries", Workspace.REPO)

        browser("tour-08-after-sync.png")
    }

    @Test
    fun theHistoryOfAFile() {
        scene.signedIn()
        val file = scene.file("milk.txt")
        scene.openEditor(file)
        scene.saveAsVersion("one litre")
        scene.saveAsVersion("two litres")
        scene.saveAsVersion("three litres")
        scene.closeEditor()
        scene.versionsOf(file)

        shot("tour-09-history.png") {
            HistoryScreen(viewModel = viewModel, title = "milk.txt", onBack = {})
        }
    }

    @Test
    fun theHistoryOfAFolderThatWasDeleted() {
        scene.signedIn()
        scene.folder("letters")
        scene.open("letters")
        scene.file("one.txt", "the first")
        scene.open()
        val folder = java.io.File(scene.workspace!!.accountDir, "letters")
        scene.versionsOfFolder("letters")
        viewModel.delete(folder)

        shot("tour-10-history-of-a-deleted-folder.png") {
            HistoryScreen(viewModel = viewModel, title = "letters", onBack = {})
        }
    }

    private fun noActions() = BrowserActions(
        onOpen = {},
        onRename = {},
        onMove = {},
        onDelete = {},
        onTogglePin = {},
        onHistory = {},
        onMoveIntoRepository = {},
        onKeepOnlyOnPhone = {},
    )
}
