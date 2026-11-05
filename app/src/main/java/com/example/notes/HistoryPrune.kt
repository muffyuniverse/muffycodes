package com.example.notes

import java.io.File
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk

/**
 * **The history is never shortened, and this is what makes that affordable.**
 *
 * There used to be a length: a number a person chose, and a rewrite that dropped everything older. It
 * existed because a phone cannot hold every version of everything — and it answered that by asking the
 * person to guess a number, which is a question with no good answer.
 *
 * The answer turned out to be about the *kind* of file rather than about a count. Text compresses 120x
 * (measured: two hundred versions of a 200 KB document is 340 KB), so keeping all of it costs almost
 * nothing and losing it costs the feature. A picture does not compress at all (200 versions of a 400 KB
 * PNG is 79 MB), so it keeps exactly one version — and [keepLatest] is how.
 *
 * So there is no length and nothing is dropped. What remains here is the one rewrite the app still
 * performs: collapsing a binary's trees onto its newest bytes, which is not a shortened history but the
 * same history with one file's older states released.
 */
object HistoryPrune {

    /**
     * Makes every commit that holds [path] hold the same blob — the one at HEAD.
     *
     * **This is what gives a binary one version.** A picture has no history worth keeping: its
     * versions are megabytes each, they do not compress, and the operator's rule is that a change to
     * one is a new file rather than a new version. So the commits keep their messages and their
     * order, and only the tree entries for that one path are rewritten to point at the newest bytes.
     *
     * **Why a tree rewrite rather than deleting the old objects.** LFS objects are not reachable
     * *from commits* in the first place — `.git/lfs/objects` is a store that `git gc` never looks at —
     * so leaving old pointers in old trees keeps their bytes alive for ever. Rewriting the trees is
     * what makes the bytes unreachable; the collection in [collect] and the LFS sweep then reclaim
     * them.
     *
     * **The oldest rebuilt commit keeps its ORIGINAL parents.** A rewrite that dropped them would
     * detach the chain from everything before it, and those commits would become unreachable —
     * collected, but also unreadable, which is a history shortened by a side effect rather than by a
     * decision. The ref must also actually move: a rewrite that inserts commits and leaves the branch
     * where it was has `gc` reclaim the *rewrite* and leave the originals.
     *
     * Returns how many commits were rewritten, which is zero when there is nothing to do.
     */
    fun keepLatest(repo: Repository, path: String): Int {
        val branch = repo.fullBranch ?: repo.branch ?: return 0
        val ref = repo.findRef(branch) ?: return 0
        val clean = path.trim('/')

        val newestFirst = mutableListOf<org.eclipse.jgit.revwalk.RevCommit>()
        RevWalk(repo).use { walk ->
            walk.markStart(walk.parseCommit(ref.objectId))
            for (commit in walk) newestFirst.add(commit)
        }
        if (newestFirst.isEmpty()) return 0

        val reader = repo.newObjectReader()
        try {
            // The blob HEAD holds for this path. Absent means the file is not at the newest commit,
            // and there is then no version to collapse the others onto.
            val head = newestFirst.first()
            val atHead = org.eclipse.jgit.treewalk.TreeWalk
                .forPath(repo, clean, *arrayOf<org.eclipse.jgit.lib.AnyObjectId>(head.tree))
                ?.getObjectId(0) ?: return 0

            var rewritten = 0
            val inserter = repo.newObjectInserter()
            try {
                var parent: ObjectId? = null
                // Oldest first: `parent` is the rebuilt commit just below this one.
                for (commit in newestFirst.reversed()) {
                    val tree = treeWith(repo, reader, commit.tree, clean, atHead) ?: commit.tree
                    val builder = CommitBuilder()
                    builder.setTreeId(tree)
                    builder.setAuthor(commit.authorIdent)
                    builder.setCommitter(commit.committerIdent)
                    builder.setMessage(commit.fullMessage)
                    // **The oldest keeps its own parents**, so the chain still joins everything
                    // before it.
                    if (parent != null) {
                        builder.addParentId(parent)
                    } else {
                        commit.parents.forEach { builder.addParentId(it.id) }
                    }
                    parent = inserter.insert(builder)
                    rewritten++
                }
                inserter.flush()
                val tip = parent ?: return 0
                // **The ref must move, or `gc` reclaims the rewrite instead of the originals.**
                if (tip == ref.objectId) return 0
                val update = repo.updateRef(ref.name)
                update.setNewObjectId(tip)
                update.setForceUpdate(true)
                update.update()
                return rewritten
            } finally {
                inserter.close()
            }
        } finally {
            reader.close()
        }
    }

