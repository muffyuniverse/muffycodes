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
    data object AddProject : Pending
    data object NewProject : Pending
    data object RemoveAccount : Pending
    data class RenameProject(val project: String) : Pending
    data class DeleteProject(val project: String) : Pending
    data class RenameEntry(val entry: Entry) : Pending
    data class NewFile(val folder: File) : Pending
    data class NewFolder(val folder: File) : Pending
    data class MoveEntry(val entry: Entry) : Pending
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
    // The report, frozen the instant the button is pressed — see Trace.freeze. Held
    // here rather than in a dialog so that the record is taken before the typing
    // starts, and every screen can open it.
    var report by remember { mutableStateOf<Trace.Frozen?>(null) }
    var pending by remember { mutableStateOf<Pending>(Pending.None) }

    val snackbar = remember { SnackbarHostState() }

    // The trace screen shows a snapshot, re-taken while it is open: a report that
    // stopped updating would freeze the very thing being watched for not reacting.
    var traceReport by remember { mutableStateOf(Trace.report()) }
    LaunchedEffect(showTrace) {
        while (showTrace) {
            traceReport = Trace.report()
            kotlinx.coroutines.delay(1_000)
        }
}

    // The settings screen holds the field where a key is typed. While it is up,
    // the window refuses to be screenshotted or appear in the app switcher.
    val context = LocalContext.current
    LaunchedEffect(showSettings, pending is Pending.SignIn) {
        val window = context.findActivity()?.window ?: return@LaunchedEffect
        if (showSettings || pending is Pending.SignIn) {
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

    // The picker is the system's, so the app never asks for storage permission: it
    // sees only what a person just pointed at, and only reads it.
    val pickFiles = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) viewModel.importFiles(uris) }
    val pickFolder = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> if (uri != null) viewModel.importFolder(uri) }

    BackHandler(enabled = showTrace) { showTrace = false }
    BackHandler(enabled = showSettings) { showSettings = false }

    /**
     * The bug button, wired once.
     *
     * What the trace cannot know is added here — which screen, which folder, which
     * file, whose account — because that is what a reader needs to reproduce the
     * thing, and it is exactly what the app's own record leaves out.
     */
    val openReport: () -> Unit = {
        Trace.note("report opened", "")
        report = Trace.freeze(
            listOf(
                "screen" to when {
                    showTrace -> "trace"
                    showSettings -> "settings"
                    historyPath != null -> "history of ${historyPath.last()}"
                    editing != null -> "editor of ${editing.name}"
                    else -> "browser at " + (viewModel.path.ifEmpty { listOf("/") }.joinToString("/"))
                },
                "account" to (viewModel.activeLogin ?: "nobody"),
                "folder" to (viewModel.currentDir?.absolutePath ?: "—"),
                "projects" to viewModel.projectNames.joinToString(", ").ifEmpty { "none" },
                // A report without the build is a report about a version that may no
                // longer exist — and "it used to work" is the commonest thing a
                // person says, which makes *which* build the first question.
                "version" to viewModel.versionLine,
            ),
        )
    }

    // **The trace answers taps that never reach the view model.** Cancel, opening a
    // menu, choosing a row: those change what is on the screen without changing the
    // folder, so `refresh()` cannot see them. Watching the two pieces of screen state
    // that this composable owns is what keeps "every element leaves a trace" from
    // being a claim with a hole in it where the local controls are.
    LaunchedEffect(pending, showSettings, showTrace) {
        Trace.changed(
            "screen " + (if (showTrace) "trace" else if (showSettings) "settings" else "browser") +
                " dialog " + pending.javaClass.simpleName,
        )
    }
    BackHandler(enabled = !showSettings && historyPath != null) {
        viewModel.closeHistory()
    }
    BackHandler(enabled = !showSettings && historyPath == null && editing != null) {
        viewModel.closeEditor()
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
        onOpen = { entry ->
            tap(if (entry.isFolder) "open folder" else "open file", entry.name)
            when (entry.kind) {
                EntryKind.FOLDER -> viewModel.openChild(entry.name)
                else -> viewModel.openEditor(entry.file)
            }
        },
        onRename = { entry -> tap("rename", entry.name); pending = Pending.RenameEntry(entry) },
        onMove = { entry -> tap("move", entry.name); pending = Pending.MoveEntry(entry) },
        onDelete = { entry -> tap("delete", entry.name); pending = Pending.DeleteEntry(entry) },
        onTogglePin = { entry -> tap("pin", entry.name); viewModel.togglePinned(entry.file) },
        onToggleLock = { entry -> tap("lock", entry.name); viewModel.toggleLocked(entry.file) },
        onHistory = { entry -> tap("history", entry.name); viewModel.openHistory(entry.file) },
        onSelect = { entry -> tap("select", entry.name); viewModel.toggleSelected(entry.file) },
    )

    when {
        showTrace -> ObservabilityScreen(
            report = traceReport,
            onBack = { showTrace = false },
            onShare = { shareTrace(context) },
            onSave = {
                val saved = Reports.save(context, Trace.dump(), what = "trace")
                Trace.note("trace saved", saved.where ?: "could not be written")
                viewModel.message = if (saved.ok) {
                    "Saved to ${saved.where}"
                } else {
                    "Could not save the trace"
                }
            },
            onReport = openReport,
            onClear = {
                Trace.clear()
                traceReport = Trace.report()
            },
        )

        showSettings -> SettingsScreen(
            viewModel = viewModel,
            onBack = { showSettings = false },
            onSignIn = { tap("add account"); pending = Pending.SignIn },
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
            onBack = { tap("history back", historyPath.last()); viewModel.closeHistory() },
            // Select puts the version back and returns to the file: one act, and
            // the person is looking at the result of it.
            onSelect = { viewModel.closeHistory() },
            onReport = openReport,
        )

        editing != null -> EditorScreen(
            viewModel = viewModel,
            file = editing,
            onBack = { tap("close editor", editing.name); viewModel.closeEditor() },
            // No delete here: deleting is a thing done to a row in the folder, where
            // the row and its neighbours are visible and a mistake is one undo away.
            // A three-dot menu inside the editor put the most destructive command in
            // the app beside Save.
            onOpenHistory = {
                        tap("history from editor", editing.name)
                        viewModel.editingFile?.let { viewModel.openHistory(it) }
                    },
                    onReport = openReport,
        )

        else -> androidx.compose.foundation.layout.Box {
            BrowserScreen(
                viewModel = viewModel,
                username = viewModel.account?.login,
                onOpenSettings = { tap("settings"); showSettings = true },
                onReport = openReport,
                actions = actions,
                onAddProject = { tap("add project"); pending = Pending.AddProject },
                onRowSync = { project -> tap("sync", project); pending = Pending.SyncProject(project) },
                onRowRename = { project -> tap("rename project", project); pending = Pending.RenameProject(project) },
                onRowDelete = { project -> tap("delete project", project); pending = Pending.DeleteProject(project) },
                onCreateFile = { tap("new file"); viewModel.currentDir?.let { pending = Pending.NewFile(it) } },
                onCreateFolder = { tap("new folder"); viewModel.currentDir?.let { pending = Pending.NewFolder(it) } },
                onUploadFiles = { tap("upload files"); pickFiles.launch(arrayOf("*/*")) },
                onUploadFolder = { tap("upload folder"); pickFolder.launch(null) },
                onRestoreDeleted = {
                        tap("restore deleted")
                    viewModel.currentDir?.let { pending = Pending.RestoreDeleted(it) }
                },
                onMoveSelected = { tap("move selected"); pending = Pending.MoveSelected },
                onDeleteSelected = { tap("delete selected"); pending = Pending.DeleteSelected },
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
    report?.let { frozen ->
        ReportDialog(
            frozen = frozen,
            onDismiss = { report = null },
            onSend = { note ->
                report = null
                val text = Trace.reportText(frozen, note)
                val saved = Reports.save(
                    context,
                    text,
                    slug = Reports.slugOf(note),
                    at = frozen.at,
                )
                // **Written first, then offered.** The share sheet is a convenience and
                // the file is the record: a person who dismisses the sheet still has the
                // report, and nothing the next minute does can lose it.
                Trace.note("report saved", saved.where ?: "could not be written")
                shareReport(context, text, saved)
                viewModel.message = if (saved.ok) {
                    "Report saved to ${saved.where}"
                } else {
                    "The report could not be written, so it was offered as text"
                }
            },
        )
    }

    Dialogs(
        viewModel = viewModel,
        pending = pending,
        dismiss = { pending = Pending.None },
        newProject = { pending = Pending.NewProject },
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
    askReplace: (String) -> Unit,
) {
    when (pending) {
        Pending.None -> Unit

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
                      Trace.tap("remove account", account.login + if (deleteCopies) " with copies" else "")
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
                onPick = { repo ->
                    dismiss()
                    if (repo.name in viewModel.projectNames) {
                        // Already here: replacing the copy is a question (§5.2).
                        askReplace(repo.name)
                    } else {
                        viewModel.pullRepository(repo)
                    }
                },
            )
        }

        Pending.NewProject -> NewProjectUI(viewModel, dismiss)

        is Pending.RenameProject -> PromptDialog(
            title = "Rename ${pending.project}",
            label = "Name",
            initial = pending.project,
            confirm = "Rename",
            onDismiss = dismiss,
            onConfirm = { name ->
                      Trace.tap("rename project", pending.project)
                      viewModel.renameProject(pending.project, name)
                  },
        )

        is Pending.DeleteProject -> DeleteProjectDialog(
            project = pending.project,
            onDismiss = dismiss,
            onDelete = { everywhere ->
                      Trace.tap("delete project", pending.project + if (everywhere) " everywhere" else " here")
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

        is Pending.MoveEntry -> MoveDialog(
            title = "Move ${pending.entry.name}",
            root = viewModel.workspace?.accountDir ?: viewModel.currentDir ?: return,
            forbidden = listOf(pending.entry.file),
            canChoose = { viewModel.canHoldFiles(it) },
            onDismiss = dismiss,
            onMove = { target ->
                Trace.tap("move ${pending.entry.name}", "into ${target.name}/")
                dismiss()
                viewModel.move(pending.entry.file, target)
            },
        )

        Pending.MoveSelected -> MoveDialog(
            title = if (viewModel.selected.size == 1) "Move 1 chosen item"
            else "Move ${viewModel.selected.size} chosen items",
            root = viewModel.workspace?.accountDir ?: viewModel.currentDir ?: return,
            // The things being moved cannot hold themselves, and a folder cannot be
            // moved inside itself — the window refuses to offer either.
            forbidden = viewModel.selected.mapNotNull { viewModel.fileAt(it.split("/")) },
            canChoose = { viewModel.canHoldFiles(it) },
            onDismiss = dismiss,
            onMove = { target ->
                Trace.tap("move chosen", "into ${target.name}/")
                dismiss()
                viewModel.moveSelected(target)
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
                Trace.tap("confirm delete chosen", "${viewModel.selected.size} items")
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
                Trace.tap("restore deleted", "${pending.folder.name}/$gone.name")
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
            onConfirm = { viewModel.replaceWithRemote(pending.project) },
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
        onCreate = { name, licence -> viewModel.createProject(name, licence) },
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
private fun tap(what: String, detail: String = "") {
    Trace.tap(what, detail)
}

/**
 * Offers a saved report, saying where the copy is.
 *
 * The path goes in the text as well as the message, because the person reading it on
 * the other end needs to say which file they mean — and the file is the one thing the
 * share sheet cannot carry.
 */
private fun shareReport(
    context: android.content.Context,
    text: String,
    saved: Reports.Saved,
) {
    val where = saved.where
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_SUBJECT, "Muffy Codes — problem report")
        putExtra(
            android.content.Intent.EXTRA_TEXT,
            if (where == null) text else text + "\n\nThe same report is on the phone at: $where\n",
        )
    }
    try {
        context.startActivity(
            android.content.Intent.createChooser(send, "Send the report")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (t: Throwable) {
        // Nothing can take text. The message on the screen already says where the file
        // is, which is the part that matters.
    }
}

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
