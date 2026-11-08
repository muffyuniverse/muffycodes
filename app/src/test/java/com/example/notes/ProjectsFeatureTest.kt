package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §6 and §7 of the design: **the Projects screen, and what a project is.**
 *
 * A project is **a folder that is its own repository** — `.git`, the clone's files and
 * whatever the person makes there, all in one place. The row says the project, how many
 * changes are waiting, and carries one button that is always in the same place.
 */
class ProjectsFeatureTest : FeatureTest() {

    @Test
    fun `making a project makes a repository first, then the folder with a clone in it`() {
        scene.project("Shopping")

        assertTrue("the repository is on the pretend GitHub", scene.remote("Shopping").isDirectory)
        assertTrue("the folder exists", scene.workspace!!.repoOf("Shopping").isDirectory)
        assertTrue(
            "and the clone brought the seeded files down",
            File(scene.workspace!!.repoOf("Shopping"), "README.md").isFile,
        )
    }

    @Test
    fun `the changes window lists the files to send, and survives the editor`() {
        // **The row says "1 to send"; this is the answer to "which one".** The window is filled from
        // the repository, opens at once and fills behind it, and a file touched inside it opens in the
        // editor — closing which must put the person back in the window, not in the folder.
        scene.signedIn()
        scene.project("groceries")
        scene.open("groceries")
        scene.file("milk.txt", "two litres")
        val project = "groceries"

        scene.viewModel.openChanges(project)
        assertNotNull("it opens at once, empty", scene.viewModel.changes)
        scene.await("the window to fill", 20_000) {
            scene.viewModel.changes?.any { it.path == "milk.txt" } == true
        }

        val milk = scene.viewModel.changes!!.first { it.path == "milk.txt" }
        assertNotNull("and it has a preview", milk.lines)
        assertTrue(
            "whose lines are the file's: ${milk.lines?.map { it.text }}",
            milk.lines!!.any { it.kind == GitService.DiffLine.Kind.ADDED && it.text == "two litres" },
        )

        scene.viewModel.openChangedFile("milk.txt")
        assertEquals(
            "touching it opens the editor",
            listOf("groceries", "milk.txt"),
            scene.viewModel.editingPath,
        )
        scene.viewModel.closeEditor()
        assertNotNull("and closing the editor leaves the window open", scene.viewModel.changes)

        scene.viewModel.closeChanges()
        assertNull("which the cross closes", scene.viewModel.changes)
    }

    @Test
    fun `a new project appears in the list and the view stays where it was`() {
        // It used to open the project. Being moved into an empty folder the moment a
        // clone finishes loses the thread: the list is what shows the thing was made,
        // and the row is there to open when the person wants it.
        scene.project("Shopping")

        assertTrue("the project is in the list", scene.viewModel.projectNames.contains("Shopping"))
        assertFalse(
            "and the browser was not moved inside it: ${scene.viewModel.path}",
            scene.viewModel.path.size > 1,
        )
    }

    @Test
    fun `the seeded licence lands in the clone, because GitHub writes it`() {
        scene.project("Shopping", licence = "mit")

        assertTrue(
            "the licence came down with the clone",
            File(scene.workspace!!.repoOf("Shopping"), "LICENSE").isFile,
        )
    }

    @Test
    fun `a project's row counts the changes waiting inside its worktree`() {
        scene.project("Shopping")
        scene.open("Shopping")
        scene.worktreeFile("milk.txt", "two litres")
        scene.viewModel.refreshChanges()
        scene.await("the row to count the change", 20_000) {
            scene.viewModel.changesOf("Shopping") == 1
        }

        assertEquals("one file waiting", 1, scene.viewModel.changesOf("Shopping"))
    }

    @Test
    fun `the app's own folder is not a change the project has to explain`() {
        // **Everything in a project is in its repository now**, so the only thing that can be
        // there without being a change is what the app put there itself. It writes the two lines
        // that keep `agent/` and `.muffy/` out of git, and this is what those lines buy: a
        // project that has been running an agent still says "nothing waiting".
        scene.project("Shopping")
        scene.open("Shopping")
        val records = File(scene.workspace!!.projectDir("Shopping"), AgentLog.DIR).apply { mkdirs() }
        File(records, "session.log").writeText("a record of a conversation")
        scene.viewModel.refreshChanges()

        assertEquals(
            "the record is not a change to send",
            0,
            scene.viewModel.changesOf("Shopping"),
        )
    }

