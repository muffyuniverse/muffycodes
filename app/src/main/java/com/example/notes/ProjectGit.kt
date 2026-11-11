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
     * `Credentials::secret`, which answers narrowest-first (branch, project, account, shared), so one
     * lambda is the whole of "this branch has its own key for this host".
     *
     * **The branch is asked, not the project alone.** A fine-grained token the host limits to one
     * branch is a credential a branch holds (`PLAN-credentials` C1), and a push from `feature` that
     * quietly used the project's token would be the wrong identity writing to the repository.
     */
    private val vaultToken: (project: String, branch: String, host: String) -> String? = { _, _, _ -> null },

    /**
     * **The host this account's key belongs to** — what [GitCredentials.forRemote] compares a remote
     * against before offering the account's token.
     *
     * A provider, like the four above, and for the same reason: this class must not read the account
     * store, and a test that wants "an account on gitlab.com" passes it in. `github.com` is the
     * default because a `ProjectGit` with no account behind it is a test.
     */
    private val accountHost: () -> String = { GITHUB_HOST },

    /**
     * **The username this host wants with the key**, or blank for "the token is the username".
     *
     * The third piece of the account that a remote needs (`Account.gitUser`). It is a provider for the
     * same reason the others are, and it is separate from [accountHost] because the two answer different
     * questions: the host decides *whether* the key may go, the username decides *how it is presented*.
     */
    private val gitUser: () -> String = { "" },

    /**
     * **The git connection this branch, project or account holds for a host, or null** (Vault B).
     *
     * Asked before the named secret, because it is the one that carries a username with the token and
     * the one a person sets in a row's ⋮. The store answers narrowest-first and skips a nearer scope
     * bound to another host, so one lambda is the whole of "this branch has its own key for this host".
     */
    private val gitConnection: (project: String, branch: String, host: String) -> Connection? =
        { _, _, _ -> null },
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
            workspace, tokenProvider, authorProvider, branch, vaultToken, accountHost, gitUser,
            gitConnection,
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
     *
     * **`internal` rather than private**, because the wiring is half the decision: that a branch's
     * `ProjectGit` asks with *its* branch, and that a connection's username travels with its token, are
     * facts no push to a real server can be made to show in a test — and a decision reachable only
     * through the network is a decision the suite cannot falsify.
     */
    internal fun credentialFor(project: String, remoteUrl: String?): GitCredentials.Answer =
        GitCredentials.forRemote(
            remoteUrl = remoteUrl,
            accountToken = tokenProvider(),
            accountHost = accountHost(),
            vault = { host -> vaultToken(project, branch, host) },
            connection = { host -> gitConnection(project, branch, host) },
        )

    /**
     * **The username a credential is presented with**: its own, or the account's.
     *
     * One function rather than `answer.user ?: gitUser()` six times, because the six verbs must agree
     * about who they are — a push made as one person and a tag made as another is the drift that makes
     * a host's own history unreadable.
     */
    internal fun userOf(answer: GitCredentials.Answer.Use): String = answer.user ?: gitUser()


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
        // **A repository and nothing else.** An `.gitattributes` used to be written here before the
        // repository existed, because a picture was stored as an LFS pointer and a clone had to know
        // it. Nothing stands between the tree and the working copy now, so a project is a git project
        // and a clone anywhere reads every file it holds.
        RepoLocks.hold(dir, "init") {
            val repo = GitService.initOrOpen(dir, authorProvider())
            repo.close()
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
                // **`ahead` is the whole answer, and the index's own counts are not added to it.**
                // Since 2026-11-10 a save stages and a push commits, so `unsentPaths` compares the
                // *index* with the last push — which is the same set `status().staged` describes.
                // Adding both counted every saved file twice: three saves read "6 to send", and the
                // row said so on screen.
                //
                // What is left in `unstaged` and `untracked` is what a save did not take — a file
                // written by something else, a half-written download — and it still counts, because
                // a push catches it too (`syncUnder` unions the two lists).
                val status = GitService.status(it)
                GitService.Changes(
                    staged = 0,
                    unstaged = status.unstaged,
                    untracked = status.untracked,
                    ahead = unsent,
                )
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
                GitService.commitEverything(it, what, authorProvider(), inFlight = false)
            }
        }

    /**
     * **Stages everything. Nothing is committed here.**
     *
     * The operator's rule (2026-11-10): *"we dont commit on save, we commit on push, on save changes
     * are only staged."* A commit is a version a person decided on, and ten saves are one version —
     * so the commit happens at the push, which is the moment somebody says "this is the version".
     *
     * **There is no exception for a project with nowhere to push**, because there is no such project
     * any more: every project has a remote, and a project whose remote has gone quiet is repointed
     * rather than demoted. That was the only reason this function ever committed, and removing local
     * projects removed it.
     *
     * The same hold and the same `inFlight = false` reasoning as [commitOutcome], because it is the same
     * write to the same index. Answers false when the index could not be written, so a save whose work
     * is on disk but not staged says so rather than reporting a clean save.
     */
    fun stage(project: String): Boolean =
        RepoLocks.hold(repoDir(project), "stage") {
            val repo = open(project) ?: return@hold false
            repo.use { GitService.stageAll(it, inFlight = false) }
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
                // **The staged work, which is what a save leaves behind now.** `changedPaths` answers
                // the working tree and `stagedPaths` the index; a save stages, so the index is where
                // the work is. Both are read because a sync also catches what slipped past a save — a
                // half-written file, a deletion made by another app — and a push that silently leaves
                // a change behind is worse than a commit nobody asked for.
                val changed = (GitService.stagedPaths(it) + GitService.changedPaths(it)).distinct()
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
                        // **The person's sentence, or one written from what changed.** With the commit
                        // moved to the push, this is the version's only message — so a note that was
                        // typed is used verbatim and a sync with none names the files.
                        note.trim().ifBlank { CommitMessage.sync(changed) },
                        authorProvider(),
                        // Inside `RepoLocks.hold`, so a lock failure is a corpse, not a rival.
                        inFlight = false,
                    )
                    // **Nothing is pushed over a commit that did not happen.** Pushing
                    // now would send a tree that is missing exactly the work the person
                    // was trying to save, and report the sync as done.
                    if (!made.ok) {
                        return@use GitService.RemoteResult(
                            "Nothing was pushed, because the changes could not be committed: " +
                                made.error,
                            false,
                        )
                    }
                }
                // **No marker commit any more.** The note is the message of the commit made above
                // from the staged work, which is where a version is made now (2026-11-10) — so a
                // second, empty commit carrying the same sentence would be the same reason told twice.
                // It survives as the *fallback* above: a sync with nothing staged but a note still
                // says why it was pushed, which is what a person pressing Sync on a clean tree means.
                if (changed.isEmpty()) GitService.commitMarker(it, note, authorProvider())
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
                val (token, user) = when (val answer = credentialFor(project, remote)) {
                    // **`return@use`**, because this is inside `repo.use { }` — a bare `return` here
                    // is a non-local return from `syncUnder`, which Kotlin refuses: the lambda is not
                    // inline. CI found this one; it is the same shape as the two above.
                    is GitCredentials.Answer.Say -> return@use GitService.RemoteResult(answer.why, false)
                    is GitCredentials.Answer.Use -> answer.token to userOf(answer)
                    GitCredentials.Answer.None -> null to gitUser()
                }
                // A push that fails leaves the row saying there is work to send, which is the
                // truth, and is what makes the next attempt meaningful — nothing has to be
                // recorded either way, because the count is asked of the repository.
                //
                // **Ordinary push, not a derived tree.** There used to be a third mechanism here:
                // "unsync" left a file tracked and derived a commit without it, which could only be
                // delivered by force because that commit was on no local branch. The operator replaced
                // it with classic gitignore — "a simple tracked/untracked switch" — so what is on this
                // phone's `main` and what is on GitHub's are now the same history.
                //
                // **And nothing has to be uploaded first any more.** A picture used to be a pointer in
                // the tree with its bytes in a separate store, so the bytes had to arrive before the
                // ref moved or GitHub refused the push (GH008). Every file is in the commit now, so a
                // push is a push.
                GitService.push(it, token, gitUser = user)
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
     * Answers the same shape as [sync], because the callers say the same kind of sentence.
     */
    fun push(project: String, message: String = ""): GitService.RemoteResult =
        RepoLocks.hold(repoDir(project), "push") {
            val repo = open(project) ?: return@hold GitService.RemoteResult(
                "This project has no repository yet", false
            )
            repo.use {
                try {
                    // **The commit happens here, because a save only stages now.**
                    //
                    // The operator's decision (2026-11-10): *"we dont commit on save, we commit on
                    // push, on save changes are only staged."* So the staged work becomes one version
                    // with the message somebody wrote — the person's, or the agent's — and a push is
                    // the moment a version is made.
                    //
                    // **A blank message is still a commit here, and it names what changed.** The tool
                    // refuses a blank message when work is staged, because the version's reason is the
                    // one thing only its writer knows; but this function is also reached by the Sync
                    // button, where "no note" is an ordinary thing to press and the file names are the
                    // honest sentence. A push with nothing staged commits nothing.
                    val staged = GitService.stagedPaths(it)
                    if (staged.isNotEmpty()) {
                        val made = GitService.commitEverything(
                            it,
                            // **A blank message here is unreachable from the tool** (it refuses
                            // first) and means "the person pressed Sync with no note", where naming
                            // the files is the honest sentence.
                            message.trim().ifBlank { CommitMessage.sync(staged) },
                            authorProvider(),
                            // Inside `RepoLocks.hold`, so a lock failure is a corpse, not a rival.
                            inFlight = false,
                        )
                        // **Nothing is pushed over a commit that did not happen.** Pushing now would
                        // send a tree missing exactly the work the person was trying to save, and
                        // report the push as done.
                        if (!made.ok) {
                            // **`return@use`, not `return`.** This block is a lambda argument to
                            // `repo.use`, and a bare `return` inside it is not allowed — the same trap
                            // that makes a `return@hold` inside `RepoLocks.hold` necessary.
                            return@use GitService.RemoteResult(
                                "Nothing was pushed, because the changes could not be committed: " +
                                    made.error,
                                false,
                            )
                        }
                    }
                    val remote = remoteUrlOf(it)
                    val (token, user) = when (val answer = credentialFor(project, remote)) {
                        is GitCredentials.Answer.Say -> return@use GitService.RemoteResult(
                            answer.why,
                            false,
                        )
                        is GitCredentials.Answer.Use -> answer.token to userOf(answer)
                        GitCredentials.Answer.None -> null to gitUser()
                    }
                    GitService.push(it, token, gitUser = user)
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
                    val (token, user) = when (val answer = credentialFor(project, remote)) {
                        is GitCredentials.Answer.Say -> return@use Pulled(false, said = answer.why)
                        is GitCredentials.Answer.Use -> answer.token to userOf(answer)
                        GitCredentials.Answer.None -> null to gitUser()
                    }
                    GitService.fetch(it, token, user)
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
                if ((GitService.stagedSize(it, path) ?: 0) > PREVIEW_LIMIT ||
                    (GitService.blobSize(it, sent, path) ?: 0) > PREVIEW_LIMIT
                ) {
                    return Result.failure(IllegalStateException("too big to preview"))
                }
                // **The index, not `HEAD`.** A save stages and a push commits (2026-11-10), so the
                // work this window lists is in the index and nowhere else — a preview built from
                // `HEAD` compared the last push with itself and drew nothing, for every change.
                val before = GitService.blobText(it, sent, path).orEmpty()
                val after = GitService.stagedText(it, path).orEmpty()
                // **The same two sides the lines come from.** Asking `HEAD` here would have judged a
                // different version than the one being previewed.
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
    /**
     * **A release: a tag at `HEAD`, sent to the remote.**
     *
     * The universal replacement for "dispatch a release workflow". What a host builds from a tag is
     * the host's business; that the version exists, points at a commit and can be fetched is git's, and
     * it works everywhere.
     *
     * **Held under the repository's lock**, because it writes a ref — the same reason a commit is.
     */
    fun tag(project: String, name: String, message: String? = null): GitService.RemoteResult {
        val clean = name.trim()
        if (clean.isEmpty()) {
            return GitService.RemoteResult("A tag needs a name: a version, like 1.4.0", false)
        }
        return RepoLocks.hold(repoDir(project), "tag") {
            val repo = open(project)
                ?: return@hold GitService.RemoteResult("This project has no repository yet", false)
            repo.use {
                val remote = remoteUrlOf(it)
                if (remote.isNullOrBlank()) {
                    return@use GitService.RemoteResult(
                        "This project has no address, so a tag would stay on this phone. " +
                            "Add one first if the version is meant to go anywhere.",
                        false,
                    )
                }
                // **The same decision as a push**, because it is the same push: a tag's ref goes to
                // the same host with the same key, and a tag refused by a server is no better than a
                // commit refused by one.
                val (token, user) = when (val answer = credentialFor(project, remote)) {
                    is GitCredentials.Answer.Say -> return@use GitService.RemoteResult(answer.why, false)
                    is GitCredentials.Answer.Use -> answer.token to userOf(answer)
                    GitCredentials.Answer.None -> null to gitUser()
                }
                GitService.tagAndPush(it, clean, message, token, user)
            }
        }
    }

    /**
     * **Can this address be cloned, with this account's key** — asked before anything is made.
     *
     * The same three questions a clone answers, in the order that costs least: can this app even speak
     * to it (`Secrets.transportProblem`), may this key go to that host (`[credentialFor]`), and does the
     * host accept it (`GitService.lsRemote`). A person who mistyped a path, or pasted a key that was
     * revoked, gets a sentence instead of a half-made project and a folder to delete.
     */
    fun checkRemote(project: String, remoteUrl: String): GitService.RemoteAnswer {
        Secrets.transportProblem(remoteUrl)?.let { return GitService.RemoteAnswer.Refused(it) }
        val (token, user) = when (val answer = credentialFor(project, remoteUrl)) {
            is GitCredentials.Answer.Say -> return GitService.RemoteAnswer.Refused(answer.why)
            is GitCredentials.Answer.Use -> answer.token to userOf(answer)
            GitCredentials.Answer.None -> null to gitUser()
        }
        return GitService.lsRemote(remoteUrl, token, user)
    }

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
        val (token, user) = when (val answer = credentialFor(project, remoteUrl)) {
            is GitCredentials.Answer.Say -> return GitService.RemoteResult(answer.why, false)
            is GitCredentials.Answer.Use -> answer.token to userOf(answer)
            GitCredentials.Answer.None -> null to gitUser()
        }
        val result = GitService.clone(
            remoteUrl,
            dir,
            authorProvider(),
            token,
            deadline,
            user,
        )
        if (result.success) {
            open(project)?.use { repo ->
                GitService.scrubRemoteUrls(repo)
            }
            workspace.ensureIgnore(dir)
            workspace.ensureSecretIgnores(dir)
        }
        return result
    }

    /**
     * **Points the project at [url]** — the one act that gives a project an address.
     *
     * There is no opposite any more (2026-11-10): every project has a remote, so the app has no
     * "go local" and no "keep local", and `GitService.removeRemote` has no caller. A project whose
     * host has moved is repointed by this same call, which is why the screen says "Set the
     * address…" rather than "Publish".
     */
    fun setRemote(project: String, url: String): Boolean {
        val repo = open(project) ?: return false
        return try {
            repo.use { GitService.setRemoteUrl(it, url) }
            true
        } catch (e: Throwable) {
            lastError = Secrets.describe(e)
            false
        }
    }

    /** The project's remote, or null when it has none — which is now a fault, not a state. */
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
    /**
     * **Puts the whole project back to one revision**, refusing over work that is in no version.
     *
     * The refusal is the point. A whole-tree checkout **destroys** uncommitted changes — they are not
     * in any version, so nothing can bring them back — and the app's rule everywhere else is that a
     * loss needs an asking. `keep` governs the *index* (whether the fresh stage survives the
     * checkout), not the person's unsaved bytes: a flag that let a checkout throw those away would be
     * the one irreversible act in the tool list.
     *
     * **Uncommitted means unstaged and staged**, both. Staged work is in no version either — a save
     * only stages here, and a version is made by a push.
     */
    fun restoreTree(
        project: String,
        revisionId: String,
        keepStage: Boolean = false,
        deadline: Deadline? = null,
    ): Result<Unit> = RepoLocks.hold(repoDir(project), "restore") {
        val repo = open(project) ?: return@hold Result.failure(
            IllegalStateException("This project has no repository yet")
        )
        return@hold repo.use {
            try {
                // **Read before the checkout, never after.** Afterwards the count would be
                // reporting the restore, which is always a whole tree.
                val unsent = try {
                    changes(project).total
                } catch (t: Throwable) {
                    0
                }
                if (unsent > 0) {
                    return@hold Result.failure(
                        IllegalStateException(
                            "This project has $unsent change(s) that are in no version, and putting " +
                                "the whole tree back would destroy them. `push` first, or name one " +
                                "`path` to restore on its own.",
                        ),
                    )
                }
                if (deadline?.expired == true) throw StoppedException(Stopped.body("restore", deadline))
                GitService.checkoutTree(it, revisionId, keepStage)
                Result.success(Unit)
            } catch (e: StoppedException) {
                Result.failure(e)
            } catch (e: Throwable) {
                Result.failure(e)
            }
        }
    }

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
