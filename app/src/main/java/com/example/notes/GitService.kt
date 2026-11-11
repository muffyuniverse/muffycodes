package com.example.notes

import org.eclipse.jgit.api.CreateBranchCommand
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.Status
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.util.io.NullOutputStream
import org.eclipse.jgit.lib.AnyObjectId
import org.eclipse.jgit.lib.FileMode
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Ref
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevTree
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.treewalk.AbstractTreeIterator
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.treewalk.EmptyTreeIterator
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilterGroup
import java.io.File

/**
 * A thin, app-friendly wrapper over JGit — the pure-Java git implementation.
 *
 * Everything the app needs from git goes through here: initialising a repository,
 * staging and committing changes, pushing and pulling, walking the history of a
 * file or a folder, and reading an old version back.
 *
 * The class holds no Android dependencies beyond the file system, so every rule
 * can be exercised by the JVM unit tests.
 */
object GitService {

    /**
     * Where a remote branch's copy is kept, as git names it.
     *
     * **One string, because it is built and parsed in the same breath.** "N to send" compares `HEAD`
     * with `refs/remotes/origin/<branch>`, the pull merges from it, and the branch list strips it
     * back off — a typo in one of those would be a count against nothing or a branch named with a
     * path in front of it. `ProjectGit` reads it from here for the same reason.
     */
    const val ORIGIN = "refs/remotes/origin/"

    /**
     * How many versions a reader may return.
     *
     * Not a limit on the history: how far back a person can go is the setting in
     * §14 and nothing else. This is only the ceiling that stops a corrupted
     * repository from filling memory — high enough that a reader never becomes the
     * thing that decides how long the history is, which is how a shortened history
     * came to look unshortened.
     */
    const val READ_ALL = 10_000

    /**
     * How many changed files one `diff` answers with.
     *
     * **A bound on the answer, not on the walk.** A commit that touched a thousand files — an
     * unpack, a vendor drop — would otherwise fill a conversation with one call. Forty is enough to
     * see the shape of a change, and the answer says when there were more.
     */
    const val DIFF_FILES = 40

    /**
     * How long `ls-remote` may take, in seconds.
     *
     * **A number rather than no limit**, because this runs while a person waits on a window with a
     * button in it: an address that never answers must become a sentence, not a spinner. Ten seconds
     * is longer than any server that is going to answer takes to say hello, and short enough that a
     * person who mistyped a host is told so while they still remember typing it.
     */
    const val LS_REMOTE_TIMEOUT = 10

    /** A commit in the history. */
    data class CommitInfo(
        val id: String,
        val shortId: String,
        val message: String,
        val author: String,
        val authorEmail: String,
        val timestamp: Long,
    )

    /**
     * What a project has that GitHub does not.
     *
     * **Asked as three questions, not one.** [staged], [unstaged] and [untracked] are git's own
     * view — files not yet committed — and since 2026-11-10 a save leaves its work in [staged]
     * rather than committing it: the commit happens at the push. What a person means by "changed"
     * is still [ahead], work committed here and never pushed. A row built from [ahead] alone read
     * `changed 0` with the work unsent, so Sync stayed grey and the file went nowhere.
     *
     * **[ahead] counts paths, not commits.** A commit count said "1 to send" for a batch that moved
     * ten files. It was kept in a file of its own because the history is *rebuilt* when it is
     * shortened — every id changes, so anything that remembered a commit was stale after the next
     * sync — and a diff between two trees is the answer to that: commits are renamed by a rewrite,
     * their contents are not (see [unsentPaths]).
     */
    data class Changes(
        val staged: Int,
        val unstaged: Int,
        val untracked: Int,
        val ahead: Int = 0,
    ) {
        val total: Int get() = staged + unstaged + untracked + ahead
        val isClean: Boolean get() = total == 0
    }

    /**
     * One line of a file as it differs from the last push.
     *
     * **Three kinds and no fourth.** A person reading a preview wants to know what changed and what
     * did not, and git's own vocabulary (context, hunk, `\ No newline`) is about a patch file rather
     * than about reading. [SAME] lines are shown in the theme's own text colour, which is what makes
     * the other two mean something.
     */
    data class DiffLine(val kind: Kind, val text: String) {
        enum class Kind { SAME, ADDED, REMOVED }
    }

    /** The result of a push or pull. */
    data class RemoteResult(
        val message: String,
        val success: Boolean,
    )

    /**
     * **What a remote answers about itself, without cloning it.**
     *
     * This is the one question the git protocol can be asked about an address: `ls-remote` returns the
     * refs a server is willing to hand out, which answers "can this key read this repository" on every
     * host that speaks git — GitHub, GitLab, Gitea, a bare folder, a server in somebody's basement.
     * There is no other verb for it, and no host-specific endpoint worth special-casing.
     */
    sealed interface RemoteAnswer {
        /** The branch names it reported, which is proof the address and the key both work. */
        data class Branches(val names: List<String>) : RemoteAnswer

        /** **It could not be read, and this is why** — in words a person can act on. */
        data class Refused(val why: String) : RemoteAnswer
    }

    // ---- repository lifecycle ---------------------------------------------

    /** True when [dir] holds a git repository. */
    /**
     * True when [dir] has a repository — **including a linked worktree, whose `.git` is a file.**
     *
     * `git worktree add` writes `gitdir: …` into a plain file where a normal repository has a
     * directory. Testing only `isDirectory` answered "no" for every worktree, which made
     * [RepoLocks.keyOf] fall back to the worktree's own path — and so the lock that is supposed to be
     * shared would have been one per branch, the exact bug §13d is about.
     */
    fun isRepository(dir: File): Boolean {
        val dotGit = File(dir, ".git")
        return dotGit.isDirectory || dotGit.isFile
    }

    /**
     * Creates a repository in [dir] (or opens the one already there).
     * The repository starts with one empty commit so that it has a history to
     * browse even before anything is written.
     */
    /**
     * Opens the repository in [dir] **without creating anything**, or null.
     *
     * The read-only half of [initOrOpen], for a caller that must not change the project it is looking
     * at — a walk of the tree above all: opening a repository must not initialise one, because that
     * would give a project with no history a history, and commit whatever it found while looking.
     *
     * **`findGitDir`, for the same reason [initOrOpen] uses it.** A linked worktree's `.git` is a file
     * holding `gitdir: …`, and `setGitDir` pointed at that file builds a repository that is silently
     * empty — measured on 7.8.1: `getBranch()` null, `resolve("HEAD")` null, zero branches, and **no
     * exception thrown**. It answered a repository, so callers believed it. Every reader of a working
     * tree in a branch is where that would have hurt: each would have found nothing and said nothing.
     */
    fun openExisting(dir: File): Repository? {
        if (!isRepository(dir)) return null
        return try {
            FileRepositoryBuilder().findGitDir(dir).setWorkTree(dir).build()
        } catch (e: Throwable) {
            null
        }
    }

    fun initOrOpen(dir: File, author: GitAuthor): Repository {
        dir.mkdirs()
        val gitDir = File(dir, ".git")

        // **A `.git` FILE means this is a linked worktree, and it must not be initialised.**
        // `git worktree add` writes `gitdir: <common>/.git/worktrees/<id>` into a plain file where a
        // normal repository has a directory. `Git.init()` on such a folder fails with "Creating
        // directories for …/.git failed" — measured — and the old `!gitDir.isDirectory` test sent
        // every worktree down exactly that path. §13d is why this matters: a project's branches are
        // about to be worktrees.
        val linked = gitDir.isFile
        if (!gitDir.isDirectory && !linked) {
            Git.init()
                .setDirectory(dir)
                .setInitialBranch("main")
                .call()
                .close()
        }

        // **`findGitDir` for a worktree**, because it is what reads the `commondir` file and so
        // reaches the shared refs and objects. Setting the git dir to the private
        // `.git/worktrees/<id>` would build a repository with no branches at all — that is the
        // 6.10.1 behaviour, and it is still wrong to ask for even on 7.x.
        val repo = if (linked) {
            FileRepositoryBuilder().findGitDir(dir).setWorkTree(dir).build()
        } else {
            FileRepositoryBuilder().setGitDir(gitDir).setWorkTree(dir).build()
        }
        try {
            // A repository that has never been committed to has no HEAD yet.
            // (HEAD is a symbolic reference, so it only shows up once there is
            // a commit behind it.)
            if (repo.resolve("HEAD") == null) {
                Git(repo).use { git ->
                    // **Whatever is already on disk goes in the first commit.** The caller wrote
                    // the app's exclude list once the repository exists, so that the first
                    // commit already ignores `agent/` and `.muffy/` — and committing an *empty*
                    // first commit instead left the ignore file untracked, which made a project
                    // that had just been made say "1 changed" before anybody touched it.
                    stageEverything(git)
                    git.commit()
                        .setMessage("Initial commit")
                        .setAuthor(author.ident())
                        .setCommitter(author.ident())
                        .setAllowEmpty(true)
                        .call()
                }
            }
        } catch (e: Throwable) {
            repo.close()
            throw e
        }
        return repo
    }

    /**
     * Clones [remoteUrl] into [dir].
     *
     * The key is handed to git as credentials and **never** as part of the
     * address: an address is written into `.git/config`, repeated in messages
     * and kept in logs, and every one of those is a place a key escapes from.
     * Credentials are only attached for GitHub; anything else is cloned without
     * them.
     */
    /**
     * Clones into [dir] — **into a partial folder first, and moved into place only when it worked.**
     *
     * This is what lets a stopped clone say "nothing was kept" and mean it. JGit writes the pack and
     * then the working tree as it goes; a fetch abandoned half way used to leave a directory with a
     * `.git` in it that Git could not open and the app could not list. Building beside the target and
     * renaming at the end makes the whole clone one move: it is there or it is not.
     *
     * The [deadline] reaches JGit as a `ProgressMonitor`, which is checked between objects — a real
     * stop, not an abandoned thread. Null means no limit, which is what every caller that is not the
     * agent wants: a person cloning from the add window is not on a clock.
     */
    fun clone(
        remoteUrl: String,
        dir: File,
        author: GitAuthor,
        token: String? = null,
        deadline: Deadline? = null,
        gitUser: String? = null,
    ): RemoteResult {
        // **The transport, before the credential.** This app packages no SSH, so a `git@host:path`
        // address fails deep inside JGit with a null-shaped error naming nothing — and it *looks*
        // usable right up to that point, because `hostOf` reads a host out of the scp form quite
        // happily. Refused in words, and before anything is opened, because the request is the harm.
        Secrets.transportProblem(remoteUrl)?.let { return RemoteResult(it, false) }
        // **Which key, and whether it belongs here, is not this function's question any more.**
        //
        // It used to be: a guard here refused any non-GitHub address whenever a key was present, so a
        // private repository on another host went out unauthenticated, the server answered 401, and
        // the person was told their credentials were wrong about a key the app had withheld. The rule
        // now lives in [GitCredentials], which is asked by the caller that knows the account — one
        // decision for all four ways a URL is used (clone, add-remote, pull, push) instead of a
        // GitHub-shaped copy in each of them. What arrives here is already decided, and a null token
        // means "nothing to send" rather than "not allowed to send".
        val partial = File(dir.parentFile, dir.name + ".partial")
        return try {
            dir.parentFile?.mkdirs()
            partial.deleteRecursively()
            partial.mkdirs()
            val command = Git.cloneRepository()
                .setURI(remoteUrl)
                .setDirectory(partial)
                .setCloneAllBranches(true)
            deadline?.let { command.setProgressMonitor(it.monitor()) }
            // **Guarded above, in words, before anything is opened** — `credentials` no longer decides
            // whether this token belongs here, so this call must not be the first place it is asked.
            credentials(remoteUrl, token, gitUser)?.let { command.setCredentialsProvider(it) }
            val git = command.call()
            git.use {
                // The clone inherits the remote's author; make the local identity ours.
                it.repository.config.apply {
                    setString("user", null, "name", author.name)
                    setString("user", null, "email", author.email)
                    save()
                }
            }
            if (deadline?.expired == true) {
                // The clone finished as the time ran out. Nothing was asked for any more, so nothing
                // is kept — the same rule a clone stopped half way follows.
                partial.deleteRecursively()
                return RemoteResult(Stopped.body("clone", deadline), false)
            }
            if (!partial.renameTo(dir)) {
                partial.deleteRecursively()
                return RemoteResult("Could not move the clone into this project's folder", false)
            }
            RemoteResult("Cloned ${Secrets.scrub(remoteUrl)}", true)
        } catch (e: Throwable) {
            partial.deleteRecursively()
            // A cancelled fetch throws out of JGit rather than returning; the deadline is what tells
            // the two apart, because "it was stopped" is a different answer from "it failed".
            if (deadline?.expired == true) {
                return RemoteResult(Stopped.body("clone", deadline), false)
            }
            RemoteResult(Secrets.scrub(e.message ?: "Could not clone"), false)
        }
    }

