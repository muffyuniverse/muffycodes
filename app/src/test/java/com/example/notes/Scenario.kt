package com.example.notes

import android.app.Application
import android.os.Looper
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
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

    /**
     * The named secrets, kept the same way.
     *
     * **A store that inherits the interface's default of null is a store with no secrets**, which is right for
     * every test that is not about MCP and wrong for one that is: a test asserting a token reaches a server
     * would be asserting that `null` reaches it.
     */
    private val secrets = LinkedHashMap<String, String>()

    /**
     * Keyed by name, and **the host is enforced**, so a test that asserts a token reaches a server is
     * asserting the same rule the real store applies. A fake that ignored the host would let the suite pass
     * while the app leaked.
     */
    private val secretHosts = LinkedHashMap<String, String>()

    override fun secret(name: String, host: String): String? {
        val allowed = secretHosts[name] ?: return null
        val asked = host.trim().lowercase()
        val stored = allowed.trim().lowercase()
        if (stored.isEmpty() || asked.isEmpty()) {
            if (asked != stored) return null
        } else if (!(asked == stored || asked.endsWith(".$stored"))) {
            return null
        }
        return secrets[name]
    }

    override fun setSecret(name: String, value: String, host: String) {
        secrets[name] = value
        secretHosts[name] = host
    }

    /**
     * **Every secret, project-scoped ones included** — the fake must answer what the real store answers,
     * or a test would pass here and fail on a phone. The project scopes are folded in by name, because
     * that is what the window draws rows from.
     */
    override fun secretNames(): List<String> =
        (secrets.keys + projectSecrets.keys.map { it.substringAfter('/') } + generalSecrets.keys)
            .distinct()
            .sorted()

    /**
     * The host a secret is bound to.
     *
     * **Without this the fake inherits the interface's null**, and a test that asserts a refusal names
     * the host it was really stored for would be asserting a sentence the fake could never produce —
     * which is exactly how it failed the first time.
     */
    override fun secretHost(name: String): String? = secretHosts[name]

    /**
     * **Every row, with the scope and host each map really holds** — the three maps are separate here
     * exactly as the three key prefixes are separate in the real store, so a fake that merged them
     * would pass a test the phone fails.
     */
    override fun secretRows(): List<SecretRow> =
        (
            generalSecrets.keys.map { SecretRow(it, generalHosts[it].orEmpty(), SecretScope.General) } +
                secrets.keys.map { SecretRow(it, secretHosts[it].orEmpty(), SecretScope.Account) } +
                projectSecrets.keys.map {
                    SecretRow(
                        it.substringAfter('/'),
                        projectHosts[it].orEmpty(),
                        SecretScope.Project(it.substringBefore('/')),
                    )
                } +
                branchSecrets.keys.map {
                    val project = it.substringBefore('/')
                    val rest = it.removePrefix("$project/")
                    SecretRow(
                        rest.substringAfter('/'),
                        branchHosts[it].orEmpty(),
                        SecretScope.Branch(project, rest.substringBefore('/')),
                    )
                }
            )
            .sortedWith(compareBy({ it.name }, { scopeOrder(it.scope) }))

    override fun forgetSecret(name: String, scope: SecretScope) {
        when (scope) {
            SecretScope.General -> {
                generalSecrets.remove(name)
                generalHosts.remove(name)
            }
            SecretScope.Account -> {
                secrets.remove(name)
                secretHosts.remove(name)
            }
            is SecretScope.Project -> {
                projectSecrets.remove("${scope.name}/$name")
                projectHosts.remove("${scope.name}/$name")
            }
            is SecretScope.Branch -> {
                val key = "${scope.project}/${scope.branch}/$name"
                branchSecrets.remove(key)
                branchHosts.remove(key)
            }
        }
    }

    /**
     * Project-scoped secrets, keyed `project/name`.
     *
     * **A separate map, not a prefix in [secrets]** — the real store keeps them under a different pref
     * key, so a fake that merged the two would pass a test the phone fails. Both maps are listed by
     * [secretNames], because the window draws a row per secret whatever its scope.
     */
    private val projectSecrets = LinkedHashMap<String, String>()
    private val projectHosts = LinkedHashMap<String, String>()

    /**
     * Branch-scoped secrets, keyed `project/branch/name`.
     *
     * **A fourth map, not a prefix in the project one** — the real store keeps them under a different
     * pref key, so a fake that folded them into the project's would pass a test where a branch value
     * leaked to its sibling branch.
     */
    private val branchSecrets = LinkedHashMap<String, String>()
    private val branchHosts = LinkedHashMap<String, String>()

    /** General secrets: readable from every project, whatever the account (§13c.4). */
    private val generalSecrets = LinkedHashMap<String, String>()
    private val generalHosts = LinkedHashMap<String, String>()

    /**
     * **Connections**, keyed `scope/kind` — the pair and the choice in two maps, because the real
     * store keeps them under two keys for the same reason they inherit differently (see
     * `Connections.kt`). The account scope is absent on purpose: its pair is `token_`/`model_key_`
     * plus the account's own records, so a fake that stored one here would let a test pass where the
     * phone reads nothing.
     */
    private val connections = LinkedHashMap<String, Connection>()
    private val choices = LinkedHashMap<String, Choice>()

    /**
     * **Every scope keys a row, the account included** — a row there is a person saying "not the
     * account's own records", which is a thing the phone can now store. A fake that refused it would
     * have hidden exactly the case the store was changed for.
     */
    private fun connKey(scope: SecretScope, kind: ConnKind): String? = when (scope) {
        SecretScope.Account -> "account/${kind.name}"
        SecretScope.General -> "general/${kind.name}"
        is SecretScope.Project -> "project_${scope.name}/${kind.name}"
        is SecretScope.Branch -> "branch_${scope.project}/${scope.branch}/${kind.name}"
    }

    override fun connection(scope: SecretScope, kind: ConnKind): Connection? =
        connKey(scope, kind)?.let { connections[it] }

    override fun setConnection(scope: SecretScope, kind: ConnKind, connection: Connection) {
        val key = connKey(scope, kind) ?: return
        // **A row that says nothing is removed**, the same rule the store follows: empty is the
        // inheritance, and a fake that kept an empty row would pass a test where the phone inherits.
        if (connection.wroteNothing()) connections.remove(key) else connections[key] = connection
    }

    override fun gitConnectionFor(project: String?, branch: String?, host: String): Connection? {
        val at = connectionScope(project, branch) { scope ->
            connection(scope, ConnKind.GIT)?.let { hostCovers(hostOfAddress(it.address), host) } == true
        } ?: return null
        return connection(at, ConnKind.GIT)
    }

    override fun spendable(name: String, host: String, project: String?, branch: String?): String? {
        val kind = kindFor(name) ?: return null
        if (host.isBlank()) return null
        return namedConnection(name, host, kind, project, branch) { at, of -> connection(at, of) }
            ?.secret
            ?.takeIf { it.isNotBlank() }
    }

    override fun forgetConnection(scope: SecretScope, kind: ConnKind) {
        connKey(scope, kind)?.let {
            connections.remove(it)
            choices.remove(it)
        }
    }

    override fun choice(scope: SecretScope, kind: ConnKind): Choice? =
        connKey(scope, kind)?.let { choices[it] }

    override fun setChoice(scope: SecretScope, kind: ConnKind, choice: Choice) {
        val key = connKey(scope, kind) ?: return
        // **Any field keeps the row**, including one that writes no model at all: a branch that changes
        // only the trim writes a choice with three fields, two of them absent.
        if (choice.wroteNothing()) choices.remove(key) else choices[key] = choice
    }

    override fun generalSecret(name: String, host: String): String? {
        val allowed = generalHosts[name] ?: return null
        return if (hostMatches(allowed, host)) generalSecrets[name] else null
    }

    override fun setGeneralSecret(name: String, value: String, host: String) {
        generalSecrets[name] = value
        generalHosts[name] = host
    }

    override fun generalSecretNames(): List<String> = generalSecrets.keys.sorted()

    override fun forgetGeneralSecret(name: String) {
        generalSecrets.remove(name)
        generalHosts.remove(name)
    }

    /**
     * **Branch, then project, then account, then general** — the real store's order, so a test cannot
     * pass where the phone would answer differently.
     *
     * **A nearer scope bound to another host falls through**, which is the real store's rule too: it
     * says nothing about this host, so the wider value still answers.
     */
    override fun secret(project: String?, branch: String?, name: String, host: String): String? {
        if (project != null && branch != null) {
            val key = "$project/$branch/$name"
            val allowed = branchHosts[key]
            if (allowed != null && hostMatches(allowed, host)) return branchSecrets[key]
        }
        if (project != null) {
            val key = "$project/$name"
            val allowed = projectHosts[key]
            if (allowed != null && hostMatches(allowed, host)) return projectSecrets[key]
        }
        return secret(name, host) ?: generalSecret(name, host)
    }

    override fun setBranchSecret(
        project: String,
        branch: String,
        name: String,
        value: String,
        host: String,
    ) {
        branchSecrets["$project/$branch/$name"] = value
        branchHosts["$project/$branch/$name"] = host
    }

    override fun forgetBranchSecrets(project: String, branch: String): Int {
        val doomed = branchSecrets.keys.filter { it.startsWith("$project/$branch/") }
        for (key in doomed) {
            branchSecrets.remove(key)
            branchHosts.remove(key)
        }
        return doomed.size
    }

    override fun setSecret(project: String?, name: String, value: String, host: String) {
        if (project == null) {
            setSecret(name, value, host)
            return
        }
        projectSecrets["$project/$name"] = value
        projectHosts["$project/$name"] = host
    }

    override fun forgetProjectSecrets(project: String): Int {
        val doomed = projectSecrets.keys.filter { it.startsWith("$project/") }
        for (key in doomed) {
            projectSecrets.remove(key)
            projectHosts.remove(key)
        }
        // **The branch rows go with the project**, as they do in the real store: a credential for a
        // project that no longer exists is a row nobody can read or revoke.
        val branches = branchSecrets.keys.filter { it.startsWith("$project/") }
        for (key in branches) {
            branchSecrets.remove(key)
            branchHosts.remove(key)
        }
        return doomed.size + branches.size
    }

    /** The same rule the real store applies, so a test cannot pass where the phone would leak. */
    /**
     * **The same rule the store applies**, because a fake that answered more generously would let the
     * suite pass where the phone leaks. [hostCovers] is the single copy of it.
     */
    private fun hostMatches(allowed: String, asked: String): Boolean = hostCovers(allowed, asked)
}

