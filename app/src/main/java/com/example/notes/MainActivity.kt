package com.example.notes

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import kotlinx.coroutines.flow.filterNotNull

class MainActivity : ComponentActivity() {
    /**
     * **The trace says whether the app is in front, on every line.**
     *
     * A log of what happened while a phone was in a pocket reads differently from a log of what
     * happened while somebody was looking at it — a background kill, a session that kept polling,
     * a tap that "did nothing" because the screen was not the one on top. `cls` rather than
     * `closed`, because the column is four wide and `cls` cannot be mistaken for the app being
     * shut down.
     */
    override fun onStart() {
        super.onStart()
        Trace.state = "open"
        Trace.note("app-shown", "")
    }

    override fun onStop() {
        Trace.state = "cls"
        Trace.note("app-hidden", "")
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // **Before anything opens a repository.** JGit finds the LFS filter through an SPI, and an
        // unregistered filter is a silent no-op: the commit stores the picture's raw bytes and the
        // attribute is ignored, with nothing in any log to say why. Registered here as well as in
        // `ProjectGit.init`, because this is the one place every launch goes through and the call is
        // idempotent.
        GitService.registerLfs()

        setContent {
            val viewModel: AppViewModel = viewModel()
            val settings = viewModel.settings

            MaterialTheme(
                colorScheme = if (settings.useDark) settings.theme.darkScheme()
                else settings.theme.lightScheme(),
            ) {
                // **The selection handles are the app's colours, not the platform's black.**
                //
                // The operator's screenshots of report 88 show the range highlighted correctly and
                // the two handles as solid black teardrops that cover the words under them, plus no
                // toolbar. The cause is not the selection plumbing — a range IS made, which is what
                // #42 could not establish — it is that **nothing here ever said what a handle looks
                // like.** `LocalTextSelectionColors` defaults to `Color.Black` for the handle and a
                // translucent blue for the background, and this app has a whole theme system the
                // selection never asked about.
                //
                // So a person on a dark theme gets black handles on a near-black bubble, and on a
                // light theme black blobs over the words. Both read as "selection is broken", and
                // both are one provider away from being right.
                CompositionLocalProvider(
                    LocalTextSelectionColors provides TextSelectionColors(
                        handleColor = MaterialTheme.colorScheme.primary,
                        backgroundColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f),
                    ),
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        AppRoot(viewModel)
                    }
                }
            }
        }
    }
}

/** The dialog that can be open over the browser. */
private sealed interface Pending {
    data object None : Pending
    data object SignIn : Pending
    /** Which door: GitHub's, or another host's. Both end in the one save. */
    data object ChooseAccountDoor : Pending
    /** The door for every host this app has no API for. */
    data object AddHostAccount : Pending
    data object ModelKey : Pending
    data object AddProject : Pending
    data object NewProject : Pending
    /** **Which account**, because the list shows several and the row that was tapped decides. */
    data class RemoveAccount(val login: String) : Pending
    /** A new branch in the project whose branches are on screen (§13d). */
    data object NewBranch : Pending
    /** Removing a branch, which is refused when it carries work main does not have. */
    data class RemoveBranch(val branch: String) : Pending
    /**
     * **The one project verb besides add and delete** — publishing and renaming are its two outcomes.
     * A local project given an address is published; a project whose address names something else is
     * renamed after it.
     */
    data class SetAddress(val project: String) : Pending
    data class DeleteProject(val project: String) : Pending
    data class Release(val project: String) : Pending
    /** Synced -> local: the remote goes, every version stays. */
    data class KeepLocal(val project: String) : Pending
    data class RenameEntry(val entry: Entry) : Pending
    data class NewFile(val folder: File) : Pending
    data class NewFolder(val folder: File) : Pending
    /** A blank picture, at a size the person picks. */
    data class NewImage(val folder: File) : Pending
    data class MoveEntry(val entry: Entry) : Pending
    data class CopyEntry(val entry: Entry) : Pending
    data object CopySelected : Pending
    data object MoveSelected : Pending
    data object DeleteSelected : Pending
    data class RestoreDeleted(val folder: File) : Pending
    data class DeleteEntry(val entry: Entry) : Pending
    /** What the project is: the one-word caption in the bar, tapped. */
}

/**
 * The whole app: a browser over the account's tree, an editor, the history of a
 * file, settings, and the dialogs that change things.
 *
 * The screens do not own anything; every act goes through the view model, which
 * writes to disk and records what it did in the account's history.
 */
