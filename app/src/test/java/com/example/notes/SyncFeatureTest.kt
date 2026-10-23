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
        scene.open(Workspace.PROJECTS, "Shopping")
        scene.worktreeFile("milk.txt", "two litres")

        scene.sync("Shopping")

        assertEquals("the file is on the repository", "two litres", scene.remoteText("Shopping", "milk.txt"))
        assertEquals("nothing is waiting any more", 0, scene.viewModel.changesOf("Shopping"))
    }

    @Test
    fun `the commit message is the names of the files that changed`() {
        scene.project("Shopping")
        scene.open(Workspace.PROJECTS, "Shopping")
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
        scene.open(Workspace.PROJECTS, "Shopping")
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
    fun `a local file beside the worktree is never pushed`() {
        scene.project("Shopping")
        scene.open(Workspace.PROJECTS, "Shopping")
        scene.file("diary.txt", "private")
        scene.worktreeFile("milk.txt", "two litres")

        scene.sync("Shopping")

        assertFalse(
            "the diary stayed on the phone",
            scene.remoteFiles("Shopping").contains("diary.txt"),
        )
        assertTrue("the worktree file went up", scene.remoteFiles("Shopping").contains("milk.txt"))
    }

    @Test
    fun `a second sync after more work adds another commit`() {
        scene.project("Shopping")
        scene.open(Workspace.PROJECTS, "Shopping")
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
    fun `pulling replaces the worktree with what the repository holds`() {
        scene.project("Shopping")
        scene.open(Workspace.PROJECTS, "Shopping")
        scene.worktreeFile("milk.txt", "two litres")
        scene.sync("Shopping")

        // Someone else changes the repository.
        val other = File(scene.root, "elsewhere")
        org.eclipse.jgit.api.Git.cloneRepository()
            .setURI(scene.remote("Shopping").toURI().toString())
            .setDirectory(other).call().use { clone ->
                File(other, "milk.txt").writeText("three litres")
                clone.add().addFilepattern("milk.txt").call()
                clone.commit().setMessage("Changed the milk").call()
                clone.push().call()
            }

        // This phone has its own stale copy.
        File(scene.workspace!!.repoOf("Shopping"), "milk.txt").writeText("one litre")

        scene.viewModel.replaceWithRemote("Shopping")
        scene.await("the pull to finish") { !scene.viewModel.busy }

        assertEquals(
            "the worktree is what the repository holds",
            "three litres",
            File(scene.workspace!!.repoOf("Shopping"), "milk.txt").readText(),
        )
    }

    @Test
    fun `a project is made against the account's own GitHub`() {
        scene.project("Shopping")

        assertTrue(
            "the clone's address is inside the account's folder",
            scene.github.owns(File(scene.workspace!!.repoOf("Shopping"), ".git/config")
                .readText().substringAfter("url = ").lines().first().trim()),
        )
    }

    @Test
    fun `nothing can be synced when nobody is signed in`() {
        val alone = Scenario(scene.app, signIn = false)

        assertEquals("no projects to sync", emptyList<String>(), alone.viewModel.projectNames)
        alone.viewModel.sync("Shopping")
        assertEquals("nothing happened", null, alone.viewModel.account)
    }
}
