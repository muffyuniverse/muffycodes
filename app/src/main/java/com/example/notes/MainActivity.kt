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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File

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
    data object EditIdentity : Pending
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
    var showAccount by remember { mutableStateOf(false) }
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
    val message = viewModel.message
    LaunchedEffect(message) {
        if (message != null) {
            viewModel.message = null
            snackbar.showSnackbar(message)
        }
    }

    val editing = viewModel.editingFile
    val historyPath = viewModel.historyPath

    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = !showSettings && showAccount) { showAccount = false }
    BackHandler(enabled = !showSettings && !showAccount && historyPath != null) {
        viewModel.closeHistory()
    }
    BackHandler(enabled = !showSettings && !showAccount && historyPath == null && editing != null) {
        viewModel.closeEditor()
    }
    BackHandler(
        enabled = !showSettings && !showAccount && historyPath == null && editing == null &&
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
            onEditIdentity = { pending = Pending.EditIdentity },
        )

        editing != null -> EditorScreen(
            viewModel = viewModel,
            file = editing,
            onBack = { viewModel.closeEditor() },
            onDelete = {
                val entry = Entry(editing.name, editing, viewModel.workspace?.kindOf(editing)
                    ?: EntryKind.TEXT)
                pending = Pending.DeleteEntry(entry)
            },
            onOpenHistory = { viewModel.openHistory(editing) },
        )

        historyPath != null -> HistoryScreen(
            viewModel = viewModel,
            title = historyPath.last(),
            onBack = { viewModel.closeHistory() },
        )

        else -> androidx.compose.foundation.layout.Box {
            BrowserScreen(
                viewModel = viewModel,
                username = viewModel.account?.login,
                onOpenSettings = { showSettings = true },
                onOpenAccount = { showAccount = true },
                actions = actions,
                onAddProject = { pending = Pending.AddProject },
                onRowSync = { project -> viewModel.sync(project) },
                onRowRename = { project -> pending = Pending.RenameProject(project) },
                onRowDelete = { project -> pending = Pending.DeleteProject(project) },
                onCreateFile = { viewModel.currentDir?.let { pending = Pending.NewFile(it) } },
                onCreateFolder = { viewModel.currentDir?.let { pending = Pending.NewFolder(it) } },
            )
            SnackbarHost(snackbar, modifier = Modifier)
        }
    }

    if (showAccount) {
        AccountSheet(
            account = viewModel.account,
            stored = viewModel.accountList,
            onDismiss = { showAccount = false },
            onAdd = {
                showAccount = false
                pending = Pending.SignIn
            },
            onSwitch = { login ->
                viewModel.switchTo(login)
                showAccount = false
            },
            onRemove = {
                showAccount = false
                pending = Pending.RemoveAccount
            },
            onEditIdentity = {
                showAccount = false
                pending = Pending.EditIdentity
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

        Pending.SignIn -> SignInDialog(
            onDismiss = dismiss,
            // The login is whatever GitHub says it is, not what was typed, and
            // the question goes to GitHub off the main thread.
            onSignIn = { _, token, name, email ->
                viewModel.signInWithToken(token, name, email) { }
            },
        )

        Pending.EditIdentity -> {
            val account = viewModel.account ?: return
            var name by remember { mutableStateOf(account.commitName) }
            var email by remember { mutableStateOf(account.commitEmail) }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = dismiss,
                title = { Text("Commits are written as") },
                text = {
                    androidx.compose.foundation.layout.Column {
                        androidx.compose.material3.OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Name") },
                            singleLine = true,
                        )
                        androidx.compose.material3.OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text("Email") },
                            singleLine = true,
                        )
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        viewModel.updateIdentity(name, email)
                        dismiss()
                    }) { Text("Save") }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = dismiss) { Text("Cancel") }
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
            var repos by remember { mutableStateOf<List<GitHubClient.Repo>>(emptyList()) }
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

/** The folders a thing can be moved into: those on its own level. */
private fun moveTargets(viewModel: AppViewModel, entry: Entry): List<Pair<String, File>> {
    val parent = entry.file.parentFile ?: return emptyList()
    return (parent.listFiles() ?: emptyArray())
        .filter { it.isDirectory && !it.name.startsWith(".") && it.name != entry.name }
        .map { it.name to it }
        .sortedBy { it.first.lowercase() }
}

/** Creates a project: the licence list first, then the name. */
@Composable
private fun NewProjectUI(viewModel: AppViewModel, dismiss: () -> Unit) {
    var licences by remember { mutableStateOf<List<GitHubClient.Licence>>(emptyList()) }
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
