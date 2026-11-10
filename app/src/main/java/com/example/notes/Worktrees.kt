package com.example.notes

import org.eclipse.jgit.dircache.DirCacheCheckout
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk
import java.io.File

/**
 * **A branch's working tree** (§13d) — one folder per branch, made and opened here.
 *
 * The operator's rule, 2026-11-07: *"each branch should have its own agent because branch is a separate
 * worktree and agent is worktree scoped — he doesn't have and doesn't need checkout."* This is the
 * folder half of that; `AgentSession` is the other half.
 *
 * **Why they are made by hand rather than by a library call.** JGit has no `worktree add` — measured:
 * the 7.8.1 jar contains no such command. So the four files are written exactly as `git` writes them,
 * and a checkout fills the folder. The recipe was proven against **real git** before it was written
 * here: `git worktree list` accepts the result, `git status` reports it clean, and a commit made in it
 * moves only that branch.
 *
 * **Where they live is decided, not incidental.** Under `<account>/.muffy/worktrees/<project>/<branch>`
 * — inside the state folder, which `Workspace` already treats as the app's own and `projectNames()`
 * already skips. A sibling folder (`<account>/Shopping-feature/`) would be listed as a **project**,
 * because `isProjectFolder` tests `File(file, ".git").exists()` and a worktree's `.git` is a file; a
 * folder inside the project (`Shopping/branches/feature/`) would need a `.gitignore` entry and would be
 * walked by search. Neither problem exists here.
 *
 * `main` **has no folder of its own**: a project's root *is* main's working tree, and git cannot move
 * it (`git worktree list` always lists it, and making the root bare stops it being a tree at all). So
 * [dirOf] answers the project folder for `main` and a state folder for everything else.
 */
class Worktrees(private val workspace: Workspace) {

    /** The branch a project's own folder holds. */
    private val mainBranch = AgentSession.MAIN

    /**
     * The working tree of [branch] in [project].
     *
     * **Answered whether or not it exists** — it is a path, not a fact. [make] is what makes it, and
     * [exists] is what asks.
     */
    fun dirOf(project: String, branch: String): File =
        if (branch == mainBranch) {
            workspace.projectDir(project)
        } else {
            // **`/` inside a branch name is allowed by git** (`feature/x`), so it becomes a nested
            // folder. That is harmless here and it cannot collide with a sibling branch: git itself
            // refuses `feature` and `feature/x` in one repository.
            File(File(worktreesRoot(), project), branch)
        }

    /** Where a project's branch folders live. */
    fun worktreesRoot(): File = File(workspace.stateDir, WORKTREES)

    /** The private folder git keeps for one worktree, beside the repository's other bookkeeping. */
    private fun commonEntry(project: String, branch: String): File =
        File(File(workspace.projectDir(project), ".git"), "worktrees/" + idOf(project, branch))

    /**
     * git's own id for a worktree folder, which is the **folder name** it would have picked.
     *
     * git uses the last path segment; for `feature/x` that would be `x`, which is ambiguous with a
     * branch called `x`. Flattening the slashes keeps one id per branch and stays readable.
     */
    fun idOf(project: String, branch: String): String =
        if (branch == mainBranch) "main" else "$project-" + branch.replace('/', '-')

    /** True when [branch] of [project] already has a working tree of its own. */
    fun exists(project: String, branch: String): Boolean =
        if (branch == mainBranch) {
            File(workspace.projectDir(project), ".git").exists()
        } else {
            File(dirOf(project, branch), ".git").isFile
        }

