package com.example.notes

import android.app.Application
import android.os.Looper
import org.eclipse.jgit.api.Git
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * The keys of the accounts, in memory: a test has no keystore and needs none.
 *
 * Open, so a test can be the store in a state the real one reaches and this one does not — a key
 * that cannot be read back, or a locked phone.
 */
open class MemoryCredentials : Credentials {
    private val tokens = LinkedHashMap<String, String>()
    override var activeLogin: String? = null

    override fun logins(): List<String> = tokens.keys.toList()

    override fun token(login: String): String? = tokens[login]

    override fun setToken(login: String, token: String) {
        tokens[login] = token
    }

    override fun forget(login: String) {
        tokens.remove(login)
        if (activeLogin == login) activeLogin = null
    }

    override fun hasToken(login: String): Boolean = tokens.containsKey(login)

    /** The OpenRouter keys, kept the same way: in memory, because a test has no keystore. */
    private val modelKeys = LinkedHashMap<String, String>()

    override fun modelKey(login: String): String? = modelKeys[login]

    override fun setModelKey(login: String, key: String) {
        modelKeys[login] = key
    }
}

/**
 * One walk through the app, from the outside.
 *
 * A scenario makes a phone with nothing on it, signs in, and then does what a
 * person would do: opens a folder, writes a file, makes a project, syncs it. Every
 * step goes through the real [AppViewModel] and the real classes under it — the
 * only thing that is not real is GitHub itself, which is [DiskGitHub]: a folder of
 * bare repositories where clones, commits and force-pushes are genuine.
 *
 * This is what makes the features that need an account testable at all. A test run
 * never holds a key.
 */
