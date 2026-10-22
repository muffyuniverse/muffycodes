package com.example.notes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val themePrefs = ThemePreferences(applicationContext)
        val credentials = CredentialStore(applicationContext)

        setContent {
            val theme = themePrefs.theme
            val useDark = themePrefs.useDark

            MaterialTheme(
                colorScheme = if (useDark) theme.darkScheme() else theme.lightScheme(),
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NotesApp(
                        theme = theme,
                        useDark = useDark,
                        onThemeChange = { themePrefs.selectTheme(it) },
                        onDarkChange = { themePrefs.selectDarkVariant(it) },
                        credentials = credentials,
                    )
                }
            }
        }
    }
}

/** The state of the dialogs that can be open over the list screen. */
private sealed interface PendingDialog {
    data object None : PendingDialog
    data object NewFolder : PendingDialog
    data class RenameFolder(val folder: Folder) : PendingDialog
    data class RenameFile(val note: Note) : PendingDialog
    data class DeleteFolder(val folder: Folder) : PendingDialog
    data class MoveFolder(val folder: Folder) : PendingDialog
    data class MoveNote(val note: Note) : PendingDialog
    data class Commit(val message: String) : PendingDialog
}

/** What the version browser is showing. */
private sealed interface HistoryTarget {
    data object Folder : HistoryTarget
    data class File(val note: Note) : HistoryTarget
}

/**
 * The app: a list screen, an editor, a settings screen and a version browser.
 */
