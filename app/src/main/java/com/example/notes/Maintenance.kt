package com.example.notes

import java.io.File

/**
 * **The housekeeping that a save is too expensive to do.**
 *
 * Two acts, and both exist because [HistoryPrune.prune] only moves a ref:
 *
 * 1. **the sweep** — delete the index rows of saved versions whose content no commit reaches any
 *    more. A prune rebuilds the chain with identical trees, so the blobs of the dropped commits are
 *    now unreachable, and a row naming one would be a search hit nobody can read;
 * 2. **the collection** — `git gc`, which finally deletes the objects themselves. It is proportional
 *    to the whole repository rather than to what changed, which is why [HistoryPrune] refuses to run
 *    it inside a save: every save would wait for a full walk.
 *
 * ## Why a marker, and not a timer
 *
 * A project that has never been pruned has nothing to collect, and `gc` on it is pure cost. So the two
 * things that strand content **say so** — [ProjectGit.prune] and [ProjectGit.replaceWithRemote] write
 * a marker into the project's `agent/` folder — and this pass runs only where one is waiting. The
 * marker is deleted only when the pass finished, so a crash or a killed process re-runs both halves on
 * the next start. Both halves are idempotent: a sweep deletes rows, a collection deletes objects, and
 * doing either twice is doing it once.
 *
 * ## Why the order is not negotiable
 *
 * **The sweep has to run before the collection.** `gc` deletes the very objects the sweep needs in
 * order to know which rows are dead, and after it runs there is no way to ask the question at all —
 * the objects are gone and every row looks equally unresolvable.
 *
 * ## Why the guards are what they are
 *
 * `gc` is seconds to minutes on a phone with a long history, so this runs on a daemon thread, one
 * project at a time, and it **never waits for a lock**: [RepoLocks.tryHold] rather than `hold`,
 * because the whole point of a background pass is that it does not stand in front of a commit. A
 * project whose lock is taken is skipped and its marker stays, so the next start tries again. A
 * project whose agent is switched on is skipped for the same reason at a larger scale: a `gc` holding
 * a repository for a minute would stall the next commit an agent makes.
 */
object Maintenance {

    /** Where a project says it has housekeeping waiting. Inside `agent/`, which is git-excluded. */
    const val MARKER = "maintenance-due"

    /** How long the whole pass may take before it gives up on the rest, in milliseconds. */
    const val PASS_MS = 20L * 60L * 1000L

    /** What one project's housekeeping did. */
    data class Done(
        val project: String,
        /** Why it was asked for — `"prune"` or `"pull"`. */
        val why: String,
        /** Rows swept, vectors released, and whether the collection ran. */
        val rows: Int,
        val vectors: Int,
        val collected: Boolean,
        /** Null when the work finished; the reason it did not, otherwise. */
        val stopped: String?,
        /** False when the project had no repository, which is "nothing to do" rather than a fault. */
        val tried: Boolean = true,
    ) {
        /** One sentence for the log, the notification and a test. */
        fun said(): String {
            if (stopped != null) return "housekeeping did not finish: $stopped"
            if (!tried) return "there is no repository here, so there was nothing to collect"
            val swept = when {
                rows == 0 -> "no index rows were left behind"
                rows == 1 -> "1 index row was left behind"
                else -> "$rows index rows were left behind"
            }
            val freed = if (vectors == 0) "" else ", releasing $vectors vector(s)"
            val gc = if (collected) "; the repository was collected" else "; the collection did not run"
            return "$swept$freed$gc"
        }
    }

    /**
     * Says that [project] has housekeeping waiting.
     *
     * **Idempotent, and it keeps the first reason.** A project pruned ten times in one session has one
     * thing to collect, and the first reason is as true as the tenth.
     */
    fun mark(projectDir: File, why: String) {
        try {
            val dir = File(projectDir, AgentLog.DIR)
            dir.mkdirs()
            val file = File(dir, MARKER)
            if (file.exists()) return
            file.writeText(why + "\n")
        } catch (e: Throwable) {
            // **A marker that could not be written is a collection that happens later**, on the next
            // prune that succeeds at writing one. Nothing about the project is wrong.
        }
    }

    /** True when [projectDir] is waiting for housekeeping. */
    fun waiting(projectDir: File): Boolean = File(File(projectDir, AgentLog.DIR), MARKER).isFile

    /** The reason a project gave, or `"housekeeping"` when the marker cannot be read. */
    fun why(projectDir: File): String = try {
        File(File(projectDir, AgentLog.DIR), MARKER).readText().trim()
            .ifBlank { "housekeeping" }
    } catch (e: Throwable) {
        "housekeeping"
    }

