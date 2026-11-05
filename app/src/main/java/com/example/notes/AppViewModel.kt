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
    /**
     * The application, **kept** rather than used and dropped.
     *
     * It was a constructor parameter only, which was enough while the class needed nothing from it
     * after construction — and not enough once an index pass finished while nobody was looking and
     * had to say so in the tray. A `Context` on a view model is only safe as the application one,
     * which is what this is and why `Application` is the parameter's type.
     */
    private val app: Application,
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
    /**
     * Whether this phone runs the **app-start housekeeping** — the sweep and `git gc` that reclaim
     * what a prune and a pull leave behind.
     *
     * **A seam rather than a rule, and it exists because of what the work is.** Housekeeping is a
     * concern of *the app being opened*: a `gc` runs for seconds to minutes, it holds a repository
     * for the length of it, and it is exactly right once per launch. A view model built in a test is
     * not a launch — it is a screen somebody is inspecting — and hundreds of them in one shard, each
     * starting a thread that walks a temp folder, is work nobody asked for competing with the thing
     * the test is measuring.
     *
     * The default is `true`, so the app is unchanged and only a test says otherwise.
     */
    private val housekeeping: Boolean = true,
    /**
     * Where a background index pass gets its vectors.
     *
     * **The live one by default, and a test passes [Embedder.NONE].** Every commit asks
     * [IndexQueue] for a pass, so a test that commits anything would otherwise open a socket to
     * OpenRouter — with a twenty-second connect timeout — on a thread nothing is waiting for. That
     * is not a fake being substituted for a real thing; it is the real thing happening at the wrong
     * time, and it makes every such test a test of the network's availability.
     *
     * `NONE` is not a silent no-op: a pass that cannot embed says so in its note, which is the same
     * sentence a build with no credentials shows.
     *
     * **Null means "build the live one when a pass first asks"**, which is where the key is read —
     * `activeLogin` is not initialised until the object is, and a person may add their key after the
     * app opens.
     */
    private val embedder: Embedder? = null,
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

    /**
     * The four numbers the account screen shows, and their last two days.
     *
     * **Empty until the first read**, which is also the answer when there is no history yet - an
     * account with nothing recorded draws no card rather than four cells of dashes. The card is a
     * report on work done; before any work there is nothing to report, and four empty cells would
     * look like a failure.
     */
    var heartbeat: List<Heartbeat.Measure> by mutableStateOf(emptyList())
        private set

    /** The paths that are pinned, as paths from the account root. */
    var pins: Set<String> by mutableStateOf(emptySet())
        private set

    /** The paths that are locked against editing, as paths from the account root. */
    var lockedPaths: Set<String> by mutableStateOf(emptySet())
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

    /**
     * Every path git ignores in the project being browsed, keyed by absolute path.
     *
     * **Asked of git, not of a flag file.** `.gitignore` is the only authority on what is ignored —
     * a pattern the app never wrote (`build/`, `*.log`) answers honestly here, and a second list
     * kept by the app would be a second answer that could disagree with the one that decides what is
     * sent. It is computed when the project is opened and after any change to an ignore, and read by
     * the row so the mark and the truth cannot drift.
     */
    var ignoredPaths: Map<String, Boolean> by mutableStateOf(emptyMap())
        private set

    /** Re-reads the ignore state of [project], for the rows that draw it. */
    fun refreshIgnored(project: String) {
        val ws = workspace ?: return
        val dir = ws.projectDir(project)
        ignoredPaths = git?.open(project)?.use { repo ->
            GitService.ignoredPaths(repo).associate { rel ->
                File(dir, rel).absolutePath to true
            }
        } ?: emptyMap()
    }

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
    fun githubFor(login: String?): GitHub? = Companion.githubFor(login, accounts, testAccountsAllowed)

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
        get() = workspace?.let { ws -> projectGit(ws) }

    /**
     * A [ProjectGit] that asks for an index pass, wired to this account's key and model file.
     *
     * **The editor's path needs its own wiring**, because the agent may never have been switched on:
     * [AgentWiring] configures the same queue, and either being first is enough — `configure` is
     * idempotent and both describe the same account, key and config. Doing it here, beside the
     * construction, is what keeps "who indexes" from being a thing a person has to turn on.
     */
    private fun projectGit(ws: Workspace): ProjectGit = ProjectGit(
        workspace = ws,
        tokenProvider = { activeLogin?.let { credentials.token(it) } },
        authorProvider = { author },
        index = indexer(ws),
    )

    /**
     * The provider a `ProjectGit` asks for an index pass through — **and the wiring that makes the
     * ask mean something.**
     *
     * The two calls are one function on purpose: every place that hands out a `ProjectGit` gets a
     * queue that has been told where the account is, so there is no arrangement in which a commit
     * asks a queue that was never configured. Calling it repeatedly is free.
     */
    private fun indexer(ws: Workspace): (String, String) -> Unit {
        IndexQueue.configure(
            ws = ws,
            // **Built here rather than in the constructor**, because the key is read from
            // `activeLogin`, which is not initialised until the object is — and because reading it
            // per pass is right anyway: a person may add their key after the app opens, and the next
            // pass should find it. A test passes [Embedder.NONE] and never reaches the network.
            embedder = embedder ?: Embedder.openRouter(
                key = { activeLogin?.let { credentials.modelKey(it) } },
            ),
            models = { ModelConfigFile.load(ws.accountDir).getOrNull() },
            context = app,
        )
        return { project, why -> IndexQueue.want(project, why) }
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
        // **The introduction is opened by `openOnboarding` and not here.** A property declared
        // below this block is not initialised yet when `init` runs, and `editorSession` is one of
        // them - writing to it from here is a compile error, which is the compiler saying the same
        // thing. The call is posted instead, so it happens once everything above and below has been
        // built.
        main.post { openOnboarding() }
        // **The housekeeping, after the screen is up.** A prune moves a ref and a pull replaces the
        // history; both strand objects that nothing has ever deleted, because `gc` is proportional to
        // the whole repository and has no place inside a save. It runs here — app start, daemon
        // thread, only for a project that said something is waiting — and it is posted rather than
        // called so that nothing above it waits for a `gc`.
        if (housekeeping) main.post { runHousekeeping() }
    }

    /**
     * Runs [Maintenance] in the background, for every project that asked for it.
     *
     * **Guarded by the same "is an agent on" answer the runner uses**, because a collection holding a
     * repository for a minute would stall the next commit an agent makes. A skipped project keeps its
     * marker, so the next start tries again — which is the same rule the pass itself follows when a
     * lock is taken.
     */
    private fun runHousekeeping() {
        val ws = workspace ?: return
        // **No thread unless something is waiting.** A start where nothing has been pruned or pulled
        // is the ordinary start, and spawning a worker for it costs a thread and a file read on every
        // construction — which in a test shard is hundreds of them, all competing with whatever the
        // shard is actually measuring.
        val any = (ws.accountDir.listFiles() ?: emptyArray())
            .any { it.isDirectory && !ws.isHidden(it.name) && Maintenance.waiting(it) }
        if (!any) return
        // **The account it was started for, named.** A `gc` can run for a minute, and an account
        // deleted while it does would be re-created by nothing here — but the log row a finished pass
        // writes would be written into a folder the person removed, which is a deleted account coming
        // back in the smallest possible form. So the folder is checked before the pass writes
        // anything, and the whole thing stops if the account went away.
        val account = ws.accountDir
        Thread({
            try {
                if (!account.isDirectory) return@Thread
                val sessions = AgentSessions(account).read()
                val done = Maintenance.run(
                    ws = ws,
                    // **The account can go away mid-pass.** A person can delete it while a `gc` is
                    // running, and the log row a finished pass writes would be written into a folder
                    // they removed — a deleted account coming back in the smallest possible form.
                    alive = { account.isDirectory },
                    busy = { project ->
                        sessions.any { it.project == project && it.running }
                    },
                )
                // **Only what did something refreshes the screen.** A start where nothing was
                // waiting is the normal case; a project whose sweep removed rows has just changed
                // what a search can answer, so the screens are asked again. The log row is written by
                // the pass itself, per project, so nothing is lost by not repeating it here.
                if (done.any { it.rows > 0 }) main.post { refresh() }
            } catch (e: Throwable) {
                // Housekeeping that could not run leaves its markers for the next start.
            }
        }, "maintenance").apply { isDaemon = true }.start()
    }

    /**
     * **True while a person is reading the pages they must read before the app opens.**
     *
     * Onboarding replaces the first-run README, which was a wall of text where a page with one idea
     * would do — the operator's own correction: *"onboarding screens just show one capability set at
     * a time instead of a wall of text"*. It is a folder of Markdown pages in the system project, so
     * it is read with the same renderer, the same swipe gesture and the same `app:` links as any
     * other document.
     *
     * **A state rather than a screen**, because it changes what the app will let a person do, and
     * that is the view model's business: the editor hides Exit while this is true, and the browser
     * refuses to navigate away.
     */
    var onboarding: Boolean by mutableStateOf(false)
        private set

    /**
     * Opens the onboarding pages, **wherever the person got to**.
     *
     * **Resuming rather than restarting.** A person who quit on page four should not swipe past three
     * they have read; the position is a number in settings, and finishing is that number reaching
     * the count. There is no separate "done" flag, because two records that must agree are two
     * records that eventually will not.
     *
     * **`refresh()` has already run**, so the folder and its pages exist by the time this is posted.
     * If they somehow do not, nothing is recorded and the next launch tries again — the failure is
     * "onboarding is late", never "onboarding is skipped".
     */
    private fun openOnboarding() {
        val ws = workspace ?: return
        val pages = Onboarding.pages()
        if (pages.isEmpty()) return
        if (settings.onboardingDone(pages.size)) return
        val dir = File(ws.projectDir(Workspace.SYSTEM_PROJECT), Onboarding.DIR)
        val at = settings.onboardingAt.coerceIn(0, pages.lastIndex)
        val page = File(dir, pages[at].name)
        if (!page.isFile) return
        onboarding = true
        Trace.note("onboarding", "page ${at + 1} of ${pages.size}")
        editingPath = ws.relative(page)
        editorSession++
    }

    /**
     * Records that the person has read the page they are on, and opens the next one.
     *
     * **Called by the swipe**, which is why it takes no argument: the page being left is the one that
     * was reached, so reaching page *n* means pages 0..*n* have been seen. That is also why a person
     * who swipes back and forth does not lose ground — the record only moves forward.
     */
    fun onboardingReached(index: Int) {
        if (!onboarding) return
        val pages = Onboarding.pages()
        val bounded = index.coerceIn(0, pages.lastIndex)
        if (bounded > settings.onboardingAt) settings.markOnboardingAt(bounded)
        Trace.note("onboarding", "reached ${bounded + 1} of ${pages.size}")
    }

    /**
     * **Whether onboarding has done the thing it exists to do**: the account connected to GitHub and
     * to a model.
     *
     * This is the question, and it replaces "has the person reached the last page" — the operator's
     * correction: *"after providing both signins onboarding should close on its own"*. Reading six
     * pages is not the goal; connecting is. A person who connected on page three should not have to
     * swipe through three more to be let out.
     *
     * **GitHub is [signedIn]**, not "an account exists": the provisional `~local` workspace is an
     * account and is signed in to nothing.
     *
     * **The model key is [hasModelKey]**, which is `READABLE` — a key that is stored but locked
     * behind the phone's own lock is not a key the app can use, and closing onboarding on it would
     * leave a person on a chat screen that cannot answer.
     */
    val onboardingConnected: Boolean get() = signedIn && hasModelKey

    /**
     * **Closes onboarding once both connections are made.**
     *
     * Called from wherever a connection lands, and idempotent: a second call after onboarding has
     * already ended does nothing. It does **not** require having reached the last page, because the
     * last page is where the connections are *offered*, not where they must be made.
     */
    fun closeOnboardingIfConnected() = finishOnboarding()

    /**
     * **Finishes onboarding — and there is exactly one way in: both connections made.**
     *
     * This is a **gate**, and it is a settled decision rather than a defect. `4220da6` records it:
     * *"cannot be left early, and that is enforced in two places on purpose"* — a disabled control
     * is not a rule, so `closeEditor` refuses as well.
     *
     * **It used to take the last page as its condition, which was the wrong question.** Reaching
     * page six is reading; connecting is the thing the app needs. So the condition is
     * [onboardingConnected], and reading every page without connecting does not open the door.
     *
     * **A person whose provider will not cooperate is not trapped**, because the key field is inside
     * both connect windows: it is the second way to make the same connection, not a way around it.
     * (`app:use-token` still exists as a verb for a cloned page that names it, but the page no longer
     * offers it as a third link.)
     */
    fun finishOnboarding() {
        if (!onboarding) return
        if (!onboardingConnected) {
            Trace.note("onboarding", "finish refused, not connected")
            return
        }
        // **Read before the flag is cleared**, and that ordering is the whole subtlety:
        // `onboardingIndexOf` answers -1 once `onboarding` is false, so asking it afterwards said
        // "not an onboarding page" about the page it was on. The first version of this fix cleared
        // the flag first and closed nothing, and the test caught it.
        val shown = editingPath?.let { workspace?.fileAt(it) }
        val wasOnPage = shown != null && onboardingIndexOf(shown) >= 0
        settings.markOnboardingAt(Onboarding.pages().size)
        onboarding = false
        Trace.note("onboarding", "finished")
        // **And it leaves the last page**, which is the report this exists for: *"after both
        // connections are done the onboarding screen should close itself - and you automatically
        // enter account screen"*.
        //
        // `onboarding = false` alone only stops the page *being* an onboarding page. The editor was
        // still open on `06-connect.md`, so the person stayed on the CONNECT page with the two links
        // they had just used — a screen that had done its job and would not leave. Worse, the guard
        // in `closeEditor` reads `onboarding`, so it had already stopped refusing: the page was now
        // an ordinary file with no reason to be on screen and no obvious way off it.
        //
        // **Closed here rather than in the caller**, because there are two callers (`signIn` and
        // `saveModelKey`) and a copy of this in each is a copy that can drift. This is the one
        // moment onboarding ends, so it is the one place that has to tidy up after it.
        // **Only the page it was actually on**, not any file: a person who opened an ordinary file
        // during onboarding is not owed a closed editor when the connections land.
        if (wasOnPage) closeEditor()
    }

    /** The index of the page the editor is showing, or -1 when it is not an onboarding page. */
    fun onboardingIndexOf(file: File): Int {
        if (!onboarding) return -1
        val pages = Onboarding.pages()
        return pages.indexOfFirst { it.name == file.name }
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
        // **No switch, so no branch that can leave the app silent.** `liveTrace` is `DEBUG`, and this
        // is the only place it is read: a debug build always writes, a release build never does.
        Trace.attach(if (settings.liveTrace) DownloadsSink(getApplication()) else null)
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
        // **Nothing lands inside the system project**, which is a whole-path question rather than a
        // name one: `system/wiki/` and `system/muffycodes_data/prompts/` are folders the app writes
        // and a person does not, and every one of them is named something ordinary. Report: "all
        // system project subtree is system locked — it is a readonly folder that is updated by the
        // app itself, there is no place for user generated content there."
        ws.relative(dir)?.let { if (Workspace.isSystemPath(ws.pathString(it))) return false }
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
        agentSessions = AgentSessions(ws.accountDir).read()
        refreshHeartbeat(ws)
        // The first refresh of a new process is where a session that survived a reboot gets its
        // clock back: the answer is read here, but nothing else in a refresh would start it.
        applyService(agentSessions)
        readModelConfig(ws)
        // A selection is a set of paths, and a path that is no longer there cannot
        // be selected: an item moved or deleted out from under the selection would
        // otherwise be acted on again by the next press.
        selected = selected.filterTo(mutableSetOf()) { ws.fileAt(it.split("/")).exists() }
        refreshChanges()
        // **The ignore marks are re-read on every refresh**, for the reason the lock had to be: a
        // value Compose cannot see does not redraw the row, so an icon read from a call rather than
        // from state would stay wrong after the tap that changed it.
        currentProject?.let(::refreshIgnored)
        // **One funnel for "the screen changed".** Every change this app makes ends in
        // `refresh()`, so this is the one place that can answer a tap without forty
        // call sites having to remember to. A change that leaves the signature alone
        // is not reported, because a tap answered by "nothing looks different" is not
        // an answer.
        val after = stateSignature()
        if (after != before) Trace.changed(after)
    }

    /**
     * The heartbeat, **read on the worker and published when it arrives**.
     *
     * **On the worker because this opens a database.** Three queries, an open, and a close - and
     * `refresh()` is called from thirty places, every one of them on the main thread. Reading here
     * directly would put a file open inside a frame, which is the exact mistake the screens it
     * replaced went out of their way to avoid.
     *
     * **The last good card stays until the new one is ready**, rather than being cleared first: an
     * empty card is the "nothing has happened yet" state, and flashing it between two reads would
     * make a working account look like it had lost its history.
     *
     * **Only on the account screen**, because the four cells describe the account as a whole - and
     * `refresh()` runs on every change, including inside a project where the card is not drawn.
     *
     * **A failure leaves the card as it is**, not blanked: a database that cannot be read has no new
     * numbers, and replacing a real card with nothing would report a failure as an absence.
     */
    private fun refreshHeartbeat(ws: Workspace) {
        if (path.isNotEmpty()) return
        // **Not for an account whose folder is gone.** `RequestDb.open` makes the folder it writes
        // into, so a read queued before a removal would recreate the account a moment after the
        // person deleted it - restored by a read nobody asked for. The removal refreshes on its way
        // out, which is exactly when this is true.
        if (!ws.accountDir.exists()) return
        // **One read at a time, and the newest request wins.** `refresh()` is called in bursts - a
        // tap, then the refresh it triggers, then the one the file listing triggers - so a queue of
        // reads would each open the database to compute nearly the same answer.
        //
        // **The pending flag is what makes dropping safe.** A request that arrives mid-read is
        // remembered rather than discarded, and the read that finishes runs one more. Without it a
        // refresh landing during a read would be lost, and the card would sit on numbers that no
        // longer match the log - which is the one thing it must never do.
        if (heartbeatReading) {
            heartbeatPending = true
            return
        }
        heartbeatReading = true
        onWorker {
            // **Started and finished, both recorded.** This is the one read that opens a database
            // from thirty call sites, so the question "did the card stall" has to be answerable from
            // a log written by a person holding the phone. A duration is what tells a slow device
            // from a slow database, and neither is visible in a card that simply looks old.
            val began = System.currentTimeMillis()
            val measures = try {
                // **Checked again on the worker**, because the removal can happen between this read
                // being queued and it running - which is the whole race: the queue does not care
                // what the state was when the request was made.
                if (!ws.accountDir.exists()) {
                    heartbeat
                } else {
                    RequestDb.read(ws.accountDir).use { Heartbeat.of(it, System.currentTimeMillis()) }
                }
            } catch (t: Throwable) {
                heartbeat
            }
            main.post {
                heartbeat = measures
                heartbeatReading = false
                // **Only when something was actually read**, so a card that is empty because the
                // person is inside a project - or has no history - does not fill the log with rows
                // about a read that was never going to happen.
                if (measures.isNotEmpty()) {
                    Trace.note("heartbeat", "${System.currentTimeMillis() - began} ms")
                }
                if (heartbeatPending) {
                    heartbeatPending = false
                    // Re-asked on the main thread, which is where `refresh()` asks it from, so the
                    // state it reads is the state the worker just published.
                    refreshHeartbeat(ws)
                }
            }
        }
    }

    /** Whether a heartbeat read is in flight, so a burst of refreshes asks once. */
    private var heartbeatReading = false

    /** Whether a refresh arrived mid-read and needs one more pass. */
    private var heartbeatPending = false

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
     * Chooses the model that turns text into vectors, **or the one that reorders results.**
     *
     * One function for both because they are the same act on the same shape of block — the operator's
     * instruction: *"generalize them to take catalogue path as an argument and implement for models,
     * embedding and rerank."* What differs is the third value, and it is passed rather than inferred:
     * `dimensions` is the embedding model's choice of size, and a rerank model has none.
     *
     * **The dimension is written with the model, not beside it in a second field**, for the reason
     * `contextLength` is: the index that is built later has no catalogue to ask, and a dimension read
     * from the wrong place is an index that has to be rebuilt.
     */
    fun choosePicked(kind: CatalogueKind, model: String, provider: String, dimensions: Int?) {
        val one = PickedModel(
            model = model,
            provider = provider,
            dimensions = if (kind == CatalogueKind.EMBEDDING) dimensions else null,
            providers = providersFor(kind, model),
        )
        // **A different embedding model is asked about before it happens**, because it is not a
        // preference: the vectors already on disk are in the old model's coordinate system, so every
        // project's index has to be thrown away and rebuilt. That costs minutes and tokens, so the
        // person is told what it costs and can say no — [pendingPicked] is the question, and the
        // screen draws it.
        //
        // **A different endpoint is not that question.** The provider is where the request goes, not
        // where the vectors come from, so moving a model to another endpoint keeps the index and is
        // applied at once.
        val current = modelConfig.embedding
        if (kind == CatalogueKind.EMBEDDING && current != null && current.space() != one.space()) {
            pendingPicked = one
            return
        }
        applyPicked(kind, one)
    }

    /**
     * The embedding model waiting for a person to agree to the rebuild, or null.
     *
     * **The question is state rather than a lambda in the dialog**, so the rule — *is this a new
     * vector space, and what does it cost* — lives in the app layer where a test can ask it.
     */
    var pendingPicked: PickedModel? by mutableStateOf(null)
        private set

    /** What the screen says before an embedding-model change. One sentence, and it is the truth. */
    val pendingPickedSaid: String
        get() {
            val one = pendingPicked ?: return ""
            val now = modelConfig.embedding
            return "Choosing ${one.model} at ${one.dimensions} dimensions throws away the index of " +
                "every project and rebuilds it in the background. That takes several minutes and " +
                "spends tokens on your key, and until it finishes a search answers with what is " +
                "already indexed.\n\n" +
                "The index on disk was built with ${now?.model ?: "another model"}. Only change this " +
                "if that endpoint has stopped answering — otherwise the index you have works."
        }

    /** Agreed: the choice is written, every project's index is emptied, and the queue rebuilds them. */
    fun confirmPicked() {
        val one = pendingPicked ?: return
        pendingPicked = null
        applyPicked(CatalogueKind.EMBEDDING, one)
        rebuildIndexes()
    }

    /** Refused: nothing changes, and the index that works is still the one in force. */
    fun cancelPicked() {
        pendingPicked = null
    }

    private fun applyPicked(kind: CatalogueKind, one: PickedModel) {
        val next = when (kind) {
            CatalogueKind.CHAT -> return
            CatalogueKind.EMBEDDING -> modelConfig.copy(embedding = one)
            CatalogueKind.RERANK -> modelConfig.copy(rerank = one)
        }
        writeModelConfig(next, "Chose ${one.model} on ${one.provider} for ${kind.label.lowercase()}")
    }

    /**
     * Empties every project's index and asks for each to be rebuilt.
     *
     * **Both halves in one place, and in this order.** The empty has to come first: a pass opens the
     * index, sees what it was built with, and refuses a model that differs — so asking first would
     * queue work that every pass would decline. Emptying also clears the record of which model built
     * it, which is what lets the new choice be declared ([SearchIndex.clear]).
     *
     * **One project at a time, on the worker**: each open is a database handle, and the queue
     * coalesces the asks anyway.
     */
    private fun rebuildIndexes() {
        val ws = workspace ?: return
        onWorker {
            val names = ws.projectNames()
            for (name in names) {
                try {
                    SearchIndex.open(ws.projectDir(name)).use { it.clear() }
                } catch (e: Throwable) {
                    // A project whose index cannot be emptied still gets a pass; that pass will say
                    // why in its own note rather than the whole rebuild stopping on one folder.
                }
            }
            for (name in names) IndexQueue.want(name, "the embedding model changed")
        }
    }

    /** The endpoints stored for [model] in [kind]'s block, so a choice keeps its provider list. */
    private fun providersFor(kind: CatalogueKind, model: String): List<String> = when (kind) {
        CatalogueKind.CHAT -> modelConfig.providersOf(model)
        CatalogueKind.EMBEDDING -> modelConfig.embedding
            ?.takeIf { it.model == model }?.providers.orEmpty()
        CatalogueKind.RERANK -> modelConfig.rerank
            ?.takeIf { it.model == model }?.providers.orEmpty()
    }

    /**
     * Adds a model to the embedding or rerank list — **the same act as adding a chat model**, which
     * is what "same structure as we already use" asks for.
     *
     * There is no list to add to in the file, because each of the two jobs has exactly one model
     * chosen: adding one *is* choosing it. So this exists for the picker's Add button, which asks
     * the catalogue whether the id is real before it becomes the choice.
     */
    fun addPicked(kind: CatalogueKind, id: String, provider: String): Result<Unit> {
        if (kind == CatalogueKind.CHAT) return Result.failure(IllegalArgumentException("not this one"))
        val clean = id.trim()
        if (clean.isEmpty()) return Result.failure(IllegalArgumentException("A model needs an id"))
        val tag = provider.trim()
        if (tag.isEmpty()) return Result.failure(IllegalArgumentException("A model needs a provider"))
        choosePicked(kind, clean, tag, kind.dimensions?.firstOrNull())
        return Result.success(Unit)
    }

    /** Forgets the embedding or rerank choice, so the feature says "not chosen" rather than guessing. */
    fun clearPicked(kind: CatalogueKind) {
        val next = when (kind) {
            CatalogueKind.CHAT -> return
            CatalogueKind.EMBEDDING -> modelConfig.copy(embedding = null)
            CatalogueKind.RERANK -> modelConfig.copy(rerank = null)
        }
        writeModelConfig(next, "Cleared the ${kind.label.lowercase()}")
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
                // **The second half of the way out.** If GitHub was already connected, this is the
                // connection that finishes onboarding — and the person is on a page offering the
                // sign-in, so they should not have to swipe anywhere to be let out.
                closeOnboardingIfConnected()
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

    // ---- links in a document ------------------------------------------------

    /**
     * A link a person pressed in a document, for the screen to act on.
     *
     * **The view model does not open a browser**, because it has no activity — and because the
     * screen is where "show this elsewhere" already happens. It publishes the address and lets
     * `MainActivity` do what it does for every other external address.
     */
    var linkToOpen: String? by mutableStateOf(null)
        private set

    /** Asks the screen to open [address] outside the app. */
    fun openLink(address: String) {
        Trace.tap("link", address.take(40))
        linkToOpen = address
    }

    /** Called by the screen once it has opened [linkToOpen], so it does not open twice. */
    fun linkOpened() {
        linkToOpen = null
    }

    /**
     * **What a link in [file] does when it is pressed.**
     *
     * Pulled out of the editor's composable so the rule is testable without a screen — and because
     * the rule is the app's, not the screen's. The editor calls this and nothing else.
     *
     * **`app:` is refused outside the system project.** A locked page is written by the app and
     * cannot be edited, so its links are the app's own. A *cloned* page is ordinary user text, and a
     * cloned page carrying `app:delete-account` would be a document that can act — the door this
     * feature must not open.
     */
    fun followLink(address: String, file: File) {
        when {
            address.startsWith("app:") -> {
                // **The file's own project**, which is the folder two levels up: accounts/<login>/
                // <project>/<file>. A page at the account root has no project and is therefore not
                // the system one.
                val project = file.parentFile?.parentFile?.name.orEmpty()
                if (Workspace.isSystem(project)) {
                    runLinkAction(address.removePrefix("app:"))
                } else {
                    Trace.note("link-refused", "app: outside the system project")
                }
            }
            address.startsWith("http") -> openLink(address)
            else -> Trace.note("link-ignored", "not a link the editor opens")
        }
    }

    /**
     * An `app:` link: an action a document asks the app to perform.
     *
     * **A small closed set, and that is the point.** A document that could name an arbitrary
     * function would be a document that can run the app — which is why this is a `when` over known
     * verbs rather than a registry. Unknown verbs are recorded and ignored, because a page written
     * for a newer app version must not break an older one.
     *
     * The caller has already checked that the document is one the app wrote (see
     * `EditorScreen`'s `onLink`): a cloned page is ordinary user text and must not be able to act.
     */
    fun runLinkAction(verb: String) {
        Trace.tap("link action", verb)
        when (verb) {
            // **The two connections onboarding ends on.** They open the same dialogs the settings
            // screen does, so a page is a way in rather than a second implementation.
            "connect-github" -> pendingConnection = Connection.GitHub
            "connect-openrouter" -> pendingConnection = Connection.OpenRouter
            // **The way past a sign-in that cannot succeed**, which is the reason this verb exists
            // at all: onboarding cannot be left, so a page that offers only OAuth would trap a
            // person whose provider is rate-limited or whose phone has no browser.
            "use-token" -> pendingConnection = Connection.Token
            else -> Trace.note("link-action-unknown", verb)
        }
    }

    /**
     * **A GitHub device-flow sign-in in progress**, or null.
     *
     * Three fields and no more: the code the person types, where they type it, and the sentence to
     * show if it ends badly. The polling itself is the worker's, and its interval is GitHub's.
     *
     * **It lives here rather than in the dialog** because the dialog can be dismissed and reopened,
     * and a flow that died with the composable would leave a code on screen that nobody is polling
     * for. The person can leave the dialog, come back, and the same code is still live.
     */
    data class Device(
        val code: String,
        val where: String,
        val failed: String? = null,
        /**
         * **The secret half of the pair**, and it is not shown anywhere.
         *
         * The person types [code]; the app polls with this one. They are issued together and one is
         * useless without the other, which is why they travel as one object rather than in two
         * places that could disagree about which sign-in they belong to.
         */
        val deviceCode: String = "",
    )

    var device by mutableStateOf<Device?>(null)
        private set

    /** A connection dialog a document asked for. */
    enum class Connection { GitHub, OpenRouter, Token }

    /** The connection dialog to show, or null. Cleared by the screen when it is answered. */
    var pendingConnection: Connection? by mutableStateOf(null)
        private set

    /** Called by the screen once the dialog has been shown. */
    fun connectionShown() {
        pendingConnection = null
    }

    // ---- OpenRouter sign-in -------------------------------------------------

    /**
     * The attempt in progress, if any.
     *
     * **Held in memory and nowhere else.** It is a verifier that is worth something for ten minutes
     * and nothing after that; writing it to disk would create a secret to keep for a secret that
     * expires on its own. If the app dies, the attempt dies with it and the person starts again —
     * which costs one press.
     */
    private var openRouterAttempt: OpenRouterAuth.Attempt? = null

    /** When [openRouterAttempt] stops being worth keeping, on the same clock as everything else. */
    private var openRouterExpires: Long = 0L

    /**
     * Begins an OpenRouter sign-in and answers the address to open.
     *
     * The exchange happens when the person comes back with a code; this only makes the attempt.
     */
    fun beginOpenRouterSignIn(): String {
        val attempt = OpenRouterAuth.begin()
        openRouterAttempt = attempt
        openRouterExpires = System.currentTimeMillis() + OpenRouterAuth.LIFETIME_MS
        Trace.note("openrouter-pkce", "begun")
        return attempt.url()
    }

    /**
     * Exchanges the code the person pasted for a key, and keeps it.
     *
     * **The same [saveModelKey] as the paste path**, deliberately: a key that arrived by PKCE and a
     * key that was typed are the same key, and two save paths would be two chances for one of them
     * to forget the read-back that makes a kept key trustworthy.
     *
     * @return null when the key is kept, or the sentence to show when it is not.
     */
    fun finishOpenRouterSignIn(
        code: String,
        // **The clock is a parameter so the expiry is testable without waiting ten minutes.** A
        // test that slept would be a test nobody runs; a test that could not reach the expiry would
        // leave the one branch that protects a stale verifier unexercised.
        now: Long = System.currentTimeMillis(),
        onDone: (String?) -> Unit,
    ) {
        val attempt = openRouterAttempt
        if (attempt == null || now > openRouterExpires) {
            openRouterAttempt = null
            onDone(
                "That sign-in has expired. A code lasts ten minutes — start again to get a new one.",
            )
            return
        }
        onWorker {
            val result = OpenRouterAuth.exchange(code, attempt.verifier)
            main.post {
                // **Spent either way.** A code is single use, so a failure means this attempt can
                // never succeed; keeping the verifier would only let a person retry a dead code and
                // read the same refusal twice.
                openRouterAttempt = null
                val key = result.getOrNull()
                if (key == null) {
                    Trace.note("openrouter-pkce", "refused")
                    onDone(result.exceptionOrNull()?.message ?: "That code did not work.")
                } else {
                    Trace.note("openrouter-pkce", "key minted")
                    onDone(saveModelKey(key))
                }
            }
        }
    }


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

    /**
     * The remote a project pushes to, or null when it has none.
     *
     * **The same answer the app acts on**, exposed rather than re-derived: a test that worked out a
     * project's repository from its folder name would be testing its own arithmetic, and the parse the
     * phone actually performs (`RepoRef.of`) is the thing worth asking about.
     */
    fun remoteUrlOf(project: String): String? = git?.remoteUrl(project)

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

    /**
     * Makes a picture: a blank PNG of [canvas]'s size, in the folder being looked at.
     *
     * **The same shape as [createFile]**, including the trace line and the "go to it" behaviour,
     * because to a person it is the same act: something new is in this folder and they want to be
     * looking at it. The difference is only what the file's body is.
     */
    fun createImage(name: String, canvas: Look.Canvas): Boolean {
        val ws = workspace ?: return false
        val dir = currentDir ?: return false
        val asked = if (name.trim().contains('.')) name.trim() else "${name.trim()}.png"
        val result = ws.createImage(dir, asked, canvas)
        val made = result.getOrNull()?.name ?: asked
        return afterChange(
            result,
            "Created $made at ${canvas.width}\u00d7${canvas.height}${namedFrom(asked, made)}",
            File(dir, made),
        )
    }

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
            // **What arrived is the person's.** They chose these files, so the agent must not
            // rewrite them unasked, and a binary the app cannot classify must not spend GitHub's
            // storage until the person says it should. Done here rather than inside `Imports`
            // because that object has no workspace — and a default that needs a workspace belongs
            // where the workspace is.
            result.getOrNull()?.made?.forEach { name ->
                workspace?.createdByOperator(File(dir, name))
            }
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
            // The same default as a single file: what the person imported is theirs, and what the
            // app cannot classify waits for them to say it should go to GitHub.
            result.getOrNull()?.made?.forEach { path ->
                workspace?.createdByOperator(File(dir, path))
            }
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
        // **Read from the workspace, not from this screen's copy of it.**
        //
        // `lockedPaths` is state for drawing, refreshed when the list is. Anything that locks a file
        // without going through this function — `createdByOperator`, which runs when a person makes a
        // file — writes the workspace and leaves the screen's copy stale until the next refresh. A
        // toggle reading the stale copy then *locks* a file that is already locked, and the row's
        // button does the opposite of what it says. Measured: the creation default made every file a
        // person created look unlocked to this function.
        val current = ws.locked()
        Trace.tap(if (key in current) "unlock" else "lock", file.name)
        val next = if (key in current) current - key else current + key
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
        val inside = insideOf(ws, file, path) ?: return
        // **The state is asked of git, not of a flag.** Whether a path is ignored is a question
        // `.gitignore` answers, and a second copy of that answer is a second thing that can be
        // wrong. The index is asked the same way for "is it tracked", which is the other half of
        // what the button changes.
        val ignored = git?.open(project)?.use { repo -> GitService.isIgnored(repo, inside) } ?: false
        setIgnored(file, project, inside, !ignored)
    }

    /**
     * The project-relative path of [file], as git writes it: folders keep a trailing slash.
     *
     * **Without the slash git still ignores the path**, but a person reading `.gitignore` cannot tell
     * a folder from a file, and the ignore file is a document they may read.
     */
    private fun insideOf(ws: Workspace, file: File, path: List<String>): String? {
        val inside = path.drop(1).joinToString("/").let { if (file.isDirectory) "$it/" else it }
        return inside.ifEmpty { null }
    }

    /**
     * Tracks [file], or ignores it — **one act, both directions.**
     *
     * **This is classic gitignore and nothing else.** An ignored path is in `.gitignore` *and* out of
     * the index, so it is not committed and not sent. The other mechanism this app had — leaving a
     * file tracked and deriving a tree without it — needed a force-push to deliver, because the
     * commit being sent was on no local branch. That is gone: what is on the phone's `main` and what
     * is on GitHub's are now the same history, which is what lets the agent push at all.
     *
     * The four sentences are four different situations, and the dangerous one says so every time:
     * ignoring stops a path being *sent from now on* and does not take back what was sent. The
     * commit that carried it is still in the remote's history and `git cat-file` still prints it
     * after the file is gone from a fresh clone. Measured, not assumed.
     */
    private fun setIgnored(file: File, project: String, inside: String, ignore: Boolean) {
        val ws = workspace ?: return
        Trace.tap(if (ignore) "ignore" else "track", inside)
        val ignoreFile = File(ws.projectDir(project), Workspace.IGNORE)
        val current = if (ignoreFile.isFile) ws.read(ignoreFile) else ""
        val lines = current.lineSequence().toMutableList()
        val has = lines.any { it.trim() == inside }

        val text = if (ignore) {
            if (has) current else buildString {
                append(current)
                if (current.isNotEmpty() && !current.endsWith("\n")) append("\n")
                appendLine(inside)
            }
        } else {
            // **Every copy goes**, not just the first: a file appended to twice has two lines, and
            // removing one leaves it ignored while the button says it is tracked.
            //
            // **Rebuilt from the lines that stay, and exactly one trailing blank dropped.**
            // `lineSequence()` splits a final newline into a trailing empty string, so joining
            // naively would grow the file by a blank line every time a path was tracked again — and
            // a `.gitignore` that grows on each tap is a file the person did not write. Only *one*
            // is dropped: blank lines the person put there themselves are theirs.
            lines.filterNot { it.trim() == inside }
                .let { if (it.isNotEmpty() && it.last().isEmpty()) it.dropLast(1) else it }
                .joinToString("\n")
                .let { if (it.isEmpty()) "" else "$it\n" }
        }
        if (ignore != has || text != current) {
            // **Written through the funnel, so a locked project's `.gitignore` is still refused** —
            // and then said, rather than looking like a tap that did nothing.
            if (!ws.write(ignoreFile, text)) {
                Trace.failure(if (ignore) "ignore" else "track", "refused")
                message = "Could not change .gitignore here"
                return
            }
        }

        // **The line alone does not stop a file that is already in the repository.** `.gitignore` is
        // an *untracked* filter: git keeps committing a path it already knows, and every file a
        // person has saved is already tracked because a save commits. So ignoring is completed by
        // taking the path out of the index, and tracking is completed by putting it back.
        var stillTracked = false
        var wasPushed = false
        git?.open(project)?.use { repo ->
            if (ignore) {
                // Asked before the untrack, because untracking is what makes it untrue.
                wasPushed = GitService.wasPushed(repo, inside)
                stillTracked = !GitService.untrack(repo, inside)
            } else {
                // **Added, not restored from a version.** The file on disk is the truth; a person who
                // tracked it again is saying "send this", and what is on the phone is what to send.
                GitService.track(repo, inside)
                // **And a binary gets an LFS line before it is ever committed.** Without this the
                // choice to track a `.pdf` or a `.zip` costs the person their repository: a tracked
                // binary with no attribute is committed whole, into ordinary history, and stays in
                // every clone for ever — this app never rewrites history. Written before the commit
                // below, so the attribute and the file arrive in the same commit and the pointer
                // rule is in force from the first byte.
                //
                // Only a file the app cannot read as text needs it; a picture already has its line
                // from `ensureLfsAttributes`, and a text file must not be turned into a pointer.
                val extension = file.name.substringAfterLast('.', "")
                if (extension.isNotEmpty() && Workspace.kindOfPath(file.name) == EntryKind.UNKNOWN) {
                    GitService.ensureLfsAttribute(ws.projectDir(project), extension)
                }
            }
        }

        message = when {
            ignore && stillTracked -> "Ignoring $inside, but it is still in the repository"
            ignore && wasPushed ->
                "Ignoring $inside. Its earlier versions stay on GitHub — ignoring stops it being " +
                    "sent from now on; it does not take back what was sent."
            ignore -> "Ignoring $inside"
            else -> "Tracking $inside again — it will be sent on the next push"
        }
        refresh()
        commitProject(project, if (ignore) "Ignored $inside" else "Tracked $inside")
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
    /**
     * Copies [file] to the phone's Downloads — the ⋮ item, and the same act as the `upload` tool.
     *
     * **Report 23**: *"I should be able to clone/move to the downloads - add this as a choice"*. It
     * goes through `Export.copyToDownloads`, which is exactly what `MediaStoreDownloads.write` calls,
     * so a person's copy and an agent's are the same bytes by the same route — two implementations
     * would drift in the ways nobody notices until a file arrives wrong.
     *
     * **The outcome is a message either way.** A copy that failed silently is a person who believes
     * the file is on their phone and finds it missing at the moment they needed it.
     */
    fun copyToDownloads(file: File) {
        onWorker {
            val said = Export.copyToDownloads(getApplication(), file).fold(
                onSuccess = { it.said() },
                onFailure = { it.message ?: "${file.name} could not be put in Downloads" },
            )
            main.post { message = said }
        }
    }

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
        // **The lock list is re-read here, because a creation writes it below the screen's feet.**
        // `createdByOperator` runs inside `Workspace.createFile`, which is called before this — so
        // `refresh()` above already picked the new lock up. This line is the one that keeps it true
        // for any future path that changes a lock without calling `refresh()`: the row draws from
        // `lockedPaths`, and a stale copy draws an open padlock on a locked file.
        workspace?.let { lockedPaths = it.locked() }
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

    /**
     * **Why the editor is being opened**, which decides which two versions a diff starts on.
     *
     * The operator, 2026-11-04: *"I want newest to last synced only when you open diffs from
     * 'changes to see' on the sync row (and files from this list when clicked should open in diff
     * mode). Ordinary diff when you just open diff mode yourself should show newest to previous."*
     *
     * So the pair is not a property of the file — it is a property of the **question the person
     * came with**. Arriving from the changes list, the question is "what am I about to send", and
     * the answer is HEAD against the last commit GitHub has. Pressing Diff yourself, the question is
     * "what changed in this file lately", and the answer is the newest version against the one
     * before it.
     *
     * It is read once, when the editor opens, and then it is the editor's: a person who enters Diff
     * from a file they opened from the list, and then changes the picker, has asked a different
     * question and must not have it overruled by where they came from.
     */
    enum class Opening {
        /** A plain file open: the editor starts in View, and Diff means newest-vs-previous. */
        READ,

        /**
         * **Entered from "see changes", so the question is "what am I about to send".**
         *
         * The editor starts in Diff — the operator: "files from this list when clicked should open
         * in diff mode" — and the pair is HEAD against the last synced commit.
         */
        UNSENT,
    }

    /** How the editor was opened. [Opening.READ] until something says otherwise. */
    var editorOpening: Opening by mutableStateOf(Opening.READ)
        private set

    /** Opens a file in the editor. */
    fun openEditor(file: File, opening: Opening = Opening.READ) {
        Trace.tap("open file", file.name)
        editingPath = workspace?.relative(file)
        editorOpening = opening
        editorSession++
    }

    /**
     * The file [steps] places from [file] in the folder it lives in, or null at either end.
     *
     * **Files only, and in the folder's own order** — the order the person sees in the browser, which
     * is the order they were reading in. A folder is the unit: swiping out of one into another would
     * be a navigation the screen has no title for.
     *
     * **Null rather than wrapping.** A swipe past the last file bounces, which says "this is the
     * end" — wrapping would make a folder feel circular and leave a person unsure whether they had
     * seen everything.
     */
    fun fileBeside(file: File, steps: Int): File? {
        // **The folder is read, not the screen's list.** `entries` is a snapshot taken for drawing,
        // and a swipe that consulted it would depend on whether a redraw had landed — a file made a
        // moment ago could be invisible to the gesture that was about to reach it. `list` is the
        // same order the browser shows, computed from the disk.
        val ws = workspace ?: return null
        val dir = file.parentFile ?: return null
        val siblings = ws.list(dir).filter { it.kind != EntryKind.FOLDER }.map { it.file }
        val at = siblings.indexOfFirst { it.path == file.path }
        if (at < 0) return null
        val wanted = siblings.getOrNull(at + steps) ?: return null
        // **A locked or binary sibling is still a destination.** The editor can show a picture or a
        // read-only record perfectly well; refusing to move there would make a folder with one
        // image in it feel broken.
        return wanted
    }

    fun closeEditor() {
        // **Onboarding cannot be closed early, and this is where that is enforced.** The editor's
        // Exit button is disabled while onboarding runs, and a disabled button is not a rule — the
        // system's own back gesture, or a caller this screen does not know about, would close the
        // page anyway. The refusal is silent because the screen already shows no way out: a message
        // per attempt would be the app explaining a wall the person can feel.
        if (onboarding) {
            Trace.note("onboarding", "close refused")
            return
        }
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
        // **Read before the post, not inside it.** `main.post` runs on the UI thread, and every one
        // of these is a walk of the repository: a ref lookup, a log, and a file at a revision.
        val wanted = editorOpening
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
            // **The synced commit, and the file's version there**, worked out on the worker. Null
            // when the file did not exist at that commit — the honest answer for a file added since
            // the last sync, and the screen has words for it.
            //
            // **Its own summary is kept, because the picker has to be able to name it.** The log
            // above is the commits that *touched this file*; the last synced commit may have touched
            // nothing here, and a picker that could not name the older end would draw "From: none"
            // above a diff that plainly has two sides.
            val synced = if (wanted == Opening.UNSENT) {
                // **Through the same function the test asserts with**, so the pair the screen picks
                // and the pair the test expects cannot drift apart.
                val id = syncedFor(path, repo)
                if (id == null || repo == null) {
                    null
                } else {
                    try {
                        GitService.commitInfo(repo, id)
                    } catch (t: Throwable) {
                        null
                    }
                }
            } else {
                null
            }
            main.post {
                // **The picker's list, with the synced commit added when the file's own log does not
                // have it.** It is the older end of the comparison the person is looking at, so a
                // picker that could not name it would draw "From: none" above a diff with two sides.
                diffChoices = if (synced != null && choices.none { it.id == synced.id }) {
                    choices + synced
                } else {
                    choices
                }
                // **Two questions, two pairs, and which one is asked depends on how the person got
                // here.** The operator, 2026-11-04: "I want newest to last synced only when you
                // open diffs from 'changes to see' on the sync row ... Ordinary diff when you just
                // open diff mode yourself should show newest to previous."
                //
                // **From the changes list, the question is "what am I about to send".** The answer
                // is HEAD against the last commit GitHub has, because that is exactly the set of
                // changes the row's "3 to send" is counting. A file added since the last sync has no
                // version at the older end — that is the truth of the situation and the screen says
                // so rather than inventing a comparison.
                //
                // **From the Diff button, the question is "what changed in this file lately".**
                // Newest against the one before it, which is the pair an ordinary history view
                // means, and which always has both ends whenever there are two commits.
                //
                // `from` is the older one either way, because a diff reads forwards.
                diffTo = choices.getOrNull(0)?.id
                diffFrom = synced?.id ?: choices.getOrNull(1)?.id
            }
        }
    }

    /**
     * **Sets one end of the comparison, because the picker's choice belongs to the mode.**
     *
     * It used to be local state in the composable, seeded from these two fields — and the seeding
     * was the bug: a `remember(path)` block reads whatever the view model held *when the composable
     * was first drawn*, which for the second file opened in a session is the **first** file's pair.
     * The guard that was supposed to fix that (`if (from == null) from = viewModel.diffFrom`) could
     * never fire, because `from` was already non-null. So the picker named commits that did not
     * belong to the file, and the diff drew nothing against them.
     *
     * One owner is the fix, and it is also the smaller thing: the picker says which end it means,
     * and the diff is drawn from what the view model holds.
     */
    fun chooseDiffFrom(id: String) {
        diffFrom = id
    }

    fun chooseDiffTo(id: String) {
        diffTo = id
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

    /**
     * The last commit GitHub has, when it holds a version of [path] — or null.
     *
     * **The older end of the diff a changes-list row opens**, and public because that is the only
     * way to assert it: the pair the picker shows is the view model's, so a test that recomputed it
     * with its own git call would be testing the test.
     */
    fun syncedCommitOf(path: String): String? =
        syncedFor(path, git?.let { currentProject?.let { p -> it.open(p) } })

    /**
     * **Whether the last synced commit holds a version of [path]**, which is the whole question.
     *
     * A file added since the last sync has no version there, and the answer must be null rather than
     * that commit's id: the screen says "this file is new since the last sync, so every line is an
     * addition", and naming a commit the file is not in would make it say "there is no version of
     * this file at one of those commits" instead — about the ordinary case the mode exists for.
     */
    private fun syncedFor(path: String, repo: org.eclipse.jgit.lib.Repository?): String? {
        if (repo == null) return null
        return try {
            GitService.lastSynced(repo)?.takeIf { GitService.fileAt(repo, path, it) != null }
        } catch (t: Throwable) {
            null
        }
    }

    fun openChangedFile(path: String) {
        // The list's own act: which file a person entered from "see n changes".
        Trace.tap("open changed file", path)
        val ws = workspace ?: return
        val project = currentProject ?: return
        // **Straight into Diff, against the last synced commit.** The operator: "files from this
        // list when clicked should open in diff mode." A person who tapped a file in a list called
        // "Changes to send" has already said what they want to see, and making them press Diff
        // after that is asking the same question twice.
        openEditor(File(ws.projectDir(project), path), Opening.UNSENT)
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
            // **A save must not bring a deleted account back.** The person may remove an account
            // while a save is still queued — the test below does exactly that — and `ws.write` makes
            // the folder it writes into, so the queued save recreated the account tree a moment
            // after it was deleted. The commit half of this block already asks `accounts.exists`;
            // the write half did not, and the write is the half that makes folders.
            //
            // Refused silently rather than reported: the account is gone, so there is no screen left
            // to report it on, and a message about a file in an account that no longer exists would
            // be the app talking about something the person already ended.
            if (login == null || !accounts.exists(login)) return@onWorker
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

    /**
     * Photographs a text file's **view**: renders it to a PNG beside the file, and opens that picture.
     *
     * The operator's *"a photo option for a text file to make an image based off its view"* — so a
     * person can circle a paragraph and say *this goes there*, which is the one correction that cannot
     * be said in words.
     *
     * **Beside the file, not in `agent/`.** A photograph of a file is a note about that file, so it
     * lives with it and syncs with the project; the name carries a hash of what was rendered, so the
     * same page twice is the same name and a changed page is a new one.
     *
     * **The text is the editor's current text, not what is on disk.** A person who has typed and not
     * saved is looking at the version they mean to annotate, and photographing the older one would put
     * their circle next to a paragraph that is no longer there.
     */
    fun photograph(text: String) {
        val ws = workspace ?: return
        val file = editingFile ?: return
        if (ws.kindOf(file) != EntryKind.TEXT) return
        val dir = file.parentFile ?: return
        val tap = Trace.tap("photo", file.name)
        onWorker {
            Photo.render(text, dir).fold(
                onSuccess = { rendered ->
                    val name = Photo.nameFor(file, rendered.bytes)
                    val target = File(dir, name)
                    if (!ws.writeBytes(target, rendered.bytes)) {
                        Trace.failed(tap, "The photograph could not be written", "photo")
                        main.post { message = "The photograph could not be written" }
                        return@fold
                    }
                    // **Its own commit, so the picture is in the history with the file it is about.**
                    // A photograph that existed only on the phone would be the one kind of work this
                    // app does not record.
                    val project = ws.projectOf(file)
                    project?.let { git?.commitOutcome(it, "Photographed ${file.name}") }
                    Trace.answered(
                        tap,
                        "Photographed ${file.name} as $name " +
                            "(${rendered.width}\u00d7${rendered.height})",
                    )
                    main.post {
                        refresh()
                        refreshChanges()
                        message = "Photographed ${file.name} as $name"
                        // **Opened, because drawing on it is the point.** A picture nobody is looking
                        // at is a file the person has to go and find.
                        openEditor(target)
                    }
                },
                onFailure = { failure ->
                    val said = (failure as? Photo.PhotoFailed)?.said ?: "This file could not be photographed"
                    Trace.failed(tap, said, "photo")
                    main.post { message = said }
                },
            )
        }
    }

    /**
     * Saves a picture that was drawn on: the bytes go to the file, and the file is committed.
     *
     * **Its own function rather than an overload of [saveEditor], and the reason is the guard at the
     * top of that one.** `saveEditor` refuses anything that is not `EntryKind.TEXT` — deliberately,
     * because a save must never be able to turn a `.zip` into text. A picture save is the mirror
     * image of that rule: it refuses anything that is not `EntryKind.IMAGE`, so a bug in the drawing
     * screen cannot write bytes over a document.
     *
     * **No debounce.** `saveDebounceMs` exists so a person typing does not make a commit per
     * keystroke. Drawing produces one save when they press the button, so there is nothing to
     * coalesce and a delay would only make the button feel broken.
     */
    /**
     * Writes a picture that was drawn on, **either over the file it came from or as a new one.**
     *
     * **`target` is what makes a picture's versioning a file operation.** A binary has exactly one
     * version (see `docs/PLAN-lfs-2026-11-05.md`): saving over it destroys what was there, and saving
     * beside it keeps both as two files. The screen asks which, and this is the one place both answers
     * land — the same funnel for locks, the record's protection, and the commit, so a new file cannot
     * skip a rule the old one obeys.
     *
     * **A new name does not delete the original.** The person asked for a copy; the file they were
     * editing keeps its own version, and the one they made starts with its own.
     */
    fun savePicture(bytes: ByteArray, target: File? = null) {
        val ws = workspace ?: return
        val file = editingFile ?: return
        // The mirror of `saveEditor`'s refusal, and held here rather than only on the screen for the
        // same reason: a guarantee held only by the caller is a convention.
        if (ws.kindOf(file) != EntryKind.IMAGE) return
        if (isLocked(file)) return
        // **The destination is a picture too**, or the bytes would be written into a text file and
        // the name would promise something the content is not.
        val into = target ?: file
        if (Workspace.kindOfPath(into.name) != EntryKind.IMAGE) {
            message = "\"${into.name}\" is not a picture name, so the drawing was not saved there."
            return
        }
        if (isLocked(into)) {
            message = "\"${into.name}\" is locked, so the drawing was not saved there."
            return
        }

        val tap = Trace.tap("save", into.name)
        val g = git
        val name = into.name
        val project = ws.projectOf(into) ?: ws.projectOf(file)
        val login = activeLogin
        onWorker {
            if (login == null || !accounts.exists(login)) return@onWorker
            // **Written through the workspace's own funnel**, so the app's locks and the record's
            // protection are asked here exactly as they are for a text save. A picture is not a
            // special case that gets to skip them.
            if (!ws.writeBytes(into, bytes)) {
                Trace.failed(tap, "That picture could not be written", "save")
                main.post { message = "That picture could not be written" }
                return@onWorker
            }
            var refused: String? = null
            if (login != null && accounts.exists(login)) {
                val outcome = project?.let { g?.commitOutcome(it, CommitMessage.SAVE) }
                if (outcome != null && !outcome.ok) refused = outcome.error
            }
            val answer = if (refused != null) {
                "Saved the picture, but not to its history: $refused"
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
                    // **The first push is when a repository becomes reachable, and so the first moment a
                    // signing key could be sent to it.** Not on project add: adding a project is a local
                    // act with no repository behind it, so there is nothing to ask GitHub about. A project
                    // pulled FROM the person's own GitHub counts as theirs, and this is the check that
                    // notices its key is not in CI.
                    //
                    // It asks; it does not send. See [checkSigning].
                    checkSigning(project)
                } else {
                    lastSyncError = result.message
                }
            }
        }
    }

    // ---- the signing key, asked about and sent by hand -------------------

    /**
     * What the active project's signing setup looks like, or null when nothing is worth saying.
     *
     * **Asked when a project is opened, never acted on.** Sending a signing key is not something an app
     * may decide to do: the key is the most consequential secret this app holds, and a background
     * reconcile that pushed it to whatever repository the person had just added would be the app
     * widening its own blast radius on its own initiative. This only answers.
     *
     * It is a `mutableStateOf` because the row it feeds is drawn on the project screen, and a value
     * that arrives on the worker after the screen was built must be something Compose can see.
     */
    var signingSaid by mutableStateOf<String?>(null)
        private set

    /** The key that a person has to press for. Null when there is nothing to send. */
    var signingKeyFile by mutableStateOf<File?>(null)
        private set

    /**
     * Looks for a signing key in the project and asks GitHub whether CI can see it.
     *
     * **Silent unless the one actionable state is true** — see [ReleaseKey.Reconcile], where a project
     * with no key and a project that is already set up both read as nothing to say.
     *
     * [project] is the folder name, and the key is looked for **inside it**: a `keystore.properties`
     * is a file of the project's, so the only place it can be is the project's own folder, and a key
     * found anywhere else would be a key the app could not tell apart from another project's.
     */
    fun checkSigning(project: String) {
        val ws = workspace ?: return
        val login = activeLogin
        val api = githubFor(login) ?: return
        val token = login?.let { credentials.token(it) }.orEmpty()
        if (token.isBlank()) return
        val remote = git?.remoteUrl(project).orEmpty()
        val ref = RepoRef.of(remote) ?: return
        val keyFile = File(ws.projectDir(project), ReleaseKey.FILE)
        onWorker {
            val said = ReleaseKey(keyFile).reconcile(api, token, ref, keyFile)
            main.post {
                signingSaid = said.said()
                signingKeyFile = if (said.needsSending) keyFile else null
            }
        }
    }

    /**
     * Puts a project's signing key where only a build can reach it — **the whole job, in one press.**
     *
     * ## Why the app does this rather than explaining it
     *
     * Doing it by hand is five steps on github.com, and two of them are dangerous in a specific order:
     * a repository secret is visible to every workflow, so leaving it there defeats the arrangement —
     * but deleting it *before* the workflow names the environment makes the key invisible and every
     * build fails, appearing later as "the release is unsigned".
     *
     * That is not knowledge a person installing an app should need. [Signing.move] knows the order and
     * runs it.
     *
     * ## The press is the whole permission
     *
     * There is no setting and no confirmation beyond the row existing. The row exists because the app
     * asked and found something wrong; the sentence beside the button says what pressing it does.
     */
    fun fixSigning(project: String) {
        val ws = workspace ?: return
        val login = activeLogin
        val api = githubFor(login) ?: return
        val token = login?.let { credentials.token(it) }.orEmpty()
        if (token.isBlank()) {
            message = "Sign in to GitHub first"
            return
        }
        val ref = RepoRef.of(git?.remoteUrl(project).orEmpty()) ?: return
        val dir = ws.projectDir(project)
        val keyFile = File(dir, ReleaseKey.FILE)
        // **The workflow that signs**, which is the one the app can find and a person would have to
        // choose. It is found by what it reads rather than by its name, because a workflow may call
        // the step "Build the release", "Sign", or anything else — and `Signing.nameEnvironment` walks
        // to the job from the same evidence, so the two agree by construction rather than by a shared
        // guess.
        val workflows = Workflows()
        val signing = workflows.survey(dir, ws.locked())
            .map { File(dir, it.path) }
            .firstOrNull { file ->
                val text = try {
                    file.readText()
                } catch (e: Throwable) {
                    ""
                }
                text.contains("MUFFY_KEYSTORE") || text.contains("assembleRelease")
            }
        if (signing == null) {
            message = "There is no workflow in $project that builds a release, so there is nothing to " +
                "give the key to."
            return
        }
        busy = true
        onWorker {
            val moved = Signing.move(api, token, ref, dir, keyFile, signing)
            main.post {
                busy = false
                message = moved.fold(
                    onSuccess = { it.said() },
                    onFailure = { it.message ?: "The signing key could not be moved" },
                )
                // **Asked again rather than assumed.** The row disappears because the answer changed,
                // not because the app decided its own call had worked.
                checkSigning(project)
            }
        }
    }

    /**
     * Puts a project's newest CI artifact into Downloads — the menu's "Export to Downloads".
     *
     * **It exports the artifact, not the project.** A person pressing Export on a project wants the
     * thing the build produced: an APK they can install, a bundle of screenshots, a report. Exporting
     * the project's source tree would be a hundred files in a flat folder with names that no longer
     * tell them apart, which is why `Export` refuses a folder.
     *
     * When there is no artifact it says so rather than guessing at a file, because "the newest run has
     * not produced one" and "the build failed" are different facts and the person needs the first one
     * to understand the second.
     */
    fun exportArtifact(project: String) {
        val login = activeLogin
        val api = githubFor(login) ?: return
        val token = login?.let { credentials.token(it) }.orEmpty()
        if (token.isBlank()) {
            message = "Sign in to GitHub first"
            return
        }
        val ref = RepoRef.of(remoteUrlOf(project).orEmpty())
        if (ref == null) {
            message = "$project has no GitHub repository, so it has no artifacts to export"
            return
        }
        busy = true
        onWorker {
            // **The newest run with artifacts**, because a project has many runs and the one a person
            // means by "export" is the last thing that was built.
            val runs = api.runs(token, ref, limit = 20)
            val run = runs.value.orEmpty().firstOrNull { it.conclusion == "success" }
            val artifacts = run?.let { api.artifacts(token, ref, it.id) }?.value.orEmpty()
            val first = artifacts.firstOrNull()
            val outcome = if (first == null) {
                "There is no artifact to export: no successful run has produced one yet."
            } else {
                val bytes = api.artifactBytes(token, ref, first.id)
                if (bytes.error != null) {
                    "The artifact could not be downloaded: ${bytes.error}"
                } else {
                    // **Through the same file the tool writes to**, so a person's export and the
                    // agent's are the same bytes from the same place.
                    val ws = workspace!!
                    val dir = File(File(ws.projectDir(project), AgentLog.DIR), "ci/artifacts")
                    // **The artifact's own name, made safe rather than re-styled** — see
                    // `AgentTools.safeArtifactName` for why the project naming rule is wrong here.
                    val file = File(dir, Release.artifactName(first.name) + ".zip")
                    try {
                        dir.mkdirs()
                        file.writeBytes(bytes.value ?: ByteArray(0))
                        Export.copyToDownloads(getApplication(), file).fold(
                            onSuccess = { it.said() },
                            onFailure = { it.message ?: "It could not be put in Downloads" },
                        )
                    } catch (e: Throwable) {
                        "The artifact could not be filed: " + Secrets.explain(e, "not written")
                    }
                }
            }
            main.post {
                busy = false
                message = outcome
            }
        }
    }

    /**
     * Starts a release of [versionName] from `main`.
     *
     * **The other lane, and the one the agent cannot reach.** `AgentTools`' `ci` refuses any branch but
     * `development`; this goes to `main` and is reachable only from a button a person pressed. No
     * reviewer is involved: the `release` environment may only be used by `main` and `development`, so
     * an agent that somehow dispatched on `main` would find the signing key unreachable and the release
     * would fail rather than ship.
     */
    fun startRelease(versionName: String, devBuild: Boolean = false) {
        val login = activeLogin
        val api = githubFor(login) ?: return
        val token = login?.let { credentials.token(it) }.orEmpty()
        if (token.isBlank()) {
            message = "Sign in to GitHub first"
            return
        }
        val project = path.firstOrNull() ?: return
        val ref = RepoRef.of(git?.remoteUrl(project).orEmpty())
        if (ref == null) {
            message = "$project has no GitHub repository, so there is nothing to release"
            return
        }
        busy = true
        onWorker {
            val started = Release.start(api, token, ref, versionName, devBuild)
            main.post {
                busy = false
                message = started.fold(
                    onSuccess = { it.said() },
                    onFailure = { it.message ?: "The release could not be started" },
                )
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
     * The name is checked with [Workspace.nameProblem], unlike a GitHub project's: this one is a
     * folder the person typed, not a repository whose name GitHub already accepted.
     */
    fun createLocalProject(
        name: String,
        onDone: (Boolean) -> Unit = {},
    ) {
        val ws = workspace ?: return
        // **As typed, and only blank falls back.** The app no longer rewrites names — see
        // `Workspace.nameProblem` for the measurement that removed it.
        val clean = name.trim().ifBlank { "local" }
        val problem = Workspace.nameProblem(clean)
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
            val git = ProjectGit(
                ws,
                { null },
                {
                    if (accounts.isProvisional(activeLogin)) PROVISIONAL_AUTHOR
                    else accounts.account(activeLogin ?: "")?.author() ?: DEFAULT_AUTHOR
                },
                index = indexer(ws),
            )
            val made = git.init(clean)
            // **The folder tree, and nothing in it.** The operator: *"just create empty system_data
            // folder tree for each project when it is added and put there subfolders for all of our
            // doc types"*.
            //
            // **The markers are committed**, because they are files: an empty folder is invisible to
            // git, so each one holds a marker, and a marker left uncommitted is a project that opens
            // showing four changes nobody made. That is the same fault the preset copy had, one
            // layer down, and it is why this returns the list rather than nothing.
            val wrote = ProjectTree.create(ws.projectDir(clean))
            if (wrote.isNotEmpty()) {
                git.commitOutcome(clean, "Start the project's folders")
            }
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
    fun createProject(
        name: String,
        licence: String,
        onDone: (Boolean) -> Unit = {},
    ) {
        val ws = workspace ?: return
        val login = activeLogin
        val api = githubFor(login)
        val token = login?.let { credentials.token(it) }.orEmpty()
        if (login.isNullOrBlank() || api == null) {
            message = "Sign in to GitHub first"
            onDone(false)
            return
        }
        // Deliberately not put through the project naming rule: this name becomes a
        // repository on GitHub, and a project that was pulled in already carries the
        // repository's own name. A name GitHub refuses is reported by GitHub, which
        // is the only place that knows what it will accept.
        val clean = name.trim()
        val problem = Workspace.nameProblem(clean)
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
            val git = ProjectGit(
                ws,
                { credentials.token(login) },
                { accounts.account(login)?.author() ?: DEFAULT_AUTHOR },
                index = indexer(ws),
            )
            val clone = git.cloneInto(clean, url)
            // **The folder tree, after the clone and not before.** `cloneInto` refuses a folder that
            // already holds anything, so a folder made first would have stopped the repository
            // arriving at all — the same trap `createProjectFolders` had.
            //
            // **And only when the clone worked**, because folders in a folder that is not a project
            // are folders nobody asked for.
            //
            // **And sent.** The markers are files, so there is something to commit — and a project
            // made on GitHub is on GitHub. Leaving them as a commit waiting to be sent would greet a
            // person with "1 to send" on a project they have not touched. The person pressed Create,
            // the app already spoke to GitHub to make the repository, so what it starts with is part
            // of what was made. The agent's rule — that nothing it does is pushed — is unchanged.
            if (clone.success) {
                val wrote = ProjectTree.create(ws.projectDir(clean))
                if (wrote.isNotEmpty()) {
                    git.sync(clean, "Start the project's folders")
                }
            }

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
        val problem = Workspace.nameProblem(clean)
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
                index = indexer(ws),
            ).cloneInto(repo.name, repo.cloneUrl)
            // **Nothing is written into an imported project.** It arrives with whatever the person
            // put in it, and it already works — adding a file to it would put a file in their next
            // push that they never wrote. The tree is made only because the app needs somewhere to
            // put the prompt it is given; an empty folder is not in git, so nothing appears.
            if (result.success) {
                ProjectTree.create(ws.projectDir(repo.name))
            }
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

    /**
     * Bumped when a read produced a different conversation, **not on every read**.
     *
     * The screen keys its view state on it — the open bubble, each message's page, and what is being
     * read fullscreen — so a poll that found nothing new must not move it, or a fullscreen page is
     * closed by a timer. See [applyChat].
     */
    var chatGeneration: Int by mutableStateOf(0)
        private set

    /** True once a conversation has been read at all, so an empty first read still counts. */
    private var chatRead: Boolean = false

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

    /**
     * **How big the conversation is, in tokens** — the number beside the agent's state.
     *
     * The operator's report: "for agent status just write waiting/working/stopped + current context
     * (just '131k' for example - laconic)". Measured from the record rather than from the last
     * request, because the question the figure answers is "how close is this to a clear-up", and
     * that is a fact about what the next request would carry.
     *
     * Zero means not measured yet, and the label prints nothing rather than a confident `0`.
     */
    var chatContextTokens: Long by mutableStateOf(0L)
        private set

    /** Measures the open conversation's context, so the label can show it. */
    fun measureChatContext() {
        val project = chatProject ?: return
        val ws = workspace ?: return
        val size = try {
            AgentLog.open(ws.projectDir(project)).contextTokens()
        } catch (t: Throwable) {
            return
        }
        if (size != chatContextTokens) chatContextTokens = size
    }

    /**
     * **The numbers the footer shows while a reply is arriving.**
     *
     * The operator's rule: "only footer token numbers should be updated on every chunk". So this is
     * per chunk and [chatWriting] is per reply - two different questions, two different clocks.
     *
     * A token count is a *liveness* signal: it answers "is this still working, or has it died", and
     * that question needs an answer every second. The words are not - a reply half-drawn is a
     * paragraph rearranging itself under somebody's eyes.
     */
    var chatSpent: AgentChat.Spend? by mutableStateOf(null)

    /**
     * What the requests **before this one** in the same burst cost.
     *
     * **A field rather than a local, because the listener is rebuilt per request** and a local would
     * start at zero every time — which is the bug this exists to fix. It is folded in when a reply
     * arrives ([door.onReply]) and cleared where a burst begins.
     */
    private var burstSpent = AgentChat.Spend(0, 0)

    /**
     * **The pages of the message being written, by the row id they will have.**
     *
     * Keyed by id because a page is published twice - asked, then answered - and the second has to
     * replace the first rather than appear beside it. The id is the `CALL` row's number, which is
     * also what the finished page carries, so nothing has to be matched up later.
     */
    var chatPages: Map<Int, Agent.Live> by mutableStateOf(emptyMap())

    /**
     * **The words the agent is writing, as it writes them.**
     *
     * Separate from [chatLive], which is the one-line description for the corner. This is the reply
     * itself, and the operator's report is the reason it exists: "it shouldnt be in the top bar at
     * all, agent's messages should be in their own bubble, not inside the top bar of the screen".
     *
     * The app already had the text - `Streamed.said` - and threw it away except for its length, so a
     * person watched a counter climb in the title bar and then saw the whole reply appear at once.
     * The words belong where the finished ones go.
     *
     * **Cleared when the turn ends**, because the finished reply arrives as a row and drawing both
     * would say it twice.
     */
    var chatWriting: String by mutableStateOf("")
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
        measureChatContext()
        readChat(follow = true)
    }

    fun closeChat() {
        Trace.tap("close chat", chatProject.orEmpty())
        chatProject = null
        chatBox = null
        chatWorking = false
        chatLive = ""
        chatWriting = ""
        chatSpent = null
        burstSpent = AgentChat.Spend(0, 0)
        chatPages = emptyMap()
        chatContextTokens = 0L
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
            // **The size is measured with the rows**, off the main thread and from the same file,
            // so the number beside the state cannot disagree with the conversation below it.
            val size = try {
                AgentLog.open(dir).contextTokens()
            } catch (t: Throwable) {
                0L
            }
            main.post {
                applyChat(rows, follow)
                if (size != chatContextTokens) chatContextTokens = size
            }
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
            chatWriting = ""
            chatSpent = null
            // **A burst is over, so its total is over.** Left standing, the next message would open
            // with the previous one's tokens already counted.
            burstSpent = AgentChat.Spend(0, 0)
            chatPages = emptyMap()
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

    /**
     * Ends a turn that never started, **so the screen is not left waiting.**
     *
     * A turn is `chatWorking = true` from the moment the person presses Enter. Every path out of
     * `speak`'s callback clears it, and a path that returns before the callback never runs — which is
     * a chat box that waits for an answer to a question the record never heard.
     */
    private fun finishTurn(project: String, said: String) {
        chatWorking = false
        chatLive = ""
        chatWriting = ""
        chatSpent = null
        burstSpent = AgentChat.Spend(0, 0)
        message = said
        Trace.failure(said)
        applyEnd(project, Agent.End.Stopped(said), System.currentTimeMillis())
        refresh()
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
        // **A turn that cannot start must say so.** This was `git ?: return`, and it returned into
        // nothing: no row, no message, `chatWorking` left true — a screen that waits for ever and a
        // record that does not mention the question. `git` is a property that now wires the index
        // queue on every read, so it can fail for reasons that have nothing to do with the turn.
        val g = try {
            git
        } catch (e: Throwable) {
            finishTurn(project, Secrets.explain(e, "The agent could not start"))
            return
        } ?: run {
            finishTurn(project, "There is no project for this conversation yet")
            return
        }
        val model = modelConfig.active
        val door = doorFor(model, project)
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

    /**
     * The engine the agent browses with, made on first use and shared by every runner this view
     * model builds.
     *
     * **One per view model, not one per runner.** A turn can be rebuilt many times — a model change,
     * a sign-out, a settings change — and a new WebView for each would be a new Looper thread and a
     * new Chromium for each. It is released in `onCleared` with the rest of the view model's work.
     */
    private var browser: com.example.notes.browser.WebViewBrowser? = null

    /**
     * Lets go of what outlives a screen: the runner's thread, and the engine's Chromium process.
     *
     * **The browser would otherwise outlive the view model.** A `WebView` keeps a `Looper` thread
     * and a browser process alive for as long as anything holds it, and a view model that is
     * cleared when the person leaves the app has no further use for either.
     */
    override fun onCleared() {
        runner?.close()
        runner = null
        browser?.close()
        browser = null
        super.onCleared()
    }

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
            // **The same engine the service uses, or a second one.** The view model runs turns while
            // the app is on screen; the service runs them while it is not. Two runners are possible
            // at once, so this is its own instance rather than a shared one — a WebView is bound to
            // one Looper thread for life, and sharing it across two would put every call on whichever
            // thread made it first.
            browser = browser ?: com.example.notes.browser.WebViewBrowser(getApplication()).also {
                browser = it
            },
            notify = { session -> agentNotifier?.invoke(session) },
            // **A page, as it happens.** A call is published when the model asks and filled in
            // when the tool returns, so the bubble shows the work rather than only its result.
            onPage = { project, live ->
                main.post {
                    if (project == chatProject) {
                        chatPages = chatPages + (live.id to live)
                    }
                }
            },
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
        // **The same rule the runner applies, from the same function.** There are two doors a
        // session is written through — this one and `AgentRunner.put` — and the reset lived in only
        // the second, so the screen's Stop wrote a stopped session and left the context in place.
        // A rule that lives in one of two doors is a rule the app does not have.
        val was = agentSessions.firstOrNull { it.project == session.project }
        AgentStops.resetIfStopped(ws, session, was)
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
    private fun doorFor(model: ActiveModel, project: String = ""): Door = (chatDoor ?: { active ->
        OpenRouterDoor(
            key = { activeLogin?.let { credentials.modelKey(it) } },
            keyState = { activeLogin?.let { credentials.modelKeyState(it) } ?: KeyState.NONE },
            model = active.model,
            provider = active.provider,
            // **Recorded, never enforced.** Nothing is scoped by this - it is what lets the log
            // answer "which project costs me most", and a door built without one writes an empty
            // project rather than failing.
            project = project,
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
            // **Two clocks, and the operator named both.**
            //
            // The footer's numbers update on every chunk: "only footer token numbers should be
            // updated on every chunk". A token count is a liveness signal - it answers "is this
            // still working, or has it died" - and that question needs an answer every second.
            //
            // The words update once per reply: "one full reply seems like the best point to update
            // dispayed text". A reply half-drawn is a paragraph rearranging itself under somebody's
            // eyes, and because `Streamed` is created per *request*, a per-chunk write also emptied
            // the bubble at every request boundary in a burst.
            // **The burst's own total, not the request's.** The operator's report: "thinking and
            // output in the message footer should be cumulative for all tokens spent for this
            // message burst, currently they are buggy". They were: `Spend` has had a `plus` since it
            // was written, with a comment saying a burst is a sum, and the live path *replaced* the
            // figure on every request — so a burst of five calls reported the fifth one's tokens and
            // the footer reset under the person's eyes at every boundary.
            //
            // `burstSpent` is what the requests before this one cost; the listener adds the current
            // request's running total to it. Both are reset where the burst begins.
            door.listener = { stream, elapsed ->
                chatLive = stream.shape(elapsed)
                chatSpent = burstSpent + AgentChat.Spend(
                    output = stream.said.length.toLong(),
                    thinking = stream.thinking.length.toLong(),
                )
            }
            // **Appended, not replaced.** A burst is one message made of several replies, so the
            // bubble shows the message being built rather than the last thing said.
            door.onReply = { words ->
                chatWriting = chatWriting + words
                // **The request's cost becomes part of the burst's.** Read from the screen's own
                // figure, which is `burstSpent` plus this request's total at the moment it closed.
                burstSpent = chatSpent ?: burstSpent
            }
        }
    })(model)

    /**
     * Hands a freshly read conversation to the screen — **and bumps the generation only when it
     * changed.**
     *
     * **There is no "which turn" any more.** The box drew one turn at a time and kept a position so
     * a re-read would not pull a person away from the turn they were reading; a list of bubbles
     * scrolls, so the position is the scroll's, and the screen keeps the one thing that *is* a
     * position — which bubble is open — keyed by the row's id rather than by a count.
     *
     * **Why "only when it changed" is the whole fix, and not an optimisation.** The screen keys its
     * *view* state on the generation — which bubble is open, which page each message is showing, and
     * whether a message is being read fullscreen — on the reasoning that a re-read should close a
     * bubble that may no longer exist. That is right for a re-read that changed something and wrong
     * for the one that did not, and there are two of the second kind every few minutes: the session
     * poll re-reads the record, and the keepalive re-reads it again. The operator saw both:
     *
     * > *"there is some bug that closes the message fullscreen view and returns me to chat view"*
     * > *"and there is a bug that moves me to the beginning of the message when a new page arrives"*
     *
     * The first is a fullscreen page closed by a poll that found nothing new. The second is the hold
     * set — which message the person turned back in — wiped by the same bump, so the message resumed
     * following its newest page after they had deliberately stopped it.
     *
     * **The row ids are the comparison**, because they are the one thing a record cannot change
     * without meaning it: an appended row adds an id, a forget rewrites a row's `ctx` and leaves the
     * id, and a trim shortens the list. A count alone would miss a forget.
     */
    private fun applyChat(rows: List<AgentLog.Row>, follow: Boolean) {
        val box = AgentChat.box(rows)
        // **The first read always counts, even when it is empty.** `box` is null for a conversation
        // with no operator message yet, and null before the first read as well — so a "bump only when
        // it changed" rule would leave `chatGeneration` at 0 for a project nobody has spoken in, and
        // the two places that wait for a conversation to be read would wait for ever. Something *was*
        // read; that it found nothing is an answer, not the absence of one.
        val same = chatRead && chatBox == box
        chatBox = box
        chatRead = true
        if (!same) chatGeneration++
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
    /**
     * **Starts GitHub's device flow: a code to type, and a poll that finishes by itself.**
     *
     * The decision behind it is `3f213fd` (D5): PKCE does not remove GitHub's client secret, so the
     * device flow is the only secretless path — and it is the one designed for a client that cannot
     * keep one.
     *
     * **Foreground only.** The poll stops when [cancelDevice] is called, which the screen does when
     * it leaves. A background poll is a battery cost and a token-request cost for a flow that needs
     * a person in front of it anyway.
     *
     * **A transport failure is not a verdict** — [DeviceFlow.poll] answers `Waiting` for one — so a
     * dead moment does not end the sign-in. The code's own lifetime is the bound.
     */
    fun startDeviceSignIn() {
        if (device != null) return
        Trace.tap("github device", "start")
        // **Set on the calling thread, before the worker exists.** The worker's first cancellation
        // check can run before the main thread has posted the dialog state, and a flag set *inside*
        // the worker's task would still be later than that check.
        polling.set(true)
        onWorker {
            DeviceFlow.requestCode().fold(
                onSuccess = { code ->
                    main.post {
                        device = Device(code.code, code.url, deviceCode = code.device)
                        Trace.note("github-device", "code issued, ${code.expiresInMs / 60_000} min")
                    }
                    pollDevice(code)
                },
                onFailure = { t ->
                    polling.set(false)
                    val said = Secrets.explain(t, "GitHub sign-in could not start")
                    main.post {
                        // **Said in the dialog, not as a toast.** The dialog is where the person is
                        // looking, and this is the answer to the thing they pressed.
                        device = Device("", "", failed = said)
                    }
                },
            )
        }
    }

    /**
     * **Polls until the code is approved, refused, or out of time.** On the worker, one sleep at a
     * time, with the interval GitHub set and `slow_down` respected.
     */
    /**
     * **Set by the worker the moment a flow starts, cleared by whatever ends it.**
     *
     * The cancellation check used to read [device] — the *display* state — and that is the same bug
     * the loop was extracted to kill, one layer down. `startDeviceSignIn` sets `device` on the main
     * thread and then calls `pollDevice` **on the worker**, so the worker's first `cancelled()` can
     * run before the main thread has posted anything: it reads null, takes null for "cancelled", and
     * returns without ever asking GitHub. The dialog sits on "Waiting for you to approve it…" for
     * ever, and GitHub — which does not know any of this — reports the device as connected.
     *
     * The operator's report says exactly that, and it is the second time: *"connect to github again
     * stuck in waiting"*.
     *
     * So the flag is its own thing, owned by the worker, and it is `AtomicBoolean` because two
     * threads touch it and a plain `Boolean` field is a data race rather than a shared answer.
     */
    private val polling = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun pollDevice(code: DeviceFlow.Code) {
        // **The loop is `DeviceFlow.pollThrough`, not a copy here.**
        //
        // The copy that used to be here **killed itself on its first iteration**: it asked `device`
        // whether the flow had been cancelled, and `device` was set by a `main.post { }` that had not
        // run yet — so the first check read null, took it for "cancelled", and returned. The dialog
        // then showed the code and "Waiting for you to approve it…" for ever while nothing polled,
        // and GitHub reported the device as connected. That is the bug the operator hit.
        //
        // The loop needs the *code* it was handed, not the display state, and the two are separate
        // arguments now.
        // **The end of a flow was recorded; its start was not.** A report saying "stuck in waiting"
        // could not distinguish *polling, and GitHub saying no* from *nothing polling at all* — the
        // two states this bug lives between — because only the ending left a mark. This line is the
        // fact that says the loop is alive, and it is what would have made the operator's report a
        // diagnosis instead of a description.
        Trace.note("github-device", "polling")
        DeviceFlow.pollThrough(
            code = code,
            // **The worker's own flag, not the screen's.** See [polling].
            cancelled = { !polling.get() },
        ) { answer ->
            when (answer) {
                is DeviceFlow.Poll.Done -> {
                    polling.set(false)
                    main.post { device = null }
                    // **The same door the token uses**, so there is one way in rather than two.
                    val who = signInGitHub().identity(answer.token).value
                    main.post {
                        if (who == null) {
                            message = "GitHub accepted the sign-in but would not say who you are."
                        } else {
                            signIn(who.login, answer.token, who.name, who.email)
                        }
                    }
                }
                is DeviceFlow.Poll.Failed -> {
                    polling.set(false)
                    Trace.note("github-device", "ended · ${answer.why.take(60)}")
                    main.post { device = device?.copy(failed = answer.why) }
                }
                // **Not reachable**: `pollThrough` only ever ends with Done or Failed, which is the
                // point of it being a loop rather than a step. Exhaustive rather than an `else`.
                is DeviceFlow.Poll.Waiting, is DeviceFlow.Poll.Slow -> Unit
            }
        }
    }

    /** Stops a device-flow sign-in. The screen calls this when it leaves. */
    fun cancelDevice() {
        if (device == null) return
        Trace.note("github-device", "cancelled")
        // **Before the state, so the worker cannot wake between the two and find the flow alive.**
        polling.set(false)
        device = null
    }

    fun signInWithToken(token: String, onDone: (Boolean) -> Unit) {
        onWorker {
            val result = signInGitHub().identity(token)
            val who = result.value
            // **A token that cannot do the job is refused, and named.** The operator: *"window should
            // check that all the scope requirements are satisfied and notify with rejection if they
            // are not set for the provided token."*
            //
            // Refused here rather than after signing in, because accepting it would adopt an account
            // whose first push fails with GitHub's "Resource not accessible by personal access token"
            // — a message that names neither the scope nor the file, and that I have already watched
            // cost this project a day. The dialog stays open with the reason in it.
            val verdict = who?.let { TokenScopes.judge(it.scopes) }
            main.post {
                if (who == null) {
                    message = result.error ?: "That key did not work"
                    onDone(false)
                } else if (verdict is TokenScopes.Verdict.Missing) {
                    // **`device` is where the dialog's red line comes from**, and it is the same
                    // place a refused device flow puts its reason — one way to show a refusal, not
                    // two. `failed` is the field the dialog already renders in the error colour.
                    device = Device("", "", failed = verdict.said())
                    Trace.note("github-token", "refused · missing ${verdict.missing.joinToString(",")}")
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
        // **The first half of the way out.** A key may already be on this phone from before the
        // sign-in, or the person may connect the model next; either order finishes onboarding.
        closeOnboardingIfConnected()
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
        // **A binary is not in the history, so there is nothing to bring back.** A picture has one
        // version — the newest — and the LFS store keeps only what the newest commits reference, so
        // the bytes at `gone.revision` may already be gone. Offering it back would be offering
        // something that may not exist, and writing a pointer over it would replace a picture with
        // JSON. The person is told instead of being shown a button that might work.
        //
        // **A folder is allowed**, because it may hold text; the per-file refusal below decides.
        if (!gone.isFolder && Workspace.kindOfPath(gone.name) != EntryKind.TEXT) {
            message = "\"${gone.name}\" is not text, and a picture has only one version — it " +
                "cannot be brought back. Deleting one is permanent."
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
                // **The same rule inside a folder.** A folder's version may hold pictures, and a
                // picture's bytes at that version may no longer exist; skipping it leaves the folder
                // restored without the pictures it used to hold, which the answer says.
                if (Workspace.kindOfPath(name) != EntryKind.TEXT) continue
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

        /**
         * Which GitHub a login talks to — **the rule, in one place, for the app and the agent alike.**
         *
         * It was a method on [AppViewModel] only, and the agent's CI tools need the same answer
         * without a view model: a debug build's test account is a folder pretending to be GitHub, and
         * a release build must refuse to act as one even when a stored account claims to be. Writing
         * that twice would be writing it two slightly different ways.
         *
         * **The provisional account has no GitHub.** Its folder is a real workspace, but there is no
         * account behind it, so answering with an `HttpGitHub` would give every caller something
         * that looks usable and sends unauthenticated requests to the internet. `null` is the honest
         * answer, and it is the one the screens already handle.
         */
        fun githubFor(login: String?, accounts: AccountStore, testAccountsAllowed: Boolean): GitHub? {
            if (accounts.isProvisional(login)) return null
            val stored = accounts.account(login) ?: return null
            return if (stored.dev && testAccountsAllowed) {
                DiskGitHub(accounts.devGitHubDir(stored.login), stored.login)
            } else {
                HttpGitHub()
            }
        }
        private const val SEARCH_LIMIT = 4000

        /** How many commits back the "what used to be here" list looks. */
        private const val DELETED_LOOKBACK = 50
        private const val SEARCH_RESULTS = 200
    }
}
