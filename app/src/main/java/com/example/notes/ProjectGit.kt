package com.example.notes

import org.eclipse.jgit.lib.Repository
import java.io.File

/**
 * The git side of one project.
 *
 * A project's repository lives in its `GitHub/` folder and tracks exactly what is
 * there. Everything else in the project folder — the files the user keeps beside
 * the worktree — belongs to the account's own history instead
 * ([AppHistory]), so the two histories never overlap.
 *
 * The class takes its secrets and identity as functions rather than reading
 * them itself: that keeps it free of Android dependencies and lets the JVM tests
 * drive it directly.
 *
 * **Every failure here is caught as `Throwable`, and that is not laziness.** Git
 * on a phone fails in ways that are not `Exception`s: a class the runtime cannot
 * resolve arrives as a `NoClassDefFoundError`, whose message is "Failed resolution
 * of: ...". Catching only `Exception` let one of those out of the worker, where it
 * ended the process or came back as a message with no project, no file and no
 * action in it — "sync doesn't follow" with an exception on the screen and nothing
 * else. What a failure must never do is stop the app or leave a change half-done
 * silently; it must say what happened.
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
        } catch (e: Throwable) {
            null
        }
    }

    /** Makes the project's repository, with a first commit so history can open. */
    fun init(project: String): Result<Unit> = try {
        val dir = repoDir(project)
        dir.mkdirs()
        GitService.initOrOpen(dir, authorProvider()).close()
        Result.success(Unit)
    } catch (e: Throwable) {
        Result.failure(e)
    }

    /** What went wrong the last time this class looked at a project. */
    var lastError: String? = null
        private set

    /**
     * What the project has that GitHub does not: changes not yet committed, and commits
     * not yet pushed.
     *
     * **Both, because both are "not sent".** Committing happens by itself, so a project
     * that has been edited and saved is clean in git's sense and stale in every sense a
     * person cares about. The row's number and the Sync button are about the second.
     */
    fun changes(project: String): GitService.Changes {
        val repo = open(project) ?: return GitService.Changes(0, 0, 0)
        val unsent = workspace.isUnsent(project)
        return repo.use {
            try {
                GitService.status(it).let { if (unsent) it.copy(ahead = 1) else it }
            } catch (e: Throwable) {
                lastError = Secrets.describe(e)
                if (unsent) GitService.Changes(0, 0, 0, ahead = 1) else GitService.Changes(0, 0, 0)
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
    fun commit(project: String, what: String): String? = commitOutcome(project, what).id

    /**
     * Commits everything that changed, and says **which of three things happened**.
     *
     * The old body answered "no id" both for "there was nothing to commit" and for
     * "the commit could not be written", and the caller treated both as success. On a
     * phone that had a stale `index.lock`, every save after it was refused by JGit,
     * reported as nothing-to-do, and the editor greyed Save out as though the work had
     * been recorded — while the history, correctly, showed nothing new. That is the
     * bug the trace was built to catch, and the shape of the return type is half its
     * cause.
     */
    fun commitOutcome(project: String, what: String): GitService.CommitOutcome {
        val repo = open(project) ?: return GitService.CommitOutcome.failed(
            "This project has no repository yet"
        )
        return repo.use {
            val outcome = GitService.commitEverything(it, what, authorProvider())
            if (outcome.written) {
                prune(it)
                // **A committed change is still unsent.** Every change is committed as it
                // happens and Sync is then only the push, so a version in this phone's
                // history is work GitHub has never seen. Nothing recorded that, and the row
                // counted only uncommitted files — so saving a file left the project
                // reading `changed 0`, with Sync greyed out and the work going nowhere.
                // Reported from the live log: "changed a file, saved but sync is still grey
                // and inactive and new change isnt reported".
                workspace.markUnsent(project)
            }
            outcome
        }
    }

    @JvmOverloads
    fun sync(project: String, note: String = ""): GitService.RemoteResult {
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
                // **The trace names what was waiting.** "10 changed" on a row is a
                // number nobody can act on; the names are what says whether it is real
                // work or something the app left behind. One line per sync, so it costs
                // nothing and turns a mystery into a list.
                if (changed.isNotEmpty()) {
                    Trace.note(
                        "changes waiting",
                        "$project: ${changed.size} — " +
                            changed.take(12).joinToString(", ") +
                            (if (changed.size > 12) " +${changed.size - 12} more" else ""),
                    )
                    // The person's words when they gave any, the names otherwise: a
                    // commit made here is still a change that has to be explained,
                    // and their sentence explains it better than the app can.
                    val made = GitService.commitEverything(
                        it,
                        note.trim().ifBlank { CommitMessage.sync(changed) },
                        authorProvider(),
                    )
                    // **Nothing is pushed over a commit that did not happen.** Pushing
                    // now would send a tree that is missing exactly the work the person
                    // was trying to save, and report the sync as done.
                    if (!made.ok) {
                        return GitService.RemoteResult(
                            "Nothing was pushed, because the changes could not be committed: " +
                                made.error,
                            false,
                        )
                    }
                }
                // The sync's own message, on its own commit, so that the push carries
                // a reason to GitHub rather than only a list of files.
                GitService.commitMarker(it, note, authorProvider())
                // Shorten before pushing, not after. The push is what carries the
                // history to GitHub, so a prune that ran afterwards would shorten
                // this phone's copy and leave the repository as long as it was —
                // the length would only appear to take effect on the next sync.
                prune(it)
                val pushed = GitService.pushForce(it, tokenProvider())
                // Only a push that landed clears the flag. A failed push leaves the row
                // saying there is work to send, which is the truth, and is what makes the
                // button something a person can press again.
                if (pushed.success) workspace.markSent(project)
                pushed
            } catch (e: Throwable) {
                GitService.RemoteResult(Secrets.describe(e), false)
            }
        }
    }

    /** Applies the project's history length, when one is set. */
    private fun prune(repo: Repository) {
        val keep = keepProvider()
        if (keep <= 0) return
        try {
            HistoryPrune.prune(repo, keep)
        } catch (e: Throwable) {
            // A history that could not be shortened is still a working history.
        }
    }

    /** Replaces the project's files with what the remote holds. */
    fun replaceWithRemote(project: String): GitService.RemoteResult {
        val repo = open(project) ?: return GitService.RemoteResult(
            "This project has no repository yet", false
        )
        val result = repo.use { GitService.resetToRemote(it, tokenProvider()) }
        // The files are now GitHub's, so nothing of this phone's is waiting to go there.
        if (result.success) workspace.markSent(project)
        return result
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
    fun log(
        project: String,
        path: String? = null,
        limit: Int = 100,
    ): List<GitService.CommitInfo> {
        val repo = open(project) ?: return emptyList()
        return repo.use {
            try {
                GitService.log(it, path, limit)
            } catch (e: Throwable) {
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
            } catch (e: Throwable) {
                emptyList()
            }
        }
    }

    /** The paths inside the worktree at [revisionId], under [path] — names only. */
    fun paths(project: String, path: String?, revisionId: String): List<String> {
        val repo = open(project) ?: return emptyList()
        return repo.use {
            try {
                GitService.pathsUnder(it, path?.ifBlank { null }, revisionId)
            } catch (e: Throwable) {
                emptyList()
            }
        }
    }

    /** The bytes of a file at a past version, or null when it is not there. */
    fun bytesAt(project: String, path: String, revisionId: String): ByteArray? {
        val repo = open(project) ?: return null
        return repo.use {
            try {
                GitService.bytesAt(it, path, revisionId)
            } catch (e: Throwable) {
                null
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
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }
    }
}
