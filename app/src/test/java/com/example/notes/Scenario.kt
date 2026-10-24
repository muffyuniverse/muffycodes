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

/** The keys of the accounts, in memory: a test has no keystore and needs none. */
class MemoryCredentials : Credentials {
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

    val credentials = MemoryCredentials()

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

    /** The names in the folder that is open, in the order the screen shows them. */
    fun names(): List<String> = viewModel.entries.map { it.name }

    /** Finds an entry in the folder that is open, by name. */
    fun entry(name: String): Entry {
        val found = viewModel.entries.firstOrNull { it.name == name }
        assertTrue("there is no \"$name\" in ${viewModel.path}", found != null)
        return found!!
    }

    /**
     * Waits until the account's history has recorded one more thing.
     *
     * commits are queued, and a queue coalesces: six folders made in the same
     * millisecond are one version, because the first queued commit sees all six.
     * That is the app doing what it should — a burst of changes is one version —
     * so a test that wants three versions has to let each one land first, exactly
     * as a person's pauses would.
     */
    private fun awaitRecorded(before: String?, what: String) {
        await("the history to record $what", 20_000) { headId() != before }
    }

    /** The newest commit of the account's own history, or null when there is none. */
    fun headId(): String? = accountHistory().head()

    /** The account's own history, as the app sees it. */
    fun accountHistory(): AppHistory =
        AppHistory(workspace!!.accountDir) { AppViewModel.DEFAULT_AUTHOR }

    /** How many versions the account's own history holds. */
    fun historySize(): Int = try {
        accountHistory().log(null).size
    } catch (e: Exception) {
        0
    }

    /** Makes a file in the folder that is open and waits for it to be there. */
    fun file(name: String, text: String = ""): File {
        val made = Workspace.withDefaultExtension(name)
        val before = headId()
        viewModel.createFile(name)
        await("\"$made\" to be made") { viewModel.entries.any { it.name == made } }
        val file = File(viewModel.currentDir!!, made)
        awaitRecorded(before, "\"$made\" being made")
        if (text.isNotEmpty()) {
            val textBefore = headId()
            viewModel.openEditor(file)
            viewModel.saveEditor(text)
            await("\"$made\" to hold $text") { viewModel.textOf(file) == text }
            awaitRecorded(textBefore, "\"$made\" being written")
            viewModel.closeEditor()
        }
        return file
    }

    /** Makes a folder in the folder that is open. */
    fun folder(name: String): File {
        require(!name.contains("/")) { "a folder is made in the folder that is open, not at a path" }
        val before = headId()
        viewModel.createFolder(name)
        await("\"$name\" to be made") { viewModel.entries.any { it.name == name } }
        awaitRecorded(before, "\"$name\" being made")
        return File(viewModel.currentDir!!, name)
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
     * Writes a file **inside the project's worktree**, which is what goes to GitHub.
     *
     * A file written beside the worktree — in the project's own folder — is the
     * local kind, and stays on the phone. The two are the whole point of the
     * project shape, so the harness writes the one the caller asked for.
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
     * Saves, and waits for the version to exist.
     *
     * The app waits a moment before committing a save, so that a burst of typing
     * becomes one version rather than one per keystroke. A test that saved three
     * times as fast as it could would get fewer versions than saves and would be
     * measuring the debounce rather than the history.
     */
    fun saveAsVersion(text: String) {
        val file = viewModel.editingFile ?: error("the editor is not open")
        val key = viewModel.workspace!!.pathString(viewModel.workspace!!.relative(file).orEmpty())
        viewModel.saveEditor(text)
        await("a version of ${file.name} holding \"$text\"", 30_000) {
            val versions = accountHistory().log(key)
            versions.isNotEmpty() && versions.first().let { newest ->
                accountHistory().fileAt(key, newest.id) == text
            }
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
        val folder = File(workspace!!.accountDir, name)
        viewModel.openHistory(folder)
        return viewModel.versions()
    }

    /** Puts a version back, from the history that is open. */
    fun restore(revisionId: String) {
        viewModel.restoreVersion(revisionId)
    }

    /** Closes the history screen. */
    fun closeHistory() {
        viewModel.closeHistory()
    }

    /** Every path the account's own history tracks, read from its newest version. */
    fun appHistoryFiles(): List<String> = accountHistory().tracked()

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
                    appendLine("      branch: ${git.repository.branch}, " +
                        "remote: ${git.repository.config.getString("remote", "origin", "url")}")
                }
            } catch (e: Exception) {
                appendLine("      the repository could not be read: ${e.message}")
            }
        }
        try {
            val hist = accountHistory()
            appendLine("    history: ${hist.log(null, 6).joinToString { it.message.take(30) }}")
            appendLine("    history error: ${hist.lastError}")
            appendLine("    history holds: ${hist.tracked().take(12)}")
            appendLine("    history error after reading: ${hist.lastError}")
        } catch (e: Exception) {
            appendLine("    history could not be read: ${e.message}")
        }
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