    @Test
    fun `a project cannot be made with a name that is already taken`() {
        scene.project("Shopping")
        scene.viewModel.message = null

        scene.viewModel.createProject("shopping", "mit")

        assertTrue(
            "the app says why",
            scene.viewModel.message!!.contains("already a project"),
        )
        // **`shopping` was refused, so it is not here.** The list is the one project that was made -
        // the settings folder is not a project and has no row of its own (§13f).
        assertEquals("and made nothing", listOf("Shopping"), scene.viewModel.projectNames)
    }

    @Test
    fun `a project cannot be made with a name a file could not have`() {
        scene.viewModel.createProject("bad/name", "mit")

        assertTrue("the app says no", scene.viewModel.message != null)
        // **Empty, and that is the point**: a folder at the account root with no repository is not a
        // project, and the settings folder never was one (§13f).
        assertEquals("and made no project", emptyList<String>(), scene.viewModel.projectNames)
    }

    @Test
    fun `a private project carries no licence file`() {
        // **"Private" is a choice, not a licence**, so it must reach the API as two facts:
        // `private: true`, and no `license_template` at all. Sending `license_template: "private"`
        // would be refused by GitHub, and the refusal would read as a bug in the app rather than as
        // the thing the person asked for.
        //
        // **Through the harness, which waits for the worker.** A project is made off the main
        // thread, so a test that read the folder immediately would find nothing — and would pass on
        // any machine where the clone happened to finish first.
        scene.project("secrets", licence = PRIVATE_LICENCE)

        assertTrue("it was made", "secrets" in scene.viewModel.projectNames)
        val dir = scene.workspace!!.projectDir("secrets")
        val licenceFiles = dir.listFiles()?.filter {
            it.name.startsWith("LICENSE") || it.name.startsWith("COPYING")
        }.orEmpty()
        assertEquals("and no licence file was seeded", emptyList<File>(), licenceFiles)
    }

    @Test
    fun `the licence window offers private first, because it is the safe answer`() {
        // **The default matters more than the list.** A person who has just made a project on their
        // phone has not decided who may read it, and a default of GPL-3.0 publishes their first save
        // to the world before they have written it.
        val fromGitHub = listOf(
            Licence("mit", "MIT License"),
            Licence("gpl-3.0", "GNU General Public License v3.0"),
        )

        val offered = licenceChoices(fromGitHub)

        assertEquals("private is the first entry", PRIVATE_LICENCE, offered.first().key)
        assertTrue("and it says what it is", offered.first().name.contains("Private"))
        assertEquals("GitHub's own list follows, unchanged", fromGitHub, offered.drop(1))
    }

    @Test
    fun `renaming a project renames the folder and the repository together`() {
        scene.project("Shopping")

        scene.viewModel.renameProject("Shopping", "Groceries")
        scene.await("the rename to finish") { !scene.viewModel.busy }

        assertFalse("the old folder is gone", File(scene.workspace!!.accountDir, "Shopping").exists())
        assertTrue("the new folder is there", File(scene.workspace!!.accountDir, "Groceries").isDirectory)
        assertFalse("the old repository is gone", scene.remote("Shopping").exists())
        assertTrue("the new repository is there", scene.remote("Groceries").isDirectory)
    }

    @Test
    fun `renaming a project keeps the clone inside it`() {
        scene.project("Shopping")
        scene.open("Shopping")
        scene.worktreeFile("milk.txt", "two litres")

        scene.viewModel.renameProject("Shopping", "Groceries")
        scene.await("the rename to finish") { !scene.viewModel.busy }

        assertTrue(
            "the worktree moved with the folder",
            File(scene.workspace!!.repoOf("Groceries"), "milk.txt").isFile,
        )
    }

    @Test
    fun `deleting a project from this phone only leaves the repository alone`() {
        scene.project("Shopping")

        scene.viewModel.deleteProject("Shopping", everywhere = false)
        scene.await("the delete to finish") { !scene.viewModel.busy }

        assertFalse("the folder is gone", File(scene.workspace!!.accountDir, "Shopping").exists())
        assertTrue("the repository is untouched", scene.remote("Shopping").isDirectory)
    }

    /**
     * **"Everywhere" no longer reaches GitHub.** Plan §13b.6: `delete_repo` was the one irreversible
     * capability, and it was dropped so that no act in this app can destroy something a person cannot
     * get back. The local folder goes; **the repository stays**, and the message says so and says where.
     *
     * This test used to assert the opposite, which is why it is rewritten rather than deleted: the
     * behaviour changed on purpose, and the record of that is worth keeping.
     */
    @Test
    fun `deleting a project everywhere removes the folder and leaves the repository alone`() {
        scene.project("Shopping")

        scene.viewModel.deleteProject("Shopping", everywhere = true)
        scene.await("the delete to finish") { !scene.viewModel.busy }

        assertFalse("the folder is gone", File(scene.workspace!!.accountDir, "Shopping").exists())
        assertTrue(
            "and the repository is still there, because this app cannot delete one",
            scene.remote("Shopping").exists(),
        )
        assertTrue(
            "and the message says where it went: ${scene.viewModel.message}",
            scene.viewModel.message.orEmpty().contains("still on GitHub"),
        )
    }

