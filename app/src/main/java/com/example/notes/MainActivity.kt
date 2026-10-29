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

        setContent {
            val viewModel: AppViewModel = viewModel()
            val settings = viewModel.settings

            MaterialTheme(
                colorScheme = if (settings.useDark) settings.theme.darkScheme()
                else settings.theme.lightScheme(),
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(viewModel)
                }
            }
        }
    }
}

/** The dialog that can be open over the browser. */
private sealed interface Pending {
    data object None : Pending
    data object SignIn : Pending
    data object ModelKey : Pending
    data object AddProject : Pending
    data object NewProject : Pending
    data object NewLocalProject : Pending
    data object RemoveAccount : Pending
    data class RenameProject(val project: String) : Pending
    data class DeleteProject(val project: String) : Pending
    /** Local -> synced: a repository, seeded with the licence the person picks. */
    data class PublishProject(val project: String) : Pending
    /** Synced -> local: the remote goes, every version stays. */
    data class KeepLocal(val project: String) : Pending
    data class RenameEntry(val entry: Entry) : Pending
    data class NewFile(val folder: File) : Pending
    data class NewFolder(val folder: File) : Pending
    data class MoveEntry(val entry: Entry) : Pending
    data class CopyEntry(val entry: Entry) : Pending
    data object CopySelected : Pending
    data object MoveSelected : Pending
    data object DeleteSelected : Pending
    data class SyncProject(val project: String) : Pending
    data class RestoreDeleted(val folder: File) : Pending
    data class DeleteEntry(val entry: Entry) : Pending
    data class ReplaceProject(val project: String) : Pending
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
    var showTrace by remember { mutableStateOf(false) }
    // **The cost table, opened from the projects screen's bar.** It reads the request logs when it
    // is opened and not while it is open: the log grows on every response, so a table that watched
    // it would move under the reader's eyes and would be re-reading files the agent was writing.
    var showStats by remember { mutableStateOf(false) }
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
                snackbar.showSnackbar(text)
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

