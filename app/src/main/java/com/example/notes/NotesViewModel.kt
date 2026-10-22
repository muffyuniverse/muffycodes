package com.example.notes

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import java.util.UUID

/**
 * Holds the notes, the folders and the current location in the folder tree.
 * State is exposed as Compose state so the UI recomposes automatically. Every
 * mutation is written straight through to disk.
 *
 * The view model also owns the git side of things: which folder is attached to
 * which project, how many changes are waiting to be committed, and the
 * operations that commit, push and pull.
 */
class NotesViewModel(app: Application) : AndroidViewModel(app) {

    private val store = NoteStore(app)
    private val credentials = CredentialStore(app)

    /** The git repositories of the app folders. */
    val git = GitProjects(
        rootDir = store.rootDir,
        tokenProvider = { credentials.token },
        authorProvider = { credentials.author() },
    )

    var folders by mutableStateOf<List<Folder>>(emptyList())
        private set

    var notes by mutableStateOf<List<Note>>(emptyList())
        private set

    var query by mutableStateOf("")

    /** The folder currently being viewed; null means the root. */
    var currentFolderId by mutableStateOf<String?>(null)
        private set

    /** The bindings between folders and git projects. */
    var projects by mutableStateOf<ProjectStore>(ProjectStore(emptyMap()))
        private set

    /** How many changes are waiting to be committed in the current folder. */
    var pendingChanges by mutableStateOf(0)
        private set

    /** The last message from git, shown to the user. */
    var gitMessage by mutableStateOf<String?>(null)

    init {
        val document = store.load()
        folders = document.folders
        notes = document.notes
        projects = store.loadProjects()
        refreshPendingChanges()
    }

    // ---- navigation -------------------------------------------------------

    val currentFolder: Folder?
        get() = currentFolderId?.let { id -> folders.firstOrNull { it.id == id } }

    /** The chain of folders from the root down to the current location. */
    val breadcrumb: List<Folder>
        get() = FolderTree.path(folders, currentFolderId)

    /** Direct sub-folders of the current location. */
    val childFolders: List<Folder>
        get() = FolderTree.childrenOf(folders, currentFolderId)
            .sortedBy { it.name.lowercase() }

    fun openFolder(folderId: String) {
        if (folders.any { it.id == folderId }) {
            currentFolderId = folderId
            refreshPendingChanges()
        }
    }

    /**
     * Goes up one level. A folder that sits at the top level has no parent, so going
     * up from it means returning to the top level — not doing nothing. Returns false
     * when already at the top level.
     */
    fun navigateUp(): Boolean {
        return when (val target = FolderTree.backTarget(folders, currentFolderId)) {
            is FolderTree.BackTarget.AtTopLevel -> false
            is FolderTree.BackTarget.GoTo -> {
                currentFolderId = target.folderId
                refreshPendingChanges()
                true
            }
        }
    }

    /** True when there is a level above the current location. */
    val canNavigateUp: Boolean
        get() = currentFolderId != null

    // ---- visible content --------------------------------------------------

    /**
     * The notes of the current location matching the search, pinned first, then
     * most recently updated. A search spans the whole tree, so results from other
     * folders are shown with their folder path.
     */
    val visibleNotes: List<Note>
        get() {
            val q = query.trim()
            val inScope = if (q.isEmpty()) {
                notes.filter { it.folderId == currentFolderId }
            } else {
                notes.filter {
                    it.title.contains(q, ignoreCase = true) ||
                        it.body.contains(q, ignoreCase = true)
                }
            }
            return inScope.sortedWith(
                compareByDescending<Note> { it.pinned }.thenByDescending { it.updatedAt }
            )
        }

    /** True when a search is active and showing notes from outside the current folder. */
    val isSearchingEverything: Boolean
        get() = query.isNotBlank()

    /** The folder a note belongs to, for display next to search results. */
    fun folderNameOf(note: Note): String? {
        if (note.folderId == null) return null
        val path = FolderTree.path(folders, note.folderId)
        return path.joinToString(" / ") { it.displayName() }
    }

    // ---- notes ------------------------------------------------------------

    fun createNote(folderId: String? = currentFolderId): Note {
        val note = Note(id = UUID.randomUUID().toString(), title = "", body = "", folderId = folderId)
        notes = listOf(note) + notes
        persist()
        return note
    }

    /** Renames a file (note) without opening it. */
    fun renameNote(id: String, title: String) {
        notes = notes.map { note ->
            if (note.id != id) {
                note
            } else {
                note.copy(
                    title = title.trim().ifEmpty { note.title },
                    updatedAt = System.currentTimeMillis(),
                )
            }
        }
        persist()
    }

    fun updateNote(id: String, title: String, body: String) {
        notes = notes.map { note ->
            if (note.id != id) {
                note
            } else {
                note.copy(
                    title = title.trim(),
                    body = body,
                    updatedAt = System.currentTimeMillis(),
                )
            }
        }
        persist()
    }

    fun togglePinned(id: String) {
        notes = notes.map { note ->
            if (note.id == id) note.copy(pinned = !note.pinned) else note
        }
        persist()
    }

    fun deleteNote(id: String) {
        notes = notes.filterNot { it.id == id }
        persist()
    }