    // ---- committing --------------------------------------------------------

    /**
     * Stages every change in the working tree.
     *
     * Two passes are needed. A plain add stages new and modified files but
     * leaves deletions behind; an add with [org.eclipse.jgit.api.AddCommand.setUpdate]
     * stages deletions but ignores files it does not know yet. Doing both means
     * everything ends up staged.
     */
    private fun stageEverything(git: Git) {
        excludeTemporaries(git.repository)
        git.add().addFilepattern(".").call()
        git.add().addFilepattern(".").setUpdate(true).call()
    }

    /**
     * Tells this repository to ignore the app's own half-written files — **and never a person's.**
     *
     * A file is written beside itself and renamed over, so between the write and the rename there is
     * a real file in the project. `git add .` cannot tell it from content, and a commit that ran in
     * that window would record a file the rename then deleted: a phantom "deleted file" in the
     * person's history, for ever, offering to restore something they never had.
     *
     * **`.git/info/exclude`, not `.gitignore`.** An ignore line in `.gitignore` is content: it would
     * be committed, pushed to GitHub, and appear in the person's change list as a file they did not
     * write. `info/exclude` is git's own place for a local ignore, it lives inside `.git` where the
     * app already owns the bytes, and it is not part of any tree.
     *
     * Written on the way into every commit rather than once at init, because a project made before
     * this existed has no line and would keep the hazard. The read is of a file measured in bytes,
     * next to a stage-everything that walks the tree.
     */
    private fun excludeTemporaries(repo: Repository) {
        try {
            val info = File(repo.directory, "info").apply { mkdirs() }
            val exclude = File(info, "exclude")
            val line = "${Beside.TEMP_PREFIX}*"
            val was = if (exclude.isFile) exclude.readText() else ""
            if (was.lineSequence().any { it.trim() == line }) return
            val now = if (was.isEmpty() || was.endsWith("\n")) was + line + "\n" else was + "\n" + line + "\n"
            exclude.writeText(now)
        } catch (t: Throwable) {
            // **A repository that cannot be told still works.** The rename deletes the temporary a
            // moment later, so the failure mode is a phantom file in one listing rather than a lost
            // one - and a commit that failed because a hint could not be written would be worse than
            // the hint.
        }
    }

    /**
     * Stops tracking [path], **leaving the file where it is.**
     *
     * ## Why an ignore line is not enough, and this is the whole reason this exists
     *
     * `.gitignore` is not a commit filter; it is an *untracked* filter. It stops git from
     * beginning to track a path and does nothing at all to a path already in the repository —
     * verified against a real repository: a tracked file, ignored, edited, committed, still had
     * its edit in the commit. Git has no "commit but do not push" mechanism either: a commit is
     * a whole tree, so a commit without a file is a *different* commit, and "synced" cannot mean
     * "the phone's commit minus a file".
     *
     * So "stop sending this" has to mean "take it out of the repository", and that is this:
     * `git rm --cached`, which unstages and untracks while the working copy stays on the disk,
     * editable and untouched.
     *
     * Without this, "Add to .gitignore" wrote a line, committed it, said "Ignoring x" — and the
     * file went on being committed and pushed. Every file a person has saved is already tracked,
     * because a save commits, so the button was wrong for exactly the files people use it on.
     *
     * @return true when the path is no longer tracked afterwards — including when it never was.
     */
    fun untrack(repo: Repository, path: String): Boolean = try {
        Git(repo).use { git ->
            git.rm().addFilepattern(path).setCached(true).call()
        }
        // **The index is asked, because the index is what "tracked" means.** A status report has
        // an entry for every kind of change and reading the right one out of it is a guess; a path
        // with no index entry cannot be committed, and that is the whole question.
        repo.readDirCache().getEntry(path) == null
    } catch (t: Throwable) {
        false
    }

    /**
     * Puts [path] back in the index, so it is staged now and sent by the next push.
     *
     * **The file on disk is the truth.** Tracking again is a person saying "send this", and what is
     * on the phone is what to send — not whatever version a history walk would find. A path that is
     * not on disk is not added, because there is nothing to add; the caller learns that from `false`.
     */
    fun track(repo: Repository, path: String): Boolean = try {
        Git(repo).use { git ->
            git.add().addFilepattern(path.trimEnd('/')).call()
        }
        repo.readDirCache().getEntry(path.trimEnd('/')) != null
    } catch (t: Throwable) {
        false
    }

    /**
     * Whether git would ignore [path] — **asked of git, not of a flag.**
     *
     * `isIgnored` is the same check the working tree uses, so a path matched by a pattern the app
     * never wrote (a `build/` line, a `*.log`) answers honestly. A second list of ignored paths kept
     * by the app would be a second answer that can disagree with the one that decides what is sent.
     */
    /**
     * Fetches `origin`, leaving the working tree alone.
     *
     * It only learns what GitHub has. A pull needs exactly that — deciding what to do with the
     * answer is the merge's job, not the fetch's.
     */
    /**
     * **Asks a remote what branches it has, without cloning it.**
     *
     * The check that makes adding a project by address honest: the address is real, and the key is one
     * the host accepts — established *before* a folder is made, so a mistyped path or a revoked key is a
     * sentence rather than a half-made project.
     *
     * **The failures are told apart, because they are fixed in different places.** "Unauthorised" means
     * the key (or the account's git username) is wrong; "not found" means the address is; anything else
     * is the network's own words. One sentence for all three would send a person to the wrong fix.
     */
    fun lsRemote(remoteUrl: String, token: String?, gitUser: String? = null): RemoteAnswer {
        Secrets.transportProblem(remoteUrl)?.let { return RemoteAnswer.Refused(it) }
        // **A local address is asked about directly, not through JGit.** `ls-remote` against a path
        // that is not a repository fails deep in the transport with a message about a URI, and the
        // sentence a person needs — "there is no repository at that address" — is one this code can
        // already decide by looking. It also makes the check testable without a network, which is why
        // the local case is worth handling rather than leaving to the general path.
        localMissing(remoteUrl)?.let { return RemoteAnswer.Refused(it) }
        return try {
            val command = Git.lsRemoteRepository()
                .setRemote(remoteUrl)
                .setHeads(true)
                .setTags(false)
                .setTimeout(LS_REMOTE_TIMEOUT)
            credentials(remoteUrl, token, gitUser)?.let { command.setCredentialsProvider(it) }
            val refs = command.call()
            val names = refs
                .mapNotNull { it.name }
                .filter { it.startsWith("refs/heads/") }
                .map { it.removePrefix("refs/heads/") }
                .sorted()
            RemoteAnswer.Branches(names)
        } catch (e: Throwable) {
            RemoteAnswer.Refused(remoteSaid(Secrets.describe(e), Secrets.hostOf(remoteUrl)))
        }
    }

    /**
     * The sentence for a local address that holds no repository, or null for anything else.
     *
     * **A local path is a remote like any other** — it is how a folder-to-folder clone works, and how
     * this app's own tests talk to a "server" — so it is checked like one rather than special-cased
     * out of the check.
     */
    private fun localMissing(url: String): String? {
        if (Secrets.hostOf(url).isNotEmpty()) return null
        val path = url.trim().removePrefix("file:")
        if (path.isEmpty()) return null
        val dir = File(path)
        val looksLikeRepository = isRepository(dir) ||
            // A bare repository: no `.git`, and the three names every repository root has.
            (dir.isDirectory && File(dir, "HEAD").isFile && File(dir, "objects").isDirectory)
        return if (looksLikeRepository) {
            null
        } else {
            "There is no repository at that address."
        }
    }

    /**
     * JGit's own words, turned into the three things that are actually wrong.
     *
     * **`Secrets.describe` first**, so no token can reach the sentence: JGit repeats the URI it was
     * given, and a URI may carry one.
     */
    private fun remoteSaid(said: String, host: String): String {
        val lower = said.lowercase()
        val where = host.ifBlank { "the server" }
        return when {
            // The key, or how it was presented. Both are fixed in the account, not on the project.
            lower.contains("not authorized") || lower.contains("401") ||
                lower.contains("authentication") || lower.contains("403") ||
                lower.contains("forbidden") ->
                "$where refused the key. Check that the key is for $where, and that its " +
                    "scopes allow reading this repository."
            // The address. Nothing about a key can fix a path that does not exist.
            lower.contains("404") || lower.contains("not found") ||
                lower.contains("repository not found") ->
                "There is no repository at that address on $where."
            else -> "$where could not be reached: $said"
        }
    }

    fun fetch(repo: Repository, token: String?, gitUser: String? = null) {
        Git(repo).use { git ->
            val fetch = git.fetch().setRemote("origin")
            credentials(remoteOf(repo), token, gitUser)?.let { fetch.setCredentialsProvider(it) }
            fetch.call()
        }
    }

    /**
     * True when [ancestor] is reachable from [descendant] — the question "can this be a
     * fast-forward".
     */
    fun isAncestor(repo: Repository, ancestor: AnyObjectId, descendant: AnyObjectId): Boolean =
        try {
            RevWalk(repo).use { walk ->
                walk.isMergedInto(walk.parseCommit(ancestor), walk.parseCommit(descendant))
            }
        } catch (e: Throwable) {
            false
        }

    /** What a merge did. */
    data class Merged(
        val ok: Boolean,
        val paths: List<String> = emptyList(),
        val conflicted: List<String> = emptyList(),
        val said: String = "",
    )

    /**
     * Merges [their] into the current branch, committing the result.
     *
     * **A merge commit, not a rebase.** A rebase would give every one of this phone's commits a new
     * id; this app does not rewrite history, and an agent doing it to somebody's work is the worst
     * way for that rule to break. A merge commit is visible and says what happened.
     *
     * **A conflict is answered, not thrown.** The conflicted paths are the answer the caller needs:
     * resolution is ordinary editing — read the markers, write the file — so a tool for it would be
     * a second way to edit. Nothing is committed on a conflict, so the tree stays exactly as git left
     * it with the markers in place, which is what `read` will show.
     *
     * **The merge commit carries the repository's configured identity**, because JGit's `MergeCommand`
     * takes no author and there is no honest way to pass one. That identity is set when the project
     * is made ([ProjectGit] writes it), so the commit is attributed the same way every other commit
     * on this phone is — which is the answer we would have given the parameter anyway.
     */
    fun merge(repo: Repository, their: AnyObjectId): Merged =
        try {
            Git(repo).use { git ->
                val result = git.merge()
                    .include(their)
                    .setCommit(true)
                    .setMessage("Merge what the remote had")
                    .call()
                val status = result.mergeStatus
                if (status == org.eclipse.jgit.api.MergeResult.MergeStatus.CONFLICTING) {
                    Merged(
                        ok = false,
                        conflicted = result.conflicts.keys.toList(),
                        said = "The merge conflicts in " +
                            result.conflicts.keys.joinToString(", ") +
                            ". Read the file, choose what it should say, write it, then push.",
                    )
                } else if (result.mergeStatus.isSuccessful) {
                    Merged(
                        ok = true,
                        // **`mergedCommits` are object ids, not commits** — the walk has to parse
                        // them, which is what `changedPathsAgainstParent` does.
                        paths = result.mergedCommits.flatMap { id: ObjectId ->
                            changedPathsAgainstParent(repo, id)
                        }.distinct(),
                        said = "Merged",
                    )
                } else {
                    Merged(false, said = "The merge did not finish: $status")
                }
            }
        } catch (e: Throwable) {
            Merged(false, said = Secrets.describe(e))
        }

