package com.example.notes

import java.io.File

/**
 * **Moving or copying a whole project from one account to another** (§3.9 of the credentials plan).
 *
 * The operator, 2026-11-09: *"make it possible to move and clone projects between accounts, and make
 * ~local undeletable … so that local always exist and we dont need machinery for its recreation, and
 * users can just transfer its projects to proper remote accounts and there connect them to origin."*
 *
 * So this is the verb that replaces the old adoption-by-rename. It has to exist for its own sake — two
 * signed-in accounts are two places a project may live — and the first sign-in is then simply the first
 * time somebody uses it, which is why `AccountStore.adoptProvisional` could be deleted rather than
 * rewritten.
 *
 * ### What a project is, beyond its folder
 *
 * | part | what happens |
 * |---|---|
 * | `<account>/<project>/` | renamed, or copied |
 * | `<account>/.muffy/worktrees/<project>/` | the same, then **repointed** ([Worktrees.repoint]) |
 * | the repository | travels with the folder, `search.db` included — the index describes the files, and they are the same files |
 * | `shared/` | **not moved.** A unit a person wrote is theirs, and copying a file is the browser's Copy |
 * | the request history | **not moved.** It is what this account's key spent |
 *
 * **Two things are removed rather than carried, and both are account-scoped facts about a project**:
 * the branch's schema pointer (a path in *this* account's `shared/requests/`, absent in the destination)
 * and the agent session rows (a claim about an agent standing in a worktree that has gone). The
 * destination's own rows for the name are cleared too, because a name can have been a project there
 * before and a stale row would answer for a project nobody started.
 *
 * ### Why none of it runs on the worker
 *
 * `move` is a rename, which is one filesystem call. `copy` walks a repository, exactly as the browser's
 * Copy of a folder already does on this thread — the app's existing answer for the same act, and a
 * transfer is a deliberate press rather than something a refresh does.
 */
object Transfer {

    /**
     * Why [project] cannot go from [from] to the account at [toAccountDir], or null when it can.
     *
     * **Answered before anything is touched**, so a refusal leaves both accounts exactly as they were.
     * Every sentence names the thing that is wrong and what to do about it, because these refusals are
     * reached from a menu on a row a person is looking at.
     */
    fun problem(
        from: Workspace,
        toAccountDir: File,
        project: String,
        toIsLocal: Boolean,
    ): String? {
        val folder = from.projectDir(project)
        if (!from.isProjectFolder(folder)) {
            return "\"$project\" is not a project, so there is nothing to move."
        }
        val destination = File(toAccountDir, project)
        // **A name taken is refused, not merged.** Two repositories with one name is not a move: it needs
        // a rule for which history wins, and the answer that cannot lose work is to leave both alone.
        if (destination.exists()) {
            return "\"$project\" is already a project in ${toAccountDir.name}. " +
                "Rename one of them on this phone, or move it to another account."
        }
        // **A connected project cannot arrive in Local**, which has no host and no key to reach its
        // address with (§3.9). Said here rather than in the view model because this is the third door
        // that writes `remote.origin.url`, and a rule kept at two of three is a rule with a hole.
        if (toIsLocal && connected(folder)) {
            return "\"$project\" has an address, and projects in Local stay on this phone. " +
                "Move it to an account instead, or keep a copy with no address."
        }
        return null
    }

    /**
     * Moves or copies [project] from [from] to the account at [toAccountDir].
     *
     * [problem] must have answered null; this does not re-check, because the answer is what the caller
     * showed the person and two answers to one question is how they disagree.
     */
    fun go(from: Workspace, toAccountDir: File, project: String, copy: Boolean): Result<Unit> {
        val source = from.projectDir(project)
        val target = File(toAccountDir, project)
        val to = Workspace(toAccountDir, from.appDir)
        val sourceTrees = File(from.stateDir, "${Worktrees.WORKTREES}/$project")
        val targetTrees = File(to.stateDir, "${Worktrees.WORKTREES}/$project")
        return try {
            toAccountDir.mkdirs()
            if (copy) {
                if (!copyTree(source, target)) {
                    // **A half-copied project is removed, not left.** It would be listed as a project with
                    // part of a history, which is worse than an empty folder and much worse than a
                    // refusal: nothing in the app would say which files arrived.
                    Workspace.deleteTree(target)
                    return Result.failure(
                        IllegalArgumentException("\"$project\" could not be copied, and nothing was left behind."),
                    )
                }
                if (sourceTrees.isDirectory) {
                    targetTrees.parentFile?.mkdirs()
                    copyTree(sourceTrees, targetTrees)
                }
                // **The copy's pointers name the destination's paths**, because a copy keeps the whole
                // worktree folders and their `.git` files hold absolute paths. Repointed before the
                // caller can open it.
                Worktrees(to).repoint(project)
            } else {
                if (!source.renameTo(target)) {
                    return Result.failure(
                        IllegalArgumentException("\"$project\" could not be moved: the folder is in use."),
                    )
                }
                if (sourceTrees.isDirectory) {
                    targetTrees.parentFile?.mkdirs()
                    sourceTrees.renameTo(targetTrees)
                }
                Worktrees(to).repoint(project)
            }
            // **The facts about a project that do not travel.** A move ends the source's claims; a copy
            // must not leave the destination answering for the source's.
            if (!copy) {
                AgentSessions(from.accountDir).removeProject(project)
                Schemas.clearPointers(from.accountDir, project)
            }
            AgentSessions(to.accountDir).removeProject(project)
            Schemas.clearPointers(to.accountDir, project)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(IllegalArgumentException("\"$project\" could not be transferred: ${e.message}"))
        }
    }

    /** True when the repository in [folder] names a remote — a project with somewhere to push. */
    private fun connected(folder: File): Boolean {
        val repo = GitService.openExisting(folder) ?: return false
        return repo.use { !GitService.remoteUrl(it).isNullOrBlank() }
    }

    /**
     * Copies [source] to [target] as bytes, **never following a link.**
     *
     * The same rule [Workspace.deleteTree] follows for the same reason: every worktree holds a `shared`
     * link, and a walk that followed it would copy the account's whole folder into the project.
     */
    private fun copyTree(source: File, target: File): Boolean {
        if (Workspace.isLink(source)) return true
        if (source.isDirectory) {
            if (!target.mkdirs() && !target.isDirectory) return false
            (source.listFiles() ?: return true).forEach { child ->
                if (!copyTree(child, File(target, child.name))) return false
            }
            return true
        }
        return try {
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true).isFile
        } catch (e: Exception) {
            false
        }
    }
}
