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
 * Yes — and the answer has since changed twice. `gc` collects the object database, but a pointer's
 * bytes are not in it: they live in `.git/lfs/objects`, which `gc` has never looked inside. So the
 * tests here are about the things that make housekeeping safe:
 *
 * 1. **Only where something is waiting.** A project that was never marked has nothing to collect, and
 *    `gc` on it is pure cost proportional to the whole repository.
 * 2. **The sweep only ever touches bytes nothing points at.** Each stored file is named by its own
 *    oid, so reachability is answered by reading the pointers in the trees, never by guessing paths.
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
        // **The filter has to exist before a picture is saved**, or the tree holds the raw bytes and
        // there is nothing in the store to sweep — which made these tests assert against an empty
        // store and pass for the wrong reason. `init` does this for the app; a test that calls the
        // git layer directly has to do it itself.
        assertTrue("the LFS filter registered", GitService.registerLfs())
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

    // ---- the picture store, which `gc` has never looked inside ----------------

    @Test
    fun `the sweep removes the bytes nothing points at, and keeps the ones that are used`() {
        // **`gc` does not know this directory exists.** It collects commits, trees and blobs; a
        // pointer's bytes live in `.git/lfs/objects`, which is a content-addressed store the filter
        // writes and the smudge reads. So two objects on disk and one pointer in the trees must leave
        // exactly one file behind, and the file whose oid is still named must not be touched.
        val git = ProjectGit(ws, { null }, { GitAuthor("Test", "test@example.com") })
        write("milk.txt", "the milk went sour\n")
        git.init("shopping").getOrThrow()
        File(ws.projectDir("shopping"), "page.png").writeBytes(PNG_A)
        git.commit("shopping", "a picture")

        git.open("shopping")!!.use {
            val used = storedObjects(it)
            assertEquals("one object to begin with", 1, used.size)
            // **A second object written straight into the store**, which is the shape a version no
            // commit points at takes. Its oid is nobody's pointer.
            val orphan = File(it.directory, "lfs/objects/ab/cd/abcdef0123456789")
            orphan.parentFile?.mkdirs()
            orphan.writeBytes(ByteArray(4096))
            assertEquals("two now", 2, storedObjects(it).size)

            val freed = LfsStore.sweep(it)

            assertEquals("only the orphan went", 1, storedObjects(it).size)
            assertTrue("and the used one survived", used.single().isFile)
            assertEquals("the freed size is the orphan's", 4096L, freed)
        }
    }

    @Test
    fun `a picture saved under a new name keeps both`() {
        // **One version per file, not one version per project.** Saving under a new name is the
        // operator's versioning option, so the original must survive the sweep untouched.
        val git = ProjectGit(ws, { null }, { GitAuthor("Test", "test@example.com") })
        write("milk.txt", "the milk went sour\n")
        git.init("shopping").getOrThrow()
        val dir = ws.projectDir("shopping")
        File(dir, "page.png").writeBytes(PNG_A)
        git.commit("shopping", "a picture")
        File(dir, "page-2.png").writeBytes(PNG_B)
        git.commit("shopping", "a copy")

        git.open("shopping")!!.use {
            assertEquals("both pictures are stored", 2, storedObjects(it).size)
            LfsStore.sweep(it)
            assertEquals("and the sweep keeps both", 2, storedObjects(it).size)
        }
    }

    @Test
    fun `a sweep with nothing to reclaim answers nothing, and does not invent a number`() {
        // **"The collection ran" is not "it released something".** A store where every object is
        // still named must answer zero, so a person is never told bytes were freed when none were.
        val git = ProjectGit(ws, { null }, { GitAuthor("Test", "test@example.com") })
        write("milk.txt", "the milk went sour\n")
        git.init("shopping").getOrThrow()
        File(ws.projectDir("shopping"), "page.png").writeBytes(PNG_A)
        git.commit("shopping", "a picture")

        git.open("shopping")!!.use {
            assertEquals("nothing was reclaimable", 0L, LfsStore.sweep(it))
            assertEquals("and the picture is still stored", 1, storedObjects(it).size)
        }
    }

    @Test
    fun `a saved picture is a pointer in the tree and bytes in the store`() {
        // **The local half of LFS, and the fact everything else rests on.** The blob git stores for a
        // picture is not the picture: it is three lines of text naming the bytes by SHA-256. The bytes
        // are beside the repository, in the store `gc` never looks at.
        //
        // If the filter were not registered this test would find the raw PNG in the tree — which is
        // exactly what "LFS silently does nothing" looks like, and what the registration call exists
        // to prevent.
        val git = ProjectGit(ws, { null }, { GitAuthor("Test", "test@example.com") })
        write("milk.txt", "the milk went sour\n")
        git.init("shopping").getOrThrow()
        File(ws.projectDir("shopping"), "page.png").writeBytes(PNG_A)
        git.commit("shopping", "a picture")

        git.open("shopping")!!.use { repo ->
            val commit = org.eclipse.jgit.revwalk.RevWalk(repo).use {
                it.parseCommit(repo.resolve("HEAD"))
            }
            val blob = org.eclipse.jgit.treewalk.TreeWalk
                .forPath(repo, "page.png", commit.tree)?.getObjectId(0)
            assertTrue("the path is in the tree", blob != null)
            val stored = repo.open(blob).bytes
            val text = String(stored)
            assertTrue(
                "the tree holds a pointer, not the picture: ${text.take(40)}",
                text.startsWith("version https://git-lfs"),
            )
            // **And it names the bytes by their SHA-256**, which is what makes the store reachable
            // from the tree without reading the picture itself.
            val oid = java.security.MessageDigest.getInstance("SHA-256")
                .digest(PNG_A)
                .joinToString("") { "%02x".format(it) }
            assertTrue("the pointer names the picture: $text", text.contains(oid))
            // **The bytes are in the store**, under the layout `objects/ab/cd/<oid>`.
            val file = File(repo.directory, "lfs/objects/${oid.take(2)}/${oid.substring(2, 4)}/$oid")
            assertTrue("the bytes are stored beside the repository", file.isFile)
            assertTrue("and they are the picture", file.readBytes().contentEquals(PNG_A))
        }
    }

    @Test
    fun `the attribute is written once, and only for the kinds the app shows`() {
        // **The line in the person's repository**, which is what makes a clone elsewhere treat the
        // file as a pointer. Written once: a second call must not append a duplicate, which was the
        // bug when the check compared the whole block against one line.
        val git = ProjectGit(ws, { null }, { GitAuthor("Test", "test@example.com") })
        git.init("shopping").getOrThrow()
        val dir = ws.projectDir("shopping")
        GitService.ensureLfsAttributes(dir)
        GitService.ensureLfsAttributes(dir)

        val lines = File(dir, ".gitattributes").readText().lines().filter { it.isNotBlank() }
        assertEquals("no duplicate lines", lines.size, lines.distinct().size)
        assertTrue("a png is a pointer", lines.any { it.startsWith("*.png ") })
        assertTrue("and it is the LFS filter", lines.any { it.contains("filter=lfs") })
        assertTrue("with -text, so line endings are not converted", lines.any { it.endsWith("-text") })
    }

    /** Every file under `.git/lfs/objects`, which is the store `gc` never looks at. */
    private fun storedObjects(repo: org.eclipse.jgit.lib.Repository): List<File> {
        val root = LfsStore.dirOf(repo)
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown().filter { it.isFile }.toList()
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
