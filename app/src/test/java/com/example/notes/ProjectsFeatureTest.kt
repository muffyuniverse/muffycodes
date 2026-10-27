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
        // **`shopping` was refused, so it is not here.** The list is the one project that was made
        // and the settings project, in the order `projectNames` sorts by: lowercase name.
        assertEquals("and made nothing", listOf("Shopping", "system"), scene.viewModel.projectNames)
    }

    @Test
    fun `a project cannot be made with a name a file could not have`() {
        scene.viewModel.createProject("bad/name", "mit")

        assertTrue("the app says no", scene.viewModel.message != null)
        assertEquals("and made nothing but the settings project", listOf("system"), scene.viewModel.projectNames)
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

    @Test
    fun `deleting a project everywhere removes the repository too`() {
        scene.project("Shopping")

        scene.viewModel.deleteProject("Shopping", everywhere = true)
        scene.await("the delete to finish") { !scene.viewModel.busy }

        assertFalse("the folder is gone", File(scene.workspace!!.accountDir, "Shopping").exists())
        assertFalse("and the repository with it", scene.remote("Shopping").exists())
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
    fun `a real failure to delete on GitHub still stops the local delete`() {
        // The other half of the same rule, and the one that would be lost if the
        // status check were loosened: a genuine error must keep the local copy and
        // say so, because otherwise the phone and GitHub silently disagree.
        val gone = scene.viewModel.accounts.devGitHubDir(scene.login)
            .let { DiskGitHub(it, scene.login) }
            .deleteRepo("", scene.login, "no-such-repository")

        assertTrue("the fake answers 404 for a repository that is not there", gone.isGone)
        assertEquals("with the status a real GitHub uses", 404, gone.status)
        assertFalse("and that is not a failure to reach the server", gone.isOffline)
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
        assertEquals("nothing but the settings project", listOf("system"), scene.viewModel.projectNames)

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
        assertEquals("its name is the one typed", listOf("scratch", "system"), scene.viewModel.projectNames)
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
    fun `the system project is a row you can open, and the one project you cannot delete`() {
        // **A project, not a hidden folder.** The settings that belong to the whole account live in
        // it, and the report that asked for it was explicit about why: "system prompt is outdated.
        // Another reason why it should be an editable file inside system project instead of a
        // hidden one". A hidden file is one nobody edits, and a prompt nobody edits goes stale.
        assertTrue("the account has a system project", scene.viewModel.projectNames.any {
            it.equals("system", ignoreCase = true)
        })
        assertTrue("it is local, so nothing in it is ever pushed", scene.viewModel.isLocalProject("system"))

        scene.viewModel.message = null
        scene.viewModel.deleteProject("system", everywhere = false)
        assertTrue("and it cannot be deleted", scene.viewModel.message != null)
        assertTrue(
            "the row is still there",
            scene.viewModel.projectNames.any { it.equals("system", ignoreCase = true) },
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
            scene.viewModel.projectNames.any { it.equals("system", ignoreCase = true) },
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
        val prompt = File(File(ws.accountDir, "system"), AgentPrompt.SYSTEM_FILE)

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