    /** The paths one commit changed against its first parent, or none for a root commit. */
    private fun changedPathsAgainstParent(repo: Repository, id: ObjectId): List<String> =
        try {
            RevWalk(repo).use { walk ->
                val parsed = walk.parseCommit(id)
                val parent = parsed.parents.firstOrNull()
                val old: AbstractTreeIterator = parent?.let { treeOf(repo, it) } ?: EmptyTreeIterator()
                val out = mutableListOf<String>()
                DiffFormatter(DISCARD).use { diff ->
                    diff.setRepository(repo)
                    for (entry in diff.scan(old, treeOf(repo, parsed) ?: return emptyList())) {
                        out += entry.newPath ?: entry.oldPath ?: continue
                    }
                }
                out
            }
        } catch (e: Throwable) {
            emptyList()
        }

    /**
     * Every path git ignores, as repository-relative paths.
     *
     * **One status call for the whole project**, because the row asks about every entry on screen and
     * a call per row would walk the working tree once per file. The caller maps them onto the files
     * it is drawing.
     */
    fun ignoredPaths(repo: Repository): List<String> = try {
        Git(repo).use { git -> git.status().call().ignoredNotInIndex.map { it.trimEnd('/') } }
    } catch (t: Throwable) {
        emptyList()
    }

    fun isIgnored(repo: Repository, path: String): Boolean = try {
        Git(repo).use { git ->
            // **`ignoredNotInIndex`, not "ignored".** JGit has no plain `ignored` set; this one is
            // exactly the question being asked — a path matched by an ignore rule *and* absent from
            // the index, which is the state the button produces and the state that stops it being
            // sent. A path that is ignored by a rule but still tracked is deliberately NOT counted:
            // git keeps committing it, so calling it ignored would be a lie the row tells.
            git.status().call().ignoredNotInIndex.any { it.trimEnd('/') == path.trimEnd('/') }
        }
    } catch (t: Throwable) {
        false
    }

    /**
     * Whether [path] was ever **pushed** — that is, whether GitHub has a copy of it.
     *
     * **Asked of the remote-tracking ref, not of `HEAD`.** `refs/remotes/origin/<branch>` is git's
     * own record of what the remote holds, so a path in that history is a path that left this phone.
     * The local history is not the same question: a file saved and never synced is in `HEAD` and not
     * on GitHub, and telling a person "its earlier versions stay on GitHub" about a file that never
     * got there would be a false warning on the one screen where they are trying to be careful.
     *
     * **The walk is bounded by the branch tip, not by every ref.** A stash or a dangling commit is
     * not something GitHub was told about.
     */
    /**
     * **The commit the remote last held** — the other end of the editor's default diff.
     *
     * The question "what has changed since I last synced" needs two commits, and this is the
     * interesting one: `HEAD` is where the file is now, and this is where GitHub last saw it. The
     * pair is the default because it answers the question a person actually asks when they open a
     * diff — not "what changed in this file ever", but "what am I about to send".
     *
     * **Null when nothing was ever pushed**, which is a different answer from "the same as HEAD":
     * a file whose whole history is local has no synced version, and a diff against nothing is
     * every line added. The caller says that rather than pretending there is a comparison.
     */
    fun lastSynced(repo: Repository): String? = try {
        repo.findRef("refs/remotes/origin/${repo.branch}")?.objectId?.name
    } catch (t: Throwable) {
        null
    }

    fun wasPushed(repo: Repository, path: String): Boolean = try {
        val remoteRef = repo.findRef("refs/remotes/origin/${repo.branch}") ?: return false
        RevWalk(repo).use { walker ->
            walker.markStart(walker.parseCommit(remoteRef.objectId))
            // **Does this path exist in any commit the remote holds?** That is the question, and
            // `TreeWalk.forPath` answers it for one commit without a diff: a non-null walk means the
            // path was there. No filter, no patch, no list of every file in every commit.
            walker.any { commit -> TreeWalk.forPath(repo, path, commit.tree) != null }
        }
    } catch (t: Throwable) {
        // **A question that cannot be answered is answered "no".** The warning is the cautious
        // sentence; refusing to ignore a file because the repository could not be read would be a
        // worse failure than a warning that does not appear.
        false
    }

    /**
     * What became of an attempt to write a change into a repository.
     *
     * Three outcomes, not two. Returning `String?` — an id, or null — made "there was
     * nothing to commit" and "the commit could not be written" the same answer, and
     * the caller treated both as success. That is how a save refused by a stale git
     * lock went into the editor as saved, greyed Save out, and left no version behind:
     * the history was right, and the app had said nothing at all.
     */
    data class CommitOutcome(val id: String?, val error: String?) {
        /** True when nothing went wrong — whether or not there was anything to do. */
        val ok: Boolean get() = error == null

        /** True when a new version was actually written. */
        val written: Boolean get() = id != null

        companion object {
            fun wrote(id: String) = CommitOutcome(id, null)
            val nothing = CommitOutcome(null, null)
            fun failed(said: String) = CommitOutcome(null, said)
        }
    }

    /**
     * True when JGit could not take a lock it needs in order to write.
     *
     * What is thrown is usually `JGitInternalException("Exception caught during
     * execution of add command")` with a `LockFailedException` underneath, and the
     * sentence about the lock is the marker that survives every wrapper JGit adds.
     */
    fun isLockFailure(error: Throwable): Boolean =
        generateSequence(error as Throwable?) { it.cause }.any {
            it is org.eclipse.jgit.errors.LockFailedException ||
                it.message.orEmpty().contains("cannot lock", ignoreCase = true) ||
                it.message.orEmpty().contains("lock file", ignoreCase = true)
        }

    /**
     * Runs [block], clearing a lock a killed process left behind and trying once more.
     *
     * **Why this is safe without asking.** A lock failure in this app is, in the ordinary case, the
     * corpse of a process Android killed mid-write: nobody can reach `/data/user/0/…` to delete it,
     * and until it is deleted *every* write in that project fails for ever while a save says
     * nothing. git tells a person to delete the file and try again, which is reasonable in a
     * terminal and unreasonable on a phone.
     *
     * **This used to say the corpse was the only possibility, because "every write is made on one
     * worker thread". That was false** — there are two, the view model's and the agent runner's —
     * so a failure could equally be *another writer's live lock*, and clearing it corrupted the
     * other writer's commit.
     *
     * [inFlight] is the answer, and **its sense is the opposite of what it sounds like**: `true`
     * means *somebody in this process is holding the repository right now*, so a lock failure is
     * **not** a corpse and must be left alone. `false` means the app is not writing, so the lock
     * can only be a corpse and clearing it is what keeps the project usable.
     *
     * **So a caller that holds [RepoLocks] must pass `false`.** Holding the lock means no other
     * writer of ours can be inside — which is precisely the condition that makes a lock failure a
     * corpse again. Passing `true` from inside the hold would disable the recovery the project
     * depends on, and did: it turned "a killed app leaves a lock that blocks every future save"
     * back into a real bug.
     */
    fun <T> retryingLock(
        repo: Repository,
        inFlight: Boolean = false,
        block: () -> T,
    ): T {
        return try {
            block()
        } catch (t: Throwable) {
            if (inFlight || !isLockFailure(t)) throw t
            if (!clearStaleLock(repo)) throw t
            block()
        }
    }

    /**
     * Deletes a lock file nothing can be holding, and says whether the way is clear.
     *
     * `index.lock` is the one JGit takes for every command that writes a working
     * tree's index — add, commit, reset, checkout. Ref locks are left alone: they are
     * rarer, and a wrong guess about one of those loses a reference.
     */
    fun clearStaleLock(repo: Repository): Boolean = try {
        val lock = File(repo.directory, "index.lock")
        !lock.exists() || lock.delete()
    } catch (e: Throwable) {
        false
    }

    /**
     * Stages every change in the working tree, including deletions, and commits
     * it. Returns the new commit id, or null when there was nothing to commit.
     *
     * **Prefer [commitEverything],** which can also say that it failed. This signature
     * is still here for the callers whose only question is the id.
     */
    fun commitAll(
        repo: Repository,
        message: String,
        author: GitAuthor,
        /**
         * True when the caller is **holding the repository's lock** ([RepoLocks]).
         *
         * **This is what makes `retryingLock`'s promise true instead of hopeful.** A lock failure
         * with the repository held can only be the corpse of a killed process, so clearing it is
         * safe. Without the hold, the failure may be another writer's *live* `index.lock` — and
         * deleting it is how a commit ends up writing a tree that does not match its message.
         *
         * It defaults to false so a caller that has not thought about it gets the cautious
         * behaviour, and [ProjectGit] passes true because it holds the lock around the whole
         * sequence.
         */
        inFlight: Boolean = false,
    ): String? {
        return Git(repo).use { git ->
            // A lock left by a killed process is cleared, not reported.
            retryingLock(repo, inFlight) { stageEverything(git) }
            val status = retryingLock(repo, inFlight) { git.status().call() }
            if (status.isClean) {
                null
            } else {
                val commit = retryingLock(repo, inFlight) {
                    git.commit()
                        .setMessage(message.trim().ifEmpty { "Update files" })
                        .setAuthor(author.ident())
                        .setCommitter(author.ident())
                        .setAllowEmpty(false)
                        .call()
                }
                commit.name
            }
        }
    }

    /**
     * **Stages everything, and commits nothing.** The person's save.
     *
     * The operator's decision (2026-11-10): *"make it so that we dont commit on save, we commit on push,
     * on save changes are only staged. and add a commit message arg to push tool."*
     *
     * **Why this is the right shape for this app.** A commit is a *version*, and versions are what the
     * history, the restore and the branch screens read — so an autosave that made one per keystroke
     * filled the history with noise and made "what did I change" a question about the app's timing
     * rather than about the work. Staged changes are the honest middle: the work is recorded in the
     * index (so `changes` can list it, and a push can turn it into one version with a message somebody
     * wrote), and the history holds the versions a person decided on.
     *
     * **The index is where git already keeps this.** `git add` writes the blob and the index entry, so
     * a save that stages is a save whose content is in the object database — an interrupted phone loses
     * the commit, not the text.
     */
    fun stageAll(repo: Repository, inFlight: Boolean = false): Boolean = try {
        Git(repo).use { git ->
            retryingLock(repo, inFlight) { stageEverything(git) }
            true
        }
    } catch (e: Throwable) {
        false
    }

