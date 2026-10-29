package com.example.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.robolectric.RuntimeEnvironment
import androidx.test.espresso.IdlingPolicies
import java.util.concurrent.TimeUnit
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
// **A real phone window.** Without a size qualifier the calendar dialog lays out at
// Robolectric's tiny default and Material's date grid composes no day cells at all.
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
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

    /** The chat, which needs a project and a conversation in it before it has anything to show. */
    @Composable
    private fun chatScreen() = Screen { AgentChatScreen(viewModel = viewModel, onBack = {}) }

    /** What the app did: the trace's own screen, with a report that has something in it. */
    @Composable
    private fun observability(report: Trace.Report) = Screen {
        ObservabilityScreen(report = report, onBack = {}, onShare = {}, onClear = {})
    }

    /** The cost table, over a real log so the numbers in the picture are numbers. */
    @Composable
    private fun stats(accountDir: java.io.File, now: Long) = Screen {
        // **The reader is handed straight through rather than left null.** A null reader means
        // "rows were supplied already", so with none the screen would sit on "reading" for ever and
        // the picture would be of a screen that never finished. Reading synchronously here is
        // honest: a scenario has one account and one thread and nothing to keep responsive.
        StatsScreen(
            accountDir = accountDir,
            onBack = {},
            now = now,
            // **The clock a preset reads when tapped**, pinned so a picture does not depend on the
            // day CI runs.
            clockNow = { now },
            // **Opened on this thread, and never closed - deliberately.**
            //
            // The screen does not own the handle (the view model does, in the app), so a scenario
            // must not expect it to be closed. Closing it here would be worse than leaking it: the
            // content lambda is re-composed for the second half of every scenario, so the close
            // would land on the handle the *next* composition had just opened, and the failure
            // would surface as "attempt to re-open an already-closed object" from a query with no
            // visible connection to the cause.
            //
            // The process is one test JVM and the file is a temp directory the harness deletes, so
            // an unclosed handle costs nothing and cannot outlive the run.
            readRows = { onDone -> onDone(RequestDb.read(accountDir)) },
        )
    }

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
                calls = listOf(Call("call-1", "tree", """{"path":""}""")),
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

        // A morning of turns, each with a warm cache, then the keepalive alone for the afternoon -
        // which is the shape a real day has and the one the two blocks exist to show apart.
        var at = now - 5 * 3_600_000L
        repeat(4) {
            RequestDb.write(account, at, RequestLog.Stats(
                kind = RequestLog.Stats.Kind.TURN, ms = 4_100,
                provider = "deepinfra/fp8/flex", model = "deepseek/deepseek-v4.1-flash",
                usage = usage(prompt = 6_000, cached = 5_500, completion = 600, thinking = 90, cost = 0.00071),
                finish = "stop",
            ))
            at += 12 * 60_000L
        }
        at = now - 2 * 3_600_000L
        repeat(18) {
            RequestDb.write(account, at, RequestLog.Stats(
                kind = RequestLog.Stats.Kind.PING, ms = 1_840,
                provider = "deepinfra/fp8/flex", model = "deepseek/deepseek-v4.1-flash",
                usage = usage(prompt = 3_691, cached = 3_584, completion = 1, thinking = 1, cost = 0.0000241),
                finish = "length",
            ))
            at += 4 * 60_000L
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
            "What it cost.",
            "the request history summed over an interval chosen with two pickers.",
            screen = { stats(account, now) },
        ) {
            before()
            then(
                "Nothing is pressed: the table is the history summed over the interval the " +
                    "pickers already name, and `Generated` shows there is nothing new to ask for.",
            ) {}
            after()
            result(
                "Two picks and one column. The interval is stated rather than inferred: From " +
                    "and To sit above the table, and every number below is that interval summed. " +
                    "One figure per metric, which is what a person asks for - not five nested " +
                    "views of one instant that each need a rule for where they start. " +
                    "The 22 requests are read from three SQLite tables - minutes, hours, days - " +
                    "cut into disjoint slices so nothing is counted twice, which is the whole " +
                    "reason the levels are separate tables rather than lines in one file.",
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
            "What it cost, before anything was asked.",
            "an empty history, which has to say so rather than show a grid of zeroes.",
            screen = { stats(account, now) },
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
    fun `a preset chosen from the rolling row`() {
        // **The two rows side by side, and what choosing from one does to the other.** Clock "1 day"
        // is lit when the screen opens; tapping rolling "6 hours" must light that instead and leave
        // the clock row dark, because two lit chips would name two intervals and the figures can
        // only be one of them.
        scene.signedIn()
        val account = scene.workspace?.accountDir ?: error("signed in, so there is one")
        val now = java.time.LocalDate.parse("2026-10-28")
            .atTime(14, 30).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        repeat(6) { i ->
            RequestDb.write(account, now - (i + 1) * 600_000L, RequestLog.Stats(
                kind = RequestLog.Stats.Kind.TURN, ms = 2_000,
                provider = "deepinfra/fp8/flex", model = "deepseek/deepseek-v4.1-flash",
                usage = org.json.JSONObject(
                    """{"prompt_tokens":4000,"completion_tokens":200,"cost":0.0004,
                       "prompt_tokens_details":{"cached_tokens":3000}}""",
                ),
                finish = "stop",
            ))
        }

        scenario(
            compose, "28-a-preset-from-the-rolling-row",
            "A preset chosen from the rolling row.",
            "two rows of intervals, one selection across both.",
            screen = { stats(account, now) },
        ) {
            before()
            then("`6 hours` is tapped in the rolling row.") {
                // **Scrolled into view first**: the row scrolls sideways and the chip sits past the
                // right edge, so a tap without scrolling has no position to tap.
                // **Named by row**, because "6 hours" is a chip in both rows and they mean
                // different things: one starts at 03:00, the other six hours before the tap.
                compose.onNodeWithContentDescription("Rolling 6 hours").performScrollTo().performClick()
                compose.waitForIdle()
            }
            after()
            result(
                "Rolling six hours: the start moved to 08:30, six hours before the tap, and the " +
                    "clock row went dark. The figures were recomputed at once - a preset is itself " +
                    "a request for them, so Generate has nothing left to do.",
            )
        }
    }

    @Test
    fun `the table waiting for Generate`() {
        // **The button's other state, and the reason it exists.** A minute stepper can move sixty
        // times on the way to a time; re-reading the history on each of those is work nobody asked
        // for, and numbers that move under a thumb are numbers a person cannot compare. So the
        // figures describe the last generated interval, and the button says when the picks have
        // moved away from it.
        scene.signedIn()
        val account = scene.workspace?.accountDir ?: error("signed in, so there is one")
        val now = java.time.LocalDate.parse("2026-10-28")
            .atTime(14, 30).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        // A few responses, so the table has figures to hold still while the picks move.
        repeat(5) { i ->
            RequestDb.write(account, now - (i + 1) * 60_000L, RequestLog.Stats(
                kind = RequestLog.Stats.Kind.TURN, ms = 2_000,
                provider = "deepinfra/fp8/flex", model = "deepseek/deepseek-v4.1-flash",
                usage = org.json.JSONObject(
                    """{"prompt_tokens":4000,"completion_tokens":200,"cost":0.0004,
                       "prompt_tokens_details":{"cached_tokens":3000}}""",
                ),
                finish = "stop",
            ))
        }

        scenario(
            compose, "27-the-table-waiting-for-generate",
            "The table waiting for Generate.",
            "picks that have moved away from the interval the figures describe.",
            screen = { stats(account, now) },
        ) {
            before()
            then("the start's **date** is opened and a different day committed with `Show`.") {
                // The date rather than the dial: a day is one tap away from the current one, while
                // the dial needs a precise drag that a Robolectric tap cannot make.
                // **The first of the two**, because the default interval is today at both ends - so
                // both buttons read the same date and only the From one is wanted.
                compose.onAllNodesWithText("28 Oct 2026")[0].performClick()
                compose.waitForIdle()
                // **Named as a selectable, because a bare "26" is not unique in the calendar** - the
                // year-navigation row can carry the same digits, and the day cell is the selectable one.
                compose.onNodeWithText("26").performClick()
                compose.onNodeWithText("Show").performClick()
                compose.waitForIdle()
            }
            after()
            result(
                "The button now offers to Generate, and the figures above it are still the ones " +
                    "for the interval that was last generated - not a half-set-up one. Pressing " +
                    "it is what moves them.",
            )
        }
    }

    @Test
    fun `the window that picks when the table is about`() {
        // **The one popup the cost table has, and it offers no minute at all.** The table counts
        // whole hours, so the dialog offers the hour alone - a decision only a picture can confirm,
        // because "the minute stepper is gone" is equally true of a dialog that lost the hour too.
        // The `:00` beside the stepper is the whole of the fix: it says the value IS the hour, not
        // the hour part of a time that has minutes somewhere else.
        //
        // Material's calendar is above the steppers: the day is the platform's own picker because a
        // hand-written calendar is a month of work that would still not match the phone's, and only
        // the hour is ours.
        scene.signedIn()
        val account = scene.workspace?.accountDir ?: error("signed in, so there is one")
        val now = java.time.LocalDate.parse("2026-10-28")
            .atTime(14, 30).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

        scenario(
            compose, "25-the-window-that-picks-a-time",
            "The window that picks a time.",
            "a dial for the time, in its own window.",
            screen = { stats(account, now) },
        ) {
            before()
            then("the start's **time** is pressed, which opens the dial.") {
                // **The dial, not the calendar**, and that is the point of the picture: the two are
                // separate windows now, each doing one job. The time button is the one reading the
                // interval's own start.
                compose.onNodeWithText("00:00").performClick()
            }
            after()
            result(
                "Material's own dial: drag the hour hand, then the minute hand. It has a window to " +
                    "itself, which is what the previous design could not give it - two attempts to " +
                    "put time controls under a calendar ended with them below the bottom of the " +
                    "dialog. The date has its own window beside it.",
            )
        }
    }

    // ---- the windows ---------------------------------------------------------

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
            "the empty state of the list of repositories on GitHub.",
            screen = {
                window {
                    AddProjectDialog(
                        repositories = emptyList(),
                        alreadyHere = emptySet(),
                        loading = false,
                        error = null,
                        onDismiss = {},
                        onNewProject = {},
                        onPick = {},
                    )
                }
            },
        ) {
            before()
            then("Nothing is picked: there is nothing to pick.") {}
            after()
            result(
                "An empty list says it is empty rather than looking like a list that failed to " +
                    "load, and making a project on this phone is offered beside it.",
            )
        }
    }

    @Test
    fun `the window that makes a new project`() {
        scenario(
            compose, "20-a-window-making-a-project",
            "A window making a new project.",
            "a name, a licence, and nothing else to decide.",
            screen = {
                window {
                    NewProjectDialog(licences = emptyList(), local = true, onDismiss = {}, onCreate = { _, _ -> })
                }
            },
        ) {
            before()
            then("Nothing is created.") {}
            after()
            result(
                "The name and the licence are the whole question, and the licence's own sentence " +
                    "is under it rather than hidden behind a link.",
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

    @Test
    fun `the window that syncs, with changes to send`() {
        scenario(
            compose, "22-a-window-syncing",
            "A window syncing, with two changes waiting.",
            "the message the sync will be committed under.",
            screen = {
                window {
                    SyncDialog(project = "groceries", changes = 2, onDismiss = {}, onSync = {})
                }
            },
        ) {
            before()
            then("Nothing is synced.") {}
            after()
            result(
                "The window says how many changes are going and lets the message be changed, " +
                    "because the message is what the history will say about them for ever.",
            )
        }
    }

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
}
