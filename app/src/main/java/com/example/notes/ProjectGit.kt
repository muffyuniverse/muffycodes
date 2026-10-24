package com.example.notes

import org.eclipse.jgit.lib.Repository
import java.io.File

/**
 * The git side of one project.
 *
 * A project's repository lives in its `repo/` folder and tracks exactly what is
 * there. Everything else in the project folder — the files the user keeps beside
 * the worktree — belongs to the account's own history instead
 * ([AppHistory]), so the two histories never overlap.
 *
 * The class takes its secrets and identity as functions rather than reading
 * them itself: that keeps it free of Android dependencies and lets the JVM tests
 * drive it directly.
 */
class ProjectGit(
    private val workspace: Workspace,
    private val tokenProvider: () -> String?,
    private val authorProvider: () -> GitAuthor,
    /** How many commits a project's history keeps; 0 means no limit. */
    private val keepProvider: () -> Int = { 0 },
) {

    /** The worktree of a project. */
    fun repoDir(project: String): File = workspace.repoOf(project)

    /** True when the project has a repository. */
    fun isRepository(project: String): Boolean = GitService.isRepository(repoDir(project))

    /** Opens the project's repository, or null when it has none. */
    fun open(project: String): Repository? {
        val dir = repoDir(project)
        if (!GitService.isRepository(dir)) return null
        return try {
            GitService.initOrOpen(dir, authorProvider())
        } catch (e: Exception) {
            null
        }
    }

    /** Makes the project's repository, with a first commit so history can open. */
    fun init(project: String): Result<Unit> = try {
        val dir = repoDir(project)
        dir.mkdirs()
        GitService.initOrOpen(dir, authorProvider()).close()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** What went wrong the last time this class looked at a project. */
    var lastError: String? = null
        private set

    /** How many changes are waiting in the project, for the row's count. */
    fun changes(project: String): GitService.Changes {
        val repo = open(project) ?: return GitService.Changes(0, 0, 0)
        return repo.use {
            try {
                GitService.status(it)
            } catch (e: Exception) {
                lastError = Secrets.scrub(e.message ?: "Could not read the project")
                GitService.Changes(0, 0, 0)
            }
        }
    }

    /**
     * Syncs the project: commit everything that changed, then force push.
     *
     * The commit message is written from the names that changed, and the
     * history is shortened to the configured length afterwards — the same
     * setting the account's own history obeys, applied to this project.
     */
    /**
     * Commits whatever has changed in the project's worktree, now.
     *
     * Every change is committed as it happens — that is the project's **sync
     * history**, and it is what the history screen walks. Syncing is then only the
     * push: a person is never asked to decide what a change means before they can
     * go back to it.
     *
     * Returns the new commit's id, or null when there was nothing to commit.
     */
    fun commit(project: String, what: String): String? {
        val repo = open(project) ?: return null
        return repo.use {
            try {
                val id = GitService.commitAll(it, what, authorProvider())
                if (id != null) prune(it)
                id
            } catch (e: Exception) {
                null
            }
        }
    }

    fun sync(project: String): GitService.RemoteResult {
        val repo = open(project) ?: return GitService.RemoteResult(
            "This project has no repository yet", false
        )
        return repo.use {
            try {
                // Every change is already committed, so this can only be catching
                // something that slipped past — a half-written file, a deletion made
                // by another app. It stays because a push that silently leaves a
                // change behind is worse than a commit nobody asked for.
                val changed = GitService.changedPaths(it)
                if (changed.isNotEmpty()) {
                    GitService.commitAll(it, CommitMessage.sync(changed), authorProvider())
                }
                // Shorten before pushing, not after. The push is what carries the
                // history to GitHub, so a prune that ran afterwards would shorten
                // this phone's copy and leave the repository as long as it was —
                // the length would only appear to take effect on the next sync.
                prune(it)
                GitService.pushForce(it, tokenProvider())
            } catch (e: Exception) {
                GitService.RemoteResult(Secrets.scrub(e.message ?: "Sync failed"), false)
            }
        }
    }

    /** Applies the project's history length, when one is set. */
    private fun prune(repo: Repository) {
        val keep = keepProvider()
        if (keep <= 0) return
        try {
            HistoryPrune.prune(repo, keep)
        } catch (e: Exception) {
            // A history that could not be shortened is still a working history.
        }
    }

    /** Replaces the project's files with what the remote holds. */
    fun replaceWithRemote(project: String): GitService.RemoteResult {
        val repo = open(project) ?: return GitService.RemoteResult(
            "This project has no repository yet", false
        )
        return repo.use { GitService.resetToRemote(it, tokenProvider()) }
    }

    /** Clones a remote into the project's `repo` folder. */
    fun cloneInto(project: String, remoteUrl: String): GitService.RemoteResult {
        val dir = repoDir(project)
        if (GitService.isRepository(dir)) {
            return GitService.RemoteResult("This project already has a repository", false)
        }
        val result = GitService.clone(remoteUrl, dir, authorProvider(), tokenProvider())
        if (result.success) {
            open(project)?.use { repo -> GitService.scrubRemoteUrls(repo) }
        }
        return result
    }

    /** The remote of the project, or null. */
    fun remoteUrl(project: String): String? {
        val repo = open(project) ?: return null
        return repo.use { GitService.remoteUrl(it) }
    }

    /** The commits that touched a path inside the worktree, newest first. */
    fun log(project: String, path: String? = null): List<GitService.CommitInfo> {
        val repo = open(project) ?: return emptyList()
        return repo.use {
            try {
                GitService.log(it, path)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    /** The versions of a file inside the worktree, newest first. */
    fun versions(project: String, path: String): List<Pair<GitService.CommitInfo, String?>> {
        val repo = open(project) ?: return emptyList()
        return repo.use {
            try {
                GitService.versionsOf(it, path)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    /** Puts an old version of a file back into the worktree. */
    fun restore(project: String, path: String, revisionId: String): Result<Unit> {
        val repo = open(project) ?: return Result.failure(
            IllegalStateException("This project has no repository yet")
        )
        return repo.use {
            try {
                GitService.restore(it, path, revisionId)
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}
