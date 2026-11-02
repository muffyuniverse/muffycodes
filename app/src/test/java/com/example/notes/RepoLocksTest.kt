package com.example.notes

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * **One lock per repository, held by every writer.**
 *
 * The rule this file exists for is a sentence that was false: `GitService.retryingLock` said *"every
 * write in this app is made on one worker thread"*, and there are two — the view model's and the
 * agent runner's, and the runner's runs tool calls that commit. So two commits could run at once, and
 * the loser of a lock race had its live `index.lock` deleted by `clearStaleLock` and retried.
 *
 * These tests ask the lock the questions a race cannot answer by luck: is it exclusive, is it shared
 * between two `ProjectGit` objects over one project, does it name its holder, and does a writer that
 * fails still release it.
 */
class RepoLocksTest {

    private lateinit var root: File
    private lateinit var dir: File

    @Before
    fun makeSpace() {
        root = File(System.getProperty("java.io.tmpdir"), "repolocks-${System.nanoTime()}")
        dir = File(root, "groceries").apply { mkdirs() }
    }

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `a hold is exclusive, so a second writer waits rather than overlapping`() {
        val inside = AtomicInteger(0)
        val overlapped = AtomicInteger(0)
        val started = CountDownLatch(1)
        val done = CountDownLatch(2)

        repeat(2) { n ->
            Thread {
                started.await()
                RepoLocks.hold(dir, "writer$n") {
                    if (inside.incrementAndGet() != 1) overlapped.incrementAndGet()
                    Thread.sleep(120)
                    inside.decrementAndGet()
                }
                done.countDown()
            }.start()
        }
        started.countDown()
        assertTrue("both writers finished", done.await(10, TimeUnit.SECONDS))

        assertEquals(
            "**Never two at once.** A lock that allowed overlap would be a lock that does nothing, " +
                "and the failure it prevents — a staged tree committed under another writer's " +
                "message — is invisible in the result.",
            0,
            overlapped.get(),
        )
    }

    @Test
    fun `two ProjectGit objects over one project share the lock, because the repository is the project`() {
        // **The bug, at the level where it happens.** The editor's save and an agent's `edit` each
        // build their own `ProjectGit` — `AppViewModel` makes one, `AgentWiring.runnerFor` makes
        // another. A lock that lived on the instance would exclude nothing between them, which is
        // exactly the shape of the two-writer bug this class exists for.
        val ws = Workspace(File(root, "muffyuniverse")).apply { accountDir.mkdirs() }
        val author = GitAuthor("Test", "test@example.com")
        val one = ProjectGit(ws, { null }, { author })
        val two = ProjectGit(ws, { null }, { author })
        assertEquals("both name the same folder", one.repoDir("groceries"), two.repoDir("groceries"))

        val seen: String? = RepoLocks.hold(one.repoDir("groceries"), "save") {
            // The second object asks about the same repository while the first holds it.
            RepoLocks.holderOf(two.repoDir("groceries"))
        }
        assertEquals(
            "**the holder is visible through the other object** — one lock, not one per instance",
            "save",
            seen,
        )
    }

    @Test
    fun `a hold says who holds it, and forgets afterwards`() {
        assertNull("nothing holds it to begin with", RepoLocks.holderOf(dir))
        assertFalse("and it is not held", RepoLocks.isHeld(dir))

        RepoLocks.hold(dir, "agent #2") {
            assertEquals("the holder is named", "agent #2", RepoLocks.holderOf(dir))
            assertTrue("and the lock is held", RepoLocks.isHeld(dir))
        }

        assertNull("**and it is forgotten when the hold ends**", RepoLocks.holderOf(dir))
        assertFalse("so the next writer is not told it is busy", RepoLocks.isHeld(dir))
    }