    @Test
    fun `deleting a project whose repository is already gone is not an error`() {
        // §6: "A repository already deleted on GitHub is not an error — the local
        // side goes." Deleting it on GitHub by hand is a normal thing to do, and the
        // phone must agree with what is true rather than refuse to tidy up.
        scene.project("Shopping")
        scene.remote("Shopping").deleteRecursively()

        scene.viewModel.deleteProject("Shopping", everywhere = true)
        scene.await("the delete to finish") { !scene.viewModel.busy }

        assertFalse("the folder is gone", File(scene.workspace!!.accountDir, "Shopping").exists())
        assertFalse(
            "and nothing complained about the missing repository",
            scene.viewModel.message.orEmpty().contains("no repository", ignoreCase = true),
        )
    }

    @Test
    fun `deleting something already gone is not a failure`() {
        // **The rule that lives in `delete()`.** A move that runs
        // twice — a person pressing again, a retry after a dropped connection — must land on "it is
        // not there" and stop, rather than reporting a failure for having already succeeded. It used
        // to be decided from the error text (`contains("404")`), which would also swallow a message
        // that mentioned 404 for some other reason.
        //
        // It is exercised through a secret rather than a repository because the app no longer deletes
        // repositories at all (§13b.6) — `delete()` is the one caller, and this is its path.
        val api = DiskGitHub(scene.workspace!!.accountDir, scene.login)
        val ref = RepoRef(scene.login, "Shopping")
        api.putRepoSecret("Shopping", "MUFFY_TEST", "a-value".toByteArray())

        val first = api.deleteRepoSecret("a-key", ref, "MUFFY_TEST")
        val second = api.deleteRepoSecret("a-key", ref, "MUFFY_TEST")

        assertTrue("the first delete succeeds", first.isSuccess)
        assertTrue("and so does the second, which finds nothing", second.isSuccess)
        assertNull("with no error at all", second.error)
    }

    @Test
    fun `the add window offers the account's repositories`() {
        scene.project("Shopping")
        var offered: List<String> = emptyList()
        var problem: String? = null

        scene.viewModel.listRepositories { repos, error ->
            offered = repos.map { it.name }
            problem = error
        }
        scene.await("the list to arrive") { offered.isNotEmpty() || problem != null }

        assertTrue("the repository is offered", offered.contains("Shopping"))
        assertNull("and nothing went wrong", problem)
    }

    @Test
    fun `pulling a repository in makes a project of it`() {
        scene.project("Shopping")
        scene.viewModel.deleteProject("Shopping", everywhere = false)
        scene.await("the delete to finish") { !scene.viewModel.busy }
        assertEquals("no projects left at all", emptyList<String>(), scene.viewModel.projectNames)

        val url = scene.github.cloneUrlFor(scene.login, "Shopping")
        scene.viewModel.pullRepository(Repo("Shopping", "${scene.login}/Shopping", false, url))
        scene.await("the pull to finish") { !scene.viewModel.busy }
        scene.await("the repository's files to be here", 30_000) {
            File(scene.workspace!!.repoOf("Shopping"), "README.md").isFile
        }

        assertTrue("the project came back", scene.viewModel.projectNames.contains("Shopping"))
        assertTrue(
            "with the repository's files",
            File(scene.workspace!!.repoOf("Shopping"), "README.md").isFile,
        )
    }

    @Test
    fun `the licences a project can be made with come from the GitHub in use`() {
        var licences: List<String> = emptyList()

        scene.viewModel.listLicences { licences = it.map { licence -> licence.key } }
        scene.await("the licences to arrive") { licences.isNotEmpty() }

        assertTrue("MIT is offered", licences.contains("mit"))
    }
    // ---- a local project: a project with no remote ---------------------------