@Composable
private fun AppRoot(viewModel: AppViewModel) {
    var showSettings by remember { mutableStateOf(false) }
    // **The vault's door**, opened from settings. Its own flag rather than a Pending, because it is a
    // screen and not a window: the list is edited in place, and a dialog would have to close to show
    // what the edit did.
    var showSecrets by remember { mutableStateOf(false) }
    var showTrace by remember { mutableStateOf(false) }
    // **The cost table, opened from the projects screen's bar.** It reads the request logs when it
    // is opened and not while it is open: the log grows on every response, so a table that watched
    // it would move under the reader's eyes and would be re-reading files the agent was writing.
    // The report window. No snapshot to take: the log is already being appended to, so
    // the moment the person writes their sentence is a `REPORT` line and everything
    // above it is the context. That is the whole simplification — one stream, one
    // document, and the button only adds a checkpoint to it.
    var reportOpen by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Pending>(Pending.None) }

    val snackbar = remember { SnackbarHostState() }

    // The trace screen shows the ring, re-read while it is open: a screen that stopped
    // updating would freeze the very thing being watched for not reacting.
    var traceReport by remember { mutableStateOf(Trace.report()) }
    LaunchedEffect(showTrace) {
        while (showTrace) {
            traceReport = Trace.report()
            kotlinx.coroutines.delay(1_000)
        }
    }

    /**
     * The clock, while the app is on the screen.
     *
     * **Fifteen seconds is a decision, not an interval.** The tick does no work when nothing is
     * due, and it is what turns "four minutes passed" into a ping within a quarter of a minute of
     * the right moment — cheap enough to be a coroutine, and coarse enough that it is not a
     * battery question. The state machine decides everything else; this only moves `now` along.
     *
     * The foreground service runs the same rules through the same [AgentRunner] when the app is
     * not open — and while it is open, both tick. That is safe rather than wasteful: a ping writes
     * the session's own `lastPollAt` to disk *before* the request goes out, so whichever clock gets
     * there second reads a session that is not due. The clock is a parameter here, and nothing in
     * this file knows what time it is.
     */
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.tickAgents()
            kotlinx.coroutines.delay(15_000)
        }
    }

    // The settings screen holds the field where a key is typed. While it is up,
    // the window refuses to be screenshotted or appear in the app switcher.
    val context = LocalContext.current
    LaunchedEffect(showSettings, pending is Pending.SignIn, pending is Pending.ModelKey) {
        val window = context.findActivity()?.window ?: return@LaunchedEffect
        if (showSettings || pending is Pending.SignIn || pending is Pending.ModelKey) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    // Every message the view model raises is shown once, then dropped.
    //
    // Collected rather than keyed on, because keying was a trap: `LaunchedEffect`
    // is cancelled when its key changes, and clearing `message` inside it changes
    // the key — so the `showSnackbar` that was suspended in flight was cancelled by
    // the very clearing that was meant to consume it, and the message vanished. A
    // failed sign-in raised at the same moment as anything else was exactly the
    // case that lost its explanation. Collecting keeps the clearing local: the
    // collector sees null next, ignores it, and goes on.
    LaunchedEffect(Unit) {
        snapshotFlow { viewModel.message }
            .filterNotNull()
            .collect { text ->
                viewModel.message = null
                // **Only a sentence that explains something unexpected covers the screen.**
                // The operator's report: "can you remove any in-screen popup notifications that
                // dont bring value and just restate what happened before … notifications should
                // explain why something that user expected to happen didn't happen".
                //
                // The message is still written to the trace by `AppViewModel.message`'s setter, so
                // nothing is lost: a confirmation moves from a strip nobody reads to the log that
                // records what happened. [Said] is where the rule lives, because it is a decision
                // about the app's own words rather than a condition in an effect.
                if (Said.worthShowing(text)) snackbar.showSnackbar(text)
            }
    }

    val editing = viewModel.editingFile
    val historyPath = viewModel.historyPath
    val chatProject = viewModel.chatProject

    // The picker is the system's, so the app never asks for storage permission: it
    // sees only what a person just pointed at, and only reads it.
    // **The picker's answer is the press's answer.** Upload hands the person to a
    // system screen, and the app cannot see anything until they come back — with files
    // or with nothing. Recording only the import left `upload files` unanswered in every
    // trace where somebody changed their mind, and the tap that opened the picker was the
    // one press the log could never pair. The tap's id is kept here so the reply is
    // addressed to it rather than to whatever happens to be waiting when the picker closes.
    var pickingFiles by remember { mutableIntStateOf(0) }
    var pickingFolder by remember { mutableIntStateOf(0) }
    val pickFiles = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        Trace.answered(
            pickingFiles,
            if (uris.isEmpty()) "Nothing was chosen" else "Chose ${uris.size} file(s)",
        )
        if (uris.isNotEmpty()) viewModel.importFiles(uris)
    }
    // The one system setting this app needs, opened the way the pickers are: the press is recorded
    // before the screen opens and answered when the person comes back, and the state is read again
    // at that moment because the setting is the phone's and this app is not told when it changes.
    var batteryAsked by remember { mutableIntStateOf(0) }
    var openingBattery by remember { mutableIntStateOf(0) }
    val askBattery = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(),
    ) {
        Trace.answered(openingBattery, Battery.said(context))
        batteryAsked++
    }
    // The notification permission, asked the same way: the press is recorded before the dialog
    // opens and answered when the person is back, and the fact is read again at that moment
    // because only the phone knows the answer.
    var noticesAsked by remember { mutableIntStateOf(0) }
    var openingNotices by remember { mutableIntStateOf(0) }
    val askNotices = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) {
        Trace.answered(openingNotices, Notices.said(context))
        noticesAsked++
    }
    val pickFolder = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        Trace.answered(pickingFolder, if (uri == null) "Nothing was chosen" else "Chose a folder")
        if (uri != null) viewModel.importFolder(uri)
    }

    // **Back from a project screen returns to the accounts list**, which is what makes the folders a
    // hierarchy rather than a mode. A back from the account's root (path is empty) leaves it; a back
    // from inside a project's folders goes up one folder first, as it always did.
    BackHandler(
        enabled = !showTrace && !showSettings && !showSecrets && pending is Pending.None &&
            !viewModel.onAccountsScreen,
    ) {
        if (viewModel.path.isEmpty() && viewModel.editingFile == null && chatProject == null) {
            viewModel.leaveAccount()
        } else if (viewModel.path.isNotEmpty()) {
            viewModel.up()
        }
    }
    BackHandler(enabled = showTrace) { showTrace = false }
    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = showSecrets) { showSecrets = false }

    /**
     * The bug button, wired once.
     *
     * What the trace cannot know is added here — which screen, which folder, which
     * file, whose account — because that is what a reader needs to reproduce the
     * thing, and it is exactly what the app's own record leaves out.
     */
    val openReport: () -> Unit = {
        // One line saying where they were, so the checkpoint is not the only thing on
        // record about the moment — but no snapshot: the log below it is the state.
        Trace.note(
            "report-opened",
            (when {
                showTrace -> "trace"
                showSecrets -> "secrets"
                showSettings -> "settings"
                historyPath != null -> "history/${historyPath.last()}"
                editing != null -> "editor/${editing.name}"
                else -> "browser/" + (viewModel.path.ifEmpty { listOf("/") }.joinToString("/"))
            }) + " · " + (viewModel.activeLogin ?: "nobody") +
                " · projects=" + (viewModel.projectNames.joinToString(",").ifEmpty { "none" }),
        )
        reportOpen = true
    }

    // **The trace answers taps that never reach the view model.** Cancel, opening a
    // menu, choosing a row: those change what is on the screen without changing the
    // folder, so `refresh()` cannot see them. Watching the pieces of screen state that
    // this composable owns is what keeps "every element leaves a trace" from being a
    // claim with a hole in it where the local controls are.
    //
    // **Which screen, not just which dialog.** The editor and the history were left out
    // of this key, and the live log showed the cost: `open-file` reached the editor with
    // nothing recorded, so the tap waited unanswered until a later screen change claimed
    // it — the file being opened was the one thing a reader most wanted a line about.
    // They are named here rather than read from `viewModel` because the editor is this
    // composable's screen the moment `editing` is set, whatever `pending` says.
    LaunchedEffect(pending, showSettings, showTrace, editing, historyPath, chatProject) {
        Trace.changed(
            "screen " + when {
                showTrace -> "trace"
                showSecrets -> "secrets"
                showSettings -> "settings"
                historyPath != null -> "history"
                editing != null -> "editor"
                chatProject != null -> "chat/$chatProject"
                else -> "browser"
            } + " dialog " + pending.javaClass.simpleName,
        )
    }
    BackHandler(enabled = !showSettings && historyPath != null) {
        viewModel.closeHistory()
    }
    BackHandler(enabled = !showSettings && historyPath == null && editing != null) {
        viewModel.closeEditor()
    }
    // The conversation is a screen of its own, so back leaves it before it moves the folder
    // underneath it — the same rule as the editor and the history.
    BackHandler(
        enabled = !showSettings && historyPath == null && editing == null && chatProject != null
    ) {
        viewModel.closeChat()
    }
    // **Back stops the choosing first.** Being in the middle of choosing several
    // things is a mode, and a mode is what back leaves first; going up a folder
    // instead would take the rows away from under the selection.
    BackHandler(
        enabled = !showSettings && historyPath == null && editing == null &&
            viewModel.selected.isNotEmpty()
    ) {
        viewModel.clearSelection()
    }

    BackHandler(
        enabled = !showSettings && historyPath == null && editing == null &&
            viewModel.canGoUp
    ) {
        viewModel.up()
    }

    // **Every row's control names itself in the trace**, with the row's own name, so a
    // report reads like the screen it came from: "history milk.txt", "lock milk.txt".
    val actions = BrowserActions(
        // **A gesture is recorded once, by the thing that does the work.** Every one of
        // these also calls `Trace.tap` inside the view model, so logging here as well
        // gave one press two ids a millisecond apart — and a log where every gesture
        // half-answers itself cannot answer "which taps were never answered". This is
        // why the screen's copy is gone rather than the view model's.
        onOpen = { entry ->
            when (entry.kind) {
                EntryKind.FOLDER -> viewModel.openChild(entry.name)
                else -> viewModel.openEditor(entry.file)
            }
        },
        // **Opening a window is a tap of its own.** Until now only the dialog's button
        // was recorded, so a menu item whose window never appeared left no trace at all —
        // which is exactly the failure a trace exists to catch. The pair is
        // `move` / `confirm move`, and the detail says which file.
        onMove = { entry -> tap("move", entry.name); pending = Pending.MoveEntry(entry) },
        onCopy = { entry -> tap("copy", entry.name); pending = Pending.CopyEntry(entry) },
        // **The same window, under the name the row gave it.** A file the app keeps offers
        // "Clone…", and a trace that said `copy` would be describing a button the person did not
        // press.
        onClone = { entry -> tap("clone", entry.name); pending = Pending.CopyEntry(entry) },
        // **A tap of its own**, and recorded before the copy is attempted: a copy that fails has to
        // be visible in the trace, or the person sees a message and no evidence.
        onDownload = { entry -> tap("copy to downloads", entry.name); viewModel.copyToDownloads(entry.file) },
        onRename = { entry -> tap("rename", entry.name); pending = Pending.RenameEntry(entry) },
        onDelete = { entry -> tap("delete", entry.name); pending = Pending.DeleteEntry(entry) },
        onTogglePin = { entry -> viewModel.togglePinned(entry.file) },
        onToggleLock = { entry -> viewModel.toggleLocked(entry.file) },
        onHistory = { entry -> viewModel.openHistory(entry.file) },
        onSelect = { entry -> viewModel.toggleSelected(entry.file) },
        // The act records its own tap: it is the view model that knows the path git will match.
        onIgnore = { entry -> viewModel.ignoreInGit(entry.file) },
    )

    when {
        // **Only offered where there is an account to read**, which is the same condition the
        // button is drawn under. `workspace` is non-null exactly when somebody is signed in.
        showTrace -> ObservabilityScreen(
            report = traceReport,
            onBack = { showTrace = false },
            onShare = {
                tap("share log")
                shareTrace(context)
            },
            onReport = openReport,
            onClear = {
                // The ring is emptied and the file is kept, and the clearing is a NOTE of
                // its own — `clearScreen` writes it. Recording the press first would be
                // the second id for the same gesture, so this is deliberately silent.
                Trace.clearScreen()
            },
        )

        showSecrets -> SecretsScreen(
            viewModel = viewModel,
            onBack = { showSecrets = false },
        )

        showSettings -> SettingsScreen(
            viewModel = viewModel,
            batteryAsked = batteryAsked,
            onAskBattery = { openingBattery = tap("battery"); askBattery.launch(Battery.intent(context)) },
            noticesAsked = noticesAsked,
            onAskNotices = {
                openingNotices = tap("notifications")
                val permission = Notices.permission()
                if (permission != null) askNotices.launch(permission)
            },
            onBack = { showSettings = false },
            // **No `onSignIn` / `onRemoveAccount` any more** (§13c.5 step 3): those three acts live
            // on the accounts screen, which is the main menu. Settings keeps only what is local to
            // this phone.
            onSetKey = { tap("set model key"); pending = Pending.ModelKey },
            onTestAccount = { tap("use test account"); viewModel.signInAsTestAccount() },
            onOpenTrace = { showTrace = true },
            onOpenSecrets = { tap("secrets"); showSecrets = true },
            onReport = openReport,
        )

        // History is checked *before* the editor, and the order is the whole of a
        // bug: the editor stays open underneath while its file's history is being
        // looked at, so testing `editing != null` first kept drawing the editor and
        // the history only appeared after Done had closed it.
        historyPath != null -> HistoryScreen(
            viewModel = viewModel,
            title = historyPath.last(),
            onBack = { viewModel.closeHistory() },
            // Select puts the version back and returns to the file: one act, and
            // the person is looking at the result of it.
            onSelect = { viewModel.closeHistory() },
            onReport = openReport,
        )

        editing != null -> EditorScreen(
            viewModel = viewModel,
            file = editing,
            // `closeEditor` records the tap itself; recording it here as well gave
            // one press two ids.
            onBack = { viewModel.closeEditor() },
            // No delete here: deleting is a thing done to a row in the folder, where
            // the row and its neighbours are visible and a mistake is one undo away.
            // A three-dot menu inside the editor put the most destructive command in
            // the app beside Save.
            // **The same element as the row's menu item, so the log can compare them.**
            // `openHistory` records it: one gesture, one id, and one name for one button.
            onOpenHistory = { viewModel.editingFile?.let { viewModel.openHistory(it) } },
                    onReport = openReport,
        )

        // Before the browser: the conversation covers the folder it belongs to.
        chatProject != null -> AgentChatScreen(
            viewModel = viewModel,
            onBack = { viewModel.closeChat() },
            // The same act the browser offers, reaching the same window: a report is a report
            // wherever it is noticed, and the chat is where a wrong answer is noticed.
            onReport = openReport,
        )

        // **The main menu is the list of accounts** (§13c.2). It is a branch of its own rather than a
        // mode inside BrowserScreen, because the two show different things about different subjects —
        // and because the back gesture has to return here from a project screen.
        viewModel.onAccountsScreen -> AccountsScreen(
            viewModel = viewModel,
            onEnter = { login -> viewModel.enterAccount(login) },
            // **The same sign-in the Settings block used**, so adding an account from the list and
            // from Settings were never two mechanisms.
            onAdd = { tap("add account"); pending = Pending.ChooseAccountDoor },
            onRemove = { login -> tap("remove account", login); pending = Pending.RemoveAccount(login) },
            onSettings = { tap("settings"); showSettings = true },
        )

        // **Tapping a project shows its branches first** (§13d). The operator's shape, and it is a
        // branch of its own rather than a mode inside BrowserScreen for the same reason the accounts
        // list is: a different subject, and a back gesture that has to return here.
        viewModel.choosingBranchFor != null -> BranchesScreen(
            project = viewModel.choosingBranchFor.orEmpty(),
            branches = viewModel.choosingBranches,
            unmerged = viewModel.choosingUnmerged,
            // **Entering a branch opens that branch's own worktree**, made on the spot if it is the
            // first time. `enterBranch` also closes the branch screen, and it does so at the right
            // moment — after the folder exists and before the listing is refreshed. Doing that here
            // instead read `choosingBranchFor` *after* clearing it, which opened the account root.
            onEnter = { branch -> viewModel.enterBranch(branch) },
            onAdd = { tap("add branch"); pending = Pending.NewBranch },
            onRemove = { branch -> tap("remove branch", branch); pending = Pending.RemoveBranch(branch) },
            onBack = { viewModel.closeBranches() },
        )

        else -> androidx.compose.foundation.layout.Box {
            BrowserScreen(
                viewModel = viewModel,
                username = viewModel.account?.login,
                onOpenSettings = { tap("settings"); showSettings = true },
                onReport = openReport,
                actions = actions,
                onAddProject = { tap("add project"); pending = Pending.AddProject },
                onRowChat = { project -> viewModel.openChat(project) },
                onRowRename = { project -> tap("set address", project); pending = Pending.SetAddress(project) },
                onRowDelete = { project -> tap("delete project", project); pending = Pending.DeleteProject(project) },
                onRowKeepLocal = { project -> tap("keep local", project); pending = Pending.KeepLocal(project) },
                // **The release button on the project's strip.** It is offered only when nothing is
                // unsent, so the window is reached exactly when a version can honestly be named.
                onRelease = { project -> tap("publish release", project); pending = Pending.Release(project) },
                onCreateFile = { tap("new file"); viewModel.currentDir?.let { pending = Pending.NewFile(it) } },
                onCreateFolder = { tap("new folder"); viewModel.currentDir?.let { pending = Pending.NewFolder(it) } },
                onCreateImage = { tap("new image"); viewModel.currentDir?.let { pending = Pending.NewImage(it) } },
                onUploadFiles = { pickingFiles = tap("upload files"); pickFiles.launch(arrayOf("*/*")) },
                onUploadFolder = { pickingFolder = tap("upload folder"); pickFolder.launch(null) },
                onRestoreDeleted = {
                    tap("restore deleted")
                    viewModel.currentDir?.let { pending = Pending.RestoreDeleted(it) }
                },
                onMoveSelected = { tap("move", "${viewModel.selected.size} chosen"); pending = Pending.MoveSelected },
                onCopySelected = { tap("copy", "${viewModel.selected.size} chosen"); pending = Pending.CopySelected },
                onDeleteSelected = { tap("delete", "${viewModel.selected.size} chosen"); pending = Pending.DeleteSelected },
            )
            // At the bottom, and **clear of the add button**. It used to sit at the
            // bottom centre with 16dp of margin, which is exactly where the button
            // is: the message that said what had just happened covered the control
            // that does the next thing, and both were unreachable at once. A
            // floating button is 56dp tall and inset by 16, so the message starts
            // above it.
            SnackbarHost(
                snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(start = 16.dp, end = 16.dp, bottom = 104.dp),
            )
        }
    }

    // The report sits above every other window, because the moment it is wanted is the
    // moment something else on the screen is not working.
    if (reportOpen) {
        ReportDialog(
            waiting = Trace.broken(),
            failures = Trace.report().failures,
            onDismiss = { reportOpen = false },
            onSend = { note ->
                reportOpen = false
                // **The whole act is one line in the log.** Everything above it is the
                // context, because a log is ordered — nothing has to be captured,
                // frozen, slugged, filed or sent for the report to exist and be found.
                Trace.checkpoint(note)
                // **And nothing is said about it.** The dialog closing is the
                // confirmation, and the log is written as it happens — a toast
                // saying so asks a person to trust a message instead of the file.
                // It also repeated a path nothing on that screen can open.
            },
        )
    }

    // **A link in a document, and what it asked for.** Two effects rather than one `when`, because
    // a link can ask for either and the two land in different places: an address leaves the app, a
    // verb makes it do something. Both are cleared as soon as they are answered, so a recomposition
    // cannot fire them twice.
    //
    // **Here rather than inside `Dialogs`**, because `Dialogs` receives `pending` read-only — it is
    // a function of what to show, not the owner of what is shown, and a link that changes what is
    // shown has to sit where that is writable.
    val linkContext = LocalContext.current
    LaunchedEffect(viewModel.linkToOpen) {
        val address = viewModel.linkToOpen ?: return@LaunchedEffect
        openInBrowser(linkContext, address)
        viewModel.linkOpened()
    }
    LaunchedEffect(viewModel.pendingConnection) {
        val asked = viewModel.pendingConnection ?: return@LaunchedEffect
        viewModel.connectionShown()
        pending = when (asked) {
            AppViewModel.Connection.GitHub -> Pending.SignIn
            AppViewModel.Connection.OpenRouter -> Pending.ModelKey
            // **The token door is the same dialog**, because a pasted token *is* a GitHub key. A
            // separate screen would be a second implementation of the thing that already works.
            AppViewModel.Connection.Token -> Pending.SignIn
        }
    }

    Dialogs(
        viewModel = viewModel,
        pending = pending,
        dismiss = { pending = Pending.None },
        // **A window that opens another window.** The account door is a choice between two dialogs,
        // so the choice has to be able to replace what is on screen — `dismiss` only closes.
        show = { pending = it },
        newProject = { pending = Pending.NewProject },
    )
}

