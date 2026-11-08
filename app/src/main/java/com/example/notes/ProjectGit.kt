package com.example.notes

import org.eclipse.jgit.lib.ObjectId
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

    /**
     * How to ask for a background index pass, or a no-op.
     *
     * **A provider like the three above it**, and a parameter rather than a call into [IndexQueue]
     * for the reason they are: a `ProjectGit` built in a test must not spend a key or start a
     * thread, and a test that wants to see *that a commit asks for an index* passes a recorder. The
     * app passes `IndexQueue::want` at the places that also configure the queue.
     */
    /**
     * How to ask for a background index pass: **(project, branch, why)**, or a no-op.
     *
     * The branch is in the call because the index is per worktree (§13d): a commit in `feature`'s tree
     * asks for `feature`'s index, and asking for the project alone would have the pass walk main's
     * files and leave the branch's index describing the wrong tree.
     */
    private val index: (String, String, String) -> Unit = { _, _, _ -> },

    /**
     * **The branch this view of the repository is on** (§13d), and therefore which working tree it
     * opens, locks and commits in.
     *
     * `main` by default, because a project's own folder is main's tree — so every caller that does not
     * care keeps exactly the behaviour it had. [onBranch] is how the agent gets a view of the branch it
     * is actually standing in: without it, an agent on `feature` wrote files into `feature`'s worktree
     * and then committed **main's** tree, which is a commit that records nothing and says nothing.
     */
    private val branch: String = AgentSession.MAIN,

    /**
     * **The secret a remote's host has in the vault, or null** — the portable half of a push.
     *
     * A provider like the others: a `ProjectGit` in a test must not need a keystore to answer, and a
     * test that wants to see *that the vault was asked* passes a recorder. The app passes
     * `Credentials::secret`, which answers narrowest-first (project, then account, then shared), so one
     * lambda is the whole of "this project has its own key for this host".
     */
    private val vaultToken: (project: String, host: String) -> String? = { _, _ -> null },

    /**
     * **The host this account's key belongs to** — what [GitCredentials.forRemote] compares a remote
     * against before offering the account's token.
     *
     * A provider, like the four above, and for the same reason: this class must not read the account
     * store, and a test that wants "an account on gitlab.com" passes it in. `github.com` is the
     * default because a `ProjectGit` with no account behind it is a test.
     */
    private val accountHost: () -> String = { GITHUB_HOST },
) {

    /**
     * **The same repository, seen from another branch's working tree.**
     *
     * One method rather than a branch parameter on all thirty: the providers are the same lambdas, the
     * lock is keyed by the common directory (so both views share it), and only the directory each
     * operation opens changes.
     */
    fun onBranch(branch: String): ProjectGit =
        if (branch == this.branch) this
        else ProjectGit(
            workspace, tokenProvider, authorProvider, index, branch, vaultToken, accountHost,
        )

    /**
     * The working tree of a project on **this view's branch** — the project's own folder on `main`, the
     * branch's worktree otherwise.
     */
    fun repoDir(project: String): File = workspace.treeOf(project, branch)

    /** True when the project has a repository. */
    fun isRepository(project: String): Boolean = GitService.isRepository(repoDir(project))

    /**
     * What this project's remote authenticates with, or the sentence that says why nothing can.
     *
     * **Asked before anything is sent**, so a host this phone has no credential for produces a sentence
     * rather than a server's 401 about a key that was never offered — the failure this app forbids
     * most. The decision itself is [GitCredentials], which has no Android in it and is tested alone.
     */
    private fun credentialFor(project: String, remoteUrl: String?): GitCredentials.Answer =
        GitCredentials.forRemote(remoteUrl, tokenProvider(), accountHost()) { host ->
            vaultToken(project, host)
        }

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
        // **The attribute goes in before the repository is made**, so the very first commit already
        // holds it and a project that has just been created has nothing waiting to be sent. Writing
        // it afterwards left `.gitattributes` as an uncommitted change in every new project — which
        // the app then reported as "1 change" before anybody had touched anything.
        //
        // In the person's repository, not the app's storage: a clone elsewhere has to know which
        // files are pointers, and an attribute only this app could read would make a picture arrive
        // as 130 bytes of JSON that looks like corruption.
        GitService.ensureLfsAttributes(dir)
        RepoLocks.hold(dir, "init") {
            val repo = GitService.initOrOpen(dir, authorProvider())
            try {
                // **Before anything is committed**, so the very first picture a person saves is a
                // pointer rather than a 400 KB blob in the object store. The registration is here
                // as well as in `MainActivity` because a repository can be made before any screen
                // exists — an agent's first write does exactly that.
                GitService.registerLfs()
                GitService.enableLfs(repo)
            } finally {
                repo.close()
            }
        }
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
    /**
     * How many paths of [project] are ignored, so they will never be sent.
     *
     * **The fact `state` used to report to the agent, now on the project row.** A project holding a
     * 40 MB video and one note would otherwise say "up to date" — true of the note, a lie about the
     * project. A project with no repository has nothing ignored, because nothing is tracked.
     */
    fun ignoredCount(project: String): Int {
        val repo = open(project) ?: return 0
        return repo.use {
            try {
                GitService.ignoredPaths(it).size
            } catch (e: Throwable) {
                0
            }
        }
    }

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
                    // **A picture is a pointer, and its bytes are beside the repository.**
                    //
                    // There is nothing to rewrite here, and that is the design: a commit that holds
                    // a picture holds 130 bytes naming its bytes by SHA-256, so a version of a
                    // picture costs the tree almost nothing and the *store* holds exactly one copy
                    // of the content. When a picture is deleted the pointer leaves the tree and
                    // `LfsStore.sweep` reclaims the bytes nothing names any more — no commit is
                    // rebuilt, and no id changes.
                    //
                    // **A marker, so the sweep happens at the next start.** It is proportional to
                    // what is stored rather than to the whole repository, but it is still work that
                    // has nothing to do with this save, so the save does not wait for it.
                    val touched = outcome.id?.let { id ->
                        GitService.changedPaths(it, ObjectId.fromString(id))
                    }.orEmpty()
                    if (touched.any { Workspace.kindOfPath(it) != EntryKind.TEXT }) {
                        Maintenance.mark(repoDir(project), "a picture was saved")
                    }
                    // **Every change the app makes is one commit, so this is the single signal that
                    // the project's text changed** — the editor, the agent's `write`, a delete, a
                    // move, a restore: they all arrive here. The queue is asked rather than told, so
                    // the work happens off this thread and a save does not wait for an embedding.
                    //
                    // A commit that wrote nothing asks for nothing: `written` is false, and a pass
                    // would answer "nothing had changed" at the cost of a walk.
                    index(project, branch, "a change was saved")
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
                // a reason to the remote rather than only a list of files.
                GitService.commitMarker(it, note, authorProvider())
                // **What is sent is `HEAD` minus the unsynced paths.** The local commit is already
                // made above and stays exactly as it is; the push derives a tree without them, so a
                // file the person unsynced keeps every version it has on this phone and never
                // reaches GitHub. An empty list falls through to the ordinary push.
                // **The pictures first, and this order is not a preference.** A pointer names its
                // bytes by SHA-256, and GitHub refuses a push whose tree references an object it
                // does not hold (`GH008`). So the bytes have to be there before the ref that names
                // them moves — uploading after the push is not a slower path, it is a rejected
                // push. The filter is off around the push itself (see `GitService.push`), which is
                // why JGit's own hook never runs and this is the only uploader.
                // **Decided before anything leaves the phone.** A remote this phone has no
                // credential for is a sentence, not a server's 401 about a key never offered.
                val remote = remoteUrlOf(it)
                val token = when (val answer = credentialFor(project, remote)) {
                    is GitCredentials.Answer.Say -> return GitService.RemoteResult(answer.why, false)
                    is GitCredentials.Answer.Use -> answer.token
                    GitCredentials.Answer.None -> null
                }
                val pictures = LfsSync.push(it, token, remote)
                if (!pictures.ok) {
                    // **Nothing is pushed over a picture that did not arrive.** The tree would name
                    // an object GitHub does not have, and the sync would report a failure the person
                    // cannot act on. Saying which picture is the actionable half.
                    GitService.RemoteResult(
                        "Nothing was sent, because a picture could not be uploaded: " +
                            (pictures.why ?: "${pictures.failed} of them failed"),
                        false,
                    )
                } else {
                    // A push that fails leaves the row saying there is work to send, which is the
                    // truth, and is what makes the next attempt meaningful — nothing has to be
                    // recorded either way, because the count is asked of the repository.
                    //
                    // **Ordinary push, not a derived tree.** There used to be a third mechanism here:
                    // "unsync" left a file tracked and derived a commit without it, which could only
                    // be delivered by force because that commit was on no local branch. The operator
                    // replaced it with classic gitignore — "a simple tracked/untracked switch" — so
                    // what is on this phone's `main` and what is on GitHub's are now the same
                    // history, and the push is a fast-forward like any other.
                    GitService.push(it, token)
                }
            } catch (e: Throwable) {
                GitService.RemoteResult(Secrets.describe(e), false)
            }
        }
    }

    /**
     * Sends the project's `main` to GitHub — **fast-forward only, never forced.**
     *
     * **Why not forced.** The operator's own sync used to force, because it pushed a *derived* commit
     * that was on no local branch and GitHub had to be told to accept it. That mechanism is gone, so
     * an ordinary push is a fast-forward and a rejection means the remote genuinely moved. Forcing
     * would discard whatever arrived there, and the agent is the one actor that must never be able
     * to do that — it would be a silent loss with the app's own authority behind it.
     *
     * **Pictures first.** `LfsSync.push` uploads before the ref moves, because GitHub rejects a tree
     * naming bytes it does not hold. Uploading after would be a rejected push, not a slower one.
     *
     * Answers the same shape as [sync], because the callers say the same kind of sentence.
     */
    fun push(project: String): GitService.RemoteResult =
        RepoLocks.hold(repoDir(project), "push") {
            val repo = open(project) ?: return@hold GitService.RemoteResult(
                "This project has no repository yet", false
            )
            repo.use {
                try {
                    val remote = remoteUrlOf(it)
                    val token = when (val answer = credentialFor(project, remote)) {
                        is GitCredentials.Answer.Say -> return@use GitService.RemoteResult(
                            answer.why,
                            false,
                        )
                        is GitCredentials.Answer.Use -> answer.token
                        GitCredentials.Answer.None -> null
                    }
                    val pictures = LfsSync.push(it, token, remote)
                    if (!pictures.ok) {
                        return@use GitService.RemoteResult(
                            "Nothing was sent, because a picture could not be uploaded: " +
                                (pictures.why ?: "${pictures.failed} of them failed"),
                            false,
                        )
                    }
                    GitService.push(it, token)
                } catch (e: Throwable) {
                    GitService.RemoteResult(Secrets.describe(e), false)
                }
            }
        }

    /** What [pull] found: the paths that came in, or the ones that conflict. */
    data class Pulled(
        val ok: Boolean,
        val arrived: List<String> = emptyList(),
        val conflicted: List<String> = emptyList(),
        val said: String = "",
    )

    /**
     * Fetches `origin` and **merges** `origin/main` into this project's branch.
     *
     * **Merge, never rebase.** A rebase rewrites commits and gives them new ids; nothing in this app
     * rewrites history, and an agent doing it silently to somebody's work is the worst version of
     * that rule being broken. A merge commit is visible, dated, and says what happened.
     *
     * **A conflict is an answer, not a failure.** The conflicted paths are returned so the agent can
     * read the markers and write the resolution — resolution is ordinary editing, and a tool for it
     * would be a second way to edit a file.
     */
    fun pull(project: String): Pulled =
        RepoLocks.hold(repoDir(project), "pull") {
            val repo = open(project) ?: return@hold Pulled(false, said = "This project has no repository yet")
            repo.use {
                try {
                    val remote = remoteUrlOf(it)
                    if (remote.isNullOrBlank()) {
                        return@use Pulled(false, said = "This project has no address to pull from")
                    }
                    val token = when (val answer = credentialFor(project, remote)) {
                        is GitCredentials.Answer.Say -> return@use Pulled(false, said = answer.why)
                        is GitCredentials.Answer.Use -> answer.token
                        GitCredentials.Answer.None -> null
                    }
                    GitService.fetch(it, token)
                    val branch = it.branch ?: "main"
                    val upstream = it.resolve("refs/remotes/origin/$branch")
                        ?: return@use Pulled(true, said = "Nothing to pull: the remote has no $branch yet")
                    val head = it.resolve("HEAD")
                        ?: return@use Pulled(false, said = "This project has no commits yet")
                    if (GitService.isAncestor(it, upstream, head)) {
                        return@use Pulled(true, said = "Already up to date")
                    }
                    val result = GitService.merge(it, upstream)
                    Pulled(
                        ok = result.ok,
                        arrived = result.paths,
                        conflicted = result.conflicted,
                        said = result.said,
                    )
                } catch (e: Throwable) {
                    Pulled(false, said = Secrets.describe(e))
                }
            }
        }

    /** The address this project sends to, or null when it has none. */
    private fun remoteUrlOf(repo: org.eclipse.jgit.lib.Repository): String? =
        repo.config.getString("remote", "origin", "url")

    /** How much of one file a preview will read. Half a megabyte is a long file and a short read. */
    private val PREVIEW_LIMIT = 512L * 1024

    /** The paths that differ between the last push and now, as the row counts them. */
    fun unsentPaths(project: String): List<String> {
        val repo = open(project) ?: return emptyList()
        return repo.use { GitService.unsentPaths(it) }
    }

    /**
     * What changed between two revisions, for a file or a folder — **the agent's `git diff`.**
     *
     * ## Why both ends are required
     *
     * A working-tree comparison would be meaningless here: every agent write ends in a commit and
     * every person's edit goes through `afterChange`, so a tracked change that is not committed does
     * not exist. That is why there is no "no arguments" form — there is nothing for it to compare.
     *
     * ## Why the path is a prefix
     *
     * `"src/"` answers *"what changed in this folder"*, which is the `git log -- path/to/folder`
     * question and the one that matters after `unpack` lands forty files. A file path answers for
     * that file. The distinction is the trailing slash, not a second argument.
     *
     * ## Why binary paths are listed and not diffed
     *
     * `diffLines` on a zip is a page of replacement characters that costs the whole conversation and
     * says nothing. `Workspace.kindOfPath` answers by name, before anything is read.
     */
    fun diffBetween(
        project: String,
        path: String,
        from: String,
        to: String,
    ): Result<GitService.RevisionDiff> {
        val repo = open(project) ?: return Result.failure(
            IllegalStateException("This project has no repository yet"),
        )
        return repo.use {
            try {
                GitService.changesBetween(it, path, from, to)
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }
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
                // **The kind first, before anything is read.** A preview of a picture is a page of
                // replacement characters, and `looksBinary` is a guess about bytes where the name is
                // a fact. Saying so before the read is also what keeps a 40 MB file from being
                // loaded to answer a question the name already answered.
                if (Workspace.kindOfPath(path) != EntryKind.TEXT) {
                    return Result.failure(IllegalStateException("not text, so there is no preview"))
                }
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
        // **The same decision the other three verbs make**, asked here rather than inside
        // `GitService.clone` so that every way a URL is used answers one question the same way. The
        // project folder does not exist yet, so only the account and shared scopes can answer — which
        // is exactly why an account's key is stored at account scope.
        val token = when (val answer = credentialFor(project, remoteUrl)) {
            is GitCredentials.Answer.Say -> return GitService.RemoteResult(answer.why, false)
            is GitCredentials.Answer.Use -> answer.token
            GitCredentials.Answer.None -> null
        }
        val result = GitService.clone(
            remoteUrl,
            dir,
            authorProvider(),
            token,
            deadline,
        )
        if (result.success) {
            open(project)?.use { repo ->
                GitService.scrubRemoteUrls(repo)
                // **A cloned project arrives with the attribute already committed** — that is what
                // makes LFS portable — but the *filter* is per-repository configuration, and a
                // clone does not carry it. Without this the pointers would be checked out as
                // pointers: a project of 130-byte files that look like corruption.
                GitService.enableLfs(repo)
            }
            workspace.ensureIgnore(dir)
            workspace.ensureSecretIgnores(dir)
            // **Not written here.** A project cloned from GitHub that holds pictures already carries
            // the attribute — it is committed content, which is the whole point of it — and writing
            // it again would leave the freshly-arrived project with an uncommitted change nobody
            // made. Only a clone of a project that has no attribute gets one, and then it is
            // genuinely new content that has to be committed like anything else.
            GitService.ensureLfsAttributesIfMissing(dir)
            // **The comprehensive pass, asked for once, right after the tree lands.** A clone writes
            // hundreds of files at once and commits none of them, so the commit hook above never
            // fires for it — and a project that arrived from GitHub is exactly the case where the
            // whole index is stale at once. The queue makes it background work with a result, rather
            // than the first search paying for it while somebody waits.
            index(project, branch, "the project was added")
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
