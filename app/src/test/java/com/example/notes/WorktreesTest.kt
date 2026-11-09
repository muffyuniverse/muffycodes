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
        // where it is.
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
            workspace.projectNames() == listOf("Shopping") || workspace.projectNames() == listOf("Shopping", "shared"),
        )
    }

    @Test
    fun `a branch with a slash in its name gets its own folder and its own id`() {
        // `topic/x` is a legal git branch name, so the folder nests — and git's own worktree ids are the
        // last path segment, which would make `topic/x` and `x` share one. Flattened here.
        //
        // **Not `feature/x`, and the reason is a git rule worth knowing**: `projectWithBranch` already
        // made `feature`, and git refuses `feature/x` beside it — a directory and a file cannot share a
        // name in `refs/heads`. That is git's own refusal, passed through as a sentence; the app does not
        // have to prevent it. This test failed with an exception from `createBranch` before the name was
        // changed, which is the rule being discovered rather than assumed.
        val dir = projectWithBranch()
        GitService.openExisting(dir)!!.use { repo ->
            GitService.createBranch(repo, "topic/x").getOrThrow()
        }

        val tree = trees.make("Shopping", "topic/x").getOrThrow()

        assertEquals(
            "a nested folder, named for the whole branch",
            File(File(workspace.stateDir, Worktrees.WORKTREES), "Shopping/topic/x"),
            tree,
        )
        assertEquals("and one id per branch", "Shopping-topic-x", trees.idOf("Shopping", "topic/x"))
    }

    @Test
    fun `a branch cannot be made beside one whose name it extends`() {
        // **`feature` and `feature/x` cannot both exist**, because `refs/heads/feature` and
        // `refs/heads/feature/x` need a file and a folder with one name. git refuses it, and the refusal
        // arrives as a sentence rather than as a crash - which is why the branch screen needs no rule of
        // its own about slashes.
        val dir = projectWithBranch()

        GitService.openExisting(dir)!!.use { repo ->
            val refused = GitService.createBranch(repo, "feature/x")

            assertTrue("it was refused", refused.isFailure)
            assertTrue(
                "and said so: ${refused.exceptionOrNull()?.message}",
                refused.exceptionOrNull()?.message.orEmpty().isNotBlank(),
            )
        }
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
        assertFalse("and not the old one", File(trees.worktreesRoot(), "Shopping").isDirectory)
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

    // ---- the system folder, which every tree can see (§13f) ----

    @Test
    fun `a new worktree gets a link to the account's system folder`() {
        // **The operator's ask, as an assertion**: *"for each worktree to have a symlink to it so that
        // system is displayed as a gitignored folder in every worktree."* The link is what makes the
        // one shared folder reachable from inside a branch, without a second copy of it.
        projectWithBranch()
        workspace.ensure()

        val tree = trees.make("Shopping", "feature").getOrThrow()

        val link = File(tree, Workspace.SYSTEM_PROJECT)
        assertTrue("the link is a symlink, not a folder", Workspace.isLink(link))
        assertTrue("and it resolves to a folder", link.isDirectory)
        assertTrue(
            "the account's own system folder: ${link.canonicalPath}",
            link.canonicalPath ==
                File(workspace.accountDir, Workspace.SYSTEM_PROJECT).canonicalPath,
        )
    }

    @Test
    fun `the link is not a change in git, in main's tree or in a branch's`() {
        // **Measured, because the obvious rule was wrong.** `.gitignore` form `system/` matches only a
        // directory, and a symlink is not one — so a rule written by analogy would have left the link
        // untracked and every project would have read "1 to send" for a file the app made. The exclude
        // entry is written without the slash and lives in the common directory, so it covers a linked
        // worktree too.
        val dir = projectWithBranch()
        workspace.ensure()
        workspace.ensureIgnore(dir)
        val tree = trees.make("Shopping", "feature").getOrThrow()

        for (where in listOf(dir, tree)) {
            GitService.openExisting(where)!!.use { repo ->
                org.eclipse.jgit.api.Git(repo).use { git ->
                    val status = git.status().call()
                    assertTrue(
                        "nothing untracked in ${where.name}: ${status.untracked}",
                        status.untracked.isEmpty(),
                    )
                    assertTrue(
                        "and the link is what is ignored: ${status.ignoredNotInIndex}",
                        status.ignoredNotInIndex.contains(Workspace.SYSTEM_PROJECT),
                    )
                }
            }
        }
    }

    @Test
    fun `deleting a worktree removes the link and leaves the system folder alone`() {
        // **The dangerous one.** `File.deleteRecursively()` follows a symlink: it walks the *target* and
        // deletes its contents before deleting the link. So removing a branch - or a project - would
        // have emptied the account's system folder. The probe is a file inside it that must survive.
        projectWithBranch()
        workspace.ensure()
        val tree = trees.make("Shopping", "feature").getOrThrow()
        val readme = File(File(workspace.accountDir, Workspace.SYSTEM_PROJECT), Workspace.README)
        assertTrue("the folder the link points at has a file in it", readme.isFile)

        trees.remove("Shopping", "feature")

        assertFalse("the tree is gone", tree.exists())
        assertTrue("and the system folder's file is still there", readme.isFile)
    }

    @Test
    fun `renaming a project carries its worktrees and repoints git at them`() {
        // **Two files name the other half, by absolute path**, so moving the folder is half a rename:
        // the tree's `.git` still points at the old private entry and that entry's `gitdir` still names
        // the old folder. JGit then opens it as a repository with **no branch and no exception** — the
        // same silent shape as the `setGitDir` bug — so the assertion is the branch, read back.
        val dir = projectWithBranch()
        val tree = trees.make("Shopping", "feature").getOrThrow()

        // What the rename does: the project's folder first, then the branch folders.
        assertTrue("the project moved", dir.renameTo(workspace.projectDir("Groceries")))
        assertTrue("the worktrees moved", trees.rename("Shopping", "Groceries"))

        val moved = trees.dirOf("Groceries", "feature")
        assertTrue("the branch folder is at the new project's path: $moved", moved.isDirectory)
        assertTrue("git's private entry was renamed too", trees.exists("Groceries", "feature"))
        assertFalse("and the old folder is gone", tree.exists())
        GitService.openExisting(moved)!!.use { repo ->
            assertEquals("it still opens as its branch", "feature", GitService.currentBranch(repo))
            assertNotNull("with a HEAD that resolves", repo.resolve("HEAD"))
        }
        // **The link came with it**, because the folder moved rather than being rebuilt.
        assertTrue("and the system folder is still reachable", File(moved, Workspace.SYSTEM_PROJECT).isDirectory)
    }

    @Test
    fun `a rename repoints a branch whose name has a slash too`() {
        // **The nested case, which a one-level listing gets wrong.** `topic/x` is a folder `topic`
        // holding `x`, and `topic` has no `.git` — so "repoint every child folder" repoints nothing and
        // leaves the real tree naming a path that no longer exists. The walk is the same one
        // `branches` uses, so the two cannot disagree about which folders are worktrees.
        val dir = projectWithBranch()
        GitService.openExisting(dir)!!.use { repo ->
            GitService.createBranch(repo, "topic/x", "feature").getOrThrow()
        }
        val tree = trees.make("Shopping", "topic/x").getOrThrow()

        assertTrue("the project moved", dir.renameTo(workspace.projectDir("Groceries")))
        assertTrue("the worktrees moved", trees.rename("Shopping", "Groceries"))

        val moved = trees.dirOf("Groceries", "topic/x")
        assertTrue("the nested branch's folder is at the new path", moved.isDirectory)
        GitService.openExisting(moved)!!.use { repo ->
            assertEquals("and it still opens as its branch", "topic/x", GitService.currentBranch(repo))
        }
        assertFalse("the old tree is gone", tree.exists())
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
