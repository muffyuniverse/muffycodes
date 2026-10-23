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

    /** Makes a file in the folder that is open and waits for it to be there. */
    fun file(name: String, text: String = ""): File {
        viewModel.createFile(name)
        await("\"$name\" to be made") { viewModel.entries.any { it.name == name } }
        val made = File(viewModel.currentDir!!, name)
        if (text.isNotEmpty()) {
            viewModel.openEditor(made)
            viewModel.saveEditor(text)
            await("\"$name\" to hold $text") { viewModel.textOf(made) == text }
            viewModel.closeEditor()
        }
        return made
    }

    /** Makes a folder in the folder that is open. */
    fun folder(name: String): File {
        viewModel.createFolder(name)
        await("\"$name\" to be made") { viewModel.entries.any { it.name == name } }
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
        return this
    }

    /** Writes a file inside an open project's worktree. */
    fun worktreeFile(name: String, text: String): File {
        val ws = viewModel.workspace!!
        val dir = ws.fileAt(viewModel.path)
        val file = File(dir, name)
        ws.write(file, text)
        return file
    }

    /** Syncs a project and waits until nothing is waiting to be committed. */
    fun sync(project: String): Scenario {
        viewModel.sync(project)
        await("the sync of $project to finish") { !viewModel.busy }
        await("$project to have nothing left to sync") { viewModel.changesOf(project) == 0 }
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

    /** Closes the editor, which is what the back arrow does. */
    fun closeEditor() {
        viewModel.closeEditor()
    }

    /** The versions of a file, newest first, as the history screen would list them. */
    fun versionsOf(file: File): List<Pair<GitService.CommitInfo, String?>> {
        viewModel.openHistory(file)
        val versions = viewModel.versions()
        viewModel.closeHistory()
        return versions
    }

    /** The versions of a folder, newest first, each with what was inside it. */
    fun versionsOfFolder(name: String): List<Pair<GitService.CommitInfo, String?>> {
        val folder = File(workspace!!.accountDir, name)
        viewModel.openHistory(folder)
        val versions = viewModel.versions()
        viewModel.closeHistory()
        return versions
    }

    /** Every path the account's own history tracks, read from its newest commit. */
    fun appHistoryFiles(): List<String> = try {
        Git.open(workspace!!.accountDir).use { git ->
            val head = git.repository.resolve("HEAD")
            if (head == null) emptyList()
            else GitService.filesUnder(git.repository, null, head.name).map { it.first }
        }
    } catch (e: Exception) {
        emptyList()
    }

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
    fun await(what: String, timeoutMs: Long = 30_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(4)
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue("waited $timeoutMs ms for $what", condition())
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
