package com.example.notes

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * **One lock per repository, and every writer holds it.**
 *
 * This exists because the sentence it replaces was false. `GitService.retryingLock` said:
 *
 * > *"Every write in this app is made on one worker thread, in order, so when a command fails on a
 * > lock there is no command of ours holding one: what is on disk is the corpse of a process
 * > Android killed mid-write."*
 *
 * There are **two** workers — `AppViewModel.worker` and `AgentRunner.worker` — and the runner's runs
 * every tool call, including `AgentTools.commit` → `ProjectGit.commitOutcome` →
 * `GitService.commitAll`. So a save in the editor and an agent's `edit` could commit to one
 * repository at the same time, and because `inFlight` was never passed, the first of the two to hit
 * a lock called `clearStaleLock` — which **deleted the other's live `index.lock`** and retried.
 *
 * That is not corruption. It is worse in one way: it looks like success. A commit that had its lock
 * taken away underneath it can write a tree that does not match the message it was given, and the
 * only record is a history that disagrees with the person's memory of what they did.
 *
 * ## What this is not
 *
 * It is **not** a queue. Two writers waiting on one repository is contention, not scheduling: a
 * commit is milliseconds, so the honest answer is to wait rather than to invent a way for a commit
 * to be refused. It is also not per-agent: the repository is the project's, and N agents plus the
 * editor and the sync all write to the same one — which is exactly why the lock is keyed by the
 * directory rather than by who is asking.
 *
 * ## Why a lock is not enough, and what that means
 *
 * Two agents editing one file is **not** a race this can fix. There is no merge and no conflict:
 * one write simply lands last. The app's answer to that is the prompt's rule — read before you
 * change one, and prefer `grep` to a line number — plus a trace line when a write follows a stale
 * read. A lock that claimed to prevent lost writes would be the more dangerous kind of wrong.
 */
object RepoLocks {

    /**
     * One lock per repository path, **shared by every holder in the process.**
     *
     * Keyed by the canonical path, because a project reached as `…/projects/x` and as `…/projects/x/`
     * is one repository and two strings, and two locks over one repository is the bug this class
     * exists to prevent — with extra steps.
     */
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    /** Who holds what, for the trace line and for a report. Guarded by itself. */
    private val holders = ConcurrentHashMap<String, String>()

    /** How long a hold may go on before it is worth saying so. */
    const val SLOW_MS = 5_000L

    private fun keyFor(dir: File): String = try {
        dir.canonicalPath
    } catch (t: Throwable) {
        // A path that will not canonicalise is still a path; the absolute one is the next best
        // answer, and refusing to lock at all would be the worst one.
        dir.absolutePath
    }

    /**
     * Runs [block] holding the repository's lock, and **says so if it waited too long.**
     *
     * [who] is the name a person reads in a bug report — `save`, `sync`, `agent #2`, `init` — and it
     * is written on the *way out* only when the hold was slow, so a healthy repository pays one
     * comparison and nothing else.
     *
     * **Why the trace line matters more than the lock.** A mutex's failure mode is not corruption,
     * it is a stall: something holds the lock and the app appears to have stopped. A stall with no
     * name is a report nobody can act on ("it froze"), and one line naming the holder and the
     * duration is the difference between that and a fix. It is `Deadline`'s lesson applied to a
     * lock — the thing that can hang must say what it was doing.
     */
    fun <T> hold(dir: File, who: String, block: () -> T): T {
        val key = keyFor(dir)
        val lock = locks.computeIfAbsent(key) { ReentrantLock() }
        val began = System.currentTimeMillis()
        lock.lock()
        val waited = System.currentTimeMillis() - began
        if (waited >= SLOW_MS) {
            // **Named before the work, because the wait already happened.** A line written after
            // would report the wait as part of the hold, and the two are different faults: a writer
            // that *waited* is one behind a busy repository, and a writer that *held* is the one
            // everybody else was waiting for. Naming only the total would make the victim look
            // like the culprit.
            Trace.note("repo-wait", "${dir.name} · $who waited $waited ms for ${holders[key] ?: "?"}")
        }
        // **The previous holder, restored on the way out.** `commitOutcome` takes this lock again
        // from inside a caller that already holds it (a reentrant hold on the same thread), and
        // `remove` on the inner exit would leave the *outer* hold unnamed — so a stall in the
        // remaining work would say nobody was holding the repository, which is the one thing a
        // stall report must not say.
        val was = holders.put(key, who)
        try {
            return block()
        } finally {
            if (was == null) holders.remove(key) else holders[key] = was
            lock.unlock()
            val held = System.currentTimeMillis() - began - waited
            if (held >= SLOW_MS) {
                Trace.note("repo-hold", "${dir.name} · $who held $held ms")
            }
        }
    }

    /**
     * The name of whatever holds [dir], or null when nothing does.
     *
     * **Asked without taking the lock.** `synchronized` cannot answer "is it held" — asking by
     * taking it would block the caller trying to find out whether it would block. `ReentrantLock`
     * can, and the answer is what a trace line and a failing test both need.
     */
    fun holderOf(dir: File): String? = holders[keyFor(dir)]

    /** True when anything in this process holds [dir]. */
    fun isHeld(dir: File): Boolean = locks[keyFor(dir)]?.isLocked == true

    /**
     * Runs [block] only if the lock is free, or says why it did not.
     *
     * Not used by the app — the app waits, because a commit is milliseconds and refusing one would
     * be a new way for work to fail. This is for a test that must prove the lock is *held* rather
     * than prove it eventually frees, and for any future caller that must not block.
     */
    fun tryHold(dir: File, who: String, waitMs: Long, block: () -> Unit): Boolean {
        val key = keyFor(dir)
        val lock = locks.computeIfAbsent(key) { ReentrantLock() }
        if (!lock.tryLock(waitMs, TimeUnit.MILLISECONDS)) return false
        val was = holders.put(key, who)
        try {
            block()
            return true
        } finally {
            if (was == null) holders.remove(key) else holders[key] = was
            lock.unlock()
        }
    }
}
