package com.example.notes

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.CommitBuilder
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevWalk

/**
 * Keeps a history to a set length.
 *
 * The oldest commits are dropped by **rebuilding the chain**: the newest [keep]
 * commits are written again, each with the same tree, message, author and time,
 * and each pointing at the previous one — except the oldest, which points at
 * nothing. The branch then moves to the rebuilt tip.
 *
 * Because every rebuilt commit carries the identical tree, the working tree
 * does not change at all: only how far back the history goes.
 */
object HistoryPrune {

    /**
     * Keeps only the newest [keep] commits of the current branch and returns how
     * many were dropped. [keep] of 0 or less means "keep everything", so the
     * setting can express "no limit".
     */
    fun prune(repo: Repository, keep: Int): Int {
        if (keep <= 0) return 0
        // The full name is needed: the short one resolves through a lookup that
        // differs between the two forms, and a miss here would silently do
        // nothing at all.
        val branch = repo.fullBranch ?: repo.branch ?: return 0
        val ref = repo.findRef(branch) ?: return 0

        // Walk back only as far as needed: one commit past the limit tells us
        // there is something to drop, and no further work is required.
        val newestFirst = mutableListOf<org.eclipse.jgit.revwalk.RevCommit>()
        RevWalk(repo).use { walk ->
            walk.markStart(walk.parseCommit(ref.objectId))
            for (commit in walk) {
                newestFirst.add(commit)
                if (newestFirst.size > keep) break
            }
        }
        if (newestFirst.size <= keep) return 0

        val kept = newestFirst.take(keep).reversed() // oldest first
        val inserter = repo.newObjectInserter()
        try {
            var parent: ObjectId? = null
            for (commit in kept) {
                val builder = CommitBuilder()
                builder.setTreeId(commit.tree)
                builder.setAuthor(commit.authorIdent)
                builder.setCommitter(commit.committerIdent)
                builder.setMessage(commit.fullMessage)
                parent?.let { builder.addParentId(it) }
                val id = inserter.insert(builder)
                parent = id
            }
            inserter.flush()

            val tip = parent ?: return 0
            val update = repo.updateRef(ref.name)
            update.setNewObjectId(tip)
            update.setForceUpdate(true)
            update.update()

            // **Collecting is the expensive half, and it does not belong in the change that
            // caused it.** `gc` walks every object in the repository; running it after every
            // single commit made each save wait for a full collection, which on a phone with a
            // long history is seconds rather than milliseconds — and the version was already
            // written and the ref already moved, so the work a person was waiting for had
            // finished before the housekeeping began.
            //
            // The objects it would collect are unreachable the moment the ref moves, so leaving
            // them is a few kilobytes on disk and no incorrectness at all. [collect] does it on
            // demand, where somebody can be told how long it takes.
            return newestFirst.size - keep
        } finally {
            inserter.close()
        }
    }

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
            editor.add(
                org.eclipse.jgit.dircache.DirCacheEditor.PathEdit(path) { e ->
                    e.fileMode = entry.fileMode
                    e.setObjectId(blob)
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
    fun collect(repo: Repository): Boolean = try {
        Git(repo).gc().call()
        true
    } catch (e: Exception) {
        // Housekeeping: a repository that could not be collected is still a working repository.
        false
    }
}
