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
 * **One history per project**, in the project's own folder. It runs behind the UI on a single
 * worker, so a save never waits for a commit and a sync never freezes a list.
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

    /**
     * Where the accounts live, so the runner can be built over the same root they were made in.
     *
     * Taken once at construction: the constructor's `rootDir` is shadowed by the property, and a
     * method reading `rootDir` is reading the accounts folder rather than its parent.
     */
    private val accountsRoot: File = rootDir

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

    /**
     * The login of the active account.
     *
     * **Never null, because the provisional account always exists.** Before anybody signs in this
     * is `~local`, whose folder is a real workspace - so a local project, the system project and the
     * request history all work with no account, and every caller that asks "is there a workspace"
     * answers yes. When the last account is removed it becomes `~local` again rather than nothing,
     * which is why the screen can never be left pointing at an account that is gone.
     */
    var activeLogin: String? by mutableStateOf(credentials.activeLogin ?: accounts.ensureProvisional().login)
        private set

    /** The path from the account's root, as names. Empty means the root. */
    var path: List<String> by mutableStateOf(emptyList())
        private set

    /** What is in the folder at [path]. */
    var entries: List<Entry> by mutableStateOf(emptyList())
        private set

    /**
     * The names of the projects on this phone, as **observable state**.
     *
     * A plain `get()` over the filesystem was invisible to Compose: `refresh()` read the
     * names, so the trace correctly reported "2 rows" — and nothing the screen observed had
     * changed, so nothing redrew. Adding or deleting a project then showed up only after
     * leaving the Projects screen and coming back, because navigation forces the
     * recomposition the change itself could not. Reported from the live log: "adding and
     * deleting a project shows the change only after you exit and enter the project menu
     * again".
     *
     * The list is replaced, never mutated: `mutableStateOf` compares by identity, so a
     * `MutableList` edited in place is also a change Compose cannot see.
     *
     * **Declared here, with the other state, and not further down.** It is written by
     * `refresh()`, and this class has an `init` block that calls `refresh()` for an account
     * that is already signed in — so a declaration below that block is a null delegate at
     * the moment it is first written, which crashes on the very path a person takes when
     * they open the app they already set up.
     */
    var projectNames: List<String> by mutableStateOf(emptyList())
        private set

    /** The paths that are pinned, as paths from the account root. */
    var pins: Set<String> by mutableStateOf(emptySet())
        private set

    /** The paths that are locked against editing, as paths from the account root. */
    var lockedPaths: Set<String> by mutableStateOf(emptySet())
        private set

    /**
     * The paths kept off GitHub, as paths from the account root.
     *
     * Held in state for the same reason [lockedPaths] is: the row's icon has to redraw when it
     * changes, and asking `.muffy/unsync.json` on every call gave Compose no reason to.
     */
    var unsyncedPaths: Set<String> by mutableStateOf(emptySet())
        private set

    /**
     * The agent sessions this phone is running, read from `.muffy/agents.json`.
     *
     * **Declared here, with the other state, and not with the methods below.** `refresh()` writes
     * it and an `init` block calls `refresh()` for an account that is already signed in, so a
     * declaration further down is a null delegate at the first write — the crash `projectNames`
     * already cost once.
     */
    var agentSessions: List<AgentSession> by mutableStateOf(emptyList())
        private set

    /** The paths that are selected, for a move or a delete of several at once. */
    var selected: Set<String> by mutableStateOf(emptySet())
        private set

    /**
     * The agent's model choice, read from `model_conf.json` in the account's folder.
     *
     * **The file is the only store.** The settings rows and the picker are editors of it, so
     * there is no second copy of the same three values to disagree with the first.
     */
    var modelConfig: ModelConfig by mutableStateOf(ModelConfigFile.default())
        private set

    /**
     * Why the config could not be read, or null when it did.
     *
     * Kept beside the config rather than thrown away, because the two together are the honest
     * state: "this is the choice in force, and this is why it is not the one in your file".
     * A config that will not parse is never replaced by the defaults on disk.
     */
    var modelConfigProblem: String? by mutableStateOf(null)
        private set

    /**
     * Where the picker's numbers come from.
     *
     * Swappable so a test can answer without a socket: the whole agent is built on the rule that
     * nothing in the suite opens a connection, and a picker that could only be tested against
     * OpenRouter would be a picker tested by hand or not at all.
     */
    var catalogue: Catalogue = OpenRouterCatalogue(
        { activeLogin?.let { credentials.modelKey(it) } }
    )

    /**
     * What OpenRouter says about the model in use, read when a picker opens.
     *
     * **State rather than a call from the screen**, for two reasons. A sheet that fetches while
     * it draws freezes exactly when the network is slow, which is when somebody opened it; and
     * the *choices* do not need the network at all — the provider tags are in the config file —
     * so only the numbers are allowed to be late. Before they arrive the rows are still there.
     */
    var modelInfo: ModelInfo? by mutableStateOf(null)
        private set

    var modelEndpoints: List<EndpointInfo> by mutableStateOf(emptyList())
        private set

    var catalogueProblem: String? by mutableStateOf(null)
        private set

    var catalogueReading: Boolean by mutableStateOf(false)
        private set

    /** The model the numbers above belong to, so a picker does not re-read on every frame. */
    private var catalogueFor: String? = null

    /** Reads the numbers for [model], once per model unless [force]. */
    fun readCatalogue(model: String, force: Boolean = false) {
        if (!force && model == catalogueFor && (modelInfo != null || catalogueProblem != null)) return
        catalogueFor = model
        catalogueReading = true
        catalogueProblem = null
        val source = catalogue
        onWorker {
            val info = source.model(model)
            val endpoints = source.endpoints(model)
            main.post {
                info.fold(
                    onSuccess = { modelInfo = it },
                    onFailure = { catalogueProblem = it.message },
                )
                endpoints.fold(
                    onSuccess = { modelEndpoints = it },
                    onFailure = { if (catalogueProblem == null) catalogueProblem = it.message },
                )
                catalogueReading = false
            }
        }
    }

    /** The add form's own read: a model that is not in use yet. */
    var previewInfo: ModelInfo? by mutableStateOf(null)
        private set

    var previewEndpoints: List<EndpointInfo> by mutableStateOf(emptyList())
        private set

    var previewProblem: String? by mutableStateOf(null)
        private set

    var previewReading: Boolean by mutableStateOf(false)
        private set

    /**
     * Asks OpenRouter about [id] before anything is written.
     *
     * A form that only wrote a string into the file would let a typo through and fail eight
     * seconds into the agent's first turn with a 404; this turns that into a sentence at the
     * moment the person is looking.
     */
    fun readPreview(id: String) {
        val clean = id.trim()
        previewInfo = null
        previewEndpoints = emptyList()
        previewProblem = null
        previewReading = true
        val source = catalogue
        onWorker {
            val info = source.model(clean)
            val endpoints = if (info.isSuccess) source.endpoints(clean) else
                Result.failure(IllegalStateException("not asked"))
            main.post {
                info.fold(
                    onSuccess = { previewInfo = it },
                    onFailure = { previewProblem = it.message },
                )
                if (info.isSuccess) {
                    endpoints.fold(
                        onSuccess = { previewEndpoints = it },
                        onFailure = { previewProblem = it.message },
                    )
                }
                previewReading = false
            }
        }
    }

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

    /**
     * The window that lists what is waiting to be sent, or null when it is closed.
     *
     * **It survives the editor on purpose.** A person reading the list touches a file, reads it,
     * changes it maybe, and comes back — and a list that had closed itself would make them find the
     * button again. Nothing here is a snapshot of the disk either: it is rebuilt from the repository
     * every time it is opened, so the numbers in it are the ones the row has.
     */
    var changes: List<ChangedFile>? by mutableStateOf(null)
        private set

    /** One file in that window: its path, and how it differs from the last push. */
    data class ChangedFile(
        val path: String,
        /** Null when there is no preview, and [said] then says why. */
        val lines: List<GitService.DiffLine>?,
        val said: String?,
    )

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

    /**
     * How many version lists have finished being read, since the app opened.
     *
     * **The screen's loading flag is local composable state, which nothing outside the composition
     * can see** - and that is why the report's picture of scenario 09 was a picture of "Reading the
     * history…". A test could not ask whether the read had finished, so it had to guess at a pause,
     * and its guess was short.
     *
     * This is the same fact, kept where the app can answer it: the number of reads that have
     * landed. The screen still owns its own spinner - a fact about one composition should stay in
     * it - and this is only a counter of completions, which is exactly what "has the screen got
     * its answer yet" means from outside.
     */
    var historyLoaded: Int by mutableStateOf(0)
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

    /** True while the workspace is the provisional one - the state before any sign-in. */
    val isProvisionalAccount: Boolean get() = accounts.isProvisional(activeLogin)

    /**
     * The GitHub the active account talks to.
     *
     * A signed-in account talks to GitHub. The test account of a debug build talks
     * to a folder on this phone that behaves like one, so every feature that needs
     * an account can be walked through without a key ever existing.
     */
    fun githubFor(login: String?): GitHub? {
        // **The provisional account has no GitHub.** Its folder is a real workspace, but there is no
        // account behind it, so answering with an `HttpGitHub` would give every caller something
        // that looks usable and sends unauthenticated requests to the internet. `null` is the honest
        // answer, and it is the one the screens already handle.
        if (accounts.isProvisional(login)) return null
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

    /**
     * True when there is a GitHub account behind the workspace.
     *
     * **Not "an account exists"**, because the provisional account is one and is not signed in to
     * anything. Anything that needs a real account - pushing, pulling, listing repositories - asks
     * this, and the provisional workspace answers no while still being a workspace.
     */
    val signedIn: Boolean get() = account != null && !isProvisionalAccount

    /** The active account's tree, or null when nobody is signed in. */
    val workspace: Workspace? get() = activeLogin?.let { Workspace(accounts.dirOf(it)) }

    /**
     * Who commits, when nobody has signed in.
     *
     * **Not a `@users.noreply.github.com` address.** The provisional account's login is `~local`,
     * and the derived address would be `~local@users.noreply.github.com` - a shape that claims to
     * belong to a GitHub account that cannot exist. A commit made on this phone before signing in
     * should say so, and it keeps saying so after the projects are adopted, because that is when it
     * was made.
     */
    private val author: GitAuthor
        get() = if (accounts.isProvisional(activeLogin)) PROVISIONAL_AUTHOR
        else account?.author() ?: DEFAULT_AUTHOR

    private val git: ProjectGit?
        get() = workspace?.let { ws ->
            ProjectGit(
                workspace = ws,
                tokenProvider = { activeLogin?.let { credentials.token(it) } },
                authorProvider = { author },
                keepProvider = { settings.historyLength },
            )
        }


    init {
        // The trace's durable copy lives beside everything else the app owns, in the
        // account's `.muffy/` — outside both histories, so a run can never appear as a
        // version, and in one folder with the settings.
        attachTrace()
        // **Every line carries the build, so the line about the build is the one that sets it.**
        // The file holds several builds at once — nine of them at the time this was written — and a
        // report from one is not a report about another. `buildName` is the short form: the version
        // name the phone installed, which is what a person says when they say "on 149".
        Trace.build = buildName
        Trace.note("app-opened", versionLine)
        refresh()
        // **The introduction, once.** The report that asked for it: *"remove this sign in to begin
        // text box. Instead I want on first open to move operator to readme.md file from system
        // project in view mode so that the operator can read how to use the app and what is possible
        // here"*.
        //
        // **The introduction is opened by `openFirstRunReadme` and not here.** A property declared
        // below this block is not initialised yet when `init` runs, and `editorSession` is one of
        // them - writing to it from here is a compile error, which is the compiler saying the same
        // thing. The call is posted instead, so it happens once everything above and below has been
        // built.
        main.post { openFirstRunReadme() }
    }

    /**
     * Opens the system project's README, **once for the life of the install**.
     *
     * The report that asked for it: *"remove this sign in to begin text box. Instead I want on
     * first open to move operator to readme.md file from system project in view mode so that the
     * operator can read how to use the app and what is possible here"*.
     *
     * **VIEW mode, not the editor's default for a new file**, because the point is to read it. The
     * editor already opens in view; this only chooses the file.
     *
     * **`refresh()` has already run**, so the system folder and its README exist by the time this
     * is posted. If they somehow do not, nothing is marked as shown and the next launch tries
     * again - the failure is "the introduction is late", never "the introduction is skipped".
     */
    private fun openFirstRunReadme() {
        if (settings.readmeShown) return
        val ws = workspace ?: return
        val readme = File(ws.projectDir(Workspace.SYSTEM_PROJECT), Workspace.README)
        if (!readme.isFile) return
        settings.markReadmeShown()
        Trace.note("first-run-readme", Workspace.SYSTEM_PROJECT)
        editingPath = ws.relative(readme)
        editorSession++
    }

    /**
     * Turns the log's file on or off, now.
     *
     * A switch that only took effect at the next launch would have been a lie for a whole
     * session — and the moment somebody wants it is the moment something is going wrong,
     * which is not a good moment to be told to restart.
     */
    fun keepLog(on: Boolean) {
        settings.keepLiveTrace(on)
        attachTrace()
        Trace.note(
            "log-switched",
            if (on) "writing to " + (Trace.where ?: "nowhere") else "off",
        )
    }


    /**
     * Points the log at Downloads.
     *
     * **Once, and not per account.** The log is about this phone's copy of the app, not
     * about who is signed in: an account switch mid-session is exactly the kind of thing
     * worth having in one stream rather than in two files. A release build attaches
     * nothing at all, so nothing is ever written there.
     */
    private fun attachTrace() {
        if (!settings.liveTrace) {
            Trace.attach((null as Trace.Sink?))
            return
        }
        Trace.attach(DownloadsSink(getApplication()))
    }

    // ---- reading the tree ---------------------------------------------------

    /** The folder being shown. */
    val currentDir: File? get() = workspace?.fileAt(path)

    /** The project the current path is inside, or null. */
    val currentProject: String? get() = workspace?.projectAt(path)

    /**
     * True when the account's own screen is showing: **the list of projects**.
     *
     * There is no `projects/` folder to walk into any more — the account root *is* the list, which
     * is the whole of the consolidation that was asked for.
     */
    val atProjects: Boolean get() = path.isEmpty()

    /** True when the path is inside a project, which is to say inside a repository. */
    val inWorktree: Boolean get() = currentProject != null

    /** True when there is a level above. */
    val canGoUp: Boolean get() = path.isNotEmpty()

    /**
     * True when a file may be moved into [dir].
     *
     * Asked by the move window rather than restated there: a hidden folder and the record's own
     * `agent/` cannot hold a person's file, and everything else — the repository included, since a
     * project *is* the repository — can. A window that decided this for itself would be a second
     * copy of the rule, and the copy that is wrong is always the one nobody is looking at.
     */
    fun canHoldFiles(dir: File): Boolean {
        val ws = workspace ?: return false
        if (!dir.isDirectory) return false
        if (ws.isHidden(dir.name)) return false
        // **A folder the app keeps is not a place a person's document goes**, even now that it is
        // visible. Report 28 made `.muffy/` a row so it could be *read*; a move target is a different
        // question, and answering it with "is it hidden" stopped being right the moment `.muffy`
        // stopped being hidden. The command refuses it too — `coreProblem` names it — so this is the
        // picker agreeing with the command rather than being the only guard.
        if (Workspace.isSystemLocked(dir.name)) return false
        // The record's folder is the one the app owns inside a project; everything else — the
        // repository itself included, now that a project *is* the repository — is the person's.
        return !ws.isStructural(dir)
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
        projectNames = ws.projectNames()
        entries = currentDir?.let { ws.list(it) } ?: emptyList()
        pins = ws.pins()
        lockedPaths = ws.locked()
        unsyncedPaths = ws.unsynced()
        agentSessions = AgentSessions(ws.accountDir).read()
        // The first refresh of a new process is where a session that survived a reboot gets its
        // clock back: the answer is read here, but nothing else in a refresh would start it.
        applyService(agentSessions)
        readModelConfig(ws)
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

    // ---- the agent's model -------------------------------------------------

    /**
     * Reads `model_conf.json`, keeping the last good choice when the file will not parse.
     *
     * A damaged file leaves [modelConfig] as it was and puts the reason in
     * [modelConfigProblem]: the screen can then say which line is wrong while still being able
     * to show what the agent would use. Replacing it with the defaults would be the app deciding
     * for the person that their file did not matter.
     */
    private fun readModelConfig(ws: Workspace) {
        ModelConfigFile.load(ws.accountDir).fold(
            onSuccess = { loaded ->
                modelConfig = loaded
                modelConfigProblem = null
            },
            onFailure = { problem -> modelConfigProblem = problem.message },
        )
    }

    /** Writes the config and records it as a version, like any other change to the account. */
    private fun writeModelConfig(next: ModelConfig, what: String) {
        val ws = workspace ?: return
        ModelConfigFile.write(ws.accountDir, next)
        modelConfig = next
        modelConfigProblem = null
        Trace.changed("model ${next.summary()}")
        refresh()
    }

    /**
     * The three values the picker sets, kept together because they only make sense together.
     *
     * The effort is clamped to what the model declares: a model that offers `low` and `max`
     * cannot answer `high`, and sending a rung it never declared is a 400 from one provider and
     * a silently ignored parameter from another.
     */
    fun chooseModel(
        model: String,
        provider: String,
        effort: String,
        supported: List<String>,
        contextLength: Int? = null,
        maxOutput: Int? = null,
    ) {
        val clamped = ModelConfigFile.effortFor(supported, effort) ?: effort
        val before = modelConfig.active.effort
        // **The window travels with the choice.** Two readers build an agent — this screen's turn and
        // the service's poll — and neither can reach a catalogue, so the numbers the picker saw are
        // written beside the model they belong to.
        //
        // **A number is only carried over when the endpoint is the same one.** Both values are facts
        // about a *pin*: `deepinfra/fp8/flex` keeps 131,072 of the window for replies and
        // `cloudflare` keeps 943,718, so carrying the old reserve across a provider change would
        // leave the agent clamped to an endpoint it is no longer using — the 96k bug with the sign
        // flipped. Null here means "not told", and an unknown endpoint falls through
        // `ContextBudget.ceiling` to the model's own window, which is the honest answer.
        val same = model == modelConfig.active.model && provider == modelConfig.active.provider
        val active = ActiveModel(
            model = model,
            provider = provider,
            effort = clamped,
            contextLength = contextLength ?: if (same) modelConfig.active.contextLength else null,
            maxOutput = maxOutput ?: if (same) modelConfig.active.maxOutput else null,
        )
        writeModelConfig(
            modelConfig.copy(active = active),
            "Chose $model on $provider at $clamped",
        )
        // **A model picked by name has no window yet**, and the app cannot ask the endpoint for it
        // while a turn is running. So the catalogue is asked once, here, and the answer is written
        // into the choice — the same place the next poll reads it from.
        if (active.contextLength == null) learnWindow(model)
        if (clamped != before && supported.isNotEmpty()) {
            Trace.said("effort: $before -> $clamped, this model's way of saying it")
        }
    }

    /**
     * Asks the catalogue for one model's window, once, and writes it beside the choice.
     *
     * **A file write, not a field.** The poll that builds an agent runs in another process and
     * cannot see this view model, so a number held in memory would be a number the service never
     * has. Nothing is written if the catalogue answers nothing, and nothing is written over a window
     * that has arrived in the meantime.
     */
    private fun learnWindow(model: String) {
        val source = catalogue
        onWorker {
            val info = source.model(model).getOrNull() ?: return@onWorker
            main.post {
                val now = modelConfig.active
                // Nothing is written when the choice moved on, or when a window has arrived
                // already: the second answer to the same question is not news.
                if (now.model == model && now.contextLength == null) {
                    writeModelConfig(
                        modelConfig.copy(
                            active = now.copy(
                                contextLength = info.contextLength,
                                maxOutput = info.maxOutput,
                            ),
                        ),
                        "Read ${model}'s window",
                    )
                }
            }
        }
    }

    /** Adds a model and the endpoints it may be pinned to. */
    fun addModel(id: String, providers: List<String>): Result<Unit> {
        val clean = providers.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (id.isBlank()) return Result.failure(IllegalArgumentException("A model needs an id"))
        if (clean.isEmpty()) {
            return Result.failure(IllegalArgumentException("A model needs at least one provider"))
        }
        if (modelConfig.models.any { it.id == id }) {
            return Result.failure(IllegalArgumentException("$id is already in the list"))
        }
        writeModelConfig(
            modelConfig.copy(models = modelConfig.models + ModelChoice(id, clean)),
            "Added $id",
        )
        return Result.success(Unit)
    }

    /**
     * Removes a model, refusing the one in use.
     *
     * Refused with a sentence rather than by removing it and then having no model: the picker
     * asks a person to choose another first, which is a thing they can do, where "the agent has
     * no model" is not.
     */
    fun removeModel(id: String): Result<Unit> {
        if (id == modelConfig.active.model) {
            return Result.failure(
                IllegalArgumentException("That is the model in use, so choose another first")
            )
        }
        val left = modelConfig.models.filterNot { it.id == id }
        if (left.size == modelConfig.models.size) {
            return Result.failure(IllegalArgumentException("$id is not in the list"))
        }
        writeModelConfig(modelConfig.copy(models = left), "Removed $id")
        return Result.success(Unit)
    }

    /**
     * Adds a provider to a model's list.
     *
     * **The providers of a model are a list the person chooses**, not a fact the app derives: one
     * model is served by many endpoints, they differ in price and in window, and pinning to one is
     * how a person says which they want. So adding one is a choice from the endpoints OpenRouter
     * publishes for that model - never a typed tag. Report: "you are prompted to give model tag
     * instead of opening a list of available providers for current model".
     */
    fun addProvider(model: String, tag: String): Result<Unit> {
        val clean = tag.trim()
        if (clean.isEmpty()) return Result.failure(IllegalArgumentException("A provider needs a tag"))
        val choice = modelConfig.models.firstOrNull { it.id == model }
            ?: return Result.failure(IllegalArgumentException("$model is not in the list"))
        if (choice.providers.any { it == clean }) {
            return Result.failure(IllegalArgumentException("$clean is already allowed for $model"))
        }
        Trace.tap("add provider", clean)
        val next = modelConfig.models.map {
            if (it.id == model) it.copy(providers = it.providers + clean) else it
        }
        writeModelConfig(modelConfig.copy(models = next), "Allowed $clean for $model")
        return Result.success(Unit)
    }

    /**
     * Moves one endpoint up or down in a model's list, **which is the fallback order.**
     *
     * The operator asked for this directly: "I want ability to rearrange provider endpoints on the
     * provider selection card (by holding and then moving them), so that their order is the fallback
     * order."
     *
     * **[from] and [to] are positions in the model's own list**, not names, because the act is a
     * move rather than an edit: the caller knows where the row was dragged from and where it landed.
     * A move that would leave the list unchanged is a no-op rather than an error - a drag that ends
     * where it started is something a finger does constantly.
     */
    fun moveProvider(model: String, from: Int, to: Int) {
        val choice = modelConfig.models.firstOrNull { it.id == model } ?: return
        val list = choice.providers
        if (from !in list.indices || to !in list.indices || from == to) return
        val moved = list.toMutableList().apply { add(to, removeAt(from)) }
        Trace.tap("reorder provider", "${list[from]} to ${to + 1}")
        val next = modelConfig.models.map {
            if (it.id == model) it.copy(providers = moved) else it
        }
        writeModelConfig(
            modelConfig.copy(models = next),
            "Moved ${list[from]} to position ${to + 1} for $model",
        )
    }

    /**
     * Takes a provider out of a model's list.
     *
     * **The one in use is refused**, because removing it would leave the agent pinned to an endpoint
     * its own config no longer lists - a state the picker could not show and the next request would
     * fail on. Choosing another provider first is one tap; discovering the breakage later is not.
     */
    fun removeProvider(model: String, tag: String): Result<Unit> {
        if (model == modelConfig.active.model && tag == modelConfig.active.provider) {
            return Result.failure(
                IllegalArgumentException("That is the provider in use, so choose another first")
            )
        }
        val choice = modelConfig.models.firstOrNull { it.id == model }
            ?: return Result.failure(IllegalArgumentException("$model is not in the list"))
        if (choice.providers.none { it == tag }) {
            return Result.failure(IllegalArgumentException("$tag is not allowed for $model"))
        }
        if (choice.providers.size == 1) {
            // A model with no provider is a model that cannot be asked anything, so the last one is
            // kept rather than left behind as an entry that looks usable and is not.
            return Result.failure(
                IllegalArgumentException("$model would have no provider left; remove the model instead")
            )
        }
        Trace.tap("remove provider", tag)
        val next = modelConfig.models.map {
            if (it.id == model) it.copy(providers = it.providers - tag) else it
        }
        writeModelConfig(modelConfig.copy(models = next), "Stopped allowing $tag for $model")
        return Result.success(Unit)
    }

    /**
     * Puts back the config the app shipped.
     *
     * From the build's own copy, not from the account's history: the two are different acts. A
     * history step would put back whatever was there a version ago, which may be the broken
     * edit; this is "the one the app ships", which is what a person asking to reset means.
     */
    fun resetModelConfig() {
        val fresh = ModelConfigFile.default()
        Trace.tap("reset model config")
        writeModelConfig(fresh, "Reset the model config")
        Trace.said("Back to the model the app ships")
    }

    /**
     * What the phone can say about the active account's OpenRouter key.
     *
     * `LOCKED` is not "no key": the bytes are there and the store's own key is only usable while the
     * phone is unlocked. Saying "no key yet" for that would send a person to type a key they already
     * have, on the one screen they cannot use while locked.
     */
    val modelKeyState: KeyState
        get() = activeLogin?.let { credentials.modelKeyState(it) } ?: KeyState.NONE

    /** The OpenRouter key of the active account, when there is one that can be read now. */
    val hasModelKey: Boolean
        get() = modelKeyState == KeyState.READABLE

    /**
     * Keeps the OpenRouter key of the active account — **and reads it back before saying so**.
     *
     * "Kept" is a claim about this phone, and the only way to know it is true is to open the door it
     * was put behind. The old version said it on the strength of the write alone, so a keystore that
     * refused the key (a locked phone is enough) was reported as success, and the person was left
     * with a key that saved twice and never worked — reported, correctly, as *the key isn't passing
     * through*.
     *
     * @return null when the key is kept, or the sentence to show when it is not.
     */
    fun saveModelKey(key: String): String? {
        val login = activeLogin
            ?: return "There is no account signed in on this phone, so there is nobody to keep a key for."
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return "The key is empty, so nothing was kept."
        credentials.setModelKey(login, trimmed)
        modelKeySaved++
        return when (credentials.modelKeyState(login)) {
            KeyState.READABLE -> {
                Trace.note("key-kept", "the OpenRouter key reads back")
                Trace.said("The OpenRouter key is kept on this phone")
                null
            }

            KeyState.LOCKED -> {
                // Stored, and it will work the moment the phone is unlocked. Saying "kept" without
                // saying this is what made a working key look like a broken one.
                Trace.note("key-kept", "stored, and this phone has to be unlocked to read it")
                Trace.said("The key is kept. This phone has to be unlocked before the agent can read it.")
                null
            }

            // The same refusal as NONE, because the write did not produce a readable key either
            // way - but reached through the unrecognised branch, which keeps the bytes.
            KeyState.UNREADABLE, KeyState.NONE -> {
                Trace.note("key-not-kept", "saved, and it could not be read back")
                Trace.said("The key was stored and could not be read back, so it will not work.")
                "The key was stored, and this phone could not read it back, so nothing here can " +
                    "use it. The bytes have been left in place: try Keep it again. If it keeps " +
                    "happening, the key belongs to a different account than the one signed in."
            }
        }
    }

    /** Bumped when a key is saved, so a screen showing "no key" can notice. */
    var modelKeySaved: Int by mutableStateOf(0)
        private set

    /**
     * The build, in one line, for a report to carry.
     *
     * `versionName` and `versionCode` are what the phone installed; the device line is
     * what makes "it crashes on mine" reproducible by somebody who is not holding it.
     * Nothing here identifies the person or the account.
     */
    /**
     * The version name alone — `149`, `1.4` — for the trace's build column.
     *
     * **A dev build says so.** It carries the live trace, it can make a test account, and it is the
     * build a bug is found on; a log whose build column read `149` for both the shipped app and the
     * dev copy would make "which build produced this report" unanswerable at exactly the moment
     * somebody is trying to answer it. `149-dev` is one token, so the column's shape is unchanged.
     */
    val buildName: String
        get() = try {
            val name = getApplication<android.app.Application>().packageManager
                .getPackageInfo(getApplication<android.app.Application>().packageName, 0)
                .versionName.orEmpty()
            if (BuildConfig.DEBUG) "$name-dev" else name
        } catch (t: Throwable) {
            if (BuildConfig.DEBUG) "-dev" else "-"
        }

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

    /**
     * True when [project] has nowhere to push.
     *
     * Asked by the row and by the sync button. A local project's row says `local`, not `up to date`,
     * and pressing Sync on one says why it cannot — a button that refuses without saying why is the
     * failure this app keeps fixing.
     */
    fun isLocalProject(project: String): Boolean = git?.isLocal(project) ?: true

    // ---- moving around ------------------------------------------------------

    /** Opens a folder by path from the account root. */
    fun open(target: List<String>) {
        path = target
        query = ""
        // **Leaving a folder ends the choosing.** The operator's report: "on exiting the screen where
        // multiselection was active it should cancel and close - otherwise it shows on different
        // screens".
        //
        // A selection is a set of *paths*, and `refresh` prunes only the ones that no longer exist
        // anywhere - so a person who selected two files and then walked into another folder kept
        // both selected, invisible, and a bulk action would act on files they could not see. The
        // strip stayed at the top of a screen where nothing was ticked, which is the "shows on
        // different screens" half.
        //
        // **Cleared on every open, including a move between two folders that both hold some of the
        // selected files.** "The selection survives if it is still valid" sounds helpful and is the
        // rule that made this confusing: the same gesture means different things depending on what
        // happens to be on disk. Choosing is a mode for the folder you are in.
        selected = emptySet()
        refresh()
    }

    /**
     * Opens the child of the current folder with this name.
     *
     * The tap is recorded here rather than in the screen, because this is the whole of
     * what pressing a folder does — and a gesture recorded in two places gets two ids.
     */
    fun openChild(name: String) {
        Trace.tap("open folder", name)
        open(path + name)
    }

    /**
     * Goes up one level. Returns false when already at the root.
     *
     * **Through [open], so going up ends a choosing the same way going down does.** This set the
     * path itself, which meant the one navigation a person uses most was the one that kept a
     * selection alive - and the selection was then of files in a folder they had just left.
     */
    fun up(): Boolean {
        if (path.isEmpty()) return false
        open(path.dropLast(1))
        return true
    }

    /** The entries shown for a search, or the folder's own contents. */
    val visibleEntries: List<Entry>
        get() {
            val q = query.trim()
            if (q.isEmpty()) return entries
            val project = currentProject ?: return emptyList()
            val ws = workspace ?: return emptyList()
            val root = ws.projectNames().firstOrNull { it == project }
                ?.let { ws.projectDir(it) } ?: return emptyList()
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
        // **A file cannot be put into a folder that refuses files.** `createFile` and
        // `createFolder` both ask `lockProblem(dir)`; importing did not, so the one way to add a
        // file to an app-locked folder was the phone's own picker - the door nobody thinks of as a
        // door. The operator's rule: adding to a locked branch is not allowed, and it has to hold
        // for every way of adding.
        workspace?.lockProblem(dir)?.let {
            message = it
            return false
        }
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
        // The same door as [importFiles], and a folder is the larger thing to add.
        workspace?.lockProblem(dir)?.let {
            message = it
            return false
        }
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
        // The destination gains the file, and the source loses it. One commit each
        // when they are different repositories; one in all when it stayed inside
        // the same one.
        if (was != null && was != now) commitProject(was, what)
        if (now != null) commitProject(now, what)
        historyGeneration++
        return true
    }

    /**
     * Copies [file] into [into]. The original does not move.
     *
     * **Only the destination is committed.** A copy changes one place: the folder that
     * gains a new file. The source is untouched, so there is nothing to record there —
     * unlike a move, which has to tell the repository it left as well as the one it
     * arrived in. Getting that wrong would put a "deleted" commit into a project whose
     * file is still sitting there.
     *
     * **The name never changes.** A taken name is refused by [Workspace.copy], so there is one
     * sentence here rather than two: the copy is `milk.txt` or nothing happened.
     */
    fun copy(file: File, into: File): Boolean {
        val ws = workspace ?: return false
        val now = ws.projectOf(into)?.takeIf { ws.inWorktree(into) }
        val result = ws.copy(file, into)
        if (result.isFailure) {
            message = result.exceptionOrNull()?.message ?: "That did not work"
            return false
        }
        refresh()
        val what = "Copied ${file.name} into ${into.name}/"
        if (now != null) commitProject(now, what)
        historyGeneration++
        message = what
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
        // Named by the direction it was pressed: a switch's trace says what the person
        // did, not what the app ended up believing.
        Trace.tap(if (key in lockedPaths) "unlock" else "lock", file.name)
        val next = if (key in lockedPaths) lockedPaths - key else lockedPaths + key
        ws.setLocked(next)
        lockedPaths = next
        // No message. The row's icon changes, which is the whole answer to "is it
        // locked?" — and a snackbar per tap said the same thing over the list.
    }

    /**
     * Adds [file] to its project's `.gitignore`, so it stops being synced.
     *
     * **The path written is the project-relative one**, because that is what git matches: a line
     * naming the account's folder would ignore nothing. A folder goes in with a trailing slash —
     * without it git still ignores the path, but a person reading the file cannot tell a folder
     * from a file, and the ignore file is a document they may read.
     *
     * The change is committed like any other, so "I told it to ignore this" is a version, and the
     * next sync carries it. Saying so is part of the act: an ignore line that is written and not
     * sent looks exactly like one that did not work.
     */
    fun ignoreInGit(file: File) {
        val ws = workspace ?: return
        val path = ws.relative(file) ?: return
        val project = ws.projectOf(file) ?: return
        val inside = path.drop(1).joinToString("/").let {
            if (file.isDirectory) "$it/" else it
        }
        if (inside.isEmpty()) return
        Trace.tap("add to gitignore", inside)
        val ignore = File(ws.projectDir(project), Workspace.IGNORE)
        val current = if (ignore.isFile) ws.read(ignore) else ""
        if (current.lineSequence().any { it.trim() == inside }) {
            message = "$inside is already ignored"
            return
        }
        val text = buildString {
            append(current)
            if (current.isNotEmpty() && !current.endsWith("\n")) append("\n")
            appendLine(inside)
        }
        // **Written through the funnel, so a locked project's `.gitignore` is still refused** —
        // and then said, rather than looking like a tap that did nothing.
        if (!ws.write(ignore, text)) {
            Trace.failure("add to gitignore", "refused")
            message = "Could not change .gitignore here"
            return
        }
        // **The line alone does not stop a file that is already in the repository.** `.gitignore`
        // is an *untracked* filter: git tested against itself keeps committing a path it already
        // knows, and every file a person has saved is already tracked because a save commits. So
        // the ignore is completed by taking the path out of the index — the file stays on the
        // phone, editable, and stops being sent. Without this the button wrote a line, committed
        // it, and said "Ignoring x" while x went on being pushed.
        // **Asked before the untrack, because untracking is what makes it untrue.** The question is
        // "does GitHub have a copy of this path", and once the path is out of the index a fresh
        // answer would be about a different state. It is also the question the person actually has:
        // a file is ignored *because* it holds something that should not travel.
        var pushed = false
        var stillTracked = false
        git?.open(project)?.use { repo ->
            pushed = GitService.wasPushed(repo, inside)
            stillTracked = !GitService.untrack(repo, inside)
        }
        // **Two sentences, because there are two situations and only one of them is dangerous.**
        //
        // Ignoring stops a path being *sent from now on*. It does not take back what was sent: the
        // commit that carried it is still in the remote's history and `git cat-file` still prints it
        // after the file is gone from a fresh clone. Measured, not assumed. So a person who ignores a
        // file because it holds a key has not removed the key, and the sentence has to say so.
        //
        // **Every time, and for every path** - not only for names that look like secrets. The app
        // cannot judge what is a secret, and a list of patterns would be right about the names
        // somebody thought of and silently wrong about every other file.
        message = when {
            stillTracked -> "Ignoring $inside, but it is still in the repository"
            pushed ->
                "Ignoring $inside. Its earlier versions stay on GitHub — ignoring stops it being " +
                    "sent from now on; it does not take back what was sent."
            else -> "Ignoring $inside"
        }
        refresh()
        commitProject(project, "Ignored $inside")
    }

    /**
     * Keeps [file] off GitHub, or sends it again — **one act, both directions.**
     *
     * **Not `.gitignore`.** An ignore would untrack the file: out of the index, out of future
     * commits, unversioned on the phone. Unsyncing is the other thing: the file stays tracked, every
     * save still makes a version, the local history keeps all of them, and only the tree that is
     * *sent* leaves it out. The operator's distinction, and the reason the button is not called
     * Ignore.
     *
     * A file the app holds - a secret, or the app's own bookkeeping - cannot be unsynced *or*
     * synced: the app already keeps it off GitHub, and a tap that claimed to change that would be
     * lying. Those are drawn as marks rather than buttons, so this should not be reached; the guard
     * is here so the rule lives with the act rather than only with the drawing.
     */
    fun toggleUnsynced(file: File) {
        val ws = workspace ?: return
        if (ws.unsync(file) == Workspace.Unsync.APP) {
            message = "The app keeps ${file.name} off GitHub, and only the app can change that"
            return
        }
        val path = ws.relative(file) ?: return
        val key = ws.pathString(path)
        val was = key in unsyncedPaths
        Trace.tap(if (was) "sync again" else "unsync", file.name)
        val next = if (was) unsyncedPaths - key else unsyncedPaths + key
        ws.setUnsynced(next)
        unsyncedPaths = next
        // **Said, because the state is not visible in the file.** A lock shows on the row; whether a
        // file will be sent is a fact about the next push, so the sentence is where a person learns
        // it - and the sentence differs by direction, because only one of them has a caveat.
        message = if (was) {
            "${file.name} will be sent again on the next sync"
        } else {
            // **The caveat, and only when it is true.** Unsyncing stops future sends; it does not
            // take back what GitHub already has. Saying so on a file that was never pushed would be
            // noise, so the question is asked of the repository.
            val project = ws.projectOf(file)
            val inside = path.drop(1).joinToString("/")
            val pushed = project != null && git?.open(project)?.use { repo ->
                GitService.wasPushed(repo, inside)
            } == true
            if (pushed) {
                "${file.name} will not be sent again. Its earlier versions stay on GitHub — this " +
                    "stops future sends, it does not take back what was sent."
            } else {
                "${file.name} will not be sent to GitHub"
            }
        }
        refresh()
    }

    /** True when [file] may not be written — itself, or because a folder above it is.
     *
     * The rule lives in [Workspace], which is where every write goes. This answers the
     * screen's question from the same place rather than from its own copy, so the icon,
     * the greyed Save and the funnel cannot disagree.
     */
    fun isLocked(file: File): Boolean = workspace?.isLocked(file) ?: false

    /**
     * Who holds [file], for the row that draws it.
     *
     * **The row's question, and not the same as [isLocked].** A person's lock is an instruction to
     * the agent, so it does not stop the person - but the row must still show it, or the list would
     * look like the lock had not taken. `isLocked` is "may anybody write this"; this is "what does
     * the mark say", and the two differ exactly by the person's own locks.
     */
    fun hold(file: File): Workspace.Hold = workspace?.hold(file, lockedPaths) ?: Workspace.Hold.NONE

    /**
     * True for the agent's record, which the app keeps and does not let a person save over.
     *
     * Asked here for the same reason [isLocked] is: the greyed Save and the funnel that refuses the
     * write must be one answer, not two copies of a rule that can drift apart.
     */
    fun isRecord(file: File): Boolean = workspace?.isRecord(file) ?: false

    /** True when [file] itself was locked, rather than sitting inside a locked folder. */
    fun isLockedHere(file: File): Boolean = workspace?.isLockedHere(file) ?: false

    /** Pins or unpins a file or folder (§2: the flags live in `.muffy`). */
    fun togglePinned(file: File) {
        val ws = workspace ?: return
        val target = ws.relative(file) ?: return
        val key = ws.pathString(target)
        // Named before the change, so the trace says which way the switch was pressed
        // rather than what it ended up as.
        Trace.tap(if (key in pins) "unpin" else "pin", file.name)
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
        Trace.tap(if (key in selected) "unselect" else "select", file.name)
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
     * **Checked first, then committed as it goes.** Two questions are answered before anything
     * moves — is a name already taken there, and is anything involved locked — because those are the
     * two a person cannot see from where they are standing, and a batch that fails halfway through
     * because of one of them is a surprise found later.
     *
     * **A failure that could not be checked for commits what did move.** A disk that filled, a
     * folder that vanished: the files that already moved are on disk, and a return with no commit
     * would leave work that no repository knows about — the one state this app exists to make
     * impossible. So the batch stops, records what happened, says "Moved 3 of 5", and leaves the
     * rest selected for a second try.
     *
     * The commits happen after the moves, not per move: a commit takes everything that is waiting
     * anyway, so per-move commits would fold into one and cost a repository walk each.
     */
    fun moveSelected(into: File): Boolean {
        val ws = workspace ?: return false
        val files = selectedFiles().filter { it.canonicalFile != into.canonicalFile }
        if (files.isEmpty()) {
            message = "Nothing is selected"
            return false
        }
        blocked(files, into, "moved")?.let { message = it; return false }
        // Which repositories the things came out of. A move that crosses the
        // boundary has to be recorded on both sides, and with several things at once
        // the sources can be more than one.
        val was = mutableSetOf<String>()
        val moved = mutableListOf<File>()
        var failure: String? = null
        for (file in files) {
            ws.projectOf(file)?.takeIf { ws.inWorktree(file) }?.let { was += it }
            val result = ws.move(file, into)
            if (result.isFailure) {
                failure = result.exceptionOrNull()?.message ?: "One file could not be moved"
                break
            }
            moved += file
        }
        // **What moved leaves the selection; everything else stays as it was.** A person whose batch
        // stopped halfway has the rest still marked, so the second try is one touch and not a hunt.
        val movedKeys = moved.mapNotNull { ws.relative(it) }.map { ws.pathString(it) }.toSet()
        selected = selected - movedKeys
        refresh()
        val what = when {
            moved.isEmpty() -> null
            moved.size < files.size -> "Moved ${moved.size} of ${files.size} items into ${into.name}/"
            files.size == 1 -> "Moved 1 item into ${into.name}/"
            else -> "Moved ${moved.size} items into ${into.name}/"
        }
        // Both sides of the boundary, as for one move: the destination gains what
        // arrived, and every source repository records what left it. Only where something moved —
        // a batch that failed on its first file has nothing to record and must not commit.
        if (what != null) {
            val arriving = ws.projectOf(into)?.takeIf { ws.inWorktree(into) }
            val leaving = was - setOfNotNull(arriving)
            leaving.forEach { commitProject(it, what) }
            if (arriving != null) commitProject(arriving, what)
            historyGeneration++
        }
        message = when {
            failure == null -> what ?: "Nothing was moved"
            moved.isEmpty() -> "Nothing was moved — $failure"
            else -> "$what, and then $failure. The rest are still selected."
        }
        return failure == null
    }

    /**
     * Copies everything selected into [into].
     *
     * The same pre-check as [moveSelected], the same reason, and the same honesty when one copy
     * fails: what was copied is committed and counted, and the rest stay selected. **Only the
     * receiving side is committed** — nothing left any source, so there is nothing to record on the
     * other side.
     */
    fun copySelected(into: File): Boolean {
        val ws = workspace ?: return false
        val files = selectedFiles().filter { it.canonicalFile != into.canonicalFile }
        if (files.isEmpty()) {
            message = "Nothing is selected"
            return false
        }
        blocked(files, into, "copied")?.let { message = it; return false }
        val copied = mutableListOf<File>()
        var failure: String? = null
        for (file in files) {
            val result = ws.copy(file, into)
            if (result.isFailure) {
                failure = result.exceptionOrNull()?.message ?: "One thing could not be copied"
                break
            }
            copied += file
        }
        val copiedKeys = copied.mapNotNull { ws.relative(it) }.map { ws.pathString(it) }.toSet()
        selected = selected - copiedKeys
        refresh()
        val what = when {
            copied.isEmpty() -> null
            copied.size < files.size -> "Copied ${copied.size} of ${files.size} items into ${into.name}/"
            files.size == 1 -> "Copied 1 item into ${into.name}/"
            else -> "Copied ${copied.size} items into ${into.name}/"
        }
        if (what != null) {
            ws.projectOf(into)?.takeIf { ws.inWorktree(into) }?.let { commitProject(it, what) }
            historyGeneration++
        }
        message = when {
            failure == null -> what ?: "Nothing was copied"
            copied.isEmpty() -> "Nothing was copied — $failure"
            else -> "$what, and then $failure. The rest are still selected."
        }
        return failure == null
    }

    /**
     * Why a batch must not start, or null when every name is free and nothing is locked.
     *
     * **The two questions a batch has to answer before it touches anything.** A taken name is the
     * one the person cannot see from where they are standing, so it is named; a locked file in a
     * move is a member of the batch that cannot go, and moving the others would leave the act
     * half done. A copy of a locked file is fine — a copy only reads the source.
     *
     * [verb] is past tense, because the sentence is about the act that did not happen.
     */
    private fun blocked(files: List<File>, into: File, verb: String): String? {
        val taken = files.map { File(into, it.name) }.filter { it.exists() }.map { it.name }.distinct()
        if (taken.isNotEmpty()) {
            val quoted = taken.map { "\"$it\"" }
            val list = when (quoted.size) {
                1 -> quoted.first()
                2 -> "${quoted[0]} and ${quoted[1]}"
                else -> quoted.dropLast(1).joinToString(", ") + " and " + quoted.last()
            }
            return "Nothing was $verb — there is already $list there"
        }
        if (verb == "moved") {
            files.firstOrNull { isLocked(it) }?.let {
                return "${it.name} is locked, so nothing was moved"
            }
        }
        if (isLocked(into)) return "${into.name} is locked, so nothing was $verb"
        return null
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
        Trace.tap("open file", file.name)
        editingPath = workspace?.relative(file)
        editorSession++
    }

    fun closeEditor() {
        Trace.tap("close editor")
        editingPath = null
        // **The editor's own report does not follow you out.** The operator's report: "why do I get
        // renamed and saved notifications after exiting editor? I did those actions already inside
        // the editor and dont need reminders".
        //
        // "Saved milk.txt" and "Renamed milk.txt to groceries.txt" are answers to a tap that happened
        // *in the editor*, drawn as the editor's state line. Leaving the editor left them set, so the
        // browser showed a sentence about an act the person had already watched happen - and on the
        // browser's own line, where the next message (a sync result, a lock) will overwrite it, it
        // reads as though something just happened now.
        //
        // **Cleared, not remembered.** The history holds what was done, and a message that outlives
        // its screen is a reminder about nothing. Anything the *browser* has to say sets it again
        // after this, which is why clearing here cannot swallow a real answer.
        message = null
    }

    /**
     * Fills the changes window from the repository: every path the last push does not have, with its
     * diff.
     *
     * **Opened empty and filled behind the UI**, because a project with two hundred changed files is
     * two hundred blob reads, and a button that froze the screen while it worked would be a button
     * people learn not to press. The list arrives all at once: a half-filled list whose numbers grow
     * while a person reads it is worse than a moment of nothing.
     */
    fun openChanges(project: String) {
        Trace.tap("see changes", project)
        changes = emptyList()
        onWorker {
            val g = git ?: return@onWorker
            val files = g.unsentPaths(project).map { path ->
                g.diff(project, path).fold(
                    onSuccess = { ChangedFile(path, it, null) },
                    onFailure = { ChangedFile(path, null, it.message ?: "no preview") },
                )
            }
            main.post { changes = files }
        }
    }

    /** Closes the changes window. The editor closing does not. */
    fun closeChanges() {
        Trace.tap("close changes")
        changes = null
    }

    /**
     * Opens one of the changed files in the editor, **leaving the window behind it.**
     *
     * The path is git's: relative to the project, `/` between folders — which is also the file's
     * place on disk, because the project's folder is the repository.
     */
    // ---- the editor's diff mode ---------------------------------------------

    /**
     * The two commits the editor's diff is between, **as ids a person can read.**
     *
     * `from` is where GitHub last saw the file and `to` is `HEAD` — the pair that answers "what am I
     * about to send", which is the question somebody opening a diff on this screen is asking. Both
     * are replaceable: the picker offers the file's own history, so a person comparing two older
     * versions is the same act with two other ids.
     *
     * **Null `from` means nothing was ever pushed**, which is not the same as "no changes": a file
     * whose whole history is local has no synced version, and the diff is every line added. The
     * screen says that instead of drawing a comparison against nothing.
     */
    var diffFrom: String? by mutableStateOf(null)
        private set

    var diffTo: String? by mutableStateOf(null)
        private set

    /** The commits that touched the file being edited, newest first, for the picker. */
    var diffChoices: List<GitService.CommitInfo> by mutableStateOf(emptyList())
        private set

    /**
     * Reads the two ends and the choices for [path].
     *
     * Called when Diff is opened rather than on every redraw: it is a walk of the file's history,
     * and a screen that re-walked it on each recomposition would be doing that work for a frame.
     */
    fun openDiff(path: String) {
        // **Traced here rather than in the screen**, because this is where the mode becomes a
        // question about the repository: entering Diff and finding nothing to compare look the same
        // on the screen, and only the log can say which happened.
        Trace.tap("diff", path)
        val ws = workspace ?: return
        val project = currentProject ?: return
        val g = git ?: return
        onWorker {
            val repo = g.open(project)
            val choices = if (repo == null) {
                emptyList()
            } else {
                try {
                    GitService.log(repo, path)
                } catch (t: Throwable) {
                    emptyList()
                }
            }
            main.post {
                diffChoices = choices
                // **The default is the newest version against the one before it.** The operator's
                // report: "diff isnt getting selected even when there is history to compare, fix
                // it. and set it so that by default it shows newest version vs previous one".
                //
                // It used to be newest against the *last synced* commit, and that is a different
                // question with a hole in it: a file that has never been pushed has no synced
                // version, so `diffFrom` was null and the screen said "there is no version of this
                // file at one of those commits" about a file with a dozen commits behind it. The
                // person saw an empty diff and a file with history, which is exactly what the
                // report describes.
                //
                // Newest-vs-previous always has both ends whenever there are two commits, which is
                // the condition for the mode to mean anything at all. `from` is the older one
                // because a diff reads forwards.
                diffTo = choices.getOrNull(0)?.id
                diffFrom = choices.getOrNull(1)?.id
            }
        }
    }

    /**
     * The file at [path] as it was at [revision], or null when it did not exist there.
     *
     * **Null is a real answer**: a file added since the last sync has no version at the older end,
     * and the diff is every line added rather than a failure.
     */
    fun fileAtRevision(path: String, revision: String?): String? {
        val project = currentProject ?: return null
        val g = git ?: return null
        val repo = g.open(project) ?: return null
        return try {
            GitService.fileAt(repo, path, revision)
        } catch (t: Throwable) {
            null
        }
    }

    fun openChangedFile(path: String) {
        // The list's own act: which file a person entered from "see n changes".
        Trace.tap("open changed file", path)
        val ws = workspace ?: return
        val project = currentProject ?: return
        openEditor(File(ws.projectDir(project), path))
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
        // **The tap is recorded here, after the refusals and before the work.** Save is
        // the element people report on — "I pressed Save and nothing appeared in the
        // history" — and until this line the most important action in the app left no
        // trace at all. It is named as the button is rather than as the method is,
        // because a report is read by somebody holding the phone.
        //
        // After the guards, because a tap the screen never allows is not a tap: a locked
        // file has Save greyed out, and recording an intent for it would put a broken
        // sequence in every trace, from a button nobody could press.
        // **By id.** The answer is written after a write, a commit and a redraw, and by then
        // the newest tap waiting may be somebody else's — which is what the log showed: an
        // answer labelled "Saved milk.txt" attached to an `open-file` tap, and the save itself
        // still waiting. A save knows which press it is answering.
        val tap = Trace.tap("save", file.name)
        val g = git
        val name = file.name
        // **Its own project's repository versions it.** Every file below a project is in that
        // project's repository now, so there is one history rather than two: every save is its
        // own version, and sync is then only the push.
        val project = ws.projectOf(file)
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
                val outcome = project?.let { g?.commitOutcome(it, CommitMessage.SAVE) }
                if (outcome != null && !outcome.ok) refused = outcome.error
            }
            // **The save answers itself here, and not after the hop to the screen.** A save
            // changes the file and its history, and neither is on the screen: the name is the
            // same afterwards, so the state line is either unchanged (never written) or
            // identical. The tap therefore waited forever — the live log shows `save
            // file26.txt` with no answer at all, while the next back press spent it.
            //
            // Written on this thread, the one that did the work, so a row is never missing
            // because the main thread has not got to it. The message still goes through the
            // post, because it is state the screen draws.
            val answer = if (refused != null) {
                "Saved to the file, but not to its history: $refused"
            } else {
                "Saved $name"
            }
            if (refused != null) {
                Trace.failed(tap, answer, "save")
            } else {
                Trace.answered(tap, answer)
            }
            main.post {
                refresh()
                refreshChanges()
                message = answer
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
     * project's history would be the app's own ("Saved", "Created Drafts/"), and the
     * answer to "why was this pushed" would be a list of file names. A blank note means
     * "just push", which is what a project with nothing waiting gets.
     */
    @JvmOverloads
    fun sync(project: String, note: String? = null) {
        val g = git ?: return
        if (g.isLocal(project)) {
            // **Said, not silently ignored.** A local project has no remote by design, and a person
            // who presses Sync on one deserves the sentence rather than a spinner that ends.
            Trace.failure("sync", "local")
            message = "$project stays on this phone: it has no GitHub repository to send to"
            return
        }
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
     * Makes a **local** project: a folder with a repository of its own and nothing to push to.
     *
     * A local project is a project: it has versions, an agent, and a row in the same list — and the
     * row says `local` instead of a count, because "up to date" is a claim about GitHub and this
     * folder has never been there. That is what makes it useful for work that does not belong to any
     * repository: the app's history and the agent still apply, and nothing leaves the phone.
     *
     * The name is put through [Workspace.cleanName], unlike a GitHub project's: this one is a folder
     * the person typed, not a repository whose name GitHub already accepted.
     */
    fun createLocalProject(name: String, onDone: (Boolean) -> Unit = {}) {
        val ws = workspace ?: return
        val clean = Workspace.cleanName(name, "local")
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
            val folders = ws.createProjectFolders(clean)
            if (folders.isFailure) {
                main.post {
                    busy = false
                    message = folders.exceptionOrNull()?.message ?: "Could not make the project"
                    onDone(false)
                }
                return@onWorker
            }
            // **A repository, with no remote.** The history is what makes it a project rather than
            // a folder: every save is a version, and a version can be gone back to.
            val made = ProjectGit(
                ws,
                { null },
                {
                    if (accounts.isProvisional(activeLogin)) PROVISIONAL_AUTHOR
                    else accounts.account(activeLogin ?: "")?.author() ?: DEFAULT_AUTHOR
                },
                { settings.historyLength },
            ).init(clean)
            main.post {
                busy = false
                // **The words and the answer first, then the redraw.** `refresh()` is what makes the
                // new project a row; the screen waiting on this is the add window, and it was closed
                // on a list that had not been redrawn yet — so the project appeared only after
                // leaving the screen and coming back. Reported from the live log: "adding and
                // deleting a project shows the change only after you exit and enter the project
                // menue again".
                message = if (made.isSuccess) {
                    "Made \"$clean\" on this phone. It does not go to GitHub."
                } else {
                    "Made the folder, but its history could not be started"
                }
                onDone(made.isSuccess)
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
            // **No folder is made first.** `createProjectFolders` makes the project's folder,
            // and git refuses to clone into a folder that already holds anything — so making it
            // here meant every project made on GitHub arrived as an empty folder, an error in the
            // snackbar and no repository, which is to say no history at all. The clone makes the
            // folder; `cloneInto` writes the app's two ignore lines into it afterwards.
            val clone = ProjectGit(
                ws,
                { credentials.token(login) },
                { accounts.account(login)?.author() ?: DEFAULT_AUTHOR },
                { settings.historyLength },
            ).cloneInto(clean, url)

            main.post {
                busy = false
                message = clone.message
                // **It does not move you inside.** A project appearing in the list is the result,
                // and being teleported into an empty folder is how a person loses track of where
                // they were — the row is right there when they want it.
                //
                // **And the window is answered before the list is redrawn**, for the reason the
                // local-project path gives: the screen that waits is the add window, and a list
                // redrawn after it has closed reaches nobody.
                onDone(clone.success)
                refresh()
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
        // **Both directions.** Renaming it away would leave the settings in a folder called
        // something else, and renaming another project *to* it would give the account two — one of
        // which the delete guard would then refuse, which is worse than refusing the rename.
        if (Workspace.isSystem(project) || Workspace.isSystem(clean)) {
            message = if (Workspace.isSystem(project)) {
                "\"$project\" is where this account's settings live, so it keeps its name"
            } else {
                "\"$clean\" is reserved for this account's settings"
            }
            return
        }
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
            val moved = ws.rename(ws.projectDir(project), clean, tidy = false)
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
                // The folder was renamed, and so was the path the screen is looking at — which
                // is now the project's own name, one level shallower than it used to be.
                if (path.firstOrNull() == project) {
                    path = listOf(clean) + path.drop(1)
                }
                refresh()
            }
        }
    }

    /**
     * Deletes a project. [everywhere] also deletes the repository on GitHub;
     * without it only this phone's copy goes and the add button brings it back.
     */
    fun deleteProject(project: String, everywhere: Boolean) {
        val ws = workspace ?: return
        // **The system project is not deletable.** It holds the settings that belong to the whole
        // account, and a person who deletes it loses the prompt with no way to notice until the
        // agent behaves differently. Refused in the view model rather than greyed out on the row:
        // a screen-only guard is one the next screen will not honour.
        if (Workspace.isSystem(project)) {
            message = "\"$project\" is where this account's settings live, so it stays"
            return
        }
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
            ws.delete(ws.projectDir(project))
            main.post {
                busy = false
                message = note.ifBlank {
                    if (everywhere) "Deleted $project everywhere" else "Deleted $project from this phone"
                }
                if (currentProject == project) path = emptyList()
                // **The row must be gone before the sentence about it is read.** The delete window
                // closed on the old list, so the project was still drawn under the message saying it
                // had been deleted — the same fault as the add window's, reported in the same breath:
                // "adding and deleting a project shows the change only after you exit and enter the
                // project menue again".
                refresh()
            }
        }
    }

    /**
     * **Gives a local project a GitHub repository** — local → synced.
     *
     * The operator's report: "local projects should have add to github option. and synced already
     * give ability to keep the project as local in delete, right? make sure that the state change
     * cycle (local<>synced) is complete and without errors."
     *
     * **The history goes up with it.** A local project has been committing since it was made - that
     * is what makes it a project rather than a folder - so the repository is created empty and the
     * first sync carries every version that already exists. Nothing is rewritten and nothing is
     * copied: the project's folder *is* the repository.
     *
     * **The licence IS asked for, and that is the operator's correction.** A project on this phone
     * has no licence file, because there was nothing to seed; the moment it is published it is a
     * repository somebody can find, use and quote, so the terms it is offered under are a real
     * decision rather than a detail. The app's policy is that everything it publishes is open
     * source, so the dialog offers the licences GitHub publishes and starts on GPL-3.0.
     */
    fun addProjectToGitHub(project: String, licence: String, onDone: (Boolean) -> Unit = {}) {
        val ws = workspace ?: return
        val api = github
        val login = activeLogin
        val token = login?.let { credentials.token(it) }.orEmpty()
        if (api == null || login == null) {
            message = "Sign in to GitHub first: a local project becomes synced by getting a repository"
            onDone(false)
            return
        }
        if (!isLocalProject(project)) {
            message = "$project already has a GitHub repository"
            onDone(false)
            return
        }
        busy = true
        onWorker {
            // **Seeded with the licence, which is the whole point of asking.** GitHub writes the
            // file as part of creating the repository; the first sync then carries the project's
            // own history on top of it.
            val created = api.createRepo(token, project, licence)
            if (created.error != null) {
                main.post {
                    busy = false
                    message = created.error
                    onDone(false)
                }
                return@onWorker
            }
            val url = created.value?.cloneUrl?.takeIf { it.isNotBlank() }
                ?: api.cloneUrlFor(login, project)
            val pointed = git?.makeRemote(project, url) ?: false
            main.post {
                busy = false
                // **The first sync is what actually sends it**, and it runs here rather than being
                // left for a tap: a project that says "synced" while GitHub holds an empty
                // repository is the worst of both states.
                if (pointed) {
                    Trace.note("published", "$project -> $url")
                    sync(project)
                } else {
                    message = "The repository was made, but $project could not be pointed at it"
                }
                refresh()
                onDone(pointed)
            }
        }
    }

    /**
     * **Takes a project's GitHub repository away from this phone** — synced → local.
     *
     * Every commit stays. The history is in this phone's repository, so the project keeps every
     * version it has and simply stops having somewhere to send them.
     *
     * **What is on GitHub stays on GitHub**, and the window says so before this runs: removing the
     * remote is a statement about this phone, not about the server, and "keep as local" that quietly
     * left a public copy behind would be a promise the app could not keep. Deleting the repository
     * itself is the other act, "delete everywhere".
     */
    fun keepProjectLocal(project: String, onDone: (Boolean) -> Unit = {}) {
        if (Workspace.isSystem(project)) {
            message = "\"$project\" is where this account's settings live, so it stays"
            onDone(false)
            return
        }
        if (isLocalProject(project)) {
            message = "$project is already only on this phone"
            onDone(false)
            return
        }
        val hadRemote = git?.remoteUrl(project)
        val ok = git?.makeLocal(project) ?: false
        Trace.tap("keep local", project)
        message = if (ok) {
            "$project is local now. Everything it holds is still here, and its repository is " +
                "still on GitHub" + (hadRemote?.let { " at $it" } ?: "")
        } else {
            "$project could not be made local"
        }
        refresh()
        onDone(ok)
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
            // As in `createProject`: the clone makes the folder. Making it first is what
            // stopped every pull-in, because git will not clone into a non-empty folder.
            val result = ProjectGit(
                ws,
                { credentials.token(login) },
                { accounts.account(login)?.author() ?: DEFAULT_AUTHOR },
                { settings.historyLength },
            ).cloneInto(repo.name, repo.cloneUrl)
            main.post {
                busy = false
                message = result.message
                // Same rule as creating one: pulling a project in adds a row, it
                // does not take over the screen.
                refresh()
            }
        }
    }

    // ---- the agent's chat ---------------------------------------------------

    /** The project whose conversation is on the screen, or null when none is. */
    var chatProject: String? by mutableStateOf(null)
        private set

    /**
     * **What has happened to the open project's context**, for the settings block.
     *
     * Read on demand rather than held in state: it changes only when the agent forgets something or
     * the app trims, and both are visible on the chat screen the moment they happen - so a settings
     * screen that read it once when it opened would be showing a number from before the thing the
     * person is looking at.
     */
    val contextFate: AgentLog.Fate
        get() {
            val project = chatProject ?: return AgentLog.Fate()
            val ws = workspace ?: return AgentLog.Fate()
            return try {
                AgentLog.open(ws.projectDir(project)).fate()
            } catch (t: Throwable) {
                // A record that will not parse is a record with nothing to report, not a crash on
                // the settings screen.
                AgentLog.Fate()
            }
        }

    /**
     * The conversation as the record last had it.
     *
     * **Read, never remembered.** Every burst re-reads `agent/session.log` and hands the box a
     * fresh [AgentChat.Box], so the screen and the evidence are the same object — an in-memory
     * transcript beside the file would disagree the first time the app was closed mid-turn.
     */
    var chatBox: AgentChat.Box? by mutableStateOf(null)
        private set

    /** Bumped on every read, so a box that is byte-identical still redraws. */
    var chatGeneration: Int by mutableStateOf(0)
        private set

    /** True while a burst is running. */
    var chatWorking: Boolean by mutableStateOf(false)
        private set

    /**
     * What has arrived of the answer being written, **as the footer shows it.**
     *
     * Empty when nothing is in flight. It is a *description*, not a fraction: there is no
     * denominator, because nobody knows how long an answer will be until it is finished — so this
     * is elapsed and produced, and it is deliberately not a progress bar. A bar would imply a
     * proportion the app cannot know.
     *
     * **Characters, not tokens.** A per-chunk `usage` block is not guaranteed, so a token count
     * here would be an estimate dressed as a measurement; the real totals arrive once, at the end.
     */
    var chatLive: String by mutableStateOf("")
        private set

    /**
     * Where the agent's requests go.
     *
     * Swappable for the same reason [catalogue] is: no test opens a socket, so the box has to be
     * drivable by a script — and being able to say exactly what was sent is most of what makes a
     * test of a chat worth writing.
     */
    var chatDoor: ((ActiveModel) -> Door)? = null

    /** Opens [project]'s conversation and lands on its newest turn. */
    fun openChat(project: String) {
        Trace.tap("chat", project)
        chatProject = project
        chatBox = null
        readChat(follow = true)
    }

    fun closeChat() {
        Trace.tap("close chat", chatProject.orEmpty())
        chatProject = null
        chatBox = null
        chatWorking = false
        chatLive = ""
    }

    /**
     * Reads the conversation again, off the main thread.
     *
     * [follow] decides what happens to the turn being looked at: a person who opened the chat or
     * just spoke wants the end of the conversation, and a person reading turn 2 of 6 while the
     * agent works does not want to be pulled forward.
     */
    fun readChat(follow: Boolean = false) {
        val project = chatProject ?: return
        val ws = workspace ?: return
        val dir = ws.projectDir(project)
        onWorker {
            val rows = try {
                AgentLog.open(dir).rows()
            } catch (t: Throwable) {
                emptyList()
            }
            main.post { applyChat(rows, follow) }
        }
    }

    /**
     * Says something to the agent, and lets it work until it ends the turn.
     *
     * The burst runs on the app's one worker — the same worker as saves and commits — so two
     * turns cannot overlap and a file cannot be written twice. [Agent.burst] reports each turn as
     * it completes, and each report re-reads the record: the box fills in while the agent works
     * instead of appearing all at once at the end.
     */
    fun sendChat(text: String) {
        val said = text.trim()
        if (said.isEmpty()) return
        val project = chatProject ?: return
        if (workspace == null || git == null) return
        Trace.tap("send", said.take(60))
        // **A message during a turn cuts it short instead of being dropped.** v1's C8: the person
        // is the highest priority in the conversation, so the request in flight is abandoned, the
        // calls it carried are answered `1 interrupted`, and these words are the next turn — in
        // that order, because a tool result may not follow a user message. The old behaviour,
        // returning early, made the app look as though it had not heard them.
        if (chatWorking) {
            pendingChat = said
            interrupt = INTERRUPTED
            Trace.note("agent", "$project: the operator spoke during a turn")
            return
        }
        chatWorking = true
        // **Speaking to a stopped agent turns it on again**, because the person is the switch:
        // there is no other control they would have used, and a message that vanished into a
        // stopped session would look like the app ignoring them.
        sessionOf(project)?.let {
            if (!it.running) putSession(it.resumed(System.currentTimeMillis()))
        }
        speak(project, said)
    }

    /** One turn said out loud, and what to do when it ends. */
    private fun speak(project: String, said: String) {
        chatWorking = true
        interrupt = null
        runTurn(project, said) { rows, end ->
            applyChat(rows, follow = true)
            chatWorking = false
            // **The footer goes when the answer does.** It described an answer in flight; once the
            // turn is over the record holds the answer itself, and a live line left behind would be
            // a description of something that already finished.
            chatLive = ""
            message = end.plainly()
            applyEnd(project, end, System.currentTimeMillis())
            refresh()
            refreshChanges()
            // The words that interrupted this turn, sent as the turn they were waiting for. Not
            // before the results were written: the record has to be legal in between.
            val next = pendingChat
            pendingChat = null
            if (next != null && end is Agent.End.Interrupted) speak(project, next)
        }
    }

    /** What to tell the loop when the person speaks mid-turn, or stops the agent. */
    private var interrupt: String? = null

    /** The words that arrived during a turn, sent as the next one. */
    private var pendingChat: String? = null


    /**
     * Runs one turn on the worker and hands back what it wrote.
     *
     * **The person's turn, and only that.** The app's own keepalive is the runner's, not this: it
     * builds a request and throws the answer away, so it has nothing to draw and nothing to tell
     * the screen mid-flight. Sharing this function with it would have meant a redraw path nobody
     * waits for and a `said` that can be null — which is exactly the null branch this used to carry.
     */
    private fun runTurn(project: String, said: String, then: (List<AgentLog.Row>, Agent.End) -> Unit) {
        val ws = workspace ?: return
        val g = git ?: return
        val model = modelConfig.active
        val door = doorFor(model)
        // The two limits, with this pin's ceiling — read once per turn, because the pin does not
        // change mid-turn and a settings read per request would be the same number twice.
        //
        // **The ceiling is the model's own window, which the choice stores** (see [ActiveModel]).
        // Asking for the pin alone used to reach a 96,000-token floor for every endpoint the table
        // did not name, so a million-token model was trimmed at 96k while the screen promised its
        // real size.
        val limits = ContextBudget.Limits.of(
            triggerAsked = settings.triggerTokens.toLong(),
            hardAsked = settings.hardTokens.toLong(),
            targetAsked = settings.targetTokens.toLong(),
            ceiling = ContextBudget.ceiling(model.provider, model.contextLength, model.maxOutput),
        )
        // One result may not be bigger than the model's input window: bigger than that cannot be
        // sent at all, so the ask is clamped and the settings row says which number is in force.
        val resultLimit = ContextBudget.resultLimit(settings.maxResult, model.contextLength)
        // **The tick must not wake this session while the person's turn is running.** One request on
        // one conversation at a time: a wake landing beside this turn would write a second ending and
        // a second set of results, and the person would read the mixture of the two.
        val release = runner?.hold(project)
        onWorker {
            try {
                val log = AgentLog.open(ws.projectDir(project))
                val agent = Agent(
                    ws.accountDir, project, ws, g, log, door, model,
                    // How much of one result may enter the conversation, clamped by the model's input
                    // window — a result larger than that cannot be sent, so it would be filed for ever.
                    maxResult = resultLimit.inForce.toInt(),
                    limits = limits,
                    // **The same registry the polls use.** A `background` command started while
                    // somebody is typing keeps running beside the turn and lands its result in a file on
                    // its own thread; a turn and a poll share the one registry, so a command started by
                    // one is not invisible to the other.
                    bg = runner?.bg ?: Background.inline(),
                    foregroundMs = settings.foregroundMs,
                    reconnectMs = settings.reconnectMs,
                    backgroundMs = { settings.backgroundMs },
                    // **The clear-up point, read per call.** `forget` refuses under it, so the
                    // number that applies is the one in force when the call arrives - not the one
                    // that was in force when the chat screen was opened.
                    triggerTokens = {
                        // **The same numbers the settings screen shows**, resolved the same way:
                        // the trigger and hard limit as asked for, clamped by this pin's own window.
                        // A second derivation here would be a second answer to "how full is the
                        // context allowed to be", and the two could disagree.
                        ContextBudget.Limits.of(
                            triggerAsked = settings.triggerTokens.toLong(),
                            hardAsked = settings.hardTokens.toLong(),
                            targetAsked = settings.targetTokens.toLong(),
                            ceiling = ContextBudget.ceiling(
                                pin = modelConfig.active.provider,
                                contextLength = modelInfo?.contextLength,
                                maxOutput = modelInfo?.maxOutput,
                            ),
                        ).triggerAt
                    },
                    sessionTokens = settings.sessionLimit,
                    // Read at every boundary rather than captured: the person may speak or stop while
                    // this turn is waiting for an answer, and the loop has to see that when it comes.
                    cut = { interrupt },
                )
                val end = try {
                    // **The screen only ever speaks.** The tick's own pings come from the runner, where
                    // nothing is drawn; a poll built here as well was a second door into the same
                    // conversation, and the two could not agree about who was waiting.
                    agent.burst(said) {
                        val rows = log.rows()
                        main.post { applyChat(rows, follow = true) }
                    }
                } catch (t: Throwable) {
                    // **A burst that threw is a wall.** The transport's own failures already come back
                    // as a retryable Stopped from inside the loop, so what escapes to here is the turn's
                    // own machinery — the record, a tool, a bug — and retrying that every four seconds
                    // would hide it.
                    Agent.End.Stopped(Secrets.explain(t, "The agent could not run"))
                }
                val rows = log.rows()
                main.post { then(rows, end) }
            } finally {
                release?.invoke()
            }
        }
    }

    // ---- the agent's sessions -----------------------------------------------


    /**
     * Where a notification goes.
     *
     * A hook rather than a `NotificationManager`, for the same reason the door is an interface:
     * what should be said, and when, is the part worth testing, and it can be tested without a
     * tray. The Android side passes a lambda that posts.
     */
    var agentNotifier: ((AgentSession) -> Unit)? = null

    /**
     * The clock, shared with the foreground service.
     *
     * **One implementation of "which session is due".** The screen's tick and the service's tick
     * are the same act, so both call the same runner — a second copy would be a second answer to
     * the same question, and the one a person reads would be whichever ran last. The runner has no
     * screen: [agentSessions] is refreshed from the file it keeps.
     */
    private var runner: AgentRunner? = null

    /** How often a session that needs a person reminds them, from Settings. */
    val reminderMs: Long get() = settings.remindMinutes * 60_000L

    /** The projects with a ping in flight, so a test can wait for one to finish. */
    internal fun agentPollingInFlight(): Set<String> = runner?.polling() ?: emptySet()

    /**
     * Whether this project's agent is busy, asked the runner rather than compared here.
     *
     * **Not `project in agentPollingInFlight()`.** The runner's set holds agent ids; a project name
     * equals one today and will not when a project can have more than one agent. The comparison
     * lives with the ids, so the day they differ the screen is right without being changed.
     */
    internal fun agentBusy(project: String): Boolean = runner?.isBusy(project) ?: false

    /**
     * The last answer to "does a clock need to exist?", or null before this process asked.
     *
     * **Asked on change rather than on every tick**, because the answer is the same on almost every
     * tick and starting a service is not free. Null rather than false so the first refresh of a new
     * process always asks — which is what starts the clock for sessions that were already on when
     * the phone was switched off.
     */
    private var serviceWanted: Boolean? = null

    /** The session of [project], or null when the agent is not running there. */
    fun sessionOf(project: String): AgentSession? =
        agentSessions.firstOrNull { it.project == project }

    /**
     * The session as it stands **on disk**, which is not always what the screen has.
     *
     * `agentSessions` is a snapshot taken when a tick was *started*, and the poll that tick began
     * writes its outcome afterwards — from a worker. Anything that needs the result of a poll it
     * just watched finish has to read the store, or it reads the moment before the answer arrived.
     * [sessionOf] stays for the screen, which wants exactly the snapshot it drew.
     */
    fun readSession(project: String): AgentSession? = workspace
        ?.let { AgentSessions(it.accountDir).read().firstOrNull { s -> s.project == project } }

    /**
     * Switches the agent on for [project], with nothing said yet.
     *
     * The first ping is one interval away, because there is no conversation to keep warm until the
     * person has asked for something — an agent switched on at 09:00 and asked at 09:03 should not
     * have spent three requests warming an empty one.
     */
    fun startAgent(project: String, now: Long = System.currentTimeMillis()) {
        Trace.tap("start agent", project)
        if (sessionOf(project)?.running == true) return
        putSession(
            AgentSession(project = project, state = AgentSession.State.IDLE, lastPollAt = now)
                // **The interval in force when it started.** A tick reads this copy, so a session
                // written now keeps answering after a reboot without the settings file in hand.
                .withInterval(settings.pollMs)
        )
        Trace.said("the agent is on for $project")
    }

    /**
     * Switches every running agent off — **one switch, in settings, that the brief assumed existed.**
     *
     * It is the same act as one box's Stop, repeated: each session goes through [stopAgent], so the
     * request in flight is interrupted and the session is written the same way. A second
     * implementation of "the person stopped it" is the thing this app keeps out of itself.
     */
    fun stopAllAgents() {
        val on = agentSessions.filter { it.running }
        if (on.isEmpty()) return
        Trace.tap("stop all agents", "${on.size}")
        for (session in on) stopAgent(session.project)
    }

    /**
     * Clears the agent's working context, and keeps the evidence.
     *
     * The person's act, not the agent's: the agent's own clear-up is checked by
     * [AgentLog.forgetProblem] and can never take the newest task, while a reset is a different
     * thing — the conversation starts here. `agent/session.log` is not touched, so nothing that
     * happened is lost: the rows that were in the context are marked `app`, which is the truth (the
     * app took them out; the agent did not judge them), and [AgentLog.thrownAway] still reports
     * only what the model chose to drop.
     *
     * Refused while a turn is running: a context replaced under a reply being written would
     * leave the record half in each file.
     */
    fun resetAgent(project: String) {
        val ws = workspace ?: return
        if (chatWorking || agentBusy(project)) return
        Trace.tap("reset agent", project)
        onWorker {
            val log = AgentLog.open(ws.projectDir(project))
            val id = log.reset()
            val rows = log.rows()
            Trace.note("agent-reset", "$project · row $id")
            main.post {
                if (chatProject == project) applyChat(rows, follow = true)
                message = "The working context was cleared. The session log still has it."
            }
        }
    }

    /** Switches it off. The reason is recorded, because "why did it stop" is the question later. */
    fun stopAgent(project: String) {
        val session = sessionOf(project) ?: return
        Trace.tap("stop agent", project)
        // **The request in flight is abandoned** (v1: "in-flight generations are cancelled in that
        // case, right order"). The loop is told at its next boundary; the calls the reply carried
        // are answered `1 interrupted` so the record stays a conversation, and the session is
        // already stopped so the ending cannot switch it back on.
        if (chatWorking || agentBusy(project)) {
            interrupt = Agent.BY_OPERATOR
            pendingChat = null
        }
        putSession(session.stopped(AgentSession.StopReason.BY_OPERATOR))
        Trace.said("the agent is off for $project")
    }

    /**
     * One tick of the clock: remind whoever needs reminding, and warm whoever is due.
     *
     * **The clock is a parameter, not `now()`.** Every rule about four minutes and one hour is
     * then a test that moves a number, which is the only way to test them at all — and it is why
     * the shell that calls this can be a service, an alarm, or a test, without changing a rule.
     * The rules themselves are [AgentRunner]'s, so a tick from the service and a tick from the
     * screen cannot disagree.
     */
    fun tickAgents(now: Long = System.currentTimeMillis()) {
        val runner = runner ?: makeRunner()
        if (runner == null) {
            // Nobody is signed in, so there is no account whose sessions could be due.
            agentSessions = emptyList()
            return
        }
        runner.tick(now)
        agentSessions = runner.sessions()
        // **The one moment the app already wakes up on a schedule.** Rolling is driven by writes,
        // but a retention cutoff moves at a day boundary and a phone that was simply never used
        // crosses that boundary with no write at all - so it is caught here, once per tick.
        compactRequests(now)
        // **A session on disk needs a clock even if nobody pressed anything.** The service is
        // sticky, so the system restarts it after a kill — but not after a reboot, and not after
        // the app was force-stopped. Opening the app is the first moment the app is allowed to
        // start it again, and this is that moment.
        applyService(agentSessions)
    }

    /**
     * Tidies the request history, on the worker.
     *
     * **Not a timer.** The roll is driven by the boundary each write crosses (`RequestDb.write`),
     * because that is the only trigger that can see a minute close: a clock on this screen runs only
     * while somebody is looking, and the responses that matter most are made while the phone is in a
     * pocket. So there is nothing periodic left to do here.
     *
     * **What is left is the case the write cannot cover: nothing has been written since.** Retention
     * moves at a day boundary, and if the app is opened a week later with no request yet, the day
     * boundary that passed was crossed by no write at all. This is where that is caught - once per
     * open, which is the same moment `tick` already runs.
     *
     * **Nothing is reported.** A compaction that fails leaves the history whole, which is the right
     * failure: the table still reads correctly, just from more rows than it needed to.
     */
    private fun compactRequests(now: Long) {
        val dir = workspace?.accountDir ?: return
        val keep = settings.requestDays
        onWorker {
            RequestDb.open(dir).use { db -> RequestDb.compact(db, now, keep) }
        }
    }

    /**
     * Starts the clock when one is needed, and says nothing when the answer has not changed.
     *
     * The service is what keeps the promise "it must never stop until the operator removes it"; the
     * screen's own tick only runs while somebody is looking. So the app asks the question wherever
     * the sessions are read — on every tick, on every refresh — and [AgentService.apply] decides.
     */
    private fun applyService(sessions: List<AgentSession>) {
        val wanted = sessions.any { it.running || it.needsYou }
        if (wanted == serviceWanted) return
        serviceWanted = wanted
        AgentService.apply(getApplication(), sessions)
    }

    /**
     * Builds the runner for the account that is signed in now.
     *
     * Rebuilt when the account changes, because the projects, the record and the model are all the
     * account's — a runner over the previous account would poll somebody else's folders. The old
     * one is closed, so its thread goes with it.
     */
    private fun makeRunner(): AgentRunner? {
        val ws = workspace ?: return null
        if (git == null) return null
        runner?.close()
        val fresh = AgentWiring.runnerFor(
            app = getApplication(),
            rootDir = accountsRoot,
            credentials = credentials,
            settings = settings,
            door = { active -> doorFor(active) },
            notify = { session -> agentNotifier?.invoke(session) },
            changed = { sessions ->
                main.post {
                    agentSessions = sessions
                    // A poll can change what the box shows, and the person may be reading it. The
                    // rows come from the record the same way every other refresh gets them: one
                    // path from the file to the screen rather than two.
                    chatProject?.let { project ->
                        val dir = ws.projectDir(project)
                        if (dir.isDirectory) applyChat(AgentLog.open(dir).rows(), follow = true)
                    }
                }
            },
        )
        runner = fresh
        return fresh
    }

    /** Reads a session's own file and keeps the observable copy in step. */
    private fun putSession(session: AgentSession) {
        val ws = workspace ?: return
        AgentSessions(ws.accountDir).put(session)
        agentSessions = agentSessions.filterNot { it.project == session.project } + session
        // **The service follows the sessions, always.** One call, from the one place a session is
        // written, so no path can switch an agent on and leave the phone without a clock.
        applyService(agentSessions)
        Trace.note("agent-state", "${session.project} · ${session.label()}")
        // **Only a session that just started needing somebody speaks.** Otherwise every reminder
        // tick would also post here, and the reminder count would never leave the first one.
        if (session.needsYou && session.reminders == 0) agentNotifier?.invoke(session)
    }

    /**
     * The session's own state, moved on by how a turn ended.
     *
     * The transition itself is [Agent.End.after], because the timer makes it too — see
     * [AgentRunner]. What is here is the screen's half: writing it down and redrawing.
     */
    private fun applyEnd(project: String, end: Agent.End, now: Long) {
        val current = sessionOf(project) ?: return
        // **The window in force for this turn**, not the one in Settings now: a person changing it
        // mid-turn should not have the turn it started with reinterpreted.
        val next = end.onto(current, now, reconnectMs = settings.reconnectMs)
        if (next != current) putSession(next)
    }

    /** The door for [model]: the test's, when one is set, and the pinned endpoint otherwise. */
    private fun doorFor(model: ActiveModel): Door = (chatDoor ?: { active ->
        OpenRouterDoor(
            key = { activeLogin?.let { credentials.modelKey(it) } },
            keyState = { activeLogin?.let { credentials.modelKeyState(it) } ?: KeyState.NONE },
            model = active.model,
            provider = active.provider,
            // **The model's own endpoints, in the order the person arranged them.** This is the
            // fallback list and nothing else uses it: `provider` above is still the pin, so the
            // first attempt is always the endpoint they selected. A failure the account owns -
            // an empty balance, a refused key - never walks this list; see `classify`.
            fallbacks = modelConfig.providersOf(active.model),
            effort = active.effort,
            // **The person's numbers, not the app's.** A slow network is a fact only they know, and
            // a timeout that is too short turns a long turn into a ladder of retries that never let
            // it finish. Three of them now: the ping still has one wait, because it is not streamed
            // and reads no answer; a turn has the ack and the gap, which are the two silences
            // streaming made different.
            // One number for "nothing has come back yet", on both transports. See AgentWiring.
            timeoutMs = settings.responseMs,
            responseMs = settings.responseMs,
            // **Every response is written down, whether or not anybody is watching.** The log is
            // not the footer: the footer stops when the screen does, and the requests that matter
            // most are the ones made while the phone is in a pocket. `ws` is the account whose
            // system project holds the files, and it is read at write time so a response landing
            // after a sign-out cannot be filed under the wrong account.
            onStats = { stats ->
                workspace?.let { live ->
                    RequestDb.write(
                        live.accountDir,
                        System.currentTimeMillis(),
                        stats,
                        settings.requestDays,
                    )
                }
            },
        ).also { door ->
            // **The footer's feed, and only while somebody is looking.** The listener is set where
            // the door is built, so a turn run for the background draws nothing and costs nothing;
            // and it is handed the stream itself rather than a number, so the count it shows is
            // always the one that just arrived.
            door.listener = { stream, elapsed -> chatLive = stream.shape(elapsed) }
        }
    })(model)

    /**
     * Hands a freshly read conversation to the screen.
     *
     * **There is no "which turn" any more.** The box drew one turn at a time and kept a position so
     * a re-read would not pull a person away from the turn they were reading; a list of bubbles
     * scrolls, so the position is the scroll's, and the screen keeps the one thing that *is* a
     * position — which bubble is open — keyed by the row's id rather than by a count.
     */
    private fun applyChat(rows: List<AgentLog.Row>, follow: Boolean) {
        chatBox = AgentChat.box(rows)
        chatGeneration++
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
                    // **Not announced.** The screen this closes onto already says who is
                    // signed in, in the same words — a toast repeating it makes a person
                    // check whether something else happened. A sign-in that worked is
                    // visible; only one that did not gets a sentence.
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
                onDone(true)
            }
        }
    }

    /** Signs an account in, or adds it when it is new, and makes it active. */
    fun signIn(login: String, token: String, commitName: String, commitEmail: String) {
        // **Adopt BEFORE saving, and the order is load-bearing.** `accounts.save` creates the
        // account's folder, and `adoptProvisional` refuses when that folder already exists - so
        // saving first would leave the provisional content behind for ever, on the one occasion
        // the person most expects it to come with them.
        //
        // **What moves is the whole workspace**, by one rename: the local projects made before
        // signing in become this account's projects, and their git repositories, the system
        // project and the request history go with them because they were all inside the same
        // folder. Nothing is copied, so a failure leaves everything on the provisional side.
        val adopted = accounts.adoptProvisional(login)
        credentials.setToken(login, token)
        accounts.save(
            Account(
                login = login,
                commitName = commitName.ifBlank { login },
                commitEmail = commitEmail.ifBlank { noreplyEmail(login) },
            )
        )
        credentials.activeLogin = login
        activeLogin = login
        path = emptyList()
        if (adopted) {
            // Said once, because a person who had projects before signing in should be told they
            // came along rather than discovering it - and if they did NOT come along (an account
            // of that name already had a folder) this is the only signal that something was left.
            Trace.note("adopted-provisional", login)
        }
        refresh()
    }

    /** Makes a stored account active. */
    fun switchTo(login: String) {
        credentials.activeLogin = login
        activeLogin = login
        // One log, not one per account: a switch is a thing that happened in this
        // session, and splitting the stream would lose the line before it.
        Trace.note("switched-account", login)
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
        // **The provisional account is the floor.** Removing the last real account leaves `~local`,
        // which is a workspace rather than an absence - so the browser still lists local projects
        // and the system project still has its README. It is made here rather than assumed, because
        // this is the moment it can have been adopted away by a sign-in and not yet remade.
        val next = accounts.accounts().map { it.login }
            .firstOrNull { it != login && !accounts.isProvisional(it) }
            ?: credentials.logins().firstOrNull { accounts.exists(it) && it != login }
            ?: accounts.ensureProvisional().login
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
        // **One name for one element.** The editor's button and the row's menu item
        // open the same screen for the same file; giving them two names made a log
        // where "history" and "history from editor" could not be compared, and the
        // screen is in the state line anyway.
        Trace.tap("history", file.name)
        historyPath = workspace?.relative(file)
        historyIsFolder = file.isDirectory
        historyGeneration++
    }

    fun closeHistory() {
        // **One recorder for one press.** The screen recorded this too, so every close
        // appeared twice in the same millisecond — a variant with the file's name and one
        // without — and, since neither was answered, two unanswered taps for one press.
        // Recorded here instead, because every way of closing goes through this method:
        // the back arrow, the back gesture, and choosing a version.
        Trace.tap("close history", historyPath?.lastOrNull().orEmpty())
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
    /**
     * Reads the request log on the worker and hands the rows back on the main thread.
     *
     * **Why this cannot stay in the screen.** The stats table used to call `RequestStats.read` from
     * inside a `remember`, which runs during composition — so the whole log was parsed on the main
     * thread before the first frame drew. At a few hundred rows nobody notices; at ten thousand
     * requests a day a year of history is millions of lines, and the screen would be a frozen
     * window for as long as the disk took. This is the same arrangement `requestVersions` and
     * every other disk read here uses: the work goes to the one worker, and the result is posted
     * back.
     *
     * [onDone] is called on the main thread, so the caller may assign Compose state in it.
     */
    /** The open history, so the one this view model opened is the one it closes. */
    private var openHistory: RequestDb.History? = null

    fun readRequestStats(onDone: (RequestDb.History) -> Unit) {
        val dir = workspace?.accountDir ?: return
        onWorker {
            // **Opened on the worker and handed back open.** The screen computes a total per
            // interval and the pickers move, so opening per draw would be a database open per pick.
            //
            // **This is the owner, so this is what closes it.** The previous handle is closed here
            // rather than in the screen: a screen can be re-composed, previewed, or drawn twice in
            // one test, and a handle closed by the wrong owner surfaces as an exception from an
            // unrelated query. Closing is idempotent, so a handle already closed elsewhere is fine.
            val history = RequestDb.read(dir)
            val previous = openHistory
            openHistory = history
            previous?.close()
            main.post { onDone(history) }
        }
    }

    /** Releases the history when the app is done with it - on sign-out, or when the screen goes. */
    fun closeRequestStats() {
        val previous = openHistory
        openHistory = null
        previous?.close()
    }

    fun requestVersions(onDone: (List<Pair<GitService.CommitInfo, String?>>) -> Unit) {
        onWorker {
            val found = versions()
            main.post {
                onDone(found)
                historyLoaded++
            }
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
        // **One repository answers now.** Every file below a project is in that project's
        // repository, and the only things outside a project are the app's own — which have no
        // history of their own to show.
        val project = ws.projectAt(target) ?: return emptyList()
        val g = git ?: return emptyList()
        return g.versions(project, target.drop(1).joinToString("/"))
    }

    /**
     * The files that used to be in [dir] and are not there now, newest first.
     *
     * Deleting a file is the one change with no way back from the folder it was in:
     * the row is gone, so there is nothing to open a history on, and the version
     * that holds it is a version of a folder nobody is looking at. Hence this list,
     * which the add button offers beside the other ways of getting a file in.
     *
     * **Its own project's repository answers**, because that is the only history there is: a
     * folder outside a project is the app's own, and the app does not offer its own state back.
     */
    fun deletedIn(dir: File): List<Gone> {
        val ws = workspace ?: return emptyList()
        val path = ws.relative(dir) ?: return emptyList()
        val project = ws.projectOf(dir) ?: return emptyList()
        val repo = git
        // The path as the repository names it: from the project's own folder, which is the root
        // of its history.
        val base = path.drop(1).joinToString("/")

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
        val commits = repo?.log(project, null, DELETED_LOOKBACK).orEmpty()
        // Newest first, so the first commit that holds a name is the version to
        // bring back: the file as it last was, not as it was years ago.
        val found = LinkedHashMap<String, Gone>()
        for (commit in commits) {
            val held = repo?.paths(project, base.ifEmpty { null }, commit.id).orEmpty()
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
        val project = ws.projectOf(dir) ?: run {
            message = "${dir.name} is not a project, so there is no history to bring anything back from"
            return false
        }
        val repo = git
        val under = path.drop(1) + gone.name.split("/")
        fun bytesAt(name: String): ByteArray? = repo?.bytesAt(project, name, gone.revision)

        val written = mutableListOf<File>()
        if (gone.isFolder) {
            // Every file the folder held at that version. An empty folder was never
            // in git, so it cannot be brought back — and an empty folder is not a
            // thing anyone lost.
            val prefix = under.joinToString("/")
            val held = repo?.paths(project, prefix, gone.revision).orEmpty()
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
        // **The project records it, and there is only one project it can be.** The files
        // were written into a folder of one repository, so its commit is the one that matters.
        commitProject(project, what)
        historyGeneration++
        return true
    }

    /** Puts an old version back into the file. */
    fun restoreVersion(revisionId: String) {
        Trace.tap("restore version", revisionId.take(7))
        val ws = workspace ?: return
        val target = historyPath ?: return
        // A restore writes, so a lock refuses it. The history stays open and says so:
        // the version list is exactly where a person finds out that they froze this file.
        if (ws.lockProblem(ws.fileAt(target)) != null) {
            Trace.failure("restore version", "locked")
            message = "${target.last()} is locked, so nothing was put back"
            return
        }
        val project = ws.projectAt(target)
        val ok = project != null &&
            git?.restore(project, target.drop(1).joinToString("/"), revisionId)?.isSuccess == true

        // **Only a failure is announced on the screen.** A restore replaces the text the
        // person is looking at, so a message saying "restored that version" repeats what
        // they can see — and it then lingered on the way out of the editor, which is what
        // made it feel like a pop-up about nothing. The *trace* still answers, because a
        // tap with no line after it is the one thing the log exists to find: this tap sat
        // unanswered until the next back press claimed it, which read as though closing the
        // history had answered a restore.
        if (!ok) {
            message = "Could not restore it"
            Trace.failure(
                "Could not restore ${target.lastOrNull()} from ${revisionId.take(7)}",
                "restore version",
            )
        } else {
            Trace.said("Restored ${target.lastOrNull()} from ${revisionId.take(7)}")
        }
        if (ok) {
            refresh()
            // A restore changes what is on disk, so it is itself a version: the history must be
            // able to undo it, and an undo that cannot be undone is not what "any state can be
            // rolled back" means. It is committed into the project's own repository, which is the
            // only history there is — left uncommitted it would say "1 changed" until the next
            // sync, and the version it was put back to would not be in the history it came from.
            commitProject(project!!, "Restored an older version")
            // The version the screen was showing is now a new version at the top of the list, so
            // the list the screen is holding is out of date.
            historyGeneration++
        }
    }

    companion object {

        /** The reason a turn is cut short by the operator's own words. */
        const val INTERRUPTED = "the operator spoke"

        /** The login of the test account a debug build can make. */
        const val TEST_ACCOUNT = "test-account"

        val DEFAULT_AUTHOR = GitAuthor("Muffy World", "muffyworld365@gmail.com")

        /**
         * Who commits while the provisional account is active.
         *
         * **A local address, because there is no GitHub account behind it.** `.local` is reserved
         * for exactly this - an identity that names no remote - and it cannot be confused with a
         * real address by anyone reading a commit log or a `git blame` after the projects are
         * adopted into a signed-in account.
         */
        val PROVISIONAL_AUTHOR = GitAuthor("Muffy Codes", "local@muffy.local")
        private const val SEARCH_LIMIT = 4000

        /** How many commits back the "what used to be here" list looks. */
        private const val DELETED_LOOKBACK = 50
        private const val SEARCH_RESULTS = 200
    }
}
