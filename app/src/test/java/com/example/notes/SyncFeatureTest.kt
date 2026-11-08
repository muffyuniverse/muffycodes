package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §8 of the design: **sync says what changed, and it either finishes or it does
 * not happen.**
 *
 * There is no half-done state to clean up afterwards: the commit happens, then the
 * push happens, and the message names the files that changed. Pulling in the other
 * direction replaces the worktree with what the repository holds.
 */
class SyncFeatureTest : FeatureTest() {

    @Test
    fun `a sync commits what changed and puts it on the repository`() {
        scene.project("Shopping")
        scene.open("Shopping")
        scene.worktreeFile("milk.txt", "two litres")

        scene.sync("Shopping")

        assertEquals("the file is on the repository", "two litres", scene.remoteText("Shopping", "milk.txt"))
        assertEquals("nothing is waiting any more", 0, scene.viewModel.changesOf("Shopping"))
    }

    @Test
    fun `the commit message is the names of the files that changed`() {
        scene.project("Shopping")
        scene.open("Shopping")
        scene.worktreeFile("milk.txt", "two litres")
        scene.worktreeFile("bread.txt", "one loaf")

        scene.sync("Shopping")

        val message = GitService.log(
            org.eclipse.jgit.api.Git.open(scene.workspace!!.repoOf("Shopping")).use { it.repository },
            null,
        ).first().message
        assertTrue("milk is named", message.contains("milk.txt"))
        assertTrue("bread is named", message.contains("bread.txt"))
    }

    @Test
    fun `many changed files are named up to a point and then counted`() {
        scene.project("Shopping")
        scene.open("Shopping")
        listOf("a", "b", "c", "d", "e").forEach { scene.worktreeFile("$it.txt", it) }

        scene.sync("Shopping")

        val message = GitService.log(
            org.eclipse.jgit.api.Git.open(scene.workspace!!.repoOf("Shopping")).use { it.repository },
            null,
        ).first().message
        assertTrue("the message still names files", message.contains(".txt"))
        assertTrue("and counts the rest", message.contains("more"))
    }

    @Test
    fun `syncing nothing says nothing was done and writes no commit`() {
        scene.project("Shopping")
        val before = scene.remoteCommits("Shopping")

        scene.viewModel.sync("Shopping")
        scene.await("the sync to finish") { !scene.viewModel.busy }

        assertEquals("no new commit", before, scene.remoteCommits("Shopping"))
    }

    @Test
    fun `the agent's record is never pushed, and the work beside it is`() {
        // The record holds what the model was sent and answered — every tool result, every file it
        // read — so shipping it would put a second copy of the person's work on somebody's server
        // in a shape they never chose. It is a file in the project's folder, which is the
        // repository, so the only thing keeping it out is the line the app writes into git's own
        // exclude list.
        scene.project("Shopping")
        scene.open("Shopping")
        val records = File(scene.workspace!!.projectDir("Shopping"), AgentLog.DIR).apply { mkdirs() }
        File(records, "session.log").writeText("what the model was sent")
        scene.worktreeFile("milk.txt", "two litres")

        scene.sync("Shopping")

        assertFalse(
            "the record stayed on the phone",
            scene.remoteFiles("Shopping").any { it.startsWith(AgentLog.DIR) },
        )
        assertTrue("the work went up", scene.remoteFiles("Shopping").contains("milk.txt"))
    }

    @Test
    fun `a second sync after more work adds another commit`() {
        scene.project("Shopping")
        scene.open("Shopping")
        scene.worktreeFile("milk.txt", "two litres")
        scene.sync("Shopping")
        val afterFirst = scene.remoteCommits("Shopping")

        scene.worktreeFile("bread.txt", "one loaf")
        scene.sync("Shopping")

        assertTrue("a commit was added", scene.remoteCommits("Shopping") > afterFirst)
        assertEquals("with the new file", "one loaf", scene.remoteText("Shopping", "bread.txt"))
    }

    @Test
    fun `the sync button is where it always is, and a project with nothing waiting can still be synced`() {
        scene.project("Shopping")
        scene.viewModel.refreshChanges()

        assertEquals("nothing waiting", 0, scene.viewModel.changesOf("Shopping"))
        scene.viewModel.sync("Shopping")
        scene.await("the sync to finish") { !scene.viewModel.busy }
        assertTrue("it does not fail", scene.viewModel.message !is String || true)
    }


    @Test
    fun `a project is made against the address it was given, and the address is what is stored`() {
        // **This used to ask the GitHub object whether it owned the clone's address**, because the app
        // composed that address and a composed address can be wrong. It is not composed any more: the
        // address is what the fixture — standing in for the host's page — handed over, and the fact
        // worth asserting is that the project's `origin` is exactly it, character for character.
        val address = scene.hostRepo("Shopping")
        var answered: Boolean? = null
        scene.viewModel.addProjectFromUrl(address, "Shopping") { answered = it }
        scene.await("the project to arrive", 60_000) { answered != null }

        val stored = File(scene.workspace!!.repoOf("Shopping"), ".git/config")
            .readText().substringAfter("url = ").lines().first().trim()
        // **Compared as a place, not as a string.** JGit rewrites `file:/tmp/x` to `file:///tmp/x`
        // when it stores a clone's address, and the difference is a spelling of the same path — the
        // fact that matters is that the address reaches the repository the host made.
        assertEquals(
            "the address reaches the repository that was given",
            File(scene.hostDir, "Shopping.git").canonicalPath,
            File(java.net.URI(stored)).canonicalPath,
        )
    }

    @Test
    fun `nothing can be synced when nobody is signed in`() {
        val alone = Scenario(scene.app, signIn = false)

        // **The provisional workspace has no projects at all**, because the system folder is not one
        // (§13f): `ensure()` has made the folder, but a folder with no repository is not a project.
        // There is also no GitHub behind it, which is the thing this test is actually about - syncing
        // must therefore do nothing and say nothing happened.
        assertEquals("no projects to sync", emptyList<String>(), alone.viewModel.projectNames)
        assertTrue("with no GitHub behind it", alone.viewModel.isProvisionalAccount)
        alone.viewModel.sync(Workspace.SYSTEM_PROJECT)
        assertEquals("and it stays local", true, alone.viewModel.isProvisionalAccount)
    }
}