    BackHandler(enabled = showTrace) { showTrace = false }
    BackHandler(enabled = showStats) { showStats = false }
    BackHandler(enabled = showSettings) { showSettings = false }

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
                showStats -> "stats"
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
                showStats -> "stats"
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
        onRename = { entry -> tap("rename", entry.name); pending = Pending.RenameEntry(entry) },
        onDelete = { entry -> tap("delete", entry.name); pending = Pending.DeleteEntry(entry) },
        onTogglePin = { entry -> viewModel.togglePinned(entry.file) },
        onToggleLock = { entry -> viewModel.toggleLocked(entry.file) },
        onHistory = { entry -> viewModel.openHistory(entry.file) },
        onSelect = { entry -> viewModel.toggleSelected(entry.file) },
        // The act records its own tap: it is the view model that knows the path git will match.
        onIgnore = { entry -> viewModel.toggleUnsynced(entry.file) },
    )

    when {
        // **Only offered where there is an account to read**, which is the same condition the
        // button is drawn under. `workspace` is non-null exactly when somebody is signed in.
        showStats && viewModel.workspace != null -> StatsScreen(
            accountDir = viewModel.workspace!!.accountDir,
            // **Closing on the way out is the view model's job**, because it is what opened the
            // handle - see readRequestStats. A screen closed by the wrong owner throws from an
            // unrelated query later.
            onBack = {
                showStats = false
                viewModel.closeRequestStats()
            },
            // The log is read on the app's worker and posted back, never in composition: see
            // AppViewModel.readRequestStats.
            readRows = { onDone -> viewModel.readRequestStats(onDone) },
            onReport = openReport,
        )
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
            onSignIn = { tap("add account"); pending = Pending.SignIn },
            onSetKey = { tap("set model key"); pending = Pending.ModelKey },
            onRemoveAccount = { tap("remove account"); pending = Pending.RemoveAccount },
            onTestAccount = { tap("use test account"); viewModel.signInAsTestAccount() },
            onOpenTrace = { showTrace = true },
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
        )

        else -> androidx.compose.foundation.layout.Box {
            BrowserScreen(
                viewModel = viewModel,
                username = viewModel.account?.login,
                onOpenSettings = { tap("settings"); showSettings = true },
                onReport = openReport,
                // The act records itself: opening a table is a tap that reaches no folder, so
                // `refresh()` cannot see it and the trace would have a hole exactly where a
                // person would look for "did the button work".
                onOpenStats = { tap("stats"); showStats = true },
                actions = actions,
                onAddProject = { tap("add project"); pending = Pending.AddProject },
                onRowChat = { project -> viewModel.openChat(project) },
                onRowSync = { project -> tap("sync", project); pending = Pending.SyncProject(project) },
                onRowRename = { project -> tap("rename project", project); pending = Pending.RenameProject(project) },
                onRowDelete = { project -> tap("delete project", project); pending = Pending.DeleteProject(project) },
                onRowPublish = { project -> tap("publish", project); pending = Pending.PublishProject(project) },
                onRowKeepLocal = { project -> tap("keep local", project); pending = Pending.KeepLocal(project) },
                onCreateFile = { tap("new file"); viewModel.currentDir?.let { pending = Pending.NewFile(it) } },
                onCreateFolder = { tap("new folder"); viewModel.currentDir?.let { pending = Pending.NewFolder(it) } },
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

    Dialogs(
        viewModel = viewModel,
        pending = pending,
        dismiss = { pending = Pending.None },
        newProject = { pending = Pending.NewProject },
        newLocalProject = { pending = Pending.NewLocalProject },
        askReplace = { project -> pending = Pending.ReplaceProject(project) },
    )
}

/** Every dialog, in one place, so the screens stay free of state. */
@Composable
private fun Dialogs(
    viewModel: AppViewModel,
    pending: Pending,
    dismiss: () -> Unit,
    newProject: () -> Unit,
    newLocalProject: () -> Unit,
    askReplace: (String) -> Unit,
) {
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
            )
        }

        Pending.SignIn -> {
            // The dialog owns the wait, so the answer lands where the person is
            // looking. A refusal keeps the dialog open with the reason in it; a
            // success is the only thing that dismisses.
            var signingIn by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf<String?>(null) }
            SignInDialog(
                onDismiss = dismiss,
                signingIn = signingIn,
                error = error,
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

        Pending.RemoveAccount -> viewModel.account?.let { account ->
            RemoveAccountDialog(
                login = account.login,
                onDismiss = dismiss,
                onRemove = { deleteCopies ->
                      Trace.tap("confirm remove account", account.login + if (deleteCopies) " with copies" else "")
                      viewModel.removeAccount(account.login, deleteCopies)
                  },
            )
        }

        Pending.AddProject -> {
            var repos by remember { mutableStateOf<List<Repo>>(emptyList()) }
            var loading by remember { mutableStateOf(true) }
            var error by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                viewModel.listRepositories { list, problem ->
                    repos = list
                    error = problem
                    loading = false
                }
            }
            AddProjectDialog(
                repositories = repos,
                alreadyHere = viewModel.projectNames.toSet(),
                loading = loading,
                error = error,
                onDismiss = dismiss,
                onNewProject = {
                    dismiss()
                    newProject()
                },
                onNewLocalProject = {
                    dismiss()
                    newLocalProject()
                },
                onPick = { repo ->
                    dismiss()
                    if (repo.name in viewModel.projectNames) {
                        // Already here: replacing the copy is a question (§5.2). The choice that
                        // started it is "replace"; the question's button is its confirm.
                        tap("replace", repo.name)
                        askReplace(repo.name)
                    } else {
                        tap("confirm add project", repo.name)
                        viewModel.pullRepository(repo)
                    }
                },
            )
        }

        Pending.NewProject -> NewProjectUI(viewModel, dismiss)

        Pending.NewLocalProject -> NewProjectDialog(
            // No licence list is asked for: a local project has no repository to seed.
            licences = emptyList(),
            local = true,
            onDismiss = dismiss,
            onCreate = { name, _ ->
                tap("local project", name)
                viewModel.createLocalProject(name)
            },
        )

        is Pending.RenameProject -> PromptDialog(
            title = "Rename ${pending.project}",
            label = "Name",
            initial = pending.project,
            confirm = "Rename",
            onDismiss = dismiss,
            onConfirm = { name ->
                      Trace.tap("confirm rename project", pending.project)
                      viewModel.renameProject(pending.project, name)
                  },
        )

        is Pending.PublishProject -> PublishProjectUI(
            viewModel = viewModel,
            project = pending.project,
            dismiss = dismiss,
        )

        is Pending.KeepLocal -> KeepLocalDialog(
            project = pending.project,
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

        is Pending.SyncProject -> SyncDialog(
            project = pending.project,
            changes = viewModel.changesOf(pending.project),
            onDismiss = dismiss,
            onSync = { note ->
                Trace.tap("confirm sync", pending.project)
                dismiss()
                viewModel.sync(pending.project, note)
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
            text = if (pending.entry.isFolder) {
                "The folder and everything inside it goes. It can be brought back " +
                    "from the history."
            } else {
                "The file goes. It can be brought back from the history."
            },
            confirm = "Delete",
            destructive = true,
            onDismiss = dismiss,
            onConfirm = {
                Trace.tap("confirm delete", pending.entry.name)
                viewModel.delete(pending.entry.file)
            },
        )

        is Pending.ReplaceProject -> ConfirmDialog(
            title = "Replace ${pending.project}?",
            text = "The files on this phone are replaced with what GitHub holds. " +
                "Your local files beside the repository are kept.",
            confirm = "Replace",
            onDismiss = dismiss,
            onConfirm = {
                tap("confirm replace", pending.project)
                viewModel.replaceWithRemote(pending.project)
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

/**
 * Publishing a local project: **the licence list first**, because a published project has to say
 * what it may be used for.
 *
 * The same read as `NewProjectUI`, and for the same reason: the licences are GitHub's, so they are
 * fetched rather than written into the app. A failure to read them is not fatal - the dialog starts
 * on GPL-3.0 and the list is simply empty - because a project must still be publishable on a phone
 * with no network.
 */
@Composable
private fun PublishProjectUI(viewModel: AppViewModel, project: String, dismiss: () -> Unit) {
    var licences by remember { mutableStateOf<List<Licence>>(emptyList()) }
    LaunchedEffect(Unit) {
        viewModel.listLicences { licences = it }
    }
    PublishProjectDialog(
        project = project,
        licences = licences,
        onDismiss = dismiss,
        onPublish = { licence ->
            Trace.tap("confirm publish", "$project · $licence")
            viewModel.addProjectToGitHub(project, licence)
        },
    )
}

/** Creates a project: the licence list first, then the name. */
@Composable
private fun NewProjectUI(viewModel: AppViewModel, dismiss: () -> Unit) {
    var licences by remember { mutableStateOf<List<Licence>>(emptyList()) }
    LaunchedEffect(Unit) {
        viewModel.listLicences { licences = it }
    }
    NewProjectDialog(
        licences = licences,
        onDismiss = dismiss,
        onCreate = { name, licence ->
            tap("new project", name)
            viewModel.createProject(name, licence)
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


/** The activity behind a compose context, however it is wrapped. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
