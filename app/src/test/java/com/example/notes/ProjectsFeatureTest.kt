package com.example.notes

import org.eclipse.jgit.revwalk.RevTag
import org.eclipse.jgit.revwalk.RevWalk
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

        scene.viewModel.createProject("shopping")

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
        scene.viewModel.createProject("bad/name")

        assertTrue("the app says no", scene.viewModel.message != null)
        // **Empty, and that is the point**: a folder at the account root with no repository is not a
        // project, and the settings folder never was one (§13f).
        assertEquals("and made no project", emptyList<String>(), scene.viewModel.projectNames)
    }

    @Test
    fun `an address that names a new repository renames the folder after it`() {
        // **Renaming is a consequence of the address**, which is the operator's decision: the person
        // renames on the host's own website — where the confirmation is the host's — and pastes the
        // address here. So the fixture renames there first, and the app is asked to agree with it.
        scene.localProject("Shopping")
        val address = scene.hostRepo("Groceries", seeded = false)

        scene.viewModel.setAddress("Shopping", address)
        scene.await("the address to be set") { !scene.viewModel.busy }

        assertFalse("the old folder is gone", File(scene.workspace!!.accountDir, "Shopping").exists())
        assertTrue("the new folder is there", File(scene.workspace!!.accountDir, "Groceries").isDirectory)
        assertEquals(
            "and the address is the one that was given",
            address,
            scene.viewModel.remoteUrlOf("Groceries"),
        )
    }

    @Test
    fun `the worktree moves with the folder when the address names it`() {
        scene.localProject("Shopping")
        scene.open("Shopping")
        scene.worktreeFile("milk.txt", "two litres")
        val address = scene.hostRepo("Groceries", seeded = false)

        scene.viewModel.setAddress("Shopping", address)
        scene.await("the address to be set") { !scene.viewModel.busy }

        assertTrue(
            "the worktree moved with the folder",
            File(scene.workspace!!.repoOf("Groceries"), "milk.txt").isFile,
        )
    }

    @Test
    fun `an address that names nothing is refused before the project changes`() {
        // **The check comes first, and this is why.** Rewriting `origin` to an address that does not
        // answer leaves a row saying "synced" while nothing can be pushed — the one outcome §8.3 says
        // must never happen. Nothing has moved here: not the folder, not the address.
        scene.localProject("Shopping")
        val nowhere = File(scene.workspace!!.accountDir, "host/Nothing.git").toURI().toString()

        scene.viewModel.setAddress("Shopping", nowhere)
        scene.await("the refusal") { !scene.viewModel.busy }

        assertEquals(
            "the sentence says there is nothing there",
            "There is no repository at that address.",
            scene.viewModel.message,
        )
        assertTrue(
            "the folder kept its name",
            File(scene.workspace!!.accountDir, "Shopping").isDirectory,
        )
        assertNull("and the project still has no address", scene.viewModel.remoteUrlOf("Shopping"))
    }

    @Test
    fun `an SSH address is refused before a key is even considered`() {
        // **A fact about the address, not about the key.** This app packages no SSH transport at all,
        // so the message must not be one that sends the person looking for a better key.
        scene.localProject("Shopping")

        scene.viewModel.setAddress("Shopping", "git@gitlab.com:owner/shopping.git")

        val said = scene.viewModel.message.orEmpty()
        assertTrue("it says which address cannot be used", said.contains("SSH"))
        assertTrue("and what to use instead", said.contains("https://"))
        assertTrue(
            "nothing was made",
            File(scene.workspace!!.accountDir, "Shopping").isDirectory,
        )
    }

    @Test
    fun `an address with the same name changes only the address`() {
        // **A repository that moved to another owner or host keeps its name**, which is ordinary. The
        // sentence must say the address changed rather than report a rename that did not happen.
        // **The repository the project already has**, set as its address again: the same name, a
        // normal thing when a repository moves owner or host.
        scene.project("Shopping")
        val moved = File(scene.hostDir, "Shopping.git").toURI().toString()

        scene.viewModel.setAddress("Shopping", moved)
        scene.await("the address to be set") { !scene.viewModel.busy }

        assertEquals("the address is the new one", moved, scene.viewModel.remoteUrlOf("Shopping"))
        assertTrue(
            "the folder kept its name",
            File(scene.workspace!!.accountDir, "Shopping").isDirectory,
        )
        assertEquals(
            "and it says so",
            "The address of Shopping is $moved",
            scene.viewModel.message,
        )
    }

    @Test
    fun `deleting a project removes it here and leaves the repository alone`() {
        // **One act now.** There were two radio buttons — "from this phone only" and "everywhere" —
        // and the second stopped being an act when `delete_repo` was dropped: it was the one
        // irreversible capability in the app, and a repository is deleted on the host's own page.
        // What was left was a window asking a question it had already answered.
        scene.project("Shopping")

        scene.viewModel.deleteProject("Shopping")
        scene.await("the delete to finish") { !scene.viewModel.busy }

        assertFalse("the folder is gone", File(scene.workspace!!.accountDir, "Shopping").exists())
        assertTrue("the repository is untouched", scene.remote("Shopping").isDirectory)
        // **And it says where the copy still is**, by address: this sentence promised "on GitHub" for
        // every project, which was true only while every project had one.
        val said = scene.viewModel.message.orEmpty()
        assertTrue("the sentence names the address: $said", said.contains("still at file:"))
        assertTrue("and says nothing here touched it", said.contains("nothing here touched it"))
    }

    @Test
    fun `deleting a local project says there is no copy anywhere else`() {
        // **The other sentence, and it must not promise a copy that does not exist.** A local project
        // has no address, so "its repository is still at …" would be a fiction.
        scene.localProject("scratch")

        scene.viewModel.deleteProject("scratch")
        scene.await("the delete to finish") { !scene.viewModel.busy }

        assertFalse("the folder is gone", File(scene.workspace!!.accountDir, "scratch").exists())
        assertFalse(
            "and nothing claims a copy survived: ${scene.viewModel.message}",
            scene.viewModel.message.orEmpty().contains("still at"),
        )
    }

    @Test
    fun `a project from an address is the same project the picker used to make`() {
        // **What replaced two tests: "the add window offers the account's repositories" and "pulling a
        // repository in makes a project of it".** The list is gone because there is no git command for
        // it — `GET /user/repos` is GitHub's, and every other host spells it differently — so the same
        // act is an address, checked with `ls-remote`, which works on every host at once.
        //
        // The repository is a bare folder on this phone here, which is a real remote for exactly this
        // purpose: no network, and every step is the one the app really takes.
        val remote = File(scene.root, "picker-remote")
        GitService.initOrOpen(remote, GitAuthor("Test", "test@example.com")).use { repo ->
            File(remote, "README.md").writeText("# Shopping\n")
            GitService.commitAll(repo, "readme", GitAuthor("Test", "test@example.com"))
        }
        var answered: Boolean? = null
        scene.viewModel.addProjectFromUrl(remote.absolutePath, "Shopping") { answered = it }
        scene.await("the project to arrive", 30_000) { answered != null }
        assertEquals("it was added", true, answered)
        assertTrue("and it is a project", scene.viewModel.projectNames.contains("Shopping"))
        assertTrue(
            "with the repository's files",
            File(scene.workspace!!.projectDir("Shopping"), "README.md").isFile,
        )
    }

    @Test
    fun `entering a project whose remote has gone says so, and asks for the right address`() {
        // **The operator's request**: *"if project's remote doent answer (there should be a check when
        // you enter the project), then notification should tell that remote no longer responds and ask
        // to set up the correct url."*
        //
        // The repository is a bare folder on this phone, so the check is the real `ls-remote` against
        // the real address — and then the folder is deleted, which is exactly what a host does when a
        // repository is renamed or removed. Nothing about the local project changes, and its files
        // stay readable: that is the point of checking on entry rather than refusing to open.
        val remote = File(scene.root, "vanishing-remote")
        GitService.initOrOpen(remote, GitAuthor("Test", "test@example.com")).use { repo ->
            File(remote, "README.md").writeText("# Groceries\n")
            GitService.commitAll(repo, "readme", GitAuthor("Test", "test@example.com"))
        }
        scene.viewModel.addProjectFromUrl(remote.absolutePath, "groceries")
        scene.await("the project to arrive", 30_000) {
            scene.viewModel.projectNames.contains("groceries") && !scene.viewModel.busy
        }
        // **Healthy first**, or "it says the remote is gone" would be true of a check that never ran.
        scene.await("the first check to answer", 30_000) {
            scene.viewModel.unreachableOf("groceries") == null
        }

        remote.deleteRecursively()

        // Entering is what asks again.
        scene.openBranches("groceries")
        scene.enterBranch(AgentSession.MAIN)
        scene.await("the host to be asked again", 30_000) {
            scene.viewModel.unreachableOf("groceries") != null
        }

        val said = scene.viewModel.unreachableOf("groceries").orEmpty()
        assertTrue("it says the remote did not answer: $said", said.contains("did not answer"))
        assertTrue("and names the address: $said", said.contains(remote.absolutePath))
        // **The project still opens**, because its files are here and are not the problem.
        assertEquals("the project is open", "groceries", scene.viewModel.currentProject)
        assertTrue(
            "and its files are still there",
            File(scene.workspace!!.projectDir("groceries"), "README.md").isFile,
        )
    }

    @Test
    fun `setting a working address clears the fault, without cloning over the work`() {
        // **The operator's rule for a *change* of address** (2026-11-10): *"when you change the address
        // you keep the history and collisions are resolved by the agent on push. no extra dialogue
        // needed."* So this is not a clone: the project keeps every version it has, and the notice goes
        // the moment the new address answers.
        scene.localProject("groceries")
        val dir = scene.workspace!!.projectDir("groceries")
        File(dir, "milk.txt").writeText("two litres\n")
        // **A project with no address is already a fault**, so the premise is that notice.
        assertTrue(
            "it starts with no connection",
            scene.viewModel.unreachableOf("groceries").orEmpty().contains("No address"),
        )

        // **The address is named `groceries`, because a project's name follows its address.** Setting
        // an address whose repository is called something else renames the project — that is the
        // documented rule, and a test that used a different name would be testing the rename rather
        // than the fault clearing.
        val remote = File(scene.root, "groceries")
        GitService.initOrOpen(remote, GitAuthor("Test", "test@example.com")).use { repo ->
            File(remote, "README.md").writeText("# Groceries\n")
            GitService.commitAll(repo, "readme", GitAuthor("Test", "test@example.com"))
        }
        scene.setAddress("groceries", remote.absolutePath)
        scene.await("the address to answer", 30_000) {
            scene.viewModel.unreachableOf("groceries") == null
        }

        assertEquals("the address is the one given", remote.absolutePath, scene.viewModel.remoteUrlOf("groceries"))
        assertEquals(
            "and the work here is untouched, because this is not a clone",
            "two litres\n",
            File(dir, "milk.txt").readText(),
        )
        assertFalse(
            "nor was the remote's file pulled in",
            File(dir, "README.md").exists(),
        )
    }

    // ---- a local project: a project with no remote ---------------------------

    @Test
    fun `a local project is a folder with its own history and nothing to push to`() {
        // **A project, not a special case.** Work that does not belong to any repository still
        // deserves versions and an agent; what it does not have is somewhere to send them. The
        // test is here rather than in a file of its own because this is what a project is now —
        // a folder with a history, optionally with a remote.
        scene.viewModel.createProject("scratch")
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
        // **No address, and the app says what that costs** — the operator's sentence: *"user can set
        // no remote url, but then his agent will not be able to push changes, and project should
        // display that there is no connection."* This is the one answer the row, the strip and the
        // push refusal all read.
        assertEquals(
            "and the project says it has no connection",
            "No address is set, so nothing can be sent from this project.",
            scene.viewModel.unreachableOf("scratch"),
        )
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
        scene.viewModel.createProject("scratch")
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
        scene.viewModel.createProject("scratch")
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
        scene.viewModel.createProject("scratch")
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
    fun `sync on a project with no address says why it cannot, rather than doing nothing`() {
        // The strip is not offered, and the act refuses in words if it is asked anyway — a press
        // that silently does nothing is the failure this app keeps fixing.
        //
        // **The reason is now the host's answer rather than a category.** There is one question,
        // `unreachableOf`, and the sync refusal repeats its words: no address and an address nobody
        // answers are different faults, and a person can only act on the second if the sentence says
        // which one it is.
        scene.localProject("scratch")

        scene.viewModel.sync("scratch")
        scene.await("the refusal", 5_000) { scene.viewModel.message?.contains("cannot be sent") == true }

        val said = scene.viewModel.message.orEmpty()
        assertTrue("and it says there is no address: $said", said.contains("No address is set"))
        assertTrue("and that nothing can leave it: $said", said.contains("nothing can be sent"))
    }

    @Test
    fun `tagging a version is refused while anything is unsent`() {
        // **The rule that makes a version mean something.** A tag over unsent work would send a commit
        // that `main` on the remote does not have — a version of different code from the one the person
        // is looking at — and nothing would say so.
        //
        // **The count is taken when the tag is asked for**, not read from the cached map: `changesOf`
        // is a snapshot the last refresh took, so a save made since would not be in it and the guard
        // would pass while the sentence it exists to prevent was true.
        scene.signedIn()
        scene.project("Shopping")
        scene.open("Shopping")
        // **Written, not `scene.file`** — that helper ends by pressing Sync (a version is what most
        // callers want), and a synced file is exactly what this test must *not* have. What is being
        // tested is the guard over unsent work, so the work is left unsent on purpose.
        val file = File(scene.workspace!!.repoOf("Shopping"), "notes.txt")
        // **Through the editor**, because that is the door a save goes through — a bare write is a
        // file on disk with nothing staged, which is a different row and not this guard's question.
        scene.openEditor(file)
        scene.saveEditor("unsent\n")
        scene.closeEditor()
        scene.viewModel.message = null

        scene.viewModel.tagRelease("1.5")
        scene.await("the answer") { !scene.viewModel.busy && scene.viewModel.message != null }

        val said = scene.viewModel.message.orEmpty()
        assertTrue("it says why: $said", said.contains("not sent yet"))
        assertTrue("and what the version would name: $said", said.contains("remote does not have"))
    }

    @Test
    fun `tagging a clean project makes a tag and sends it to the address`() {
        // **The door, walked end to end.** A release is a tag: it is made at the top of `main`, and the
        // ref is pushed to the project's own address — which is the release the protocol has, and the
        // reason this works on a host this app has no API for.
        scene.signedIn()
        scene.project("Shopping")
        scene.open("Shopping")
        // **A remote that exists.** A push to a path that is not there fails with the filesystem's own
        // words, which is a fact about the fixture rather than about tags — so the bare repository is
        // made first, exactly as a real remote is made by the host.
        val remote = File(scene.workspace!!.accountDir, "local-remote")
        org.eclipse.jgit.api.Git.init()
            .setDirectory(remote)
            .setBare(true)
            .setInitialBranch("main")
            .call()
            .close()
        scene.await("the project to be clean") { scene.viewModel.changesOf("Shopping") == 0 }
        val author = GitAuthor("Test", "test@example.com")
        GitService.initOrOpen(scene.workspace!!.projectDir("Shopping"), author).use {
            GitService.setRemoteUrl(it, remote.absolutePath)
        }
        scene.viewModel.message = null

        scene.viewModel.tagRelease("1.5", "the first one")
        scene.await("the answer", 30_000) { !scene.viewModel.busy && scene.viewModel.message != null }

        assertTrue("it says what happened: ${scene.viewModel.message}", scene.viewModel.message.orEmpty().contains("1.5"))
        // **The tag is in the project's own repository**, and its message is the one typed: a note that
        // did not become part of the tag would be a field that does nothing.
        GitService.initOrOpen(scene.workspace!!.projectDir("Shopping"), author).use { repo ->
            val ref = repo.exactRef("refs/tags/1.5")
            assertTrue("the tag exists", ref != null)
            // **The tag object's own message**, which is what makes it annotated rather than a bare
            // pointer: a note that did not become part of the tag would be a field that does nothing.
            // **The tag object, walked rather than opened raw.** `ObjectLoader` has no message in this
            // JGit; a `RevTag` is the parsed form, and its message is the annotation.
            RevWalk(repo).use { walk ->
                val tag = walk.parseTag(ref!!.objectId) as RevTag
                assertEquals("and carries what the person wrote", "the first one", tag.fullMessage.trim())
            }
        }
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
        // dangling row. It is **the app's own folder** now (§3.11) — one per phone, outside every
        // account — drawn as a row of its own at the root and reachable from every project's link.
        assertFalse("no project row", scene.viewModel.projectNames.any { it.equals("shared", ignoreCase = true) })
        assertTrue(
            "and the folder is really there, beside the accounts rather than inside one",
            scene.workspace!!.systemDir.isDirectory,
        )
        // **And it is not inside the account any more**, which is what makes it one folder rather
        // than one per identity: the path a person used to find it at is gone.
        assertFalse(
            "not in the account's own folder",
            File(scene.workspace!!.accountDir, Workspace.SYSTEM_PROJECT).exists(),
        )

        scene.viewModel.message = null
        scene.viewModel.deleteProject("shared")
        assertTrue("and it cannot be deleted", scene.viewModel.message != null)
        assertTrue("the folder is still there", scene.workspace!!.systemDir.isDirectory)
    }

    @Test
    fun `the system project keeps its name in both directions`() {
        // **Renaming it away is one half; renaming another project *to* it is the other.** Without
        // the second, an account ends up with two folders called `system`, and the delete guard
        // then refuses both — which is worse than refusing the rename. The rename is now the
        // address's last segment, so both halves are a `setAddress` with an address that says it.
        scene.viewModel.message = null
        scene.viewModel.setAddress(Workspace.SYSTEM_PROJECT, "https://host/owner/settings.git")
        assertTrue("it cannot be renamed away", scene.viewModel.message != null)
        assertTrue("and it still has its own name", scene.workspace!!.systemDir.isDirectory)

        scene.project("groceries")
        scene.viewModel.message = null
        // **The name in another case is still the name.** The guard is case-insensitive because a
        // filesystem that folds case would otherwise offer a second `Shared` that can be deleted.
        scene.viewModel.setAddress("groceries", "https://host/owner/SHARED.git")
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
        val prompt = AgentPrompt.systemFile(ws.systemDir)

        // **Asked first, because asking is what writes it.** The shipped text becomes a file the
        // first time the agent needs it — the same rule the project layer already follows, and the
        // reason a missing file is not an agent with no instructions.
        assertEquals("the shipped text to begin with", AgentPrompt.SYSTEM_DEFAULT, AgentPrompt.system(ws.systemDir))
        assertTrue("the system prompt is written where the project is", prompt.isFile)

        // The file is the person's: editing it changes the prompt, with no second copy winning.
        prompt.writeText("Say only the word: milk.")
        assertEquals("the file is the prompt", "Say only the word: milk.", AgentPrompt.system(ws.systemDir))
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
        // **"Out of the repository" is a question about the index now**, because the untrack stages and
        // the commit happens at a push (2026-11-10). Asking the newest commit would wait for a commit
        // that is not coming — and worse, would keep saying "still tracked" for work the app had
        // already done.
        scene.await("the ignore to take it out of the index", 30_000) {
            org.eclipse.jgit.api.Git.open(ws.repoOf("groceries")).use { git ->
                git.repository.readDirCache().getEntry("secret.txt") == null
            }
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
    fun `ignoring a file the remote already has says its earlier versions stay there`() {
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
            "and that earlier versions stay at the remote: $said",
            said.contains("earlier versions stay at the remote"),
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
        // **The index, not the newest commit**: the untrack is staged and the commit is made at a push
        // (2026-11-10), so asking the history would wait for a commit that is not coming.
        scene.await("the ignore to take it out of the index", 30_000) {
            org.eclipse.jgit.api.Git.open(ws.repoOf("groceries")).use { git ->
                git.repository.readDirCache().getEntry("notes.txt") == null
            }
        }

        // The same button, pressed again, because the state is what decides the direction.
        scene.viewModel.ignoreInGit(file)
        scene.await("the path to be tracked again", 30_000) {
            org.eclipse.jgit.api.Git.open(ws.repoOf("groceries")).use { git ->
                git.repository.readDirCache().getEntry("notes.txt") != null
            }
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
    fun `a project with no address keeps its versions when something records them`() {
        // **A project that cannot send still has versions** — its folder is a repository, and going
        // back to how it was does not need a host. What it does not have is a *push* to make a
        // version at, so a version here is made deliberately, by `commitOutcome` — which is what the
        // scaffold and the app's own writes use. The earlier shape of this test asserted that a save
        // was enough, and that was true only while a save committed.
        scene.localProject("scratch")
        scene.open("scratch")
        val file = scene.file("note.txt", "first")
        val git = ProjectGit(
            scene.workspace!!,
            { null },
            { GitAuthor("Muffy", "muffy@example.com") },
        )
        git.commitOutcome("scratch", "a version decided on")
        scene.await("the version", 30_000) { scene.versionsOf(file).any { it.second == "first" } }

        scene.openEditor(file)
        scene.saveEditor("second")
        git.commitOutcome("scratch", "another version")
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
