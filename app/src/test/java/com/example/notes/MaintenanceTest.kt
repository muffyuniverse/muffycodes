package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * **The housekeeping a save is too expensive to do.**
 *
 * Two questions produced this file, and both are the operator's own words:
 *
 * > *"git gc never runs? so we actually never prune anything, just remove commits?"*
 *
 * Yes: `HistoryPrune.prune` rebuilds the chain and moves a ref, and nothing ever deleted the objects
 * it stranded — the function written to delete them had no caller. So the tests here are about the
 * three things that make wiring it up safe:
 *
 * 1. **Only where something is waiting.** A project that was never pruned has nothing to collect, and
 *    `gc` on it is pure cost proportional to the whole repository.
 * 2. **The sweep runs first, and only ever touches saved versions.** `gc` destroys the evidence the
 *    sweep needs; and a prune says nothing about the working tree, so the current file's rows must
 *    survive it.
 * 3. **A project that could not be collected keeps its marker**, so the next start asks again rather
 *    than silently never collecting.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MaintenanceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var account: File
    private lateinit var ws: Workspace

    @Before
    fun setUp() {
        account = File(temp.root, "account")
        ws = Workspace(account)
        ws.ensure()
        ws.projectDir("shopping").mkdirs()
    }

    private fun write(path: String, text: String): File {
        val file = File(ws.projectDir("shopping"), path)
        file.parentFile?.mkdirs()
        file.writeText(text)
        return file
    }

    /** A project with a repository and [saves] commits, each rewriting one file. */
    private fun repository(saves: Int): ProjectGit {
        val git = ProjectGit(ws, { null }, { GitAuthor("Test", "test@example.com") })
        write("milk.txt", "the milk went sour\n")
        git.init("shopping").getOrThrow()
        for (i in 2..saves) {
            write("milk.txt", "the milk went sour, save $i\n")
            git.commit("shopping", "save $i")
        }
        return git
    }

    // ---- 1. only where something is waiting --------------------------------

    @Test
    fun `a project that never asked is not touched`() {
        repository(3)

        val done = Maintenance.run(ws)

        assertTrue("nothing was attempted: $done", done.isEmpty())
    }

    @Test
    fun `a prune leaves a marker, and a pull does too`() {
        // **The two things that strand commits.** A prune rewrites the history; a reset replaces it.
        // Neither can collect where it happens, so both say so and this pass does the work.
        val dir = ws.projectDir("shopping")
        assertFalse("nothing waiting yet", Maintenance.waiting(dir))

        Maintenance.mark(dir, "the history was shortened")
        assertTrue("the prune's marker", Maintenance.waiting(dir))
        assertEquals("and its reason", "the history was shortened", Maintenance.why(dir))

        // **Idempotent, and it keeps the first reason** — ten prunes in one session are one
        // collection, and the first reason is as true as the tenth.
        Maintenance.mark(dir, "the remote replaced the history")
        assertEquals("the first reason survives", "the history was shortened", Maintenance.why(dir))
    }

    @Test
    fun `a running agent means not now, and the marker stays`() {
        val dir = ws.projectDir("shopping")
        repository(2)
        Maintenance.mark(dir, "the history was shortened")

        val done = Maintenance.run(ws, busy = { it == "shopping" })

        assertEquals("one attempt", 1, done.size)
        assertTrue("it said why: ${done.first().said()}", done.first().said().contains("agent is switched on"))
        assertTrue("and asked again next time", Maintenance.waiting(dir))
    }

    // ---- 2. the sweep, and its order ---------------------------------------

    @Test
    fun `a row whose content no commit reaches is swept, and the current file is kept`() {
        repository(3)
        val dir = ws.projectDir("shopping")
        val index = SearchIndex.open(dir)
        try {
            index.declare("test/embed", 4).getOrThrow()
            // The current file's text, which a prune says nothing about.
            index.put("milk.txt", "", Chunker.split("the milk went sour\n")) { texts ->
                Result.success(texts.map { FloatArray(4) { 1f } })
            }.getOrThrow()
            // A saved version that no commit in this repository reaches — what a rewritten history
            // leaves behind.
            index.put("milk.txt", "1".repeat(40), Chunker.split("a version nobody saved\n")) { texts ->
                Result.success(texts.map { FloatArray(4) { 2f } })
            }.getOrThrow()
        } finally {
            index.close()
        }
        Maintenance.mark(dir, "the history was shortened")

        val done = Maintenance.run(ws).first()

        assertEquals("one row swept: ${done.said()}", 1, done.rows)
        val after = SearchIndex.open(dir)
        try {
            assertTrue(
                "the ghost version is gone",
                after.versions().none { it.second == "1".repeat(40) },
            )
            val current = after.nearest(FloatArray(4) { 1f }, 10, history = false).getOrThrow()
            assertTrue("and the current file's row survives a prune", current.isNotEmpty())
        } finally {
            after.close()
        }
    }

    @Test
    fun `the marker goes when the work finished`() {
        val dir = ws.projectDir("shopping")
        repository(2)
        Maintenance.mark(dir, "the history was shortened")

        Maintenance.run(ws)

        assertFalse("nothing left to ask for", Maintenance.waiting(dir))
    }

    @Test
    fun `a project with no repository still clears its marker`() {
        // **The honest outcome.** A folder with no history has nothing to collect and no index rows
        // to sweep, so the marker would otherwise be retried at every start for ever.
        val dir = ws.projectDir("shopping")
        write("milk.txt", "the milk went sour\n")
        Maintenance.mark(dir, "the history was shortened")

        val done = Maintenance.run(ws).first()

        assertTrue("it said why: ${done.said()}", done.said().contains("no repository"))
        // **And it stops asking.** Nothing can be collected in a folder with no history, so a marker
        // that survived would be retried at every start for ever — the kind of "nothing to do" that
        // looks like work being done.
        assertFalse("and it did not keep asking", Maintenance.waiting(dir))
    }

    @Test
    fun `housekeeping writes one row, in the project's own log`() {
        val dir = ws.projectDir("shopping")
        repository(2)
        Maintenance.mark(dir, "the history was shortened")

        Maintenance.run(ws)

        val rows = AgentLog.open(dir).rows().map { it.name to it.detail }
        val row = rows.firstOrNull { it.first == "maintenance" }
        assertTrue("the row is there: $rows", row != null)
        assertTrue("and it says what happened: ${row?.second}", row?.second?.contains("shortened") == true)
    }

    // ---- 3. the bounds -----------------------------------------------------

    @Test
    fun `a busy project is skipped rather than waited for`() {
        val dir = ws.projectDir("shopping")
        repository(2)
        Maintenance.mark(dir, "the history was shortened")

        // **`tryHold`, not `hold`**: a background pass that waits in front of a commit is a commit
        // somebody is waiting for. The lock is held here for the length of the pass, and the pass has
        // to notice and come back later.
        //
        // **On another thread, because the lock is reentrant.** Holding it on this one and calling
        // into the pass would let the pass take it again — `ReentrantLock` says yes to its own thread
        // — and the test would pass while proving nothing about a real second caller.
        var done: Maintenance.Done? = null
        val holding = Thread {
            RepoLocks.tryHold(dir, "a test", 5_000L) {
                done = Maintenance.run(ws).firstOrNull()
            }
        }
        holding.start()
        holding.join(10_000)

        val said = done?.said() ?: "the pass never ran"
        assertTrue("it said it was busy: $said", said.contains("busy"))
        assertTrue("and the marker survived for the next start", Maintenance.waiting(dir))
    }
}