/**
 * One walk through the app, from the outside.
 *
 * A scenario makes a phone with nothing on it, signs in, and then does what a
 * person would do: opens a folder, writes a file, makes a project, syncs it. Every
 * step goes through the real [AppViewModel] and the real classes under it. The only thing that is not
 * real is a host's API, which is [DiskWho] and answers exactly one question — whose key this is. A
 * repository is a bare folder this fixture makes by hand, which is what a host's page makes, and
 * clones, commits and force-pushes against it are genuine because the git protocol is.
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
     * The host that signing in asks who a key belongs to.
     *
     * Null leaves the app's own choice alone, which is what every scenario but the sign-in ones
     * wants. A test that needs a host to *refuse* a key passes one that does, because the real code
     * path is the one that has to say no.
     */
    signInGitHub: (() -> Who)? = null,
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
        signInGitHub = signInGitHub ?: { HttpWho() },
        // **No housekeeping.** It is a concern of *the app being opened*: a `gc` holds a repository
        // for seconds to minutes, and a shard builds hundreds of these view models. The pass has its
        // own tests (`MaintenanceTest`) and its own seam; a feature test that started one would be
        // measuring a background walk of a temp folder.
        housekeeping = false,
        // **No socket.** Every commit asks the index queue for a pass, and a live embedder would
        // open a connection to OpenRouter with a twenty-second timeout on a thread nothing waits
        // for. `NONE` refuses in a sentence the pass records, which is what a build with no
        // credentials does.
        embedder = Embedder.NONE,
    )

    val login: String get() = AppViewModel.TEST_ACCOUNT

    init {
        if (signIn) signedIn()
    }

    /**
     * Signs the test account in, and waits until the app knows it.
     *
     * **Waiting on the LOGIN, not on "an account exists".** The provisional account means there is
     * always one, so `account != null` was true before the sign-in had done anything - the wait
     * returned at once, and every scenario carried on with `~local` as its workspace. The failure
     * surfaced as a GitHub request going to the internet, which is a long way from the cause.
     */
    fun signedIn(): Scenario {
        viewModel.signInAsTestAccount()
        await("the test account to be signed in") { viewModel.activeLogin == login }
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
        signInGitHub = { DiskWho(login) },
        housekeeping = false,
        embedder = Embedder.NONE,
    )

    /**
     * Where a project's repository is **on the host** — a bare folder, made by the fixture.
     *
     * `repoDir` used to live in `DiskGitHub`, because the app made these. It does not any more: a
     * repository is made on the host's website, so the fixture makes one by hand and the app is given
     * its address, exactly as a person would give it one. What is left in the app is the question
     * `Who` asks.
     */
    fun remote(project: String): File = File(hostDir, "$project.git")

    /** The host's own folder: one directory per repository, as a bare repository each. */
    val hostDir: File get() = File(viewModel.accounts.devGitHubDir(login), login)

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
        // **A picture is recorded as a pointer, not as its bytes.** The commit holds 130 bytes
        // naming the picture by SHA-256, so comparing the tree's text against the file's text would
        // never match for a picture — it would wait twenty seconds and report a commit that did
        // happen as one that did not. So for a picture the question is whether the newest version
        // *names the bytes on disk*, which is what "it is recorded" means once LFS holds them.
        // **Read inside the wait, not before it.** `createImage` writes through a worker, so the
        // file may not exist yet when this is called — reading it once up front made the digest a
        // digest of nothing, and the comparison then failed for ever.
        val isPicture = Workspace.kindOfPath(file.name) == EntryKind.IMAGE
        await("the project's history to record $what", 20_000) {
            val newest = history.head()
            if (newest == null || newest == before) return@await false
            val inTree = history.fileAt(relative, newest) ?: return@await false
            if (isPicture) {
                val onDisk = if (file.isFile) file.readBytes() else return@await false
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(onDisk)
                    .joinToString("") { "%02x".format(it) }
                // **Either shape is "recorded".** With the LFS filter registered the tree holds a
                // pointer naming the digest; a scenario that never registered it holds the picture
                // whole, which is what every build before LFS did. The question here is whether the
                // newest version is *this* picture, and both answers say yes.
                inTree.contains(digest) || inTree == String(onDisk, Charsets.UTF_8)
            } else {
                inTree == file.readText()
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
        // **The name the app will actually use, which is now the name as typed.** The app no longer
        // rewrites names — see `Workspace.nameProblem` for the measurement that removed it — so the
        // harness asks for what it asked for, and only a blank name falls back.
        val made = Workspace.withDefaultExtension(name).trim().ifBlank { "untitled" }
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

    /**
     * Makes a picture in the folder that is open, the way `+ New image` does.
     *
     * **Through the view model, not by writing a file**, so the scenario exercises the same path a
     * person's press does — the size dialogue's choice, the writer, the commit and the redraw.
     */
    fun picture(name: String, canvas: Look.Canvas = Look.DEFAULT_CANVAS): File {
        val made = (if (name.contains('.')) name.trim() else "${name.trim()}.png")
            .ifBlank { "drawing.png" }
        val file = File(viewModel.currentDir!!, made)
        val before = headOf(file)
        viewModel.createImage(name, canvas)
        await("\"$made\" to be made") { viewModel.entries.any { it.name == made } }
        awaitRecordedFor(file, before, "\"$made\" being made")
        return file
    }

    /** Makes a folder in the folder that is open. */
    fun folder(name: String): File {
        require(!name.contains("/")) { "a folder is made in the folder that is open, not at a path" }
        val made = name.trim().ifBlank { "folder" }
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
    /**
     * Makes a **local** project: a repository on this phone with no remote.
     *
     * The one a person can make without signing in, and the one most tests of version history want -
     * they are about commits, not about GitHub.
     */
    fun localProject(name: String): Scenario {
        viewModel.createLocalProject(name)
        // **Waits for the name the app actually made.** A local project's name is checked rather
        // than rewritten, so asking for "Shopping" now makes `Shopping` — but a name that is
        // refused makes nothing at all, and this waits for the one that happened.
        val made = name.trim().ifBlank { "local" }
        await("local project \"$made\" to exist") {
            viewModel.projectNames.any { it == made }
        }
        await("the making of $made to finish", 60_000) { !viewModel.busy }
        return this
    }

    /** The name a project was actually given: the name as typed, or `local` when it was blank. */
    fun made(name: String): String = name.trim().ifBlank { "local" }

    fun project(name: String, licence: String = "mit"): Scenario {
        // **The host's website, then the address** — which is what a person does, and the only path
        // that exists now that the app makes no repositories. A bare repository is what a host's page
        // leaves behind; `ls-remote` and a push answer it for real, so the fixture talks to the thing
        // the phone talks to rather than to a mock of it.
        val address = hostRepo(name, licence)
        viewModel.addProjectFromUrl(address, name)
        await("project \"$name\" to exist") {
            viewModel.projectNames.any { it.equals(name, ignoreCase = true) }
        }
        await("the making of $name to finish", 60_000) { !viewModel.busy }
        // **What the project arrived with, measured now**, while nothing has been saved into it yet.
        // A later `seedCommits` call after a save would answer with the save included, which is the
        // one number this must not be.
        seeds[name] = remoteCommits(name)
        return this
    }

    /**
     * **A repository on the host, made by hand** — the README and licence a host's own page writes.
     *
     * This was `DiskGitHub.createRepo` and `seed`, because the app called them. Nothing in the app
     * makes a repository any more, so the fixture says plainly what a host does: an empty bare
     * repository would clone into a project with no commits, so one commit is made and pushed first.
     *
     * Returns the address a person would paste.
     */
    fun hostRepo(name: String, licence: String = "mit", seeded: Boolean = true): String {
        val dir = remote(name)
        dir.parentFile?.mkdirs()
        // **A repository with this name is replaced, not joined.** A test that deletes a project and
        // makes one with the same name is asking the host for a *new* repository, and pushing a fresh
        // initial commit into the old one would be rejected as a non-fast-forward — a failure about
        // git's history that has nothing to do with the thing under test.
        dir.deleteRecursively()
        if (!seeded) {
            // **A repository just made on the host's page**: bare, and empty. A person's first push
            // fills it, which is the ordinary way a project arrives at a host that has never seen it.
            Git.init().setBare(true).setInitialBranch("main").setDirectory(dir).call().close()
            return dir.toURI().toString()
        }
        val work = File(dir.parentFile, "${dir.name}.seed")
        work.deleteRecursively()
        try {
            Git.init().setBare(true).setInitialBranch("main").setDirectory(dir).call().close()
            work.mkdirs()
            Git.init().setInitialBranch("main").setDirectory(work).call().use { git ->
                File(work, "README.md").writeText("# $name\n\nA test repository.\n")
                if (licence.isNotBlank()) {
                    File(work, "LICENSE").writeText("The $licence licence applies here.\n")
                }
                val who = PersonIdent(login, "$login@example.com")
                git.add().addFilepattern(".").call()
                git.commit()
                    .setMessage("Initial commit")
                    .setAuthor(who)
                    .setCommitter(who)
                    .call()
                val pushed = git.push()
                    .setRemote(dir.toURI().toString())
                    .setRefSpecs(RefSpec("refs/heads/main:refs/heads/main"))
                    .call()
                val landed = pushed.flatMap { it.remoteUpdates }.any {
                    it.status == RemoteRefUpdate.Status.OK ||
                        it.status == RemoteRefUpdate.Status.UP_TO_DATE
                }
                if (!landed) {
                    val said = pushed.flatMap { it.remoteUpdates }
                        .mapNotNull { it.message ?: it.status?.name }
                        .joinToString("; ")
                    error("The first commit did not reach the repository: $said")
                }
            }
        } finally {
            work.deleteRecursively()
        }
        return dir.toURI().toString()
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

    /**
     * How many commits the remote held **before the test touched the project.**
     *
     * A project is made with a licence and the preset pair, so its remote starts with commits that
     * nobody in the test wrote. Asking "did my change reach GitHub" needs that number subtracted:
     * asserting a total instead means the test fails when the app starts making projects with one
     * more file in them, which says nothing about the change under test.
     *
     * The answer is the seed's own commit count, measured from the remote the moment the project
     * exists — so it stays right whatever the seed is.
     */
    private val seeds = mutableMapOf<String, Int>()

    fun seedCommits(project: String): Int = seeds.getOrPut(project) { remoteCommits(project) }

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
                    // **The names, not only the count.** A count of 1 says nothing about *what* is
                    // waiting, and "the person's own file" and "a row the app made" are different
                    // diagnoses — learning which cost a CI round once, which is once too many.
                    val named = (status.added + status.changed + status.modified +
                        status.removed + status.missing + status.untracked)
                        .sorted().joinToString(", ")
                    appendLine("      waiting: $waiting" + if (named.isEmpty()) "" else " — $named")
                    appendLine("      ignored: ${status.ignoredNotInIndex.sorted().joinToString(", ")}")
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
