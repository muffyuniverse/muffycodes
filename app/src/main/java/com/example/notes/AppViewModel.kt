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
     * How long a save waits before it is committed.
     *
     * **Zero, because saving is a button now.** The wait existed when a pause in
     * typing was the save: it folded a sentence's worth of pauses into one commit.
     * With an explicit Save the wait buys nothing and costs something real — the
     * commit had not landed yet when the history was opened a moment later, so the
     * newest version in the list was the one *before* the save. The value stays as a
     * parameter because a test may want to coalesce deliberately.
     */
    private val saveDebounceMs: Long = 0L,
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

    /**
     * Runs [task] on the worker, and refuses to let it take the app down.
     *
     * An exception on a background thread is not contained on Android: the thread's
     * default handler ends the process, and the person sees the app close with no
     * explanation. Every git call already catches its own failures, so what is left
     * here is the failure that is an `Error` rather than an `Exception` — running
     * out of memory while hashing a large file, for instance — which is exactly the
     * kind that closed the app on a phone and said nothing.
     *
     * Catching [Throwable] means the worst a change can do is fail and say so.
     */
    private fun onWorker(task: () -> Unit) {
        worker.execute {
            try {
                task()
            } catch (t: Throwable) {
                // The last resort: something on the worker got past every catch of
                // its own. It is named in the trace as a failure, so the one class of
                // bug that used to close the app in silence leaves a mark.
                val said = Secrets.explain(t, "That change could not be finished")
                Trace.failure(said)
                main.post { message = said }
            }
        }
    }

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

    /** The paths that are locked against editing, as paths from the account root. */
    var lockedPaths: Set<String> by mutableStateOf(emptySet())
        private set

    /** The paths that are selected, for a move or a delete of several at once. */
    var selected: Set<String> by mutableStateOf(emptySet())
        private set

    /** The search text, which searches inside the current project (§7). */
    var query: String by mutableStateOf("")

    /** True while the search field is showing. */
    var showSearch: Boolean by mutableStateOf(false)

    /**
     * A message to show once: the result of a sync, a deletion, an error.
     *
     * **Every message is also a trace line,** which is why this is a property with a
     * setter rather than a plain state object: a sentence is the app's answer to
     * something a person did, and an intent answered by words is not a broken
     * sequence. Writing `Trace.said(...)` beside forty assignments would have been
     * forty chances to forget one.
     */
    var message: String?
        get() = messageState
        set(value) {
            messageState = value
            if (value != null) Trace.said(value)
        }

    private var messageState: String? by mutableStateOf(null)

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
        // The trace's durable copy lives beside everything else the app owns, in the
        // account's `.muffy/` — outside both histories, so a run can never appear as a
        // version, and in one folder with the settings.
        attachTrace()
        Trace.note("app opened", "signed in as " + (activeLogin ?: "nobody"))
        refresh()
    }

    /** Points the trace's file at the active account, or nowhere when signed out. */
    private fun attachTrace() {
        val login = activeLogin
        Trace.attach(login?.let { File(accounts.stateDir(it), "trace.txt") })
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

    /**
     * True when a file may be moved into [dir].
     *
     * Asked by the move window rather than restated there: the app's own folders —
     * the hidden ones, and `projects/` itself — cannot hold a person's file, and
     * `repo/` is the deliberate exception. A window that decided this for itself
     * would be a second copy of the rule, and the copy that is wrong is always the
     * one nobody is looking at.
     */
    fun canHoldFiles(dir: File): Boolean {
        val ws = workspace ?: return false
        if (!dir.isDirectory) return false
        if (ws.isHidden(dir.name)) return false
        return !(ws.isStructural(dir) && dir.name != Workspace.REPO)
    }

    /** The file at a path from the account root, as names. */
    fun fileAt(path: List<String>): File? = workspace?.fileAt(path)

    /** Re-reads the folder and the pins. */
    fun refresh() {
        val ws = workspace ?: run {
            entries = emptyList()
            pins = emptySet()
            return
        }
        val before = stateSignature()
        // The folders the app owns exist from the moment the account is shown, so
        // that an empty account and a new one look the same.
        ws.ensure()
        entries = currentDir?.let { ws.list(it) } ?: emptyList()
        pins = ws.pins()
        lockedPaths = ws.locked()
        // A selection is a set of paths, and a path that is no longer there cannot
        // be selected: an item moved or deleted out from under the selection would
        // otherwise be acted on again by the next press.
        selected = selected.filterTo(mutableSetOf()) { ws.fileAt(it.split("/")).exists() }
        refreshChanges()
        // **One funnel for "the screen changed".** Every change this app makes ends in
        // `refresh()`, so this is the one place that can answer a tap without forty
        // call sites having to remember to. A change that leaves the signature alone
        // is not reported, because a tap answered by "nothing looks different" is not
        // an answer.
        val after = stateSignature()
        if (after != before) Trace.changed(after)
    }

    /**
     * What the screen looks like, in one line.
     *
     * Deliberately made of what a person can *see*: where they are, what the rows say,
     * which are locked or pinned, what is selected, whether anything is being worked
     * on, and the count on every project row. Two states with the same signature are
     * the same screen, which is what makes it usable as the answer to a tap.
     */
    fun stateSignature(): String = buildString {
        append("at ").append(if (path.isEmpty()) "/" else path.joinToString("/"))
        append(" · ").append(entries.size).append(" rows")
        val shown = entries.take(40)
        for (e in shown) {
            append(" · ").append(e.name)
            if (e.isFolder) append('/')
            val rel = if (path.isEmpty()) e.name else path.joinToString("/") + "/" + e.name
            if (rel in lockedPaths) append('L')
            if (rel in pins) append('P')
        }
        if (entries.size > shown.size) append(" · +").append(entries.size - shown.size)
        if (selected.isNotEmpty()) {
            append(" · selected ").append(selected.size)
            append(": ").append(selected.sorted().joinToString(","))
        }
        editingPath?.let { append(" · editing ").append(it.joinToString("/")) }
        historyPath?.let { append(" · history ").append(it.joinToString("/")) }
        if (busy) append(" · busy")
        if (changesByProject.isNotEmpty()) {
            append(" · changed ")
            append(changesByProject.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" })
        }
        if (query.isNotEmpty()) append(" · search ").append(query)
    }

    /** Recounts the unsynced changes of every project, behind the UI. */
    fun refreshChanges() {
        val ws = workspace ?: return
        val g = git ?: return
        val names = ws.projectNames()
        onWorker {
            val counts = names.associateWith { g.changes(it).total }
            main.post { changesByProject = counts }
        }
    }

    /**
     * The build, in one line, for a report to carry.
     *
     * `versionName` and `versionCode` are what the phone installed; the device line is
     * what makes "it crashes on mine" reproducible by somebody who is not holding it.
     * Nothing here identifies the person or the account.
     */
    val versionLine: String
        get() = try {
            val info = getApplication<android.app.Application>().packageManager
                .getPackageInfo(getApplication<android.app.Application>().packageName, 0)
            val version = if (android.os.Build.VERSION.SDK_INT >= 28) {
                info.versionName + " (" + info.longVersionCode + ")"
            } else {
                @Suppress("DEPRECATION")
                info.versionName + " (" + info.versionCode + ")"
            }
            "Muffy Codes $version · Android ${android.os.Build.VERSION.RELEASE} " +
                "(API ${android.os.Build.VERSION.SDK_INT}) · ${android.os.Build.MANUFACTURER} " +
                "${android.os.Build.MODEL}"
        } catch (t: Throwable) {
            "Muffy Codes · Android ${android.os.Build.VERSION.RELEASE}"
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
        val asked = Workspace.withDefaultExtension(name.trim())
        val result = ws.createFile(dir, asked)
        val made = result.getOrNull()?.name ?: asked
        return afterChange(result, "Created $made${namedFrom(asked, made)}", File(dir, made))
    }

    /**
     * Brings files in from the phone's picker.
     *
     * Bytes are copied, never decoded: the commonest import is a picture or a PDF,
     * and reading one as text on the way in is how it arrives corrupted.
     */
    fun importFiles(uris: List<android.net.Uri>): Boolean {
        val dir = currentDir ?: return false
        // The copy happens on the worker, not in the picker's answer.
        //
        // This used to run on the main thread, and a screenshot, a video or an
        // installed .apk from Downloads is tens of megabytes: the window stopped
        // responding for as long as the copy took, and Android killed the app for
        // it. The file was on disk by then — which is why it looked as though the
        // import worked and then the app died for no reason.
        busy = true
        onWorker {
            val result = Imports.filesInto(getApplication(), uris, dir)
            main.post {
                busy = false
                if (afterChange(result, "Imported into ${dir.name}/", dir)) {
                    message = result.getOrNull()?.said() ?: "Imported"
                }
            }
        }
        return true
    }

    /** Brings a whole folder in, keeping its shape. */
    fun importFolder(tree: android.net.Uri): Boolean {
        val dir = currentDir ?: return false
        busy = true
        onWorker {
            val result = Imports.folderInto(getApplication(), tree, dir)
            main.post {
                busy = false
                if (afterChange(result, "Imported a folder into ${dir.name}/", dir)) {
                    message = result.getOrNull()?.said() ?: "Imported"
                }
            }
        }
        return true
    }

    fun createFolder(name: String): Boolean {
        val ws = workspace ?: return false
        val dir = currentDir ?: return false
        val result = ws.createFolder(dir, name)
        val made = result.getOrNull()?.name?.plus("/") ?: name.trim()
        return afterChange(result, "Created $made${namedFrom(name, made.trimEnd('/'))}", File(dir, made.trimEnd('/')))
    }

    fun rename(file: File, newName: String): Boolean = renameTo(file, newName) != null

    /**
     * Renames [file] and answers with the file as it now is.
     *
     * The answer is the **file**, not "yes": a rename is the one change where the
     * thing being changed gets a new identity, and a caller that has to guess the new
     * name — or read it back out of the app's state — gets the name from *before* the
     * change. That is exactly how the editor's title ended up lagging one rename
     * behind, and showing a name the file no longer had.
     */
    fun renameTo(file: File, newName: String): File? {
        val ws = workspace ?: return null
        // Silent, like saving: a locked file says so on its row and in the editor.
        if (isLocked(file)) return null
        val wasEditing = editingPath != null && editingPath == ws.relative(file)
        val result = ws.rename(file, newName)
        // The editor and the history point at a path, not at a file object, so a
        // rename of the file being edited would leave both looking at a name that no
        // longer exists. They follow it instead — renaming from inside the editor is
        // exactly when this happens.
        val moved = result.getOrNull()
        if (result.isSuccess && moved != null && wasEditing) editingPath = ws.relative(moved)
        val made = moved?.name ?: newName.trim()
        return if (afterChange(result, "Renamed ${file.name} to $made${namedFrom(newName, made)}", file.parentFile)) {
            moved
        } else {
            null
        }
    }

    /**
     * Moves [file] into the folder [into], wherever that is in the account.
     *
     * A move is the one command that can cross the line between the two histories,
     * and both sides have to be told. A file taken into a project's repository must
     * be **tracked** by that project; a file taken out of one must have its removal
     * **recorded** there, or GitHub would go on holding a copy of a file this phone
     * no longer has. Each history trusts the other to have committed what it can
     * see, so a move that only commits the destination leaves the source repository
     * quietly wrong — which is exactly what "keep only on this phone" did.
     */
    fun move(file: File, into: File): Boolean {
        val ws = workspace ?: return false
        val was = ws.projectOf(file)?.takeIf { ws.inWorktree(file) }
        val now = ws.projectOf(into)?.takeIf { ws.inWorktree(into) }
        val result = ws.move(file, into)
        if (result.isFailure) {
            message = result.exceptionOrNull()?.message ?: "That did not work"
            return false
        }
        val moved = result.getOrNull() ?: file
        refresh()
        val what = "Moved ${file.name} into ${into.name}/"
        record(what)
        // The destination gains the file, and the source loses it. One commit each
        // when they are different repositories; one in all when it stayed inside
        // the same one.
        if (was != null && was != now) commitProject(was, what)
        if (now != null) commitProject(now, what)
        historyGeneration++
        return true
    }

    fun delete(file: File): Boolean {
        val ws = workspace ?: return false
        // A lock is read-only in every direction: not written, not renamed, not
        // deleted. Only the screen was refusing, which is the same as not refusing.
        if (isLocked(file)) {
            message = "${file.name} is locked, so it was not deleted"
            return false
        }
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

    /**
     * Locks or unlocks a file.
     *
     * A locked file can be opened and read, and — inside the editor — not written.
     * The lock is a property of this phone's copy (`.muffy/locked.json`), so it makes
     * no commit: locking does not change the file, and unlocking does not undo
     * anything. Anything that writes has to ask [isLocked] first, which is why the
     * question is answered here and not by the screen alone.
     */
    fun toggleLocked(file: File) {
        val ws = workspace ?: return
        val target = ws.relative(file) ?: return
        val key = ws.pathString(target)
        val next = if (key in lockedPaths) lockedPaths - key else lockedPaths + key
        ws.setLocked(next)
        lockedPaths = next
        // No message. The row's icon changes, which is the whole answer to "is it
        // locked?" — and a snackbar per tap said the same thing over the list.
    }

    /** True when [file] may not be written. */
    fun isLocked(file: File): Boolean {
        val ws = workspace ?: return false
        val target = ws.relative(file) ?: return false
        return ws.pathString(target) in lockedPaths
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

    // ---- choosing several things --------------------------------------------

    /** True when [file] is selected. */
    fun isSelected(file: File): Boolean {
        val ws = workspace ?: return false
        val target = ws.relative(file) ?: return false
        return ws.pathString(target) in selected
    }

    /** Adds [file] to the selection, or takes it out. */
    fun toggleSelected(file: File) {
        val ws = workspace ?: return
        val target = ws.relative(file) ?: return
        val key = ws.pathString(target)
        selected = if (key in selected) selected - key else selected + key
    }

    /** Drops the selection — leaving selection mode, or finishing with it. */
    fun clearSelection() {
        selected = emptySet()
    }

    /** The selected things that still exist, deepest path last. */
    private fun selectedFiles(): List<File> {
        val ws = workspace ?: return emptyList()
        return selected
            .map { ws.fileAt(it.split("/")) }
            .filter { it.exists() }
            // Parents before children, so deleting a folder does not leave its
            // children to be deleted twice — and a failure on one is reported
            // without stopping the rest.
            .sortedBy { it.path }
    }

    /**
     * Moves everything selected into [into], and records both sides of every cross.
     *
     * The files are moved one at a time so that a single refusal — a name already
     * taken in the destination, a folder into itself — does not take the rest with
     * it. The commits happen after the moves, not per move: a commit takes
     * everything that is waiting anyway, so per-move commits would fold into one and
     * cost a repository walk each.
     */
    fun moveSelected(into: File): Boolean {
        val ws = workspace ?: return false
        val files = selectedFiles().filter { it.canonicalFile != into.canonicalFile }
        if (files.isEmpty()) {
            message = "Nothing is selected"
            return false
        }
        val refused = mutableListOf<String>()
        var movedCount = 0
        // Which repositories the things came out of. A move that crosses the
        // boundary has to be recorded on both sides, and with several things at once
        // the sources can be more than one.
        val was = mutableSetOf<String>()
        files.forEach { file ->
            ws.projectOf(file)?.takeIf { ws.inWorktree(file) }?.let { was += it }
            val result = ws.move(file, into)
            if (result.isFailure) {
                refused += result.exceptionOrNull()?.message ?: "One file could not be moved"
            } else {
                movedCount++
            }
        }
        selected = emptySet()
        refresh()
        if (movedCount == 0) {
            message = refused.firstOrNull() ?: "Nothing was moved"
            return false
        }
        val what = if (movedCount == 1) {
            "Moved 1 item into ${into.name}/"
        } else {
            "Moved $movedCount items into ${into.name}/"
        }
        record(what)
        // Both sides of the boundary, as for one move: the destination gains what
        // arrived, and every source repository records what left it.
        val arriving = ws.projectOf(into)?.takeIf { ws.inWorktree(into) }
        val leaving = was - setOfNotNull(arriving)
        leaving.forEach { commitProject(it, what) }
        if (arriving != null) commitProject(arriving, what)
        historyGeneration++
        message = if (refused.isEmpty()) what else "$what — ${refused.first()}"
        return true
    }

    /** Deletes everything selected, in one recorded change. */
    fun deleteSelected(): Boolean {
        val ws = workspace ?: return false
        val files = selectedFiles()
        if (files.isEmpty()) {
            message = "Nothing is selected"
            return false
        }
        // **One locked file stops all of it.** Deleting the rest and leaving that
        // one behind reads as a delete that worked, and the person finds out later
        // which half happened.
        val locked = files.firstOrNull { isLocked(it) }
        if (locked != null) {
            message = "${locked.name} is locked, so nothing was deleted"
            return false
        }
        // A folder that is already going takes its children with it, and deleting
        // them afterwards would either fail or delete something that was made again.
        val gone = mutableListOf<File>()
        files.forEach { file ->
            if (gone.any { file.path.startsWith(it.path + File.separator) }) return@forEach
            if (ws.delete(file).isSuccess) gone += file else message = "Could not delete ${file.name}"
        }
        selected = emptySet()
        // The view must not be left pointing inside a folder that is gone.
        if (gone.isNotEmpty()) {
            val stillThere = currentDir?.exists() == true
            if (!stillThere) path = path.dropLast(1)
        }
        refresh()
        if (gone.isEmpty()) return false
        val what = if (gone.size == 1) {
            "Deleted ${gone.first().name}"
        } else {
            "Deleted ${gone.size} items"
        }
        record(what)
        gone.mapNotNull { file ->
            val project = ws.projectOf(file) ?: return@mapNotNull null
            // Only a worktree has a repository: a local file beside one is versioned
            // by the account's history, which `record` has already dealt with.
            if (ws.inWorktree(file)) project else null
        }
            .distinct()
            .forEach { commitProject(it, what) }
        historyGeneration++
        message = what
        return true
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
        // The change has a name, and the name is what a person needs to see. It was
        // being passed in and only ever used for the commit message, so a file could
        // arrive as `my_shopping_list.txt` when `My Shopping List` was typed and
        // nothing at all would say so — which reads as the file having gone missing.
        message = what
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
        if (!ws.inWorktree(file)) return
        commitProject(ws.projectOf(file) ?: return, what)
    }

    /**
     * Commits [project]'s worktree, whatever changed in it.
     *
     * Used for a change that spans two repositories — a move across the boundary —
     * where the file that changed is no longer in one of them.
     */
    private fun commitProject(project: String, what: String) {
        val g = git ?: return
        val login = activeLogin
        onWorker {
            if (login != null && accounts.exists(login)) g.commit(project, what)
            // The row's count was read *before* this commit, so it still says the
            // change is waiting after it has been recorded. Ask again, now that the
            // answer has changed.
            main.post { refreshChanges() }
        }
    }

    /**
     * The note to add to a message when the app used a different name.
     *
     * A silent rename is what makes a person think the app lost their file: they
     * typed "My Note", and there is now a `my_note.txt` they did not ask for.
     * Naming both is one short clause and answers it.
     */
    private fun namedFrom(asked: String, used: String): String =
        if (asked.trim() == used) "" else " (was \"${asked.trim()}\")"

    /** Commits a change into the account's own history, behind the UI. */
    private fun record(what: String) {
        val login = activeLogin ?: return
        val h = history ?: return
        val keep = settings.historyLength
        // The account can be removed while this is still on its way to the worker.
        // Writing then would make its folder again, and it would come back.
        onWorker {
            if (accounts.exists(login)) h.commit(what, keep)
            main.post { refreshChanges() }
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

    /**
     * How many editing sessions the editor has had.
     *
     * The screen resets its text and its name when this changes, and not when the
     * file's path does — because renaming the file you are writing renames its path,
     * and resetting on that would throw away everything typed so far.
     */
    var editorSession: Int by mutableStateOf(0)
        private set

    /** Opens a file in the editor. */
    fun openEditor(file: File) {
        editingPath = workspace?.relative(file)
        editorSession++
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
        // A locked file can be read and nothing else. The screen greys Save out, and
        // that is not enough for the same reason: a lock that only the screen honours
        // is a lock the next screen will not.
        // A locked file can be read and nothing else. Refused without a word: the
        // row and the editor both show a lock, and a message per attempt would be
        // the app saying the same thing over the screen that already says it.
        if (isLocked(file)) return
        val keep = settings.historyLength
        val h = history
        val g = git
        val name = file.name
        // A file inside a project's *repository* is versioned by that project, and
        // everything else — including a note kept in the project's folder beside it
        // — by the account. Every save is its own version; sync is then only the push.
        val project = if (ws.inWorktree(file)) ws.projectOf(file) else null
        val login = activeLogin
        // **The write is part of the commit, not something done before it.** Both
        // happen on the app's one worker, in the order the saves were made, so each
        // save commits the text it was given. Writing on the calling thread and
        // committing later meant two saves in a row left the file holding the second
        // text before the first commit ran: the first commit took the newer text and
        // the second found nothing to commit, so the intermediate version vanished
        // and the history could not be walked back through it.
        onWorker {
            ws.write(file, text)
            if (saveDebounceMs > 0) Thread.sleep(saveDebounceMs)
            // **The text is on disk and its version may not be,** and those are two
            // different promises. A commit that could not be written used to look
            // exactly like a commit with nothing to do: no id, no message, the editor
            // greying Save out, and a history with nothing new in it. The person's work
            // was safe in the file and invisible in the history, which is the failure
            // this app's design is about.
            var refused: String? = null
            if (login != null && accounts.exists(login)) {
                val outcome = if (project != null) {
                    g?.commitOutcome(project, "Edited $name")
                } else {
                    h?.commitOutcome("Edited $name", keep)
                }
                if (outcome != null && !outcome.ok) refused = outcome.error
            }
            main.post {
                refresh()
                refreshChanges()
                if (refused != null) {
                    message = "Saved to the file, but not to its history: $refused"
                }
            }
        }
    }

    // ---- the project actions ------------------------------------------------

    /**
     * Syncs a project: commit what changed, then force push.
     *
     * [note] is the message the person typed in the window the Sync button opens.
     * It is written as the sync's **own** commit — a commit whose tree is what was
     * already committed, whose message is theirs. Without it the only messages in a
     * project's history would be the app's own ("Edited milk.txt"), and the answer to
     * "why was this pushed" would be a list of file names. A blank note means "just
     * push", which is what a project with nothing waiting gets.
     */
    @JvmOverloads
    fun sync(project: String, note: String? = null) {
        val g = git ?: return
        busy = true
        val said = note?.trim().orEmpty()
        onWorker {
            val result = g.sync(project, said)
            main.post {
                busy = false
                // **A failed sync says what failed, and records nothing.** The
                // history claiming "Synced" over a push GitHub refused is how a
                // project came to look synced while the phone and GitHub were
                // different.
                message = result.message
                refreshChanges()
                if (result.success) {
                    record(if (said.isEmpty()) "Synced $project" else "Synced $project: $said")
                } else {
                    lastSyncError = result.message
                }
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
        onWorker {
            val result = g.replaceWithRemote(project)
            main.post {
                busy = false
                message = result.message
                refresh()
                // The row's count comes from the working tree, which has just been
                // replaced wholesale; reading it once, afterwards, is the only way
                // the number can be right.
                refreshChanges()
            }
        }
    }

    /** What went wrong the last time a sync was asked for, for a screen that asks. */
    var lastSyncError: String? by mutableStateOf(null)
        private set

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
        // Deliberately not put through `Workspace.cleanName`: this name becomes a
        // repository on GitHub, and a project that was pulled in already carries the
        // repository's own name. A name GitHub refuses is reported by GitHub, which
        // is the only place that knows what it will accept.
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
        onWorker {
            val created = api.createRepo(token, clean, licence)
            if (created.error != null) {
                main.post {
                    busy = false
                    message = created.error
                    onDone(false)
                }
                return@onWorker
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
                return@onWorker
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
        onWorker {
            var note = ""
            if (api != null && login != null) {
                val renamed = api.renameRepo(token, login, project, clean)
                if (renamed.error != null) note = renamed.error
            }
            // Not tidied: this folder stands for a repository, whose name is its own.
            val moved = ws.rename(File(ws.projectsDir, project), clean, tidy = false)
            if (moved.isFailure) {
                main.post {
                    busy = false
                    message = moved.exceptionOrNull()?.message ?: "Could not rename the folder"
                }
                return@onWorker
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
        onWorker {
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
        onWorker {
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
        onWorker {
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
        onWorker {
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
        onWorker {
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
        onWorker {
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
        // The trace follows the account: what is written from here on belongs to the
        // account being looked at, and a person switching accounts should not find the
        // other one's history in their trace.
        attachTrace()
        Trace.note("switched account", login)
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
     * Asks for the versions, on the worker, and hands them to [onDone] on the UI.
     *
     * Reading a history opens a repository and walks it, which is not something to
     * do inside a frame. More importantly it is not something to do **before** the
     * save that was just made has been committed: both run on the one worker, in the
     * order they were asked for, so asking here guarantees the answer includes the
     * commit the save queued a moment ago. Reading synchronously on the UI thread
     * raced the commit and showed the version before the one that was just saved.
     */
    fun requestVersions(onDone: (List<Pair<GitService.CommitInfo, String?>>) -> Unit) {
        onWorker {
            val found = versions()
            main.post { onDone(found) }
        }
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

    /**
     * The files that used to be in [dir] and are not there now, newest first.
     *
     * Deleting a file is the one change with no way back from the folder it was in:
     * the row is gone, so there is nothing to open a history on, and the version
     * that holds it is a version of a folder nobody is looking at. Hence this list,
     * which the add button offers beside the other ways of getting a file in.
     *
     * It reads whichever repository owns the folder — the project's, or the
     * account's — because that is where the file was committed.
     */
    fun deletedIn(dir: File): List<Gone> {
        val ws = workspace ?: return emptyList()
        val path = ws.relative(dir) ?: return emptyList()
        val inside = ws.inWorktree(dir)
        val project = if (inside) ws.projectOf(dir) else null
        if (inside && project == null) return emptyList()
        val repo = git
        val hist = history
        // The path as the repository that owns the folder names it: from the
        // worktree root for a project, from the account root otherwise.
        val base = (if (inside) path.drop(3) else path).joinToString("/")

        val onDisk = mutableSetOf<String>()
        fun collect(here: File, prefix: String) {
            for (child in here.listFiles() ?: emptyArray()) {
                if (ws.isHidden(child.name)) continue
                val name = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                // A folder that is still there is not a folder that was deleted, so
                // its name counts as present exactly as a file's does. Collecting
                // only files made every existing folder look gone.
                onDisk += name
                if (child.isDirectory) collect(child, name)
            }
        }
        collect(dir, "")

        // Every recent commit, not only the ones a path filter says touched this
        // folder. The filter is asked about a *directory*, and a commit that removed
        // a file from it does not always answer as a change to the directory — which
        // is how this list came to hold one name when five files had been deleted.
        // The tree at each commit is read anyway, so the filter only ever narrowed
        // the question and never widened the answer.
        val commits = if (inside) {
            repo?.log(project!!, null, DELETED_LOOKBACK).orEmpty()
        } else {
            hist?.log(null, DELETED_LOOKBACK).orEmpty()
        }
        // Newest first, so the first commit that holds a name is the version to
        // bring back: the file as it last was, not as it was years ago.
        val found = LinkedHashMap<String, Gone>()
        for (commit in commits) {
            val held = if (inside) {
                repo?.paths(project!!, base.ifEmpty { null }, commit.id).orEmpty()
            } else {
                hist?.pathsIn(base.ifEmpty { null }, commit.id).orEmpty()
            }
            for (full in held) {
                val rest = if (base.isEmpty()) full else full.removePrefix("$base/")
                if (rest.isEmpty()) continue
                // **Direct children only.** Restoring is bringing back a row that
                // used to be in *this* folder; something from a folder inside it was
                // never a row here. A folder is one row: `letters` rather than each
                // of the files it held, because a person who deleted a folder did one
                // thing.
                val name = rest.substringBefore('/')
                val isFolder = rest != name
                if (name in onDisk) continue
                // The app's own markers are in the history and are not a person's
                // files: `.gitkeep` exists so that an empty folder has a version, and
                // offering it back as a deleted file would be the app talking to
                // itself.
                if (ws.isHidden(name)) continue
                val already = found[name]
                // A file and a folder can share a name across versions; what is on
                // disk decides which one is missing, and the newest version that has
                // it decides what to bring back.
                if (already == null) {
                    found[name] = Gone(name, commit.id, isFolder)
                } else if (!isFolder && already.isFolder) {
                    found[name] = Gone(name, commit.id, false)
                }
            }
        }
        return found.values.sortedBy { it.name.lowercase() }
    }

    /**
     * Brings a deleted file — or a whole folder — back, byte for byte, from
     * [Gone.revision].
     *
     * **Byte for byte, not as text.** A picture put back from a text reading is not
     * the picture, so the bytes come out of the object database exactly as they went
     * in. A folder is put back with every file it held at that version.
     *
     * **It says what happened either way.** This used to answer false without a
     * word, and the dialog ignored the answer: picking a name and watching nothing
     * happen is indistinguishable from a broken screen.
     */
    fun restoreDeleted(dir: File, gone: Gone): Boolean {
        val ws = workspace ?: return false
        val path = ws.relative(dir) ?: run {
            message = "${dir.name} is not part of this account any more"
            return false
        }
        val inside = ws.inWorktree(dir)
        val project = if (inside) ws.projectOf(dir) else null
        if (inside && project == null) {
            message = "${dir.name} has no repository, so there is nothing to bring back from"
            return false
        }
        val repoBase = if (inside) path.drop(3) else path
        val under = repoBase + gone.name.split("/")
        fun bytesAt(name: String): ByteArray? = if (inside) {
            git?.bytesAt(project!!, name, gone.revision)
        } else {
            history?.bytesAt(name, gone.revision)
        }

        val written = mutableListOf<File>()
        if (gone.isFolder) {
            // Every file the folder held at that version. An empty folder was never
            // in git, so it cannot be brought back — and an empty folder is not a
            // thing anyone lost.
            val prefix = under.joinToString("/")
            val held = if (inside) {
                git?.paths(project!!, prefix, gone.revision).orEmpty()
            } else {
                history?.pathsIn(prefix, gone.revision).orEmpty()
            }
            for (name in held) {
                val bytes = bytesAt(name) ?: continue
                val target = ws.fileAt(path + gone.name.split("/") +
                    name.removePrefix("$prefix/").split("/"))
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
                written += target
            }
        } else {
            val bytes = bytesAt(under.joinToString("/"))
            if (bytes == null) {
                message = "${gone.name} is not in that version any more"
                return false
            }
            val target = ws.fileAt(path + gone.name.split("/"))
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            written += target
        }
        if (written.isEmpty()) {
            message = "Nothing of ${gone.name} was left in that version"
            return false
        }
        val what = if (gone.isFolder) {
            "Brought ${gone.name}/ back"
        } else {
            "Brought ${gone.name} back"
        }
        message = what
        refresh()
        record(what)
        // Each side of the boundary is told, exactly as for a move: the repository
        // that now holds these files is the one that has to record them.
        written.mapNotNull { file ->
            val owning = ws.projectOf(file) ?: return@mapNotNull null
            if (ws.inWorktree(file)) owning else null
        }.distinct().forEach { commitProject(it, what) }
        historyGeneration++
        return true
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
            //
            // Which history is told depends on where the file is, exactly as it does
            // for an edit. A restore inside a project's repository that only
            // recorded the account's history would leave the project's worktree
            // modified and uncommitted — the file would say "1 changed" until the
            // next sync, and the version it was put back to would not be in the
            // history it came from.
            record("Restored an older version")
            if (project != null && insideWorktree) commitProject(project, "Restored an older version")
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

        /** How many commits back the "what used to be here" list looks. */
        private const val DELETED_LOOKBACK = 50
        private const val SEARCH_RESULTS = 200
    }
}