    @Test
    fun `a local project is a folder with its own history and nothing to push to`() {
        // **A project, not a special case.** Work that does not belong to any repository still
        // deserves versions and an agent; what it does not have is somewhere to send them. The
        // test is here rather than in a file of its own because this is what a project is now —
        // a folder with a history, optionally with a remote.
        scene.viewModel.createLocalProject("scratch")
        scene.await("the local project", 30_000) {
            scene.viewModel.projectNames.contains("scratch") && !scene.viewModel.busy
        }

        val dir = scene.workspace!!.projectDir("scratch")
        assertTrue("the folder is there", dir.isDirectory)
        assertTrue("with a repository in it", File(dir, ".git").isDirectory)
        assertTrue(
            "and the app's two lines in git's own exclude list, not in the person's .gitignore",
            File(dir, ".git/info/exclude").readText().contains("agent/") &&
                !File(dir, ".gitignore").exists(),
        )
        assertTrue("and no remote at all", scene.viewModel.isLocalProject("scratch"))
        assertEquals("its name is the one typed", listOf("scratch"), scene.viewModel.projectNames)
    }

    @Test
    fun `a project is added from an address, and the check catches a wrong one first`() {
        // **The operator's proposal, end to end**: *"we can ask user to add remote urls on his own via
        // + button, and we just check with git ls-remote command that is part of the protocol."*
        //
        // A bare repository on this phone is a real remote for exactly this purpose: `ls-remote` works
        // against it, so the check runs for real without a network, and a mistyped address fails the
        // way a mistyped address fails.
        val remote = File(scene.root, "address-remote")
        GitService.initOrOpen(remote, GitAuthor("Test", "test@example.com")).use { repo ->
            File(remote, "milk.txt").writeText("two litres\n")
            GitService.commitAll(repo, "milk", GitAuthor("Test", "test@example.com"))
        }

        // A path that is not there: refused, and **nothing is made** — which is the point of asking
        // before cloning rather than after.
        var answered: Boolean? = null
        scene.viewModel.addProjectFromUrl(
            File(scene.root, "nothing-here").absolutePath,
            "wrong",
        ) { answered = it }
        scene.await("the refusal to arrive", 30_000) { answered != null }
        assertEquals("the wrong address was refused", false, answered)
        assertFalse("and no project was made", scene.viewModel.projectNames.contains("wrong"))
        assertTrue(
            "and it says the address has no repository: ${scene.viewModel.message}",
            scene.viewModel.message.orEmpty().contains("no repository at that address"),
        )

        // The real one: the same call, a real bare repository, and the project arrives with its file.
        answered = null
        scene.viewModel.addProjectFromUrl(remote.absolutePath, "") { answered = it }
        scene.await("the project to arrive", 30_000) { answered != null && !scene.viewModel.busy }

        assertEquals("it was added", true, answered)
        assertEquals(
            "and the name came from the address's last segment",
            true,
            scene.viewModel.projectNames.contains("address-remote"),
        )
        assertEquals(
            "with the file the repository held",
            "two litres\n",
            File(scene.workspace!!.projectDir("address-remote"), "milk.txt").readText(),
        )
    }

    @Test
    fun `an ssh address is refused in words before anything is made`() {
        // No SSH transport is packaged, so this would die inside JGit with an error naming nothing —
        // and it looks usable, because `hostOf` reads a host out of the scp form happily. Every host's
        // page shows this form first, so it is the address a person will paste.
        var answered: Boolean? = null
        scene.viewModel.addProjectFromUrl("git@github.com:owner/name.git", "name") { answered = it }

        assertEquals("it was refused", false, answered)
        assertTrue(
            "in words, naming the form that works: ${scene.viewModel.message}",
            scene.viewModel.message.orEmpty().contains("HTTPS"),
        )
        assertFalse("and nothing was made", scene.viewModel.projectNames.contains("name"))
    }

    @Test
    fun `a new local project arrives with an empty prompts folder`() {
        // **What replaced the preset.** The operator: *"just create empty system_data folder tree for
        // each project when it is added and put there subfolders for all of our doc types"*. Empty is
        // the point — a starter document would be a project type by another name, and the types were
        // removed.
        scene.viewModel.createLocalProject("scratch")
        scene.await("the local project", 30_000) {
            scene.viewModel.projectNames.contains("scratch") && !scene.viewModel.busy
        }

        val dir = scene.workspace!!.projectDir("scratch")
        assertTrue("the prompts folder is there", ProjectTree.promptsDir(dir).isDirectory)
        assertEquals(
            "and it holds only the marker, so nothing was prescribed",
            listOf(Workspace.KEEPER),
            ProjectTree.promptsDir(dir).listFiles()?.map { it.name }.orEmpty(),
        )
        assertFalse(
            "no workflow was invented for a project nobody has described",
            File(dir, ".github").exists(),
        )
    }