/** Every dialog, in one place, so the screens stay free of state. */
@Composable
private fun Dialogs(
    viewModel: AppViewModel,
    pending: Pending,
    dismiss: () -> Unit,
    show: (Pending) -> Unit,
    newProject: () -> Unit,
) {
    // **This composable's own context**, for the two places that leave the app: the OpenRouter
    // sign-in and a link in a document. The parent has one too, and they are different scopes —
    // reaching across was what compiled into the `context(...)` stdlib call the first time.
    val here = LocalContext.current
    when (pending) {
        Pending.None -> Unit

        Pending.ModelKey -> {
            // **Refused keys keep the window open, with the reason in it.** The save is a write and a
            // read back, and the read back can fail on a phone that is doing nothing wrong. Dismissing
            // on the press turned that refusal into a snackbar at a screen that was already gone, which
            // reads exactly like success.
            var refused by remember { mutableStateOf<String?>(null) }
            ModelKeyDialog(
                onDismiss = { refused = null; dismiss() },
                error = refused,
                onSave = { key ->
                    // The key is never the detail: a trace is a file a person shares.
                    tap("save key", viewModel.account?.login.orEmpty())
                    refused = keySaveVerdict(viewModel.saveModelKey(key)) { dismiss() }
                },
                // **A real browser, not this app's WebView.** RFC 8252 is explicit that a sign-in
                // belongs in an external user agent, and the reason is visible to the person: the
                // address bar is there, so they can see whose page is asking for their password.
                // The app's own WebView is one-shot and headless by design — the opposite of what a
                // login needs — and an embedded page for a sign-in is indistinguishable from a
                // phishing screen.
                onSignIn = {
                    tap("openrouter sign in")
                    val url = viewModel.beginOpenRouterSignIn()
                    openInBrowser(here, url)
                    url
                },
                onCode = { code, answered ->
                    tap("openrouter code")
                    // **Named, because the clock sits between the code and the answer.** With
                    // `now` defaulted in the middle, a trailing lambda lands on the wrong
                    // parameter — which the compiler caught, and which is worth a comment so
                    // nobody "simplifies" it back.
                    viewModel.finishOpenRouterSignIn(code = code, onDone = answered)
                },
            )
        }

        Pending.ChooseAccountDoor -> AddAccountDialog(
            onDismiss = dismiss,
            onGitHub = {
                tap("confirm add account", "github")
                show(Pending.SignIn)
            },
            onAnotherHost = {
                tap("confirm add account", "host")
                show(Pending.AddHostAccount)
            },
        )

        Pending.AddHostAccount -> {
            // **The reason is read back from the view model**, the same way GitHub's door does it: a
            // refusal keeps the window open with the sentence in it, and only a success dismisses.
            var error by remember { mutableStateOf<String?>(null) }
            AddHostAccountDialog(
                onDismiss = dismiss,
                error = error,
                onAdd = { name, host, gitUser, key ->
                    // **The key is never traced.** The name of the act is enough to place it, and a
                    // trace is a file a person shares.
                    tap("confirm add account", "host")
                    viewModel.addHostAccount(name, host, gitUser, key) { ok ->
                        if (ok) dismiss() else error = viewModel.message
                    }
                },
            )
        }

        Pending.SignIn -> {
            // The dialog owns the wait, so the answer lands where the person is
            // looking. A refusal keeps the dialog open with the reason in it; a
            // success is the only thing that dismisses.
            var signingIn by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            // **The device flow's success closes this window, and nothing used to.**
            //
            // The operator's report: *"sign in the github window should be closing on its own when
            // success response from github is received"*. It did not, and the reason is that the
            // two paths into this dialog end differently: the token path calls back with a yes and
            // dismisses, while the device path sets `device = null` on the view model and stops
            // there. So the sign-in had happened — the account was adopted, onboarding closed — and
            // the window sat on top of it still saying "Waiting for you to approve it…".
            //
            // **Watched rather than called back**, because the device flow finishes on the worker
            // thread with no caller to answer: there is no `onDone` to invoke. `signedIn` becoming
            // true is the fact that ends it, and it is the same fact that closes onboarding.
            val nowSignedIn = viewModel.signedIn
            LaunchedEffect(nowSignedIn) {
                if (nowSignedIn && viewModel.device == null) dismiss()
            }
            SignInDialog(
                onDismiss = {
                    // **Leaving stops the poll**, which is what "foreground only" means: a code
                    // nobody is watching for is a code spending the token-request limit for a
                    // person who has walked away.
                    viewModel.cancelDevice()
                    dismiss()
                },
                signingIn = signingIn,
                error = error,
                device = viewModel.device,
                onStartDevice = { viewModel.startDeviceSignIn() },
                // **A real browser, and the same reason as OpenRouter's**: RFC 8252 says a sign-in
                // belongs in an external user agent, and the address bar is what lets a person see
                // whose page is asking. `https://` is added if GitHub ever answers a bare host, so
                // the intent is always a web page rather than something else that parses as a URI.
                onOpenWhere = { where ->
                    // **Traced here, where `tap` lives.** The dialog reports the act and this file
                    // records it, rather than the dialog reaching into a private function in
                    // another file — which is what the compiler refused, correctly.
                    tap("open github device")
                    openInBrowser(
                        here,
                        if (where.startsWith("http")) where else "https://$where",
                    )
                },
                // The login is whatever GitHub says it is, not what was typed, and
                // the question goes to GitHub off the main thread.
                onSignIn = { token ->
                    // The token itself is never recorded: the name of the act is enough to place it,
                    // and a trace is a file a person shares.
                    tap("confirm add account")
                    signingIn = true
                    error = null
                    viewModel.signInWithToken(token) { ok ->
                        signingIn = false
                        if (ok) {
                            dismiss()
                        } else {
                            // The view model has already put the reason in
                            // `message`; read it here so the dialog can show it
                            // rather than a snackbar behind the dialog.
                            error = viewModel.message ?: "That key did not work"
                        }
                    }
                },
            )
        }

        is Pending.RemoveAccount -> viewModel.accounts.account(pending.login)?.let { account ->
            RemoveAccountDialog(
                login = account.login,
                onDismiss = dismiss,
                onRemove = { deleteCopies ->
                      Trace.tap("confirm remove account", account.login + if (deleteCopies) " with copies" else "")
                      viewModel.removeAccount(account.login, deleteCopies)
                  },
            )
        }

        Pending.AddProject -> AddProjectDialog(
            onDismiss = dismiss,
            onNewProject = {
                dismiss()
                newProject()
            },
            onAddUrl = { url, name ->
                // **The address is never traced**: it may name a private repository, and a trace is a
                // file a person shares. The name is the identity, and the name is enough.
                tap("add from address", name)
                dismiss()
                viewModel.addProjectFromUrl(url, name)
            },
        )

        Pending.NewProject -> NewProjectUI(viewModel, dismiss)

        is Pending.SetAddress -> SetAddressDialog(
            project = pending.project,
            // **The address it has now**, so the ordinary use — a repository that moved — starts from
            // what is true rather than from an empty field the person has to retype.
            current = viewModel.remoteUrlOf(pending.project),
            onDismiss = dismiss,
            onSet = { url ->
                Trace.tap("confirm set address", pending.project)
                viewModel.setAddress(pending.project, url)
            },
        )

        is Pending.Release -> ReleaseDialog(
            project = pending.project,
            onDismiss = dismiss,
            onRelease = { version, note ->
                Trace.tap("confirm publish release", version)
                viewModel.tagRelease(version, note)
            },
        )

        is Pending.KeepLocal -> KeepLocalDialog(
            project = pending.project,
            address = viewModel.remoteUrlOf(pending.project),
            onDismiss = dismiss,
            onKeepLocal = {
                Trace.tap("confirm keep local", pending.project)
                viewModel.keepProjectLocal(pending.project)
            },
        )

        is Pending.DeleteProject -> DeleteProjectDialog(
            project = pending.project,
            onDismiss = dismiss,
            onDelete = { everywhere ->
                      Trace.tap("confirm delete project", pending.project + if (everywhere) " everywhere" else " here")
                      viewModel.deleteProject(pending.project, everywhere)
                  },
        )

        is Pending.RenameEntry -> PromptDialog(
            title = "Rename ${pending.entry.name}",
            label = "Name",
            initial = pending.entry.name,
            confirm = "Rename",
            onDismiss = dismiss,
            onConfirm = { name ->
                      Trace.tap("confirm rename", "${pending.entry.name} → $name")
                      viewModel.rename(pending.entry.file, name)
                  },
        )

        is Pending.NewFile -> PromptDialog(
            title = "New file",
            label = "Name",
            initial = "new.",
            confirm = "Create",
            onDismiss = dismiss,
            onConfirm = { name ->
                      Trace.tap("confirm new file", name)
                      viewModel.createFile(name)
                  },
        )

        is Pending.NewBranch -> PromptDialog(
            title = "New branch",
            label = "Name",
            initial = "",
            confirm = "Create",
            onDismiss = dismiss,
            onConfirm = { name -> viewModel.newBranch(name) },
        )

        is Pending.RemoveBranch -> ConfirmDialog(
            title = "Remove ${pending.branch}?",
            // **The refusal is named before the button is pressed**, because it is the whole reason
            // this is a confirmation and not a swipe: a branch with work main does not have is work
            // that exists nowhere else.
            text = "The branch goes from this phone. A branch with work that is not on main is " +
                "refused, because those commits would be left with nothing pointing at them.",
            confirm = "Remove",
            destructive = true,
            onDismiss = dismiss,
            onConfirm = { viewModel.dropBranch(pending.branch) },
        )

        is Pending.NewImage -> NewImageDialog(
            onDismiss = dismiss,
            onConfirm = { name, canvas ->
                Trace.tap("confirm new image", "$name ${canvas.width}x${canvas.height}")
                viewModel.createImage(name, canvas)
            },
        )

        is Pending.NewFolder -> PromptDialog(
            title = "New folder",
            label = "Name",
            initial = "New folder",
            confirm = "Create",
            onDismiss = dismiss,
            onConfirm = { name ->
                      Trace.tap("confirm new folder", name)
                      viewModel.createFolder(name)
                  },
        )

        is Pending.MoveEntry -> PlaceDialog(
            title = "Move ${pending.entry.name}",
            root = viewModel.workspace?.accountDir ?: viewModel.currentDir ?: return,
            forbidden = listOf(pending.entry.file),
            canChoose = { viewModel.canHoldFiles(it) },
            onDismiss = dismiss,
            onPick = { target ->
                Trace.tap("confirm move", "${pending.entry.name} into ${target.name}/")
                dismiss()
                viewModel.move(pending.entry.file, target)
            },
        )

        Pending.MoveSelected -> PlaceDialog(
            title = if (viewModel.selected.size == 1) "Move 1 chosen item"
            else "Move ${viewModel.selected.size} chosen items",
            root = viewModel.workspace?.accountDir ?: viewModel.currentDir ?: return,
            // The things being moved cannot hold themselves, and a folder cannot be
            // moved inside itself — the window refuses to offer either.
            forbidden = viewModel.selected.mapNotNull { viewModel.fileAt(it.split("/")) },
            canChoose = { viewModel.canHoldFiles(it) },
            onDismiss = dismiss,
            onPick = { target ->
                Trace.tap("confirm move", "${viewModel.selected.size} chosen into ${target.name}/")
                dismiss()
                viewModel.moveSelected(target)
            },
        )
    is Pending.CopyEntry -> PlaceDialog(
        title = "Copy ${pending.entry.name}",
        verb = "Copy",
        root = viewModel.workspace?.accountDir ?: viewModel.currentDir ?: return,
        forbidden = listOf(pending.entry.file),
        canChoose = { viewModel.canHoldFiles(it) },
        onDismiss = dismiss,
        onPick = { target ->
            // The act is recorded where the target is known, once — the same place a
            // move records it, so one gesture still has one id.
            Trace.tap("confirm copy", "${pending.entry.name} into ${target.name}/")
            dismiss()
            viewModel.copy(pending.entry.file, target)
        },
    )

    Pending.CopySelected -> PlaceDialog(
        title = if (viewModel.selected.size == 1) "Copy 1 chosen item"
        else "Copy ${viewModel.selected.size} chosen items",
        verb = "Copy",
        root = viewModel.workspace?.accountDir ?: viewModel.currentDir ?: return,
        forbidden = viewModel.selected.mapNotNull { viewModel.fileAt(it.split("/")) },
        canChoose = { viewModel.canHoldFiles(it) },
        onDismiss = dismiss,
        onPick = { target ->
            Trace.tap("confirm copy", "${viewModel.selected.size} chosen into ${target.name}/")
            dismiss()
            viewModel.copySelected(target)
        },
    )

    Pending.DeleteSelected -> ConfirmDialog(
        title = if (viewModel.selected.size == 1) "Delete 1 chosen item?"
        else "Delete ${viewModel.selected.size} chosen items?",
        text = "Everything chosen goes. It can be brought back from the history of " +
            "the folder it was in.",
        confirm = "Delete",
        destructive = true,
        onDismiss = dismiss,
        onConfirm = {
            Trace.tap("confirm delete", "${viewModel.selected.size} chosen")
            dismiss()
            viewModel.deleteSelected()
        },
    )

        is Pending.RestoreDeleted -> RestoreDeletedDialog(
            viewModel = viewModel,
            folder = pending.folder,
            onDismiss = dismiss,
            // The answer is not swallowed. `restoreDeleted` says what happened —
            // including why nothing did — and the message goes to the snackbar; a
            // tap that closes a window and changes nothing is the worst of both.
            onPick = { gone ->
                Trace.tap("confirm restore deleted", "${pending.folder.name}/${gone.name}")
                dismiss()
                viewModel.restoreDeleted(pending.folder, gone)
            },
        )

        is Pending.DeleteEntry -> ConfirmDialog(
            title = "Delete ${pending.entry.name}?",
            text = deleteWarning(pending.entry),
            confirm = "Delete",
            destructive = true,
            onDismiss = dismiss,
            onConfirm = {
                Trace.tap("confirm delete", pending.entry.name)
                viewModel.delete(pending.entry.file)
            },
        )

    }
}