    /**
     * The paths the newest commit changed, against its parent.
     *
     * **The commit's own diff, not the working tree's.** After a commit the working tree is clean, so
     * `git status` says nothing changed — which is the question "is there work to save", not "what did
     * the save just write". A parentless commit is diffed against the empty tree, so its whole contents
     * are its changes.
     *
     * Used by the binary rule: a commit that wrote a picture is the only moment the app knows which
     * path needs its older versions collapsed.
     */
    fun changedPaths(repo: Repository, commitId: AnyObjectId): List<String> {
        return try {
            val commit = RevWalk(repo).use { it.parseCommit(commitId) }
            val older: AbstractTreeIterator = if (commit.parentCount == 0) {
                EmptyTreeIterator()
            } else {
                treeOf(repo, commit.getParent(0).id) ?: EmptyTreeIterator()
            }
            val parser = CanonicalTreeParser()
            repo.newObjectReader().use { reader -> parser.reset(reader, commit.tree.id) }
            val out = mutableListOf<String>()
            DiffFormatter(DISCARD).use { diff ->
                diff.setRepository(repo)
                for (entry in diff.scan(older, parser)) {
                    // **A deletion counts.** It is a change to the path, and a picture that was removed
                    // still has old versions in older trees — which is exactly what a binary must not
                    // keep.
                    val path = entry.newPath.ifEmpty { entry.oldPath }
                    if (path.isNotEmpty()) out += path
                }
            }
            out.distinct()
        } catch (e: Throwable) {
            emptyList()
        }
    }

    /**
     * [commitAll], answering with all three outcomes instead of folding a failure into
     * "nothing to do".
     */
    fun commitEverything(
        repo: Repository,
        message: String,
        author: GitAuthor,
        inFlight: Boolean = false,
    ): CommitOutcome = try {
        val id = commitAll(repo, message, author, inFlight)
        if (id == null) CommitOutcome.nothing else CommitOutcome.wrote(id)
    } catch (e: Throwable) {
        CommitOutcome.failed(Secrets.explain(e, "That change could not be saved"))
    }

    /**
     * A commit that changes nothing and says something.
     *
     * This is the **sync's own commit**: the tree is already what it is, and the
     * message is the person's answer to "what did you change?". Every other commit in
     * a project is the app narrating a file ("Saved", "Created Drafts/"), which is the
     * right message for an undo step and the wrong one for the history on GitHub — there,
     * the question is why a push happened. An empty commit is normally noise, and a
     * design that made one per change would be nothing but; one per sync is a
     * landmark.
     */
    fun commitMarker(repo: Repository, message: String, author: GitAuthor): String? {
        val said = message.trim()
        if (said.isEmpty()) return null
        return Git(repo).use { git ->
            git.commit()
                .setMessage(said)
                .setAuthor(author.ident())
                .setCommitter(author.ident())
                .setAllowEmpty(true)
                .call()
                .name
        }
    }

    /**
     * The paths that differ between the last push and now — **files, not commits.**
     *
     * **Why a tree diff and not a commit walk.** The history is shortened by rebuilding it, so every
     * kept commit gets a new id and a remembered commit is stale afterwards — that is why the app
     * counted commits in a file for so long, and a count of commits says "1" for a batch that moved
     * ten files. A diff between two trees asks about *content*, which a rewrite does not change: the
     * commits are new, the files in them are the same.
     *
     * The other side of the comparison is `refs/remotes/origin/<branch>`, which git itself updates
     * on a push. When it is missing — a project that has never been pushed — the comparison is
     * against the empty tree, which is the truth: GitHub has none of these files yet.
     *
     * **The near side is the index, not `HEAD`** (2026-11-10). A save stages and a push commits, so
     * the work a person is looking at is in the index and *not yet in any commit* — a comparison
     * against `HEAD` would say "up to date" about a tree full of unsent work, which is the exact
     * sentence this function exists to prevent. The index is also what a commit is made from, so the
     * two sides are the two things a push will actually diff.
     *
     * Only tracked paths appear (an excluded `agent/` never does), so nothing is filtered here.
     */
    fun unsentPaths(repo: Repository): List<String> {
        val branch = repo.branch ?: return emptyList()
        // **No remote, nothing to send.** A project that stays on this phone has no GitHub to be up
        // to date with, and comparing its history with the empty tree would make every file it holds
        // read as unsent for ever.
        val remote = repo.config.getString("remote", "origin", "url")
        if (remote.isNullOrBlank()) return emptyList()
        val pushed = repo.resolve(ORIGIN + branch)
        return try {
            val newer = indexTree(repo) ?: return emptyList()
            val older: AbstractTreeIterator = pushed?.let { treeOf(repo, it) } ?: EmptyTreeIterator()
            val out = sortedSetOf<String>()
            // A sink of our own: `OutputStream.nullOutputStream()` is API 33 and this app starts at 26.
            DiffFormatter(DISCARD).use { diff ->
                diff.setRepository(repo)
                for (entry in diff.scan(older, newer)) {
                    val path = if (entry.changeType == DiffEntry.ChangeType.DELETE) {
                        entry.oldPath
                    } else {
                        entry.newPath
                    }
                    if (path.isNotEmpty()) out += path
                }
            }
            out.toList()
        } catch (e: Throwable) {
            // A repository mid-write is a repository that will answer next time; a row that reads
            // "up to date" for one refresh is better than a screen that throws.
            emptyList()
        }
    }

    /** A stream that keeps nothing: the diff is read from `scan`, not from formatted text. */
    private val DISCARD = object : java.io.OutputStream() {
        override fun write(b: Int) = Unit
        override fun write(b: ByteArray, off: Int, len: Int) = Unit
    }

    /** The tree of [id], as the parser a diff needs. Null when the object is gone. */
    private fun treeOf(repo: Repository, id: ObjectId): CanonicalTreeParser? = try {
        val commit = RevWalk(repo).use { it.parseCommit(id) }
        val parser = CanonicalTreeParser()
        repo.newObjectReader().use { reader -> parser.reset(reader, commit.tree.id) }
        parser
    } catch (e: Throwable) {
        null
    }

    /**
     * **The tree the index describes** — what a commit made right now would hold.
     *
     * Written into the repository as an object, which is what `DirCache.writeTree` does: the index
     * itself is a list of paths and ids, and a tree is the shape a diff can walk. It is a new object
     * every call, so the repository grows by one tree per refresh — the same cost `git write-tree`
     * pays, and the reason a caller should ask this once and not in a loop.
     *
     * Null when the index cannot be read, which is the case a caller must not mistake for "empty": a
     * locked index is a moment, not a project with no files.
     */
    private fun indexTree(repo: Repository): CanonicalTreeParser? = try {
        val cache = repo.readDirCache()
        // **Two objects, because they are two jobs.** `writeTree` needs an `ObjectInserter` (it writes a
        // new tree object into the repository), and `reset` needs an `ObjectReader` (it walks the tree
        // back out). `ObjectReader` is not an `ObjectInserter`, which is the trap a one-object version
        // of this would hit at compile time.
        repo.newObjectInserter().use { inserter ->
            val id = cache.writeTree(inserter)
            inserter.flush()
            repo.newObjectReader().use { reader ->
                val parser = CanonicalTreeParser()
                parser.reset(reader, id)
                parser
            }
        }
    } catch (e: Throwable) {
        null
    }

    /** How big a file is at one revision, or null when it is not there. */
    fun blobSize(repo: Repository, revision: String, path: String): Long? = try {
        val id = repo.resolve("$revision:$path")
        // `size` is the object's size whether or not its content has been loaded, so a large object
        // is measured like any other — and that is the point: this is what decides not to read it.
        if (id == null) null else repo.open(id).size
    } catch (e: Throwable) {
        null
    }

    /**
     * A file's text at one revision, or null when it is not there, not a file, or past [limit].
     *
     * **The three nulls are one answer on purpose**: a caller previewing a change does not act on
     * them differently — an absent path is a file that was added or deleted, and both are `""` to a
     * diff. What must not happen is reading a large object to find out that it is large.
     */
    /**
     * **A path's text as the index holds it** — the staged version, which is what a push would send.
     *
     * `blobText(repo, "HEAD", …)` answers the *last version*, and a save only stages (2026-11-10) — so
     * a preview built from `HEAD` was empty for every unsent change, which is the only kind of change
     * the window lists. The index's entry names the blob directly, so there is nothing to resolve.
     */
    fun stagedText(repo: Repository, path: String, limit: Int = 512 * 1024): String? = try {
        val entry = repo.readDirCache().getEntry(path)
        if (entry == null) {
            null
        } else {
            val obj = repo.open(entry.objectId)
            if (obj.type != org.eclipse.jgit.lib.Constants.OBJ_BLOB || obj.size > limit) {
                null
            } else {
                String(obj.bytes, Charsets.UTF_8)
            }
        }
    } catch (e: Throwable) {
        null
    }

    /** The staged size of [path], or null when it is not in the index. */
    fun stagedSize(repo: Repository, path: String): Long? = try {
        repo.readDirCache().getEntry(path)?.let { repo.open(it.objectId).size }
    } catch (e: Throwable) {
        null
    }

    fun blobText(repo: Repository, revision: String, path: String, limit: Int = 512 * 1024): String? = try {
        val id = repo.resolve("$revision:$path")
        if (id == null) {
            null
        } else {
            val obj = repo.open(id)
            if (obj.type != org.eclipse.jgit.lib.Constants.OBJ_BLOB || obj.size > limit) {
                null
            } else {
                String(obj.bytes, Charsets.UTF_8)
            }
        }
    } catch (e: Throwable) {
        null
    }

    /**
     * True when [text] is not text: a NUL byte in the first few thousand characters.
     *
     * The same rule git uses, and the reason a preview needs one: rendering a picture's bytes as
     * lines produces convincing nonsense, and a person would have no way to tell it was not the file.
     */
    fun looksBinary(text: String): Boolean {
        val upTo = text.take(8_000)
        return upTo.indexOf('\u0000') >= 0
    }

    /**
     * Two versions of one file, line by line — **every line, not a patch.**
     *
     * A hunk with three lines of context is the right shape for a terminal and the wrong one here: the
     * person is reading the file, and the lines that did not change are what tells them where the
     * change is. [DiffLine.Kind.SAME] is most of the answer.
     */
    fun diffLines(before: String, after: String): List<DiffLine> {
        val old = if (before.isEmpty()) emptyList() else before.split("\n")
        val new = if (after.isEmpty()) emptyList() else after.split("\n")
        val edits = try {
            org.eclipse.jgit.diff.HistogramDiff().diff(
                org.eclipse.jgit.diff.RawTextComparator.DEFAULT,
                // Bytes, not a string: this JGit's `RawText` has no `String` constructor, and the
                // comparator works on bytes anyway.
                org.eclipse.jgit.diff.RawText(before.toByteArray()),
                org.eclipse.jgit.diff.RawText(after.toByteArray()),
            )
        } catch (e: Throwable) {
            // A diff that cannot be computed is still a file the person may want to read; the honest
            // answer is that every line is new rather than an exception on a screen.
            null
        }
        if (edits == null) return new.map { DiffLine(DiffLine.Kind.ADDED, it) }
        val out = ArrayList<DiffLine>(old.size + new.size)
        var a = 0
        var b = 0
        for (edit in edits) {
            while (a < edit.beginA) {
                out += DiffLine(DiffLine.Kind.SAME, old[a])
                a++
                b++
            }
            for (i in edit.beginA until edit.endA) out += DiffLine(DiffLine.Kind.REMOVED, old[i])
            for (i in edit.beginB until edit.endB) out += DiffLine(DiffLine.Kind.ADDED, new[i])
            a = edit.endA
            b = edit.endB
        }
        while (a < old.size) {
            out += DiffLine(DiffLine.Kind.SAME, old[a])
            a++
            b++
        }
        while (b < new.size) {
            out += DiffLine(DiffLine.Kind.ADDED, new[b])
            b++
        }
        return out
    }

    /**
     * **What is staged and not yet committed** — the work a push is about to turn into a version.
     *
     * A save stages now (§"we commit on push"), so the question "is there anything to commit" is asked
     * of the index rather than of the working tree. `added`, `changed` and `removed` are the index's own
     * three answers; `modified` and `missing` would be *unstaged* edits, which a save has already
     * staged and which a push must not quietly include under a message that did not mention them.
     */
    fun stagedPaths(repo: Repository): List<String> = try {
        val status = Git(repo).use { it.status().call() }
        (status.added + status.changed + status.removed)
            .filter { !it.startsWith(".") }
            .sorted()
    } catch (e: Throwable) {
        emptyList()
    }