    @Test
    fun `a new project opens with nothing waiting to be sent`() {
        // **The markers are files, so they DO need committing.** An empty folder is invisible to
        // git, so each subject folder holds a marker — and a marker left uncommitted is a project
        // that opens showing four changes nobody made. That is the same fault the preset copy had,
        // one layer down, and this is the assertion that catches it.
        scene.viewModel.createLocalProject("scratch")
        scene.await("the local project", 30_000) {
            scene.viewModel.projectNames.contains("scratch") && !scene.viewModel.busy
        }
        // **No history length any more** — the fourth argument used to be `keepProvider`, and the
        // setting it read is gone. What this test asks is that a project that was just created has
        // nothing waiting, which needs no argument at all.
        val git = ProjectGit(
            scene.workspace!!,
            { null },
            { GitAuthor("Test", "test@example.com") },
        )
        val changes = git.changes("scratch")
        assertEquals("nothing is waiting: $changes", 0, changes.staged + changes.unstaged + changes.untracked)
    }

    @Test
    fun `documents the agent writes into the folder become the brief`() {
        // **The whole point of the folder**: a person asks the agent to write a rule, it writes a
        // file, and from that moment the rule is in every brief. This is the seam between the
        // project tree and what the agent is actually given.
        scene.viewModel.createLocalProject("scratch")
        scene.await("the local project", 30_000) {
            scene.viewModel.projectNames.contains("scratch") && !scene.viewModel.busy
        }

        val dir = scene.workspace!!.projectDir("scratch")
        File(ProjectTree.promptsDir(dir), "never-push.md").writeText("Never push without asking.")

        assertTrue(
            "the new rule is in the brief",
            AgentPrompt.project(dir).contains("Never push without asking."),
        )
    }

    @Test
    fun `sync on a local project says why it cannot, rather than doing nothing`() {
        // The button is not offered, and the act refuses in words if it is asked anyway — a press
        // that silently does nothing is the failure this app keeps fixing.
        scene.viewModel.createLocalProject("scratch")
        scene.await("the local project", 30_000) {
            scene.viewModel.projectNames.contains("scratch") && !scene.viewModel.busy
        }

        scene.viewModel.sync("scratch")
        scene.await("the refusal", 5_000) { scene.viewModel.message?.contains("no GitHub") == true }

        assertTrue(
            "and it says where the project lives: ${scene.viewModel.message}",
            scene.viewModel.message.orEmpty().contains("this phone"),
        )
    }

    @Test
    fun `publishing a release is refused while anything is unsent`() {
        // **The one rule that makes a release mean something**, and it is enforced in the view model
        // rather than only by the button: a version published from a tree with unsent work names a
        // commit GitHub does not have, so the release would be of different code from the one the
        // person is looking at.
        //
        // **The count is taken when the release is asked for**, because `changesOf` is a snapshot the
        // last refresh took — a save made since would not be in it, and the guard would pass while the
        // sentence it exists to prevent was true.
        scene.signedIn()
        scene.project("Shopping")
        scene.open("Shopping")
        scene.file("notes.txt", "unsent\n")
        // **A key, so the refusal under test is the one about the tree.** Without it the answer is
        // "sign in first", which is true and is not what this test is about — and the guess would have
        // been invisible, because both are refusals.
        scene.credentials.setToken(scene.login, "a-key")
        scene.viewModel.message = null

        scene.viewModel.startRelease("1.5")
        scene.await("the answer") { !scene.viewModel.busy && scene.viewModel.message != null }

        val said = scene.viewModel.message.orEmpty()
        assertTrue("it says why: $said", said.contains("not sent yet"))
        assertTrue("and names the version's problem: $said", said.contains("GitHub does not have"))
        // **Nothing was dispatched**, which is the half a message alone cannot prove.
        assertFalse(
            "the pretend GitHub has no run",
            File(scene.remote("Shopping"), "ci").listFiles().orEmpty().isNotEmpty(),
        )
    }

