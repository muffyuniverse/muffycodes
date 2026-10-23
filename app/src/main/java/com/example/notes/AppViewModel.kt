package com.example.notes

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import java.io.File
import java.util.concurrent.Executors

/**
 * The app's state: whose account is active, where in the tree we are, what is
 * there, and every operation that changes something.
 *
 * There is no index behind this and no id anywhere. The account's folder **is**
 * the app's filesystem: opening a folder lists it, renaming renames the file on
 * disk, and a thing's identity is its path. That is what makes the repository on
 * GitHub read like a person wrote it (§2 of the design).
 *
 * Two histories are kept: each project's own repository, and the account's whole
 * tree ([AppHistory]). Both run behind the UI on a single worker, so a save never
 * waits for a commit and a sync never freezes a list.
 */
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val accounts = AccountStore(app.filesDir)
    private val credentials = CredentialStore(app)
    val settings = SettingsStore(app)

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    // ---- who and where -----------------------------------------------------

    /** The login of the active account, or null when nobody is signed in. */
    var activeLogin: String? by mutableStateOf(credentials.activeLogin)
        private set

    /** The path from the account's root, as names. Empty means the root. */
    var path: List<String> by mutableStateOf(emptyList())
        private set

    /** What is in the folder at [path]. */
    var entries: List<Entry> by mutableStateOf(emptyList())
        private set

    /** The paths that are pinned, as paths from the account root. */
    var pins: Set<String> by mutableStateOf(emptySet())
        private set

    /** The search text, which searches inside the current project (§7). */
    var query: String by mutableStateOf("")

    /** True while the search field is showing. */
    var showSearch: Boolean by mutableStateOf(false)

    /** A message to show once: the result of a sync, a deletion, an error. */
    var message: String? by mutableStateOf(null)

    /** The number of unsynced changes of each project, from `git status`. */
    var changesByProject: Map<String, Int> by mutableStateOf(emptyMap())
        private set

    /** True while a git operation is running. */
    var busy: Boolean by mutableStateOf(false)
        private set

    /** The file open in the editor, as a path from the account root. */
    var editingPath: List<String>? by mutableStateOf(null)
        private set

    /** The file whose history is open, as a path from the account root. */
    var historyPath: List<String>? by mutableStateOf(null)
        private set

    // ---- the account in use -------------------------------------------------

    val accountList: List<Account> get() = accounts.accounts()

    val account: Account? get() = accounts.account(activeLogin)

    val signedIn: Boolean get() = account != null

    /** The active account's tree, or null when nobody is signed in. */
    val workspace: Workspace? get() = activeLogin?.let { Workspace(accounts.dirOf(it)) }

    private val author: GitAuthor get() = account?.author() ?: DEFAULT_AUTHOR

    private val git: ProjectGit?
        get() = workspace?.let { ws ->
            ProjectGit(
                workspace = ws,
                tokenProvider = { activeLogin?.let { credentials.token(it) } },
                authorProvider = { author },
                keepProvider = { settings.projectHistoryLength },
            )
        }

    private val history: AppHistory?
        get() = workspace?.let { ws -> AppHistory(ws.accountDir) { author } }

    init {
        refresh()
    }

    // ---- reading the tree ---------------------------------------------------

    /** The folder being shown. */
    val currentDir: File? get() = workspace?.fileAt(path)

    /** The project the current path is inside, or null. */
    val currentProject: String? get() = workspace?.projectAt(path)

    /** True when the Projects screen is showing (the `projects` folder). */
    val atProjects: Boolean get() = path == listOf(Workspace.PROJECTS)

    /** True when the path is inside a project's worktree. */
    val inWorktree: Boolean
        get() {
            val p = path
            return p.size >= 3 && p[0] == Workspace.PROJECTS && p[2] == Workspace.REPO
        }

    /** True when there is a level above. */
    val canGoUp: Boolean get() = path.isNotEmpty()

    /** The names of the projects on this phone. */
    val projectNames: List<String> get() = workspace?.projectNames() ?: emptyList()

    /** Re-reads the folder and the pins. */
    fun refresh() {
        val ws = workspace ?: run {
            entries = emptyList()
            pins = emptySet()
            return
        }
        entries = currentDir?.let { ws.list(it) } ?: emptyList()
        pins = ws.pins()
        refreshChanges()
    }

    /** Recounts the unsynced changes of every project, behind the UI. */
    fun refreshChanges() {
        val ws = workspace ?: return
        val g = git ?: return
        val names = ws.projectNames()
        worker.execute {
            val counts = names.associateWith { g.changes(it).total }
            main.post { changesByProject = counts }
        }
    }

    /** How many changes [project] has waiting, as last counted. */
    fun changesOf(project: String): Int = changesByProject[project] ?: 0

    // ---- moving around ------------------------------------------------------

    /** Opens a folder by path from the account root. */
    fun open(target: List<String>) {
        path = target
        query = ""
        refresh()
    }

    /** Opens the child of the current folder with this name. */
    fun openChild(name: String) = open(path + name)

    /** Goes up one level. Returns false when already at the root. */
    fun up(): Boolean {
        if (path.isEmpty()) return false
        path = path.dropLast(1)
        query = ""
        refresh()
        return true
    }

    /** Leaves a project and shows the projects list. */
    fun goToProjects() = open(listOf(Workspace.PROJECTS))

    fun goToRoot() = open(emptyList())

    /** The entries shown for a search, or the folder's own contents. */
    val visibleEntries: List<Entry>
        get() {
            val q = query.trim()
            if (q.isEmpty()) return entries
            val project = currentProject ?: return emptyList()
            val ws = workspace ?: return emptyList()
            val root = ws.projectNames().firstOrNull { it == project }
                ?.let { File(ws.projectsDir, it) } ?: return emptyList()
            return search(root, q)
        }

    /** True when a search is running. */
    val isSearching: Boolean get() = query.isNotBlank()

    /** Every file and folder in [dir] whose name or text matches [q]. */
    private fun search(dir: File, q: String): List<Entry> {
        val found = mutableListOf<Entry>()
        val stack = ArrayDeque<File>()
        val ws = workspace ?: return emptyList()
        stack.addLast(dir)
        var guard = 0
        while (stack.isNotEmpty() && guard++ < SEARCH_LIMIT) {
            val here = stack.removeFirst()
            (here.listFiles() ?: emptyArray()).forEach { child ->
                if (child.name.startsWith(".")) return@forEach
                val matched = child.name.contains(q, ignoreCase = true) ||
                    (child.isFile && ws.kindOf(child) == EntryKind.TEXT &&
                        ws.read(child).contains(q, ignoreCase = true))
                if (matched) found.add(Entry(child.name, child, ws.kindOf(child)))
                if (child.isDirectory) stack.addLast(child)
            }
        }
        return found.take(SEARCH_RESULTS)
    }

    // ---- creating things ----------------------------------------------------

    fun createFile(name: String): Boolean {
        val ws = workspace ?: return false
        val dir = currentDir ?: return false
        val withExtension = Workspace.withDefaultExtension(name.trim())
        val result = ws.createFile(dir, withExtension)
        return afterChange(result, "Created $withExtension")
    }

    fun createFolder(name: String): Boolean {
        val ws = workspace ?: return false
        val dir = currentDir ?: return false
        val result = ws.createFolder(dir, name)
        return afterChange(result, "Created ${name.trim()}/")
    }

    fun rename(file: File, newName: String): Boolean {
        val ws = workspace ?: return false
        val result = ws.rename(file, newName)
        return afterChange(result, "Renamed ${file.name} to ${newName.trim()}")
    }

    fun move(file: File, into: File): Boolean {
        val ws = workspace ?: return false
        val result = ws.move(file, into)
        return afterChange(result, "Moved ${file.name} into ${into.name}/")
    }

    fun delete(file: File): Boolean {
        val ws = workspace ?: return false
        val wasFolder = file.isDirectory
        val name = file.name
        val result = ws.delete(file)
        // A deleted folder must not leave the view pointing inside it.
        if (result.isSuccess && path.size > 0) {
            val doomed = path.size
            val parent = file.parentFile
            if (parent != null) {
                val target = ws.relative(parent)?.takeIf { it.size < doomed }
                if (target != null && (path.size > target.size)) path = target
            }
        }
        return afterChange(result, if (wasFolder) "Deleted $name/" else "Deleted $name")
    }

    /** Moves a file or folder into the project's worktree (§3: the crosses). */
    fun moveIntoRepository(file: File): Boolean {
        val ws = workspace ?: return false
        val project = ws.projectOf(file) ?: return false
        val result = ws.move(file, ws.repoOf(project))
        return afterChange(result, "Moved ${file.name} into the repository")
    }

    /** Moves a tracked file out of the worktree, to stay on this phone only. */
    fun keepOnlyOnPhone(file: File): Boolean {
        val ws = workspace ?: return false
        val project = ws.projectOf(file) ?: return false
        val projectDir = File(ws.projectsDir, project)
        val result = ws.move(file, projectDir)
        return afterChange(result, "Kept ${file.name} on this phone only")
    }

    /** Pins or unpins a file or folder (§2: the flags live in `.muffy`). */
    fun togglePinned(file: File) {
        val ws = workspace ?: return
        val target = ws.relative(file) ?: return
        val key = ws.pathString(target)
        val next = if (key in pins) pins - key else pins + key
        ws.setPins(next)
        pins = next
    }

    /** Runs a change, records it in the account's history, and reloads. */
    private fun afterChange(result: Result<*>, what: String): Boolean {
        if (result.isFailure) {
            message = result.exceptionOrNull()?.message ?: "That did not work"
            return false
        }
        refresh()
        record(what)
        return true
    }

    /** Commits a change into the account's own history, behind the UI. */
    private fun record(what: String) {
        val h = history ?: return
        val keep = settings.appHistoryLength
        worker.execute {
            h.commit(what, keep)
        }
    }

    // ---- the editor ---------------------------------------------------------

    /** The text of the file being edited. */
    fun textOf(file: File): String = workspace?.read(file) ?: ""

    /** Opens a file in the editor. */
    fun openEditor(file: File) {
        editingPath = workspace?.relative(file)
    }

    fun closeEditor() {
        editingPath = null
    }

    /** The file the editor is showing, or null. */
    val editingFile: File?
        get() = editingPath?.let { workspace?.fileAt(it) }

    /**
     * Saves the editor's text. The file is written on disk, then committed to the
     * account's history — which is where a local file's versions come from.
     */
    fun saveEditor(text: String) {
        val ws = workspace ?: return
        val file = editingFile ?: return
        ws.write(file, text)
        val keep = settings.appHistoryLength
        val h = history
        val name = file.name
        if (h != null) {
            worker.execute {
                Thread.sleep(SAVE_DEBOUNCE_MS)
                h.commit("Edited $name", keep)
            }
        }
        refresh()
    }

    // ---- the project actions ------------------------------------------------

    /** Syncs a project: commit what changed with the names, then force push. */
    fun sync(project: String) {
        val g = git ?: return
        busy = true
        worker.execute {
            val result = g.sync(project)
            main.post {
                busy = false
                message = result.message
                refreshChanges()
                record("Synced $project")
            }
        }
    }

    /**
     * Replaces a project with what GitHub holds (§5.2). An existing project is
     * asked about first by the screen; this is the act.
     */
    fun replaceWithRemote(project: String) {
        val g = git ?: return
        busy = true
        worker.execute {
            val result = g.replaceWithRemote(project)
            main.post {
                busy = false
                message = result.message
                refresh()
            }
        }
    }

    /**
     * Makes a project: the repository on GitHub first, seeded with its licence,
     * then the folder and the clone (§4.1).
     */
    fun createProject(name: String, licence: String, onDone: (Boolean) -> Unit = {}) {
        val ws = workspace ?: return
        val token = activeLogin?.let { credentials.token(it) }
        val login = activeLogin
        if (token.isNullOrBlank() || login.isNullOrBlank()) {
            message = "Sign in to GitHub first"
            onDone(false)
            return
        }
        val clean = name.trim()
        val problem = ws.nameProblem(clean)
        if (problem != null) {
            message = problem
            onDone(false)
            return
        }
        if (ws.projectNames().any { it.equals(clean, ignoreCase = true) }) {
            message = "There is already a project called \"$clean\""
            onDone(false)
            return
        }

        busy = true
        worker.execute {
            val created = GitHubClient.createRepo(token, clean, licence)
            if (created.error != null) {
                main.post {
                    busy = false
                    message = created.error
                    onDone(false)
                }
                return@execute
            }
            val url = created.value?.cloneUrl?.takeIf { it.isNotBlank() }
                ?: GitHubClient.cloneUrlFor(login, clean)
            val folders = ws.createProjectFolders(clean)
            if (folders.isFailure) {
                main.post {
                    busy = false
                    message = folders.exceptionOrNull()?.message ?: "Could not make the project"
                    onDone(false)
                }
                return@execute
            }
            val clone = ProjectGit(
                ws,
                { credentials.token(login) },
                { accounts.account(login)?.author() ?: DEFAULT_AUTHOR },
                { settings.projectHistoryLength },
            ).cloneInto(clean, url)

            main.post {
                busy = false
                message = clone.message
                open(listOf(Workspace.PROJECTS, clean))
                record("Created project $clean")
                onDone(clone.success)
            }
        }
    }

    /** Renames a project and its repository together (§3). */
    fun renameProject(project: String, newName: String) {
        val ws = workspace ?: return
        val token = activeLogin?.let { credentials.token(it) }
        val login = activeLogin
        val clean = newName.trim()
        val problem = ws.nameProblem(clean)
        if (problem != null) {
            message = problem
            return
        }
        if (clean == project) return
        if (ws.projectNames().any { it.equals(clean, ignoreCase = true) }) {
            message = "There is already a project called \"$clean\""
            return
        }
        busy = true
        worker.execute {
            var note = ""
            if (token != null && login != null) {
                val renamed = GitHubClient.renameRepo(token, login, project, clean)
                if (renamed.error != null) note = renamed.error
            }
            val moved = ws.rename(File(ws.projectsDir, project), clean)
            if (moved.isFailure) {
                main.post {
                    busy = false
                    message = moved.exceptionOrNull()?.message ?: "Could not rename the folder"
                }
                return@execute
            }
            // The repository moved too, so its address changes with it.
            if (token != null && login != null && note.isEmpty()) {
                val g = ProjectGit(
                    ws,
                    { credentials.token(login) },
                    { accounts.account(login)?.author() ?: DEFAULT_AUTHOR },
                    { settings.projectHistoryLength },
                )
                g.open(clean)?.use { repo ->
                    GitService.setRemoteUrl(repo, GitHubClient.cloneUrlFor(login, clean))
                }
            }
            main.post {
                busy = false
                message = note.ifBlank { "Renamed to $clean" }
                if (path.size >= 2 && path[0] == Workspace.PROJECTS && path[1] == project) {
                    path = listOf(Workspace.PROJECTS, clean) + path.drop(2)
                }
                refresh()
                record("Renamed project $project to $clean")
            }
        }
    }

    /**
     * Deletes a project. [everywhere] also deletes the repository on GitHub;
     * without it only this phone's copy goes and the add button brings it back.
     */
    fun deleteProject(project: String, everywhere: Boolean) {
        val ws = workspace ?: return
        val token = activeLogin?.let { credentials.token(it) }
        val login = activeLogin
        busy = true
        worker.execute {
            var note = ""
            if (everywhere && token != null && login != null) {
                val deleted = GitHubClient.deleteRepo(token, login, project)
                // A repository already gone is not a failure: the local side goes.
                if (deleted.error != null && !deleted.error.contains("404")) {
                    note = deleted.error
                }
            }
            ws.delete(File(ws.projectsDir, project))
            main.post {
                busy = false
                message = note.ifBlank {
                    if (everywhere) "Deleted $project everywhere" else "Deleted $project from this phone"
                }
                if (currentProject == project) path = listOf(Workspace.PROJECTS)
                refresh()
                record("Deleted project $project")
            }
        }
    }

    /** The account's public repositories, for the add window (§6). */
    fun listRepositories(onDone: (List<GitHubClient.Repo>, String?) -> Unit) {
        val token = activeLogin?.let { credentials.token(it) }
        if (token.isNullOrBlank()) {
            onDone(emptyList(), "Sign in to GitHub first")
            return
        }
        worker.execute {
            val result = GitHubClient.listRepos(token)
            main.post { onDone(result.value ?: emptyList(), result.error) }
        }
    }

    /** The licences a new project can be created with. */
    fun listLicences(onDone: (List<GitHubClient.Licence>) -> Unit) {
        val token = activeLogin?.let { credentials.token(it) }
        if (token.isNullOrBlank()) {
            onDone(emptyList())
            return
        }
        worker.execute {
            val result = GitHubClient.licences(token)
            main.post { onDone(result.value ?: emptyList()) }
        }
    }

    /** Pulls a repository in from GitHub, cloning it as a new project (§5.2). */
    fun pullRepository(repo: GitHubClient.Repo) {
        val ws = workspace ?: return
        val token = activeLogin?.let { credentials.token(it) }
        val login = activeLogin ?: return
        val existing = ws.projectNames().firstOrNull { it.equals(repo.name, ignoreCase = true) }
        if (existing != null) {
            replaceWithRemote(existing)
            return
        }
        busy = true
        worker.execute {
            val folders = ws.createProjectFolders(repo.name)
            val result = if (folders.isFailure) {
                GitService.RemoteResult(
                    folders.exceptionOrNull()?.message ?: "Could not make the project", false
                )
            } else {
                ProjectGit(
                    ws,
                    { credentials.token(login) },
                    { accounts.account(login)?.author() ?: DEFAULT_AUTHOR },
                    { settings.projectHistoryLength },
                ).cloneInto(repo.name, repo.cloneUrl)
            }
            main.post {
                busy = false
                message = result.message
                open(listOf(Workspace.PROJECTS, repo.name))
            }
        }
    }

    // ---- accounts -----------------------------------------------------------

    /**
     * Signs in with a key: GitHub is asked who the key belongs to, and only then
     * is the account stored. The call reaches the network, so it runs behind the
     * UI like every other piece of git work.
     */
    fun signInWithToken(token: String, name: String, email: String, onDone: (Boolean) -> Unit) {
        worker.execute {
            val result = GitHubClient.whoami(token)
            val login = result.value
            main.post {
                if (login == null) {
                    message = result.error ?: "That key did not work"
                    onDone(false)
                } else {
                    signIn(login, token, name, email)
                    message = "Signed in as $login"
                    onDone(true)
                }
            }
        }
    }

    /** Signs an account in, or adds it when it is new, and makes it active. */
    fun signIn(login: String, token: String, commitName: String, commitEmail: String) {
        credentials.setToken(login, token)
        accounts.save(
            Account(
                login = login,
                commitName = commitName.ifBlank { login },
                commitEmail = commitEmail.ifBlank { "$login@users.noreply.github.com" },
            )
        )
        credentials.activeLogin = login
        activeLogin = login
        path = emptyList()
        refresh()
    }

    /** Makes a stored account active. */
    fun switchTo(login: String) {
        credentials.activeLogin = login
        activeLogin = login
        path = emptyList()
        editingPath = null
        historyPath = null
        refresh()
    }

    /** Updates the commit identity of the active account. */
    fun updateIdentity(name: String, email: String) {
        val current = account ?: return
        accounts.save(current.copy(commitName = name, commitEmail = email))
        refresh()
    }

    /**
     * Removes an account. [deleteCopies] also deletes its tree from this phone;
     * without it the folders stay and signing in again returns to them (§5).
     */
    fun removeAccount(login: String, deleteCopies: Boolean) {
        if (deleteCopies) accounts.remove(login)
        credentials.forget(login)
        // Another stored account takes over, so the screen is never left pointing
        // at an account that is no longer here.
        val next = credentials.logins().firstOrNull { accounts.exists(it) }
        credentials.activeLogin = next
        activeLogin = next
        path = emptyList()
        editingPath = null
        historyPath = null
        refresh()
    }

    /** The token of the active account, for the sign-in check. */
    fun tokenOf(login: String): String? = credentials.token(login)

    /** True when [login] already has a token on this phone. */
    fun known(login: String): Boolean = credentials.hasToken(login)

    // ---- history ------------------------------------------------------------

    /** Opens the history of a file. */
    fun openHistory(file: File) {
        historyPath = workspace?.relative(file)
    }

    fun closeHistory() {
        historyPath = null
    }

    /**
     * The versions of the file whose history is open, newest first.
     *
     * Which history answers depends on where the file is: inside a project's
     * worktree the project's own repository has it, and anywhere else the
     * account's history does (§4).
     */
    fun versions(): List<Pair<GitService.CommitInfo, String?>> {
        val ws = workspace ?: return emptyList()
        val target = historyPath ?: return emptyList()
        val project = ws.projectAt(target)
        val insideWorktree = target.size >= 3 && target[2] == Workspace.REPO
        if (project != null && insideWorktree) {
            val g = git ?: return emptyList()
            val inside = target.drop(3).joinToString("/")
            return g.versions(project, inside)
        }
        val h = history ?: return emptyList()
        val text = ws.pathString(target)
        return h.log(text).map { commit -> commit to h.fileAt(text, commit.id) }
    }

    /** Puts an old version back into the file. */
    fun restoreVersion(revisionId: String) {
        val ws = workspace ?: return
        val target = historyPath ?: return
        val project = ws.projectAt(target)
        val insideWorktree = target.size >= 3 && target[2] == Workspace.REPO
        val ok = if (project != null && insideWorktree) {
            git?.restore(project, target.drop(3).joinToString("/"), revisionId)?.isSuccess == true
        } else {
            history?.restore(ws.pathString(target), revisionId) == true
        }
        message = if (ok) "Restored that version" else "Could not restore it"
        if (ok) refresh()
    }

    companion object {
        val DEFAULT_AUTHOR = GitAuthor("Muffy World", "muffyworld365@gmail.com")
        private const val SEARCH_LIMIT = 4000
        private const val SEARCH_RESULTS = 200
        private const val SAVE_DEBOUNCE_MS = 1500L
    }
}
