package com.example.notes

import org.junit.After
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
 * **Indexing follows the project, not the search.**
 *
 * The operator's instruction: *"we should run comprehensive indexing of a project we add from github
 * the moment it is added and in background with result status returning to session log as app
 * notification."* And the correction that came with it — every change the app makes is one commit, so
 * there *is* a per-change hook and it is `ProjectGit.commitOutcome`.
 *
 * What is tested here is the part that would fail silently:
 *
 * 1. **A commit asks for a pass** — and a commit that wrote nothing does not, because a pass would
 *    spend a walk to answer "nothing had changed".
 * 2. **A clone asks for a pass**, because a clone writes a whole tree and commits none of it, so the
 *    commit hook never fires for the one case where the whole index is stale.
 * 3. **Many asks are one pass.** The map is the coalescer; without it, ten saves in an afternoon are
 *    ten walks of the project.
 * 4. **A finished pass reaches the session log**, so the record answers "what has this app done".
 *
 * **The queue runs its own thread, so every test here waits for a real pass rather than sleeping at a
 * guess**: `settle` spins on [IndexQueue.passes] with a bound. A test that slept would pass on a fast
 * machine and fail in CI for a reason that has nothing to do with the code.
 */
// **Robolectric, because the index is SQLite** — the queue's whole job ends in one.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IndexQueueTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var account: File
    private lateinit var ws: Workspace
    private lateinit var git: ProjectGit

    /** What the commit hook asked for, which is the fact the hook is responsible for. */
    private val asked = mutableListOf<Pair<String, String>>()

    @Before
    fun setUp() {
        account = File(temp.root, "account")
        ws = Workspace(account)
        ws.ensure()
        ws.repoOf("shopping").mkdirs()
        ws.write(File(ws.repoOf("shopping"), "milk.txt"), "the milk went sour\n")
        git = ProjectGit(
            ws,
            { null },
            { GitAuthor("Muffy", "muffy@example.com") },
            index = { project, why -> asked += project to why },
        )
        git.init("shopping")
    }

    @After
    fun tearDown() {
        // **The queue is an object, so it outlives the test.** A worker left pointing at a deleted
        // temp folder is a flake in whichever test runs next.
        IndexQueue.clear()
    }

    // ---- 1 and 2: what asks for a pass -------------------------------------

    @Test
    fun `a commit that wrote a version asks for a pass`() {
        ws.write(File(ws.repoOf("shopping"), "milk.txt"), "three litres\n")

        git.commit("shopping", "Saved")

        assertEquals("one ask", 1, asked.size)
        assertEquals("for this project", "shopping", asked.single().first)
        assertTrue("and it says what prompted it: ${asked.single().second}", "saved" in asked.single().second)
    }

    @Test
    fun `a commit with nothing to record asks for nothing`() {
        // **A pass would answer "nothing had changed" at the cost of a walk of the project.** The
        // commit knows there was nothing to do, so it is the place that should not ask.
        git.commit("shopping", "Saved")

        assertTrue("nothing was asked for: $asked", asked.isEmpty())
    }

    @Test
    fun `a project arriving from a remote asks for a comprehensive pass`() {
        // **The one case the commit hook cannot cover.** A clone writes the whole tree and commits
        // none of it file by file, so nothing below ever fires for it.
        val origin = File(temp.root, "origin").apply { mkdirs() }
        val source = ProjectGit(
            Workspace(origin),
            { null },
            { GitAuthor("Muffy", "muffy@example.com") },
        ).also {
            Workspace(origin).repoOf("far").mkdirs()
            Workspace(origin).write(File(File(origin, "far"), "notes.txt"), "a fact\n")
            it.init("far")
        }
        source.commit("far", "first")

        // **A folder with no repository, which is the whole point of the case.** `setUp` initialises
        // `shopping`, and cloning into a project that already has a `.git` is refused — so this asks
        // for a project of its own rather than deleting the fixture's repository underneath it.
        val clone = git.cloneInto("arriving", File(origin, "far").toURI().toString())

        assertTrue("the clone worked: ${clone.message}", clone.success)
        assertEquals("one ask", 1, asked.size)
        assertEquals("for the project that arrived", "arriving", asked.single().first)
        assertTrue(
            "and it says the project arrived: ${asked.single().second}",
            "added" in asked.single().second,
        )
    }

    @Test
    fun `a pull asks for a pass, because it changes files and records nothing`() {
        // **The second case with no commit.** `replaceWithRemote` fetches and resets the worktree to
        // the remote's copy — the files change, and no local version is written, so the commit hook
        // is blind to it. Left to the search guard it would be indexed by whichever search came next,
        // which is to say the one search that most needs to be fast pays for the whole diff.
        val origin = File(temp.root, "origin").apply { mkdirs() }
        val source = ProjectGit(
            Workspace(origin),
            { null },
            { GitAuthor("Muffy", "muffy@example.com") },
        ).also {
            Workspace(origin).repoOf("far").mkdirs()
            Workspace(origin).write(File(File(origin, "far"), "notes.txt"), "a fact\n")
            it.init("far")
        }
        source.commit("far", "first")
        // **Its own project, for the same reason as the clone test**: `shopping` already has a
        // repository, and a clone into one is refused, so the pull would have nothing to pull.
        git.cloneInto("arriving", File(origin, "far").toURI().toString())
        asked.clear()

        val replaced = git.replaceWithRemote("arriving")

        assertTrue("the pull worked: ${replaced.message}", replaced.success)
        assertEquals("one ask", 1, asked.size)
        assertTrue(
            "and it says what did it: ${asked.single().second}",
            "remote" in asked.single().second,
        )
        // **And the housekeeping is asked for too.** A reset strands this phone's old commits, and
        // no prune sees it — nothing shortened the history — so the reset itself is the only place
        // that can notice. Left unwritten, those objects are never collected.
        assertTrue(
            "the reset says it stranded commits",
            Maintenance.waiting(ws.projectDir("arriving")),
        )
        // **And the marker is cleared here**, because the queue's own pass would otherwise run a
        // collection inside a test's temp folder after the test ended — the same reason `clear()` is
        // called in `tearDown`.
        File(File(ws.projectDir("arriving"), AgentLog.DIR), Maintenance.MARKER).delete()
    }

    // ---- 3: many asks, one pass -------------------------------------------

    @Test
    fun `many asks in a row are one pass, because a pass is idempotent`() {
        // **The map is the coalescer.** An index pass answers "make this index match this project",
        // so three of them at once are the same statement — and a project saved ten times in an
        // afternoon would otherwise be walked ten times for one changed file.
        val fake = Fake()
        IndexQueue.configure(
            ws = ws,
            embedder = fake,
            models = { Config(embedding = PICK) },
        )

        val before = IndexQueue.passes
        repeat(5) { IndexQueue.want("shopping", "a change was saved") }
        settle(before)

        // **The guarantee is the work, not the count.** A pass is idempotent, so "the project was
        // indexed once" is a fact about the answer: the text went to the model one time however many
        // passes ran. The pass count is timing — a request that arrives after the worker took the
        // first job is coalesced into the *next* one, not the running one — so it is bounded rather
        // than asserted exactly, and asserting `1` here failed on a runner for a reason that is not a
        // defect.
        assertEquals("the project was indexed once", 1, fake.embedded.count { it == "the milk went sour\n" })
        assertTrue(
            "and five asks are fewer than five passes: ${IndexQueue.passes - before}",
            IndexQueue.passes - before < 5,
        )
    }

    @Test
    fun `a finished pass is written to the project's session log`() {
        // **The record answers "what has this app done"**, and the log is where it lives. It is a
        // bookkeeping row — in the file and not in the model's context — because indexing is not part
        // of the conversation.
        IndexQueue.configure(
            ws = ws,
            embedder = Fake(),
            models = { Config(embedding = PICK) },
        )

        IndexQueue.want("shopping", "the project was added")
        settle(IndexQueue.passes)

        val rows = AgentLog.open(ws.projectDir("shopping")).rows()
        val row = rows.firstOrNull { it.name == "index" }
        assertTrue("there is a row: ${rows.map { it.name }}", row != null)
        assertTrue("saying what prompted it: ${row!!.body}", "the project was added" in row.body)
        assertTrue("and what happened: ${row.body}", "indexed" in row.body)
    }

    @Test
    fun `a project with no embedding model says so instead of passing quietly`() {
        // **The same rule as the search's refusal**, and it matters more here: this pass runs with
        // nobody watching, so a silent no-op would leave a project that looks indexed and is not.
        IndexQueue.configure(ws = ws, embedder = Fake(), models = { null })

        IndexQueue.want("shopping", "a change was saved")
        settle(IndexQueue.passes)

        val row = AgentLog.open(ws.projectDir("shopping")).rows().first { it.name == "index" }
        assertTrue("it names the setting: ${row.body}", "Embedding model" in row.body)
    }

    @Test
    fun `a project that no longer exists is named rather than thrown`() {
        IndexQueue.configure(
            ws = ws,
            embedder = Fake(),
            models = { Config(embedding = PICK) },
        )
        ws.repoOf("shopping").deleteRecursively()

        // **`passes` is the process's count, not this test's**, and other tests in this class have
        // already run passes — so what is asserted is that this ask caused one more.
        val before = IndexQueue.passes
        IndexQueue.want("shopping", "a change was saved")
        settle(before)

        assertEquals("the pass ran and reported", before + 1, IndexQueue.passes)
    }

    @Test
    fun `an unconfigured queue does nothing at all`() {
        // **The default is a queue that cannot spend a key**, which is what a test and a build with
        // no credentials get. Asking must not start a thread, and must not throw.
        IndexQueue.clear()

        // **`passes` is the process's count**, and other tests in this class have already run
        // passes — so "nothing happened" is this ask not adding one, not the counter reading zero.
        val before = IndexQueue.passes
        IndexQueue.want("shopping", "a change was saved")
        // No pass will ever come, so this waits for the queue to be idle rather than for one.
        val until = System.currentTimeMillis() + 1_000
        while (System.currentTimeMillis() < until && !IndexQueue.idle) Thread.sleep(10)

        assertEquals("no pass", before, IndexQueue.passes)
    }

    // ---- harness ----------------------------------------------------------

    /**
     * Waits until the queue has nothing left to do, with a bound so a wedge fails the test instead of
     * hanging it.
     *
     * **One condition, `idle`, rather than counting passes.** A worker is alive for a moment after its
     * last pass, and an assertion that ran then would read the log before the row was written — a
     * test that passes on a fast machine and fails in CI for a reason unrelated to the code.
     */
    private fun settle(was: Int) {
        val until = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < until) {
            // **`idle` alone is not enough, and CI proved it.** The worker sets itself free between
            // taking a job and starting it, so a poll that lands in that gap reads "idle" and asserts
            // about a pass that has not run — a test that passes on a fast machine and fails on a
            // runner. Idle *and* a pass having happened is the only state worth asserting about.
            if (IndexQueue.idle && IndexQueue.passes > was) return
            Thread.sleep(10)
        }
    }

    /** An [Embedder] that records what it was asked to embed, and answers a fixed vector. */
    private class Fake : Embedder {
        val embedded = mutableListOf<String>()

        override fun embed(texts: List<String>, model: String, dims: Int): Result<List<FloatArray>> {
            embedded += texts
            return Result.success(texts.map { FloatArray(dims) { 1f } })
        }

        override fun rerank(
            query: String,
            documents: List<String>,
            model: String,
        ): Result<List<Embedder.Ranked>> =
            Result.success(documents.indices.map { Embedder.Ranked(it, 1.0) })
    }

    /**
     * A config whose only interesting row is the embedding model.
     *
     * **`ModelConfig`, not `ModelConfigFile`** — the second is the object that reads and writes the
     * file, and calling it as a constructor is the mistake this test made first: the compiler said
     * `Unresolved reference`, which reads like a missing import and is a missing `val`.
     */
    private fun Config(embedding: PickedModel?) = ModelConfig(
        active = ActiveModel("chat/model", "provider", "high"),
        models = emptyList(),
        embedding = embedding,
    )

    private companion object {
        val PICK = PickedModel("test/embed", "test", dimensions = 4)
    }
}
