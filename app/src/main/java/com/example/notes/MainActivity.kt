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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    var pending by remember { mutableStateOf<Pending>(Pending.None) }

    val snackbar = remember { SnackbarHostState() }

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

    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = !showSettings && historyPath != null) {
        viewModel.closeHistory()
    }
    BackHandler(enabled = !showSettings && historyPath == null && editing != null) {
        viewModel.closeEditor()
    }
    BackHandler(
        enabled = !showSettings && historyPath == null && editing == null &&
            viewModel.canGoUp
    ) {
        viewModel.up()
    }

    val actions = BrowserActions(
        onOpen = { entry ->
            when (entry.kind) {
                EntryKind.FOLDER -> viewModel.openChild(entry.name)
                else -> viewModel.openEditor(entry.file)
            }
        },
        onRename = { entry -> pending = Pending.RenameEntry(entry) },
        onMove = { entry -> pending = Pending.MoveEntry(entry) },
        onDelete = { entry -> pending = Pending.DeleteEntry(entry) },
        onTogglePin = { entry -> viewModel.togglePinned(entry.file) },
        onHistory = { entry -> viewModel.openHistory(entry.file) },
        onMoveIntoRepository = { entry ->
            if (!viewModel.moveIntoRepository(entry.file)) Unit
        },
        onKeepOnlyOnPhone = { entry ->
            if (!viewModel.keepOnlyOnPhone(entry.file)) Unit
        },
    )

    when {
        showSettings -> SettingsScreen(
            viewModel = viewModel,
            onBack = { showSettings = false },
            onSignIn = { pending = Pending.SignIn },
            onRemoveAccount = { pending = Pending.RemoveAccount },
            onTestAccount = { viewModel.signInAsTestAccount() },
        )

        // History is checked *before* the editor, and the order is the whole of a
        // bug: the editor stays open underneath while its file's history is being
        // looked at, so testing `editing != null` first kept drawing the editor and
        // the history only appeared after Done had closed it.
        historyPath != null -> HistoryScreen(
            viewModel = viewModel,
            title = historyPath.last(),
            onBack = { viewModel.closeHistory() },
        )

        editing != null -> EditorScreen(
            viewModel = viewModel,
            file = editing,
            onBack = { viewModel.closeEditor() },
            onRename = {
                pending = Pending.RenameEntry(
                    Entry(editing.name, editing, viewModel.workspace?.kindOf(editing)
                        ?: EntryKind.TEXT)
                )
            },
            onDelete = {
                val entry = Entry(editing.name, editing, viewModel.workspace?.kindOf(editing)
                    ?: EntryKind.TEXT)
                pending = Pending.DeleteEntry(entry)
            },
            onOpenHistory = { viewModel.openHistory(editing) },
        )

        else -> androidx.compose.foundation.layout.Box {
            BrowserScreen(
                viewModel = viewModel,
                username = viewModel.account?.login,
                onOpenSettings = { showSettings = true },
                actions = actions,
                onAddProject = { pending = Pending.AddProject },
                onRowSync = { project -> viewModel.sync(project) },
                onRowRename = { project -> pending = Pending.RenameProject(project) },
                onRowDelete = { project -> pending = Pending.DeleteProject(project) },
                onCreateFile = { viewModel.currentDir?.let { pending = Pending.NewFile(it) } },
                onCreateFolder = { viewModel.currentDir?.let { pending = Pending.NewFolder(it) } },
                onUploadFiles = { pickFiles.launch(arrayOf("*/*")) },
                onUploadFolder = { pickFolder.launch(null) },
            )
            // At the bottom, and inset from it. A snackbar at the top of the
            // window sat under the phone's status bar and was cropped by it — the
            // success and failure messages of every action were half a line of text
            // in the one place nothing can be drawn.
            SnackbarHost(
                snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            )
        }
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
                onRemove = { deleteCopies -> viewModel.removeAccount(account.login, deleteCopies) },
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
            onConfirm = { name -> viewModel.renameProject(pending.project, name) },
        )

        is Pending.DeleteProject -> DeleteProjectDialog(
            project = pending.project,
            onDismiss = dismiss,
            onDelete = { everywhere -> viewModel.deleteProject(pending.project, everywhere) },
        )

        is Pending.RenameEntry -> PromptDialog(
            title = "Rename ${pending.entry.name}",
            label = "Name",
            initial = pending.entry.name,
            confirm = "Rename",
            onDismiss = dismiss,
            onConfirm = { name -> if (!viewModel.rename(pending.entry.file, name)) Unit },
        )

        is Pending.NewFile -> PromptDialog(
            title = "New file",
            label = "Name",
            initial = "new.",
            confirm = "Create",
            onDismiss = dismiss,
            onConfirm = { name -> if (!viewModel.createFile(name)) Unit },
        )

        is Pending.NewFolder -> PromptDialog(
            title = "New folder",
            label = "Name",
            initial = "New folder",
            confirm = "Create",
            onDismiss = dismiss,
            onConfirm = { name -> if (!viewModel.createFolder(name)) Unit },
        )

        is Pending.MoveEntry -> MoveDialog(
            title = "Move ${pending.entry.name}",
            folders = moveTargets(viewModel, pending.entry),
            onDismiss = dismiss,
            onPick = { target -> if (!viewModel.move(pending.entry.file, target)) Unit },
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
            onConfirm = { if (!viewModel.delete(pending.entry.file)) Unit },
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
 * The folders a thing can be moved into: those on its own level (§3).
 *
 * The visibility rule is asked for, not restated. This used to write
 * `!it.name.startsWith(".")` itself, which matched the rule for hidden things but
 * not for the app's structural folders — so `projects/` and `repo/` appeared as
 * ordinary destinations even though §3 promises that nothing the app owns can
 * appear in a move picker or be targeted by a command.
 *
 * `Workspace` is the only place that decides; asking it is how "a guarantee, not
 * a convention" stays true when the rule changes.
 */
private fun moveTargets(viewModel: AppViewModel, entry: Entry): List<Pair<String, File>> {
    val parent = entry.file.parentFile ?: return emptyList()
    val ws = viewModel.workspace ?: return emptyList()
    return (parent.listFiles() ?: emptyArray())
        .filter { target ->
            target.isDirectory &&
                target.name != entry.name &&
                !ws.isHidden(target.name) &&
                !ws.isStructural(target)
        }
        .map { it.name to it }
        .sortedBy { it.first.lowercase() }
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

/** The activity behind a compose context, however it is wrapped. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
