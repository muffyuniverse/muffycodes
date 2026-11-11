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
     * The host that signing in asks who a key belongs to.
     *
     * A seam, not a mock: production leaves it alone and gets [HttpWho]. It is here because the
     * failure half of signing in — a key the host refuses — had no way to happen in a test, so the
     * code that shows the refusal was never run. The one thing that must never be substituted is the
     * answer a *release* build gives, and that is asserted separately.
     */
    private val signInGitHub: () -> Who = { HttpWho() },
    /**
     * Where a hand-run request gets its bytes.
     *
     * **A seam so a test can answer without a socket**, the same rule the agent's own wiring follows.
     * Null means "the real one, with this account's settings", and the default is built lazily rather
     * than passed in so a view model made in a test needs no fetcher at all.
     */
    private val fetcher: Fetcher? = null,
) : AndroidViewModel(app) {

    /** The fetcher a hand-run request uses: the seam's, or the real one obeying this account. */
    private val requestFetcher: Fetcher
        get() = fetcher ?: Fetcher.http(privateAllowed = { settings.browsePrivate })

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
     * The login of the active account, **or null when this phone has none.**
     *
     * It used to be never null: a `~local` account always existed, so "which account" always had an
     * answer. There is no account an app can ship (§3.11), so the honest value is exactly what the
     * person has signed in — and every reader of [workspace] finds nothing rather than a workspace
     * belonging to nobody.
     */
    var activeLogin: String? by mutableStateOf(credentials.activeLogin)
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
        // **The tree the rows are drawn from** (§13d): `git status` in main's view names main's files,
        // so on a branch every mark was attached to a path that is not on the screen — and the screen
        // showed no marks at all while `git` said some paths were ignored.
        val dir = ws.treeOf(project)
        ignoredPaths = git?.onBranch(ws.branchOf(dir))?.open(project)?.use { repo ->
            GitService.ignoredPaths(repo).associate { rel ->
                File(dir, rel).absolutePath to true
            }
        } ?: emptyMap()
    }

    /** The number of unsynced changes of each project, from `git status`. */
    var changesByProject: Map<String, Int> by mutableStateOf(emptyMap())

    /**
     * How many paths in each project are **ignored, so they will never be sent** — per project.
     *
     * **The sentence `state` used to carry, moved to where a person reads it.** An ignored file is
     * not a change and not unsent: it is a file on the phone that will never be on GitHub until
     * somebody tracks it. Without this a project holding a 40 MB video and one note reports
     * "up to date", which is true of the note and a lie about the project.
     *
     * `state` answered it for the agent and has been removed — its two halves were two different
     * questions wearing one name. The fact belongs on the row, because it is a fact for the person
     * rather than a question for the model.
     */
    var ignoredByProject: Map<String, Int> by mutableStateOf(emptyMap())
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

    /**
     * **GitHub's own question, for the account that is signed in** — and nothing else of GitHub's.
     *
     * This used to be a whole API surface: repositories, licences, CI runs, artifacts, environment
     * secrets. All of it is gone, because none of it is the git protocol and every host spells it
     * differently. What is left is the one thing a sign-in cannot do without — asking whose key it
     * has — and it is the only reason this app still knows a vendor's address at all. See [Who].
     */
    fun whoFor(login: String?): Who? = Companion.whoFor(login, accounts, testAccountsAllowed)

    /** The door of the active account, or null when no GitHub account is signed in. */
    val who: Who? get() = whoFor(activeLogin)

    /**
     * True when an account is open, **which is now the same question as "is there a workspace"**.
     *
     * It used to be two: a `~local` account existed before anybody signed in, so "an account exists"
     * and "this phone can act as somebody" were different answers. With no account shipped (§3.11)
     * there is nothing to distinguish — a phone with no account has no workspace at all.
     */
    val signedIn: Boolean get() = account != null

    /**
     * The active account's tree, or null when nobody is signed in.
     *
     * **One instance per account, cached, and the caching is a correctness requirement now.** It used to
     * be `Workspace(dir)` built fresh on every read, which was harmless while the object held nothing but
     * the folder — and became a silent bug the moment [Workspace.activeBranch] existed: `enterBranch`
     * wrote the branch to one instance and the next read built another, so the write went to a throwaway
     * and the screen stayed on `main`. Measured, after two CI rounds of guessing at it.
     *
     * Keyed by login rather than held once, because switching accounts must hand out the new account's
     * tree — and a fresh instance is what puts that account back on `main`, which is what `switchTo`
     * wants anyway.
     */
    val workspace: Workspace?
        get() {
            val login = activeLogin ?: return null
            cachedWorkspace?.takeIf { it.first == login }?.let { return it.second }
            val fresh = Workspace(accounts.dirOf(login), accounts.sharedDir)
            cachedWorkspace = login to fresh
            return fresh
        }

    /** The last [workspace] handed out, with the login it belongs to. Nothing else reads this. */
    private var cachedWorkspace: Pair<String, Workspace>? = null

    /**
     * The account's tree, or null **after saying why** — for a door a person pressed.
     *
     * With no account open there is nothing to act on, and until §3.11 that state could not be reached:
     * a provisional account always existed, so every one of these branches was dead and a silent
     * `?: return` cost nothing. Now it is the state a phone starts in, and a silent return is a button
     * that does nothing — the person presses "New file", the screen does not move, and there is no way
     * to tell that apart from a broken app.
     *
     * **[done] is the act in the past tense**, because that is what a failure has to say: what happened.
     * "No account is open, so nothing was created" is true of every door and names the outcome; a
     * sentence built from the act's own words would have to be written twice, once for the door and once
     * for the sentence, and the second copy is the one that goes stale.
     *
     * **Read doors do not ask this.** They answer empty (`?: return emptyList()`), because they run while
     * a screen is being drawn, and setting a message in composition would redraw for ever.
     */
    private fun workspaceFor(done: String): Workspace? {
        val ws = workspace
        if (ws == null) message = "No account is open, so nothing was $done."
        return ws
    }

    /**
     * Who commits. **An account's own identity, or the app's placeholder when there is none.**
     *
     * The placeholder is reachable only on a phone with no account — where nothing can be committed
     * anyway, because there is no project — so it is a value that must exist rather than one a person
     * can meet.
     */
    private val author: GitAuthor
        get() = account?.author() ?: DEFAULT_AUTHOR

    private val git: ProjectGit?
        get() = workspace?.let { ws -> projectGit(ws) }

    /** A [ProjectGit] for this account, wired to the identity and the vault the rest of the app uses. */
    private fun projectGit(ws: Workspace): ProjectGit = ProjectGit(
        workspace = ws,
        tokenProvider = { activeLogin?.let { credentials.token(it) } },
        authorProvider = { author },
        // **A project on another host carries its own key** (PLAN-portable-2026-11-08 §4 P1). The
        // store answers narrowest-first — the project's, then the account's, then the shared one — so a
        // single lambda is the whole of "this project can reach a host this app knows nothing about".
        vaultToken = { project, branch, host ->
            credentials.secret(project, branch, GitCredentials.SECRET, host)
        },
        // **The host the key belongs to is a fact about the account, not about this app.** Without
        // it, "the account's own host" would be GitHub for every account, which is the assumption
        // making the app GitHub-shaped in the first place.
        accountHost = { activeLogin?.let { accounts.account(it)?.host } ?: GITHUB_HOST },
        gitUser = { activeLogin?.let { accounts.account(it)?.gitUser }.orEmpty() },
        // **The scoped connection first, and the named secret after it.** `PLAN-credentials` §2.2: a
        // git connection is a token *and the name it is presented as*, held at branch, project or
        // account scope. The older `git`-named secret is still read behind it, so a credential
        // somebody stored keeps working.
        gitConnection = { project, branch, host ->
            credentials.gitConnectionFor(project, branch, host)
        },
    )

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
        // **The app's own folder is made before anything asks whether there is an account** (§3.11).
        // It holds the introduction, the system prompt and the library, none of which belongs to an
        // identity — so it exists on a phone that has never signed in, and the first screen a person
        // sees is readable.
        Workspace(accounts.sharedDir, accounts.sharedDir).ensureAppFolder()
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
        val pages = Onboarding.pages()
        if (pages.isEmpty()) return
        if (settings.onboardingDone(pages.size)) return
        // **The app's folder, not an account's** (§3.11). The introduction has to be readable on a
        // phone that has never signed in — that is the whole reason it lives where it does — so this
        // asks for the pages before it asks whether there is a workspace, and the path it sets is the
        // `shared/…` a person sees rather than one only a workspace could produce.
        val at = settings.onboardingAt.coerceIn(0, pages.lastIndex)
        val page = File(File(accounts.sharedDir, Onboarding.DIR), pages[at].name)
        if (!page.isFile) return
        onboarding = true
        Trace.note("onboarding", "page ${at + 1} of ${pages.size}")
        editingPath = listOf(Workspace.SYSTEM_PROJECT, Onboarding.DIR, page.name)
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
     * **GitHub is [signedIn]**, which is now the same question as "is there an account at all" — with
     * no account shipped (§3.11) there is no workspace belonging to nobody.
     *
     * **The model key is [hasModelKey]**, which asks whether the vault holds a readable
     * `@openrouter` row — a key that is stored but locked behind the phone's own lock is not a key
     * the app can use, and closing onboarding on it would leave a person on a chat screen that
     * cannot answer.
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
        val shown = editingFile
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
        // `finishOpenRouterSignIn`) and a copy of this in each is a copy that can drift. This is the one
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
        // **Nothing lands in the app's held paths** — `shared` itself and `shared/schemas/`. A
        // whole-path question rather than a name one, because every folder there is named something
        // ordinary; and the *same* rule the lock applies, so the picker cannot offer a target the
        // command would refuse. `shared/requests/` is deliberately not among them: it is where the
        // person keeps the forms the app sends (§3.8.1).
        ws.relative(dir)?.let { if (ws.isAppHeld(it)) return false }
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
        // The folders the app owns exist from the moment the account is shown, so that an empty
        // account and a new one look the same.
        //
        // **But never for an account that has just been removed.** `rememberAccount`/`removeAccount`
        // deletes the account's folder and then asks for a redraw; a redraw that re-made the folders
        // undid the delete a moment after it happened, and — worse — made `AccountStore.exists` true
        // again, so a queued save found the guard open and wrote into a tree nobody owned. Found by
        // `a queued save alone does not bring a deleted account back` failing on a loaded shard.
        val login = activeLogin
        if (login != null && accounts.exists(login)) ws.ensure()
        projectNames = ws.projectNames()
        entries = currentDir?.let { ws.list(it) } ?: emptyList()
        pins = ws.pins()
        lockedPaths = ws.locked()
        agentSessions = AgentSessions(ws.accountDir).read()
        refreshHeartbeat(ws)
        // The first refresh of a new process is where a session that survived a reboot gets its
        // clock back: the answer is read here, but nothing else in a refresh would start it.
        applyService(agentSessions)
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

    /**
     * **How much is unsent in the tree the person is looking at** — which is not always main's (§13d).
     *
     * The list on the account screen asks about projects, and a project's own count is main's. But the
     * strip drawn *inside* a project described the tree on screen, and it read main's count: on a
     * branch with five unsent files it said **"up to date"**, which is the one claim this app must
     * never get wrong.
     */
    var changesHere: Int by mutableStateOf(0)
        private set

    /** How many paths of the open tree are ignored, for the same rows and the same reason. */
    var ignoredHere: Int by mutableStateOf(0)
        private set

    /** Recounts the unsynced changes of every project, and of the tree on screen, behind the UI. */
    fun refreshChanges() {
        val ws = workspace ?: return
        val g = git ?: return
        val names = ws.projectNames()
        // **Read before the worker starts**, because both are state that can change while it runs and
        // the answer must describe the screen the person was looking at when they asked.
        val here = currentProject
        val branch = activeBranch
        onWorker {
            val counts = names.associateWith { g.changes(it).total }
            val ignored = names.associateWith { g.ignoredCount(it) }
            val onBranch = g.onBranch(branch)
            val mine = if (here != null && ws.projectNames().contains(here)) {
                Pair(onBranch.changes(here).total, onBranch.ignoredCount(here))
            } else {
                null
            }
            main.post {
                changesByProject = counts
                ignoredByProject = ignored
                if (mine != null) {
                    changesHere = mine.first
                    ignoredHere = mine.second
                }
            }
        }
    }

    /**
     * **Whether a minted model key is on this phone and readable.**
     *
     * One question, one row: the vault's `@openrouter` secret for OpenRouter's own host. It used to
     * ask `model_key_<login>`, a second record of the same key kept beside the vault — which is the
     * duplication that made `ConnKind.LLM` exist at all (§3.11.4). Minting writes the vault row now,
     * so this is where the app reads it.
     *
     * **The host matters.** A secret is stored bound to the host it was made for, so this asks for
     * OpenRouter's specifically: a row of the same name for another server is not this key.
     */
    val hasModelKey: Boolean
        get() = credentials.secret(
            project = null,
            branch = null,
            name = Schemas.MINTED_KEY_NAME,
            host = Secrets.hostOf(OPENROUTER_BASE),
        ) != null

    /**
     * **Keeps a model key as the vault's `@openrouter` row, and reads it back before saying so.**
     *
     * The paste path and the mint path end here, deliberately: a key that arrived by PKCE and a key
     * that was typed are the same key, and two save paths would be two chances for one of them to
     * forget the read-back that makes a kept key trustworthy.
     *
     * **One row, one name, one scope.** The scope is the account's, which is the narrowest that does
     * not name a project; a person who wants it wider or narrower moves it on the vault screen, which
     * is where every other secret's scope is chosen. Nothing is granted to an agent by minting — the
     * form names it, and a form is a file a person wrote.
     *
     * @return null when the key is kept, or the sentence to show when it is not.
     */
    fun saveMintedKey(key: String): String? {
        val login = activeLogin
            ?: return "There is no account signed in on this phone, so there is nobody to keep a key for."
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return "The key is empty, so nothing was kept."
        val host = Secrets.hostOf(OPENROUTER_BASE)
        val problem = saveSecret(SecretScope.Account, Schemas.MINTED_KEY_NAME, trimmed, host)
        if (problem != null) return problem
        Trace.note("key-kept", "the OpenRouter key reads back from the vault")
        Trace.said("The OpenRouter key is kept on this phone as the secret @${Schemas.MINTED_KEY_NAME}")
        // **The second half of the way out.** If GitHub was already connected, this is the connection
        // that finishes onboarding — and the person is on a page offering the sign-in, so they should
        // not have to swipe anywhere to be let out.
        closeOnboardingIfConnected()
        return null
    }

    // ---- named secrets ------------------------------------------------------

    /**
     * The names of this account's secrets, **never their values**.
     *
     * A name is what an article or a tool holds, so a name is what a screen may show. A value is the
     * thing the whole store exists to keep out of sight, and a screen that could display one would be
     * the leak this mechanism is for.
     */
    fun secretNames(): List<String> = credentials.secretNames()

    /** Every secret as a row: its name, the host it is bound to, and the scope it lives in. */
    fun secretRows(): List<SecretRow> = credentials.secretRows()

    /** The host a secret may be used at, or null when there is no such secret. */
    fun secretHost(name: String): String? = credentials.secretHost(name)

    /**
     * Keeps a **general** secret, and reads it back before saying so.
     *
     * **A shorthand for [saveSecret] with the general scope**, whose read-back has to ask the general
     * store: the precedence lookup would answer with an account secret of the same name, and this
     * would then say "kept" about a row that was never written.
     */
    fun saveGeneralSecret(name: String, value: String, host: String): String? =
        saveSecret(SecretScope.General, name, value, host)

    /**
     * Every project, for the scope selector.
     *
     * **The system project is excluded**: it holds the account's own settings and cannot be deleted,
     * so a secret scoped to it would be a secret nobody can ever remove by deleting its project.
     */
    fun scopeProjects(): List<String> = projectNames.filterNot { Workspace.isSystem(it) }.sorted()

    /**
     * **Where one scope keeps its value, read back before saying so.** Every writer goes through here,
     * so the read-back rule is one rule and not one per scope.
     *
     * The read-back asks **the scope's own key**, not the precedence lookup: asking with precedence
     * would let a wider value answer for a scope whose write silently failed, and the app would say
     * "kept" about a row that was never written.
     */
    private fun keepInScope(
        scope: SecretScope,
        name: String,
        value: String,
        host: String,
    ) {
        credentials.setSecret(scope, name, value, host)
    }

    /** The value [scope] itself holds, ignoring any wider scope that would shadow it. */
    private fun inScope(scope: SecretScope, name: String, host: String): String? = when (scope) {
        SecretScope.Account -> credentials.secret(name, host)
        SecretScope.General -> credentials.generalSecret(name, host)
        is SecretScope.Project -> credentials.secret(scope.name, scope.name, name, host)
        // **A branch's own row**, which the precedence lookup finds first anyway — but said explicitly
        // so this function stays a statement about one scope rather than about the order between four.
        is SecretScope.Branch -> credentials.secret(scope.project, scope.branch, name, host)
    }

    /**
     * **The branches [project] has**, `main` first — the scope window's list.
     *
     * Asked of git, not of the worktree folders. A branch can exist before it has a tree (`+ Branch`
     * makes the ref; the tree appears when the branch is entered), and a person setting up a token for
     * `feature` may well do it *before* opening the branch — so offering only the trees would make the
     * value impossible to store at the moment it is most wanted. The row's promise is about a branch,
     * and a branch is a ref.
     *
     * `main` is always among them — every project is made with it — but the fallback is here rather
     * than assumed, because a repository that will not open must still answer with something.
     */
    fun branchesOf(project: String): List<String> {
        val repo = git?.open(project) ?: return listOf(AgentSession.MAIN)
        return try {
            GitService.localBranches(repo).ifEmpty { listOf(AgentSession.MAIN) }
        } finally {
            repo.close()
        }
    }

    /** Keeps [name] where [scope] says, and reads back **that row** before answering "kept". */
    fun saveSecret(scope: SecretScope, name: String, value: String, host: String): String? {
        val login = activeLogin
            ?: return "There is no account signed in on this phone, so there is nobody to keep a secret for."
        val problem = secretProblem(name, value, host)
        if (problem != null) return problem
        val trimmedHost = Secrets.hostOf(host).ifEmpty { host.trim().lowercase() }
        keepInScope(scope, name.trim(), value, trimmedHost)
        return if (inScope(scope, name.trim(), trimmedHost) != null) {
            Trace.note("secret-kept", "$name for $trimmedHost (${scopeLabel(scope)})")
            Trace.said("The secret $name is kept for $trimmedHost")
            secretSaved++
            null
        } else {
            Trace.note("secret-not-kept", "$name saved and could not be read back")
            Trace.said("The secret $name was stored and could not be read back, so it will not work.")
            "The secret was stored, and this phone could not read it back. Unlock the phone and try " +
                "again; if it keeps happening, the phone's key has changed and every secret has to be " +
                "entered again."
        }
    }

    /**
     * Keeps a secret for [project], or for the account when it is null.
     *
     * **A shorthand for [saveSecret] with a scope**, kept because the project question is the one most
     * callers have. @return null when the secret is kept, or the sentence to show when it is not.
     */
    fun saveSecret(name: String, value: String, host: String, project: String? = null): String? =
        saveSecret(
            project?.let { SecretScope.Project(it) } ?: SecretScope.Account,
            name,
            value,
            host,
        )

    /** Forgets a secret, so a value that is no longer wanted cannot be used by anything. */
    fun forgetSecret(name: String, scope: SecretScope) {
        credentials.forgetSecret(name, scope)
        Trace.note("secret-forgotten", "$name (${scopeLabel(scope)})")
        secretSaved++
    }

    /** Bumped when a secret changes, so a screen listing them can notice. */
    var secretSaved: Int by mutableStateOf(0)
        private set

    // ---- the connections a scope holds ----------------------------------------------------

    /**
     * **What a window on [scope] opens showing**: the values in force for this worktree, or the
     * scope's own when it has one.
     *
     * The account's own record answers for the account scope, because the account *is* the credential
     * for the host it was made against; a narrower scope's row is shown when it wrote one, so a window
     * cannot show one host and save a token for another.
     */
    fun connectionShown(
        scope: SecretScope,
        kind: ConnKind,
        project: String?,
        branch: String?,
    ): ConnectionDraft {
        val own = credentials.connection(scope, kind)
        return ConnectionDraft(
            kind = kind,
            // **The account scope has no project to ask**, and its own host is the honest answer:
            // it is the host the sign-in token was made against.
            address = own?.address
                ?: project?.let { host(it, branch.orEmpty()) }
                ?: activeLogin?.let { accounts.account(it)?.host }.orEmpty(),
            user = own?.user ?: activeLogin?.let { accounts.account(it)?.gitUser }.orEmpty(),
            nameable = own?.nameable == true,
        )
    }

    /**
     * The host a project's remote names, or null — what a git window fills its address with.
     *
     * **Read, not stored.** The address is the remote's own fact (`remote.origin.url`); the vault keeps
     * the host a token is for, and this is how the window offers the one it already knows.
     */
    fun host(project: String, branch: String): String? {
        val ws = workspace ?: return null
        val url = try {
            projectGit(ws).onBranch(branch).remoteUrl(project)
        } catch (e: Throwable) {
            null
        } ?: return null
        return Secrets.hostOf(url).takeIf { it.isNotEmpty() }
    }

    /**
     * **Keeps what a window typed, and writes nothing for a field that was left alone.**
     *
     * Two records rather than one, because they inherit differently: the pair (address + key + shape) is
     * atomic, and the choice (model + effort) is per field. A blank address therefore writes no pair,
     * and a blank key *beside* an address means "keep the key that is here" — which is why the window
     * never fills a key back in.
     */
    fun saveConnection(
        scope: SecretScope,
        draft: ConnectionDraft,
        onDone: (Boolean) -> Unit = {},
    ) {
        draft.problemSaid()?.let { said ->
            message = said
            onDone(false)
            return
        }
        // **Only what changed is written.** The window opened on the values in force, so saving it
        // unchanged must write nothing — otherwise every field it showed would be pinned at this scope
        // and stop following the parent. See [ConnectionDraft.changesAgainst].
        val (project, branch) = placeOf(scope)
        val inForce = connectionShown(scope, draft.kind, project, branch)
        val changed = draft.changesAgainst(inForce)
        // **An empty row removes the row**, which is what "inherit" means; the explicit act of removal
        // is still "Forget", and the two arrive at the same place on purpose.
        credentials.setConnection(scope, draft.kind, changed.connection() ?: Connection())
        Trace.note("connection-saved", "${draft.kind.name.lowercase()} at ${scopeLabel(scope)}")
        connectionSaved++
        onDone(true)
    }

    /** Forgets a scope's connection — **both halves**, because a model with no address is meaningless. */
    fun forgetConnection(scope: SecretScope, kind: ConnKind) {
        credentials.forgetConnection(scope, kind)
        Trace.note("connection-forgotten", "${kind.name.lowercase()} at ${scopeLabel(scope)}")
        connectionSaved++
    }

    /** Bumped when a connection changes, so a screen showing one can notice. */
    var connectionSaved: Int by mutableStateOf(0)
        private set

    /**
     * **Checks a connection before it is kept**, with the server's own words.
     *
     * Runs on the worker because it opens a socket, and hands the sentence back on the main thread
     * because a dialog is what shows it. The key it uses is the one that *would* be used: a typed one if
     * the box has one, the stored one otherwise — checking with a key that is not the one a turn will
     * send would check a different connection.
     */
    fun checkConnection(
        scope: SecretScope,
        draft: ConnectionDraft,
        project: String?,
        branch: String?,
        then: (String) -> Unit,
    ) {
        draft.problemSaid()?.let {
            then(it)
            return
        }
        val key = draft.secret.ifBlank {
            credentials.connection(scope, ConnKind.GIT)?.secret
                ?: activeLogin?.let { credentials.token(it) }
        }
        // **A git connection is measured by the protocol's own question**: `ls-remote` says whether
        // the address is real and whether this token may read it, and there is no second way to
        // ask.
        //
        // **Against the project's own address, not the host.** A host is not a repository — asking
        // `gitlab.com` whether it exists says nothing about whether this token may read *this*
        // project — so the check uses `remote.origin.url`, which is where the address lives.
        val ws = workspace
        // **No project, no address to check against** — and the account's git credential is the
        // sign-in token, whose window already exists, so this says so rather than pretending.
        val remote = if (project == null) {
            null
        } else {
            ws?.let {
                try {
                    projectGit(it).onBranch(branch.orEmpty()).remoteUrl(project)
                } catch (e: Throwable) {
                    null
                }
            }
        }
        if (remote.isNullOrBlank()) {
            then(
                "This check asks the project's own address, and this project has none yet. " +
                    "Set the address first, then check the token against it.",
            )
            return
        }
        val user = draft.user
        onWorker {
            val said = when (val answer = GitService.lsRemote(remote, key, user)) {
                is GitService.RemoteAnswer.Branches ->
                    "The host answered and accepted the token. It has " +
                        "${answer.names.size} branches this token can see."
                is GitService.RemoteAnswer.Refused -> "The host refused: ${answer.why}"
            }
            main.post { then(said) }
        }
    }

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
     * **Nothing on this phone opens web pages**, said in words, naming the address.
     *
     * The activity is where the attempt happens — it is the only place with a `Context` — and the
     * sentence is the app's, so it comes back here rather than being written by a screen. The address
     * is in the words because a person whose phone cannot open a page still needs somewhere to type
     * it.
     */
    fun noBrowser(address: String) {
        Trace.note("no-browser", address.take(60))
        message = "Nothing on this phone opens web pages, so $address stays a link in the text."
    }

    /**
     * **What a link in a document does when it is pressed.** [base] is the folder the document lives
     * in, which is what a relative address means.
     *
     * Pulled out of the editor's composable so the rule is testable without a screen — and because
     * the rule is the app's, not the screen's. The editor calls this and nothing else.
     *
     * **Three kinds of address and no more**: `app:` (the app acting on its own page), an `http(s)`
     * address (the browser, outside), and **a path** (a file of this account, opened in the editor).
     * A path is the one the agent writes most: `[the notes](notes.md)` in a message, and the person
     * presses it and lands in the file.
     *
     * **`app:` is refused outside the system project.** A locked page is written by the app and
     * cannot be edited, so its links are the app's own. A *cloned* page is ordinary user text, and a
     * cloned page carrying `app:delete-account` would be a document that can act — the door this
     * feature must not open.
     */
    fun followLink(address: String, base: File?) {
        when {
            // **`app:` is refused, in every document, and the refusal is the whole of the rule.** It
            // was a vocabulary of verbs a page could press — `connect-github`, `use-token` — which
            // made a Markdown file into a control panel and made "is this page the app's own?" a
            // security question whose answer was a path comparison. The pages teach the journey now
            // and every act has its own door, so there is nothing left for a verb to do: an address
            // with a scheme the app does not serve is recorded and ignored.
            address.startsWith("app:") ->
                Trace.note("link-refused", "app: in a document")
            address.startsWith("http") -> openLink(address)
            else -> openPath(address, base)
        }
    }


    /**
     * **A link in a chat message.** Same three kinds, one difference that matters: **`app:` never
     * acts here.**
     *
     * A message is not a document the app wrote. The agent can type `app:connect-github` into a
     * reply, and it must not press anything — the same rule that refuses `app:` in a cloned page,
     * applied to the place where the text is written by a model rather than a person.
     *
     * **Paths resolve against the branch's working tree**, which is the folder every tool composes
     * its paths from (`AgentTools.projectDir`). So the address the agent was given — `shot` answers
     * with `agent/browser/shot-1.png`, relative to exactly that folder — is the address the person
     * can press, with no translation between the two.
     */
    fun followChatLink(address: String) {
        val project = chatProject ?: return
        when {
            address.startsWith("app:") ->
                Trace.note("link-refused", "app: in a message")
            address.startsWith("http") -> openLink(address)
            else -> openPath(address, workspace?.treeOf(project, chatBranch))
        }
    }

    /**
     * **A path someone pressed, opened in the editor.**
     *
     * [base] is what a relative path is relative to: the document's folder, or a branch's tree. The
     * file must be one this account can name — `Workspace.relative` is the whole of that question,
     * and it answers null for anything outside. **A path it cannot name is refused in words**, not
     * dropped: the alternative is the editor closing, which looks exactly like a link that did
     * nothing.
     *
     * **Nothing is created.** A link to a file that is not there is a link to a file that is not
     * there; opening an empty editor on that path would make the next Save write it, and a
     * mistyped address would quietly become a new file.
     */
    fun openPath(address: String, base: File?) {
        val ws = workspace
        if (ws == null) {
            message = "No account is open, so $address cannot be looked for."
            return
        }
        val plain = address.substringBefore('#').substringBefore('?')
        if (plain.isBlank()) {
            message = "That link names no file."
            return
        }
        val file = File(plain).let { named ->
            // **An absolute path is taken as given**, which is how a link out of Downloads reads;
            // everything else is beside the document.
            if (named.isAbsolute) named else base?.let { File(it, plain) }
        }
        if (file == null || !file.isFile) {
            message = "\"$address\" is not a file, so there is nothing to open."
            return
        }
        // **The app's folder is readable with no account** (§3.11) — it is the one place a phone that
        // has not signed in can open a file, and it is where the introduction lives. Everything else
        // must be one the workspace can name. The question is asked through the workspace, which is
        // what already knows where its two roots are; a second canonical-path comparison here was the
        // same answer computed twice.
        if (ws.relative(file) == null && !ws.isInAppFolder(file)) {
            message = "\"$address\" is outside this account, and the editor reads this account's files."
            return
        }
        // **A file inside a branch's worktree opens that branch.** `relative` reports a worktree path
        // as the project path it stands for — the branch is the difference — and `fileAt` reads that
        // path back through `activeBranch`, so a file pressed in `feature`'s chat would otherwise open
        // main's copy of the same name. Setting the branch is what makes the two agree, and it is the
        // same first act `enterBranch` performs.
        val branch = ws.branchOf(file)
        if (branch != ws.activeBranch) {
            ws.activeBranch = branch
            forgetBranches()
        }
        openEditor(file)
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
     * Exchanges the code the person pasted for a key, and **keeps it as a vault secret.**
     *
     * **The operator's shape (2026-11-10):** *"openrouter oauth key minting should be an option in
     * general settings in the same block with vault secrets management button - because it should be
     * used to basically mint an openrouter key as a secret that then could be given an access scope."*
     *
     * So this writes a row a template can name (`"key": "@openrouter"`) and an `http` call can use
     * (`"auth": "@openrouter"`) — **one record, one reader set**, rather than a model key the chat read
     * directly and a vault row it also needed. The scope is not chosen here: the key arrives without
     * one, and [saveSecret]'s account default is where a person grants it afterwards.
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
                    // **The minted key is a secret with a name**, and the name is the app's word for
                    // it: `openrouter`. A template that says `"key": "@openrouter"` finds it without
                    // anybody retyping a 70-character token into a form.
                    onDone(saveMintedKey(key))
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

    /** How many paths of [project] are ignored, so they will never be sent. */
    fun ignoredOf(project: String): Int = ignoredByProject[project] ?: 0

    /**
     * **The last thing each project's host said, when what it said was "no".**
     *
     * Only failures are held, so absence means *nothing is wrong* — either the host answered or
     * nobody has asked yet, and both are drawn the same way: the ordinary count. Reachability is a
     * fact about *now*, so a remembered "it answered" from ten minutes ago would be the exact lie
     * this check exists to stop; a remembered failure is worth keeping until something retries,
     * because it is what the person needs to see.
     *
     * **This is not a second status system.** [unreachableOf] is the one question "can this project
     * talk to its host, and if not why", and the row, the strip, `canPublish` and the push refusal
     * all read it. It replaced `isLocalProject`, which answered "no address" and nothing else and
     * would have been a second, competing answer to the same question.
     */
    var hostSaidNo: Map<String, String> by mutableStateOf(emptyMap())
        private set

    /**
     * **Why [project] cannot reach its host, or null when nothing is wrong.**
     *
     * Two cases, and the cheap one is answered without a network call:
     *
     * - **No address.** Asked of the repository's config, so the list can say it for every project
     *   without touching the network. The operator: *"user can set no remote url, but then his agent
     *   will not be able to push changes, and project should display that there is no connection."*
     * - **An address the host did not answer.** Remembered by [checkReach] from the last time
     *   somebody asked — on entering a project, and after the address changes.
     */
    fun unreachableOf(project: String): String? =
        hostSaidNo[project]
            ?: if (remoteUrlOf(project).isNullOrBlank()) {
                "No address is set, so nothing can be sent from this project."
            } else {
                null
            }

    /**
     * **Asks the project's host whether it is there** — on entry, and after an address changes.
     *
     * `ls-remote` is the protocol's own answer to "is this address real, and may this key read it",
     * and the operator asked for the check at the moment a person enters a project: a project whose
     * remote has been deleted, renamed or moved is otherwise indistinguishable from a healthy one
     * until a push fails much later, with a sentence about refs.
     *
     * **A refusal is a sentence, not a flag.** `ls-remote` answers with the reason in words a person
     * can act on, and those words are what the notice shows.
     */
    fun checkReach(project: String) {
        val g = git ?: return
        val address = g.remoteUrl(project)
        if (address.isNullOrBlank()) {
            // Nothing to ask. `unreachableOf` already answers this from the config, so there is
            // nothing to record — and recording it would be a second copy of the same fact.
            return
        }
        onWorker {
            val answer = try {
                g.checkRemote(project, address)
            } catch (t: Throwable) {
                GitService.RemoteAnswer.Refused(Secrets.describe(t))
            }
            main.post {
                hostSaidNo = when (answer) {
                    is GitService.RemoteAnswer.Branches -> hostSaidNo - project
                    is GitService.RemoteAnswer.Refused ->
                        hostSaidNo + (project to "The remote at $address did not answer. ${answer.why}")
                }
            }
        }
    }
    /**
     * The remote a project pushes to, or null when it has none.
     *
     * **The same answer the app acts on**, exposed rather than re-derived: a test that worked out a
     * project's repository from its folder name would be testing its own arithmetic, and the parse the
     * phone actually performs is the thing worth asking about.
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
        // **Showing a path means not showing the account list.** One place says so, because every
        // caller of this door is a move into the browser: a folder row, a link, a branch, the
        // templates row in Settings. Without this line the templates row set the path and left the
        // accounts list drawn over it, which is a door that looks open and is not.
        onAccountsScreen = false
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
            // **The tree the person is looking at, not main's.** This was `projectDir`, so a search
            // inside a branch read main's files and answered with paths the branch does not have —
            // which is the same defect as an agent writing main's files from a branch, in the one
            // place a person would notice it as "search is wrong".
            val root = ws.projectNames().firstOrNull { it == project }
                ?.let { ws.treeOf(it) } ?: return emptyList()
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
                // **Never into a link.** Every tree holds `system` pointing at the account's own folder
                // (§13f); descending would return the prompt and the templates as though they were this
                // project's text — and the same files again under every other project.
                if (child.isDirectory && !Workspace.isLink(child)) stack.addLast(child)
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
        val ws = workspaceFor("created") ?: return false
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
        val ws = workspaceFor("created") ?: return false
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
        val ws = workspaceFor("created") ?: return false
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
        val ws = workspaceFor("renamed") ?: return null
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
        val ws = workspaceFor("moved") ?: return false
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
        val ws = workspaceFor("copied") ?: return false
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
        val ws = workspaceFor("deleted") ?: return false
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
        val ws = workspaceFor("locked") ?: return
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
        val ws = workspaceFor("ignored") ?: return
        val path = ws.relative(file) ?: return
        val project = ws.projectOf(file) ?: return
        val inside = insideOf(ws, file, path) ?: return
        // **The state is asked of git, not of a flag.** Whether a path is ignored is a question
        // `.gitignore` answers, and a second copy of that answer is a second thing that can be
        // wrong. The index is asked the same way for "is it tracked", which is the other half of
        // what the button changes.
        // **The file's own tree**, because whether a path is ignored is a fact about the `.gitignore`
        // in the tree that holds it — and the line this writes goes there too.
        val ignored = git?.onBranch(ws.branchOf(file))?.open(project)
            ?.use { repo -> GitService.isIgnored(repo, inside) } ?: false
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
        val ws = workspaceFor("ignored") ?: return
        Trace.tap(if (ignore) "ignore" else "track", inside)
        // **The tree the file is in.** A branch's `.gitignore` is that branch's file: writing main's
        // would change a document the person is not looking at, and the branch's own status would not
        // move — which reads as "ignore did nothing".
        val ignoreFile = File(ws.treeOf(project, ws.branchOf(file)), Workspace.IGNORE)
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
            }
        }

        message = when {
            ignore && stillTracked -> "Ignoring $inside, but it is still in the repository"
            ignore && wasPushed ->
                "Ignoring $inside. Its earlier versions stay at the remote — ignoring stops it " +
                    "being sent from now on; it does not take back what was sent."
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
     * Copies [file] to the phone's Downloads — the ⋮ item, and the person's own exit.
     *
     * **Report 23**: *"I should be able to clone/move to the downloads - add this as a choice"*. It
     * goes through `Export.copyToDownloads`, the same call the browser's row menu makes, so every
     * route to Downloads is one implementation — two would drift in the ways nobody notices until a
     * file arrives wrong.
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
        val ws = workspaceFor("pinned") ?: return
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
        val ws = workspaceFor("selected") ?: return
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
        val ws = workspaceFor("moved") ?: return false
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
        val ws = workspaceFor("copied") ?: return false
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
        val ws = workspaceFor("deleted") ?: return false
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
        commitProject(ws.projectOf(file) ?: return, what, ws.branchOf(file))
    }

    /**
     * **The repository as the file's own tree sees it.**
     *
     * A save is committed by the tree the file is in. `git` is main's view, so a file written in
     * `feature`'s worktree was saved, then a commit was attempted against main's index — which held
     * nothing from this edit — and the app reported a successful save with no version behind it. That
     * is the one promise the app makes, broken quietly.
     *
     * The branch is read from the **file**, not from [activeBranch], because the two can disagree: the
     * person can leave a branch and act on a file that is still inside it.
     */
    private fun gitFor(file: File): ProjectGit? {
        val g = git ?: return null
        val ws = workspace ?: return g
        return g.onBranch(ws.branchOf(file))
    }

    /**
     * Stages [project]'s worktree, whatever changed in it.
     *
     * Used for a change that spans two repositories — a move across the boundary — where the file
     * that changed is no longer in one of them, so the caller cannot ask the file's own tree.
     *
     * **Staging, not committing** (2026-11-10): a save records the work and a push makes the version.
     */
    private fun commitProject(
        project: String,
        what: String,
        branch: String = AgentSession.MAIN,
    ) {
        val g = git ?: return
        val login = activeLogin
        onWorker {
            if (login != null && accounts.exists(login)) g.onBranch(branch).stage(project)
            // The row's count was read *before* this, so it still says the change is waiting after it
            // has been recorded. Ask again, now that the answer has changed.
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
        // **The tree on screen**, for the same reason the strip counts it (§13d): the window lists
        // what is unsent *here*, and main's list on a branch is both wrong and unopenable — the paths
        // do not exist in the folder the person is looking at.
        val g = git?.onBranch(activeBranch) ?: return
        onWorker {
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
        val ws = workspaceFor("compared") ?: return
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
        val ws = workspaceFor("opened") ?: return
        val project = currentProject ?: return
        // **Straight into Diff, against the last synced commit.** The operator: "files from this
        // list when clicked should open in diff mode." A person who tapped a file in a list called
        // "Changes to send" has already said what they want to see, and making them press Diff
        // after that is asking the same question twice.
        // **The tree the change is in**, not the project's root: on a branch this opened main's copy,
        // which either does not have the change or holds a different text.
        openEditor(File(ws.treeOf(project), path), Opening.UNSENT)
    }

    /** The file the editor is showing, or null. */
    val editingFile: File?
        get() = editingPath?.let { path ->
            // **The app's folder is readable with no account** (§3.11), and it is the one path that
            // can be: there is no project to belong to, so the workspace is not what answers for it.
            // Everything else still goes through the workspace, which is what keeps a branch's
            // worktree redirection in one place.
            workspace?.fileAt(path) ?: appFileAt(path)
        }

    /**
     * A path inside the app's folder, or null when it names anything else.
     *
     * The same conversion `Workspace.partsOf` performs in reverse — `shared/a/b` is
     * `<files>/shared/a/b` — kept here because this is the one reader that must work when there is no
     * workspace to ask.
     */
    private fun appFileAt(path: List<String>): File? {
        if (path.firstOrNull()?.equals(Workspace.SYSTEM_PROJECT, ignoreCase = true) != true) return null
        return path.drop(1).fold(accounts.sharedDir) { dir, name -> File(dir, name) }
    }

    /**
     * Saves the editor's text. The file is written on disk, then committed to the
     * account's history — which is where a local file's versions come from.
     */
    fun saveEditor(text: String) {
        // **Said, not dropped.** With no account the only file the editor can be showing is one of
        // the app's own — which is locked and refused below — but a guard that returns in silence is
        // the one thing this app does not do, and "no account" is now a state a person can be in.
        val ws = workspace ?: run {
            message = "No account is open, so there is nothing to save into."
            return
        }
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
            // **Staged, not committed.** The operator's decision (2026-11-10): *"we dont commit on
            // save, we commit on push, on save changes are only staged."* So a save records the work in
            // the index and leaves the history for a version somebody decided on — the push, whose
            // message says what the version is.
            //
            // **`changes` still sees it**, because the row's count is asked of the index and the tree,
            // so nothing disappears from the screen; what changes is that ten saves are one version
            // instead of ten, and "what did I change" is a question about the work rather than about
            // the app's timing.
            var refused: String? = null
            if (login != null && accounts.exists(login)) {
                // **The tree the file was written into**, not the project's root — see [gitFor].
                val here = g?.onBranch(ws.branchOf(file))
                val staged = project?.let { here?.stage(it) } ?: true
                if (!staged) refused = "the change could not be staged"
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
                "Saved to the file, but not staged: $refused"
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
        val ws = workspaceFor("photographed") ?: return
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
                    // **Staged, like every other write.** A photograph is the person's work, so it
                    // goes into the index with the file it is about and becomes a version at the next
                    // push — the same rule as a save, because two kinds of change with two rules about
                    // when they are recorded is a history nobody can predict.
                    val project = ws.projectOf(file)
                    project?.let { git?.onBranch(ws.branchOf(file))?.stage(it) }
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
     * **Runs a `.http` file as a request.**
     *
     * The agent reaches the same document through `http`, and this is the person's door to it: a file
     * they wrote, kept in the project, sent with the same expansion and filed in the same
     * `agent/http/` — so "what did this project send" is one folder rather than two.
     *
     * **The text is the one on the glass, not the one on disk.** A person who edited a header and
     * pressed Run means the header they can see; saving first and then sending is two presses for one
     * intention, and the second one is easy to forget.
     *
     * **Everything the vault does still applies.** The host is read from the document's own address, so
     * a secret is supplied only for the host it was stored for — which is why this cannot be a shortcut
     * around `Expand`.
     */
    fun runRequest(text: String) {
        val ws = workspaceFor("sent") ?: return
        val file = editingFile ?: return
        val project = ws.projectOf(file) ?: return
        val tap = Trace.tap("run request", file.name)
        // **The document is read and expanded on the worker**, because expansion reads files and the
        // vault and neither belongs on the thread drawing the screen.
        onWorker {
            val parsed = HttpRequest.parse(text).getOrElse {
                val said = it.message.orEmpty()
                Trace.failed(tap, said, "run request")
                main.post { message = said }
                return@onWorker
            }
            // **The network switch is the same one the agent obeys.** A person sending a request by
            // hand is not a licence to spend a switch they turned off — and the refusal names the
            // switch rather than failing at the socket.
            if (!settings.httpAllowed) {
                val said = "Reaching the network is switched off in this account's settings, so " +
                    "nothing was sent."
                Trace.failed(tap, said, "run request")
                main.post { message = said }
                return@onWorker
            }
            com.example.notes.browser.Addresses.problem(parsed.url, settings.browsePrivate)?.let {
                Trace.failed(tap, it, "run request")
                main.post { message = it }
                return@onWorker
            }
            val host = Secrets.hostOf(parsed.url)
            val names = Expand.names(ws.projectDir(project), host, credentials, project, ws.branchOf(file)) { one ->
                if (one.kind == Expand.Mention.Kind.SECRET) secretSaid(one.name, host) else null
            }
            val request = parsed.expand(host, names).getOrElse {
                val said = it.message.orEmpty()
                Trace.failed(tap, said, "run request")
                main.post { message = said }
                return@onWorker
            }
            HttpRun.send(request, ws.projectDir(project), requestFetcher, settings.fetchBytes)
                .fold(
                    onSuccess = { outcome ->
                        Trace.answered(tap, "${request.method} ${request.url} → ${outcome.status}")
                        main.post {
                            refresh()
                            message = outcome.said
                        }
                    },
                    onFailure = { failure ->
                        val said = failure.message ?: "The request could not be sent"
                        Trace.failed(tap, said, "run request")
                        main.post { message = said }
                    },
                )
        }
    }

    /**
     * Why a `{{secret:…}}` in a request answered nothing, in the vault's own words.
     *
     * **The same sentence the agent gets**, because a person running a request and an agent sending
     * one are asking the same question of the same store — and a person is the one who can act on
     * "it is stored for another host".
     */
    private fun secretSaid(name: String, host: String): String {
        val rows = credentials.secretRows().filter { it.name == name }
        if (rows.isNotEmpty()) {
            val where = rows.joinToString("; ") { row ->
                row.host.ifEmpty { "this phone only" } + " (" + scopeLabel(row.scope) + ")"
            }
            return "The secret \"$name\" is not for $host. It is stored for $where, and the app " +
                "will not send it anywhere else."
        }
        return "There is no secret called \"$name\" on this phone, in this project, or for every " +
            "project. The names it knows are: " +
            credentials.secretNames().joinToString(", ") { "\"$it\"" }.ifEmpty { "none" } + "."
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
     * **`target` is what makes a picture's versioning a file operation**, and the choice is still the
     * person's: saving over the file keeps one chain of versions, and saving beside it keeps both as
     * two files. The screen asks which, and this is the one place both answers land — the same funnel
     * for locks, the record's protection, and the commit, so a new file cannot skip a rule the old one
     * obeys.
     *
     * **It used to be the only way to keep anything.** A binary had exactly one version because its
     * bytes lived outside the tree, so overwriting it destroyed what was there. That is not true any
     * more — every version of a picture is a commit (see `docs/REMOVED-…-lfs.md`) — so "save over" is
     * a choice about how the folder reads rather than a warning about losing history.
     *
     * **A new name does not delete the original.** The person asked for a copy; the file they were
     * editing keeps its own version, and the one they made starts with its own.
     */
    fun savePicture(bytes: ByteArray, target: File? = null) {
        val ws = workspaceFor("saved") ?: return
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
                // **The picture's own tree**, for the same reason as a text save: a picture written in
                // a branch's worktree must be staged there, or a push from that branch would not carry
                // it. Asked of `into` — the file that was just written — and not of the source it came
                // from, which may be a picture in another folder.
                val staged = project?.let { g?.onBranch(ws.branchOf(into))?.stage(it) } ?: true
                if (!staged) refused = "it could not be staged"
            }
            val answer = if (refused != null) {
                "Saved the picture, but not staged: $refused"
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
     * [note] is the message the person typed in the window the Sync button opens, and **it is the
     * version's message now** — the push is where a commit happens (2026-11-10), so the sentence a
     * person writes there is the one the history shows. A blank note means "just push", and a push with
     * staged work and no note writes one from the names that changed, which is what the old autosave
     * did.
     */
    @JvmOverloads
    fun sync(project: String, note: String? = null) {
        val g = git ?: return
        // **Said, not silently ignored**, and said with the *reason* rather than a category: no
        // address and an address the host does not answer are different faults and a person can only
        // act on the second one if the words say which it is. `unreachableOf` is that one answer.
        unreachableOf(project)?.let { why ->
            Trace.failure("sync", why)
            message = "$project cannot be sent: $why"
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
                if (!result.success) lastSyncError = result.message
            }
        }
    }

    /** What went wrong the last time a sync was asked for, for a screen that asks. */
    var lastSyncError: String? by mutableStateOf(null)
        private set

    /**
     * **Makes a project in the open account, from an address** — the one door in.
     *
     * The operator's rule (2026-11-10): *"when you add the address you clone from remote."* There is
     * no project-without-a-remote to make first and no repository to create on a host — creating one
     * is a host's own API call, not git protocol, and this app speaks only the protocol. So a project
     * arrives by cloning, which is also what makes its history the host's history rather than a
     * second, unrelated one.
     *
     * **An empty address is allowed, and it is a fault state rather than a kind.** The operator:
     * *"user can set no remote url, but then his agent will not be able to push changes, and project
     * should display that there is no connection."* A folder with its own repository is still a
     * project — its versions are real and it can be worked in — but nothing can leave it, so the row
     * and the project's own screen say **no connection** instead of pretending to be up to date.
     *
     * The name is checked with [Workspace.nameProblem], unlike a cloned project's: this one may be a
     * folder the person typed rather than a repository whose name a host already accepted.
     */
    fun createProject(
        name: String,
        address: String = "",
        onDone: (Boolean) -> Unit = {},
    ) {
        val ws = workspace ?: run {
            message = "No account is open, so there is nowhere to make a project. Add one first."
            onDone(false)
            return
        }
        val url = address.trim()
        // **The clone is the whole of it.** `addProjectFromUrl` already owns "check an address with
        // `ls-remote`, then fill a folder from it" — every refusal, every sentence — so with an
        // address this is that act with the name already chosen. Calling it here rather than inside
        // the worker below is deliberate: it starts a worker of its own.
        //
        // **Nothing is validated twice.** A name is checked in one place and an address in another,
        // and both of those places are the functions that act on them.
        if (url.isNotEmpty()) {
            addProjectFromUrl(url, name, onDone)
            return
        }
        // **As typed, and only blank falls back.** The app no longer rewrites names — see
        // `Workspace.nameProblem` for the measurement that removed it.
        val clean = name.trim()
        if (clean.isEmpty()) {
            message = "A project needs a name"
            onDone(false)
            return
        }
        Workspace.nameProblem(clean)?.let {
            message = it
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
            // a folder: every save is a version, and a version can be gone back to. It has no
            // address, which is a state the screen shows as **no connection** — see this function's
            // doc.
            val git = ProjectGit(
                ws,
                { null },
                { accounts.account(activeLogin ?: "")?.author() ?: DEFAULT_AUTHOR },
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
            // **The markers are committed**, and this is the one place that is still true. They are
            // the folder tree the app makes before a person has written anything, and a project whose
            // first push would be "four folders" is a project whose first version says nothing. So the
            // scaffold is its own version, made by the app, before the person's work is staged on top
            // of it.
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
                    "Made \"$clean\". It has no address, so nothing can be sent from it until you " +
                        "set one."
                } else {
                    "Made the folder, but its history could not be started"
                }
                onDone(made.isSuccess)
                refresh()
            }
        }
    }

    /**
     * **Sets a project's address — and the local name follows it.**
     *
     * This is the whole of renaming, and the whole of publishing, because both are the same act seen
     * from different sides: a project's name is a consequence of where its repository is, not a thing
     * the app composes from one. The person renames on the host's own website, where the confirmation
     * is the host's, and pastes the new address here; the app asks `ls-remote` whether it is true and
     * then makes the folder agree with it.
     *
     * **Nothing is composed.** `cloneUrlFor` built GitHub's address out of a login and a repository
     * name, which is GitHub's convention for spelling a URL — GitLab's and Gitea's differ, and a table
     * of the differences is exactly what this plan removes. The address is what the person gave us.
     *
     * **The order is check, then address, then name.** The check comes first so a mistyped path or a
     * key the host refuses is a sentence rather than a project that claims to be published and cannot
     * be pushed — the one outcome §8.3 says must never happen. The address is set before the folder is
     * renamed because the address is what was asked for; if the rename then fails, what the person
     * asked for is still true and the sentence says which part did not happen.
     *
     * **A name that does not change is not a rename**, and it is a normal thing: a repository moved to
     * another owner, or to another host, sometimes keeps its name. The sentence says the address
     * changed rather than reporting a rename that did not happen.
     */
    fun setAddress(project: String, url: String, onDone: (Boolean) -> Unit = {}) {
        // **No account, no address — and it is said rather than dropped.** With no account shipped
        // (§3.11) a phone that has not signed in has no project to address, so this is the door for
        // somebody who reached it anyway: an agent, or a screen that has not redrawn.
        val ws = workspace ?: run {
            message = "No account is open, so there is nowhere to keep an address."
            onDone(false)
            return
        }
        val address = url.trim()
        if (address.isEmpty()) {
            message = "An address is needed"
            onDone(false)
            return
        }
        // **The transport first, before any credential is considered.** An SSH address cannot be
        // spoken by this app at all (no `jgit.ssh` is packaged), so it is a fact about the address
        // rather than about the key — and saying so after a key was chosen would be a lie about what
        // failed.
        Secrets.transportProblem(address)?.let {
            message = it
            onDone(false)
            return
        }
        val clean = Workspace.repoNameOf(address)
        val problem = Workspace.nameProblem(clean)
        if (problem != null) {
            message = problem
            onDone(false)
            return
        }
        if (clean == project) {
            // **Nothing to rename.** The address is the whole of this press.
            //
            // **No question about keeping or cloning.** The operator (2026-11-10): *"when you add the
            // address you clone from remote, when you change the address you keep the history and
            // collisions are resolved by the agent on push. no extra dialogue needed."* So the
            // address is written and nothing else is decided here — the next push carries this
            // phone's history to the new host, and if the two histories disagree the agent resolves
            // it where a person can see it happening.
            val pointed = git?.setRemote(project, address) ?: false
            message = if (pointed) "The address of $project is $address" else
                "$project could not be pointed at $address"
            if (pointed) {
                // **The new address is asked about at once**, so a typo is a notice now rather than
                // a failed push later.
                checkReach(project)
                refresh()
            }
            onDone(pointed)
            return
        }
        if (Workspace.isSystem(project) || Workspace.isSystem(clean)) {
            message = if (Workspace.isSystem(project)) {
                "\"$project\" is where this account's settings live, so it keeps its name"
            } else {
                "\"$clean\" is reserved for this account's settings"
            }
            onDone(false)
            return
        }
        if (ws.projectNames().any { it.equals(clean, ignoreCase = true) && it != project }) {
            message = "There is already a project called \"$clean\""
            onDone(false)
            return
        }
        busy = true
        onWorker {
            val checked = git?.checkRemote(project, address)
            if (checked is GitService.RemoteAnswer.Refused) {
                main.post {
                    busy = false
                    message = checked.why
                    onDone(false)
                }
                return@onWorker
            }
            val pointed = git?.setRemote(project, address) ?: false
            if (!pointed) {
                main.post {
                    busy = false
                    message = "$project could not be pointed at $address"
                    onDone(false)
                }
                return@onWorker
            }
            // Not tidied: this folder stands for a repository, whose name is its own.
            val moved = ws.rename(ws.projectDir(project), clean, tidy = false)
            if (moved.isFailure) {
                main.post {
                    busy = false
                    // **Said precisely.** The address did change; the folder did not, and a person
                    // who was told "it failed" would not know which of the two they have.
                    message = "The address is set to $address, but the folder kept its name: " +
                        (moved.exceptionOrNull()?.message ?: "it could not be renamed")
                    refresh()
                    onDone(false)
                }
                return@onWorker
            }
            // **The branch folders come with it, and their pointers are repointed.** Renaming only the
            // project's folder left every worktree under `.muffy/worktrees/<old name>/` — invisible to
            // the app, and with two files naming paths that no longer exist, which JGit reports as a
            // repository with no branch rather than as a failure.
            Worktrees(ws).rename(project, clean)
            main.post {
                busy = false
                message = "Renamed to $clean, at $address"
                // The folder was renamed, and so was the path the screen is looking at — which
                // is now the project's own name, one level shallower than it used to be.
                if (path.firstOrNull() == project) {
                    path = listOf(clean) + path.drop(1)
                }
                onDone(true)
                // **The new address is asked about**, for the same reason as above: the notice on
                // the project's own screen is what makes a moved repository visible.
                checkReach(clean)
                refresh()
            }
        }
    }

    /**
     * **Deletes a project from this phone, and says where the copy on the host still is.**
     *
     * **One act, because there is only one act.** The window used to offer a second choice —
     * "everywhere" — which called `deleteRepo`; that was dropped when `delete_repo` left the token's
     * scopes, because it was the one irreversible capability in the app and a repository is deleted on
     * the host's own page. A choice between doing a thing and doing the same thing with a longer
     * sentence is a question the window has already answered.
     *
     * **The order is the safety**: secrets, then the branch folders, then the folder. The reverse
     * leaves a window in which the folder is gone and its secrets are not, and nothing then knows
     * which project they belonged to.
     */
    // ---- moving a project between accounts (§3.9) ---------------------------

    /**
     * **The accounts a project may go to** — every account on this phone except the one it is in.
     *
     * The menu offers the transfer only when this is not empty, because a transfer with one destination
     * that is where you already are is a window that cannot do anything.
     */
    fun accountsFor(project: String): List<Account> {
        val here = activeLogin
        return accounts.accounts().filter { it.login != here }
    }

    /** Moves [project] out of this account and into [toLogin]'s. */
    fun moveProject(project: String, toLogin: String): Boolean = transferProject(project, toLogin, copy = false)

    /** Copies [project] into [toLogin]'s, leaving this account's exactly as it is. */
    fun copyProject(project: String, toLogin: String): Boolean = transferProject(project, toLogin, copy = true)

    /**
     * The one road for both, because they differ in one argument and agree about every rule.
     *
     * **The destination's name is read, not the login** — [`toLogin`] is what the person picked, and
     * `AccountStore.account` has already answered whether this phone has it. An account that is not here
     * is refused before anything is touched.
     */
    private fun transferProject(project: String, toLogin: String, copy: Boolean): Boolean {
        val ws = workspaceFor("transferred") ?: return false
        val to = accounts.account(toLogin)?.let { accounts.dirOf(it.login) }
        if (to == null) {
            message = "\"$toLogin\" is not an account on this phone, so \"$project\" did not move."
            return false
        }
        Transfer.problem(ws, to, project)?.let { message = it; return false }
        val verb = if (copy) "Copied" else "Moved"
        val done = Transfer.go(ws, to, project, copy)
        // **One refusal, one sentence**, and it is the transfer's own: it names the folder it could not
        // write and whether anything was left behind, which is what the person needs to know.
        done.exceptionOrNull()?.message?.let {
            message = it
            return false
        }
        // **The screen cannot be showing what left.** A moved project's path names a folder that is no
        // longer in this account, so the browser goes back to the account root rather than drawing a
        // row for a folder that has gone; a copy leaves the screen alone.
        if (!copy && currentProject == project) {
            path = emptyList()
            editingPath = null
            historyPath = null
        }
        refresh()
        message = "$verb \"$project\" to $toLogin."
        Trace.tap(if (copy) "copy project" else "move project", "$project -> $toLogin")
        return true
    }

    fun deleteProject(project: String) {
        val ws = workspaceFor("deleted") ?: return
        // **The system project is not deletable.** It holds the settings that belong to the whole
        // account, and a person who deletes it loses the prompt with no way to notice until the
        // agent behaves differently. Refused in the view model rather than greyed out on the row:
        // a screen-only guard is one the next screen will not honour.
        if (Workspace.isSystem(project)) {
            message = "\"$project\" is where this account's settings live, so it stays"
            return
        }
        busy = true
        onWorker {
            // **The project's own secrets go with it**, before the folder does. A secret whose
            // project no longer exists is a row nothing can ever read, and leaving it would be a
            // leak nobody can see. Account-scope secrets are untouched — they were never this
            // project's.
            //
            // Ordered secrets-first because the reverse leaves a window where the folder is gone and
            // the secrets are not, and nothing then knows which project they belonged to.
            val forgotten = credentials.forgetProjectSecrets(project)
            // **Read before the folder goes**, because after `ws.delete` there is nothing left to
            // read it from.
            val remote = git?.remoteUrl(project)
            // **The branch folders go first**, because they live outside the project folder and
            // deleting the folder alone leaves them under `.muffy/worktrees/` for ever — a link to the
            // system folder each, and nothing that lists them to notice.
            Worktrees(ws).removeAll(project)
            ws.delete(ws.projectDir(project))
            main.post {
                busy = false
                // **The address, when there is one.** The sentence used to promise the repository was
                // "still on GitHub" whichever project this was, and to offer a second delete that had
                // already stopped existing; now it says where the copy is and that the host's page is
                // where it goes.
                val address = remote
                message = "Deleted $project from this phone, and ${forgotten} of its secret(s)." +
                    (address?.let { " Its repository is still at $it - nothing here touched it." } ?: "")
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
     * **Adds a project from an address the person supplies** — the universal half of the add window.
     *
     * The address is checked with `ls-remote` *before* a folder is made, so a mistyped path, a key the
     * host refuses, or an SSH address this app cannot speak are sentences rather than a half-made
     * project nobody can clone into. Then the clone itself, which is the same call the picker used to
     * make — there is only one way a repository arrives on this phone.
     *
     * **The name is asked for, not assumed.** The last segment of the address is a prefill, because the
     * remote may be `my/repo` and a slash cannot be a folder name — and a person who wants their local
     * project called something other than their repository is doing something ordinary.
     */
    fun addProjectFromUrl(
        url: String,
        name: String,
        onDone: (Boolean) -> Unit = {},
    ) {
        val ws = workspace ?: run {
            message = "No account is open, so there is nowhere to put the clone."
            onDone(false)
            return
        }
        val address = url.trim()
        if (address.isEmpty()) {
            message = "An address is needed: this is where the repository lives"
            onDone(false)
            return
        }
        Secrets.transportProblem(address)?.let {
            message = it
            onDone(false)
            return
        }
        val clean = name.trim().ifBlank { Workspace.repoNameOf(address) }
        Workspace.nameProblem(clean)?.let {
            message = it
            onDone(false)
            return
        }
        val existing = ws.projectNames().firstOrNull { it.equals(clean, ignoreCase = true) }
        if (existing != null) {
            // **No replace**, the same rule the picker follows: deleting a project and adding it again
            // is one act the person already knows, and a second way to destroy a project would be a
            // second set of rules about what survives.
            message = "\"$existing\" is already a project on this phone. Delete it first if you " +
                "want a fresh copy."
            onDone(false)
            return
        }
        busy = true
        onWorker {
            val git = projectGit(ws)
            // **The check, and it is the feature.** `ls-remote` is the protocol's own answer to "is
            // this address real, and may this key read it", and it is the only one there is.
            val checked = git.checkRemote(clean, address)
            if (checked is GitService.RemoteAnswer.Refused) {
                main.post {
                    busy = false
                    message = checked.why
                    onDone(false)
                }
                return@onWorker
            }
            val result = git.cloneInto(clean, address)
            if (result.success) {
                val wrote = ProjectTree.create(ws.projectDir(clean))
                // **The app's own folders are sent with the project**, exactly as a project made on
                // this phone commits them: they are files in the repository, so leaving them as an
                // unsent change would greet a person with "3 to send" on a project they had not
                // touched — the app's own bookkeeping counting as work waiting to go.
                if (wrote.isNotEmpty()) git.sync(clean, "Start the project's folders")
            }
            main.post {
                busy = false
                message = result.message
                refresh()
                onDone(result.success)
            }
        }
    }

    // ---- the agent's chat ---------------------------------------------------

    /** The project whose conversation is on the screen, or null when none is. */
    var chatProject: String? by mutableStateOf(null)

    /**
     * **Which branch's conversation is open** (§13d), beside the project it belongs to.
     *
     * A conversation is a session's — `agent/` lives inside a working tree, so main's diary and
     * `feature`'s are two files in two folders. The screen followed only the project, which meant
     * opening a chat from a branch showed **main's** turns while the agent wrote to the branch's log:
     * the person would answer yesterday's question and the agent would never see it.
     *
     * `main` by default because opening a chat from the project screen is opening main's.
     */
    var chatBranch: String by mutableStateOf(AgentSession.MAIN)

    /** The open conversation as a session is keyed: `project/branch`. */
    val openChatKey: String get() = AgentSession.keyOf(chatProject.orEmpty(), chatBranch)

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
                AgentLog.open(ws.treeOf(project, chatBranch)).fate()
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

    /**
     * The conversation a burst is running in, or null when none is.
     *
     * **A conversation, not a flag.** `chatWorking` was one boolean for the whole app, so a turn
     * running in `feature` made `main`'s chat say it was working — the header described a turn in
     * another branch, and the box refused a message for a conversation that was idle. The turn knows
     * which worktree it is in; this is where that is remembered.
     */
    private var workingOn: String? by mutableStateOf(null)

    /** True while a burst is running **in the conversation on screen**. */
    val chatWorking: Boolean get() = workingOn != null && workingOn == openChatKey

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
            AgentLog.open(ws.treeOf(project, chatBranch)).contextTokens()
        } catch (t: Throwable) {
            return
        }
        if (size != chatContextTokens) chatContextTokens = size
    }


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
     * Swappable so a test never opens a socket: the box has to be drivable by a script, and being able
     * to say exactly what was sent is most of what makes a test of a chat worth writing.
     */
    var chatDoor: ((Wired) -> Door)? = null

    /** Opens [project]'s conversation on [branch] and lands on its newest turn. */
    /**
     * Opens the chat for **the tree the person is looking at**.
     *
     * Two doors reach the chat and they mean two different conversations: the project's row at the
     * account root is the project, whose own folder is main's tree; the chat button inside a project is
     * the branch the person walked into. This is the second door, so it reads [currentProject] and
     * [activeBranch] rather than assuming — and it reads them here rather than in the screen, because a
     * screen that decided which session it was talking to would be a second answer to that question.
     */
    fun openChatHere(project: String) {
        openChat(project, if (currentProject != null) activeBranch else AgentSession.MAIN)
    }

    fun openChat(project: String, branch: String = AgentSession.MAIN) {
        Trace.tap("chat", project)
        chatProject = project
        chatBranch = branch
        // **A sentence about something else is not about this conversation.** The chat draws
        // `message` above its field — which is where a refused path link lands — so what it shows has
        // to be about the conversation that was just opened rather than about the last thing the
        // browser did.
        message = null
        chatBox = null
        measureChatContext()
        readChat(follow = true)
    }

    // ---- the chat's request form (N5) ---------------------------------------

    /**
     * **Which request form this chat runs**, by name, or null when it has not chosen one.
     *
     * Read from the pointer rather than from the resolved connection, because the pointer *is* the
     * choice: the connection is what the choice resolves to, and a chat that inherited the account's
     * model has not chosen anything.
     */
    val chatFormName: String?
        get() {
            val ws = workspace ?: return null
            val project = chatProject ?: return null
            val path = Schemas.pointer(ws.accountDir, project, chatBranch) ?: return null
            return path.trim('/').substringAfterLast('/').substringBeforeLast('.')
        }

    /**
     * **The units in the chat folder**, by file name, for the settings row's count.
     *
     * `ensure` first, for the same reason [chatForms] does it: a fresh install has no folder, and a row
     * that read "none yet" beside a picker offering three would be two answers to one question.
     */
    fun templateNames(): List<String> {
        val ws = workspace ?: return emptyList()
        Schemas.ensure(ws)
        return Schemas.names(ws, ShapeKind.CHAT)
    }

    /**
     * **The forms a chat may pick from**, by name, each with the model it asks.
     *
     * The model id is under the name because that is what a person is choosing between: two forms
     * that differ only in their model read as two identical rows otherwise.
     *
     * `ensure` first, because a fresh install has no folder and the picker would be an empty window on
     * the first day. It fills only when the folder is absent, so a person who deleted the shipped one
     * keeps it deleted (see [Schemas.ensure]).
     */
    fun chatForms(): List<Pair<String, String>> {
        val ws = workspace ?: return emptyList()
        Schemas.ensure(ws)
        return Schemas.names(ws, ShapeKind.CHAT).map { name ->
            val path = Schemas.unitPath(ShapeKind.CHAT, name)
            val model = Schemas.read(ws, path, ShapeKind.CHAT).getOrNull()
                ?.body?.optString("model").orEmpty()
            name.substringBeforeLast('.') to model
        }
    }

    /**
     * **Points this chat at [name]** — the act the picker exists for.
     *
     * Writes the pointer and then re-measures, so the button's label and the runner's next turn agree
     * the moment the window closes. A name that is not there is refused in words rather than writing a
     * pointer to nothing: a chat pointed at a missing form would refuse every turn with a sentence
     * about a file.
     */
    fun chooseForm(name: String) {
        val ws = workspaceFor("chosen") ?: return
        val project = chatProject ?: return
        // **The label is the name without its extension; the file has it.** `chatForms` strips
        // `.jsonc` so the picker reads "deepseek-1017" rather than a file name, so the name that comes
        // back here is a *label* — and appending it to a path unchanged looked for a file that does not
        // exist. A form that is already named with its extension is left alone, so a caller that passed
        // the file name still works.
        val file = if (name.endsWith(".jsonc")) name else "$name.jsonc"
        val path = Schemas.unitPath(ShapeKind.CHAT, file)
        if (Schemas.read(ws, path, ShapeKind.CHAT).isFailure) {
            message = "There is no request form called \"$name\" in ${Schemas.REQUESTS}/" +
                "${ShapeKind.CHAT.folder}/."
            return
        }
        Trace.tap("choose form", name)
        Schemas.setPointer(ws.accountDir, project, chatBranch, path)
        message = "This chat asks with $file."
        refresh()
    }

    fun closeChat() {
        Trace.tap("close chat", chatProject.orEmpty())
        chatProject = null
        chatBox = null
        // **Closing a chat does not end another conversation's turn.** The flag is per-conversation
        // now, and this one is being closed rather than cancelled.
        if (workingOn != null && workingOn == openChatKey) workingOn = null
        chatLive = ""
        chatWriting = ""
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
        // **The open conversation's tree**, not the project's root (§13d).
        val dir = ws.treeOf(project, chatBranch)
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
        val branch = chatBranch
        if (workspace == null || git == null) return
        Trace.tap("send", said.take(60))
        // **A message during a turn cuts it short instead of being dropped.** v1's C8: the person
        // is the highest priority in the conversation, so the request in flight is abandoned, the
        // calls it carried are answered `1 interrupted`, and these words are the next turn — in
        // that order, because a tool result may not follow a user message. The old behaviour,
        // returning early, made the app look as though it had not heard them.
        if (chatWorking) {
            val key = AgentSession.keyOf(project, branch)
            pendingChats[key] = said
            interrupts[key] = INTERRUPTED
            Trace.note("agent", "$project: the operator spoke during a turn")
            return
        }
        workingOn = AgentSession.keyOf(project, branch)
        // **Speaking to a stopped agent turns it on again**, because the person is the switch:
        // there is no other control they would have used, and a message that vanished into a
        // stopped session would look like the app ignoring them.
        sessionOf(project, branch)?.let {
            if (!it.running) putSession(it.resumed(System.currentTimeMillis()))
        }
        speak(project, branch, said)
    }

    /** One turn said out loud, and what to do when it ends. */
    private fun speak(project: String, branch: String, said: String) {
        val key = AgentSession.keyOf(project, branch)
        workingOn = key
        interrupts.remove(key)
        runTurn(project, branch, said) { rows, end ->
            applyChat(rows, follow = true, from = key)
            if (workingOn == key) workingOn = null
            // **The footer goes when the answer does.** It described an answer in flight; once the
            // turn is over the record holds the answer itself, and a live line left behind would be
            // a description of something that already finished.
            chatLive = ""
            chatWriting = ""
            chatPages = emptyMap()
            message = end.plainly()
            applyEnd(project, branch, end, System.currentTimeMillis())
            refresh()
            refreshChanges()
            // The words that interrupted this turn, sent as the turn they were waiting for. Not
            // before the results were written: the record has to be legal in between.
            val next = pendingChats.remove(key)
            if (next != null && end is Agent.End.Interrupted) speak(project, branch, next)
        }
    }

    /**
     * Ends a turn that never started, **so the screen is not left waiting.**
     *
     * A turn is marked working from the moment the person presses Enter. Every path out of
     * `speak`'s callback clears it, and a path that returns before the callback never runs — which is
     * a chat box that waits for an answer to a question the record never heard.
     */
    private fun finishTurn(project: String, branch: String, said: String) {
        if (workingOn == AgentSession.keyOf(project, branch)) workingOn = null
        chatLive = ""
        chatWriting = ""
        message = said
        Trace.failure(said)
        applyEnd(project, branch, Agent.End.Stopped(said), System.currentTimeMillis())
        refresh()
    }

    /**
     * What to tell the loop when the person speaks mid-turn, or stops the agent — **per conversation.**
     *
     * It was one field, and `cut = { interrupt }` is read by every running loop, so stopping `main`
     * also abandoned `feature`'s turn: one branch's press ending another branch's work. A map keyed by
     * the session that is being cut says the same thing to the loop that owns it and nothing to the
     * others.
     */
    private val interrupts = mutableMapOf<String, String>()

    /** The words that arrived during a turn, sent as the next one — per conversation, for the same reason. */
    private val pendingChats = mutableMapOf<String, String>()


    /**
     * Runs one turn on the worker and hands back what it wrote.
     *
     * **The person's turn, and only that.** The app's own keepalive is the runner's, not this: it
     * builds a request and throws the answer away, so it has nothing to draw and nothing to tell
     * the screen mid-flight. Sharing this function with it would have meant a redraw path nobody
     * waits for and a `said` that can be null — which is exactly the null branch this used to carry.
     */
    private fun runTurn(
        project: String,
        branch: String,
        said: String,
        then: (List<AgentLog.Row>, Agent.End) -> Unit,
    ) {
        val ws = workspace ?: run {
            message = "No account is open, so the agent has nowhere to work."
            return
        }
        // **A turn that cannot start must say so.** This was `git ?: return`, and it returned into
        // nothing: no row, no message, the turn left working — a screen that waits for ever and a
        // record that does not mention the question. `git` opens a repository on every read, so it
        // can fail for reasons that have nothing to do with the turn.
        val g = try {
            git
        } catch (e: Throwable) {
            finishTurn(project, branch, Secrets.explain(e, "The agent could not start"))
            return
        } ?: run {
            finishTurn(project, branch, "There is no project for this conversation yet")
            return
        }
        // **The same resolution the runner uses, for this worktree.** A person's turn and the agent's
        // own wake must be asked with the same model at the same address, or the conversation changes
        // voice depending on who started it — so this asks the chat's form, which is the only answer
        // there is (2026-11-10). A chat with no form gets `missing` and the turn refuses in words.
        val asked = workspace?.let {
            Wired.ofForm(it, credentials, project, branch)
        } ?: Wired.of(ActiveModel("", ""))
        val model = asked.active
        val door = doorFor(asked, project, branch)
        // **The numbers the worktree carries**, resolved with the model by the same function the
        // service uses — the chat's form — so the screen's turn and a poll cannot be held to two
        // different sizes. Read once per turn, because neither changes mid-turn.
        val limits = asked.limits
        // **The tick must not wake this session while the person's turn is running.** One request on
        // one conversation at a time: a wake landing beside this turn would write a second ending and
        // a second set of results, and the person would read the mixture of the two.
        // **Held by the worktree's key**, because the same conversation is what the tick must not
        // wake: `feature`'s agent and `main`'s are two turns, and one holding the other's claim would
        // be a turn nobody can start.
        val release = runner?.hold(project, branch)
        onWorker {
            try {
                val log = AgentLog.open(ws.treeOf(project, branch))
                val agent = Agent(
                    ws.accountDir, project, ws, g, log, door, model,
                    // How much of one result may enter the conversation, as the person wrote it.
                    maxResult = settings.maxResult,
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
                        // **The same numbers this turn was built with**, read per call because
                        // `forget` refuses under the trigger that applies when the call arrives
                        // rather than the one in force when the screen was opened.
                        limits.triggerAt
                    },
                    sessionTokens = settings.sessionLimit,
                    // Read at every boundary rather than captured: the person may speak or stop while
                    // this turn is waiting for an answer, and the loop has to see that when it comes.
                    cut = { interrupts[AgentSession.keyOf(project, branch)] },
                    // **The tools work in this branch's tree** (§13d). Without it a person typing in a
                    // branch's chat would watch the agent write main's files.
                    branch = branch,
                )
                val end = try {
                    // **The screen only ever speaks.** The tick's own pings come from the runner, where
                    // nothing is drawn; a poll built here as well was a second door into the same
                    // conversation, and the two could not agree about who was waiting.
                    agent.burst(said) {
                        val rows = log.rows()
                        main.post {
                            applyChat(rows, follow = true, from = AgentSession.keyOf(project, branch))
                        }
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
    internal fun agentBusy(project: String, branch: String = AgentSession.MAIN): Boolean =
        runner?.isBusy(project, branch) ?: false

    /**
     * Whether **this conversation** has a turn running — the screen's turn or the agent's own.
     *
     * One question, two sources: `workingOn` is a turn the person started from the chat box, and
     * [agentBusy] is one the runner is taking. They are different claims on the same worktree, and a
     * caller asking "is it safe to touch this context" must see both — while a caller asking about
     * `main` must not be told about `feature`.
     */
    private fun busyHere(project: String, branch: String): Boolean =
        workingOn == AgentSession.keyOf(project, branch) || agentBusy(project, branch)

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
    /**
     * The session for [project] on [branch], and **the branch is named rather than assumed.**
     *
     * It used to be `firstOrNull { it.project == project }`, which was exactly right while a project
     * had one agent. With worktree-scoped agents (§13d) a project has one per branch, so "the first
     * session for this project" would answer whichever happened to be written first — `main` sorting
     * before `feature` — and the screen for `feature` would draw main's state. A default of `main`
     * keeps every caller that predates branches honest rather than lucky.
     */
    fun sessionOf(project: String, branch: String = AgentSession.MAIN): AgentSession? =
        agentSessions.firstOrNull { it.project == project && it.branch == branch }

    /**
     * The session as it stands **on disk**, which is not always what the screen has.
     *
     * `agentSessions` is a snapshot taken when a tick was *started*, and the poll that tick began
     * writes its outcome afterwards — from a worker. Anything that needs the result of a poll it
     * just watched finish has to read the store, or it reads the moment before the answer arrived.
     * [sessionOf] stays for the screen, which wants exactly the snapshot it drew.
     */
    fun readSession(project: String, branch: String = AgentSession.MAIN): AgentSession? = workspace
        ?.let { account ->
            AgentSessions(account.accountDir).read()
                .firstOrNull { s -> s.project == project && s.branch == branch }
        }

    /**
     * Switches the agent on for [project], with nothing said yet.
     *
     * The first ping is one interval away, because there is no conversation to keep warm until the
     * person has asked for something — an agent switched on at 09:00 and asked at 09:03 should not
     * have spent three requests warming an empty one.
     */
    fun startAgent(
        project: String,
        branch: String = AgentSession.MAIN,
        now: Long = System.currentTimeMillis(),
    ) {
        Trace.tap("start agent", project)
        // **This branch's session, or none.** Without the branch this found main's and decided the
        // branch's agent was already on — or turned main's on again under the branch's name.
        if (sessionOf(project, branch)?.running == true) return
        putSession(
            AgentSession(
                project = project,
                branch = branch,
                state = AgentSession.State.IDLE,
                lastPollAt = now,
                // **The wait begins at the moment it was switched on.** `warmFrom` at zero makes
                // `warmSince` fall back to `lastPollAt`, and a poll moves that — so the waiting
                // ceiling would be pushed along by every poll and never arrive.
                warmFrom = now,
            )
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
        for (session in on) stopAgent(session.project, session.branch)
    }

    /**
     * **Switches every agent this phone knows about back on.**
     *
     * The counterpart to [stopAllAgents], and it exists because the waiting ceiling made it
     * necessary: an agent that switches itself off after an hour is a *session that stopped*, and
     * before this there was no way to start one again except speaking to its project — which meant
     * opening each project in turn and typing something.
     *
     * It starts the sessions the store already knows about, and nothing else: a project whose agent
     * has never run is started from its chat, because "which project" is a question this button
     * cannot answer.
     */
    fun startAllAgents() {
        val off = agentSessions.filter { !it.running }
        if (off.isEmpty()) return
        Trace.tap("start all agents", "${off.size}")
        for (session in off) startAgent(session.project, session.branch)
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
    fun resetAgent(project: String, branch: String = AgentSession.MAIN) {
        // **Reset stops the agent, because a cleared context and a live poll schedule is a
        // contradiction.** The operator: *"if I reset then I expect the agent to stop, because he now
        // has clear context — what will he even do!?"* There is no second act here: `stopAgent` writes
        // a `STOPPED` session, and `AgentStops.resetIfStopped` — the one place every stop goes
        // through — clears the context as part of it.
        //
        // **Refused while a turn is running**, and that refusal is what makes the ordering safe: a
        // context replaced under a reply being written would leave the record half in each file, and
        // a tool still running would land its result in the cleared context.
        // **No account first**, because that is the question every other door asks first and it is
        // the one with an answer the person can act on. `stopAgent` returns silently when there is
        // no workspace, which is right for a stop — there is no session to stop — and wrong here:
        // the person pressed a button and is owed the reason nothing happened.
        workspaceFor("reset") ?: return
        // **This session's turn, not the open chat's.** `chatWorking` answers for the conversation on
        // screen, and the person may be resetting another branch — so the question is whether *this*
        // one is busy, which the runner answers for the key it holds.
        if (busyHere(project, branch)) {
            message = "The agent is working. Wait for it to finish, then reset."
            return
        }
        // **No tap of its own.** `stopAgent` records the press, and one gesture with two ids is what
        // the trace exists to prevent. The person pressed Reset; the trace says the agent stopped,
        // which is what happened.
        stopAgent(project, branch)
        message = "The agent is off and its working context was cleared. The session log still has it."
    }

    /** Switches it off. The reason is recorded, because "why did it stop" is the question later. */
    fun stopAgent(project: String, branch: String = AgentSession.MAIN) {
        val session = sessionOf(project, branch) ?: return
        Trace.tap("stop agent", project)
        // **The request in flight is abandoned** (v1: "in-flight generations are cancelled in that
        // case, right order"). The loop is told at its next boundary; the calls the reply carried
        // are answered `1 interrupted` so the record stays a conversation, and the session is
        // already stopped so the ending cannot switch it back on.
        if (busyHere(project, branch)) {
            val key = AgentSession.keyOf(project, branch)
            interrupts[key] = Agent.BY_OPERATOR
            pendingChats.remove(key)
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
            // **The turn's own resolution, and the worktree it is for**: a branch with a model of its
            // own is asked that one, and the request row is filed under the project it came from.
            door = { asked, project -> doorFor(asked, project) },
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
            onPage = { key, live ->
                main.post {
                    // **The worktree's key**, compared with the one the open chat is showing. A page
                    // from `feature`'s agent must not appear in main's conversation.
                    if (key == openChatKey) {
                        chatPages = chatPages + (live.id to live)
                    }
                }
            },
            // **The folder, when a command has changed it.** The tree is not cached anywhere —
            // `ProjectTree` calls `listFiles()` on every read — so the only thing that was missing
            // was something to ask for the read. `refresh()` had twenty-nine call sites and every
            // one of them was a person's tap; a command that wrote ten files left the list exactly
            // as it was until somebody left the project screen and came back.
            //
            // **On the main thread, because it writes Compose state**, and posted rather than
            // called: this arrives from the tool's own thread, and `refresh()` reads the disk.
            onDiskChanged = { main.post { refresh() } },
            changed = { sessions ->
                main.post {
                    agentSessions = sessions
                    // A poll can change what the box shows, and the person may be reading it. The
                    // rows come from the record the same way every other refresh gets them: one
                    // path from the file to the screen rather than two.
                    chatProject?.let { project ->
                        val dir = ws.treeOf(project, chatBranch)
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
        // **Matched by the same key the file uses**, which is project *and* branch. Matching on the
        // project alone was right while a project had one tree: it made `feature`'s session overwrite
        // `main`'s in the list, so stopping one branch's agent switched the other's off on screen, and
        // `was` could be the wrong branch's session — which decided whether a context was cleared.
        // Two branches are two agents (§13d), so they are two entries and neither may touch the other.
        val was = agentSessions.firstOrNull { it.project == session.project && it.branch == session.branch }
        AgentStops.resetIfStopped(ws, session, was)
        AgentSessions(ws.accountDir).put(session)
        agentSessions = agentSessions.filterNot {
            it.project == session.project && it.branch == session.branch
        } + session
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
    private fun applyEnd(project: String, branch: String, end: Agent.End, now: Long) {
        // **The branch the turn ran in**, not the chat that happens to be open. It used to read
        // `chatBranch`, so a turn started on `feature` and ending after the person opened `main`'s
        // chat wrote its ending onto `main`'s session — one branch's result changing another branch's
        // agent. The caller has always known the branch; it was thrown away here.
        val current = sessionOf(project, branch) ?: return
        // **The window in force for this turn**, not the one in Settings now: a person changing it
        // mid-turn should not have the turn it started with reinterpreted.
        val next = end.onto(current, now, reconnectMs = settings.reconnectMs)
        if (next != current) putSession(next)
    }

    /**
     * The door for [wired]: the test's when one is set, and **the connection the turn resolved to**
     * otherwise — so a project with its own server is asked with the key stored beside that server's
     * address, and a server on a local network is asked with no key at all.
     */
    private fun doorFor(wired: Wired, project: String = "", branch: String = AgentSession.MAIN): Door = (chatDoor ?: { asked ->
        OpenRouterDoor(
            // **The key that belongs to the address**, which is the scope's when one answered — so a
            // project with its own server is asked with the key stored beside that server's address.
            key = { asked.key() },
            keyState = { asked.keyState() },
            keyProblem = { asked.keyProblem },
            keyless = asked.keyless,
            shape = asked.shape,
            model = asked.active.model,
            // **The host, because the address is the upstream** — see `providerOf`. The log's "who
            // answered" is a fact about where the bytes went, which is the one thing the tag could
            // never be trusted for once a person could point the app anywhere.
            provider = providerOf(asked.active.baseUrl),
            // **Recorded, never enforced.** Nothing is scoped by this - it is what lets the log
            // answer "which project costs me most", and a door built without one writes an empty
            // project rather than failing.
            project = project,
            // **The turn's own connection**, which is what replaced the provider tags and their
            // ladder: an address is the upstream, so a second machine is a second connection.
            baseUrl = asked.active.baseUrl,
            effort = asked.active.effort,
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
            // **The footer belongs to the conversation that is producing it.** These two lambdas
            // fire from the turn's own thread while the person may have opened another branch, and
            // an ungated write put `feature`'s stream under `main`'s header. The footer is cleared
            // when the turn ends, so a mismatch here is not a lost update — it is a line that was
            // never about this screen.
            val onScreen = { AgentSession.keyOf(project, branch) == openChatKey }
            door.listener = { stream, elapsed ->
                if (onScreen()) chatLive = stream.shape(elapsed)
            }
            // **Appended, not replaced.** A burst is one message made of several replies, so the
            // bubble shows the message being built rather than the last thing said.
            door.onReply = { words ->
                if (onScreen()) chatWriting = chatWriting + words
            }
        }
    })(wired)

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
    private fun applyChat(rows: List<AgentLog.Row>, follow: Boolean, from: String? = null) {
        // **Rows belong to the conversation that wrote them.** A turn runs on a worker, and by the
        // time it lands the person may have opened another branch's chat — so drawing its rows
        // unconditionally painted `feature`'s conversation into `main`'s screen, and then `main`'s
        // box was replaced by a conversation it does not own. The session key travels with the rows;
        // a mismatch means this screen has moved on, and the rows are already on disk where the
        // screen will read them when it comes back.
        if (from != null && from != openChatKey) return
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
     * It is a real account in every way that matters — its own folder, its own identity, its own
     * projects — and the one thing a host answers, whose key this is, is answered on this phone by
     * [DiskWho]. Its repositories are bare folders the tests make by hand, so a project is cloned,
     * committed to and pushed for real.
     */
    fun signInAsTestAccount(onDone: (Boolean) -> Unit = {}) {
        if (!canMakeTestAccount) {
            message = "Only a test build can do that"
            onDone(false)
            return
        }
        onWorker {
            val login = TEST_ACCOUNT
            // **Nothing is made here.** This used to seed a `welcome` repository so the picker had
            // something to list; the picker is gone, and a repository is made on the host's website.
            // What the test account needs is an identity, which [DiskWho] answers.
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

    /**
     * **The one save every door uses.**
     *
     * A door may differ in exactly one thing — how a credential is acquired. GitHub's door runs the
     * device flow and asks `GET /user` for the name and the commit identity, so it fills every field
     * itself; the other door asks a person for a name, a host, a key and optionally a git username.
     * Both then arrive here, and everything after this point is the same act.
     *
     * **Splitting it into two functions is what made the app GitHub-shaped**, because the second
     * function would then have to re-decide what signing in means: whether the branch screen is
     * forgotten, whether onboarding is finished. Those are not per-door questions.
     */
    fun addAccount(
        login: String,
        host: String,
        gitUser: String,
        token: String,
        commitName: String,
        commitEmail: String,
    ) {
        // **A sign-in makes an empty account, and nothing else goes with it.** There is no existing
        // workspace to adopt or rename — no account is shipped (§3.11) — and a project made in one
        // account reaches another only by `Transfer`, when the person says so (§3.9). The account
        // that arrives is new, which is the truth: it is an identity with a key.
        //
        // **The key is stored at account scope**, not project scope, because a clone happens before a
        // project exists: a project-scoped secret could never be present the moment it is first needed.
        // The store's narrowest-first lookup still lets a project override it later.
        credentials.setToken(login, token)
        accounts.save(
            Account(
                login = login,
                commitName = commitName.ifBlank { login },
                // **A commit address belongs to the host that issued it**, so nobody but GitHub gets
                // GitHub's `users.noreply` form: on another host that address would belong to a stranger.
                commitEmail = commitEmail.ifBlank { noreplyEmail(login, host = host) },
                host = host,
                gitUser = gitUser,
            )
        )
        credentials.activeLogin = login
        activeLogin = login
        // **Signing in is an account change too**, so it forgets the branch screen for the same reason
        // `switchTo` does. This line was missing and only a test found it.
        forgetBranches()
        backToMain()
        path = emptyList()
        refresh()
        // **The first half of the way out.** A key may already be on this phone from before the
        // sign-in, or the person may connect the model next; either order finishes onboarding.
        closeOnboardingIfConnected()
    }

    /**
     * **The other door: an account on any host, with a key a person made there.**
     *
     * Everything a door is allowed to differ in is here — the host is typed rather than implied, and so
     * is the git username — and nothing else is: the save is [addAccount], the same one GitHub's door
     * uses.
     *
     * **Nothing is validated against the host, and the dialog says so.** GitHub's door can ask
     * `GET /user` who a key belongs to; no other host has an equivalent in git, and there is no git
     * command that answers "is this key valid" without a repository to ask about. Pretending to check
     * would be worse than saying the key is tried the first time it is used, and the refusal then
     * arrives from the first clone as a sentence naming the account, the host, and which of
     * unauthorised / not-found / unreachable it was.
     */
    fun addHostAccount(
        name: String,
        host: String,
        gitUser: String,
        key: String,
        onDone: (Boolean) -> Unit = {},
    ) {
        val login = name.trim()
        Workspace.nameProblem(login)?.let {
            message = it
            onDone(false)
            return
        }
        Secrets.hostProblem(host)?.let {
            message = it
            onDone(false)
            return
        }
        if (key.isBlank()) {
            message = "A key is needed: this host will refuse to push without one"
            onDone(false)
            return
        }
        // **A login is a folder name**, and two accounts cannot share one. The same rule the sign-in
        // follows, said before anything is written rather than after: adopting an existing folder would
        // hand this account somebody else's projects.
        if (accounts.account(login) != null) {
            message = "This phone already has an account called \"$login\""
            onDone(false)
            return
        }
        Trace.note("host-account", "added · ${host.trim().lowercase()}")
        addAccount(
            login = login,
            host = host.trim().lowercase(),
            gitUser = gitUser.trim(),
            token = key,
            commitName = login,
            commitEmail = "",
        )
        onDone(true)
    }

    /**
     * GitHub's door onto [addAccount]: the host is the door's own, and there is no git username.
     *
     * Kept as a name because it is what the device flow and the token field both call, and because a
     * caller that says "sign in to GitHub" should not have to pass `github.com` to say it.
     */
    fun signIn(login: String, token: String, commitName: String, commitEmail: String) {
        addAccount(
            login = login,
            host = GITHUB_HOST,
            gitUser = "",
            token = token,
            commitName = commitName,
            commitEmail = commitEmail,
        )
    }

    /**
     * **The accounts on this phone, for the main menu** (§13c.2).
     *
     * Each carries how many projects it holds, because that is the one fact a person needs in order to
     * decide which to enter — and it is read from the folder rather than stored, so it cannot disagree
     * with what entering the account shows.
     */
    val accountsWithProjects: List<Pair<Account, Int>>
        get() = accounts.accounts().map { account ->
            account to Workspace(accounts.dirOf(account.login), accounts.sharedDir).projectNames().size
        }

    /**
     * **The account you are inside**, or null when you are looking at the list of them.
     *
     * This is the folder model (§13c.2): the main menu is the accounts, entering one shows its
     * projects, and leaving returns. `activeLogin` is *derived from navigation* — it is set by
     * [enterAccount] and cleared by [leaveAccount], never picked from a dialog up front.
     */
    var insideAccount: String?
        get() = activeLogin?.takeIf { accounts.account(it) != null }
        set(value) {
            if (value == null) leaveAccount() else enterAccount(value)
        }

    /** Enters [login]'s folder: its projects become the screen, and its agent becomes the one running. */
    fun enterAccount(login: String) {
        Trace.tap("enter account")
        switchTo(login)
    }

    /**
     * **Leaves the account you are in and shows the list again.**
     *
     * The account is *not* signed out and nothing is stopped: `activeLogin` keeps naming the account
     * whose sessions the runner is ticking, because the agent for a project you were just looking at
     * should not go quiet because you went back one screen. Only the screen changes.
     */
    fun leaveAccount() {
        Trace.tap("leave account")
        forgetBranches()
        backToMain()
        path = emptyList()
        editingPath = null
        historyPath = null
        // **`insideAccount` reads from this**, and null is the list — so the flag is separate from
        // `activeLogin` rather than making it null, which would stop the runner for every project.
        onAccountsScreen = true
    }

    /**
     * True when the main menu is the **list of accounts** rather than one account's projects.
     *
     * A flag rather than a nullable `activeLogin`, because the two questions are different: *which
     * account's agent is running* (one, always, once you have entered one) and *what the screen is
     * showing* (the list, or that account's projects).
     */
    var onAccountsScreen: Boolean by mutableStateOf(true)
        private set

    /**
     * **The project whose branches are on screen, or null** (§13d).
     *
     * The operator's decision: *"when you tap a project you are shown branch screen where you need to
     * select a branch (and main branch exists by default even for newly created projects)."*
     *
     * **A flag-shaped field rather than a fourth level in `path`**, because the branch screen is not a
     * folder — there is no `File` it corresponds to yet, and putting a name in `path` would make the
     * breadcrumb claim a directory that does not exist. It is the same shape as [onAccountsScreen]:
     * what the *screen* is showing, kept apart from what the *agent* is working in.
     *
     * **Null means the project's files are what you are looking at**, which is the state every screen
     * before §13d already assumed. So nothing changes until a branch is chosen.
     */
    var choosingBranchFor: String? by mutableStateOf(null)
        private set

    /** Shows [project]'s branches instead of its files. */
    fun openBranches(project: String) {
        Trace.tap("open branches", project)
        choosingBranchFor = project
        refreshBranches()
    }

    /** Back to the project list, from the branch screen. */
    fun closeBranches() = forgetBranches()

    /**
     * **Enters [branch] of the project whose branches are on screen** — makes its worktree if it has
     * none, and shows its files.
     *
     * This is the moment §13d stops being a list and becomes a place to work. Three things have to
     * happen in this order, and the order matters:
     *
     * 1. **The worktree is made** (or found), because the folder has to exist before anything resolves a
     *    path inside it. `main` needs nothing — its tree is the project folder — so choosing `main` is
     *    the path every screen took before branches existed.
     * 2. **`activeBranch` is set on the workspace**, which is what makes [Workspace.fileAt] answer the
     *    branch's copy of a file. Set *before* `path`, because `open` refreshes the listing.
     * 3. **The branch screen closes and the project's files open** — in that order, because
     *    `choosingBranchFor` is what the screen draws and `path` is what it lists.
     *
     * **A branch that cannot be checked out says so and stays on the branch screen.** Leaving the person
     * on a file screen that is really still main's would be the worst outcome here: every edit would go
     * to the wrong branch while the title said otherwise.
     */
    fun enterBranch(branch: String) {
        Trace.tap("enter branch", branch)
        // **Silence here was a bug, not a shortcut.** A project that is not being chosen and a
        // workspace that is not signed in are both states a person can reach, and returning quietly
        // left the screen exactly as it was — so the row looked unpressed. Every other refusal in this
        // class says a sentence; so does this one.
        val project = choosingBranchFor ?: run {
            message = "No project is chosen, so there is no branch to open."
            return
        }
        val ws = workspace ?: run {
            message = "No account is open, so $branch cannot be checked out."
            return
        }
        val dir = Worktrees(ws).make(project, branch)
        val tree = dir.getOrElse { failure ->
            message = failure.message ?: "That branch could not be opened."
            return
        }
        ws.activeBranch = branch
        forgetBranches()
        open(listOf(project))
        // **The strip's count belongs to the tree, so entering one re-counts it.** Without this the
        // screen opened a branch still showing main's number — "up to date" beside five unsent files,
        // which is the same wrong sentence the strip had before, one tap earlier.
        refreshChanges()
        // **And the host is asked whether it is still there.** The operator asked for the check at the
        // moment a project is entered: a remote that has been renamed, deleted or moved is otherwise
        // indistinguishable from a healthy one until a push fails much later with a sentence about
        // refs. Off the main thread, so entering a project never waits for a network.
        checkReach(project)
        Trace.note("branch-enter", "$project · $branch · $tree")
    }

    /**
     * **Which branch the files on screen belong to**, for the title that has to say so.
     *
     * Read off the workspace rather than kept here, because the workspace is what resolves the paths:
     * two copies of this fact would eventually disagree, and the one the screen draws would be the
     * wrong one.
     */
    val activeBranch: String get() = workspace?.activeBranch ?: AgentSession.MAIN

    /**
     * **Whether a release may be started from the screen that is open.**
     *
     * A release is dispatched on `main` and published from it, so an offer inside a branch would name
     * a version of code the person cannot see — the work has to reach `main` first, and that is the
     * person's act rather than a button's. A local project has no repository to dispatch to at all.
     *
     * **In the model rather than in the composable**, because it is a rule and not a layout: a screen
     * that decided it would be a second answer, and the next screen to draw the button would decide
     * differently.
     */
    val canPublish: Boolean
        get() {
            val project = currentProject ?: return false
            return activeBranch == AgentSession.MAIN && unreachableOf(project) == null
        }

    /**
     * **A release is a tag** — at `HEAD` of `main`, sent to the remote.
     *
     * This replaces dispatching a GitHub workflow. A tag is the release the protocol has: every host
     * understands one, and what a host builds from it is that host's business rather than this app's.
     * **`main` is required for the same reason it was before**: a version is a statement about what
     * the project is, and a tag on a branch nobody has merged is a version of something else.
     */
    fun tagRelease(versionName: String, note: String = "") {
        val ws = workspaceFor("tagged") ?: return
        val project = currentProject
        if (project == null) {
            message = "Open a project first: a version belongs to one"
            return
        }
        if (activeBranch != AgentSession.MAIN) {
            message = "A version is tagged on ${AgentSession.MAIN}. You are on $activeBranch, so " +
                "nothing was tagged — merge it into ${AgentSession.MAIN} and tag it there."
            return
        }
        val clean = versionName.trim()
        if (clean.isEmpty()) {
            message = "A version needs a name, like 1.4.0"
            return
        }
        busy = true
        onWorker {
            // **Counted here, not read from the cached map** — the same rule the release had, and for
            // the same reason: `changesOf` is a snapshot from the last refresh, so a save made since
            // would not be in it. A version tagged over unsent work names a commit the remote does not
            // have, so the tag would send that commit and leave `main` behind it — a version of
            // different code from the one the person is looking at, and nothing would say so.
            val unsent = try {
                projectGit(ws).changes(project).total
            } catch (t: Throwable) {
                // A count that cannot be taken is not a count of zero, but refusing on a failure to
                // read would block a release for a reason that is not about the release.
                0
            }
            if (unsent > 0) {
                main.post {
                    busy = false
                    message = "$project has $unsent change(s) not sent yet, so a version would name " +
                        "a commit the remote does not have. Sync first."
                }
                return@onWorker
            }
            val result = projectGit(ws).tag(project, clean, note.ifBlank { "Version $clean" })
            main.post {
                busy = false
                message = result.message
                // **Traced either way**, because a version that did not go is the one report a person
                // writes as "the release button does nothing".
                Trace.note("release", "$project · $clean · ${if (result.success) "tagged" else "refused"}")
                refresh()
            }
        }
    }

    /**
     * **Drops the branch screen, without a trace line**, because the caller is already leaving.
     *
     * Extracted because **three** paths change which account is in force — `signIn`, `switchTo` and
     * `leaveAccount` — and a branch belongs to a project in one account, so every one of them has to
     * forget it. Two of the three did, and the one that did not was `signIn`: the test for it failed,
     * which is the only reason this is a function rather than three copies of the same two lines.
     */
    private fun forgetBranches() {
        choosingBranchFor = null
        choosingBranches = emptyList()
        choosingUnmerged = emptySet()
    }

    /**
     * **Back to `main`, for the paths where the branch a person was in cannot survive.**
     *
     * Deliberately *not* part of [forgetBranches]: closing the branch screen while staying in the same
     * project must not move anybody's files back to `main`. It belongs with the account-changing paths
     * (`signIn`/`switchTo`/`leaveAccount`), because a branch is a worktree of a project in one account.
     */
    private fun backToMain() {
        workspace?.activeBranch = AgentSession.MAIN
    }

    /**
     * The branches of [choosingBranchFor], `main` first.
     *
     * **Kept in state rather than read on every frame.** A `get()` that opened the repository would do
     * file I/O during composition — once per recomposition, on the frame the person is looking at —
     * and would still be stale, because Compose has no way to know a branch was made. [refreshBranches]
     * is called when the screen opens and after every change, so the list is read exactly when
     * something could have changed it.
     */
    var choosingBranches: List<String> by mutableStateOf(emptyList())
        private set

    /**
     * **The branches whose work is not on `main`**, so a row can say so before it is entered.
     *
     * Read at the same moment as [choosingBranches] and from the same repository, because they answer
     * one question between them — *what is here, and which of it is not safe to lose* — and a second
     * read could describe a repository that has moved on.
     */
    var choosingUnmerged: Set<String> by mutableStateOf(emptySet())
        private set

    /** Re-reads the branch list. Answers the list, so a caller can report what happened. */
    fun refreshBranches() {
        val project = choosingBranchFor
        if (project == null) {
            choosingBranches = emptyList()
            choosingUnmerged = emptySet()
            return
        }
        val repo = git?.open(project)
        if (repo == null) {
            choosingBranches = emptyList()
            choosingUnmerged = emptySet()
            return
        }
        try {
            choosingBranches = GitService.localBranches(repo)
            choosingUnmerged = GitService.unmergedBranches(repo)
        } finally {
            repo.close()
        }
    }

    /**
     * **Makes a branch from the branch the person chose, and gives it that branch's memory.**
     *
     * The operator's request (2026-11-10): *"when I make a branch I can choose from what original branch
     * I am forking … and the new branch agent keeps the memories of the initial branch"* — narrowed to
     * the two controls that are actually separate things: *"only parent branch selector stays (default
     * parent is main, no parent is not an option)"*.
     *
     * **[from] is the parent and is required.** There is no "start from nothing": a branch is a fork, and
     * a fork of nothing is a second project.
     *
     * **Two acts, in this order, because the second needs the first.** The ref is made from the parent's
     * commit, the tree is checked out from it, and then the parent's conversation is copied into that
     * tree — see [Worktrees.inherit] for what is copied and why it is not everything under `agent/`.
     * Doing the copy first would write into a folder that does not exist yet, and doing it after the
     * person's first edit would overwrite a conversation that had already moved on.
     *
     * **The refusal is said, not swallowed.** A name git would reject, an existing name, a parent that is
     * not there — all three are refusals the person has to see, or the button looks broken.
     */
    fun newBranch(name: String, from: String = AgentSession.MAIN) {
        // **The tap is recorded by the window that pressed it**, because the window knows which parent
        // was chosen and a second record here would give one press two ids.
        val project = choosingBranchFor ?: return
        val repo = git?.open(project) ?: run {
            message = "There is no repository for $project, so there is nothing to branch."
            return
        }
        val outcome = try {
            GitService.createBranch(repo, name, from)
        } finally {
            repo.close()
        }
        message = outcome.fold(
            { made ->
                val ws = workspace
                val tree = ws?.let { Worktrees(it).make(project, made) }
                val failed = tree?.exceptionOrNull()?.message
                if (failed != null) {
                    return@fold "Made $made from $from, but its folder could not be checked out: $failed"
                }
                // **The memory, after the tree exists.** The count is what the person is told, because
                // "inherited" with no number is a claim and "2 files" is an answer.
                val kept = ws?.let { Worktrees(it).inherit(project, made, from) }
                when {
                    kept == null -> "Made $made from $from."
                    kept.isFailure -> "Made $made from $from, but its agent's record was not copied: " +
                        (kept.exceptionOrNull()?.message ?: "the copy failed")
                    kept.getOrThrow() == 0 -> "Made $made from $from. $from had no conversation to inherit."
                    else -> "Made $made from $from, with $from's conversation."
                }
            },
            { it.message ?: "The branch could not be made." },
        )
        refreshBranches()
        refresh()
    }

    /**
     * Deletes a branch, **and says why when it will not.**
     *
     * The two refusals are `main` and a branch with work that is not on `main`; both are the rule that
     * removed `delete_repo`, and both are sentences rather than a button that does nothing.
     */
    fun dropBranch(name: String) {
        Trace.tap("confirm remove branch", name)
        // **The widest precondition first, so the sentence names the one that actually stopped it.** With
        // no account open there is no project either, and "nothing was removed" is the true answer rather
        // than "no branch was chosen" — which would send a person looking for a branch to choose.
        workspaceFor("removed") ?: return
        val project = choosingBranchFor ?: run {
            message = "No project's branches are open, so nothing was removed."
            return
        }
        // **Not while its agent is working.** The tree is deleted with the branch, so an agent part way
        // through a turn would be writing into a folder somebody else just removed — and the rule that
        // a running agent means "not now" is the one housekeeping already follows.
        if (agentBusy(project, name)) {
            message = "$project is working on $name, so its agent has to stop first."
            return
        }
        val repo = git?.open(project) ?: run {
            message = "There is no repository for $project, so there is no branch to remove."
            return
        }
        val outcome = try {
            GitService.deleteBranch(repo, name)
        } finally {
            repo.close()
        }
        // **The tree goes with the branch, and only when the branch really went.** A fork point is a
        // folder git still lists and the app still offers; left behind it would be a directory whose
        // branch does not exist, holding the system link and whatever was checked out.
        val removed = outcome.getOrNull()
        if (removed != null) {
            val ws = workspaceFor("removed") ?: return
            Worktrees(ws).remove(project, removed)
            // **The session goes with it.** A session whose tree is gone is one the tick would remove
            // on its next pass anyway — but between now and then the screen still offers a
            // conversation, and a `put` in that window would recreate the folder through
            // `AgentLog.open`, resurrecting a worktree whose branch no longer exists.
            AgentSessions(ws.accountDir).remove(project, removed)
            agentSessions = AgentSessions(ws.accountDir).read()
            // **The branch's secrets go with it** — counted in the sentence below, because "Removed
            // feature" while a token stays on the phone is a claim about this phone that is not true.
            // **If the person is standing in it, back to main.** Deleting the tree under an open
            // screen leaves every path resolving into a folder that is gone.
            if (activeBranch == removed) backToMain()
        }
        message = outcome.fold(
            { branch ->
                if (credentials.forgetBranchSecrets(project, branch) > 0) {
                    "Removed $branch, and the secrets kept for it."
                } else {
                    "Removed $branch."
                }
            },
            { it.message ?: "The branch could not be removed." },
        )
        refreshBranches()
    }

    /** Makes a stored account active. */
    fun switchTo(login: String) {
        credentials.activeLogin = login
        activeLogin = login
        // **The branch belongs to a project in the account you were in**, so switching accounts
        // must not leave the branch screen naming a project that is no longer there.
        forgetBranches()
        backToMain()
        // One log, not one per account: a switch is a thing that happened in this
        // session, and splitting the stream would lose the line before it.
        Trace.note("switched-account", login)
        path = emptyList()
        editingPath = null
        historyPath = null
        // **Entering an account leaves the list**, which is the whole of the navigation model.
        onAccountsScreen = false
        refresh()
    }

    /**
     * Removes an account. [deleteCopies] also deletes its tree from this phone;
     * without it the folders stay and signing in again returns to them (§5).
     */
    fun removeAccount(login: String, deleteCopies: Boolean) {
        // **The answer decides whether there is a second attempt.** A save already on its way to the
        // worker can write into the tree while this walks it, and then `delete` fails on a folder
        // that has just been re-made — the account stays on the phone and the person is told nothing.
        // The walk is re-asked below, **on the worker**, which is the only place it can be ordered
        // against the writes; here the fast path runs first so the screen changes at once.
        val gone = if (deleteCopies) accounts.remove(login) else accounts.forget(login)
        credentials.forget(login)
        // Another stored account takes over, so the screen is never left pointing
        // at an account that is no longer here.
        // The disk is what says which accounts this phone has; the keys only say
        // which of them can still open a host.
        //
        // **Null is a real answer, and it is the one this step exists for.** There is no account an
        // app can ship (§3.11), so a phone whose last account was just removed has none — and the
        // screen that answers that is the accounts list with its two doors, not a workspace with
        // nowhere to be.
        val next = accounts.accounts().map { it.login }
            .firstOrNull { it != login }
            ?: credentials.logins().firstOrNull { accounts.exists(it) && it != login }
        credentials.activeLogin = next
        activeLogin = next
        path = emptyList()
        editingPath = null
        historyPath = null
        refresh()
        if (!gone) finishRemoval(login, deleteCopies)
    }

    /**
     * The removal, asked a second time **after every write already in flight has landed**.
     *
     * Two things make this the right shape rather than a retry loop on the main thread:
     *
     * - **The worker is single-threaded and in order**, so a walk queued here runs after the save
     *   that was queued before it — which is exactly the interleaving that lost the folder. A save
     *   queued *after* this runs with the folder already gone, and its own guard refuses it.
     * - **Nothing blocks.** The first walk still happens where it always did, so the screen changes
     *   at the moment the person presses the button; only the correction waits.
     *
     * A second failure is said rather than swallowed: the account is still on the phone, and a
     * person who is told it went would go looking for the wrong thing.
     */
    private fun finishRemoval(login: String, deleteCopies: Boolean) {
        onWorker {
            val now = if (deleteCopies) accounts.remove(login) else accounts.forget(login)
            if (now) return@onWorker
            main.post {
                message = removalProblemSaid(login)
                // The folder is still there, so the account is still this phone's — a list that
                // carried on without it would be the app disagreeing with its own disk.
                refresh()
            }
        }
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
        // **The tree that holds the version**, which is the file's own (§13d): a file opened from a
        // branch is that branch's history, and main's would answer with versions of a different text.
        return g.onBranch(ws.branchOf(ws.fileAt(target)))
            .versions(project, target.drop(1).joinToString("/"))
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
        // **The tree the folder is in** (§13d). A deleted file is a fact about one branch's history:
        // main's commits do not hold what `feature` deleted, and offering main's would offer files
        // this branch never had.
        val repo = git?.onBranch(ws.branchOf(dir))
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
                // A link is not a path in this repository's history (§13f), so descending would mark
                // the system folder's files as "still there" inside a project that never had them.
                if (child.isDirectory && !Workspace.isLink(child)) collect(child, name)
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
        val ws = workspaceFor("restored") ?: return false
        val path = ws.relative(dir) ?: run {
            message = "${dir.name} is not part of this account any more"
            return false
        }
        val project = ws.projectOf(dir) ?: run {
            message = "${dir.name} is not a project, so there is no history to bring anything back from"
            return false
        }
        // **Every file is in the history, so there is no kind to refuse.** This used to turn a picture
        // away: a binary had one version and its bytes lived outside the tree, so the version named
        // might already be gone. The bytes are in the commit now, so a photograph someone deleted by
        // accident is as recoverable as a note.
        //
        // The folder's own tree, for the same reason [deletedIn] read it there.
        val branch = ws.branchOf(dir)
        val repo = git?.onBranch(branch)
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
        // were written into a folder of one repository, so its commit is the one that matters —
        // and [branch] is that repository's view, because `ws.fileAt` above resolved into it.
        commitProject(project, what, branch)
        historyGeneration++
        return true
    }

    /** Puts an old version back into the file. */
    fun restoreVersion(revisionId: String) {
        Trace.tap("restore version", revisionId.take(7))
        val ws = workspaceFor("restored") ?: return
        val target = historyPath ?: return
        // A restore writes, so a lock refuses it. The history stays open and says so:
        // the version list is exactly where a person finds out that they froze this file.
        if (ws.lockProblem(ws.fileAt(target)) != null) {
            Trace.failure("restore version", "locked")
            message = "${target.last()} is locked, so nothing was put back"
            return
        }
        val project = ws.projectAt(target)
        // **The file's own tree**, for the same reason [versions] reads it there: putting a version back
        // into main's file while the person is looking at a branch's would replace text they cannot see.
        val here = git?.onBranch(ws.branchOf(ws.fileAt(target)))
        val ok = project != null &&
            here?.restore(project, target.drop(1).joinToString("/"), revisionId)?.isSuccess == true

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
         * Which GitHub a login talks to — **the rule, in one place, for the app and the agent alike.**
         *
         * It was a method on [AppViewModel] only, and the agent's CI tools need the same answer
         * without a view model: a debug build's test account is a folder pretending to be GitHub, and
         * a release build must refuse to act as one even when a stored account claims to be. Writing
         * that twice would be writing it two slightly different ways.
         *
         * **No account, no host.** A login this phone does not have is not an identity to send with,
         * and answering with an `HttpWho` would give every caller something that looks usable and
         * send unauthenticated requests to the internet. `null` is the honest answer, and it is the
         * one the screens already handle.
         */
        fun whoFor(login: String?, accounts: AccountStore, testAccountsAllowed: Boolean): Who? {
            val stored = accounts.account(login) ?: return null
            if (stored.dev && testAccountsAllowed) {
                // **A host that says who a key is and nothing else.** Its repositories are bare
                // folders the fixtures make by hand, which is what a host's website does.
                return DiskWho(stored.login)
            }
            // **An account on another host has no such door.** `HttpWho()` pointed at `api.github.com`
            // on behalf of a `gitlab.com` account would send that account's key to a host it was never
            // made for — refused by design — and answer with a 401 that explains nothing. `null` is the
            // honest answer, and it is what makes the other-host door ask the person for a name.
            return if (Secrets.isHost("https://${stored.host}/", GITHUB_HOST)) HttpWho() else null
        }
        private const val SEARCH_LIMIT = 4000

        /** How many commits back the "what used to be here" list looks. */
        private const val DELETED_LOOKBACK = 50
        private const val SEARCH_RESULTS = 200
    }
}
