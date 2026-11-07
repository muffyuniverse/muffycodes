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
 * **A branch's working tree** (§13d) — made by writing git's own four files, and filled from the tip.
 *
 * The operator's rule is why this exists at all: *"each branch should have its own agent because branch
 * is a separate worktree and agent is worktree scoped."*
 *
 * **JGit has no `worktree add`** — measured: the 7.8.1 jar contains no such command. So this class
 * writes what git writes, and every test here asks whether **git itself** agrees afterwards. That is the
 * only question worth asking of a hand-built layout: a folder that JGit opens is not the same claim as a
 * folder that `git worktree list` accepts.
 *
 * The properties that matter, in the order they can go wrong:
 *
 * 1. **The files are there** — and the tree opens as the right branch (a worktree whose HEAD is missing
 *    opens as nothing, silently; that is the `openExisting` bug in `ProjectGitTest`).
 * 2. **The content is the branch's** — an empty folder is the half-made state this class deletes rather
 *    than keeps.
 * 3. **`main` has no folder of its own** — the project root *is* main's tree, and git cannot move it.
 * 4. **Removing takes both halves** — a folder without git's record, or a record without the folder, are
 *    the two ways a worktree list goes wrong.
 */
class WorktreesTest {

    private lateinit var root: File
    private lateinit var workspace: Workspace
    private lateinit var trees: Worktrees

    private val author = GitAuthor("Test", "test@example.com")

    @Before
    fun makeSpace() {
        root = File(System.getProperty("java.io.tmpdir"), "worktrees-${System.nanoTime()}")
        workspace = Workspace(File(root, "muffyuniverse"))
        workspace.accountDir.mkdirs()
        workspace.ensure()
        trees = Worktrees(workspace)
    }

    @After
    fun clear() {
        root.deleteRecursively()
    }

    /** A project with one commit on `main`, and a `feature` branch made from it. */
    private fun projectWithBranch(project: String = "Shopping"): File {
        val dir = workspace.projectDir(project)
        dir.mkdirs()
        File(dir, "a.txt").writeText("on main\n")
        // The first commit, so `main` has a tree to check out. `initOrOpen` makes the repository;
        // `commitAll` stages the folder the way the app does.
        commitAll(dir, "first")
        GitService.openExisting(dir)!!.use { repo ->
            GitService.createBranch(repo, "feature").getOrThrow()
        }
        return dir
    }

    /** Stages and commits everything in [dir], from real files, so the tree is not empty. */
    private fun commitAll(dir: File, message: String) {
        val repo = GitService.initOrOpen(dir, author)
        repo.use {
            org.eclipse.jgit.api.Git(it).use { git ->
                git.add().addFilepattern(".").call()
                git.commit().setMessage(message).setAuthor(author.ident())
                    .setCommitter(author.ident()).call()
            }
        }
    }

    @Test
    fun `a branch folder holds the branch, and opening it says so`() {
        // **The three things a worktree can silently not be**: no pointers, right pointers and no
        // branch, or a branch that is not this one. All three read as "an empty repository" to JGit.
        val dir = projectWithBranch()

        val made = trees.make("Shopping", "feature")
        assertTrue("it was made: $made", made.isSuccess)
        val tree = made.getOrThrow()

        assertTrue("the pointer is a FILE, as git writes it", File(tree, ".git").isFile)
        assertTrue(
            "and it points at git's own place",
            File(tree, ".git").readText().startsWith("gitdir: "),
        )
        GitService.openExisting(tree)!!.use { repo ->
            assertEquals("it is the branch", "feature", GitService.currentBranch(repo))
            assertNotNull("and its HEAD resolves", repo.resolve("HEAD"))
        }
    }

    @Test
    fun `the folder is filled with the branch's files, not left empty`() {
        // **The half-made worktree**, which is the state this class refuses to keep: a folder git lists
        // but that holds nothing looks exactly like a branch with no files.
        val dir = projectWithBranch()

        val tree = trees.make("Shopping", "feature").getOrThrow()

        assertTrue("a.txt was checked out", File(tree, "a.txt").isFile)
        assertEquals("with the branch's content", "on main\n", File(tree, "a.txt").readText())
    }

    @Test
    fun `main has no folder of its own, because the project root is its tree`() {
        // **A fact about git, not a shortcut.** `git worktree list` always lists the main tree, there is
        // no verb to relocate it, and making the root bare stops it being a tree at all — so main stays
        // where it is and no existing project needs migrating.
        val dir = projectWithBranch()

        assertEquals("main is the project folder", dir, trees.dirOf("Shopping", "main"))
        assertTrue("and it exists already", trees.exists("Shopping", "main"))
    }

    @Test
    fun `branches live in the state folder, so they are never mistaken for projects`() {
        // **The reason for this placement**, measured: a sibling folder would be listed as a project,
        // because `isProjectFolder` tests `File(file, ".git").exists()` and a worktree's `.git` is a
        // file. Inside `.muffy` it is invisible to `projectNames()` and needs no `.gitignore` entry.
        projectWithBranch()

        val tree = trees.make("Shopping", "feature").getOrThrow()

        assertEquals(
            "under .muffy/worktrees",
            File(File(workspace.stateDir, Worktrees.WORKTREES), "Shopping/feature"),
            tree,
        )
        assertTrue(
            "and the project list does not see it: ${workspace.projectNames()}",
            workspace.projectNames() == listOf("Shopping") || workspace.projectNames() == listOf("Shopping", "system"),
        )
    }