    fun changedPaths(repo: Repository): List<String> {
        val status: Status = Git(repo).use { it.status().call() }
        return (status.added + status.changed + status.modified +
            status.removed + status.missing + status.untracked)
            .filter { !it.startsWith(".") }
            .sorted()
    }

    /**
     * The remote branch that stands for this project.
     *
     * Tried in order: the branch this phone is on, the remote's own default, and
     * then the only branch there is. The last one matters for a repository that has
     * exactly one branch but does not point `origin/HEAD` at it, which is common in
     * cloned projects that were never given a default.
     */
    fun remoteBranch(repo: Repository, local: String): String? {
        fun exists(name: String): Boolean =
            repo.exactRef(ORIGIN + name) != null

        if (exists(local)) return local
        val head = repo.exactRef(ORIGIN + "HEAD")
        val pointed = head?.target?.name?.removePrefix(ORIGIN)
            ?.takeIf { it.isNotBlank() && it != "HEAD" }
        if (pointed != null && exists(pointed)) return pointed
        val others = repo.refDatabase.getRefsByPrefix(ORIGIN)
            .map { it.name.removePrefix(ORIGIN) }
            .filter { it != "HEAD" }
        return others.singleOrNull()
    }

    /** How many changes are waiting in the working tree. */
    fun status(repo: Repository): Changes {
        val status: Status = Git(repo).use { it.status().call() }
        return Changes(
            staged = status.changed.size + status.added.size,
            unstaged = status.modified.size + status.removed.size + status.missing.size,
            untracked = status.untracked.size + status.untrackedFolders.size,
        )
    }

    // ---- history -----------------------------------------------------------

    /**
     * The commits that touched [path] (a path relative to the repository root),
     * newest first. A null or blank path returns the whole history.
     */
    fun log(
        repo: Repository,
        path: String?,
        /** A ceiling for the caller; [READ_ALL] by default. */
        limit: Int = READ_ALL,
    ): List<CommitInfo> {
        val commits = Git(repo).use { git ->
            val command = git.log()
            if (!path.isNullOrBlank()) command.addPath(path)
            command.call().take(limit).toList()
        }
        return commits.map { toInfo(it) }
    }

    /**
     * One commit, by id — or null when the repository does not have it.
     *
     * **For a commit the file's own log will not contain.** `log(repo, path)` answers "which commits
     * touched this file", and the last synced commit may have touched nothing here. A diff that
     * compares against it still has to name both ends, so this looks the commit up directly.
     */
    fun commitInfo(repo: Repository, id: String): CommitInfo? = try {
        RevWalk(repo).use { walker -> toInfo(walker.parseCommit(ObjectId.fromString(id))) }
    } catch (t: Throwable) {
        null
    }

    /** Turns a JGit commit into the app's own summary of it. */
    private fun toInfo(commit: RevCommit): CommitInfo = CommitInfo(
        id = commit.name,
        shortId = commit.name.take(7),
        message = commit.fullMessage.trim(),
        author = commit.authorIdent.name,
        authorEmail = commit.authorIdent.emailAddress,
        timestamp = commit.authorIdent.`when`?.time ?: (commit.commitTime.toLong() * 1000L),
    )

    /** The tree object of [path] at [revisionId]. */
    private fun treeFor(
        repo: Repository,
        walk: RevWalk,
        path: String?,
        revisionId: String?,
    ): RevTree {
        val asked = revisionId?.takeIf { it.isNotBlank() }
        val start = if (asked == null) {
            repo.resolve("HEAD") ?: throw IllegalStateException("The repository has no commits yet")
        } else {
            // **A version that is not there is not HEAD.** This used to fall back, so a wrong id
            // answered with the *current* file: a `read` at a version showed something the version
            // never said, and a `restore` committed "Restored milk.txt" while the file had never
            // moved. A refusal is the only honest answer to an id that resolves to nothing.
            repo.resolve(asked)
                ?: throw IllegalArgumentException("There is no version $asked")
        }
        val commit = walk.parseCommit(start)
        val tree = commit.tree
        val clean = path?.trim('/').orEmpty()
        if (clean.isEmpty()) return tree
        val found = TreeWalk.forPath(repo, clean, *arrayOf<AnyObjectId>(tree))
            ?: throw IllegalStateException("'$clean' is not in this revision")
        val objectId = found.getObjectId(0)
        found.close()
        return walk.parseTree(objectId)
    }

    /** One file's part in a [changesBetween] answer. */
    data class ChangedFile(
        val path: String,
        /** `A` added, `M` modified, `D` deleted. */
        val kind: Char,
        val added: Int,
        val removed: Int,
        /** The lines, when the file is text and small enough to be worth reading. */
        val lines: List<DiffLine>,
        /** Why there are no lines, when there are none. */
        val notDiffed: String? = null,
    )

    /**
     * What changed between two revisions: the files, and what happened in each.
     *
     * **Not `Changes`**, which is already taken by the staged/unstaged/untracked counts — a name
     * collision that compiled in the class file and failed only where both were in scope, which is
     * exactly the kind of thing a compiler catches and a text search does not.
     */
    data class RevisionDiff(
        val from: String,
        val to: String,
        val path: String,
        val files: List<ChangedFile>,
        /** True when more files matched than are listed. */
        val more: Boolean = false,
    )

    /**
     * Every path that differs between two revisions, under [path], as diffs.
     *
     * **`DiffFormatter` with a `TreeWalk`, not two `bytesAt` calls**, because [path] may name a
     * folder: forty files changed under `src/` is forty diffs, and asking for each one by name would
     * be forty tree lookups to discover what one walk already knows.
     *
     * **A binary file is named and not read.** `looksBinary` decides from the first bytes, so a
     * 40 MB video costs one blob read rather than a page of replacement characters in a conversation.
     */
    fun changesBetween(
        repo: Repository,
        path: String,
        from: String,
        to: String,
    ): Result<RevisionDiff> {
        val old = repo.resolve(from) ?: return Result.failure(
            IllegalArgumentException("There is no version $from"),
        )
        val new = repo.resolve(to) ?: return Result.failure(
            IllegalArgumentException("There is no version $to"),
        )
        val prefix = path.trim('/')
        val out = ArrayList<ChangedFile>()
        var more = false
        RevWalk(repo).use { walk ->
            val a = walk.parseCommit(old).tree
            val b = walk.parseCommit(new).tree
            // **`DiffFormatter` implements `AutoCloseable`, not `Closeable`**, so `use` reaches it
            // through the Kotlin extension. `NullOutputStream.INSTANCE` because that class has a
            // **private constructor** — it is a singleton, and `NullOutputStream()` does not compile.
            // The entry list is what this needs; the formatted patch text would be a second
            // representation of the same facts, computed and thrown away.
            val formatter = DiffFormatter(NullOutputStream.INSTANCE)
            try {
                formatter.setRepository(repo)
                val entries = formatter.scan(a, b)
                for (entry in entries) {
                    val name = entry.newPath ?: entry.oldPath ?: continue
                    if (prefix.isNotEmpty() && name != prefix && !name.startsWith("$prefix/")) continue
                    if (out.size >= DIFF_FILES) {
                        more = true
                        break
                    }
                    val kind = when (entry.changeType) {
                        DiffEntry.ChangeType.ADD -> 'A'
                        DiffEntry.ChangeType.DELETE -> 'D'
                        else -> 'M'
                    }
                    // **The name first, then the bytes.** A `.zip`, a `.png` or an `.mp4` is a
                    // binary by its extension whatever the bytes happen to look like — a tiny zip
                    // with no NUL in its first block would otherwise be diffed as text. The NUL test
                    // is the second net, for a binary with no useful extension.
                    val before = if (kind == 'A') "" else blobText(repo, from, name).orEmpty()
                    val after = if (kind == 'D') "" else blobText(repo, to, name).orEmpty()
                    if (Workspace.kindOfPath(name) != EntryKind.TEXT ||
                        looksBinary(before) || looksBinary(after)
                    ) {
                        out += ChangedFile(name, kind, 0, 0, emptyList(), "binary")
                        continue
                    }
                    val lines = diffLines(before, after)
                    out += ChangedFile(
                        path = name,
                        kind = kind,
                        added = lines.count { it.kind == DiffLine.Kind.ADDED },
                        removed = lines.count { it.kind == DiffLine.Kind.REMOVED },
                        lines = lines,
                    )
                }
            } finally {
                formatter.close()
            }
        }
        return Result.success(RevisionDiff(from, to, prefix, out, more))
    }

    /**
     * The contents of the file [path] at the revision [revisionId], as text.
     * Returns null when the file did not exist at that revision.
     */
    fun fileAt(
        repo: Repository,
        path: String,
        revisionId: String? = null,
    ): String? {
        val walk = RevWalk(repo)
        try {
            val tree = treeFor(repo, walk, null, revisionId)
            val found = TreeWalk.forPath(repo, path.trim('/'), *arrayOf<AnyObjectId>(tree))
                ?: return null
            found.use {
                val loader = repo.open(it.getObjectId(0))
                return String(loader.getBytes(), Charsets.UTF_8)
            }
        } finally {
            walk.close()
        }
    }

    /**
     * Every file under [path] at [revisionId], with its text, as paths from the
     * repository root. A blank path means the whole repository.
     */
    fun filesUnder(
        repo: Repository,
        path: String?,
        revisionId: String? = null,
    ): List<Pair<String, String>> {
        val walk = RevWalk(repo)
        try {
            val tree = treeFor(repo, walk, path, revisionId)
            val base = path?.trim('/').orEmpty()
            val found = mutableListOf<Pair<String, String>>()
            TreeWalk(repo).use { treeWalk ->
                treeWalk.addTree(tree)
                treeWalk.setRecursive(true)
                while (treeWalk.next()) {
                    val name = treeWalk.getPathString()
                    val full = if (base.isEmpty()) name else "$base/$name"
                    // A submodule is a pointer to a commit in another repository.
                    // It is not a file, it has no text, and reading it would fail
                    // for the whole version rather than for the one entry.
                    if (treeWalk.getFileMode(0) == FileMode.GITLINK) continue
                    val loader = try {
                        repo.open(treeWalk.getObjectId(0))
                    } catch (e: Throwable) {
                        continue
                    }
                    found.add(full to String(loader.bytes, Charsets.UTF_8))
                }
            }
            return found
        } finally {
            walk.close()
        }
    }

