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
class AppViewModel @JvmOverloads constructor(
    app: Application,
    /** Where accounts live; `files/` on a phone, a temporary folder in a test. */
    rootDir: File = app.filesDir,
    private val credentials: Credentials = CredentialStore(app),
    val settings: SettingsStore = SettingsStore(app),
    /**
     * How long a save waits before it is committed into the account's history.
     *
     * A person types; a test does not, so the wait is given rather than assumed.
     * Zero commits at once, which is what a walking skeleton of a test wants.
     */
    private val saveDebounceMs: Long = 1500L,
    /**
     * Whether this build may keep a test account. A release build may not, and the
     * answer is given rather than read from the build type so that the refusal can
     * be tested: a rule that cannot be asked about is a rule nobody has checked.
     */
    private val testAccountsAllowed: Boolean = BuildConfig.DEBUG,
    /**
     * The GitHub that signing in asks who a key belongs to.
     *
     * A seam, not a mock: production leaves it alone and gets [HttpGitHub]. It is
     * here because the failure half of signing in — a key GitHub refuses — had no
     * way to happen in a test, so the code that shows the refusal was never run.
     * The one thing that must never be substituted is the answer a *release* build
     * gives, and that is asserted separately.
     */
    private val signInGitHub: () -> GitHub = { HttpGitHub() },
) : AndroidViewModel(app) {

    /** The accounts on this phone. */
    val accounts = AccountStore(rootDir)

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

    /** The file or folder whose history is open, as a path from the account root. */
    var historyPath: List<String>? by mutableStateOf(null)
        private set

    /** True when the open history belongs to a folder rather than a file. */
    var historyIsFolder: Boolean by mutableStateOf(false)
        private set

    /**
     * Bumped whenever the list of versions could have changed.
     *
     * The history screen reads the repository to draw itself, which is too much
     * work to redo on every recomposition; this says when the answer is stale.
     */
    var historyGeneration: Int by mutableStateOf(0)
        private set

    /** What kind of thing the open history belongs to, so it can be previewed. */
    val historyKind: EntryKind
        get() {
            val ws = workspace ?: return EntryKind.TEXT
            val target = historyPath ?: return EntryKind.TEXT
            if (historyIsFolder) return EntryKind.FOLDER
            return ws.kindOf(ws.fileAt(target))
        }

    // ---- the account in use -------------------------------------------------

    /**
     * The accounts that can be switched to: the ones with a key, and the test
     * account of a build that may keep one.
     *
     * An account that was removed with its copies kept still has a folder on this
     * phone, but no key, so it is not something to switch to — signing in again is
     * what brings it back.
     */
    val accountList: List<Account>
        get() = accounts.accounts().filter {
            credentials.hasToken(it.login) || (it.dev && testAccountsAllowed)
        }

    val account: Account? get() = accounts.account(activeLogin)

    /**
     * The GitHub the active account talks to.
     *
     * A signed-in account talks to GitHub. The test account of a debug build talks
     * to a folder on this phone that behaves like one, so every feature that needs
     * an account can be walked through without a key ever existing.
     */
    fun githubFor(login: String?): GitHub? {
        val stored = accounts.account(login) ?: return null
        // A test account is a folder pretending to be GitHub. A build that may not
        // keep one refuses to act as one even if a stored account claims to be —
        // the account is then an ordinary account, and talks to GitHub.
        return if (stored.dev && testAccountsAllowed) {
            DiskGitHub(accounts.devGitHubDir(stored.login), stored.login)
        } else {
            HttpGitHub()
        }
    }

    /** The GitHub of the active account, or null when nobody is signed in. */
    val github: GitHub? get() = githubFor(activeLogin)

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
                keepProvider = { settings.historyLength },
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
        // The folders the app owns exist from the moment the account is shown, so
        // that an empty account and a new one look the same.
        ws.ensure()
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
                // The rule, asked for rather than restated (§3): a search result
                // must never carry something the app owns.
                if (ws.isHidden(child.name)) return@forEach
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
        return afterChange(result, "Created $withExtension", File(dir, withExtension))
    }

    /**
     * Brings files in from the phone's picker.
     *
     * Bytes are copied, never decoded: the commonest import is a picture or a PDF,
     * and reading one as text on the way in is how it arrives corrupted.
     */
    fun importFiles(uris: List<android.net.Uri>): Boolean {
        val ws = workspace ?: return false
        val dir = currentDir ?: return false
        val result = Imports.filesInto(application, uris, dir)
        val ok = afterChange(result, "Imported into ${dir.name}/", dir)
        if (ok) {
            val n = result.getOrNull() ?: 0
            message = if (n == 1) "Imported 1 file" else "Imported $n files"
        }
        return ok
    }

    /** Brings a whole folder in, keeping its shape. */
    fun importFolder(tree: android.net.Uri): Boolean {
        val ws = workspace ?: return false
        val dir = currentDir ?: return false
        val result = Imports.folderInto(application, tree, dir)
        val ok = afterChange(result, "Imported a folder into ${dir.name}/", dir)
        if (ok) {
            val n = result.getOrNull() ?: 0
            message = if (n == 1) "Imported 1 file" else "Imported $n files"
        }
        return ok
    }

    fun createFolder(name: String): Boolean {
        val ws = workspace ?: return false
        val dir = currentDir ?: return false
        val result = ws.createFolder(dir, name)
        return afterChange(result, "Created ${name.trim()}/", File(dir, name.trim()))
    }

    fun rename(file: File, newName: String): Boolean {
        val ws = workspace ?: return false
        val wasEditing = editingPath != null && editingPath == ws.relative(file)
        val result = ws.rename(file, newName)
        // The editor and the history point at a path, not at a file object, so a
        // rename of the file being edited would leave both looking at a name that no
        // longer exists. They follow it instead — renaming from inside the editor is
        // exactly when this happens.
        if (result.isSuccess) {
            val moved = result.getOrNull() ?: file
            if (wasEditing) editingPath = ws.relative(moved)
        }
        return afterChange(result, "Renamed ${file.name} to ${newName.trim()}", file.parentFile)
    }

    fun move(file: File, into: File): Boolean {
        val ws = workspace ?: return false
        val result = ws.move(file, into)
        return afterChange(result, "Moved ${file.name} into ${into.name}/", into)
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
        return afterChange(result, if (wasFolder) "Deleted $name/" else "Deleted $name", file.parentFile)
    }

    /** Moves a file or folder into the project's worktree (§3: the crosses). */
    fun moveIntoRepository(file: File): Boolean {
        val ws = workspace ?: return false
        val project = ws.projectOf(file) ?: return false
        val result = ws.move(file, ws.repoOf(project))
        return afterChange(result, "Moved ${file.name} into the repository", ws.repoOf(project))
    }

    /** Moves a tracked file out of the worktree, to stay on this phone only. */
    fun keepOnlyOnPhone(file: File): Boolean {
        val ws = workspace ?: return false
        val project = ws.projectOf(file) ?: return false
        val projectDir = File(ws.projectsDir, project)
        val result = ws.move(file, projectDir)
        return afterChange(result, "Kept ${file.name} on this phone only", projectDir)
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
    /**
     * Finishes a change: refresh, then commit it.
     *
     * Two histories answer, and which one depends on where the change happened. A
     * file inside a project's worktree belongs to that project's repository; the
     * rest of the tree belongs to the account's own history. Both are committed
     * **now**, because "go back to how it was" is a thing a person wants a second
     * after the mistake, not after the next sync.
     *
     * [touched] is where the change happened, when that is known. It is only a hint
     * — committing a project with nothing to commit does nothing — so a change whose
     * file has just been deleted still lands in the right history.
     */
    private fun afterChange(result: Result<*>, what: String, touched: File? = null): Boolean {
        if (result.isFailure) {
            message = result.exceptionOrNull()?.message ?: "That did not work"
            return false
        }
        refresh()
        record(what)
        touched?.let { commitProjectOf(it, what) }
        return true
    }

    /**
     * Commits the project whose tree [file] belongs to, if any.
     *
     * A local file beside a project is not in the project's repository, so it has
     * nothing to commit there and the call does nothing.
     */
    private fun commitProjectOf(file: File, what: String) {
        val ws = workspace ?: return
        val project = ws.projectOf(file) ?: return
        val g = git ?: return
        val login = activeLogin
        worker.execute {
            if (login != null && accounts.exists(login)) g.commit(project, what)
        }
    }

    /** Commits a change into the account's own history, behind the UI. */
    private fun record(what: String) {
        val login = activeLogin ?: return
        val h = history ?: return
        val keep = settings.historyLength
        // The account can be removed while this is still on its way to the worker.
        // Writing then would make its folder again, and it would come back.
        worker.execute {
            if (accounts.exists(login)) h.commit(what, keep)
        }
    }

    // ---- the editor ---------------------------------------------------------

    /** The text of the file being edited. */
    /**
     * A file's text, for the editor.
     *
     * Empty for anything that is not text, because reading it would already be the
     * destructive act: the editor keeps this string in state and saves it back on a
     * pause in typing, so handing back a UTF-8 reading of a `.zip` invites the file
     * to be overwritten with it. The screen decides what to show by [kindOf], which
     * is the same rule that made the file not-text in the first place.
     */
    fun textOf(file: File): String {
        val ws = workspace ?: return ""
        return if (ws.kindOf(file) == EntryKind.TEXT) ws.read(file) else ""
    }

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
        // Refused here as well as on the screen. The screen only ever saves text, so
        // this cannot fire in normal use — which is the point: the guarantee is that
        // no save can ever overwrite something that is not text, and a guarantee
        // held only by the caller is a convention. A `.zip` opened by mistake must
        // survive even a bug in the screen.
        if (ws.kindOf(file) != EntryKind.TEXT) return
        ws.write(file, text)
        val keep = settings.historyLength
        val h = history
        val g = git
        val name = file.name
        // A file inside a project's worktree is versioned by that project, and
        // everything else by the account. The commit happens on every pause in
        // typing — that *is* the change history — and sync is then only the push.
        val project = ws.projectOf(file)
        val login = activeLogin
        worker.execute {
            if (saveDebounceMs > 0) Thread.sleep(saveDebounceMs)
            if (login != null && accounts.exists(login)) {
                if (project != null) g?.commit(project, "Edited $name")
                else h?.commit("Edited $name", keep)
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
        val login = activeLogin
        val api = githubFor(login)
        val token = login?.let { credentials.token(it) }.orEmpty()
        if (login.isNullOrBlank() || api == null) {
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
            val created = api.createRepo(token, clean, licence)
            if (created.error != null) {
                main.post {
                    busy = false
                    message = created.error
                    onDone(false)
                }
                return@execute
            }
            val url = created.value?.cloneUrl?.takeIf { it.isNotBlank() }
                ?: api.cloneUrlFor(login, clean)
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
                { settings.historyLength },
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
        val api = github
        val token = activeLogin?.let { credentials.token(it) }.orEmpty()
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
            if (api != null && login != null) {
                val renamed = api.renameRepo(token, login, project, clean)
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
                    { settings.historyLength },
                )
                // A null here is not a failure: the folder was renamed, and only
                // the address of the repository could not follow.
                api?.cloneUrlFor(login, clean)?.let { remote ->
                    g.open(clean)?.use { repo -> GitService.setRemoteUrl(repo, remote) }
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
        val api = github
        val token = activeLogin?.let { credentials.token(it) }.orEmpty()
        val login = activeLogin
        busy = true
        worker.execute {
            var note = ""
            if (everywhere && api != null && login != null) {
                val deleted = api.deleteRepo(token, login, project)
                // A repository already gone is not a failure: the local side goes.
                // Asked by status, not by whether the message happens to contain
                // "404" — see GitHubResult.isGone.
                if (deleted.error != null && !deleted.isGone) note = deleted.error
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
    fun listRepositories(onDone: (List<Repo>, String?) -> Unit) {
        val api = github
        val token = activeLogin?.let { credentials.token(it) }.orEmpty()
        if (api == null) {
            onDone(emptyList(), "Sign in to GitHub first")
            return
        }
        worker.execute {
            val result = api.listRepos(token)
            main.post { onDone(result.value ?: emptyList(), result.error) }
        }
    }

    /** The licences a new project can be created with. */
    fun listLicences(onDone: (List<Licence>) -> Unit) {
        val api = github
        val token = activeLogin?.let { credentials.token(it) }.orEmpty()
        if (api == null) {
            onDone(emptyList())
            return
        }
        worker.execute {
            val result = api.licences(token)
            main.post { onDone(result.value ?: emptyList()) }
        }
    }

    /** Pulls a repository in from GitHub, cloning it as a new project (§5.2). */
    fun pullRepository(repo: Repo) {
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
                    { settings.historyLength },
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
     * Signs in with a key — and asks for nothing else.
     *
     * GitHub is asked who the key belongs to, and the name and email to commit as
     * come back with the answer. Asking a person to retype what their account
     * already knows was two fields that could disagree with the account they
     * belonged to; a commit is now written as whoever GitHub says holds the key.
     *
     * The call reaches the network, so it runs behind the UI like every other piece
     * of git work.
     */
    fun signInWithToken(token: String, onDone: (Boolean) -> Unit) {
        worker.execute {
            val result = signInGitHub().identity(token)
            val who = result.value
            main.post {
                if (who == null) {
                    message = result.error ?: "That key did not work"
                    onDone(false)
                } else {
                    signIn(who.login, token, who.name, who.email)
                    message = "Signed in as ${who.login}"
                    onDone(true)
                }
            }
        }
    }

    /**
     * Whether this build may make a test account.
     *
     * Only a debug build may. The account has no key and talks to a folder on this
     * phone, so it lets a test run walk the features that need an account without
     * a key ever existing. A release build has no way to ask for it: not a setting,
     * not an intent extra, nothing.
     */
    val canMakeTestAccount: Boolean get() = testAccountsAllowed

    /**
     * Makes the test account of a debug build and signs it in.
     *
     * It is a real account in every way that matters — its own folder, its own
     * identity, its own projects — and its GitHub is a folder of bare repositories
     * ([DiskGitHub]), so a project is invented, cloned, committed to and pushed
     * for real. One repository is put there first, so Pull has something to pull.
     */
    fun signInAsTestAccount(onDone: (Boolean) -> Unit = {}) {
        if (!canMakeTestAccount) {
            message = "Only a test build can do that"
            onDone(false)
            return
        }
        worker.execute {
            val login = TEST_ACCOUNT
            val github = DiskGitHub(accounts.devGitHubDir(login), login)
            if (github.listRepos("").value?.isEmpty() != false) {
                github.createRepo("", "welcome", "mit", "A repository that is not here yet")
            }
            main.post {
                accounts.save(
                    Account(
                        login = login,
                        commitName = "Test Account",
                        commitEmail = "$login@example.com",
                        dev = true,
                    )
                )
                credentials.activeLogin = login
                activeLogin = login
                path = emptyList()
                refresh()
                message = "Signed in as the test account"
                onDone(true)
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

    /**
     * Removes an account. [deleteCopies] also deletes its tree from this phone;
     * without it the folders stay and signing in again returns to them (§5).
     */
    fun removeAccount(login: String, deleteCopies: Boolean) {
        if (deleteCopies) accounts.remove(login) else accounts.forget(login)
        credentials.forget(login)
        // Another stored account takes over, so the screen is never left pointing
        // at an account that is no longer here.
        // The disk is what says which accounts this phone has; the keys only say
        // which of them can still open GitHub.
        val next = accounts.accounts().map { it.login }.firstOrNull { it != login }
            ?: credentials.logins().firstOrNull { accounts.exists(it) }
        credentials.activeLogin = next
        activeLogin = next
        path = emptyList()
        editingPath = null
        historyPath = null
        refresh()
    }

    /** The token of the active account, for the sign-in check. */
    fun tokenOf(login: String): String? = credentials.token(login)

    /**
     * True when this phone can act as [login]: it holds a key for it, or it is the
     * test account of a build that may keep one.
     *
     * Removing an account forgets its key, so a removed account is no longer
     * something to switch to — even when its folder was kept.
     */
    fun known(login: String): Boolean {
        if (credentials.hasToken(login)) return true
        return testAccountsAllowed && accounts.account(login)?.dev == true
    }

    // ---- history ------------------------------------------------------------

    /** Opens the history of a file, or of a folder and everything in it. */
    fun openHistory(file: File) {
        historyPath = workspace?.relative(file)
        historyIsFolder = file.isDirectory
        historyGeneration++
    }

    fun closeHistory() {
        historyPath = null
        historyIsFolder = false
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
        if (historyIsFolder) {
            // A folder has no text of its own: its version is the list of what
            // was inside it at that moment.
            return h.log(text).map { commit ->
                val inside = h.filesUnder(text, commit.id)
                commit to inside.joinToString("\n") { it.first.substringAfterLast('/') }
            }
        }
        return h.log(text).map { commit -> commit to h.fileAt(text, commit.id) }
    }

    /** Puts an old version back into the file. */
    fun restoreVersion(revisionId: String) {
        val ws = workspace ?: return
        val target = historyPath ?: return
        val project = ws.projectAt(target)
        val insideWorktree = target.size >= 3 && target[2] == Workspace.REPO
        val text = ws.pathString(target)
        val ok = if (project != null && insideWorktree) {
            git?.restore(project, target.drop(3).joinToString("/"), revisionId)?.isSuccess == true
        } else if (historyIsFolder) {
            // Bringing a folder back means putting every file of that version
            // where it was. Nothing is removed: a restore only ever adds back.
            val files = history?.filesUnder(text, revisionId).orEmpty()
            files.forEach { (path, body) ->
                val restored = ws.fileAt(path.split("/"))
                ws.write(restored, body)
            }
            files.isNotEmpty()
        } else {
            history?.restore(text, revisionId) == true
        }
        message = if (ok) "Restored that version" else "Could not restore it"
        if (ok) {
            refresh()
            // A restore changes what is on disk, so it is itself a version: the
            // history must be able to undo it, and an undo that cannot be undone
            // is not what "any state can be rolled back" means.
            record("Restored an older version")
            // The version the screen was showing is now a new version at the top of
            // the list, so the list the screen is holding is out of date.
            historyGeneration++
        }
    }

    companion object {
        /** The login of the test account a debug build can make. */
        const val TEST_ACCOUNT = "test-account"

        val DEFAULT_AUTHOR = GitAuthor("Muffy World", "muffyworld365@gmail.com")
        private const val SEARCH_LIMIT = 4000
        private const val SEARCH_RESULTS = 200
    }
}