    @Test
    fun `publishing a clean project dispatches the release workflow on main`() {
        // **The door, walked end to end.** `Release.start`, `Release.nameProblem` and `startRelease`
        // all existed and were tested while nothing called any of them — the app could not publish a
        // release from inside it at all. This is the proof that the button reaches the dispatch: the
        // project is clean, a key is stored, and the pretend GitHub records what was asked for.
        scene.signedIn()
        scene.project("Shopping")
        scene.open("Shopping")
        scene.credentials.setToken(scene.login, "a-key")
        scene.await("the project to be clean") { scene.viewModel.changesOf("Shopping") == 0 }
        scene.viewModel.message = null

        scene.viewModel.startRelease("1.5")
        scene.await("the dispatch") {
            scene.github.dispatches(RepoRef(scene.login, "Shopping")).isNotEmpty()
        }

        val sent = scene.github.dispatches(RepoRef(scene.login, "Shopping")).single()
        assertEquals("the release workflow", Release.WORKFLOW, sent.workflow)
        assertEquals("on the branch a release is published from", "main", sent.branch)
        assertEquals("with the version the person typed", "1.5", sent.inputs["version_name"])
        // **And nothing was asked for that was not chosen**: a dev copy is a second APK, and a box
        // nobody ticked must not be sent as "true".
        assertFalse("no dev copy was asked for", sent.inputs.containsKey("dev_build"))
    }

    @Test
    fun `the system folder is reachable at the root and is not a project`() {
        // **A folder, not a hidden one** - the settings that belong to the whole account live in it,
        // and the report that asked for it was explicit: "system prompt is outdated. Another reason why
        // it should be an editable file inside system project instead of a hidden one." A hidden file
        // is one nobody edits, and a prompt nobody edits goes stale.
        //
        // **But it is not a project, and that is the operator's correction** (§13f): as a project it had
        // no repository, so the row promised a history, a status and a remote that did not exist - a
        // dangling row. It is a folder at the account root, it is listed as an entry there, and every
        // project can reach it through a link.
        assertFalse("no project row", scene.viewModel.projectNames.any { it.equals("system", ignoreCase = true) })
        assertTrue("but it is a folder in the root listing", scene.names().contains("system"))
        assertTrue(
            "and the folder is really there",
            File(scene.workspace!!.accountDir, Workspace.SYSTEM_PROJECT).isDirectory,
        )

        scene.viewModel.message = null
        scene.viewModel.deleteProject("system", everywhere = false)
        assertTrue("and it cannot be deleted", scene.viewModel.message != null)
        assertTrue(
            "the folder is still there",
            File(scene.workspace!!.accountDir, Workspace.SYSTEM_PROJECT).isDirectory,
        )
    }

    @Test
    fun `the system project keeps its name in both directions`() {
        // **Renaming it away is one half; renaming another project *to* it is the other.** Without
        // the second, an account ends up with two folders called `system`, and the delete guard
        // then refuses both — which is worse than refusing the rename.
        scene.viewModel.message = null
        scene.viewModel.renameProject("system", "settings")
        assertTrue("it cannot be renamed away", scene.viewModel.message != null)
        assertTrue(
            "and it still has its own name",
            File(scene.workspace!!.accountDir, Workspace.SYSTEM_PROJECT).isDirectory,
        )

        scene.project("groceries")
        scene.viewModel.message = null
        scene.viewModel.renameProject("groceries", "System")
        assertTrue("nor can another project take it", scene.viewModel.message != null)
        assertTrue(
            "and groceries is still groceries",
            scene.viewModel.projectNames.contains("groceries"),
        )
    }

    @Test
    fun `the system prompt is a file in the system project, where the editor can reach it`() {
        // The prompt is a file, not a constant, so a person can change what their agent is told.
        // It used to live at the account's root — a place the app draws as the project list, with
        // nothing in the interface leading to it. A file nothing leads to is a file nobody edits.
        val ws = scene.workspace!!
        val prompt = AgentPrompt.systemFile(ws.accountDir)

        // **Asked first, because asking is what writes it.** The shipped text becomes a file the
        // first time the agent needs it — the same rule the project layer already follows, and the
        // reason a missing file is not an agent with no instructions.
        assertEquals("the shipped text to begin with", AgentPrompt.SYSTEM_DEFAULT, AgentPrompt.system(ws.accountDir))
        assertTrue("the system prompt is written where the project is", prompt.isFile)

        // The file is the person's: editing it changes the prompt, with no second copy winning.
        prompt.writeText("Say only the word: milk.")
        assertEquals("the file is the prompt", "Say only the word: milk.", AgentPrompt.system(ws.accountDir))
    }