    /**
     * **What each commit in the history changed, newest first — one walk for the whole history.**
     *
     * ## Why this exists rather than [versionsOf] per file
     *
     * [versionsOf] runs `git log -- <path>`, so asking about thirty paths is thirty walks of the
     * history. A search with `history: true` needs every path's versions at once, and this answers
     * that in one pass: each commit is diffed against its parent, and the diff *is* the set of
     * `(path, blob)` pairs that commit introduced. It is the same work git does to show a commit,
     * done once for all of them.
     *
     * ## The pairs, not the commits
     *
     * The caller is [versionsOf], and it is keyed by **content**: the same text saved twice is one
     * blob. So what this yields is the blob id — which survives a force-push that rewrites local
     * commits, because a blob is content and content does not move — and that is also what keeps one
     * text saved six times from being six reads of it.
     *
     * A blob id names content, not a place: the same text can appear at two paths, and this reports
     * both, because the *path* is what a search answer has to say.
     *
     * ## Bounds
     *
     * [limit] is commits, not files, and the newest [limit] are the ones a person is likely to mean.
     * [deadline] is checked once per commit — a diff of one commit is not interruptible, but the walk
     * between them is, and that is where a long history spends its time.
     */
    fun walkChanges(
        repo: Repository,
        limit: Int = READ_ALL,
        deadline: Deadline = Deadline.none(),
        onCommit: (CommitInfo, List<Change>) -> Unit,
    ) {
        val head = repo.resolve("HEAD") ?: return
        RevWalk(repo).use { walk ->
            walk.markStart(walk.parseCommit(head))
            var seen = 0
            for (commit in walk) {
                if (seen >= limit) return
                deadline.check("history")
                seen++
                // **The root commit is diffed against nothing**, so every file it holds is a change.
                // `EmptyTreeIterator` is that nothing, and it is the same trick `unsentPaths` uses.
                val older: AbstractTreeIterator = if (commit.parentCount == 0) {
                    EmptyTreeIterator()
                } else {
                    treeOf(repo, commit.getParent(0).id) ?: EmptyTreeIterator()
                }
                val newer = treeOf(repo, commit.id) ?: continue
                val changes = mutableListOf<Change>()
                try {
                    DiffFormatter(DISCARD).use { diff ->
                        diff.setRepository(repo)
                        for (entry in diff.scan(older, newer)) {
                            // **A deletion has no blob**, so it is not a change to index — and saying
                            // so here is what keeps the caller from having to ask. "This file was not
                            // there" is a fact about a tree, and the history screen reads trees.
                            if (entry.changeType == DiffEntry.ChangeType.DELETE) continue
                            val path = entry.newPath
                            if (path.isEmpty()) continue
                            // `oldId` is the all-zero id for an addition, which is exactly right:
                            // the caller filters by [Change.blob].
                            changes += Change(path, entry.newId.name())
                        }
                    }
                } catch (e: Throwable) {
                    // **One unreadable commit does not end the walk.** A repository mid-write, or an
                    // object a collection took, leaves this commit with nothing to report; the rest of
                    // the history is still worth reading.
                    continue
                }
                onCommit(toInfo(commit), changes)
            }
        }
    }

    /**
     * **Blob ids resolved back to the commits that produced them — one pass for many pairs.**
     *
     * The index is keyed by content ([GitService.Change.blob]) because a commit id does not survive a
     * prune or a force-pushed remote. But an answer a person or a model reads has to name a *save* —
     * "which version do I read?" — and that is a commit. So the resolution happens here, at read time,
     * and never gets stored: a cached commit id would be the very thing that goes stale.
     *
     * ## Why one object for many pairs
     *
     * A search answers up to thirty rows, so resolving each one separately would walk the history
     * thirty times. [GitService.walkChanges] already yields `(path, blob)` per commit, newest first, so
     * one walk resolves the whole answer — and stops as soon as every pair is accounted for, which for
     * recent work is the first handful of commits.
     *
     * ## What "the commit for a blob" means
     *
     * **The newest commit that put this content at this path.** Newest, because a person asking about
     * a version means the most recent time they saved it; the pair, because content addressing does not
     * know about names — the same text can live at two paths, and the answer has to say which.
     */
    class VersionResolver(
        private val repo: Repository,
        private val deadline: Deadline = Deadline.none(),
    ) {
        /**
         * Every pair that some commit reaches, mapped to the newest such commit's id.
         *
         * Pairs no commit reaches are simply absent — the caller drops their rows and says so, because
         * a row that outlives its commits is a search hit that cannot be read.
         */
        fun resolve(pairs: Collection<Pair<String, String>>): Map<Pair<String, String>, String> {
            val wanted = pairs.toSet()
            val out = mutableMapOf<Pair<String, String>, String>()
            if (wanted.isEmpty()) return out
            try {
                walkChanges(repo, deadline = deadline) { commit, changes ->
                    // **Every pair accounted for is the end of the walk**, which for recent work is
                    // the first few commits rather than the whole history.
                    if (out.size >= wanted.size) return@walkChanges
                    for (change in changes) {
                        if (!change.real) continue
                        val pair = change.path to change.blob
                        if (pair in wanted && pair !in out) out[pair] = commit.id
                    }
                }
            } catch (e: StoppedException) {
                // **What was resolved is still true.** A history too long to finish in the turn
                // answers for the versions it reached and lets the rest be dropped by the caller's own
                // rule, rather than failing the search outright.
                throw e
            } catch (e: Throwable) {
                // An unreadable repository resolves nothing; the caller's note says the rows were
                // dropped, which is honest about the outcome.
            }
            return out
        }
    }

    /** One file as one commit left it: where it is, and **what it was** — the blob's id. */
    data class Change(val path: String, val blob: String) {
        /** True when the diff carried a real object id rather than the zero id of a deletion. */
        val real: Boolean get() = blob.isNotEmpty() && blob.trim('0').isNotEmpty()
    }

    /**
     * The commits that changed the file [path], newest first, each with the
     * contents of the file at that commit. This is what the version browser
     * shows.
     */
    fun versionsOf(
        repo: Repository,
        path: String,
        /** A ceiling for the screen; [READ_ALL] for the ordinary case. */
        limit: Int = READ_ALL,
        /**
         * Asked once per version, because this loads every version's contents.
         *
         * It is the one read in this class that grows with the *history* rather than with the file:
         * a file saved a thousand times is a thousand trees to open, and a command that may not
         * outlast its turn has to be able to stop between them.
         */
        deadline: Deadline = Deadline.none(),
    ): List<Pair<CommitInfo, String?>> {
        val walk = RevWalk(repo)
        try {
            val commits = Git(repo).use { git ->
                git.log().addPath(path).call().take(limit).toList()
            }
            return commits.map { commit ->
                deadline.check("history")
                val info = toInfo(commit)
                val found = TreeWalk.forPath(repo, path.trim('/'), *arrayOf<AnyObjectId>(commit.tree))
                val content = found?.use {
                    val loader = repo.open(it.getObjectId(0))
                    String(loader.getBytes(), Charsets.UTF_8)
                }
                info to content
            }
        } finally {
            walk.close()
        }
    }

    /**
     * The bytes of one blob, **by its own id** — no path and no commit.
     *
     * [bytesAt] takes a *revision* and walks a tree to find the file in it; a blob id is not a tree,
     * so asking it for one finds nothing. The index stores blobs ([Change.blob]), so reading a saved
     * version back is this call and not that one.
     */
    fun bytesOf(repo: Repository, blob: String): ByteArray? = try {
        val id = ObjectId.fromString(blob)
        // **No `use`: a loader is not `Closeable`.** It holds no handle of its own — the repository
        // owns the reader — so the only thing to close is the repository, and that is the caller's.
        val obj = repo.open(id)
        if (obj.type == org.eclipse.jgit.lib.Constants.OBJ_BLOB) obj.bytes else null
    } catch (e: Throwable) {
        null
    }

    /**
     * The bytes of [path] at [revisionId], or null when it is not there.
     *
     * A version is not always text. A picture keeps its bytes exactly as they were
     * committed, and putting one back by writing a UTF-8 reading of it would replace
     * the picture with a row of replacement characters. Everything that restores a
     * file goes through here for that reason.
     */
    fun bytesAt(repo: Repository, path: String, revisionId: String? = null): ByteArray? {
        val walk = RevWalk(repo)
        try {
            val tree = treeFor(repo, walk, null, revisionId)
            val found = TreeWalk.forPath(repo, path.trim('/'), *arrayOf<AnyObjectId>(tree))
                ?: return null
            found.use {
                if (it.getFileMode(0) == FileMode.GITLINK) return null
                return repo.open(it.getObjectId(0)).bytes
            }
        } finally {
            walk.close()
        }
    }

    /**
     * Every path under [path] at [revisionId] — names only, no contents.
     *
     * Used to answer "what used to be in this folder". Reading every file to find
     * out its name would make a small question cost a whole tree.
     */
    fun pathsUnder(repo: Repository, path: String?, revisionId: String? = null): List<String> {
        val walk = RevWalk(repo)
        try {
            val tree = treeFor(repo, walk, path, revisionId)
            val base = path?.trim('/').orEmpty()
            val found = mutableListOf<String>()
            TreeWalk(repo).use { treeWalk ->
                treeWalk.addTree(tree)
                treeWalk.setRecursive(true)
                while (treeWalk.next()) {
                    if (treeWalk.getFileMode(0) == FileMode.GITLINK) continue
                    val name = treeWalk.getPathString()
                    found.add(if (base.isEmpty()) name else "$base/$name")
                }
            }
            return found
        } finally {
            walk.close()
        }
    }

    /**
     * Puts the contents of [path] at [revisionId] back into the working tree, byte
     * for byte.
     */
    /**
     * Puts [path] back as it was at [revisionId] — **a file or a whole folder.**
     *
     * A folder had no case here for a long time: `bytesAt` answers only for a blob, so restoring a
     * folder version found nothing, deleted the target and reported success. A version of a folder
     * is every file under it at that commit, so that is what is written back — and, because
     * "this version" has to *be* the version, anything under it that the version did not hold is
     * removed. The removal is committed like any other change, so it is as reversible as the
     * restore itself.
     */
    /**
     * Puts [path] back as it was at [revisionId] — **or stops cleanly and leaves the tree alone.**
     *
     * A folder version is written into a partial directory beside the target and moved into place
     * only once every file is there. Without that, stopping half way would leave the person with a
     * folder of some of its files and no way to know which, and the sentence "nothing was kept"
     * would be false. A file version is one write, so it needs no such care.
     *
     * [deadline] is checked between files, which is the granularity this loop has. It is not a hard
     * limit on any single write — a 4 MB file is written whole — but a version is many files, and
     * that is where the time goes.
     */
    fun restore(repo: Repository, path: String, revisionId: String, deadline: Deadline? = null) {
        val clean = path.trim('/')
        // **A picture is restored like anything else**, because the bytes are in the commit.
        //
        // The refusal that used to be here was LFS's: the tree held a pointer and the bytes lived in a
        // content-addressed store pruned to what the newest commits referenced, so putting an older
        // version back wrote 130 bytes of JSON over the picture. No filter stands between the tree and
        // the working copy any more, so a binary has versions and the write below is the whole of it —
        // and `HistoryScreen` already told the person "it can still be put back", which was true only
        // once this line went.
        val target = File(repo.workTree, clean)
        val walk = RevWalk(repo)
        try {
            val tree = treeFor(repo, walk, null, revisionId)
            val found = TreeWalk.forPath(repo, clean, *arrayOf<AnyObjectId>(tree))
            if (found == null) {
                // **`Workspace.deleteTree`, because the target may be the `system` link.** Restoring a
                // path that is not in the commit removes what is there - and for `system` that means a
                // link to the account's own folder, whose contents `deleteRecursively()` would empty.
                Workspace.deleteTree(target)
                return
            }
            found.use {
                if (it.getFileMode(0) != FileMode.TREE) {
                    // A file: the same bytes, written over whatever is there.
                    target.parentFile?.mkdirs()
                    target.writeBytes(repo.open(it.getObjectId(0)).bytes)
                    return
                }
            }
            // A folder: everything under it at that commit, and nothing else.
            val keep = LinkedHashMap<String, ByteArray>()
            TreeWalk(repo).use { treeWalk ->
                treeWalk.addTree(tree)
                treeWalk.isRecursive = true
                treeWalk.filter = PathFilterGroup.createFromStrings(listOf(clean))
                while (treeWalk.next()) {
                    if (treeWalk.getFileMode(0) == FileMode.GITLINK) continue
                    if (deadline?.expired == true) {
                        throw StoppedException(Stopped.body("restore", deadline))
                    }
                    keep[treeWalk.pathString] = repo.open(treeWalk.getObjectId(0)).bytes
                }
            }
            val partial = File(target.parentFile, target.name + ".partial")
            partial.deleteRecursively()
            try {
                partial.mkdirs()
                for ((pathInTree, bytes) in keep) {
                    if (deadline?.expired == true) {
                        throw StoppedException(Stopped.body("restore", deadline))
                    }
                    val file = File(repo.workTree, pathInTree)
                    // **Inside the partial folder, not the real one.** The tree path is the full
                    // path in the project; the partial folder takes the place of the target, so the
                    // part after it is what is written.
                    val inside = File(partial, pathInTree.removePrefix(clean).trimStart('/'))
                    inside.parentFile?.mkdirs()
                    inside.writeBytes(bytes)
                }
                Workspace.deleteTree(target)
                if (!partial.renameTo(target)) {
                    throw IllegalStateException("Could not put the version back into place")
                }
            } catch (e: Throwable) {
                partial.deleteRecursively()
                throw e
            }
        } finally {
            walk.close()
        }
    }