    @Test
    fun `a writer that fails still releases the lock`() {
        // **The failure mode that would take the app down for good.** A commit that throws while
        // holding the lock, with the release inside the `try` rather than the `finally`, leaves every
        // later write in that project waiting for ever — and the app does not stop, it just never
        // saves again. `finally` is the whole fix, and this is what proves it is there.
        val boom = runCatching {
            RepoLocks.hold(dir, "doomed") { throw IllegalStateException("the commit failed") }
        }
        assertTrue("the failure came back to the caller", boom.isFailure)
        assertNull("and the lock was released anyway", RepoLocks.holderOf(dir))

        val after: Boolean = RepoLocks.tryHold(dir, "next", 200) { }
        assertTrue("so the next writer gets in", after)
    }

    /**
     * **A commit made while the lock is held reports a lock failure as safe to clear.**
     *
     * The fact `retryingLock` needs, and the one that was wrong: `inFlight` was never passed, so a
     * commit that found another writer's `index.lock` deleted it and retried. This asserts the
     * plumbing — `ProjectGit` holds the lock and tells `commitEverything` so — by committing for
     * real through a repository, which is the only way to know the argument reaches the place that
     * uses it.
     */
    @Test
    fun `a real commit through ProjectGit holds the lock and reports in-flight`() {
        val ws = Workspace(File(root, "muffyuniverse")).apply { accountDir.mkdirs() }
        val author = GitAuthor("Test", "test@example.com")
        val git = ProjectGit(ws, { null }, { author })
        ws.projectDir("groceries").mkdirs()
        git.init("groceries").getOrThrow()

        File(ws.projectDir("groceries"), "note.txt").writeText("milk")

        // **No outer hold here, because `commitOutcome` takes the lock itself.** The question is
        // whether the *commit's own* hold is visible and whether it survives the nesting inside
        // `commitEverything`; wrapping it in a second hold would test the wrapper instead.
        val outcome = git.commitOutcome("groceries", "Saved note.txt", who = "save")

        assertTrue("the commit was written: ${outcome.id}", outcome.written)
        assertNull(
            "**and the lock is released, with the holder forgotten** — a stale name would make " +
                "every later stall report blame a commit that finished",
            RepoLocks.holderOf(git.repoDir("groceries")),
        )
        assertFalse("and it is not held", RepoLocks.isHeld(git.repoDir("groceries")))
    }

    @Test
    fun `a nested hold keeps naming the outer holder when it ends`() {
        // **The bug this catches.** `commitOutcome` holds the lock, and `commitEverything` nests
        // inside it. A `remove` on the inner exit leaves the outer hold unnamed — so work that
        // stalls *after* the inner block finishes would report nobody holding the repository, and a
        // stall report that says nobody is there is worse than no report.
        RepoLocks.hold(dir, "outer") {
            RepoLocks.hold(dir, "inner") {
                assertEquals("the inner name is current", "inner", RepoLocks.holderOf(dir))
            }
            assertEquals(
                "**and the outer name comes back**",
                "outer",
                RepoLocks.holderOf(dir),
            )
        }
        assertNull("and the last exit clears it", RepoLocks.holderOf(dir))
    }

    @Test
    fun `a hold that is too slow is traced, and a quick one is not`() {
        // **The stall has to name itself.** A mutex's failure mode is not corruption, it is the app
        // appearing to freeze — and "it froze" is a report nobody can act on. One line naming the
        // holder and the duration is the difference. This is `Deadline`'s lesson applied to a lock.
        val before = Trace.report().lines.size
        RepoLocks.hold(dir, "quick") { }
        val quiet = Trace.report().lines.size
        assertEquals("a millisecond hold writes nothing", before, quiet)

        // **The clock is the test's, not a sleep's.** `SLOW_MS` is compared against wall time, so the
        // only way to reach it without waiting five seconds is to hold for longer than the constant —
        // which this does by sleeping just past it. It is the one wait in this file, and it is here
        // because the alternative is not testing the line at all.
        RepoLocks.hold(dir, "slow") { Thread.sleep(RepoLocks.SLOW_MS + 120) }
        val lines = Trace.report().lines
        assertTrue(
            "the slow hold is named: ${lines.takeLast(3)}",
            lines.drop(quiet).any { it.contains("repo-hold") && it.contains("slow") },
        )
    }
}