/**
 * The files that used to be in this folder and are not there any more.
 *
 * Reading the history is not instant on a long-lived account, so the list is
 * fetched once when the window opens rather than on every frame.
 */
@Composable
private fun RestoreDeletedDialog(
    viewModel: AppViewModel,
    folder: File,
    onDismiss: () -> Unit,
    onPick: (Gone) -> Unit,
) {
    var loading by remember { mutableStateOf(true) }
    var found by remember { mutableStateOf<List<Gone>>(emptyList()) }
    LaunchedEffect(folder) {
        found = viewModel.deletedIn(folder)
        loading = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore a deleted file") },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp)) {
                when {
                    loading -> Text("Looking through the history…")
                    found.isEmpty() -> Text("Nothing was deleted from this folder.")
                    else -> LazyColumn {
                        items(found) { gone ->
                            Text(
                                // A folder is one row: a trailing slash is what says
                                // that picking it brings back everything inside.
                                text = if (gone.isFolder) gone.name + "/" else gone.name,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(gone) }
                                    .padding(vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Creates a project: a name, and nothing else. */
@Composable
private fun NewProjectUI(viewModel: AppViewModel, dismiss: () -> Unit) {
    NewProjectDialog(
        onDismiss = dismiss,
        onCreate = { name ->
            tap("new project", name)
            // **A project on this phone**, which is what a project is: a folder with its own history.
            // Making one used to require a GitHub account, because making one meant making a
            // repository — and that is the coupling this plan removes. An address can be added
            // whenever the person wants it to live somewhere else as well.
            viewModel.createLocalProject(name)
        },
    )
}

/**
 * Records what a person did, before doing it.
 *
 * Named for the element rather than for the code path — "Save", "sync testrepo" —
 * because the report is read by someone holding the phone, working out which thing on
 * which screen did not answer. A trace that said `saveEditor` would be a stack trace
 * with extra steps.
 */
/**
 * What the delete confirmation says, given what is being deleted.
 *
 * **A picture is the one deletion that cannot be undone.** Text keeps every version, so "it can be
 * brought back from the history" is true of it. A picture keeps exactly one — the newest — and the
 * store is swept to what the newest commits reference, so the bytes this deletes are the only bytes
 * there were. Saying "it can be brought back" there would be a promise the app cannot keep, in the
 * one place where being wrong costs the person their work.
 *
 * **A function rather than a `when` inside the dialog**, because a sentence this important is worth
 * a test, and a test cannot read a composable's parameters without a screen.
 */
fun deleteWarning(entry: Entry): String = when {
    entry.isFolder ->
        "The folder and everything inside it goes. It can be brought back from the history — " +
            "except pictures, which keep only one version."
    entry.kind == EntryKind.IMAGE ->
        "The picture goes, and this cannot be undone. A picture is not kept in the history — " +
            "only one version exists, and this is it."
    else -> "The file goes. It can be brought back from the history."
}

private fun tap(what: String, detail: String = ""): Int = Trace.tap(what, detail)

/**
 * Hands the trace to another app.
 *
 * Plain text through the system's share sheet, deliberately: it needs no permission, no
 * file provider and no storage, and it lands wherever the person wants — a chat, a note,
 * their own email. A trace that can only be read on the phone it came from is a trace
 * nobody can hand to anybody.
 */
private fun shareTrace(context: android.content.Context) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_SUBJECT, "Muffy Codes — what the app did")
        putExtra(android.content.Intent.EXTRA_TEXT, Trace.dump())
        Trace.where?.let { putExtra(android.content.Intent.EXTRA_TEXT, Trace.dump() + "\n\nOn this phone: $it\n") }
    }
    try {
        context.startActivity(
            android.content.Intent.createChooser(send, "Share the trace")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (t: Throwable) {
        // No app on the phone can take text. Saying nothing is right: the trace is
        // still on the screen, which is where the person was looking.
    }
}


/**
 * Opens [url] in whatever the phone uses for web pages.
 *
 * **Deliberately not this app's WebView.** A sign-in is the one page where the person must be able to
 * see the address and the certificate, and an embedded view cannot show either — which is why RFC
 * 8252 says authorisation happens in an external user agent, and why Google and GitHub both refuse
 * OAuth in embedded views. Opening it externally also means the person's existing browser session is
 * used, so a phone already signed in to OpenRouter is one press away from done.
 *
 * A phone with no browser is a sentence rather than a crash: the paste box in the same dialog still
 * works, and a key made by hand is the same key.
 */
private fun openInBrowser(context: android.content.Context, url: String) {
    try {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (t: Throwable) {
        // Nothing on this phone can open a web page. The dialog says nothing, because the paste box
        // is right there and a person who cannot open a browser already knows it.
    }
}

/** The activity behind a compose context, however it is wrapped. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