    /**
     * **Puts the whole working tree back to one revision.**
     *
     * The whole-project form of [restore], and the difference is not a loop over paths: a checkout
     * has to **remove** what the revision did not have, and a per-path restore cannot know what that
     * is. `CheckoutCommand` is the operation that does both, and it is the same one a person's own
     * `git checkout <commit> -- .` runs.
     *
     * **`keepStage` decides whether the index survives.** It defaults to **false**, which is the safe
     * direction: the checkout has just rewritten every file, so an index describing those changes
     * describes the restore rather than anything a person did. Leaving it would make the next `push`
     * commit the whole tree as one version with a message somebody wrote about something else.
     *
     * **The working tree is not backed up, and that is the caller's question to ask.** A checkout over
     * uncommitted work destroys it; [ProjectGit.restoreTree] refuses in that case, and this function
     * is the mechanism rather than the policy.
     */
    fun checkoutTree(repo: Repository, revisionId: String, keepStage: Boolean = false) {
        // **A version that is not there is not HEAD.** The same rule `treeFor` states: a wrong id
        // that fell back to the current tree would make a checkout answer "done" about a version
        // that was never read.
        // **`parseCommit`, not the bare id.** `repo.resolve` answers an `ObjectId`, which may be a
        // tree, a tag or a blob; a checkout needs the commit, and parsing is what says which it is.
        val start = repo.resolve(revisionId.trim())
            ?: throw IllegalArgumentException("There is no version $revisionId in this project")
        val commit = org.eclipse.jgit.revwalk.RevWalk(repo).use { walk ->
            try {
                walk.parseCommit(start)
            } catch (e: Throwable) {
                throw IllegalArgumentException("There is no version $revisionId in this project")
            }
        }
        // **`checkout` with a start point detaches HEAD, which is not what a restore means.** The
        // tree must come from the version while the *branch* stays where it is — otherwise the next
        // push would send from a detached head, and the person's screen would say "no branch".
        //
        // So the tree is written from the commit's tree directly, and the removal is the half a
        // per-path restore cannot do: **every tracked path that the version does not have is
        // deleted**, which is what "all files change to that state" means.
        val tree = commit.tree
        val wanted = LinkedHashMap<String, org.eclipse.jgit.lib.ObjectId>()
        org.eclipse.jgit.treewalk.TreeWalk(repo).use { walk ->
            walk.addTree(tree)
            walk.isRecursive = true
            while (walk.next()) {
                if (walk.getFileMode(0) == org.eclipse.jgit.lib.FileMode.GITLINK) continue
                wanted[walk.pathString] = walk.getObjectId(0).copy()
            }
        }
        // **Written before anything is deleted**, so a failure part way leaves every old file still
        // there rather than a tree that is missing both versions of something.
        for ((pathInTree, id) in wanted) {
            val target = File(repo.workTree, pathInTree)
            target.parentFile?.mkdirs()
            target.writeBytes(repo.open(id).bytes)
        }
        // **And now the deletions.** A tracked file the version does not have is gone; a file that is
        // untracked is left alone, because it is not part of any version and deleting it would be
        // destroying work this command was never asked about.
        // **What was tracked before, read from the tree that is on disk's index** — `lsTree` is not
        // in this jgit, and the index is the right source anyway: it is what "tracked" means here,
        // and an untracked file is one no version ever had.
        val tracked = mutableListOf<String>()
        val cache = org.eclipse.jgit.dircache.DirCache.read(repo)
        // **By index, not by iterator**, because `DirCache` implements `Iterable` twice and the
        // compiler cannot choose — which is a fine thing for it to refuse to guess.
        for (i in 0 until cache.entryCount) tracked.add(cache.getEntry(i).pathString)
        for (pathInTree in tracked) {
            if (pathInTree in wanted) continue
            val target = File(repo.workTree, pathInTree)
            // **`Workspace.deleteTree`, because a path may be the `system` link** — the same reason
            // the per-path restore uses it.
            if (target.exists()) Workspace.deleteTree(target)
        }
        if (!keepStage) {
            // **`reset` and not `rm --cached`**: the index is set to match the tree that is now on
            // disk, which is what "nothing is staged" means. Doing nothing would leave the old index
            // describing files that no longer exist — and `push` counts the index, so the next push
            // would commit the whole tree as one version.
            Git(repo).use { git ->
                git.reset().setRef(commit.name).setMode(ResetCommand.ResetType.MIXED).call()
            }
        }
    }

    // ---- remotes -----------------------------------------------------------

    /** The URL of the remote called "origin", or null. */
    fun remoteUrl(repo: Repository): String? =
        repo.config.getString("remote", "origin", "url")

    /** Sets (or replaces) the URL of the "origin" remote. */
    fun setRemoteUrl(repo: Repository, url: String) {
        val config = repo.config
        config.setString("remote", "origin", "url", url)
        config.setString("remote", "origin", "fetch", "+refs/heads/*:" + ORIGIN + "*")
        config.save()
    }

    /**
     * Rewrites every remote address that carries a key back to the plain
     * address.
     *
     * A repository written by an older version of the app can still hold a
     * token inside its remote URL — on disk, readable by anything that reads
     * the repository. Opening a repository scrubs this, so a leak that already
     * happened stops happening.
     */
    fun scrubRemoteUrls(repo: Repository) {
        val config = repo.config
        var changed = false
        for (remote in config.getSubsections("remote")) {
            val url = config.getString("remote", remote, "url") ?: continue
            val clean = Secrets.stripCredentials(url)
            if (clean != url && clean.isNotBlank()) {
                config.setString("remote", remote, "url", clean)
                changed = true
            }
        }
        if (changed) config.save()
    }

    /** Pushes the current branch to the remote. */
    fun push(
        repo: Repository,
        token: String?,
        force: Boolean = false,
        gitUser: String? = null,
    ): RemoteResult {
        return pushNow(repo, token, force, gitUser)
    }

    private fun pushNow(
        repo: Repository,
        token: String?,
        force: Boolean,
        gitUser: String?,
    ): RemoteResult {
        return try {
            val local = currentBranch(repo)
                // **Refused, because the alternative is pushing onto the wrong branch.** A repository
                // whose HEAD cannot be read is a repository where "which branch am I sending?" has no
                // answer, and this used to answer `main` and send.
                ?: return RemoteResult(
                    "Could not tell which branch this project is on, so nothing was sent.",
                    false,
                )
            // **Push to the branch GitHub calls this project.** Sending
            // `refs/heads/main` to a repository whose default is `master` leaves the
            // branch GitHub shows — and the history a person reads there — untouched,
            // while a second branch quietly collects the commits. That is also why a
            // shortened history could look unshortened on GitHub.
            val onto = remoteBranch(repo, local) ?: local
            Git(repo).use { git ->
                val command = git.push()
                    .setRemote("origin")
                    .setForce(force)
                    .setRefSpecs(RefSpec("refs/heads/$local:refs/heads/$onto"))
                // **The URL, so a project cloned from elsewhere cannot have the key sent to it.**
                // This was the call site that had no guard at all.
                credentials(remoteOf(repo), token, gitUser)?.let { command.setCredentialsProvider(it) }
                val results = command.call()
                val updates = results.flatMap { it.remoteUpdates }
                val messages = updates
                    .mapNotNull { it.message }
                    .filter { it.isNotBlank() }
                    .joinToString("; ")
                // What the remote said is not a reliable verdict — a local push
                // often says nothing at all. The update's status is.
                val ok = updates.isNotEmpty() && updates.all {
                    it.status == RemoteRefUpdate.Status.OK ||
                        it.status == RemoteRefUpdate.Status.UP_TO_DATE
                }
                RemoteResult(Secrets.scrub(messages.ifBlank {
                    if (ok) "Pushed" else "Nothing was pushed"
                }), ok)
            }
        } catch (e: Throwable) {
            RemoteResult(Secrets.explain(e, "Push failed"), false)
        }
    }

    /**
     * Every path held by [ref], or none when it does not resolve.
     *
     * **The question "did this file travel?" has one honest answer: what the ref holds.** A working
     * tree says what is on disk here, and a local log says what this phone committed; only the
     * remote's own ref says what GitHub was given. Used by the unsync tests, and by anything that
     * wants to check a push rather than assume it.
     */
    fun treePaths(repo: Repository, ref: AnyObjectId?): List<String> {
        if (ref == null) return emptyList()
        return try {
            RevWalk(repo).use { walk ->
                val tree = walk.parseCommit(ref).tree
                TreeWalk(repo).use { tw ->
                    tw.addTree(tree)
                    tw.isRecursive = true
                    val out = mutableListOf<String>()
                    while (tw.next()) out += tw.pathString
                    out.sorted()
                }
            }
        } catch (e: Throwable) {
            emptyList()
        }
    }

    /** Pulls from the remote into the current branch. */
    /**
     * **Makes a tag at `HEAD` and sends it.**
     *
     * A tag is the release the git protocol has: every host understands one, and what a host then does
     * with it — a release page, a build, an artifact — is that host's business rather than this app's.
     *
     * **Annotated when there is a message**, because a tag that says why is what a person reading a
     * host's release list needs, and a lightweight tag cannot carry it. Tags are the one thing in git
     * that should not be moved afterwards, so a name that already exists is refused rather than
     * rewritten: re-tagging silently would make two different commits claim the same version.
     */
    fun tagAndPush(
        repo: Repository,
        name: String,
        message: String?,
        token: String?,
        gitUser: String? = null,
    ): RemoteResult {
        if (repo.resolve("refs/tags/$name") != null) {
            return RemoteResult(
                "There is already a tag called `$name`. A version that has been sent stays where " +
                    "it is; pick a new one.",
                false,
            )
        }
        return try {
            Git(repo).use { git ->
                val command = git.tag().setName(name)
                if (message.isNullOrBlank()) command.setAnnotated(false)
                else command.setAnnotated(true).setMessage(message)
                command.call()
                val push = git.push()
                // **The tag's own ref, spelled out.** A plain `push` sends the branch, and a tag is
                // not on a branch: without this refspec the tag exists here and nowhere else, which
                // is the one outcome worse than a refused release.
                push.setRefSpecs(RefSpec("refs/tags/$name:refs/tags/$name"))
                credentials(remoteOf(repo), token, gitUser)?.let { push.setCredentialsProvider(it) }
                val results = push.call()
                // **The update's status is the verdict, not the remote's silence.** A local push
                // answers with no message at all, so a reply that says nothing is not a failure —
                // the same rule `pushNow` follows, spelled the same way.
                val updates = results.flatMap { it.remoteUpdates }
                val ok = updates.isNotEmpty() && updates.all {
                    it.status == RemoteRefUpdate.Status.OK ||
                        it.status == RemoteRefUpdate.Status.UP_TO_DATE
                }
                val refused = updates
                    .filter { it.status != RemoteRefUpdate.Status.OK &&
                        it.status != RemoteRefUpdate.Status.UP_TO_DATE }
                    .mapNotNull { it.message }
                    .filter { it.isNotBlank() }
                    .joinToString("; ")
                RemoteResult(
                    Secrets.scrub(
                        if (ok) "Tagged $name and sent it"
                        else refused.ifBlank { "The host refused the tag" },
                    ),
                    ok,
                )
            }
        } catch (e: Throwable) {
            RemoteResult(Secrets.describe(e), false)
        }
    }