    /**
     * [tree] with [path] pointing at [blob], or null when the path is not in it.
     *
     * `DirCache` rather than a hand-rolled walk, for the reason `writeTreeWithout` gives: it keeps
     * every surviving entry's id, mode and attributes, and writes a tree that differs from the
     * original only at the one path.
     */
    private fun treeWith(
        repo: Repository,
        reader: org.eclipse.jgit.lib.ObjectReader,
        tree: ObjectId,
        path: String,
        blob: ObjectId,
    ): ObjectId? {
        val found = org.eclipse.jgit.treewalk.TreeWalk.forPath(repo, path, *arrayOf<ObjectId>(tree))
            ?: return null
        val old = found.getObjectId(0)
        if (old == blob) return tree
        return try {
            val cache = org.eclipse.jgit.dircache.DirCache.newInCore()
            val builder = cache.builder()
            builder.addTree(
                ByteArray(0),
                org.eclipse.jgit.dircache.DirCacheEntry.STAGE_0,
                reader,
                tree,
            )
            builder.finish()
            val entry = cache.getEntry(path) ?: return null
            val editor = cache.editor()
            // **`PathEdit` is an abstract class, not a functional interface.** The lambda form does not
            // compile — `fileMode` and `setObjectId` come back unresolved because the parameter is not
            // a `DirCacheEntry` — so it is subclassed with the one method that matters.
            editor.add(
                object : org.eclipse.jgit.dircache.DirCacheEditor.PathEdit(path) {
                    override fun apply(e: org.eclipse.jgit.dircache.DirCacheEntry) {
                        e.fileMode = entry.fileMode
                        e.setObjectId(blob)
                    }
                },
            )
            editor.finish()
            repo.newObjectInserter().use { inserter ->
                val id = cache.writeTree(inserter)
                inserter.flush()
                id
            }
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * Collects the objects a prune left behind — **on demand, never as part of a save.**
     *
     * `gc` is proportional to the whole repository rather than to what changed, so running it inside
     * the commit made every save wait for a full walk. The unreachable objects it would remove are
     * cheap to keep and harmless to keep, so the collection is a separate act that a person (or a
     * maintenance pass) asks for, and its cost is visible where it is paid.
     */
    fun collect(repo: Repository): Long = try {
        val before = sizeOf(repo)
        // **The reflog has to go first, and this is the whole trick.**
        //
        // A rewrite leaves the ORIGINAL commits named by `logs/refs/heads/<branch>`, and `gc` treats
        // everything a reflog names as reachable — so a plain `gc` keeps the old commits, their trees,
        // and the picture blobs those trees point at, for ever. Measured: a 400 KB blob survived a
        // plain `gc`, a `gc` with `setExpire(now)`, and a `gc` with `expire = 0`; it went only once the
        // reflog was deleted.
        //
        // **Nothing in this app reads the reflog.** Undo is a commit, not a reflog entry; the history
        // screen reads `RevWalk` from HEAD. So the only thing the reflog protects here is bytes the
        // person has already agreed to release by saving over a picture.
        expireReflog(repo)
        // **Both clocks, because they are separate.** The loose-object expiry and the pack expiry each
        // default to two weeks, and setting only one leaves the other protecting the object.
        val gc = org.eclipse.jgit.internal.storage.file.GC(
            repo as org.eclipse.jgit.internal.storage.file.FileRepository
        )
        gc.setExpireAgeMillis(0L)
        gc.setPackExpireAgeMillis(0L)
        gc.gc().get()
        // **The answer is measured, not assumed.** "the collection ran" is not the same as "anything
        // was released", and a pass that reported the first as though it were the second would look
        // identical to one that did nothing.
        (before - sizeOf(repo)).coerceAtLeast(0L)
    } catch (e: Throwable) {
        // **Housekeeping: a repository that could not be collected is still a working repository**,
        // and `-1` says so rather than claiming nothing was released.
        -1L
    }

    /**
     * Deletes the branch and `HEAD` reflogs.
     *
     * **The one thing that makes a rewrite actually free its bytes**, and it is safe here for a reason
     * worth stating: the app never reads a reflog. There is no "undo" that walks one — undo is a commit
     * — and the history screen walks `HEAD`. What the reflog protects in a normal repository is the
     * safety net for a mistaken `reset`; in this app the only rewrites are the ones it performed on
     * purpose.
     */
    private fun expireReflog(repo: Repository) {
        try {
            File(repo.directory, "logs").deleteRecursively()
        } catch (e: Throwable) {
            // A reflog that could not be deleted keeps the bytes; the next pass tries again.
        }
    }

    /** How many bytes the repository occupies, so a collection can say what it released. */
    private fun sizeOf(repo: Repository): Long {
        var total = 0L
        val dir = repo.directory ?: return 0L
        for (name in listOf("objects", "logs")) {
            File(dir, name).walkTopDown().forEach { if (it.isFile) total += it.length() }
        }
        return total
    }
}