    /** Moves a note into [folderId] (null puts it at the root). */
    fun moveNote(id: String, folderId: String?) {
        notes = notes.map { note ->
            if (note.id == id) note.copy(folderId = folderId, updatedAt = System.currentTimeMillis()) else note
        }
        persist()
    }

    // ---- folders ----------------------------------------------------------

    /** Creates a folder inside [parentId] and returns it. */
    fun createFolder(name: String, parentId: String? = currentFolderId): Folder {
        val folder = Folder(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { "New folder" },
            parentId = parentId,
        )
        folders = folders + folder
        persist()
        return folder
    }

    fun renameFolder(id: String, name: String) {
        val trimmed = name.trim()
        folders = folders.map { folder ->
            if (folder.id == id) folder.copy(name = trimmed.ifEmpty { folder.name }) else folder
        }
        persist()
    }

    /**
     * Deletes a folder, everything inside it, and every note inside those folders.
     * Leaving the current location is corrected so the UI never points at a
     * folder that no longer exists.
     */
    fun deleteFolder(id: String) {
        val doomed = FolderTree.descendants(folders, id).toSet()
        folders = folders.filterNot { it.id in doomed }
        notes = notes.filterNot { it.folderId != null && it.folderId in doomed }
        val here = currentFolderId
        if (here != null && here in doomed) {
            currentFolderId = nearestSurvivingAncestor(here, doomed)
        }
        projects = projects.copy(
            byFolder = projects.byFolder.filterKeys { it !in doomed }
        )
        store.saveProjects(projects)
        persist()
    }

    /**
     * Walks up from [folderId] until it reaches a folder that is not being
     * deleted. Returns null when the whole chain is going away.
     */
    private fun nearestSurvivingAncestor(folderId: String, doomed: Set<String>): String? {
        val byId = folders.associateBy { it.id }
        var current: String? = folderId
        val guard = mutableSetOf<String>()
        while (current != null && guard.add(current)) {
            if (current !in doomed) return current
            current = byId[current]?.parentId
        }
        return null
    }

    /**
     * Moves a folder under [newParentId]. Returns false when the move would make
     * the folder contain itself.
     */
    fun moveFolder(id: String, newParentId: String?): Boolean {
        if (id == newParentId) return false
        if (newParentId != null && FolderTree.isAncestorOrSelf(folders, id, newParentId)) {
            return false // would create a cycle
        }
        folders = folders.map { folder ->
            if (folder.id == id) folder.copy(parentId = newParentId) else folder
        }
        persist()
        return true
    }

    /** How many notes are directly inside [folderId]. */
    fun noteCount(folderId: String): Int = notes.count { it.folderId == folderId }

    /** How many notes are inside [folderId] and all of its sub-folders. */
    fun totalNoteCount(folderId: String): Int {
        val ids = FolderTree.descendants(folders, folderId).toSet()
        return notes.count { it.folderId != null && it.folderId in ids }
    }

    private fun persist() {
        store.save(folders, notes)
        refreshPendingChanges()
    }

    // ---- git ---------------------------------------------------------------

    /** The git project attached to a folder, or null. */
    fun projectOf(folderId: String?): GitProject? = projects.projectFor(folderId)

    /** The name of the project attached to the current folder, or null. */
    val currentProjectName: String?
        get() = projectOf(currentFolderId)?.name

    /** True when the current folder is a git project. */
    val currentIsRepository: Boolean
        get() = git.isRepository(currentFolderId)

    /** The path of a note inside the repository of its folder. */
    fun notePathInRepo(note: Note): String = store.fileNameFor(note)

    /**
     * Commits every change in the current folder. Returns the new commit id, or
     * null when there was nothing to commit.
     */
    fun commitCurrent(message: String): String? {
        val result = git.commit(currentFolderId, message)
        refreshPendingChanges()
        return result.getOrNull()
    }

    /** Pushes the current folder to its remote. */
    fun pushCurrent() {
        val result = git.push(currentFolderId)
        gitMessage = result.message
    }

    /** Pulls the latest changes into the current folder. */
    fun pullCurrent() {
        val result = git.pull(currentFolderId)
        gitMessage = result.message
        // A pull changes the files on disk, so the app has to read them again.
        val document = store.load()
        notes = document.notes
        refreshPendingChanges()
    }

    /** Attaches a git project to a folder and remembers the binding. */
    fun attachProject(folderId: String, project: GitProject) {
        projects = projects.with(folderId, project)
        store.saveProjects(projects)
    }

    /** Removes the git project of a folder, leaving the files in place. */
    fun detachProject(folderId: String) {
        projects = projects.with(folderId, null)
        store.saveProjects(projects)
    }

    /** Makes a folder into a git project. */
    fun initRepository(folderId: String?): Boolean {
        val result = git.initRepository(folderId)
        refreshPendingChanges()
        return result.isSuccess
    }

    /** Clones a remote into a folder. */
    fun cloneInto(folderId: String, remoteUrl: String): GitService.RemoteResult {
        val result = git.cloneInto(folderId, remoteUrl)
        refreshPendingChanges()
        return result
    }

    /** Recounts the changes waiting in the current folder. */
    fun refreshPendingChanges() {
        pendingChanges = git.changes(currentFolderId).total
    }
}
