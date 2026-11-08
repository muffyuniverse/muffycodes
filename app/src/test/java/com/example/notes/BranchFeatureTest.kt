package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **A branch is a place to work, seen through the doors a person actually uses.**
 *
 * `WorktreesTest` proves the folder git accepts, `AgentTest` proves the agent's tools land in it, and
 * `WorkspaceTest` proves the readings. This file is the one thing none of them can prove: that the
 * *screens* walk into a branch and back out, and that everything a person does inside one — save a
 * file, open the chat, read the history — is about that branch and not about `main`.
 *
 * It exists because the failure mode is invisible from the unit level. Every piece was right and the
 * screen still said `feature` over main's files: the paths agreed, the tree did not.
 */
class BranchFeatureTest : FeatureTest() {

    /** A project with one save on main, and a `feature` branch made from it. */
    private fun projectWithABranch(): String {
        scene.project("Shopping")
        scene.open("Shopping")
        scene.file("milk.txt", "two litres\n")
        // **Synced, so main starts clean.** A file written through the editor is committed by the
        // app, and a commit nobody has pushed is one path "to send" — so without this every assertion
        // about the *branch* being the only dirty tree would be about a project that was already
        // dirty, and would pass for the wrong reason or fail for one.
        scene.sync("Shopping")
        scene.viewModel.openBranches("Shopping")
        scene.viewModel.newBranch("feature")
        scene.await("feature to exist") { "feature" in scene.viewModel.choosingBranches }
        return "Shopping"
    }

    @Test
    fun `walking into a branch shows that branch's files, and saving there leaves main alone`() {
        // **The whole loop, one test**, because each step alone was already true. The person taps a
        // branch, edits, and comes back to main: what they changed must be in one tree and not the
        // other, and the screen must have shown the right one at the time.
        val project = projectWithABranch()

        scene.viewModel.enterBranch("feature")
        scene.await("the branch's files to open") { scene.viewModel.path == listOf(project) }
        assertEquals("the screen is on the branch", "feature", scene.viewModel.activeBranch)
        // **The branch's own tree, and it already holds main's files** — a branch is made from a
        // commit, so this is the same text rather than an empty folder.
        val branchFile = File(scene.workspace!!.treeOf(project), "milk.txt")
        assertTrue("the branch's copy is here", branchFile.isFile)

        scene.openEditor(branchFile)
        scene.saveEditor("three litres\n")

        assertEquals("the branch has the new text", "three litres\n", branchFile.readText())
        assertEquals(
            "and main's copy is untouched",
            "two litres\n",
            File(scene.workspace!!.repoOf(project), "milk.txt").readText(),
        )
    }

    @Test
    fun `a save inside a branch makes a version on that branch, not on main`() {
        // **The half that reported success and recorded nothing.** The save wrote through `fileAt`, so
        // it landed in the branch's tree — and then the commit opened the project's root, whose index
        // held nothing from that edit. The person saw a saved file with no version behind it.
        val project = projectWithABranch()
        val mainBefore = scene.commitCount(project)

        scene.viewModel.enterBranch("feature")
        scene.await("the branch's files to open") { scene.viewModel.path == listOf(project) }
        val branchFile = File(scene.workspace!!.treeOf(project), "milk.txt")
        scene.openEditor(branchFile)
        scene.saveEditor("three litres\n")

        val branchHistory = File(scene.workspace!!.treeOf(project), ".git")
        assertTrue("the branch's tree is its own", branchHistory.isFile)
        // **The version count is read from the branch**, by asking the branch's own tree for its
        // history through the same door the app uses.
        val onFeature = GitService.openExisting(scene.workspace!!.treeOf(project))
        assertNotNull("the branch's repository opens", onFeature)
        onFeature!!.use { repo ->
            val log = GitService.log(repo, null, 10)
            assertTrue(
                "the save is a version on the branch: ${log.map { it.message }}",
                log.any { it.message.contains("Save") || it.message.contains("save") },
            )
        }
        assertEquals("and main's history did not move", mainBefore, scene.commitCount(project))
    }