    @Test
    fun `ignoring a file takes it out of the repository, so its edits stop being sent`() {
        // **The defect this test was written for.** `.gitignore` is not a commit filter; it only
        // stops git from *starting* to track a path. A file already in the repository ignores the
        // ignore line completely: git keeps committing it. And every file a person has saved is
        // already tracked, because a save commits — so "Add to .gitignore" wrote a line, committed
        // it, said "Ignoring x", and the file went on being pushed.
        //
        // The act is completed by taking the path out of the index. The file stays on the phone,
        // editable, and stops being sent.
        scene.project("groceries")
        val ws = scene.workspace!!
        val dir = ws.projectDir("groceries")
        val file = File(dir, "secret.txt")
        // A `ProjectGit` of the test's own, because the point is what the *repository* holds — the
        // question the view model cannot answer about itself.
        val git = ProjectGit(ws, { null }, { GitAuthor("Muffy", "muffy@example.com") })
        ws.write(file, "the first version")
        git.commitOutcome("groceries", "a save")

        // It really is in the repository, or the rest of this proves nothing.
        assertTrue(
            "the file is in the repository before it is ignored",
            scene.repo("groceries").tracked().contains("secret.txt"),
        )

        scene.viewModel.ignoreInGit(file)
        scene.await("the ignore to be committed", 30_000) {
            !scene.repo("groceries").tracked().contains("secret.txt")
        }

        // And now the edit that used to sneak in.
        ws.write(file, "the second version")
        git.commitOutcome("groceries", "another save")

        assertTrue("the file is still on the phone", file.isFile)
        assertEquals("with the edit in it", "the second version", file.readText())
        assertFalse(
            "and the edit never reached the repository",
            scene.repo("groceries").tracked().contains("secret.txt"),
        )
    }

    @Test
    fun `ignoring a file GitHub already has says its earlier versions stay there`() {
        // **Report 9's other half, and the sentence the operator asked for.** Ignoring stops a path
        // being *sent from now on*; it does not take back what was sent. Measured: after untracking
        // and pushing, a fresh clone has no file in its tree, but `git log --all` still finds the
        // commit and `git cat-file` still prints the contents. A person ignoring a file because it
        // holds a key has not removed the key, and the app has to say so.
        scene.project("groceries")
        val ws = scene.workspace!!
        val dir = ws.projectDir("groceries")
        val file = File(dir, "secret.txt")
        val git = ProjectGit(ws, { null }, { GitAuthor("Muffy", "muffy@example.com") })
        // A real remote, because "was it pushed" is a question about a real ref. A bare repository
        // on disk is enough: a push writes `refs/remotes/origin/<branch>` exactly as GitHub would.
        val bare = File(File(System.getProperty("java.io.tmpdir"), "remote-${System.nanoTime()}"), "groceries.git")
        // **A bare repository, made the way the rest of this suite makes one.** `Git.init()` returns
        // an `InitCommand`, not a `Git`, so the command is configured, run, and the result closed.
        // `setInitialBranch("main")` matters here: the assertion below looks up the tracking ref by
        // the branch's own name, and a remote whose default is `master` would answer a different
        // question than the one the test is asking.
        org.eclipse.jgit.api.Git.init()
            .setDirectory(bare)
            .setBare(true)
            .setInitialBranch("main")
            .call()
            .close()
        git.open("groceries")!!.use { GitService.setRemoteUrl(it, bare.absolutePath) }

        ws.write(file, "the key that must not travel")
        git.commitOutcome("groceries", "a save")
        val pushed = git.open("groceries")!!.use { repo -> GitService.push(repo, null) }
        assertTrue(
            "the push really reached the remote, or the rest of this proves nothing: ${pushed.message}",
            pushed.success,
        )
        assertTrue(
            "and the remote-tracking ref is there",
            git.open("groceries")!!.use { repo ->
                repo.findRef("refs/remotes/origin/${repo.branch}") != null
            },
        )

        scene.viewModel.ignoreInGit(file)

        val said = scene.viewModel.message.orEmpty()
        assertTrue("it says the file is ignored: $said", said.contains("Ignoring secret.txt"))
        assertTrue(
            "and that earlier versions stay on GitHub: $said",
            said.contains("earlier versions stay on GitHub"),
        )
        assertTrue(
            "and what ignoring does not do: $said",
            said.contains("does not take back what was sent"),
        )
    }

    @Test
    fun `ignoring a file that was never pushed says only that it is ignored`() {
        // **The other sentence, and the reason there are two.** A file saved and never synced is in
        // the local history and not on GitHub, so warning that "earlier versions stay on GitHub"
        // would be false - on the one screen where a person is trying to be careful. The warning is
        // worth something only if its absence means something.
        scene.project("groceries")
        val ws = scene.workspace!!
        val dir = ws.projectDir("groceries")
        val file = File(dir, "notes.txt")
        val git = ProjectGit(ws, { null }, { GitAuthor("Muffy", "muffy@example.com") })
        ws.write(file, "never sent anywhere")
        git.commitOutcome("groceries", "a save")
        // No remote at all: this project stays on the phone.

        scene.viewModel.ignoreInGit(file)

        val said = scene.viewModel.message.orEmpty()
        assertTrue("it says the file is ignored: $said", said.contains("Ignoring notes.txt"))
        assertFalse(
            "and says nothing about GitHub, because it was never there: $said",
            said.contains("GitHub"),
        )
    }