@Composable
fun NotesApp(
    theme: AppTheme,
    useDark: Boolean,
    onThemeChange: (AppTheme) -> Unit,
    onDarkChange: (Boolean) -> Unit,
    credentials: CredentialStore,
    viewModel: NotesViewModel = viewModel(),
) {
    var editingId by remember { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var historyTarget by remember { mutableStateOf<HistoryTarget?>(null) }
    var pending by remember { mutableStateOf<PendingDialog>(PendingDialog.None) }
    var pendingNoteDelete by remember { mutableStateOf<Note?>(null) }

    val editingNote = editingId?.let { id -> viewModel.notes.firstOrNull { it.id == id } }
    // A delegated property cannot be smart-cast, so the value is copied first.
    val history = historyTarget

    // The system back button walks back through the screens and up the folder
    // tree before it leaves the app.
    BackHandler(enabled = showSettings || historyTarget != null) {
        showSettings = false
        historyTarget = null
    }
    BackHandler(enabled = !showSettings && historyTarget == null && editingNote != null) {
        editingId = null
    }
    BackHandler(
        enabled = !showSettings && historyTarget == null && editingNote == null &&
            viewModel.canNavigateUp
    ) {
        viewModel.navigateUp()
    }

    if (showSettings) {
        SettingsScreen(
            currentTheme = theme,
            useDark = useDark,
            onThemeChange = onThemeChange,
            onDarkChange = onDarkChange,
            signedInName = credentials.accountName,
            signedInEmail = credentials.accountEmail,
            folders = viewModel.folders,
            projectOf = { folderId -> viewModel.projectOf(folderId) },
            onSignIn = { token, email ->
                val result = GitHubClient.whoami(token)
                if (result.isSuccess) {
                    credentials.accountName = result.value
                    credentials.accountEmail = email
                    credentials.token = token
                    viewModel.gitMessage = "Connected as ${result.value}"
                } else {
                    viewModel.gitMessage = result.error
                }
            },
            onSignOut = {
                credentials.signOut()
            },
            onAttachProject = { folderId, project ->
                viewModel.attachProject(folderId, project)
            },
            onDetachProject = { folderId -> viewModel.detachProject(folderId) },
            onInitRepository = { folderId ->
                viewModel.initRepository(folderId)
            },
            onCloneInto = { folderId, remoteUrl ->
                val result = viewModel.cloneInto(folderId, remoteUrl)
                viewModel.gitMessage = result.message
            },
            onCreateRepo = { folderId, name, isPrivate, description ->
                val token = credentials.token
                if (token.isNullOrBlank()) {
                    viewModel.gitMessage = "Connect your GitHub account first"
                } else {
                    val result = GitHubClient.createRepo(token, name, isPrivate, description)
                    val repo = result.value
                    if (repo != null) {
                        viewModel.initRepository(folderId)
                        viewModel.git.setRemote(folderId, repo.cloneUrl)
                        viewModel.attachProject(
                            folderId,
                            GitProject(name = repo.fullName, remoteUrl = repo.cloneUrl),
                        )
                        viewModel.gitMessage = "Created ${repo.fullName}"
                    } else {
                        viewModel.gitMessage = result.error
                    }
                }
            },
            onBack = { showSettings = false },
        )
    } else if (history != null) {
        HistoryContent(
            target = history,
            viewModel = viewModel,
            onBack = { historyTarget = null },
        )
    } else if (editingNote != null) {
        NoteEditorScreen(
            note = editingNote,
            folders = viewModel.folders,
            folderPathOf = { viewModel.folderNameOf(it) },
            onSave = { title, body -> viewModel.updateNote(editingNote.id, title, body) },
            onDelete = { pendingNoteDelete = editingNote },
            onMove = { pending = PendingDialog.MoveNote(editingNote) },
            onHistory = { historyTarget = HistoryTarget.File(editingNote) },
            onBack = { editingId = null },
        )
    } else {
        NotesListScreen(
            folders = viewModel.childFolders,
            notes = viewModel.visibleNotes,
            breadcrumb = viewModel.breadcrumb,
            canNavigateUp = viewModel.canNavigateUp,
            isSearchingEverything = viewModel.isSearchingEverything,
            query = viewModel.query,
            onQueryChange = { viewModel.query = it },
            noteCountOf = { viewModel.noteCount(it) },
            totalCountOf = { viewModel.totalNoteCount(it) },
            folderPathOf = { viewModel.folderNameOf(it) },
            projectName = viewModel.currentProjectName,
            pendingChanges = viewModel.pendingChanges,
            onNavigateUp = { viewModel.navigateUp() },
            onOpenFolder = { viewModel.openFolder(it) },
            onOpenNote = { editingId = it.id },
            onCreateNote = { editingId = viewModel.createNote().id },
            onCreateFolder = { pending = PendingDialog.NewFolder },
            onOpenSettings = { showSettings = true },
            onOpenHistory = { historyTarget = HistoryTarget.Folder },
            onCommit = { pending = PendingDialog.Commit("") },
            onTogglePin = { viewModel.togglePinned(it) },
            onFolderAction = { folder, action ->
                pending = when (action) {
                    FolderAction.Rename -> PendingDialog.RenameFolder(folder)
                    FolderAction.Move -> PendingDialog.MoveFolder(folder)
                    FolderAction.Delete -> PendingDialog.DeleteFolder(folder)
                }
            },
            onNoteAction = { note, action ->
                when (action) {
                    NoteAction.Rename -> pending = PendingDialog.RenameFile(note)
                    NoteAction.Move -> pending = PendingDialog.MoveNote(note)
                    NoteAction.Delete -> pendingNoteDelete = note
                    NoteAction.History -> historyTarget = HistoryTarget.File(note)
                }
            },
        )
    }

    when (val dialog = pending) {
        PendingDialog.None -> Unit

        PendingDialog.NewFolder -> NameFolderDialog(
            title = "New folder",
            onDismiss = { pending = PendingDialog.None },
            onConfirm = { name ->
                viewModel.createFolder(name)
                pending = PendingDialog.None
            },
        )

        is PendingDialog.RenameFolder -> NameFolderDialog(
            title = "Rename folder",
            initialName = dialog.folder.name,
            onDismiss = { pending = PendingDialog.None },
            onConfirm = { name ->
                viewModel.renameFolder(dialog.folder.id, name)
                pending = PendingDialog.None
            },
        )

        is PendingDialog.RenameFile -> RenameFileDialog(
            initialTitle = dialog.note.title,
            onDismiss = { pending = PendingDialog.None },
            onConfirm = { title ->
                viewModel.renameNote(dialog.note.id, title)
                pending = PendingDialog.None
            },
        )

        is PendingDialog.DeleteFolder -> {
            val count = viewModel.totalNoteCount(dialog.folder.id)
            ConfirmDialog(
                title = "Delete '${dialog.folder.displayName()}'?",
                message = if (count > 0) {
                    "This also deletes $count file(s) inside this folder and any sub-folders. " +
                        "This cannot be undone."
                } else {
                    "This deletes the folder and any sub-folders. This cannot be undone."
                },
                onDismiss = { pending = PendingDialog.None },
                onConfirm = {
                    viewModel.deleteFolder(dialog.folder.id)
                    pending = PendingDialog.None
                },
            )
        }

        is PendingDialog.MoveFolder -> MoveToFolderDialog(
            folders = viewModel.folders,
            // A folder cannot be moved into itself or into one of its own descendants.
            disabledIds = FolderTree.descendants(viewModel.folders, dialog.folder.id).toSet(),
            onDismiss = { pending = PendingDialog.None },
            onPick = { targetId ->
                viewModel.moveFolder(dialog.folder.id, targetId)
                pending = PendingDialog.None
            },
        )

        is PendingDialog.MoveNote -> MoveToFolderDialog(
            folders = viewModel.folders,
            onDismiss = { pending = PendingDialog.None },
            onPick = { targetId ->
                viewModel.moveNote(dialog.note.id, targetId)
                pending = PendingDialog.None
            },
        )

        is PendingDialog.Commit -> CommitDialog(
            initialMessage = dialog.message,
            pendingCount = viewModel.pendingChanges,
            onDismiss = { pending = PendingDialog.None },
            onConfirm = { message ->
                val id = viewModel.commitCurrent(message)
                viewModel.gitMessage = if (id == null) {
                    "Nothing to commit"
                } else {
                    "Committed ${id.take(7)}"
                }
                pending = PendingDialog.None
            },
        )
    }

    pendingNoteDelete?.let { note ->
        ConfirmDialog(
            title = "Delete file?",
            message = if (note.title.isBlank()) {
                "This untitled file will be deleted. This cannot be undone."
            } else {
                "'${note.title}' will be deleted. This cannot be undone."
            },
            onDismiss = { pendingNoteDelete = null },
            onConfirm = {
                viewModel.deleteNote(note.id)
                pendingNoteDelete = null
                editingId = null
            },
        )
    }
}

/**
 * The version browser for the current folder or for a file inside it.
 */
@Composable
private fun HistoryContent(
    target: HistoryTarget,
    viewModel: NotesViewModel,
    onBack: () -> Unit,
) {
    val folderId = viewModel.currentFolderId
    val projectName = viewModel.currentProjectName

    when (target) {
        is HistoryTarget.File -> {
            val note = target.note
            val path = viewModel.notePathInRepo(note)
            val versions = viewModel.git.versions(folderId, path)
            VersionBrowserScreen(
                title = note.title.ifBlank { "Untitled file" },
                projectName = projectName,
                versions = versions.map { it.first },
                fileContents = versions.map { it.second },
                trees = emptyList(),
                isFile = true,
                onBack = onBack,
                onRestore = { commit ->
                    viewModel.git.restore(folderId, path, commit.id)
                    viewModel.gitMessage = "Restored ${commit.shortId} — commit to keep it"
                },
            )
        }

        HistoryTarget.Folder -> {
            val versions = viewModel.git.history(folderId, null)
            val trees = versions.map { commit ->
                viewModel.git.treeAt(folderId, null, commit.id)
            }
            VersionBrowserScreen(
                title = viewModel.currentFolder?.displayName() ?: "Files",
                projectName = projectName,
                versions = versions,
                fileContents = emptyList(),
                trees = trees,
                isFile = false,
                onBack = onBack,
                onRestore = { commit ->
                    viewModel.gitMessage = "Restore is only available for a single file"
                },
            )
        }
    }
}