    /**
     * Runs housekeeping for every project that asked, **without waiting for any lock.**
     *
     * @return one [Done] per project that was attempted, in project-name order.
     */
    fun run(
        ws: Workspace,
        /** Asked per project: true when an agent is switched on there. */
        busy: (String) -> Boolean = { false },
        deadline: Deadline = Deadline(PASS_MS),
    ): List<Done> {
        val out = mutableListOf<Done>()
        for (project in ws.projectNames()) {
            val dir = ws.projectDir(project)
            if (!waiting(dir)) continue
            val why = why(dir)
            // **A running agent means "not now".** `tryHold` is a point-in-time check, and a `gc`
            // that got the lock would hold it for a minute with an agent's next commit behind it.
            if (busy(project)) {
                out += Done(project, why, 0, 0, false, "its agent is switched on")
                continue
            }
            val done = one(ws, project, why, dir, deadline)
            out += done
        }
        return out
    }

    /** One project: sweep, collect, clear the marker — **in that order, and only on success.** */
    private fun one(
        ws: Workspace,
        project: String,
        why: String,
        dir: File,
        deadline: Deadline,
    ): Done {
        var rows = 0
        var vectors = 0
        var collected = false
        var stopped: String? = null
        var repository = false
        // **The lock is taken without waiting.** A commit in flight is milliseconds away from done,
        // and a marker that waits for it is a marker that gets collected on the next start anyway.
        val held = RepoLocks.tryHold(dir, "maintenance", 0L) {
            val index = try {
                SearchIndex.open(dir)
            } catch (e: Throwable) {
                stopped = "the index could not be opened: ${reason(e)}"
                return@tryHold
            }
            try {
                val repo = try {
                    GitService.openExisting(dir)
                } catch (e: Throwable) {
                    null
                }
                if (repo == null) {
                    // **Not a failure.** A folder with no history has nothing stranded and no index
                    // rows to sweep, so it is finished — and its marker has to go, or this pass
                    // would ask the same question at every start for ever.
                    return@tryHold
                }
                repo.use {
                    repository = true
                    // **The reachable set first**, because the collection destroys the evidence.
                    val reachable = try {
                        reachableBlobs(it, deadline)
                    } catch (e: StoppedException) {
                        stopped = "the time limit ran out while reading the history"
                        return@use
                    } catch (e: Throwable) {
                        stopped = "the history could not be read: ${reason(e)}"
                        return@use
                    }
                    val (swept, released) = index.sweep(reachable)
                    rows = swept
                    vectors = released
                    // **Now, and only now.** `gc` deletes the objects the sweep just proved dead.
                    collected = HistoryPrune.collect(it)
                    if (!collected) stopped = "the repository could not be collected"
                }
            } finally {
                index.close()
            }
        }
        if (!held) {
            return Done(project, why, 0, 0, false, "the project was busy, so it will try next time")
        }
        val done = Done(project, why, rows, vectors, collected, stopped, tried = repository)
        // **The marker goes only when the work did.** A failed collection keeps it, so the next start
        // asks again rather than silently never collecting.
        if (stopped == null) {
            try {
                File(File(dir, AgentLog.DIR), MARKER).delete()
            } catch (e: Throwable) {
                // The marker outliving a successful pass costs one extra sweep, which is idempotent.
            }
        }
        try {
            AgentLog.open(dir).appNote("maintenance", "$why: ${done.said()}")
        } catch (e: Throwable) {
            // The log is a record, not the work; the notification carries the same sentence.
        }
        return done
    }

    /**
     * Every blob the current branch still reaches.
     *
     * **A tree walk over every commit, not a diff**, because the question is "what exists", not "what
     * changed": a file that has not been touched in a hundred commits is still reachable, and asking
     * only about changes would sweep its rows.
     */
    private fun reachableBlobs(repo: org.eclipse.jgit.lib.Repository, deadline: Deadline): Set<String> {
        val head = repo.resolve("HEAD") ?: return emptySet()
        val out = mutableSetOf<String>()
        org.eclipse.jgit.revwalk.RevWalk(repo).use { walk ->
            walk.markStart(walk.parseCommit(head))
            for (commit in walk) {
                deadline.check("maintenance")
                org.eclipse.jgit.treewalk.TreeWalk(repo).use { tree ->
                    tree.addTree(commit.tree)
                    tree.isRecursive = true
                    while (tree.next()) {
                        // **A submodule is a commit id in a tree, not a blob** — adding it to the
                        // reachable set would be a blob id that is never a chunk's version.
                        if (tree.getFileMode(0) == org.eclipse.jgit.lib.FileMode.GITLINK) continue
                        out += tree.getObjectId(0).name
                    }
                }
            }
        }
        return out
    }

    /** A reason from a [Throwable], never empty, because "null" is not a sentence. */
    private fun reason(e: Throwable?): String {
        if (e == null) return "no reason given"
        val said = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "it failed"
        return Secrets.explain(e, said).ifBlank { said }
    }
}
