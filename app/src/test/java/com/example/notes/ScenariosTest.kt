package com.example.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollTo
import org.robolectric.RuntimeEnvironment
import androidx.test.espresso.IdlingPolicies
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
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

    companion object {
        /**
         * **A wait that can never succeed is bounded tightly, because it is a mistake, not a wait.**
         *
         * Espresso's default master policy waits sixty seconds for idle before raising
         * `AppNotIdleException`. That default assumes the app is eventually idle; a Robolectric
         * composition with an animating dialog is never idle, so the exception is the *expected*
         * outcome there and the sixty seconds are pure cost. One bad `after()` failed five window
         * scenarios twice and took six minutes to say so, twice - twelve minutes of CI spent
         * proving that a dialog does not stand still.
         *
         * Fifteen seconds, not five: a git read on a loaded runner is not the sub-second thing it
         * is on a laptop, and a bound that a working scenario can exceed would turn a slow picture
         * red for no reason. Fifteen still cuts the cost of a mistake to a quarter.
         */
        @JvmStatic
        @BeforeClass
        fun boundTheIdleWait() {
            IdlingPolicies.setMasterPolicyTimeout(15, TimeUnit.SECONDS)
            IdlingPolicies.setIdlingResourceTimeout(15, TimeUnit.SECONDS)
        }
    }

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
        )
    }

    @Composable
    private fun settingsScreen() = Screen {
        SettingsScreen(
            viewModel = viewModel,
            onBack = {},
            onTestAccount = {},
        )
    }

    /** The chat, which needs a project and a conversation in it before it has anything to show. */
    @Composable
    private fun chatScreen() = Screen { AgentChatScreen(viewModel = viewModel, onBack = {}) }

    /** What the app did: the trace's own screen, with a report that has something in it. */
    @Composable
    private fun observability(report: Trace.Report) = Screen {
        ObservabilityScreen(report = report, onBack = {}, onShare = {}, onClear = {})
    }

    /**
     * The account screen's heartbeat card, with the history behind it.
     *
     * **A whole screen rather than the card alone**, because the card is only meant to be seen above
     * the project list - and a picture of it floating on its own would not show the thing the design
     * is about: that it costs the list one band and no scroll.
     */
    @Composable
    private fun Heartbeat(accountDir: java.io.File, now: Long) {
        // **Read once, not once per frame.** `RequestDb.read` opens a file handle, so reading in the
        // composable body would open one per recomposition and leave all but the last unclosed.
        // `remember` is keyed on the inputs, which are fixed for a scenario.
        val measures = androidx.compose.runtime.remember(accountDir, now) {
            RequestDb.read(accountDir).use { db -> Heartbeat.of(db, now) }
        }
        Screen {
            androidx.compose.foundation.layout.Column {
                HeartbeatCard(measures)
            }
        }
    }


    /**
     * Whether the window on the screen says [text], in part.
     *
     * The same question `BrowserChromeTest` asks, and here for the one window that cannot be asked
     * there: see the note on the sign-in scenario.
     */
    private fun there(text: String): Boolean =
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    /**
     * A dialog, painted on its own.
     *
     * **A window is a screen too**, and a report that only ever shows the screens behind them cannot
     * show what a person is actually looking at when the app asks a question. Each dialog is drawn
     * over an empty surface, which is what it looks like over any of them - the window carries its
     * own scrim.
     */
    @Composable
    private fun window(content: @Composable () -> Unit) = Screen {
        // A box with nothing in it, so the capture has a background to draw the window over.
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {}
        content()
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
                viewModel.createLocalProject("groceries")
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
                // **The strip is the visible half of "a file was written".** The row itself lands
                // below the fold on a screen this tall — folders sort first, then files, and the app's
                // own folder sorts after both — so the picture that changed was the count at the top
                // and nothing else. Waiting for it is also what makes the two pictures differ for the
                // right reason rather than by chance.
                scene.viewModel.refreshChanges()
                scene.await("the change to be counted") { scene.viewModel.changesHere > 0 }
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
                // **Read again once the sync is done, or the picture is the screen from before it.**
                // The browser holds the list it was given; a file appearing in the folder does not
                // reach it until something asks. Both pictures were byte-identical until this line,
                // and the caption claimed a sync - which is the exact defect the identical-pictures
                // check in `ScenarioShots` now catches.
                scene.viewModel.refresh()
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
        // **Into the project that holds the note**, not the account root. `before()` photographs
        // whatever is on screen, and with only `scene.open()` it photographed the list of projects -
        // a picture of "your projects" under the caption "a note open", which is the one thing a
        // before-picture must not be.
        scene.open("groceries")
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
    fun `a page of writing, photographed`() {
        // **The step-9 picture.** A text file's *view* — rendered markdown, wrapped — becomes a PNG
        // beside it, so a person can circle a paragraph and say "this goes there". Robolectric cannot
        // rasterise, so the only place "the letters are on the page" can be seen is here.
        scene.signedIn()
        scene.open("groceries")
        val note = scene.file(
            "plan.md",
            "# Shopping\n\nThe list is short this week.\n\n- two litres of milk\n" +
                "- a loaf of bread\n- something for the weekend\n\n> check the freezer first\n",
        )

        // **The photograph is made before the screen is shown, and awaited.** A first version pressed
        // the button from inside `ready` and answered immediately, so the capture happened before the
        // worker had rendered anything — and the picture was of the *text editor*, which looks exactly
        // like a scenario that passed. `await` drains the looper, which is what makes the worker's
        // answer land.
        var photographed: File? = null
        var pressedDraw = false
        scenario(
            compose, "34-a-page-of-writing-photographed",
            "A page, photographed.",
            "a note's own view as a picture, ready to be drawn on.",
            screen = { browser() },
            afterScreen = {
                Screen {
                    EditorScreen(
                        viewModel = viewModel,
                        file = photographed ?: note,
                        onBack = {},
                        onOpenHistory = {},
                    )
                }
            },
            // **Draw is pressed after the swap, in `ready`**, because the button only exists once the
            // after-screen is composed — and `then(...)` runs on the screen before it. Splitting the
            // two is the whole reason `ready` exists.
            ready = {
                if (!pressedDraw) {
                    pressedDraw = true
                    compose.onAllNodesWithText("Draw")[0].performClick()
                    compose.waitForIdle()
                }
                true
            },
        ) {
            before()
            then("The note is opened, Photo is pressed, and the drawing tools are chosen.") {
                scene.openEditor(note)
                viewModel.photograph(viewModel.textOf(note))
                scene.await("the photograph to be made and opened") {
                    viewModel.editingFile?.name?.let { it != note.name && it.endsWith(".png") } == true
                }
                photographed = viewModel.editingFile
                compose.waitForIdle()
            }
            after()
            result(
                "Photo renders the file's view — the rendered page, not the raw source — to a PNG " +
                    "beside it, named after the file plus a hash of what was drawn. It opens in the " +
                    "image editor, so the drawing tools are already to hand.",
            )
        }
    }

    @Test
    fun `an image with a pen over it`() {
        // **The one picture that shows the canvas paints.** Everything about the drawing that can be
        // tested in this container is tested — the strokes, the eraser, undo, the zoom arithmetic —
        // but Robolectric's `Canvas` rasterises nothing, so "a stroke appears on the screen" is only
        // answerable here. If this scenario shows a blank white rectangle, the canvas is broken and no
        // unit test could have said so.
        scene.signedIn()
        scene.open("groceries")
        val picture = scene.picture("correction", Look.CANVASES.first { it.label == "Square" })
        // Declared outside the composition so `ready` can press Draw exactly once.
        var pressedDraw = false

        scenario(
            compose, "33-a-picture-with-a-pen-over-it",
            "An image, drawn on.",
            "a picture open in Draw, with a red mark on it.",
            screen = { browser() },
            afterScreen = {
                Screen {
                    EditorScreen(
                        viewModel = viewModel,
                        file = picture,
                        onBack = {},
                        onOpenHistory = {},
                    )
                }
            },
            // **The one hook that runs between the swap and the capture.** `after()` switches the
            // composition and photographs it immediately, so a press that has to happen on the new
            // screen goes here: press Draw, answer true, and the picture shows the canvas.
            ready = {
                if (!pressedDraw) {
                    pressedDraw = true
                    compose.onAllNodesWithText("Draw")[0].performClick()
                    compose.waitForIdle()
                    // **A real stroke, driven through the same gesture a finger makes.** This is the
                    // only place in the whole suite where "the canvas paints" can be asked: the
                    // Robolectric unit tests cannot rasterise, so a picture of an *empty* canvas
                    // would be a picture of the one thing that is already proved — that the screen
                    // opens. A swipe across the surface is what makes the picture evidence.
                    compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("drawing canvas"))
                        .fetchSemanticsNodes()
                        .firstOrNull()
                        ?.let {
                            // **Relative to the surface's own bounds**, not to the screen. The
                            // first version used absolute offsets that were valid at 1:1; once the
                            // canvas opened *fitted* the picture was smaller, the swipe landed
                            // outside it, and the picture showed an empty canvas — a regression the
                            // fit introduced and only this picture could catch.
                            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("drawing canvas"))[0]
                                .performTouchInput {
                                    // `width` and `height` are the scope's own, in the node's
                                    // coordinates — which is what a touch needs.
                                    val w = width
                                    val h = height
                                    down(androidx.compose.ui.geometry.Offset(w * 0.25f, h * 0.30f))
                                    moveTo(androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.5f))
                                    moveTo(androidx.compose.ui.geometry.Offset(w * 0.7f, h * 0.35f))
                                    up()
                                }
                            compose.waitForIdle()
                        }
                }
                true
            },
        ) {
            before()
            then("The picture is opened, and Draw is chosen.") {
                scene.openEditor(picture)
            }
            // **`after()` swaps to the editor and captures at once**, so the mode has to be chosen
            // *after* the swap. `ready` is the hook that runs between the two: it presses Draw and
            // then answers true, which is what lets the picture show the canvas rather than View.
            after()
            result(
                "An image is a file like any other, so it has View and Draw rather than the " +
                    "text modes: a blank PNG is a sketch, a screenshot is an annotation, and " +
                    "a photo is a correction. The pen, line, box and eraser are the four marks; " +
                    "a pinch zooms, two fingers move the picture, and one finger draws.",
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
        // How many reads had landed before the screen mounted, so the wait can be for the next one.
        val readsBefore = viewModel.historyLoaded

        scenario(
            compose, "12-the-history-with-select",
            "The history of a file, with Select where the restore button was.",
            "a note with two versions, and the one being looked at is not the newest.",
            screen = { browser() },
            afterScreen = {
                Screen { HistoryScreen(viewModel = viewModel, title = "milk.txt", onBack = {}) }
            },
            // **A second screen that reads git to draw itself has a spinner as its first frame.**
            // Without this the picture was the words "Reading the history..." under a caption about
            // Select - the same mistake 09 already documents and this one repeated. The count is
            // taken before the `then` block, which asks the view model for the list synchronously:
            // only `requestVersions`, the screen's own call, bumps the counter.
            ready = { scene.loaded(readsBefore + 1) },
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
        // How many reads had landed before the screen mounted, so the wait can be for the next one.
        val readsBefore = viewModel.historyLoaded

        scenario(
            compose, "09-a-deleted-folder-that-can-come-back",
            "A folder that was deleted, and the history that still has it.",
            "a folder removed from the phone, with every file of it still recoverable.",
            screen = { browser() },
            afterScreen = {
                Screen { HistoryScreen(viewModel = viewModel, title = "letters", onBack = {}) }
            },
            // **The only second screen in this report that reads git to draw itself**, so the only
            // one whose first frame is a spinner. Every other one is the list the scenario came
            // from, already drawn.
            //
            // The count is taken WITHOUT the `then` block having contributed to it: that block asks
            // the view model for the list synchronously, and only `requestVersions` - which is what
            // the screen itself calls - bumps the counter. So `readsBefore + 1` is the screen's own
            // read landing, which is the frame worth photographing. A predicate that merely said
            // "loaded something" would be true from the start and would photograph the spinner
            // again, and that mistake costs a whole run to notice.
            ready = { scene.loaded(readsBefore + 1) },
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

    // ---- the two screens no scenario had reached ------------------------------

    @Test
    fun `the chat, which is a conversation rather than one turn`() {
        // **Report 36, photographed.** The box used to draw one turn behind a navigator; what a
        // person sees now is the whole conversation as a list, and a picture of it is the only way
        // to say whether the shape reads as one.
        scene.signedIn()
        scene.project("groceries")
        scene.open("groceries")
        // **The conversation is made by the app, not by hand.** `sendChat` writes the operator's row
        // and asks the door; the door answers with a call, and then with words. A scenario that
        // built the rows itself would be photographing a record the app cannot produce.
        // **With the provider's accounting, so the footer's numbers are in the picture.** A door
        // that reported no usage would photograph the one thing the footer can no longer draw - and
        // the counts are persisted precisely so they survive a reopen, so a scenario without them
        // would be showing a screen that cannot exist on a phone that has done a real turn.
        val door = ScriptedDoor(
            Reply(
                text = "",
                calls = listOf(Call("call-1", "search", """{"path":""}""")),
                usage = org.json.JSONObject(
                    """{"completion_tokens":412,"completion_tokens_details":{"reasoning_tokens":1880}}""",
                ),
            ),
            Reply(
                text = "There is one file, and it is the note you wrote.",
                usage = org.json.JSONObject(
                    """{"completion_tokens":2400,"completion_tokens_details":{"reasoning_tokens":9100}}""",
                ),
            ),
        )
        viewModel.chatDoor = { door }
        viewModel.openChat("groceries")
        scene.await("the conversation to be read") { viewModel.chatGeneration > 0 }
        viewModel.sendChat("what is in the project")
        scene.await("the answer") { viewModel.chatBox?.bubbles?.any { it.caption == "agent" } == true }

        scenario(
            compose, "13-the-chat",
            "The chat, with a question and its answer.",
            "one question, one call, and the words that came back.",
            screen = { chatScreen() },
        ) {
            before()
            then("Nothing here: the conversation is already on the screen, oldest first.") {}
            after()
            result(
                "Every act is its own bubble - the question, the call with its result, and the " +
                    "answer - and each carries the id and time of the row it came from, so the " +
                    "picture can be checked against session.log.",
            )
        }
    }

    @Test
    fun `what the app did, as the trace's own screen shows it`() {
        scene.signedIn()
        val id = Trace.tap("settings", "")
        Trace.answered(id, "opened")
        val report = Trace.report()

        scenario(
            compose, "14-what-the-app-did",
            "What the app did.",
            "the log of taps and answers, with one tap that was answered.",
            screen = { observability(report) },
        ) {
            before()
            then("Nothing is pressed: this screen is a reading of the log, not an act.") {}
            after()
            result(
                "The screen says how many taps were answered and shows the lines themselves, " +
                    "so a person can see the app's own account of what it did.",
            )
        }
    }

    @Test
    fun `what the model cost, as the statistics screen shows it`() {
        // **A picture of the table, because a table is the one screen whose defects a test cannot
        // see.** A column that overflows, a block whose rows stop lining up, a share printed as a
        // fraction, two kinds that should have been three: every assertion here would pass while the
        // screen said the wrong thing to a person. The numbers are written through the real log, so
        // what is pictured is what the door would have written.
        scene.signedIn()
        val account = scene.workspace?.accountDir ?: error("signed in, so there is one")
        val now = java.time.LocalDate.parse("2026-10-28")
            .atTime(14, 30).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        fun usage(prompt: Int, cached: Int, completion: Int, thinking: Int, cost: Double) =
            org.json.JSONObject(
                """{"prompt_tokens":$prompt,"cached_tokens":$cached,
                    "completion_tokens":$completion,"reasoning_tokens":$thinking,"cost":$cost,
                    "prompt_tokens_details":{"cached_tokens":$cached},
                    "completion_tokens_details":{"reasoning_tokens":$thinking}}""",
            )

        // **Two days, not one afternoon**, because the card draws the last forty-eight hours and a
        // picture of a graph with one bar in it cannot show whether the graph works. The shape is
        // what a real fortnight looks like: quiet nights, two busy mornings, one long afternoon of
        // keepalives - so the five cells have something to be different from.
        for (hourBack in 47 downTo 1) {
            // Nothing between midnight and seven, which is the gap the card has to draw as a gap.
            val dayHour = ((now - hourBack * 3_600_000L) / 3_600_000L % 24).toInt()
            if (dayHour < 7) continue
            // Busier towards the end, so the graph has a direction rather than a flat line.
            val perHour = if (hourBack < 6) 9 else if (hourBack < 14) 4 else 2
            var at = now - hourBack * 3_600_000L + 3 * 60_000L
            repeat(perHour) {
                RequestDb.write(account, at, RequestLog.Stats(
                    kind = if (it == 0) RequestLog.Stats.Kind.TURN else RequestLog.Stats.Kind.PING,
                    ms = 2_400,
                    provider = "deepinfra/fp8/flex", model = "deepseek/deepseek-v4.1-flash",
                    usage = usage(
                        prompt = 6_000, cached = 5_500, completion = if (it == 0) 600 else 1,
                        thinking = 90, cost = 0.00031,
                    ),
                    finish = "stop",
                ))
                at += 7 * 60_000L
            }
        }
        // One answer that began and died, so the third block is in the picture rather than only in
        // the code path.
        RequestDb.write(account, now - 30 * 60_000L, RequestLog.Stats(
            kind = RequestLog.Stats.Kind.STALL, ms = 92_000,
            provider = "deepinfra/fp8/flex", model = "deepseek/deepseek-v4.1-flash",
            usage = null, finish = null,
            received = RequestLog.Stats.Received(said = 0, thinking = 2_140),
        ))

        scenario(
            compose, "24-what-it-cost",
            "The account's heartbeat.",
            "four measures over two windows: the last hour as a number, the last two days as a graph.",
            screen = { Heartbeat(account, now) },
        ) {
            before()
            then("Nothing is pressed: the card is the history, summed as it already is.") {}
            after()
            result(
                "Four measures, each a graph of forty-eight sixty-minute windows with the last " +
                    "hour beside it and the difference as a badge. The graphs are not clock hours: " +
                    "every bar is a full sixty minutes, so the rightmost bar and the number above " +
                    "it cover the same span. An hour with no requests is a gap rather than a bar at " +
                    "the floor, which is why the quiet nights are visibly empty. Spend's badge is " +
                    "the brand's caramel because whether more spending is good is not the app's " +
                    "call; the other three are green or red because their direction is not in doubt.",
            )
        }
    }

    @Test
    fun `what it cost before anything was asked`() {
        // **The other empty screen, and it is not the same one.** A table of zeroes and a sentence
        // saying nothing has been asked yet look completely different to a person deciding whether
        // the app is broken - and until now only the populated version was ever pictured.
        scene.signedIn()
        val account = scene.workspace?.accountDir ?: error("signed in, so there is one")
        // **Its own database, deliberately.** This class shares one lazily-built `scene` across its
        // tests, so a scenario that wrote responses would leave them here and this screen would not
        // be empty at all - it would show the previous test's numbers under a caption claiming
        // nothing had been asked. Deleting the file is the honest way to say "a fresh install".
        RequestDb.delete(account)
        val now = java.time.LocalDate.parse("2026-10-28")
            .atTime(14, 30).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        scenario(
            compose, "26-what-it-cost-before-anything",
            "Account statistics, before anything was asked.",
            "an empty history, which has to say so rather than show a grid of zeroes.",
            screen = { Heartbeat(account, now) },
        ) {
            before()
            then("Nothing is pressed: there is nothing to press for.") {}
            after()
            result(
                "A sentence instead of dashes. Zeroes would read as a fault and as a measurement " +
                    "at the same time, and neither is true - the log simply has nothing in it yet.",
            )
        }
    }

    @Test
    fun `the window that asks for a name`() {
        // **A prompt is the smallest window and the most common one**: a new file, a new folder, a
        // rename and a move all end here, so a picture of it is a picture of four features.
        scenario(
            compose, "15-a-window-asking-for-a-name",
            "A window asking for a name.",
            "the shape every new file, new folder, rename and move ends in.",
            screen = {
                window {
                    PromptDialog(
                        title = "New file",
                        label = "Name",
                        initial = "",
                        confirm = "Create",
                        onDismiss = {},
                        onConfirm = {},
                    )
                }
            },
        ) {
            before()
            then("Nothing is typed: this is the window as it opens, before a name exists.") {}
            after()
            result(
                "One field, one button, and a way out - and the button is named for the act " +
                    "rather than for the window, so a person knows what pressing it does.",
            )
        }
    }

    @Test
    fun `the window that asks before something is lost`() {
        scenario(
            compose, "16-a-window-asking-before-a-loss",
            "A window asking before something is lost.",
            "deleting, which is the one act the app stops to ask about.",
            screen = {
                window {
                    ConfirmDialog(
                        title = "Delete milk.txt?",
                        text = "It goes to the folder's deleted files and can be restored from there.",
                        confirm = "Delete",
                        destructive = true,
                        onDismiss = {},
                        onConfirm = {},
                    )
                }
            },
        ) {
            before()
            then("Nothing is confirmed: the picture is the question, not the answer to it.") {}
            after()
            result(
                "The button is in the error colour, the sentence says where the file goes, and " +
                    "there is a way out - which is what makes a destructive act safe to offer.",
            )
        }
    }

    @Test
    fun `the window that offers to remove an account`() {
        scenario(
            compose, "17-a-window-removing-an-account",
            "A window offering to remove an account.",
            "the one place the app can delete work that is not on this phone.",
            screen = {
                window {
                    RemoveAccountDialog(
                        login = "muffyuniverse",
                        onDismiss = {},
                        onRemove = {},
                    )
                }
            },
        ) {
            before()
            then("Nothing is removed.") {}
            after()
            result(
                "The window names the account, says what stays and what goes, and offers the " +
                    "copies as a separate decision rather than a default.",
            )
        }
    }

    @Test
    fun `the window that offers to delete a project`() {
        scenario(
            compose, "18-a-window-deleting-a-project",
            "A window offering to delete a project.",
            "a project's own folder, and the choice of where it is deleted from.",
            screen = {
                window {
                    DeleteProjectDialog(project = "groceries", onDismiss = {}, onDelete = {})
                }
            },
        ) {
            before()
            then("Nothing is deleted.") {}
            after()
            result(
                "Both outcomes are named - here, or everywhere - because a person deleting a " +
                    "project is deciding about two copies and the window has to say so.",
            )
        }
    }

    @Test
    fun `the window that adds a project, with nothing to add`() {
        scenario(
            compose, "19-a-window-adding-a-project",
            "A window adding a project, before anything is signed in.",
            "an address field that works on any host, and making one here.",
            screen = {
                window {
                    AddProjectDialog(
                        onDismiss = {},
                        onNewProject = {},
                        onAddUrl = { _, _ -> },
                    )
                }
            },
        ) {
            before()
            then("Nothing is picked: there is nothing to pick.") {}
            after()
            result(
                "An address is checked with ls-remote before anything is cloned, so it works on " +
                    "any host that speaks git - and a project on this phone is offered beside it, " +
                    "because a repository is no longer what makes something a project.",
            )
        }
    }

    @Test
    fun `the window that makes a new project`() {
        scenario(
            compose, "20-a-window-making-a-project",
            "A window making a new project.",
            "a name, and nothing else to decide.",
            screen = {
                window {
                    NewProjectDialog(onDismiss = {}, onCreate = {})
                }
            },
        ) {
            before()
            then("Nothing is created.") {}
            after()
            result(
                "The name is the whole question, because a project on this phone is a folder with " +
                    "its own history - the licence belongs where the repository is made, on the " +
                    "host's own page, where visibility and description are asked in the same breath.",
            )
        }
    }

    @Test
    fun `the window that asks for a key`() {
        scenario(
            compose, "21-a-window-asking-for-a-key",
            "A window asking for a key.",
            "the one field the model cannot work without.",
            screen = {
                window {
                    ModelKeyDialog(onDismiss = {}, error = null, onSave = {})
                }
            },
        ) {
            before()
            then("Nothing is typed: a key is not something a picture should contain.") {}
            after()
            result(
                "One field, and no key in the picture - which is the point of a window that " +
                    "takes one.",
            )
        }
    }

    // **The sync window is gone, and this says why rather than leaving a gap.** It existed so a
    // person could name the commit their push would make. The operator moved the decision to the
    // agent — "we can remove this functionality and transfer it to agent so that it can determine
    // when to push" — so there is no window, no button and no note to type. The scenario numbering
    // is left alone: 22 is missing on purpose, because renumbering would silently change what every
    // later scenario number means in a log or a report.

    @Test
    fun `the window that reports a problem`() {
        val broken = Trace.Broken(id = 7, at = 1_000L, what = "sync", detail = "groceries", waitedMs = 30_000L)
        scenario(
            compose, "23-a-window-reporting-a-problem",
            "A window reporting a problem.",
            "a tap that never answered, which is what a person sends when something is stuck.",
            screen = {
                window {
                    ReportDialog(waiting = listOf(broken), failures = emptyList(), onDismiss = {}, onSend = {})
                }
            },
        ) {
            before()
            then("Nothing is sent: a report is a decision, not a side effect.") {}
            after()
            result(
                "The window says what is stuck, in the words of the act that got stuck, so a " +
                    "report can be understood without opening the log.",
            )
        }
    }

    @Test
    fun `the window that sets a project's address`() {
        // **Where publishing and renaming became one press.** A local project given an address is
        // published; a project whose address names something else is renamed after it. Pictured
        // because the consequence is invisible in the field: typing an address renames a folder, and
        // the window is the only place that says so.
        scenario(
            compose, "29-a-window-setting-the-address",
            "A window giving a project an address.",
            "the field, the promise that it is checked first, and the name the folder will take.",
            screen = {
                window {
                    SetAddressDialog(
                        project = "groceries",
                        current = "",
                        onDismiss = {},
                        onSet = {},
                    )
                }
            },
        ) {
            before()
            then("Nothing is pointed anywhere: the address is checked before it is used.") {}
            after()
            result(
                "The window says the address is checked with ls-remote before anything changes, so " +
                    "a mistyped path is a sentence rather than a project that claims to be " +
                    "published. It also says the folder takes the address's last segment - the " +
                    "consequence a person cannot see in a text field.",
            )
        }
    }

    @Test
    fun `the window that keeps a synced project on this phone`() {
        // **The opposite direction, and it promises nothing it does not do.** The repository stays
        // on GitHub; what stops is the sending. Pictured because the sentence that says so is the
        // whole dialog - a person deciding whether to do this needs to know their history survives.
        scenario(
            compose, "30-a-window-keeping-local",
            "A window keeping a synced project on this phone only.",
            "the promise that nothing is deleted, and where the repository still is.",
            screen = {
                window {
                    KeepLocalDialog(
                        project = "groceries",
                        address = "https://gitlab.com/owner/groceries.git",
                        onDismiss = {},
                        onKeepLocal = {},
                    )
                }
            },
        ) {
            before()
            then("Nothing is kept local: the window explains before it acts.") {}
            after()
            result(
                "It says what stays (every version, here) and what happens to the remote (nothing " +
                    "it keeps what was pushed) and names the address it is still at, because " +
                    "'still on GitHub' was only ever true for projects that were on GitHub.",
            )
        }
    }

    @Test
    fun `the window that asks for a GitHub token`() {
        // **The one window that asks for a secret**, and it is pictured so the masked field and the
        // sentence about where the token goes can be reviewed without signing in on a phone.
        scenario(
            compose, "31-a-window-signing-in",
            "A window asking for a GitHub token.",
            "the only secret the app asks for, with the sentence that says where it is kept.",
            screen = {
                window { SignInDialog(onDismiss = {}, onSignIn = {}) }
            },
            // **No frozen clock, and it was tried.** The window holds a text field, and the first
            // theory was that its caret never lets the clock settle - but `ModelKeyDialog` has a
            // password field in a dialog too and its scenario has always passed, so the field was
            // never the cause. What hung was a `setContent` that put the dialog alone in a bare
            // `Surface`; `window {}` puts a `Box` behind it, which is the shape every working window
            // scenario uses.
        ) {
            before()
            // **The assertions live here because here is where the window renders.** A standalone
            // test of this dialog in `BrowserChromeTest` hung on every reading of the semantics
            // tree - five theories were tried (the clock, a `Surface`, a `Box`, `GraphicsMode`, the
            // `waitForIdle` after `setContent`) and none was the cause. The scenario file composes
            // the identical dialog and reads it without trouble, so the words are checked where the
            // reading works rather than where it does not.
            //
            // **Not a weaker test for being in a scenario file**: `there()` fails the test, and the
            // suite is run by CI, so a sentence that went missing would fail a build exactly as it
            // would from the other file.
            assertTrue("it asks for the key", there("Personal access key"))
            // **All three, because a token missing one fails in a way that does not name it.** A
            // token without `workflow` pushes every file except a workflow, and GitHub answers with
            // the same "Resource not accessible" that a dozen other refusals produce.
            //
            // **They are a list now, not a sentence**, because the operator asked for the requirements
            // "to the point. It should say what type of token to supply and what access scopes it
            // needs." One bullet each, read from the same rule the refusal reads — so the screen
            // cannot promise a scope the check does not demand.
            assertTrue("it says which kind of key", there("A classic token, with these three scopes ticked:"))
            for (scope in TokenScopes.REQUIRED) {
                assertTrue("and names $scope", there("•  $scope"))
            }
            // **And says which kind of token.** One token type means one instruction; a fine-grained
            // token fails the same way with different words, so the dialog names the supported path
            // rather than leaving a person to discover it from a 403.
            assertTrue("and says which kind of token", there("classic token"))
            assertTrue("and says whose commits they will be", there("belongs to"))
            assertTrue("it names GitHub in the title", there("Sign in to GitHub"))
            // **The button is "Sign in", the title is "Sign in to GitHub"** - two different strings,
            // and the button is disabled until something is typed, which is the rule worth stating:
            // a window that let you press it empty would ask GitHub a question with no answer.
            assertTrue("and offers the act", there("Sign in"))
            then("Nothing is typed: the field is empty and the button says what it will do.") {}
            after()
            result(
                "The field is masked, the sentence says the token stays on this phone, and the " +
                    "button is the act rather than a checkbox - a person who has pasted a token " +
                    "should not have to find a second control to use it.",
            )
        }
    }

    @Test
    fun `the window showing the device-flow code`() {
        // **The operator's report, twice**: *"it gives the code but I cant copy it"* and *"it gives a
        // link to open but it is not interactive and also not selectable"*. Both were true — the code
        // was a plain `Text` with no selection container, and the address was a fragment of a
        // sentence rather than a link.
        //
        // This scenario is the picture of the fixed window, and the assertions are the two defects.
        // **Two states of the same window**, because the two defects were about what a *press* can
        // reach rather than what is on the screen: the code could not be selected, and the address
        // could not be tapped. So the second picture is the address having been tapped — the dialog
        // reports the act and this stands in for the browser that opens.
        val opened = mutableStateOf<String?>(null)
        scenario(
            compose, "35-a-window-with-a-code-to-type",
            "A window showing a code to type on github.com.",
            "the code is selectable, and the address is a link that opens a browser.",
            screen = {
                window {
                    SignInDialog(
                        onDismiss = {},
                        device = AppViewModel.Device(
                            code = "ABCD-1234",
                            where = "https://github.com/login/device",
                        ),
                        onOpenWhere = { opened.value = it },
                        onSignIn = {},
                    )
                }
            },
        ) {
            before()
            assertTrue("the code is shown", there("ABCD-1234"))
            assertTrue("the address is shown", there("github.com/login/device"))
            assertTrue("and it is offered as a way to finish", there("Waiting for you to approve"))
            // **The key field is still here**, which is the backup the operator asked for: a person
            // whose browser will not cooperate is not stuck on a code they cannot use.
            assertTrue("the backup is present", there("Personal access key"))

            // **The press is checked, and the picture is of the window unchanged.**
            //
            // The link opens a *browser* — it leaves this app — so there is no second screen here to
            // photograph, and pretending otherwise made the two pictures identical while the
            // scenario claimed an act. What the picture can show is the address underlined and
            // tappable; what the assertion shows is that the tap arrives with the right URL.
            compose.onNodeWithText("https://github.com/login/device").performClick()
            assertTrue(
                "**the press asked to open the address**: ${opened.value}",
                opened.value == "https://github.com/login/device",
            )
            then("Nothing is typed and nothing opens here: the link leaves this app.") {}
            after()
            result(
                "The code is monospaced and selectable so it can be copied, and the address is " +
                    "underlined and tappable - a link that only looked like one was the bug.",
            )
        }
    }

    @Test
    fun `the window that picks where a thing goes`() {
        // **A browser, not a list of paths.** The reported defect was a flat list of every folder,
        // whose shape was invisible; this is one layer at a time, with the folder being moved shown
        // as unchoosable rather than absent.
        val root = File(scene.root, "place-root").apply { mkdirs() }
        val notes = File(root, "notes").apply { mkdirs() }
        File(root, "photos").mkdirs()
        File(notes, "drafts").mkdirs()
        scenario(
            compose, "32-a-window-choosing-a-place",
            "A window choosing where a folder goes.",
            "one layer of the account at a time, with the folder being moved offered but not choosable.",
            screen = {
                window {
                    PlaceDialog(
                        title = "Move notes",
                        verb = "Move",
                        root = root,
                        forbidden = listOf(notes),
                        onDismiss = {},
                        onPick = {},
                    )
                }
            },
        ) {
            before()
            then("Nothing is moved: the current folder is shown and its children are listed.") {}
            after()
            result(
                "The verb is a parameter and every sentence is built from it, so a copy can never " +
                    "be described with the word move - which is the one way a person could be told " +
                    "the wrong thing about whether their original still exists.",
            )
        }
    }
}
