package com.example.notes

import java.io.File

/**
 * **The housekeeping that a save is too expensive to do.**
 *
 * One act, and it does not touch a commit:
 *
 * **the picture store** — delete the bytes in `.git/lfs/objects` that no pointer names any more.
 *    `git gc` does not know that directory exists, so without this a deleted picture's bytes stay on
 *    the phone for ever. It is proportional to what is stored rather than to the whole repository,
 *    which is why it is safe to run here and a full `gc` is not.
 *
 * ## Why a marker, and not a timer
 *
 * A project that never saved a picture has nothing to reclaim, and walking every tree to find out is
 * pure cost. So the things that strand content **say so** — a save that touched a picture, and
 * `pull` — by writing a marker into the project's `agent/` folder, and this
 * pass runs only where one is waiting. The marker is deleted only when the pass finished, so a crash
 * or a killed process re-runs it on the next start. The sweep is idempotent: doing it twice is doing
 * it once.
 *
 * ## Why the guards are what they are
 *
 * Walking every tree of a long history is seconds on a phone, so this runs on a daemon thread, one
 * project at a time, and it **never waits for a lock**: [RepoLocks.tryHold] rather than `hold`,
 * because the whole point of a background pass is that it does not stand in front of a commit. A
 * project whose lock is taken is skipped and its marker stays, so the next start tries again. A
 * project whose agent is switched on is skipped for the same reason at a larger scale: holding a
 * repository for seconds would stall the next commit an agent makes.
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
        /** Whether the picture store was read at all. */
        val collected: Boolean,
        /**
         * Bytes of stored picture data the sweep reclaimed.
         *
         * **Separate from [collected], because "it ran" is not "it released".** A collection on a
         * repository with nothing unreachable succeeds and frees almost nothing, and a pass that
         * reported the first as though it were the second would look identical to one that did
         * nothing. `-1` means the collection failed.
         */
        val freed: Long = 0L,
        /** Null when the work finished; the reason it did not, otherwise. */
        val stopped: String?,
        /** False when the project had no repository, which is "nothing to do" rather than a fault. */
        val tried: Boolean = true,
    ) {
        /** One sentence for the log, the notification and a test. */
        fun said(): String {
            if (stopped != null) return "housekeeping did not finish: $stopped"
            if (!tried) return "there is no repository here, so there was nothing to collect"
            val gc = if (collected) "the picture store was swept" else "the picture store was not read"
            // **Named in the sentence, because it is invisible otherwise.** A picture's old versions
            // are the largest thing this app ever deletes, and a pass that reclaimed them silently
            // would look exactly like a pass that reclaimed nothing.
            val space = if (freed <= 0L) "" else "; ${Fetcher.size(freed)} of picture data was removed"
            return "$gc$space"
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
        busy: (String, String) -> Boolean = { _, _ -> false },
        /**
         * Asked before every project: false when the account has been deleted.
         *
         * **A pass can outlive the account it was started for.** Deleting an account removes the
         * folder, and a `gc` in flight would then write its log row back into it — a deleted account
         * reappearing as an empty folder with one file in it.
         */
        alive: () -> Boolean = { true },
        deadline: Deadline = Deadline(PASS_MS),
    ): List<Done> {
        val out = mutableListOf<Done>()
        // **Folders, not `projectNames()`.** That asks which folders are repositories, and a project
        // that was cloned but never committed to has a marker and no `.git` — the case that has
        // nothing to collect. Asking the narrower question would leave its marker in place for ever,
        // which is a "nothing to do" that looks like work.
        val folders = (ws.accountDir.listFiles() ?: emptyArray())
            .filter { it.isDirectory && !ws.isHidden(it.name) }
            .map { it.name }
            .sortedBy { it.lowercase() }
        for (project in folders) {
            if (!alive()) break
            // **Every working tree, not just main's.** Each branch keeps its own `agent/` — the log,
            // the index and the marker — so a project whose work happens on a branch would never be
            // collected, and its index would grow with nothing ever sweeping it. The `Done` row is
            // labelled by session key, so two trees of one project are two rows rather than one
            // indistinguishable pair.
            for (branch in Worktrees(ws).branches(project)) {
                if (!alive()) break
                val dir = ws.treeOf(project, branch)
                if (!waiting(dir)) continue
                val why = why(dir)
                // **A running agent means "not now".** `tryHold` is a point-in-time check, and a `gc`
                // that got the lock would hold it for a minute with an agent's next commit behind it.
                if (busy(project, branch)) {
                    out += Done(
                        AgentSession.keyOf(project, branch), why, false,
                        stopped = "its agent is switched on",
                    )
                    continue
                }
                out += one(ws, AgentSession.keyOf(project, branch), why, dir, deadline)
            }
        }
        return out
    }

    /** One project: sweep the picture store, clear the marker — **in that order, and only on success.** */
    private fun one(
        ws: Workspace,
        project: String,
        why: String,
        dir: File,
        deadline: Deadline,
    ): Done {
        var collected = false
        var freed = 0L
        var stopped: String? = null
        var repository = false
        // **The lock is taken without waiting.** A commit in flight is milliseconds away from done,
        // and a marker that waits for it is a marker that gets collected on the next start anyway.
        val held = RepoLocks.tryHold(dir, "maintenance", 0L) {
            val repo = try {
                GitService.openExisting(dir)
            } catch (e: Throwable) {
                null
            }
            if (repo == null) {
                // **Not a failure.** A folder with no history has nothing stranded, so it is
                // finished — and its marker has to go, or this pass would ask the same question at
                // every start for ever.
                return@tryHold
            }
            repo.use {
                repository = true
                // **The picture store, not the object database.** A pointer's bytes are not in the
                // object database: they live in `.git/lfs/objects`, which `gc` has never looked
                // inside. Reachability is read from the pointers in the trees, so a file whose oid no
                // commit names is by definition dead. Zero means there was nothing to reclaim, which
                // is a different answer from "it could not run".
                val reclaimed = LfsStore.sweep(it)
                // **`-1` is "it refused", not "-1 bytes".** A store holding objects that no commit
                // names is a repository whose pointers could not be read; deleting on that answer
                // would destroy every picture, so the sweep declines and this reports that it did
                // not run rather than inventing a number.
                freed = if (reclaimed < 0) 0L else reclaimed
                collected = reclaimed >= 0
                if (!collected) stopped = "the picture store could not be read"
            }
        }
        if (!held) {
            return Done(project, why, false, stopped = "the project was busy, so it will try next time")
        }
        val done = Done(project, why, collected, freed, stopped, tried = repository)
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


    /** A reason from a [Throwable], never empty, because "null" is not a sentence. */
    private fun reason(e: Throwable?): String {
        if (e == null) return "no reason given"
        val said = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName ?: "it failed"
        return Secrets.explain(e, said).ifBlank { said }
    }
}
