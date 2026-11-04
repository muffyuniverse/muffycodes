package com.example.notes

import org.eclipse.jgit.lib.Repository
import java.io.File

/**
 * The git side of one project.
 *
 * A project's repository **is** the project's folder: it tracks exactly what is there, and the
 * app's own `agent/` is named in git's exclude list so the record is never shipped. There is
 * no second history to fall back on, which is what makes "where did this file's versions come
 * from" a question with one answer.
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
    /**
     * How to ask for a background index pass, or a no-op.
     *
     * **A provider like the three above it**, and a parameter rather than a call into [IndexQueue]
     * for the reason they are: a `ProjectGit` built in a test must not spend a key or start a
     * thread, and a test that wants to see *that a commit asks for an index* passes a recorder. The
     * app passes `IndexQueue::want` at the places that also configure the queue.
     */
    private val index: (String, String) -> Unit = { _, _ -> },
) {

    /** The worktree of a project — **which is the project's own folder.** */
    fun repoDir(project: String): File = workspace.projectDir(project)

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
        // **Held, because making a repository is a write like any other.** Two agents starting
        // work in one project at the same moment would otherwise both see "no repository" and both
        // initialise, and the second `git init` on an existing repository is the least harmful of
        // the outcomes — the harmful one is a commit landing while the refs are being written.
        RepoLocks.hold(dir, "init") { GitService.initOrOpen(dir, authorProvider()).close() }
        // After the repository, because the lines live in `.git/info/exclude` — they are the app's
        // own local rule, not a file in the person's repository.
        workspace.ensureIgnore(dir)
        workspace.ensureSecretIgnores(dir)
        Result.success(Unit)
    } catch (e: Throwable) {
        Result.failure(e)
    }

    /** What went wrong the last time this class looked at a project. */
    var lastError: String? = null
        private set

    /**
     * What the project has that GitHub does not: files not yet committed, and **files** not yet
     * pushed.
     *
     * **Both, because both are "not sent".** Committing happens by itself, so a project that has
     * been edited and saved is clean in git's sense and stale in every sense a person cares about.
     *
     * [GitService.Changes.ahead] is a count of **paths**, not of commits. A commit count said "1 to
     * send" for a batch that moved ten files, and it had to be kept in a file of its own because the
     * history prune renames every commit — a diff between two trees asks about content instead, so
     * neither problem exists.
     */
    fun changes(project: String): GitService.Changes {
        val repo = open(project) ?: return GitService.Changes(0, 0, 0)
        return repo.use {
            try {
                val unsent = GitService.unsentPaths(it).size
                GitService.status(it).copy(ahead = unsent)
            } catch (e: Throwable) {
                lastError = Secrets.describe(e)
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
    fun commit(project: String, what: String, who: String = "commit"): String? =
        commitOutcome(project, what, who).id

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
    fun commitOutcome(project: String, what: String, who: String = "commit"): GitService.CommitOutcome =
        // **The whole sequence under one hold, not each JGit call.** `commitAll` stages, asks for
        // the status, and commits — and the window between those is where another writer used to
        // slip in and commit *this* writer's staged tree under *its* message. A lock around the
        // individual calls would leave that window open and look correct.
        RepoLocks.hold(repoDir(project), who) {
            val repo = open(project) ?: return@hold GitService.CommitOutcome.failed(
                "This project has no repository yet"
            )
            repo.use {
                // **`inFlight = false`, because this is inside `RepoLocks.hold`.**
                //
                // The argument's sense is the opposite of what it sounds like: `true` means "a
                // writer of ours is in the middle of using this repository, so a lock failure is
                // NOT a corpse — leave it alone". Holding `RepoLocks` is the proof that no other
                // writer of ours is inside, which makes a lock failure a corpse again, which makes
                // clearing it both safe and necessary.
                //
                // Getting this backwards is not a subtle bug: it disables recovery from the corpse
                // of a killed process, and `a lock left by a killed app does not lose the change`
                // fails with the project refusing every save for ever.
                val outcome = GitService.commitEverything(it, what, authorProvider(), inFlight = false)
                if (outcome.written) {
                    prune(it)
                    // **Every change the app makes is one commit, so this is the single signal that
                    // the project's text changed** — the editor, the agent's `write`, a delete, a
                    // move, a restore: they all arrive here. The queue is asked rather than told, so
                    // the work happens off this thread and a save does not wait for an embedding.
                    //
                    // A commit that wrote nothing asks for nothing: `written` is false, and a pass
                    // would answer "nothing had changed" at the cost of a walk.
                    index(project, "a change was saved")
                }
                outcome
            }
        }

    @JvmOverloads
    fun sync(project: String, note: String = ""): GitService.RemoteResult =
        // **The whole sync under one hold.** It commits, prunes, and pushes, and every one of those
        // reads or writes the same refs — a commit landing between the prune and the push would
        // push a tree the prune did not describe, and a commit landing during the push would either
        // be silently left behind or make the push refuse for a reason nobody can act on.
        //
        // It is also the longest hold in the app: a push over a phone's network is seconds, and the
        // lock is what stops an agent's save from landing in the middle of it. That is why a hold
        // this long is traced (`repo-hold`) rather than merely waited on.
        RepoLocks.hold(repoDir(project), "sync") {
            val repo = open(project) ?: return@hold GitService.RemoteResult(
                "This project has no repository yet", false
            )
            syncUnder(repo, project, note)
        }

    /** [sync]'s body, split out so the lock wraps one statement rather than a lambda's braces. */
    private fun syncUnder(
        repo: org.eclipse.jgit.lib.Repository,
        project: String,
        note: String,
    ): GitService.RemoteResult {
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
                        // Inside `RepoLocks.hold`, so a lock failure is a corpse, not a rival.
                        inFlight = false,
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
                // **What is sent is `HEAD` minus the unsynced paths.** The local commit is already
                // made above and stays exactly as it is; the push derives a tree without them, so a
                // file the person unsynced keeps every version it has on this phone and never
                // reaches GitHub. An empty list falls through to the ordinary push.
                val omitted = workspace.unsentFrom(project)
                if (omitted.isNotEmpty()) {
                    Trace.note(
                        "not sent",
                        "$project: ${omitted.size} — " +
                            omitted.take(12).joinToString(", ") +
                            (if (omitted.size > 12) " +${omitted.size - 12} more" else ""),
                    )
                }
                // A push that fails leaves the row saying there is work to send, which is the
                // truth, and is what makes the button something a person can press again — nothing
                // has to be recorded either way, because the count is asked of the repository
                // (see [changes]).
                GitService.pushWithout(
                    it,
                    tokenProvider(),
                    omitted,
                    authorProvider(),
                    note.trim().ifBlank { "Sync" },
                )
            } catch (e: Throwable) {
                GitService.RemoteResult(Secrets.describe(e), false)
            }
        }
    }

    /** How much of one file a preview will read. Half a megabyte is a long file and a short read. */
    private val PREVIEW_LIMIT = 512L * 1024

    /** The paths that differ between the last push and now, as the row counts them. */
    fun unsentPaths(project: String): List<String> {
        val repo = open(project) ?: return emptyList()
        return repo.use { GitService.unsentPaths(it) }
    }

    /**
     * One file as it differs from the last push, line by line.
     *
     * **Pending means "not on GitHub", not "not committed"** — every save is a commit — so both sides
     * are read from the repository: `refs/remotes/origin/<branch>` against `HEAD`. A file that has
     * never been sent has no old side, and one that was deleted has no new side; both are `""`, which
     * is what makes a preview of an added file read as all additions.
     *
     * The failure is the answer for a file that is not text: the window shows the reason beside the
     * name instead of a page of convincing nonsense.
     */
    fun diff(project: String, path: String): Result<List<GitService.DiffLine>> {
        val repo = open(project) ?: return Result.failure(
            IllegalStateException("This project has no repository yet")
        )
        return repo.use {
            try {
                val branch = it.branch ?: return Result.failure(
                    IllegalStateException("This project is not on a branch")
                )
                val sent = GitService.ORIGIN + branch
                // **Said before it is read**, because reading it is the thing to avoid: a preview of a
                // 40 MB file would be a page of text the person cannot use and a second of a stalled
                // screen.
                if ((GitService.blobSize(it, "HEAD", path) ?: 0) > PREVIEW_LIMIT ||
                    (GitService.blobSize(it, sent, path) ?: 0) > PREVIEW_LIMIT
                ) {
                    return Result.failure(IllegalStateException("too big to preview"))
                }
                val before = GitService.blobText(it, sent, path).orEmpty()
                val after = GitService.blobText(it, "HEAD", path).orEmpty()
                if (GitService.looksBinary(before) || GitService.looksBinary(after)) {
                    return Result.failure(IllegalStateException("not text, so there is no preview"))
                }
                Result.success(GitService.diffLines(before, after))
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }
    }

    /**
     * Applies the project's history length, when one is set.
     *
     * **What a prune leaves behind is a marker, not a collection.** It moves a ref, so the dropped
     * commits and their trees are unreachable but still on disk; deleting them is `git gc`, which is
     * proportional to the whole repository and has no place inside a save. [Maintenance] collects at
     * app start, where a person is not waiting, and only for a project that said something is
     * waiting — which is this.
     */
    private fun prune(repo: Repository) {
        val keep = keepProvider()
        if (keep <= 0) return
        try {
            if (HistoryPrune.prune(repo, keep) > 0) {
                Maintenance.mark(repo.workTree ?: return, "the history was shortened")
            }
        } catch (e: Throwable) {
            // A history that could not be shortened is still a working history.
        }
    }

    /** Replaces the project's files with what the remote holds. */
    fun replaceWithRemote(project: String): GitService.RemoteResult {
        val repo = open(project) ?: return GitService.RemoteResult(
            "This project has no repository yet", false
        )
        // The files are now GitHub's, and nothing has to be noted down: the row's count is a diff
        // against that same remote-tracking ref.
        val result = repo.use { GitService.resetToRemote(it, tokenProvider()) }
        // **A pull is a change with no commit**, which is the second case the commit hook cannot
        // see — the working tree is replaced by the remote's and no local version is written. Left to
        // the search guard it would be indexed when somebody next searched, which is to say the one
        // search that most needs to be fast pays for the whole diff. Asked for here instead, and only
        // when it worked: a failed reset changed nothing worth indexing.
        if (result.success) {
            index(project, "the remote replaced the files")
            // **A reset strands commits too, and no prune sees it.** A force-pushed or rebased remote
            // makes this phone's old commits unreachable; nothing shortened the history, so the only
            // place that can notice is here, where the reset happened.
            Maintenance.mark(repoDir(project), "the remote replaced the history")
        }
        return result
    }

    /**
     * Clones a remote into the project's folder — which becomes the repository.
     *
     * **The app's two ignore lines go in after the clone**, because git refuses to clone into a
     * folder that already holds anything — and they go in `.git/info/exclude`, so they are not a
     * change to the person's repository and a project that has just arrived has nothing to send.
     */
    fun cloneInto(
        project: String,
        remoteUrl: String,
        deadline: Deadline? = null,
    ): GitService.RemoteResult {
        val dir = repoDir(project)
        if (GitService.isRepository(dir)) {
            return GitService.RemoteResult("This project already has a repository", false)
        }
        val result = GitService.clone(
            remoteUrl,
            dir,
            authorProvider(),
            tokenProvider(),
            deadline,
        )
        if (result.success) {
            open(project)?.use { repo -> GitService.scrubRemoteUrls(repo) }
            workspace.ensureIgnore(dir)
            workspace.ensureSecretIgnores(dir)
            // **The comprehensive pass, asked for once, right after the tree lands.** A clone writes
            // hundreds of files at once and commits none of them, so the commit hook above never
            // fires for it — and a project that arrived from GitHub is exactly the case where the
            // whole index is stale at once. The queue makes it background work with a result, rather
            // than the first search paying for it while somebody waits.
            index(project, "the project was added")
        }
        return result
    }

    /** The remote of the project, or null. */
    /**
     * Gives a local project somewhere to push — **local → synced.**
     *
     * The other half of the cycle. A local project is one with no remote, so syncing it is a matter
     * of creating the repository on GitHub and pointing at it; the history that is already on the
     * phone goes up with the first sync.
     */
    fun makeRemote(project: String, url: String): Boolean {
        val repo = open(project) ?: return false
        return try {
            repo.use { GitService.setRemoteUrl(it, url) }
            true
        } catch (e: Throwable) {
            lastError = Secrets.describe(e)
            false
        }
    }

    /**
     * Takes a project's remote away — **synced → local.**
     *
     * Every commit stays: the history is in this phone's repository, and removing the remote only
     * stops there being somewhere to send it. What is already on GitHub stays there, which is why
     * the screen asks before doing this.
     */
    fun makeLocal(project: String): Boolean {
        val repo = open(project) ?: return false
        return try {
            repo.use { GitService.removeRemote(it) }
            true
        } catch (e: Throwable) {
            lastError = Secrets.describe(e)
            false
        }
    }

    fun remoteUrl(project: String): String? {
        val repo = open(project) ?: return null
        return repo.use { GitService.remoteUrl(it) }
    }

    /**
     * True when this project has nowhere to push — **a local project**.
     *
     * A local project is a project like any other: its own folder, its own repository, its own
     * versions, its own agent. What it does not have is a remote, so Sync is not a thing it can do
     * and the row says `local` rather than `up to date`. That distinction matters: "up to date" is a
     * claim about GitHub, and a folder that has never been to GitHub is not up to date with it.
     */
    fun isLocal(project: String): Boolean = remoteUrl(project).isNullOrBlank()

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
    fun versions(
        project: String,
        path: String,
        deadline: Deadline = Deadline.none(),
    ): List<Pair<GitService.CommitInfo, String?>> {
        val repo = open(project) ?: return emptyList()
        return repo.use {
            try {
                GitService.versionsOf(it, path, deadline = deadline)
            } catch (e: StoppedException) {
                // **A stop is not a failure to read.** Every other exception here means "there is no
                // history for you"; this one means the operator's time ran out, and answering
                // "this file has no versions yet" would be a lie about the repository.
                throw e
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
    fun restore(
        project: String,
        path: String,
        revisionId: String,
        deadline: Deadline? = null,
    ): Result<Unit> = RepoLocks.hold(repoDir(project), "restore") {
        val repo = open(project) ?: return@hold Result.failure(
            IllegalStateException("This project has no repository yet")
        )
        return@hold repo.use {
            try {
                GitService.restore(it, path, revisionId, deadline)
                // The commit is made by the caller, and the row's count comes from the repository
                // itself ([changes]), so a version put back needs nothing recorded here.
                Result.success(Unit)
            } catch (e: StoppedException) {
                // **The operator's time ran out, which is not a fault.** Kept apart from the catch
                // below so the agent is told it was stopped rather than that something broke.
                Result.failure(e)
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }
    }
}
