package com.example.notes

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand
import org.eclipse.jgit.api.ResetCommand
import org.eclipse.jgit.api.Status
import org.eclipse.jgit.lib.AnyObjectId
import org.eclipse.jgit.lib.FileMode
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
     * How many versions a reader may return.
     *
     * Not a limit on the history: how far back a person can go is the setting in
     * §14 and nothing else. This is only the ceiling that stops a corrupted
     * repository from filling memory — high enough that a reader never becomes the
     * thing that decides how long the history is, which is how a shortened history
     * came to look unshortened.
     */
    const val READ_ALL = 10_000

    /** A commit in the history. */
    data class CommitInfo(
        val id: String,
        val shortId: String,
        val message: String,
        val author: String,
        val authorEmail: String,
        val timestamp: Long,
    )

    /** One entry in a git tree (the contents of a folder at one revision). */
    data class TreeEntry(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
    )

    /**
     * What a project has that GitHub does not.
     *
     * **Asked as three questions, not one.** [staged], [unstaged] and [untracked] are
     * git's own view — files not yet committed — and they are zero the moment a save
     * lands, because every change is committed as it happens. What a person means by
     * "changed" is [ahead]: work committed here and never pushed. A row built from the
     * first three alone read `changed 0` with the work unsent, so Sync stayed grey and
     * the file went nowhere.
     *
     * [ahead] is a count, and it is kept as one rather than worked out from a commit id:
     * the history is *rebuilt* when it is shortened, so every id changes and anything that
     * remembered one would read the whole history as unsent after the next sync. A number
     * that names nothing survives the rewrite.
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

    /** The result of a push or pull. */
    data class RemoteResult(
        val message: String,
        val success: Boolean,
    )

    // ---- repository lifecycle ---------------------------------------------

    /** True when [dir] holds a git repository. */
    fun isRepository(dir: File): Boolean = File(dir, ".git").isDirectory

    /**
     * Creates a repository in [dir] (or opens the one already there).
     * The repository starts with one empty commit so that it has a history to
     * browse even before anything is written.
     */
    fun initOrOpen(dir: File, author: GitAuthor): Repository {
        dir.mkdirs()
        val gitDir = File(dir, ".git")

        // A repository has to be created with Git.init, because that is what
        // writes the HEAD reference; a bare builder would leave the repository
        // without one and the first commit would fail.
        if (!gitDir.isDirectory) {
            Git.init()
                .setDirectory(dir)
                .setInitialBranch("main")
                .call()
                .close()
        }

        val repo = FileRepositoryBuilder()
            .setGitDir(gitDir)
            .setWorkTree(dir)
            .build()
        try {
            // A repository that has never been committed to has no HEAD yet.
            // (HEAD is a symbolic reference, so it only shows up once there is
            // a commit behind it.)
            if (repo.resolve("HEAD") == null) {
                Git(repo).use { git ->
                    // **Whatever is already on disk goes in the first commit.** The caller wrote
                    // the app's `.gitignore` before asking for the repository, so that the first
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
    fun clone(
        remoteUrl: String,
        dir: File,
        author: GitAuthor,
        token: String? = null,
    ): RemoteResult {
        return try {
            dir.parentFile?.mkdirs()
            val command = Git.cloneRepository()
                .setURI(remoteUrl)
                .setDirectory(dir)
                .setCloneAllBranches(true)
            if (Secrets.isGitHubHost(remoteUrl)) {
                credentials(token)?.let { command.setCredentialsProvider(it) }
            }
            val git = command.call()
            git.use {
                // The clone inherits the remote's author; make the local identity ours.
                it.repository.config.apply {
                    setString("user", null, "name", author.name)
                    setString("user", null, "email", author.email)
                    save()
                }
            }
            RemoteResult("Cloned ${Secrets.scrub(remoteUrl)}", true)
        } catch (e: Throwable) {
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
        git.add().addFilepattern(".").call()
        git.add().addFilepattern(".").setUpdate(true).call()
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
     * **Why this is safe without asking.** Every write in this app is made on one
     * worker thread, in order, so when a command fails on a lock there is no command of
     * ours holding one: what is on disk is the corpse of a process Android killed
     * mid-write. git tells a person to delete that file and try again, which is
     * reasonable in a terminal and unreasonable on a phone — nobody can reach
     * `/data/user/0/…` to do it. Until it is deleted *every* write in that project
     * fails, forever, and a save says nothing.
     *
     * [inFlight] is what keeps this honest if writes ever do run concurrently: a lock
     * is only cleared when the app is not in the middle of using it.
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
    ): String? {
        return Git(repo).use { git ->
            // A lock left by a killed process is cleared, not reported.
            retryingLock(repo) { stageEverything(git) }
            val status = retryingLock(repo) { git.status().call() }
            if (status.isClean) {
                null
            } else {
                val commit = retryingLock(repo) {
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
     * [commitAll], answering with all three outcomes instead of folding a failure into
     * "nothing to do".
     */
    fun commitEverything(
        repo: Repository,
        message: String,
        author: GitAuthor,
    ): CommitOutcome = try {
        val id = commitAll(repo, message, author)
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
     * The paths that changed in the working tree, as the repository names them.
     * This is what a commit message is written from: the names, not a count.
     */
    fun changedPaths(repo: Repository): List<String> {
        val status: Status = Git(repo).use { it.status().call() }
        return (status.added + status.changed + status.modified +
            status.removed + status.missing + status.untracked)
            .filter { !it.startsWith(".") }
            .sorted()
    }

    /**
     * Pushes the current branch, replacing what the remote holds.
     *
     * Local wins every conflict, which is the point: it cannot fail on one, and
     * the phone and GitHub end up holding the same thing.
     */
    fun pushForce(repo: Repository, token: String?): RemoteResult = push(repo, token, force = true)

    /**
     * Makes the working tree match the remote branch exactly: fetch, reset hard
     * onto it, then remove everything the remote does not have.
     */
    fun resetToRemote(repo: Repository, token: String?): RemoteResult {
        // Named outside the block below so that the answer can say which branch the
        // phone is on now — the half of a replace that is otherwise invisible.
        var landedOn: String? = null
        return try {
            val branch = currentBranch(repo)
            Git(repo).use { git ->
                val fetch = git.fetch().setRemote("origin")
                credentials(token)?.let { fetch.setCredentialsProvider(it) }
                fetch.call()

                // **Which branch is the project's?** Not necessarily the one this
                // phone is on. A repository made before GitHub's own default changed
                // has `master`, and one this app created but never synced has no
                // remote branch at all — both used to reach
                // `ResetCommand`/`RevWalk` with a ref that does not exist and answer
                // with `RefNotFoundException: Ref origin/main not found`, which reads
                // as the app being broken rather than as a different branch name.
                val remote = remoteBranch(repo, branch)
                    ?: return RemoteResult(
                        "GitHub has nothing on this project yet, so there is no copy to " +
                            "replace it with. Sync this phone's copy to put it there.",
                        false,
                    )

                // **The branch is renamed to GitHub's.** Replacing the files is the
                // visible half; the other half is that the phone and GitHub must
                // agree about which branch this project is. A phone left on `main`
                // after taking a `master` repository would push a branch GitHub does
                // not show, and the history on GitHub would stay as long — and as
                // old — as it was.
                if (remote != branch) {
                    val exists = repo.exactRef("refs/heads/$remote") != null
                    git.checkout()
                        .setName(remote)
                        .setCreateBranch(!exists)
                        .setStartPoint("refs/remotes/origin/$remote")
                        .call()
                }

                landedOn = remote
                git.reset()
                    .setMode(ResetCommand.ResetType.HARD)
                    .setRef("refs/remotes/origin/$remote")
                    .call()

                // Anything the remote does not have goes, so the folder really
                // matches what GitHub holds.
                val status = git.status().call()
                (status.untracked + status.untrackedFolders + status.missing)
                    .forEach { path -> File(repo.workTree, path).deleteRecursively() }
            }
            RemoteResult("Replaced with what GitHub has (on ${landedOn ?: "its own branch"})", true)
        } catch (e: Throwable) {
            // `Throwable`, not `Exception`: what JGit can throw on a phone includes
            // errors rather than exceptions, and an error that escapes here ends the
            // app instead of saying what went wrong.
            RemoteResult(Secrets.describe(e), false)
        }
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
            repo.exactRef("refs/remotes/origin/$name") != null

        if (exists(local)) return local
        val head = repo.exactRef("refs/remotes/origin/HEAD")
        val pointed = head?.target?.name?.removePrefix("refs/remotes/origin/")
            ?.takeIf { it.isNotBlank() && it != "HEAD" }
        if (pointed != null && exists(pointed)) return pointed
        val others = repo.refDatabase.getRefsByPrefix("refs/remotes/origin/")
            .map { it.name.removePrefix("refs/remotes/origin/") }
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

    /** Turns a JGit commit into the app's own summary of it. */
    private fun toInfo(commit: RevCommit): CommitInfo = CommitInfo(
        id = commit.name,
        shortId = commit.name.take(7),
        message = commit.fullMessage.trim(),
        author = commit.authorIdent.name,
        authorEmail = commit.authorIdent.emailAddress,
        timestamp = commit.authorIdent.`when`?.time ?: (commit.commitTime.toLong() * 1000L),
    )

    /**
     * The contents of the folder [path] at the revision [revisionId].
     * A blank path means the root of the repository.
     */
    fun treeAt(
        repo: Repository,
        path: String?,
        revisionId: String? = null,
    ): List<TreeEntry> {
        val walk = RevWalk(repo)
        try {
            val tree = treeFor(repo, walk, path, revisionId)
            val entries = mutableListOf<TreeEntry>()
            TreeWalk(repo).use { treeWalk ->
                treeWalk.addTree(tree)
                treeWalk.setRecursive(false)
                while (treeWalk.next()) {
                    val name = treeWalk.getNameString()
                    entries.add(
                        TreeEntry(
                            name = name,
                            path = if (path.isNullOrBlank()) name else "${path.trimEnd('/')}/$name",
                            isDirectory = treeWalk.isSubtree(),
                        )
                    )
                }
            }
            return entries.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        } finally {
            walk.close()
        }
    }

    /** The tree object of [path] at [revisionId]. */
    private fun treeFor(
        repo: Repository,
        walk: RevWalk,
        path: String?,
        revisionId: String?,
    ): RevTree {
        val start = revisionId?.takeIf { it.isNotBlank() }?.let { repo.resolve(it) }
            ?: repo.resolve("HEAD")
            ?: throw IllegalStateException("The repository has no commits yet")
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
     * The commits that changed the file [path], newest first, each with the
     * contents of the file at that commit. This is what the version browser
     * shows.
     */
    fun versionsOf(
        repo: Repository,
        path: String,
        /** A ceiling for the screen; [READ_ALL] for the ordinary case. */
        limit: Int = READ_ALL,
    ): List<Pair<CommitInfo, String?>> {
        val walk = RevWalk(repo)
        try {
            val commits = Git(repo).use { git ->
                git.log().addPath(path).call().take(limit).toList()
            }
            return commits.map { commit ->
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
    fun restore(repo: Repository, path: String, revisionId: String) {
        val clean = path.trim('/')
        val target = File(repo.workTree, clean)
        val walk = RevWalk(repo)
        try {
            val tree = treeFor(repo, walk, null, revisionId)
            val found = TreeWalk.forPath(repo, clean, *arrayOf<AnyObjectId>(tree))
            if (found == null) {
                target.deleteRecursively()
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
                    keep[treeWalk.pathString] = repo.open(treeWalk.getObjectId(0)).bytes
                }
            }
            target.deleteRecursively()
            target.mkdirs()
            for ((pathInTree, bytes) in keep) {
                val file = File(repo.workTree, pathInTree)
                file.parentFile?.mkdirs()
                file.writeBytes(bytes)
            }
        } finally {
            walk.close()
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
        config.setString("remote", "origin", "fetch", "+refs/heads/*:refs/remotes/origin/*")
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
    fun push(repo: Repository, token: String?, force: Boolean = false): RemoteResult {
        return try {
            val local = currentBranch(repo)
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
                credentials(token)?.let { command.setCredentialsProvider(it) }
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

    /** Pulls from the remote into the current branch. */
    fun pull(repo: Repository, token: String?): RemoteResult {
        return try {
            Git(repo).use { git ->
                val command = git.pull()
                credentials(token)?.let { command.setCredentialsProvider(it) }
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

    /** The name of the branch that is currently checked out. */
    fun currentBranch(repo: Repository): String {
        val branch = try {
            repo.branch
        } catch (e: Throwable) {
            null
        }
        return branch?.removePrefix("refs/heads/") ?: "main"
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

    /** The credentials for talking to GitHub, when a token is available. */
    private fun credentials(token: String?): CredentialsProvider? {
        if (token.isNullOrBlank()) return null
        return UsernamePasswordCredentialsProvider(token, CharArray(0))
    }
}

/** The identity used for the commits the app makes. */
data class GitAuthor(
    val name: String,
    val email: String,
) {
    fun ident(): PersonIdent = PersonIdent(name, email)
}