    /**
     * Makes the working tree of [branch] and fills it from the branch's tip.
     *
     * **Four files, then a checkout, and the order is git's.** `commondir` is the one that makes the
     * shared objects and refs reachable; without it JGit builds a repository with no branches at all.
     * `HEAD` is what makes the folder *be* that branch — and on 7.8.1 `repo.branch` reads it, which is
     * what makes a push from in here go to the right place.
     *
     * **The result says what happened**, because this is the one act here that can leave a folder
     * half-made: a failure after the pointers are written would leave a tree git lists but that holds
     * no files, so the folder is removed on the way out rather than left to be adopted.
     */
    fun make(project: String, branch: String): Result<File> {
        if (branch == mainBranch) {
            // Not an error: main's tree already exists, and it is the project's own folder.
            return Result.success(workspace.projectDir(project))
        }
        val fixture = GitService.openExisting(workspace.projectDir(project))
            ?: return Result.failure(
                IllegalStateException("$project has no repository on this phone, so $branch cannot be checked out."),
            )
        fixture.use { repo ->
            val tip: ObjectId? = try {
                repo.resolve("refs/heads/$branch")
            } catch (e: Throwable) {
                null
            }
            // **A branch with no commits is a real branch, and this is the case a clear slate makes.**
            // `createBranch(name, from = null)` writes an unborn ref, so there is nothing to resolve —
            // and refusing here would make "from nothing" a dialog that always fails. The folder is
            // made and left empty, which is exactly what a clear slate is; the app's own first commit
            // fills it (see `Workspace.ensureFreshIgnore`).
            val dir = dirOf(project, branch)
            val private = commonEntry(project, branch)
            try {
                dir.mkdirs()
                private.mkdirs()
                // git's four files, byte for byte what `git worktree add` writes.
                Beside.write(File(dir, ".git"), "gitdir: " + private.absolutePath + "\n")
                Beside.write(File(private, "commondir"), "../..\n")
                Beside.write(File(private, "gitdir"), File(dir, ".git").absolutePath + "\n")
                Beside.write(File(private, "HEAD"), "ref: refs/heads/$branch\n")
                // **Nothing to check out, and that is not a failure.** An unborn branch has no tree,
                // so the folder is written and left empty; the first commit is the app's.
                if (tip != null) populate(dir, tip)
                // **The system folder comes with the checkout** (§13f). Done here rather than waiting
                // for the next refresh, because a person who opens a branch is about to look at it.
                workspace.linkSystem(dir)
            } catch (t: Throwable) {
                // **Half a worktree is worse than none**: git would list it, the branch screen would
                // offer it, and it would open empty. Nothing is kept on a failure.
                dir.deleteRecursively()
                private.deleteRecursively()
                return Result.failure(
                    IllegalStateException("Could not check $branch out: " + Secrets.describe(t)),
                )
            }
        }
        return Result.success(dirOf(project, branch))
    }

    /**
     * Writes [tip]'s tree into the new folder, **through the worktree's own index**.
     *
     * `lockDirCache` rather than `DirCache.newInCore()`, and that is not a preference: an in-core cache
     * cannot be written, so `DirCacheCheckout` writes the files and then throws `DirCache is not locked`
     * — leaving a folder full of files with no index, which the next attempt then reports as a conflict.
     * Measured, both ways.
     */
    private fun populate(dir: File, tip: ObjectId) {
        // **`openExisting`, not `initOrOpen`.** The pointers are already written, so this opens the
        // worktree; `initOrOpen` would be a second door to the same place with an author argument
        // that means nothing here, and it is the function that once initialised a worktree's folder
        // as a fresh repository.
        val repo: Repository = GitService.openExisting(dir)
            ?: throw IllegalStateException("the worktree pointers did not open as a repository")
        repo.use {
            val commit = RevWalk(it).use { walk -> walk.parseCommit(tip) }
            val index = it.lockDirCache()
            try {
                DirCacheCheckout(it, null, index, commit.tree).apply {
                    setFailOnConflict(true)
                }.checkout()
            } finally {
                // `checkout()` commits the cache, which releases the lock itself. `unlock()` is the
                // no-op that keeps a failed checkout from leaving an `index.lock` behind — measured:
                // it is safe to call twice.
                index.unlock()
            }
        }
    }

    /**
     * Removes a branch's working tree, **and git's record of it.**
     *
     * Both halves or neither: a folder without the private entry is a stranger git does not list, and
     * an entry without the folder is a worktree git reports as missing. [deleteBranch] refuses to
     * remove a branch with unsent work, so by the time this runs the branch is either merged or empty.
     */
    fun remove(project: String, branch: String) {
        if (branch == mainBranch) return
        // **`deleteTree`, not `deleteRecursively`.** The folder holds the `system` link, and
        // `deleteRecursively` walks whatever `listFiles()` answers - which for a symlink is the target,
        // so the account's system folder would be emptied on the way. `deleteTree` deletes the link.
        Workspace.deleteTree(dirOf(project, branch))
        Workspace.deleteTree(commonEntry(project, branch))
    }