    @Test
    fun `tracking an ignored file again puts it back in the repository`() {
        // **The other direction of one act.** Ignoring is completed by taking the path out of the
        // index; tracking is completed by putting it back. Without this the button is a one-way
        // door, and a person who ignored a file by mistake has no way to undo it from the app —
        // which is what "a simple tracked/untracked switch" means it must not be.
        scene.project("groceries")
        val ws = scene.workspace!!
        val dir = ws.projectDir("groceries")
        val file = File(dir, "notes.txt")
        val git = ProjectGit(ws, { null }, { GitAuthor("Muffy", "muffy@example.com") })
        ws.write(file, "something worth sending")
        git.commitOutcome("groceries", "a save")

        scene.viewModel.ignoreInGit(file)
        scene.await("the ignore to be committed", 30_000) {
            !scene.repo("groceries").tracked().contains("notes.txt")
        }

        // The same button, pressed again, because the state is what decides the direction.
        scene.viewModel.ignoreInGit(file)
        scene.await("the path to be tracked again", 30_000) {
            scene.repo("groceries").tracked().contains("notes.txt")
        }

        val said = scene.viewModel.message.orEmpty()
        assertTrue("it says it is tracked again: $said", said.contains("Tracking notes.txt again"))
        assertEquals("with what is on the phone, not a history walk", "something worth sending",
            file.readText())
        // **And the line is gone from `.gitignore`**, or the next save would untrack it again and the
        // button would appear not to have worked.
        val ignore = File(dir, Workspace.IGNORE)
        val text = if (ignore.isFile) ignore.readText() else ""
        assertFalse("the ignore line was removed: $text", text.contains("notes.txt"))
    }

    @Test
    fun `ignoring a folder and then tracking it again takes the whole folder`() {
        // **The folder rule, both ways.** A folder goes into `.gitignore` with a trailing slash, and
        // taking it out has to match that line rather than the bare name — otherwise the line stays,
        // the folder stays ignored, and the button says it is tracked.
        scene.project("groceries")
        val ws = scene.workspace!!
        val dir = ws.projectDir("groceries")
        val folder = File(dir, "drafts").apply { mkdirs() }
        File(folder, "one.txt").writeText("one")
        val git = ProjectGit(ws, { null }, { GitAuthor("Muffy", "muffy@example.com") })
        git.commitOutcome("groceries", "the folder")

        scene.viewModel.ignoreInGit(folder)
        scene.await("the folder to be ignored", 30_000) {
            File(dir, Workspace.IGNORE).let { it.isFile && it.readText().contains("drafts/") }
        }
        scene.viewModel.ignoreInGit(folder)
        scene.await("the folder to be tracked again", 30_000) {
            !File(dir, Workspace.IGNORE).let { it.isFile && it.readText().contains("drafts/") }
        }

        val text = File(dir, Workspace.IGNORE).readText()
        assertFalse("the trailing-slash line is gone: $text", text.contains("drafts/"))
        assertFalse("and the bare name is not left behind: $text", text.contains("drafts"))
    }

    @Test
    fun `a local project keeps its versions like any other`() {
        scene.viewModel.createLocalProject("scratch")
        scene.await("the local project", 30_000) {
            scene.viewModel.projectNames.contains("scratch") && !scene.viewModel.busy
        }
        scene.open("scratch")
        val file = scene.file("note.txt", "first")
        scene.await("the first version", 30_000) { scene.repo("scratch").tracked().contains("note.txt") }

        scene.openEditor(file)
        scene.saveEditor("second")
        // **Waited for by its text, not by a count.** The file already has two versions (the empty
        // one it was made as, and `first`), so "at least two" was true before the save had run and
        // the assertion after it read the history too early.
        scene.await("the second version to be recorded", 30_000) {
            scene.versionsOf(file).any { it.second == "second" }
        }

        assertEquals(
            // **The empty file it was made as is the oldest version**, and that is the app being
            // honest: making a file is a change, so it is a version. A list that hid it would be
            // a history the app does not have, and "go back to before I wrote anything" would
            // have nothing to go back to.
            "every version is there, newest first",
            listOf("second", "first", ""),
            scene.versionsOf(file).map { it.second },
        )
    }

}
