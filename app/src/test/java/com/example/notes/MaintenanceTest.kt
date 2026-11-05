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

    @Test
    fun `a deleted account stops the pass instead of writing into the folder it removed`() {
        // **The flake this closes.** A pass can outlive the account it was started for — a `gc` is
        // seconds to minutes — and a finished pass writes a log row. Writing that row into a folder
        // somebody deleted is a deleted account coming back as an empty folder with one file in it,
        // which is exactly what `a queued save alone does not bring a deleted account back` catches.
        val dir = ws.projectDir("shopping")
        repository(2)
        Maintenance.mark(dir, "the history was shortened")

        val done = Maintenance.run(ws, alive = { false })

        assertTrue("nothing was attempted: $done", done.isEmpty())
        assertTrue("and the marker is still waiting", Maintenance.waiting(dir))
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
        // **The hold is on one thread and the pass on another**, because `tryHold` is reentrant: a
        // pass called from inside its own holder's block would take the lock again and the test would
        // pass while proving nothing about a real second caller.
        val held = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val holder = Thread {
            RepoLocks.tryHold(dir, "a test", 10_000L) {
                held.countDown()
                release.await(10, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        holder.start()
        held.await(10, java.util.concurrent.TimeUnit.SECONDS)

        var done: Maintenance.Done? = null
        try {
            done = Maintenance.run(ws).firstOrNull()
        } finally {
            release.countDown()
            holder.join(10_000)
        }

        val said = done?.said() ?: "the pass never ran"
        assertTrue("it said it was busy: $said", said.contains("busy"))
        assertTrue("and the marker survived for the next start", Maintenance.waiting(dir))
    }

    // ---- 4 and 5: a binary keeps one version, and the bytes go ----------------

    @Test
    fun `saving a picture twice leaves one blob, and every commit points at it`() {
        // **The whole of binary retention, in one assertion.** A picture has no history worth keeping:
        // its versions are megabytes each and they do not compress. So after two saves the repository
        // must hold ONE set of bytes, and every commit that mentions the path must name it.
        //
        // **No LFS involved, and that is the finding.** LFS was going to hold the bytes outside the
        // object database, and its push cannot authenticate — `LfsPrePushHook` sends no
        // `Authorization` header, so a real repository answers 401. Measured instead: a plain blob plus
        // the tree rewrite gives the same guarantee, and `gc` collects the old one.
        val git = ProjectGit(ws, { null }, { GitAuthor("Test", "test@example.com") })
        write("milk.txt", "the milk went sour\n")
        git.init("shopping").getOrThrow()
        val picture = File(ws.projectDir("shopping"), "page.png")
        picture.writeBytes(PNG_A)
        git.commit("shopping", "a picture")
        val first = git.open("shopping")!!.use { blobAt(it, "page.png", "HEAD") }

        picture.writeBytes(PNG_B)
        git.commit("shopping", "drawn on")

        git.open("shopping")!!.use {
            // **Every commit names the newest bytes**, which is what makes the older blob
            // unreachable rather than merely unreferenced by the newest commit.
            val named = commits(it).mapNotNull { c -> blobAt(it, "page.png", c.name) }.distinct()
            assertEquals("every commit holds the same blob: $named", 1, named.size)
            assertFalse("and it is not the first one", named.single() == first.name)
            assertTrue(
                "which is the newest bytes: ${repoBytes(it, named.single())}",
                repoBytes(it, named.single()).contentEquals(PNG_B),
            )
        }
    }

    @Test
    fun `a collection releases the old picture's bytes, and keeps everything else`() {
        // **The half that makes the rewrite worth doing.** Rewriting the trees only makes the bytes
        // unreachable; something has to delete them, and a plain `gc` does not — it treats everything
        // named by `logs/refs/heads/<branch>` as reachable, and a rewrite leaves the original commits
        // there. Measured on a 400 KB blob: it survived a plain `gc`, a `gc` with `setExpire(now)`,
        // and a `gc` with `expire = 0`; it went only once the reflog was deleted.
        val git = ProjectGit(ws, { null }, { GitAuthor("Test", "test@example.com") })
        write("milk.txt", "the milk went sour\n")
        git.init("shopping").getOrThrow()
        val picture = File(ws.projectDir("shopping"), "page.png")
        picture.writeBytes(PNG_A)
        git.commit("shopping", "a picture")
        val first = git.open("shopping")!!.use { blobAt(it, "page.png", "HEAD") }

        picture.writeBytes(PNG_B)
        git.commit("shopping", "drawn on")

        git.open("shopping")!!.use {
            assertTrue("the old blob exists before the collection", it.objectDatabase.has(first))
            val freed = HistoryPrune.collect(it)
            assertTrue("the collection ran and reported bytes: $freed", freed >= 0L)
            assertFalse("the old picture's bytes are gone", it.objectDatabase.has(first))
            // **And the history is intact.** A collection that removed a commit would be a shortened
            // history arrived at by accident, which is exactly what the rewrite's parent rule exists
            // to prevent.
            assertEquals("every commit is still there", 2, commits(it).size)
            assertTrue(
                "and the newest bytes are still the picture",
                repoBytes(it, blobAt(it, "page.png", "HEAD").name).contentEquals(PNG_B),
            )
        }
    }

    @Test
    fun `a picture saved under a new name keeps both`() {
        // **The other half of the rule.** One version per file, not one version per project: saving
        // under a new name is the operator's versioning option, so the original must survive the
        // rewrite and the collection untouched.
        val git = ProjectGit(ws, { null }, { GitAuthor("Test", "test@example.com") })
        write("milk.txt", "the milk went sour\n")
        git.init("shopping").getOrThrow()
        val dir = ws.projectDir("shopping")
        File(dir, "page.png").writeBytes(PNG_A)
        git.commit("shopping", "a picture")
        val first = git.open("shopping")!!.use { blobAt(it, "page.png", "HEAD") }
        File(dir, "page-2.png").writeBytes(PNG_B)
        git.commit("shopping", "a copy")

        git.open("shopping")!!.use {
            HistoryPrune.collect(it)
            assertTrue("the first picture's bytes survive", it.objectDatabase.has(first))
            assertTrue(
                "and the copy is a picture too",
                repoBytes(it, blobAt(it, "page-2.png", "HEAD").name).contentEquals(PNG_B),
            )
        }
    }

    @Test
    fun `text keeps every version, so a collection releases none of it`() {
        // **The rule is about the kind of file, and this is the assertion that says so.** A rewrite
        // that ran on a text file would delete the history, which is the product — so the guard is
        // that text is never rewritten, and this proves the collection then has nothing to take.
        val git = ProjectGit(ws, { null }, { GitAuthor("Test", "test@example.com") })
        write("milk.txt", "one\n")
        git.init("shopping").getOrThrow()
        val first = git.open("shopping")!!.use { blobAt(it, "milk.txt", "HEAD") }
        repeat(3) { i ->
            write("milk.txt", "draft $i\n")
            git.commit("shopping", "save $i")
        }

        git.open("shopping")!!.use {
            HistoryPrune.collect(it)
            assertTrue("every draft is still readable", it.objectDatabase.has(first))
            assertEquals("and there are four versions", 4, commits(it).size)
        }
    }

    /** The blob a path holds at [rev]. */
    private fun blobAt(repo: org.eclipse.jgit.lib.Repository, path: String, rev: String): org.eclipse.jgit.lib.ObjectId? =
        try {
            val commit = org.eclipse.jgit.revwalk.RevWalk(repo).use { it.parseCommit(repo.resolve(rev)) }
            org.eclipse.jgit.treewalk.TreeWalk.forPath(repo, path, commit.tree)?.getObjectId(0)
        } catch (e: Throwable) {
            null
        }

    /** The bytes a blob holds, so "it is the picture" can be asserted rather than inferred. */
    private fun repoBytes(repo: org.eclipse.jgit.lib.Repository, id: String): ByteArray =
        repo.open(org.eclipse.jgit.lib.ObjectId.fromString(id)).bytes

    private fun commits(repo: org.eclipse.jgit.lib.Repository) =
        org.eclipse.jgit.revwalk.RevWalk(repo).use { walk ->
            walk.markStart(walk.parseCommit(repo.resolve("HEAD")))
            walk.toList()
        }

    private companion object {
        /**
         * Two PNGs that differ in their bytes.
         *
         * Real magic bytes, so a reader that guesses by content agrees with the name — and small,
         * because the store's behaviour is what is being tested, not the picture.
         */
        val PNG_A: ByteArray = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + "one".toByteArray()
        val PNG_B: ByteArray = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + "two".toByteArray()
    }
}