    fun pull(repo: Repository, token: String?, gitUser: String? = null): RemoteResult {
        return try {
            Git(repo).use { git ->
                val command = git.pull()
                credentials(remoteOf(repo), token, gitUser)?.let { command.setCredentialsProvider(it) }
                val result = command.call()
                val ok = result.isSuccessful
                RemoteResult(
                    message = if (ok) "Pulled the latest changes" else "Already up to date",
                    success = ok,
                )
            }
        } catch (e: Throwable) {
            RemoteResult(Secrets.scrub(e.message ?: "Pull failed"), false)
        }
    }

    /**
     * The characters git's own `check-ref-format` refuses in a branch name.
     *
     * **A set rather than a literal inside the comparison**, because the backslash is one of them and
     * writing it inside a quoted set is how the line above stopped closing — a mistake whose symptom
     * is not "one string is broken" but "every symbol in the rest of the file is missing", because the
     * unterminated string swallows them all.
     */
    private val BAD_BRANCH_CHARS = setOf('~', '^', ':', '?', '*', '[', '\\', '\u007F')

    /**
     * The name of the branch checked out **in this repository**, or null when it cannot be read.
     *
     * **This answers null rather than `"main"`, and that is a fix, not a tidy-up.** It used to fall
     * back to `"main"`, which reads as a helpful default on a repository with no commits — but §13d
     * makes a project a set of worktrees, and the fallback then answers *"main"* for a repository
     * whose HEAD is unreadable while a worktree is checked out on `feature`. The caller pushes
     * `refs/heads/feature:refs/heads/main` and a branch's commits land on main, silently, which is
     * the one wrong answer that cannot be walked back. "Could not tell" and "main" are different
     * facts and a push must treat them differently.
     *
     * On 7.8.1 a linked worktree reports **its own** branch here (`getBranch()` reads the worktree's
     * HEAD, not the common one), which is what makes pushing from a branch work at all.
     */
    fun currentBranch(repo: Repository): String? = try {
        repo.branch?.removePrefix("refs/heads/")
    } catch (e: Throwable) {
        null
    }

    /**
     * **The branches that exist on this phone**, which is what the branch screen lists.
     *
     * Deliberately not [branches], which asks for `ListMode.ALL` and therefore includes
     * `refs/remotes/origin/…`. A remote-tracking ref is not a place you can work — it is git's copy of
     * what the server had last time — so offering it as a row would be a row that cannot be entered.
     * The screen names local branches; the *count* of unsent work already compares against
     * `origin/<branch>`.
     */
    fun localBranches(repo: Repository): List<String> = try {
        Git(repo).use { git ->
            // **`ALL` minus the remote refs, because `ListMode` has no `HEADS`.** JGit offers exactly
            // two modes — `ALL` and `REMOTE` — so "local only" is not a mode to ask for but a filter
            // to apply. I assumed a `HEADS` existed because git's own CLI has `--list` versus
            // `-r`; the enum is the authority and it has two values.
            git.branchList().setListMode(ListBranchCommand.ListMode.ALL).call()
                .map { it.name }
                .filter { it.startsWith("refs/heads/") }
                .map { it.removePrefix("refs/heads/") }
                .sortedWith(compareBy({ it != "main" }, { it.lowercase() }))
        }
    } catch (e: Throwable) {
        emptyList()
    }

    /**
     * Makes [name] from [from], or answers why not.
     *
     * **Refused rather than guessed** when the name is one git would reject or when it already
     * exists: a branch made twice is a person working in the wrong one for an hour.
     */
    /**
     * **A branch, from a branch — and the parent is not optional.**
     *
     * The operator's rule (2026-11-10): *"only parent branch selector stays (default parent is main, no
     * parent is not an option)"*. So [from] has a default and no null: a branch that starts from nothing
     * is not a thing this app makes, because a branch is a fork and a fork of nothing is a second
     * project.
     *
     * **`setStartPoint` takes a String or a RevCommit, not an ObjectId** — the ref name itself is the
     * argument, so resolving it first would only be a way to get it wrong.
     */
    fun createBranch(repo: Repository, name: String, from: String = AgentSession.MAIN): Result<String> {
        val problem = branchNameProblem(name)
        if (problem != null) return Result.failure(IllegalArgumentException(problem))
        return try {
            Git(repo).use { git ->
                if (git.branchList().call().any { it.name == "refs/heads/$name" }) {
                    return Result.failure(IllegalStateException("There is already a branch called $name."))
                }
                val start = "refs/heads/$from"
                if (repo.exactRef(start) == null) {
                    return Result.failure(IllegalStateException("There is no $from to branch from."))
                }
                git.branchCreate().setName(name).setStartPoint(start).call()
                Result.success(name)
            }
        } catch (e: Throwable) {
            Result.failure(IllegalStateException(Secrets.describe(e)))
        }
    }


    /**
     * **The branches holding commits `main` does not have** — the ones a row must say so about.
     *
     * The same question [deleteBranch] refuses on, asked once for the whole screen, so the caption and
     * the refusal cannot disagree. **`main` is never in the answer**: it is what the others are compared
     * to, and a row about main having work main does not have is meaningless.
     *
     * A branch whose tip cannot be read is left out rather than guessed at — a caption that says
     * "merged" for a branch nobody could read is the one wrong answer here.
     */
    fun unmergedBranches(repo: Repository): Set<String> = try {
        val mainTip = repo.resolve("refs/heads/main")
        if (mainTip == null) {
            emptySet()
        } else {
            localBranches(repo).filter { it != "main" }.filter { name ->
                val tip = repo.exactRef("refs/heads/$name")?.objectId
                tip != null && !isAncestor(repo, tip, mainTip)
            }.toSet()
        }
    } catch (e: Throwable) {
        emptySet()
    }

    /**
     * Deletes [name] from this phone, **and refuses the two deletions that lose work.**
     *
     * A branch that is not fully merged into `main` is work that exists nowhere else — deleting it
     * would be the one act here that destroys something a person cannot get back, which is the rule
     * that removed `delete_repo` (§13b.6). `main` itself is refused because it is the branch a project
     * is defined by.
     */
    fun deleteBranch(repo: Repository, name: String): Result<String> {
        if (name == "main") {
            return Result.failure(IllegalStateException("main is the branch a project is made of, so it stays."))
        }
        return try {
            Git(repo).use { git ->
                val ref = repo.exactRef("refs/heads/$name")
                    ?: return Result.failure(IllegalStateException("There is no branch called $name."))
                val tip = ref.objectId
                val mainTip = repo.resolve("refs/heads/main")
                if (mainTip != null) {
                    // **The branch's tip must be reachable from main**, which is [isAncestor]'s own
                    // question — the same one the fast-forward check asks, so it is asked the same
                    // way rather than with a second walk that could disagree.
                    if (!isAncestor(repo, tip, mainTip)) {
                        return Result.failure(
                            IllegalStateException(
                                "$name has work that is not on main. Merge it first, or the commits " +
                                    "would be left with nothing pointing at them.",
                            ),
                        )
                    }
                }
                git.branchDelete().setBranchNames(name).setForce(false).call()
                Result.success(name)
            }
        } catch (e: Throwable) {
            Result.failure(IllegalStateException(Secrets.describe(e)))
        }
    }

    /**
     * Why [name] cannot be a branch, or null.
     *
     * **`git check-ref-format`'s rules, stated once.** A name git would reject must be refused here,
     * where the person can change it, rather than by a command that fails later with git's own words.
     */
    fun branchNameProblem(name: String): String? {
        val trimmed = name.trim()
        // **git's own answer is asked as well**, because a list of characters I thought of will
        // always be missing one that git knows about. The sentences below are still the reason a
        // person reads; this is the rule that cannot drift.
        if (trimmed.isNotEmpty() && trimmed == name &&
            !CreateBranchCommand.isValidBranchName(trimmed)
        ) {
            return "git would not accept \"$trimmed\" as a branch name — try letters, digits and dashes."
        }
        return when {
            trimmed.isEmpty() -> "Give the branch a name."
            trimmed != name -> "A branch name cannot start or end with a space."
            trimmed.startsWith("-") -> "A branch name cannot start with a dash."
            trimmed == "main" -> "main is the branch a project is made of, so it already exists."
            trimmed.contains("..") -> "A branch name cannot contain .. — try a dash."
            trimmed.contains(' ') -> "A branch name cannot contain a space — try a dash."
            trimmed.any { it in BAD_BRANCH_CHARS } ->
                "A branch name cannot contain any of ~ ^ : ? * [ \\ — try a dash."
            trimmed.endsWith("/") || trimmed.endsWith(".lock") ->
                "A branch name cannot end with a slash or .lock."
            trimmed.startsWith("/") -> "A branch name cannot start with a slash."
            else -> null
        }
    }

    /** The branches in the repository. */
    fun branches(repo: Repository): List<String> {
        return try {
            Git(repo).use { git ->
                git.branchList().setListMode(ListBranchCommand.ListMode.ALL).call()
                    .map { it.name.removePrefix("refs/heads/") }
            }
        } catch (e: Throwable) {
            emptyList()
        }
    }

    /**
     * The credentials for [remoteUrl], from a token the caller has already decided to send there.
     *
     * **The decision is not made here, and that is the change.** This function used to answer null for
     * any host that was not GitHub's — which is [GitCredentials]' question, about *whose* token it is.
     * Two answers to one question is how a non-GitHub project could never be pushed at all: the vault's
     * own credential for that host was refused here, by a rule about the account's token.
     *
     * **The username comes from the remote when it names one.** `https://x-token-auth@bitbucket.org/…`
     * is git's own way of saying which username a token belongs to, and it is the only portable place
     * to say it: a host wanting a name other than the token's is a fact about that remote. Otherwise
     * the token is the username with an empty password, which is GitHub's shape and the one GitLab's
     * and Gitea's work with too.
     *
     * **A URL with no host still works**, because a local path — a bare repository beside the project,
     * which is how the tests and a folder-to-folder clone work — needs no username at all.
     */
    /**
     * How a token is presented to a host.
     *
     * **Three sources for the username, in this order, and the order is the rule:**
     *
     * 1. **the address**, when it names one — `https://x-token-auth@bitbucket.org/…`. The person wrote
     *    it on that project, so it beats anything stored;
     * 2. **the account**, when it was given a git username — GitLab documents `oauth2`, and a host may
     *    want a person's own name. It is data on the account because there is no universal answer, and
     *    a table of hosts in this file is exactly what the app stopped having;
     * 3. **the token as the username**, which is the commonest shape (GitHub, Gitea) and the only one
     *    that needs nothing typed.
     */
    private fun credentials(
        remoteUrl: String?,
        token: String?,
        gitUser: String? = null,
    ): CredentialsProvider? {
        if (token.isNullOrBlank()) return null
        if (remoteUrl == null) return null
        val named = Secrets.userOf(remoteUrl)
        val user = named ?: gitUser?.takeIf { it.isNotBlank() }
        return if (user == null) {
            UsernamePasswordCredentialsProvider(token, CharArray(0))
        } else {
            UsernamePasswordCredentialsProvider(user, token.toCharArray())
        }
    }

    /** The `origin` URL of [repo], or null when it has none. */
    private fun remoteOf(repo: Repository): String? =
        repo.config.getString("remote", "origin", "url")
}

/** The identity used for the commits the app makes. */
data class GitAuthor(
    val name: String,
    val email: String,
) {
    fun ident(): PersonIdent = PersonIdent(name, email)
}
