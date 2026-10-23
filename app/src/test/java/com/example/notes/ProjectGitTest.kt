package com.example.notes

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * A project's git side (§8): the sync writes its message from the names that
 * changed, the force push really replaces the remote, and the history length of
 * a project is kept the same way the account's is.
 */
class ProjectGitTest {

    private val author = GitAuthor("Test", "test@example.com")
    private lateinit var root: File
    private lateinit var workspace: Workspace
    private lateinit var bare: File
    private var keep = 0

    private fun gitFor(token: String? = null) = ProjectGit(
        workspace = workspace,
        tokenProvider = { token },
        authorProvider = { author },
        keepProvider = { keep },
    )

    @Before
    fun makeSpace() {
        root = File(System.getProperty("java.io.tmpdir"), "projectgit-${System.nanoTime()}")
        workspace = Workspace(File(root, "muffyuniverse"))
        workspace.accountDir.mkdirs()
        workspace.projectsDir.mkdirs()

        // A bare repository is what a push target really is.
        bare = File(root, "remote.git")
        org.eclipse.jgit.api.Git.init()
            .setDirectory(bare)
            .setBare(true)
            .setInitialBranch("main")
            .call()
            .close()
    }

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun syncingCommitsAndPushesWhatChanged() {
        workspace.createProjectFolders("Shopping").getOrThrow()
        val git = gitFor()
        val repo = git.open("Shopping") ?: run {
            git.init("Shopping").getOrThrow()
            git.open("Shopping")!!
        }
        repo.use { GitService.setRemoteUrl(it, bare.absolutePath) }
        File(workspace.repoOf("Shopping"), "Milk.txt").writeText("milk")

        val result = git.sync("Shopping")

        assertTrue(result.message, result.success)
        org.eclipse.jgit.storage.file.FileRepositoryBuilder().setGitDir(bare).build().use { remote ->
            assertNotNull("the commit reached the remote", remote.resolve("refs/heads/main"))
        }
        // The worktree is clean afterwards: everything went in one commit.
        assertEquals(0, git.changes("Shopping").total)
    }

    @Test
    fun theCommitMessageNamesTheFiles() {
        workspace.createProjectFolders("Shopping").getOrThrow()
        val git = gitFor()
        git.init("Shopping").getOrThrow()
        git.open("Shopping")?.use { GitService.setRemoteUrl(it, bare.absolutePath) }
        File(workspace.repoOf("Shopping"), "Milk.txt").writeText("milk")
        File(workspace.repoOf("Shopping"), "Bread.txt").writeText("bread")

        assertTrue(git.sync("Shopping").success)

        val message = git.log("Shopping").first().message
        assertTrue("the message must name the files, got: $message", message.contains("Milk.txt"))
        assertTrue("the message must name the files, got: $message", message.contains("Bread.txt"))
    }

    @Test
    fun aCleanProjectSaysNothingChanged() {
        workspace.createProjectFolders("Shopping").getOrThrow()
        val git = gitFor()
        git.init("Shopping").getOrThrow()
        git.open("Shopping")?.use { GitService.setRemoteUrl(it, bare.absolutePath) }

        // The first commit is the empty one the repository starts with.
        assertEquals(0, git.changes("Shopping").total)
    }

    @Test
    fun aProjectsHistoryIsShortenedAfterASync() {
        workspace.createProjectFolders("Shopping").getOrThrow()
        val git = gitFor()
        git.init("Shopping").getOrThrow()
        git.open("Shopping")?.use { GitService.setRemoteUrl(it, bare.absolutePath) }
        keep = 3

        repeat(7) { i ->
            File(workspace.repoOf("Shopping"), "count.txt").writeText("value $i")
            assertTrue(git.sync("Shopping").success)
        }

        assertEquals("the project keeps the length it was given", 3, git.log("Shopping").size)
        assertEquals("value 6", File(workspace.repoOf("Shopping"), "count.txt").readText())
    }

    @Test
    fun aSyncWithNoRepositorySaysSo() {
        workspace.createProjectFolders("Shopping").getOrThrow()
        val result = gitFor().sync("Shopping")
        assertFalse(result.success)
        assertTrue(result.message.contains("no repository"))
    }

    @Test
    fun aLocalFileBesideTheWorktreeIsNotSynced() {
        workspace.createProjectFolders("Shopping").getOrThrow()
        val git = gitFor()
        git.init("Shopping").getOrThrow()
        git.open("Shopping")?.use { GitService.setRemoteUrl(it, bare.absolutePath) }

        File(File(workspace.projectsDir, "Shopping"), "ideas.txt").writeText("mine")
        assertTrue(git.sync("Shopping").success)

        // Local files beside the worktree are the account's history, not git's.
        assertEquals(0, git.changes("Shopping").total)
        assertTrue(git.log("Shopping", "ideas.txt").isEmpty())
    }
}
