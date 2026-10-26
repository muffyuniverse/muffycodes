package com.example.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import org.robolectric.RuntimeEnvironment
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The app, as somebody would meet it.
 *
 * Each test is one thing a person does: they sign in, they make a project, they
 * write a file, they sync it, they look at what a file used to say. Each one
 * leaves a folder holding two pictures and a page that says what happened
 * between them, and that is what goes in the report — not a gallery of screens
 * with file names for captions.
 *
 * The words are written for the person reading the report. There are no class
 * names, no timings and no stack traces here on purpose: a report that says
 * "1524 ms" has told a reader nothing they can do anything about. What broke,
 * when something breaks, is in the test's own output where an agent reads it.
 *
 * These are the scenarios a build carries. A pull request does not run them —
 * see the note on the workflow — because a request is the workhorse tier and its
 * job is to say yes or no as fast as it can.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScenariosTest {

    @get:Rule
    val compose = createComposeRule()

    /** One phone with nothing on it. */
    private val scene: Scenario by lazy {
        Scenario(RuntimeEnvironment.getApplication(), signIn = false)
    }

    private val viewModel: AppViewModel get() = scene.viewModel

    /** The app's own screen, painted with the theme the person chose. */
    @Composable
    private fun Screen(content: @Composable () -> Unit) {
        val settings = viewModel.settings
        MaterialTheme(
            colorScheme = if (settings.useDark) settings.theme.darkScheme()
            else settings.theme.lightScheme(),
        ) {
            Surface { content() }
        }
    }

    @Composable
    private fun browser() = Screen {
        BrowserScreen(
            viewModel = viewModel,
            username = viewModel.activeLogin,
            onOpenSettings = {},
            actions = noActions(),
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
        )
    }

    @Composable
    private fun settingsScreen() = Screen {
        SettingsScreen(
            viewModel = viewModel,
            onBack = {},
            onSignIn = {},
            onRemoveAccount = {},
            onTestAccount = {},
        )
    }

    private fun record(folder: String, title: String, about: String, body: ScenarioShots.() -> Unit) {
        scenario(compose, folder, title, about, screen = { browser() }, body = body)
    }

    @Test
    fun `the app before anybody has signed in`() {
        record(
            "01-first-open",
            "The app the first time it is opened.",
            "a phone with no account, and nothing to make yet.",
        ) {
            before()
            then("Nothing yet — this is what the app shows on a phone that has never been signed in.") {}
            after()
            result(
                "The app explains that the account is the root of everything, and offers " +
                    "no button that could make something it would not know where to keep.",
            )
        }
    }

    @Test
    fun `the settings screen with its themes and history lengths`() {
        scenario(
            compose, "02-settings", "Settings, before anything has been kept.",
            "the themes and how much change history is kept, at their defaults.",
            screen = { settingsScreen() },
        ) {
            before()
            then("Nothing is done here: this is the settings screen as it is, with nothing changed.") {}
            after()
            result(
                "Themes are a row of swatches with one light/dark switch, and the change " +
                    "history field is empty, which means every change is kept.",
            )
        }
    }

    @Test
    fun `a phone that has just been signed in`() {
        record(
            "03-account-root",
            "The first thing after signing in.",
            "an account with nothing in it yet — no projects, and nothing the app made.",
        ) {
            before()
            then("The test account is signed in; this is the root of what it can see.") {
                scene.signedIn()
            }
            after()
            result(
                "The root is the list of projects, and it is empty. Everything the app " +
                    "keeps for itself is hidden, so a person never sees bookkeeping.",
            )
        }
    }

    @Test
    fun `a project is made and its folder appears`() {
        scene.signedIn()
        scene.open()

        record(
            "04-a-new-project",
            "Making a project.",
            "an empty projects list, and the project that comes of making one.",
        ) {
            before()
            then("A new project is made and named groceries.") {
                viewModel.createProject("groceries", "mit")
                scene.await("the project to exist") {
                    viewModel.projectNames.any { it.equals("groceries", true) }
                }
                scene.await("the making of it to finish", 60_000) { !viewModel.busy }
                scene.open()
            }
            after()
            result(
                "The project appears with its own folder, and the row says how many changes " +
                    "are waiting — none yet, because nothing has been written.",
            )
        }
    }

    @Test
    fun `a project with changes waiting to be synced`() {
        scene.signedIn()
        scene.project("groceries")
        scene.open("groceries")

        record(
            "05-changes-waiting",
            "Work waiting to be sent.",
            "two files written into a project, and the row that counts them.",
        ) {
            before()
            then("Two files are written into the project's worktree and the list is reopened.") {
                scene.worktreeFile("milk.txt", "two litres")
                scene.worktreeFile("bread.txt", "one loaf")
                scene.open()
                viewModel.refreshChanges()
                scene.await("the row to count the changes", 20_000) {
                    viewModel.changesOf("groceries") > 0
                }
            }
            after()
            result(
                "The row counts the two changes, so a person can see at a glance that this " +
                    "project has work that has not reached GitHub yet.",
            )
        }
    }

    @Test
    fun `a project folder holding its code and its record`() {
        scene.signedIn()
        scene.project("groceries")
        scene.open("groceries")

        record(
            "06-a-project-holding-its-code-and-its-record",
            "A project holding its code and the agent's record.",
            "one folder: the repository that goes to GitHub, and what the agent was sent and answered.",
        ) {
            before()
            then("A file is written in the project, and the agent's record is written beside it.") {
                scene.worktreeFile("shopping_list.txt", "milk, bread")
                val records = File(scene.workspace!!.projectDir("groceries"), AgentLog.DIR)
                records.mkdirs()
                File(records, AgentLog.SESSION).writeText("what the model was sent")
                scene.viewModel.refresh()
            }
            after()
            result(
                "The folder is the repository — the file is a change the project will send — " +
                    "and the app's own folder, the record, is not one: it is named in the " +
                    "project's .gitignore, and no list will ever offer it.",
            )
        }
    }

    @Test
    fun `a file inside the code and what the sync does with it`() {
        scene.signedIn()
        scene.project("groceries")
        scene.open("groceries")

        record(
            "07-after-a-sync",
            "After syncing a project.",
            "a file written inside the code, and the same project once it has been sent.",
        ) {
            before()
            then("A file is written inside the repository and the project is synced.") {
                scene.worktreeFile("milk.txt", "two litres")
                scene.sync("groceries")
            }
            after()
            result(
                "The file is on GitHub and nothing is waiting any more. The sync is one " +
                    "button, and it says nothing when there is nothing to say.",
            )
        }
    }

    @Test
    fun `what a file used to say`() {
        scene.signedIn()
        // **A version belongs to a project**, because a project's folder is the repository. A
        // note at the account root has no history at all, so the note in this scenario lives in
        // one.
        scene.project("groceries")
        scene.open("groceries")
        val file = scene.file("milk.txt")
        scene.openEditor(file)
        scene.saveAsVersion("one litre")
        scene.saveAsVersion("two litres")
        scene.saveAsVersion("three litres")
        scene.closeEditor()

        scenario(
            compose, "08-the-history-of-a-file",
            "The history of a file.",
            "a note that was written three times, and the versions it kept.",
            screen = { browser() },
            afterScreen = {
                Screen { HistoryScreen(viewModel = viewModel, title = "milk.txt", onBack = {}) }
            },
        ) {
            before()
            then("The history of milk.txt is opened, a moment after its third save.") {
                scene.versionsOf(file)
            }
            after()
            result(
                "Each save is one version, and every version is a row — the day, the time " +
                    "and its name in git, so the one wanted can be seen rather than stepped " +
                    "to. The preview is read-only: the file changes only when Select is pressed.",
            )
        }
    }

    @Test
    fun `the editor's row`() {
        scene.signedIn()
        scene.open()
        val file = scene.file("milk.txt", "two litres")

        scenario(
            compose, "11-the-editor",
            "The editor, with its row.",
            "a note open, with the four things the row does.",
            screen = { browser() },
            afterScreen = {
                Screen {
                    EditorScreen(
                        viewModel = viewModel,
                        file = file,
                        onBack = {},
                        onOpenHistory = {},
                    )
                }
            },
        ) {
            before()
            then("The note is opened.") {
                scene.openEditor(file)
            }
            after()
            result(
                "Back leaves and discards, the name is the title and can be changed " +
                    "where it stands, History opens the versions, and Save is a button " +
                    "because saving is something a person does.",
            )
        }
    }

    @Test
    fun `the history with select on the right`() {
        scene.signedIn()
        scene.project("groceries")
        scene.open("groceries")
        val file = scene.file("milk.txt")
        scene.openEditor(file)
        scene.saveAsVersion("one litre")
        scene.saveAsVersion("two litres")
        scene.closeEditor()

        scenario(
            compose, "12-the-history-with-select",
            "The history of a file, with Select where the restore button was.",
            "a note with two versions, and the one being looked at is not the newest.",
            screen = { browser() },
            afterScreen = {
                Screen { HistoryScreen(viewModel = viewModel, title = "milk.txt", onBack = {}) }
            },
        ) {
            before()
            then("The history is opened and the older version is stepped back to.") {
                scene.versionsOf(file)
            }
            after()
            result(
                "Select puts the version back and returns to the file, in one act: " +
                    "a person who has found the version they wanted has finished with " +
                    "the history.",
            )
        }
    }

    @Test
    fun `the history of a folder that was deleted`() {
        scene.signedIn()
        scene.project("groceries")
        scene.open("groceries")
        scene.folder("letters")
        scene.open("groceries", "letters")
        scene.file("one.txt", "the first")
        scene.open("groceries")
        val folder = File(scene.workspace!!.projectDir("groceries"), "letters")

        scenario(
            compose, "09-a-deleted-folder-that-can-come-back",
            "A folder that was deleted, and the history that still has it.",
            "a folder removed from the phone, with every file of it still recoverable.",
            screen = { browser() },
            afterScreen = {
                Screen { HistoryScreen(viewModel = viewModel, title = "letters", onBack = {}) }
            },
        ) {
            before()
            then(
                "The folder letters is deleted, and its history is opened afterwards."
            ) {
                viewModel.delete(folder)
                scene.versionsOfFolder("letters")
            }
            after()
            result(
                "The folder and the files that were in it can be brought back, because " +
                    "the project's whole tree has a history, not only the files it was born with.",
            )
        }
    }

    @Test
    fun `the history of a project that was never synced`() {
        scene.signedIn()
        scene.project("groceries")
        scene.open("groceries")
        scene.worktreeFile("milk.txt", "two litres")
        scene.sync("groceries")
        val file = File(scene.workspace!!.repoOf("groceries"), "milk.txt")

        scenario(
            compose, "10-the-history-of-a-synced-file",
            "The history of a file that lives on GitHub.",
            "a file inside a project, changed and synced twice, and the versions GitHub holds.",
            screen = { browser() },
            afterScreen = {
                Screen { HistoryScreen(viewModel = viewModel, title = "milk.txt", onBack = {}) }
            },
        ) {
            before()
            then("The file is changed and the project synced a second time.") {
                scene.worktreeFile("milk.txt", "three litres")
                scene.sync("groceries")
                scene.versionsOf(file)
            }
            after()
            result(
                "The history of a file inside a project comes from that project's repository, " +
                    "so what is on GitHub and what this list shows are the same thing.",
            )
        }
    }

    private fun noActions() = BrowserActions(
        onOpen = {},
        onRename = {},
        onMove = {},
        onCopy = {},
        onDelete = {},
        onTogglePin = {},
        onToggleLock = {},
        onHistory = {},
        onSelect = {},
    )
}
