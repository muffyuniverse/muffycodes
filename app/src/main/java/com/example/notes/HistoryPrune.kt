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

            // The old objects are unreachable now; collecting them is what makes
            // the pruning worth doing on a phone with little room.
            try {
                Git(repo).gc().call()
            } catch (e: Exception) {
                // Collecting is housekeeping: a failure must not undo the prune.
            }
            return newestFirst.size - keep
        } finally {
            inserter.close()
        }
    }
}
