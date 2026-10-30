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