    /**
     * **Every branch of [project] that has a working tree**, `main` first.
     *
     * A branch's folder may be nested (`feature/x`), so this walks rather than listing one level. The
     * test for a tree is git's own: a `.git` **file**, which is what a linked worktree has and a real
     * folder does not.
     */
    fun branches(project: String): List<String> {
        val found = mutableListOf(AgentSession.MAIN)
        val root = File(worktreesRoot(), project)
        fun walk(dir: File, prefix: String, depth: Int) {
            if (depth > 4) return
            (dir.listFiles() ?: return).forEach { child ->
                if (!child.isDirectory || Workspace.isLink(child)) return@forEach
                val name = if (prefix.isEmpty()) child.name else "$prefix/${child.name}"
                if (File(child, ".git").isFile) found += name
                walk(child, name, depth + 1)
            }
        }
        walk(root, "", 0)
        return found
    }

    /** Removes every branch folder of [project], for a project that is being deleted or renamed. */
    fun removeAll(project: String) {
        val root = File(worktreesRoot(), project)
        Workspace.deleteTree(root)
        // git's own per-worktree entries go with the repository; only the folders are ours to clear.
    }

    /**
     * Carries a project's branch folders across a rename, **and repoints git at them.**
     *
     * **Called after the project's own folder has been renamed**, because two of the four files git
     * keeps name the other half by absolute path: a tree's `.git` points at
     * `<common>/.git/worktrees/<id>`, and that entry's `gitdir` points back at the tree. Moving the
     * folders alone leaves a worktree whose pointers name paths that no longer exist — which
     * `GitService.openExisting` reports as a repository with **no branch and no exception**, the same
     * silent shape as the `setGitDir` bug this file's header describes.
     *
     * The private entry is also renamed, because [idOf] derives it from the project's name. Leaving
     * the old id in place would be a worktree git lists under a project that is not there.
     */
    fun rename(from: String, to: String): Boolean {
        val source = File(worktreesRoot(), from)
        if (!source.isDirectory) return true
        val target = File(worktreesRoot(), to)
        target.parentFile?.mkdirs()
        if (!source.renameTo(target)) return false
        // **The private entry is renamed first, because its name is derived from the project's.** Leaving
        // the old id in place would be a worktree git lists under a project that is not there.
        //
        // The walk, not one level of listing: a branch called `feature/x` is a folder `feature` holding
        // `x`, and `feature` itself has no `.git` — so a top-level listing would "repoint" a folder that
        // is not a worktree and leave the real one pointing at a path that no longer exists.
        branches(to).filter { it != mainBranch }.forEach { branch ->
            val oldId = idOf(from, branch)
            val newId = idOf(to, branch)
            if (oldId != newId) {
                val oldPrivate = File(File(workspace.projectDir(to), ".git"), "worktrees/$oldId")
                val newPrivate = File(File(workspace.projectDir(to), ".git"), "worktrees/$newId")
                if (oldPrivate.isDirectory) oldPrivate.renameTo(newPrivate)
            }
        }
        return repoint(to)
    }

    /**
     * **Rewrites git's two absolute pointers for every branch folder of [project].**
     *
     * A worktree is joined to its repository by two three-line files that name each other by *absolute*
     * path: the tree's `.git` holds `gitdir: <common>/.git/worktrees/<id>`, and that entry's `gitdir`
     * holds the tree's path back. `commondir` (`../..`) and the `shared` link are relative and survive a
     * move untouched — these two do not, and a tree whose pointers name paths that no longer exist is
     * reported by `GitService.openExisting` as a repository with **no branch and no exception**, the same
     * silent shape as the `setGitDir` bug this file's header describes.
     *
     * Split out of [rename] because moving a project to another account needs exactly this half and none
     * of the folder renames (`Transfer`): the ids are the project's name, which does not change when the
     * account does.
     *
     * **Rewritten, not patched.** These are one-path files, so a replacement is the same size of job as an
     * edit and cannot half-succeed.
     */
    fun repoint(project: String): Boolean {
        branches(project).filter { it != mainBranch }.forEach { branch ->
            val tree = dirOf(project, branch)
            val private = commonEntry(project, branch)
            runCatching {
                Beside.write(File(tree, ".git"), "gitdir: " + private.absolutePath + "\n")
                Beside.write(File(private, "gitdir"), File(tree, ".git").absolutePath + "\n")
                Beside.write(File(private, "commondir"), "../..\n")
            }
        }
        return true
    }

    companion object {
        const val WORKTREES = "worktrees"
    }
}