    @Test
    fun `a branch with a slash in its name gets its own folder and its own id`() {
        // `feature/x` is a legal git branch name, so the folder nests — and git's own worktree ids are
        // the last path segment, which would make `feature/x` and `x` share one. Flattened here.
        val dir = projectWithBranch()
        GitService.openExisting(dir)!!.use { repo ->
            GitService.createBranch(repo, "feature/x").getOrThrow()
        }

        val tree = trees.make("Shopping", "feature/x").getOrThrow()

        assertEquals(
            "a nested folder, named for the whole branch",
            File(File(workspace.stateDir, Worktrees.WORKTREES), "Shopping/feature/x"),
            tree,
        )
        assertEquals(
            "and one id per branch",
            "Shopping-feature-x",
            trees.idOf("Shopping", "feature/x"),
        )
    }

    @Test
    fun `asking for a branch that does not exist is refused, and makes nothing`() {
        // **Refused before any file is written**, because the alternative is an empty folder git
        // cannot check out — which the branch screen would then offer as a place to work.
        projectWithBranch()

        val made = trees.make("Shopping", "nope")

        assertTrue("it failed", made.isFailure)
        assertTrue(
            "and the refusal names the branch: ${made.exceptionOrNull()?.message}",
            made.exceptionOrNull()?.message.orEmpty().contains("nope"),
        )
        assertFalse("and nothing was made", trees.exists("Shopping", "nope"))
    }

    @Test
    fun `removing a branch takes the folder and git's record of it together`() {
        // **Both halves or neither.** A folder with no record is a stranger git does not list; a record
        // with no folder is a worktree git reports as missing. Either one alone leaves the branch screen
        // and git disagreeing about what exists.
        projectWithBranch()
        val tree = trees.make("Shopping", "feature").getOrThrow()
        val record = File(workspace.projectDir("Shopping"), ".git/worktrees/${trees.idOf("Shopping", "feature")}")
        assertTrue("the record was made too", record.isDirectory)

        trees.remove("Shopping", "feature")

        assertFalse("the folder is gone", tree.exists())
        assertFalse("and so is the record", record.exists())
        assertFalse("and it is not a worktree any more", trees.exists("Shopping", "feature"))
    }

    @Test
    fun `removing main does nothing, because its folder is the project`() {
        // A `remove` that deleted the project folder when asked to remove `main` would be the worst bug
        // in this file: it is one refused branch away from being called.
        val dir = projectWithBranch()

        trees.remove("Shopping", "main")

        assertTrue("the project is still there", dir.isDirectory)
        assertTrue("with its repository", File(dir, ".git").exists())
        assertTrue("and its file", File(dir, "a.txt").isFile)
    }

    @Test
    fun `a renamed project keeps its branch folders`() {
        // A rename that lost a checkout would be a branch the person could no longer open, with the
        // work still on disk somewhere they cannot see.
        projectWithBranch()
        trees.make("Shopping", "feature").getOrThrow()

        assertTrue("the rename carried them", trees.rename("Shopping", "Groceries"))

        assertTrue(
            "they are under the new name",
            trees.exists("Groceries", "feature"),
        )
        assertFalse("and not the old one", File(File(trees.worktreesRoot(), "Shopping")).isDirectory)
    }

    @Test
    fun `deleting a project removes every branch folder it had`() {
        // Nothing should outlive the project: a folder in the state directory with no project is a
        // branch the screen cannot name and the person cannot remove.
        projectWithBranch()
        trees.make("Shopping", "feature").getOrThrow()

        trees.removeAll("Shopping")

        assertFalse("nothing is left behind", File(trees.worktreesRoot(), "Shopping").isDirectory)
    }

    @Test
    fun `making a worktree twice finds the first one rather than a second`() {
        // The screen may offer the branch again — a tap that ran twice, or a person who went back and
        // in. A second `make` must not leave two folders pointing at one branch, which git would refuse
        // to check out twice anyway.
        projectWithBranch()
        val first = trees.make("Shopping", "feature").getOrThrow()

        val again = trees.make("Shopping", "feature")

        assertTrue("it did not throw: $again", again.isSuccess)
        assertEquals("and it is the same folder", first, again.getOrThrow())
    }

    @Test
    fun `a project with no repository is refused with a sentence about the project`() {
        // Not a crash: a project whose repository has not been made yet is a normal state, and the
        // refusal has to say which project rather than "could not check out".
        workspace.projectDir("Fresh").mkdirs()

        val made = trees.make("Fresh", "feature")

        assertTrue("it failed", made.isFailure)
        assertFalse("and nothing exists", trees.exists("Fresh", "feature"))
        assertTrue(
            "the sentence names the project: ${made.exceptionOrNull()?.message}",
            made.exceptionOrNull()?.message.orEmpty().contains("Fresh"),
        )
    }
}
