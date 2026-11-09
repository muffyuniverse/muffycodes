package com.example.notes

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * **A project moving or being copied from one account to another** (§3.9 of the credentials plan).
 *
 * The operator, 2026-11-09: *"make it possible to move and clone projects between accounts, and make
 * ~local undeletable and its projects unconnectable to remote … and users can just transfer its projects
 * to proper remote accounts and there connect them to origin."*
 *
 * The half that is easy to get wrong is not the folder: it is **git's two absolute pointers**. A worktree
 * is joined to its repository by a file naming the other half by absolute path, so a project whose folder
 * travelled and whose pointers did not opens as a repository with **no branch and no exception** — the
 * failure `Worktrees`' header describes. So every test here that carries a branch asks the destination
 * whether the branch is still there, and not merely whether the folder arrived.
 */
class TransferTest {

    private lateinit var root: File
    private lateinit var from: Workspace
    private lateinit var toDir: File
    private lateinit var to: Workspace

    private val author = GitAuthor("Test", "test@example.com")

    @Before
    fun makeTwoAccounts() {
        root = File(System.getProperty("java.io.tmpdir"), "transfer-${System.nanoTime()}")
        from = Workspace.single(File(root, "~local"))
        from.accountDir.mkdirs()
        from.ensure()
        toDir = File(root, "muffyuniverse")
        to = Workspace.single(toDir)
        to.accountDir.mkdirs()
        to.ensure()
    }

    @After
    fun clear() {
        root.deleteRecursively()
    }

    /** A project with one commit on `main` and a `feature` branch with a working tree of its own. */
    private fun projectWithBranch(project: String = "Shopping"): File {
        val dir = from.projectDir(project)
        dir.mkdirs()
        File(dir, "a.txt").writeText("on main\n")
        val repo = GitService.initOrOpen(dir, author)
        repo.use {
            org.eclipse.jgit.api.Git(it).use { git ->
                git.add().addFilepattern(".").call()
                git.commit().setMessage("first").setAuthor(author.ident())
                    .setCommitter(author.ident()).call()
            }
            GitService.createBranch(it, "feature").getOrThrow()
        }
        assertTrue("the branch tree was made", Worktrees(from).make(project, "feature").isSuccess)
        // **A file only `feature` has**, so "the branch arrived" is a fact about content rather than
        // about a folder existing.
        File(Worktrees(from).dirOf(project, "feature"), "b.txt").writeText("on the branch\n")
        return dir
    }

    @Test
    fun `a moved project keeps its history and its branches`() {
        projectWithBranch()

        val done = Transfer.go(from, toDir, "Shopping", copy = false)

        assertTrue("it moved: ${done.exceptionOrNull()?.message}", done.isSuccess)
        assertFalse("the source is gone", File(from.accountDir, "Shopping").exists())
        assertTrue("the destination has it", File(toDir, "Shopping/a.txt").isFile)
        // **The repository is the history, so the history is what proves the folder is a project.**
        val repo = GitService.openExisting(File(toDir, "Shopping"))
        assertNotNull("and it opens as a repository", repo)
        repo!!.use {
            assertEquals("with its commit", "first", it.resolve("HEAD")?.let { id ->
                it.parseCommit(id).fullMessage.trim()
            })
        }
        // **The branch's tree came too, and git agrees it is a worktree of this repository.**
        val tree = Worktrees(to).dirOf("Shopping", "feature")
        assertTrue("the branch folder is there", File(tree, ".git").isFile)
        assertEquals(
            "and holds the branch's file",
            "on the branch\n",
            File(tree, "b.txt").readText(),
        )
        assertTrue(
            "and the pointers name the destination, which is the whole of the repoint",
            File(tree, ".git").readText().contains(toDir.absolutePath),
        )
        assertTrue("the branch is still listed", "feature" in Worktrees(to).branches("Shopping"))
    }

    @Test
    fun `a copied project leaves the source alone and is its own repository`() {
        projectWithBranch()

        val done = Transfer.go(from, toDir, "Shopping", copy = true)

        assertTrue("it copied: ${done.exceptionOrNull()?.message}", done.isSuccess)
        assertTrue("the source is still there", File(from.accountDir, "Shopping/a.txt").isFile)
        assertTrue("and so is the copy", File(toDir, "Shopping/a.txt").isFile)
        // **The source's pointers must not have been rewritten to the copy.** Both were repointed at
        // their own accounts, so the source's branch tree still opens for the source.
        val sourceTree = Worktrees(from).dirOf("Shopping", "feature")
        assertTrue(
            "the source's tree still names the source",
            File(sourceTree, ".git").readText().contains(from.accountDir.absolutePath),
        )
        assertTrue(
            "and the copy's names the copy",
            File(Worktrees(to).dirOf("Shopping", "feature"), ".git").readText()
                .contains(toDir.absolutePath),
        )
    }