class Scenario(
    val app: Application = RuntimeEnvironment.getApplication(),
    /** True when the account signed in should be the test account. */
    signIn: Boolean = true,
    /**
     * Where the keys live, when a test needs a store that behaves badly on purpose.
     *
     * Null is the ordinary in-memory store. A test passes its own to make a save that cannot be
     * read back, or a phone whose screen is locked — the two states the real store can be in and
     * the in-memory one never is.
     */
    store: MemoryCredentials? = null,
    /**
     * The GitHub that signing in asks who a key belongs to.
     *
     * Null leaves the app's own choice alone, which is what every scenario but the
     * sign-in ones wants. A test that needs GitHub to *refuse* a key passes one
     * that does, because the real code path is the one that has to say no.
     */
    signInGitHub: (() -> GitHub)? = null,
) {

    /** Everything this scenario makes lives here and goes away with it. */
    val root = File(app.cacheDir, "scenario-${System.nanoTime()}").apply {
        deleteRecursively()
        mkdirs()
    }

    val credentials = store ?: MemoryCredentials()

    val settings = SettingsStore(app, "settings-${System.nanoTime()}")

    val viewModel = AppViewModel(
        app,
        rootDir = root,
        credentials = credentials,
        settings = settings,
        // A person types slowly; a test does not. The history is committed at
        // once so that what is being tested is the behaviour, not the pause.
        saveDebounceMs = 0,
        signInGitHub = signInGitHub ?: { HttpGitHub() },
    )

    val login: String get() = AppViewModel.TEST_ACCOUNT

    init {
        if (signIn) signedIn()
    }

    /** Signs the test account in, and waits until the app knows it. */
    fun signedIn(): Scenario {
        viewModel.signInAsTestAccount()
        await("the test account to be signed in") { viewModel.account != null }
        return this
    }

    /**
     * The same phone, opened again.
     *
     * A second view model over the same account, settings and credentials is what opening the
     * app is: nothing is carried in memory, so anything that survives came off the disk.
     */
    fun reopen(): AppViewModel = AppViewModel(
        app,
        rootDir = root,
        credentials = credentials,
        settings = settings,
        saveDebounceMs = 0,
        signInGitHub = { DiskGitHub(viewModel.accounts.devGitHubDir(login), login) },
    )

    /** The GitHub the account is talking to, for asking what really happened. */
    val github: DiskGitHub
        get() = DiskGitHub(viewModel.accounts.devGitHubDir(login), login)

    /** The bare repository of a project — the thing on "GitHub". */
    fun remote(project: String): File = github.repoDir(project)

    /** Opens a folder by its path from the account root. */
    fun open(vararg names: String): Scenario {
        viewModel.open(names.toList())
        await("${names.toList()} to be open") { viewModel.path == names.toList() }
        return this
    }

    /**
     * Opens a folder **inside the project that is open**, however deep.
     *
     * [open] takes a path from the account root, which every history test would otherwise have to
     * spell out in full — and a path spelled by hand is how a test ends up making its files in a
     * folder that is not in the project at all, waiting 20 seconds for a commit that could not
     * happen, and calling the app broken.
     */
    fun openIn(vararg names: String): Scenario {
        val project = viewModel.currentProject ?: error("not inside a project")
        return open(project, *names)
    }

    /** The names in the folder that is open, in the order the screen shows them. */
    fun names(): List<String> = viewModel.entries.map { it.name }

    /** Finds an entry in the folder that is open, by name. */
    fun entry(name: String): Entry {
        val found = viewModel.entries.firstOrNull { it.name == name }
        assertTrue("there is no \"$name\" in ${viewModel.path}", found != null)
        return found!!
    }

    /**
     * Waits until [file]'s project has recorded one more thing.
     *
     * **One history answers**, so this is the same question for every file: the project the file is
     * in owns its versions, and a file outside any project is the app's own and has no history.
     *
     * commits are queued, and a queue coalesces: six folders made in the same millisecond are one
     * version, because the first queued commit sees all six. That is the app doing what it should —
     * a burst of changes is one version — so a test that wants three versions has to let each one
     * land first, exactly as a person's pauses would.
     */
    private fun awaitRecordedFor(file: File, before: String?, what: String) {
        val project = projectOf(file) ?: return
        val history = repo(project)
        // The path inside the project's repository, which is what git stores — not the account's.
        val relative = file.relativeTo(workspace!!.projectDir(project)).invariantSeparatorsPath
        // **The file's own version, not merely a newer head.** Commits coalesce, so a head that moved
        // may belong to the previous file's save while this one is still queued — and a caller that
        // took the new head as "this change is recorded" went on to read a history that was still
        // growing. Asking the newest version for this file's own text cannot be answered by another
        // file's commit.
        await("the project's history to record $what", 20_000) {
            val newest = history.head()
            newest != null && newest != before && history.fileAt(relative, newest) == file.readText()
        }
        // **And for the length to be applied, when one is set.** The length is applied by shortening
        // the history *after* the commit is written, so the newest version exists for a moment in a
        // history that is one too long. A test that read at that moment and then asserted "three are
        // kept" was racing the prune and read four — the same value its `await` had just rejected.
        //
        // Waiting here rather than in each test, because every change has this question. The pruned
        // tip is a **rewritten commit**, so "the head is no longer the one that was written" is what
        // says the shortening happened; a size alone could still be the previous history's.
        val keep = settings.historyLength
        if (keep > 0) {
            val written = projectHead(project)
            if (versions(project) > keep) {
                await("the history to be shortened to $keep", 20_000) {
                    versions(project) <= keep && projectHead(project) != written
                }
            }
        }
    }

    /** How many versions a project's history holds. */
    fun versions(project: String): Int = repo(project).log(null).size

    /** The project whose folder holds [file], or null when it is not in one. */
    private fun projectOf(file: File): String? = workspace?.projectOf(file)

    /** The newest commit of the repository that owns [file], or null when there is none. */
    private fun headOf(file: File): String? {
        val project = projectOf(file) ?: return null
        return projectHead(project)
    }

    /** The newest commit of a project's repository, or null when there is none. */
    fun projectHead(project: String): String? =
        ProjectGit(workspace!!, { null }, { AppViewModel.DEFAULT_AUTHOR })
            .log(project, limit = 1)
            .firstOrNull()
            ?.id

    /** The newest commit of the open project's repository, or null when there is none. */
    fun headId(): String? =
        (viewModel.currentProject ?: viewModel.projectNames.firstOrNull())?.let { projectHead(it) }

    /** A project's repository, read the way a test reads a history. */
    fun repo(project: String): RepoHistory = RepoHistory(workspace!!.projectDir(project))

    /** How many versions the open project's history holds. */
    fun historySize(): Int = try {
        val project = viewModel.currentProject ?: viewModel.projectNames.firstOrNull()
        project?.let { repo(it).log(null).size } ?: 0
    } catch (e: Exception) {
        0
    }

    /** Makes a file in the folder that is open and waits for it to be there. */
    fun file(name: String, text: String = ""): File {
        // The name the app will actually use: it puts every name into one shape,
        // so a harness that looked for `shopping-list.txt` would wait forever for a
        // file the app had correctly called `shopping_list.txt`.
        val made = Workspace.cleanName(Workspace.withDefaultExtension(name), "untitled")
        val file = File(viewModel.currentDir!!, made)
        val before = headOf(file)
        viewModel.createFile(name)
        await("\"$made\" to be made") { viewModel.entries.any { it.name == made } }
        awaitRecordedFor(file, before, "\"$made\" being made")
        if (text.isNotEmpty()) {
            val textBefore = headOf(file)
            viewModel.openEditor(file)
            viewModel.saveEditor(text)
            await("\"$made\" to hold $text") { viewModel.textOf(file) == text }
            awaitRecordedFor(file, textBefore, "\"$made\" being written")
            viewModel.closeEditor()
        }
        return file
    }

    /** Makes a folder in the folder that is open. */
    fun folder(name: String): File {
        require(!name.contains("/")) { "a folder is made in the folder that is open, not at a path" }
        val made = Workspace.cleanName(name, "folder")
        val file = File(viewModel.currentDir!!, made)
        viewModel.createFolder(name)
        await("\"$made\" to be made") { viewModel.entries.any { it.name == made } }
        // **An empty folder is not a version, and cannot be.** Git stores content, so a folder
        // with nothing in it is not a change to commit — the app is not failing to record one, and
        // a helper that waited for a commit would wait for the test's whole timeout and then
        // report the app as broken. The folder's first version arrives with the first thing put
        // in it, which is the moment there is something to have a version *of*.
        return file
    }

    /**
     * Makes a project the way the screen does: a repository on the pretend
     * GitHub, then the folder and the clone. Waits until the folder is open.
     */
    fun project(name: String, licence: String = "mit"): Scenario {
        viewModel.createProject(name, licence)
        await("project \"$name\" to exist") {
            viewModel.projectNames.any { it.equals(name, ignoreCase = true) }
        }
        await("the making of $name to finish", 60_000) { !viewModel.busy }
        return this
    }

    /**
     * Writes a file into **the project's working tree**, which is the project's folder.
     *
     * The two used to be different places — a project folder holding a `GitHub/` worktree — so this
     * helper exists to say which one a test means. They are one folder now, and the name stays
     * because "the working tree" is still the thing that goes to GitHub: a file written here is a
     * change the project has to explain.
     */
    fun worktreeFile(name: String, text: String): File {
        val ws = viewModel.workspace!!
        val project = ws.projectAt(viewModel.path) ?: error("not inside a project")
        val file = File(ws.repoOf(project), name)
        ws.write(file, text)
        return file
    }

    /** Syncs a project and waits until nothing is waiting to be committed. */
    fun sync(project: String): Scenario {
        viewModel.sync(project)
        await("the sync of $project to finish", 60_000) { !viewModel.busy }
        await("$project to have nothing left to sync", 60_000) { viewModel.changesOf(project) == 0 }
        return this
    }

    /** Opens a file in the editor the way tapping its row does. */
    fun openEditor(file: File) {
        viewModel.openEditor(file)
        await("the editor to open ${file.name}") { viewModel.editingPath != null }
    }

    /** Saves the editor's text and waits for the file to hold it. */
    fun saveEditor(text: String) {
        val file = viewModel.editingFile ?: error("the editor is not open")
        viewModel.saveEditor(text)
        await("${file.name} to hold the new text") { viewModel.textOf(file) == text }
    }

    /**
     * The text of [file] in the newest version of its project's history.
     *
     * There is one history per project and no account-level one, so a file in a project is read
     * from that project's repository and a file outside every project has no versions at all.
     */
    fun newestVersionText(file: File): String? {
        val ws = workspace ?: return null
        val path = ws.relative(file) ?: return null
        val project = ws.projectOf(file) ?: return null
        return try {
            Git.open(ws.repoOf(project)).use { git ->
                git.repository.resolve("HEAD")?.let { head ->
                    GitService.fileAt(
                        git.repository,
                        path.drop(1).joinToString("/"),
                        head.name,
                    )
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Saves, and waits for the version to exist.
     *
     * The app waits a moment before committing a save, so that a burst of typing
     * becomes one version rather than one per keystroke. A test that saved three
     * times as fast as it could would get fewer versions than saves and would be
     * measuring the debounce rather than the history.
     */
    fun saveAsVersion(text: String) {
        val file = viewModel.editingFile ?: error("the editor is not open")
        viewModel.saveEditor(text)
        await("a version of ${file.name} holding \"$text\"", 30_000) {
            newestVersionText(file) == text && file.readText() == text
        }
    }

    /** Closes the editor, which is what the back arrow does. */
    fun closeEditor() {
        viewModel.closeEditor()
    }

    /**
     * The versions of a file, newest first, as the history screen would list them.
     *
     * The history is left **open**, because restoring is an act on the history
     * that is being shown: closing it first would make the restore a no-op, and a
     * test that did that would prove nothing.
     */
    fun versionsOf(file: File): List<Pair<GitService.CommitInfo, String?>> {
        viewModel.openHistory(file)
        return viewModel.versions()
    }

    /** The versions of a folder, newest first, each with what was inside it. */
    fun versionsOfFolder(name: String): List<Pair<GitService.CommitInfo, String?>> {
        val project = viewModel.currentProject ?: error("not inside a project")
        val folder = File(workspace!!.projectDir(project), name)
        viewModel.openHistory(folder)
        return viewModel.versions()
    }

    /** True once at least [count] history reads have landed, which is the screen's own question. */
    fun loaded(count: Int): Boolean = viewModel.historyLoaded >= count

    /** Puts a version back, from the history that is open. */
    fun restore(revisionId: String) {
        viewModel.restoreVersion(revisionId)
    }

    /** Closes the history screen. */
    fun closeHistory() {
        viewModel.closeHistory()
    }

    /**
     * Every path the open project's newest version holds.
     *
     * The account's own history is gone — the projects are the folders, so everything a test wants
     * to ask about is in one of them. Paths are project-relative, which is what git records.
     */
    fun trackedFiles(project: String? = null): List<String> =
        (project ?: viewModel.currentProject ?: viewModel.projectNames.firstOrNull())
            ?.let { repo(it).tracked() }
            .orEmpty()

    /** The account's workspace, which every scenario has once it is signed in. */
    val workspace: Workspace? get() = viewModel.workspace

    /** Every path the remote holds on its main branch. */
    fun remoteFiles(project: String): List<String> = try {
        Git.open(remote(project)).use { git ->
            git.repository.resolve("refs/heads/main")?.let { commit ->
                GitService.filesUnder(git.repository, null, commit.name).map { it.first }
            }.orEmpty()
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** The contents of one file on the remote. */
    fun remoteText(project: String, path: String): String? = try {
        Git.open(remote(project)).use { git ->
            git.repository.resolve("refs/heads/main")?.let { commit ->
                GitService.filesUnder(git.repository, null, commit.name)
                    .firstOrNull { it.first == path }?.second
            }
        }
    } catch (e: Exception) {
        null
    }

    /** How many commits a project's own repository on this phone has. */
    fun commitCount(project: String): Int = try {
        Git.open(workspace!!.repoOf(project)).use { it.log().call().count() }
    } catch (e: Exception) {
        0
    }

    /** The paths in a project's newest version, or null when it has none. */
    fun newestPaths(project: String): List<String>? = try {
        Git.open(workspace!!.repoOf(project)).use { git ->
            git.repository.resolve("HEAD")?.let { head ->
                GitService.pathsUnder(git.repository, null, head.name)
            }
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Waits until a project's newest version holds what [check] asks of it.
     *
     * A change is committed on the worker, so the repository has not answered by
     * the time the call that made the change returns. What is asserted is the
     * repository's own answer — the paths in its newest commit — not the app's
     * opinion of what it just did.
     */
    fun awaitCommits(project: String, atLeast: Int, check: (List<String>) -> Boolean): Boolean {
        var answer = false
        await("$project's history to hold the change", 30_000) {
            val paths = newestPaths(project)
            answer = paths != null && commitCount(project) >= atLeast && check(paths)
            answer
        }
        return answer
    }

    /** How many commits the remote has. */
    fun remoteCommits(project: String): Int = try {
        Git.open(remote(project)).use { git ->
            git.log().call().count()
        }
    } catch (e: Exception) {
        0
    }

    /**
     * Waits for something the app does on its worker thread.
     *
     * The app answers on the main thread, so the main thread is given a turn
     * between looks: a check that never lets the answer through would wait
     * forever for something that had already happened.
     */
    fun await(what: String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(4)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(
            "waited $timeoutMs ms for $what\n${diagnose()}",
            condition(),
        )
    }

    /**
     * What the app was saying while it waited.
     *
     * A red run is expensive to produce, so it must arrive carrying the answer:
     * the app's own message, whether its history is recording at all, and what the
     * projects think is waiting. Without this a failed assumption costs another
     * whole run to explain.
     */
    fun diagnose(): String = buildString {
        appendLine("    the app said: ${viewModel.message}")
        val ws = workspace
        if (ws == null) {
            appendLine("    no account is signed in")
            return@buildString
        }
        val dir = ws.accountDir
        appendLine("    account folder: ${if (dir.isDirectory) "there" else "MISSING"} $dir")
        appendLine("    projects: ${ws.projectNames()}")
        viewModel.projectNames.forEach { name ->
            val repo = ws.repoOf(name)
            appendLine("    $name: repo ${if (repo.isDirectory) "there" else "MISSING"}, " +
                "changes ${viewModel.changesOf(name)}")
            try {
                Git.open(repo).use { git ->
                    val log = GitService.log(git.repository, null, 5)
                    appendLine("      commits: ${log.joinToString { it.message.take(40) }}")
                    val status = git.status().call()
                    val waiting = status.added + status.changed + status.modified +
                        status.removed + status.missing + status.untracked
                    appendLine("      waiting: $waiting")
                    appendLine("      branch: ${git.repository.branch}, " +
                        "remote: ${git.repository.config.getString("remote", "origin", "url")}")
                }
            } catch (e: Exception) {
                appendLine("      the repository could not be read: ${e.message}")
            }
        }
        // The commits of each project are printed above, from its own repository, so there is no
        // second history left to print: what a person needs to see is already there.
    }

    @After
    fun close() {
        root.deleteRecursively()
    }
}

/**
 * A feature file, run the way the app runs.
 *
 * Everything a test needs is in [scenario]; the names of the tests themselves are
 * written as the promise being checked — "a file keeps its extension", "a deleted
 * folder comes back" — so that a failing test says what broke in the app's own
 * words rather than in the words of the code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
abstract class FeatureTest {

    /** The account to start with: nobody, unless a test says otherwise. */
    protected open val signIn: Boolean get() = true

    protected lateinit var scene: Scenario

    @Before
    fun makeAPhone() {
        scene = Scenario(RuntimeEnvironment.getApplication(), signIn)
    }

    @After
    fun putThePhoneAway() {
        scene.close()
    }
}