    @Test
    fun `going back to main shows main's files again`() {
        // **The way out.** A person who leaves a branch must not keep editing its tree while the header
        // says the project's name — that is the same defect from the other direction.
        val project = projectWithABranch()
        scene.viewModel.enterBranch("feature")
        scene.await("the branch's files to open") { scene.viewModel.path == listOf(project) }

        // **The person's way out is `main` on the branch screen**, which is why `main` is a row there
        // and not a special case elsewhere. `backToMain` is private on purpose: the account switches
        // call it for their own reasons, and a screen that could set the branch directly would be a
        // second door into the redirection.
        // **Through the screen, in the order a person does it**: the project's rows, then `main`.
        scene.viewModel.openBranches(project)
        scene.viewModel.enterBranch("main")
        scene.await("main's files to open") { scene.viewModel.activeBranch == "main" }

        assertEquals("back on main", "main", scene.viewModel.activeBranch)
        // **The same path a person uses**, resolved after the branch changed: `Shopping/milk.txt` must
        // now be main's file, not `feature`'s — the whole point of the redirection being one function.
        assertEquals(
            "and the same path now resolves to main's copy",
            File(scene.workspace!!.repoOf(project), "milk.txt").canonicalPath,
            scene.workspace!!.fileAt(listOf(project, "milk.txt")).canonicalPath,
        )
        assertFalse(
            "not the branch's copy",
            scene.workspace!!.fileAt(listOf(project, "milk.txt")).canonicalPath ==
                File(scene.workspace!!.treeOf(project, "feature"), "milk.txt").canonicalPath,
        )
    }

    @Test
    fun `the chat opened inside a branch is that branch's conversation`() {
        // **Two diaries, and the screen followed only the project.** `agent/` lives inside the working
        // tree, so opening a chat from a branch showed main's turns while the agent wrote to the
        // branch's log: the person answered yesterday's question and the agent never saw it.
        val project = projectWithABranch()
        scene.viewModel.enterBranch("feature")
        scene.await("the branch's files to open") { scene.viewModel.path == listOf(project) }

        scene.viewModel.openChatHere(project)

        assertEquals("the chat is the branch's", "feature", scene.viewModel.chatBranch)
        assertEquals(
            "and it is keyed by the worktree",
            AgentSession.keyOf(project, "feature"),
            scene.viewModel.openChatKey,
        )
        // **The two logs are two files**, which is why the key matters: the same project name would
        // have read main's turns out of main's tree.
        assertFalse(
            "the branch's record is not main's",
            File(scene.workspace!!.treeOf(project), AgentLog.DIR).canonicalPath ==
                File(scene.workspace!!.repoOf(project), AgentLog.DIR).canonicalPath,
        )
    }

    @Test
    fun `the strip counts the branch's unsent work, not main's`() {
        // **"up to date" is the claim this app must never get wrong.** The strip is drawn from the
        // project's count, which is main's — so on a branch with a file waiting to be sent it said the
        // project was up to date, and the person had no way to learn otherwise from that screen.
        val project = projectWithABranch()
        scene.viewModel.enterBranch("feature")
        scene.await("the branch's files to open") { scene.viewModel.path == listOf(project) }
        val branchFile = File(scene.workspace!!.treeOf(project), "milk.txt")
        scene.openEditor(branchFile)
        scene.saveEditor("three litres\n")
        scene.await("the branch's count to move") { scene.viewModel.changesHere > 0 }
        // **The premise, asserted**: main really does have nothing waiting, so a passing test cannot
        // be one where both trees happened to be dirty.
        assertEquals("main is clean", 0, scene.viewModel.changesOf(project))
    }

    @Test
    fun `the changes window lists the branch's files`() {
        // The same defect as the strip, one door further in: the window read main's unsent paths, so on
        // a branch it was empty — and the files it would have named do not exist in the folder the
        // person is looking at.
        val project = projectWithABranch()
        scene.viewModel.enterBranch("feature")
        scene.await("the branch's files to open") { scene.viewModel.path == listOf(project) }
        // **Through the editor**, because "unsent" means "committed and not pushed" — a file written
        // straight to disk is untracked, which is a different row and not this window's question.
        val branchFile = File(scene.workspace!!.treeOf(project), "notes.txt")
        branchFile.writeText("waiting\n")
        scene.openEditor(branchFile)
        scene.saveEditor("waiting\n")

        scene.viewModel.openChanges(project)
        scene.await("the window to fill", 20_000) {
            scene.viewModel.changes?.any { it.path == "notes.txt" } == true
        }
    }

    @Test
    fun `a branch's file is named the project's path, so every rule about it still applies`() {
        // **The reading everything else rests on.** A worktree path read literally names `.muffy`, a
        // folder with no project — so project rules, locks and the agent's own scope would all stop
        // applying exactly where a new agent is most likely to be running.
        val project = projectWithABranch()
        scene.viewModel.enterBranch("feature")
        scene.await("the branch's files to open") { scene.viewModel.path == listOf(project) }
        val branchFile = File(scene.workspace!!.treeOf(project), "milk.txt")

        assertEquals(
            "the path a person and the agent both use",
            listOf(project, "milk.txt"),
            scene.workspace!!.relative(branchFile),
        )
        assertEquals("and it belongs to the project", project, scene.workspace!!.projectOf(branchFile))
    }
}