    @Test
    fun `a name already taken in the destination is refused before anything moves`() {
        projectWithBranch()
        File(toDir, "Shopping").mkdirs()
        File(toDir, "Shopping/theirs.txt").writeText("not mine\n")

        val problem = Transfer.problem(from, toDir, "Shopping", toIsLocal = false)

        assertNotNull("it refuses", problem)
        assertTrue("naming the project: $problem", problem!!.contains("Shopping"))
        assertTrue("and where it is taken: $problem", problem.contains("muffyuniverse"))
        assertTrue("nothing was moved", File(from.accountDir, "Shopping/a.txt").isFile)
        assertTrue("and nothing was overwritten", File(toDir, "Shopping/theirs.txt").isFile)
    }

    @Test
    fun `a project with an address may not arrive in the local account`() {
        // **§3.9's second ruling, at the third door that can write `origin`.** Local has no host and no
        // key, so a connected project there would name a repository nothing on this phone can reach.
        //
        // The project is made in the **account** and asked to go into Local, because that is the
        // direction the rule is about — a local project has no address to begin with.
        val dir = to.projectDir("Shopping")
        dir.mkdirs()
        File(dir, "a.txt").writeText("on main\n")
        val repo = GitService.initOrOpen(dir, author)
        repo.use {
            org.eclipse.jgit.api.Git(it).use { git ->
                git.add().addFilepattern(".").call()
                git.commit().setMessage("first").setAuthor(author.ident())
                    .setCommitter(author.ident()).call()
            }
            it.config.setString("remote", "origin", "url", "https://host/owner/shopping.git")
            it.config.save()
        }

        val problem = Transfer.problem(to, from.accountDir, "Shopping", toIsLocal = true)

        assertNotNull("it refuses", problem)
        assertTrue("naming the rule: $problem", problem!!.contains("Local"))
        assertTrue("and the way out: $problem", problem.contains("Move it to an account"))
        // **A project with no address may come here**, which is the half that makes the rule about the
        // address rather than about projects: `Bare` is a repository in the account that has never been
        // given one.
        val bare = to.projectDir("Bare")
        bare.mkdirs()
        File(bare, "b.txt").writeText("local work\n")
        GitService.initOrOpen(bare, author).close()
        assertNull(
            "a project with no address is welcome",
            Transfer.problem(to, from.accountDir, "Bare", toIsLocal = true),
        )
    }

    @Test
    fun `a moved project's session rows and template pointers are dropped, not carried`() {
        // **Both are facts about this account.** A session is a claim about an agent standing in a
        // worktree that has gone; a pointer names a path in this account's `shared/requests/`, and the
        // destination has its own folder. A row that travelled would answer for a project nobody
        // started, using a file that is not there.
        projectWithBranch()
        AgentSessions(from.accountDir).put(AgentSession(project = "Shopping", branch = "main"))
        AgentSessions(from.accountDir).put(AgentSession(project = "Groceries", branch = "main"))
        Schemas.setPointer(from.accountDir, "Shopping", "main", "shared/requests/chat/mine.jsonc")
        Schemas.setPointer(from.accountDir, "Groceries", "main", "shared/requests/chat/other.jsonc")

        assertTrue(Transfer.go(from, toDir, "Shopping", copy = false).isSuccess)

        assertNull(
            "the moved project has no session left",
            AgentSessions(from.accountDir).read().firstOrNull { it.project == "Shopping" },
        )
        assertNull(
            "and no pointer",
            Schemas.pointer(from.accountDir, "Shopping", "main"),
        )
        assertNotNull(
            "while the project that stayed keeps both",
            AgentSessions(from.accountDir).read().firstOrNull { it.project == "Groceries" },
        )
        assertEquals(
            "and its pointer",
            "shared/requests/chat/other.jsonc",
            Schemas.pointer(from.accountDir, "Groceries", "main"),
        )
        assertNull(
            "nothing arrived in the destination claiming a session",
            AgentSessions(toDir).read().firstOrNull { it.project == "Shopping" },
        )
    }

    @Test
    fun `a folder that is not a project is refused`() {
        File(from.accountDir, "loose").mkdirs()

        val problem = Transfer.problem(from, toDir, "loose", toIsLocal = false)

        assertNotNull("it refuses", problem)
        assertTrue("saying what it is: $problem", problem!!.contains("not a project"))
        assertFalse("and nothing moved", File(toDir, "loose").exists())
    }
}
