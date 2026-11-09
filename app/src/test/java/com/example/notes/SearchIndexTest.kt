package com.example.notes

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
 * The vector index — and the two facts that make it affordable.
 *
 * **1. A chunk's text is its identity.** A version's chunk and the current file's chunk with the same
 * words share a hash, so the vector is stored **once** and referenced twice. A file saved ten times
 * with one paragraph edited stores ten changed chunks and one copy of everything else. The operator's
 * argument was *"it is free, and it is the half nobody else can search"* — this is what makes that
 * true rather than hopeful.
 *
 * **2. The dimension is a fact about the index.** `qwen/qwen3-embedding-8b` answers 1024, 2048 or 4096
 * on demand, so the same model at two sizes produces two incompatible spaces. A query against a
 * different size is **"re-index needed"**, never a cosine between unrelated numbers — the second would
 * be a value that looks like a score and means nothing.
 *
 * **No socket.** [Embedder] is an interface for that reason, and every vector here is made by the test.
 */
// **Robolectric, because SQLite is Android's.** `SQLiteDatabase.openOrCreateDatabase` reaches for the
// platform's native library, so a plain JVM test throws at the first `open` — which is how this file
// failed the first time it ran.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchIndexTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var project: File
    private lateinit var index: SearchIndex

    @Before
    fun setUp() {
        project = File(temp.root, "shopping")
        project.mkdirs()
        index = SearchIndex.open(project)
    }

    @After
    fun tearDown() {
        // The database holds an open handle; a test folder that cannot be deleted is a test that
        // leaves a lock behind for the next one.
        index.clear()
    }

    @Test
    fun `an index written by another shape is thrown away and built again`() {
        // **An index is derived data, so there is nothing in it worth carrying.** Every row is
        // computed from the tree — the chunks are its text, the vectors are embeddings of that text,
        // and the file rows are what a walk found — so a file whose schema this build did not write
        // is deleted rather than patched. A patch would be a reader for a format nothing writes any
        // more, and it would have to guess what an old column meant.
        //
        // **Its own folder**, so no open handle is left pointing at a deleted file: this test writes
        // a database this code did not make, which is the case being answered.
        val old = File(temp.root, "already-used")
        val file = File(File(old, AgentLog.DIR), SearchIndex.FILE)
        file.parentFile?.mkdirs()
        val db = android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL(
                "CREATE TABLE files(path TEXT PRIMARY KEY, mtime INTEGER NOT NULL, " +
                    "state INTEGER NOT NULL, error TEXT)",
            )
            db.execSQL(
                "CREATE TABLE chunks(path TEXT NOT NULL, version TEXT NOT NULL DEFAULT '', " +
                    "hash TEXT NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL, " +
                    "PRIMARY KEY(path, version, start))",
            )
            db.execSQL("CREATE TABLE vectors(hash TEXT PRIMARY KEY, vector BLOB NOT NULL)")
            db.execSQL(
                "CREATE TABLE meta(id INTEGER PRIMARY KEY, model TEXT NOT NULL, " +
                    "dims INTEGER NOT NULL)",
            )
            db.execSQL("INSERT INTO files(path, mtime, state) VALUES('milk.txt', 7, 0)")
        } finally {
            db.close()
        }

        // Opening it is the whole of the rule: the old file goes, and what answers is a new one.
        val reopened = SearchIndex.open(old)
        try {
            assertNull("the row this build did not write is gone", reopened.seen("milk.txt"))
            // **And a write that names every column works**, which is what a kept-along schema would
            // have broken: the old `files` table has no `size`, and `note()` names it.
            reopened.note("bread.txt", 9L, SearchIndex.INDEXED, size = 12L)
            assertEquals("the new row", 9L to 12L, reopened.seen("bread.txt"))
        } finally {
            reopened.clear()
        }
    }

    /**
     ** A vector of [size] where every component is [value] — **so a query can be made to land near a
     * known chunk** without pretending to be an embedding model.
     */
    private fun vector(size: Int, value: Float) = FloatArray(size) { value }

    /** A vector with one component set, so two of them are orthogonal and the ranking is arithmetic. */
    private fun axis(size: Int, at: Int) = FloatArray(size) { if (it == at) 1f else 0f }

    /**
     * The vector function the index asks for, **bound to the size the test declared.**
     *
     * `SearchIndex.put` takes `(List<String>) -> Result<List<FloatArray>>` — the hashes' texts in, one
     * vector each out — rather than an [Embedder], because the index never needs a model name: it has
     * already decided what it is built with, and the caller is the one who knows which model answers.
     * So the test supplies the function directly, and it is deterministic so the ranking is arithmetic.
     */
    private fun vectors(size: Int = 4, at: (String) -> Int = { it.length % 4 }):
        (List<String>) -> Result<List<FloatArray>> = { texts ->
        Result.success(texts.map { axis(size, at(it)) })
    }

    private fun built(size: Int = 4) {
        index.declare("test/embed", size).getOrThrow()
    }

    // ---- the hash is the whole point ---------------------------------------

    @Test
    fun `the same text at two versions is one vector`() {
        // **This is what makes history affordable**, and it is the claim the design rests on. A file
        // saved twice with one line changed stores one new vector, not two copies of everything.
        built()
        // **A file long enough to be several chunks**, because that is the shape the claim is about:
        // one paragraph edited in a file of many. A three-line file is one chunk, so editing it
        // changes the whole chunk and there is nothing to share.
        // **Three or more chunks, so there are chunks the edit cannot reach.** With two, the overlap
        // means a change to one re-embeds both and there is nothing left to prove the hash works.
        val lines = (1..200).map { "line $it of the file, with enough words to fill a chunk" }
        val before = Chunker.split(lines.joinToString("\n"))
        val after = Chunker.split(lines.joinToString("\n").replace("line 40 of", "LINE 40 of"))

        assertTrue("the file is several chunks: ${before.size}", before.size >= 3)
        val first = index.put("a.txt", "", before, vectors()).getOrThrow()
        val second = index.put("a.txt", "abc1234", after, vectors()).getOrThrow()

        assertEquals("the first pass embedded everything", before.size, first)
        // **Fewer than everything, and not exactly one.** The overlap means an edited line appears in
        // two chunks — the one it is in and the one whose tail carries it forward — so a single-line
        // change re-embeds two. That is the overlap working, not a leak: the claim the hash makes is
        // "only what changed", and two chunks contained the change.
        assertTrue(
            "the version embedded only what changed: $second of ${after.size}",
            second in 1..(after.size - 1),
        )
        // **Fewer vectors than chunks**, which is the whole claim: the text that did not change is
        // one vector referenced by both versions.
        assertTrue(
            "there are more chunk rows than vectors: ${index.size()} chunks, ${index.vectors()} vectors",
            index.vectors() < index.size(),
        )
    }

    @Test
    fun `a version and the current file can be searched separately`() {
        // **`history` is a filter, not a second table.** Versions are rows with a commit, so "current
        // files only" is `commit = ''` — one clause on the same query.
        built()
        index.put("a.txt", "", Chunker.split("the timeout is 90"), vectors()).getOrThrow()
        index.put("a.txt", "deadbee", Chunker.split("the timeout is 30"), vectors()).getOrThrow()

        val now = index.nearest(axis(4, 0), paths = 10, history = false).getOrThrow()
        val all = index.nearest(axis(4, 0), paths = 10, history = true).getOrThrow()

        assertEquals("only the current file", 1, now.size)
        assertEquals("but both versions when history is asked for", 2, all.size)
        assertTrue("and the current one is shown as a bare path", now.none { it.commit.isNotEmpty() })
        assertTrue("while a version carries its sha", all.any { it.commit == "deadbee" })
    }

    @Test
    fun `a version hit is shown as path at sha7, which read and restore already accept`() {
        // **A search hit is directly usable as the next call**, with no translation: `read
        // {revision}` and `restore` both take a sha, so `path@sha7` is the form that can be pasted.
        built()
        index.put("a.txt", "abcdef1234567890", Chunker.split("old text"), vectors()).getOrThrow()

        val hit = index.nearest(axis(4, 0), paths = 5, history = true).getOrThrow().single()

        assertEquals("a.txt@abcdef1", hit.shown())
        assertEquals("and the current file is just its path", "a.txt", index.let { hit.copy(commit = "").shown() })
    }

    // ---- the dimension is a fact about the index ---------------------------

    @Test
    fun `emptying the index is what makes a different model possible`() {
        // **The refusal promised a rebuild that could not happen.** `declare` refuses any model other
        // than the recorded one, and `clear` left the record behind — so a person who chose a new
        // embedding model got "it has to be rebuilt" for ever. Emptying the record is what makes the
        // sentence true, and the next `declare` is the one that decides what the index is.
        built()
        index.put("a.txt", "", Chunker.split("milk"), vectors()).getOrThrow()
        assertEquals("built with the first model", "test/embed", index.builtWith()?.model)

        index.clear()

        assertNull("and now it claims nothing", index.builtWith())
        assertEquals("with no text", 0, index.size())
        assertEquals("and no file it has seen", 0, index.files())
        // **Re-declared at the other model and the other size, both at once**, which is the whole
        // point: nothing about the old space is left to disagree with.
        index.declare("other/embed", 8).getOrThrow()
        assertEquals("the new space", SearchIndex.Built("other/embed", 8), index.builtWith())
    }

    @Test
    fun `an index built at one size refuses to be redeclared at another`() {
        // **Mixing two spaces in one table is arithmetic on unrelated numbers.** The refusal names
        // both, so the caller can say "re-index needed" rather than the app quietly answering
        // nonsense — and a nonsense answer here looks exactly like a real one.
        built(4)

        val again = index.declare("test/embed", 8)

        assertTrue("it is refused", again.isFailure)
        assertTrue(
            "and names both sizes: ${again.exceptionOrNull()?.message}",
            again.exceptionOrNull()?.message.orEmpty().contains("4") &&
                again.exceptionOrNull()?.message.orEmpty().contains("8"),
        )
    }

    @Test
    fun `a query at a different size is refused rather than scored`() {
        // A cosine between a 4-vector and an 8-vector is either an exception or a number from the
        // wrong vectors — and the second is worse, because it looks like a score.
        built(4)
        index.put("a.txt", "", Chunker.split("text"), vectors()).getOrThrow()

        val answer = index.nearest(axis(8, 0), paths = 5, history = false)

        assertTrue("it is refused", answer.isFailure)
        assertTrue(
            "and says what to do: ${answer.exceptionOrNull()?.message}",
            answer.exceptionOrNull()?.message.orEmpty().contains("rebuilt"),
        )
    }

    @Test
    fun `a vector of the wrong size from the model is refused before it is stored`() {
        // **The model disagreeing with the index is a fact about the model**, so the sentence names
        // it rather than a table the person never saw.
        built(4)
        val wrong: (List<String>) -> Result<List<FloatArray>> = { texts ->
            Result.success(texts.map { FloatArray(8) { 1f } })
        }

        val answer = index.put("a.txt", "", Chunker.split("text"), wrong)

        assertTrue("it is refused", answer.isFailure)
        assertTrue(
            "and names both: ${answer.exceptionOrNull()?.message}",
            answer.exceptionOrNull()?.message.orEmpty().contains("8"),
        )
        assertEquals("and nothing was stored", 0, index.size())
    }

    // ---- what a search returns ---------------------------------------------

    @Test
    fun `the closest chunk comes first`() {
        // The ranking is the whole of what the index is for, so the assertion is about the order and
        // not only about the presence of a result.
        built(4)
        // "one" is 3 characters → axis 3; "two" is 3 → axis 3; "sixxxx" is 6 → axis 2.
        index.put("a.txt", "", Chunker.split("one"), vectors(at = { 3 })).getOrThrow()
        index.put("b.txt", "", Chunker.split("sixxxx"), vectors(at = { 2 })).getOrThrow()

        val hits = index.nearest(axis(4, 3), paths = 5, history = false).getOrThrow()

        assertEquals("the matching one", "a.txt", hits.first().path)
        assertTrue("and it scores above the other", hits.first().score > hits.last().score)
    }

    @Test
    fun `paths caps the answer`() {
        built(4)
        for (i in 1..5) {
            index.put("f$i.txt", "", Chunker.split("text $i"), vectors()).getOrThrow()
        }

        val hits = index.nearest(axis(4, 0), paths = 2, history = false).getOrThrow()

        assertEquals("two, as asked", 2, hits.size)
    }

    // ---- forgetting --------------------------------------------------------

    @Test
    fun `one text in two files is scored once and still answers twice`() {
        // **The shape the ranking is built on.** Chunks share vectors by design, so a paragraph that
        // appears in two files is ONE vector and two rows. Scoring per row would read and score the
        // same vector twice - measured at 682-723 ms against 588-631 ms on a real history - and it
        // would also report the second file at whatever score the loop happened to compute, rather
        // than at the score the text actually has.
        built(4)
        index.put("a.txt", "", Chunker.split("shared paragraph"), vectors(at = { 3 })).getOrThrow()
        index.put("b.txt", "", Chunker.split("shared paragraph"), vectors(at = { 3 })).getOrThrow()
        assertEquals("one vector, two rows", 1, index.vectors())

        val hits = index.nearest(axis(4, 3), paths = 5, history = false).getOrThrow()

        // **Both files are answers**, because both hold the text the question is about.
        assertEquals("both paths answer", setOf("a.txt", "b.txt"), hits.map { it.path }.toSet())
        // **And both carry the vector's score**, not one scored and one defaulted.
        assertEquals("the same score, from the one vector", 1, hits.map { it.score }.toSet().size)
    }

    @Test
    fun `one text at many paths cannot fill the answer on its own`() {
        // **`paths` bounds the answer, not the hashes.** A single common paragraph in a hundred
        // versions is one vector, so a ranking that cut hashes and then returned every row would hand
        // back a hundred copies of one line - and the reranker, which is promised thirty rows, would
        // be given the whole history of a single sentence.
        built(4)
        for (i in 1..20) {
            index.put("f$i.txt", "", Chunker.split("the same line"), vectors(at = { 3 })).getOrThrow()
        }

        val hits = index.nearest(axis(4, 3), paths = 5, history = false).getOrThrow()

        assertEquals("five, as asked, even though there is one vector", 5, hits.size)
    }

    @Test
    fun `forgetting a file drops its rows and collects the vectors nothing points at`() {
        // **A vector nothing references is dead weight**, and collecting it here rather than on a
        // timer keeps the count honest: "vectors" means "distinct text indexed".
        built(4)
        index.put("a.txt", "", Chunker.split("only a"), vectors()).getOrThrow()
        assertEquals("one chunk, one vector", 1, index.size())

        index.forget("a.txt")

        assertEquals("no chunks", 0, index.size())
        assertEquals("and the vector went with it", 0, index.vectors())
    }

    @Test
    fun `forgetting one version keeps the other`() {
        // A file's current text and its history are separate rows, so forgetting a version must not
        // take the current file with it.
        built()
        index.put("a.txt", "", Chunker.split("same"), vectors()).getOrThrow()
        index.put("a.txt", "abc1234", Chunker.split("same"), vectors()).getOrThrow()

        index.forget("a.txt", "abc1234")

        assertEquals("the current file survives", 1, index.size())
        assertEquals("and the shared vector is still referenced", 1, index.vectors())
    }

    @Test
    fun `a version-only match does not crowd out the current files`() {
        // **The regression the two-phase ranking could have introduced.** With `history = false` the
        // filter is applied to the ROWS, so the best vectors may all belong to versions this mode
        // excludes. Cutting the ranking to `paths` before resolving would then answer with one hit
        // where the old single query returned ten - a search that quietly stops finding things.
        built(4)
        // Ten versions that match the query exactly...
        for (i in 1..10) {
            index.put("old.txt", "v$i", Chunker.split("milk $i"), vectors(at = { 3 })).getOrThrow()
        }
        // ...and one current file that matches less well, which must still be found.
        index.put("now.txt", "", Chunker.split("bread"), vectors(at = { 2 })).getOrThrow()

        val hits = index.nearest(axis(4, 3), paths = 5, history = false).getOrThrow()

        assertEquals("the current file is still answered", listOf("now.txt"), hits.map { it.path })
    }

    // ---- the bytes a vector is stored as -----------------------------------

    @Test
    fun `a vector is stored as a quarter of the bytes, and still ranks the same`() {
        // **The measurement, pinned.** int8 was chosen on a benchmark against real embeddings: 99.8%
        // top-ten agreement and a cosine error of at most 0.001, for a quarter of the space. What
        // this asserts is that the quartering happened *and* that the answer survived it - a
        // quantisation that reordered the results would be a search returning the wrong file with a
        // confident score.
        built(1024)
        val big = FloatArray(1024) { (it % 97) / 97f - 0.5f }
        index.put("a.txt", "", Chunker.split("milk"), { Result.success(listOf(big)) }).getOrThrow()

        // **Read from the file, not from a getter**: the claim is about what the index costs on disk,
        // and a method that reports its own arithmetic would prove nothing.
        assertEquals("one byte per dimension, not four", 1024L, index.vectorBytes())

        val hits = index.nearest(big, paths = 5, history = false).getOrThrow()
        assertEquals("the file it came from", "a.txt", hits.first().path)
        assertTrue("and it is close to itself: ${hits.first().score}", hits.first().score > 0.99)
    }

    // ---- the empty index ---------------------------------------------------

    @Test
    fun `an index that was never declared has no hits rather than failing`() {
        // **Nothing chosen is not an error.** A config with no embedding model reads as "not chosen",
        // and a search must fall back to keyword rather than refuse.
        val hits = index.nearest(axis(4, 0), paths = 5, history = false)

        assertTrue("it is not a failure: ${hits.exceptionOrNull()?.message}", hits.isSuccess)
        assertTrue("and it is empty", hits.getOrThrow().isEmpty())
        assertNull("with nothing declared", index.builtWith())
    }

    @Test
    fun `an empty file has no chunks rather than one empty vector`() {
        // **A vector of nothing matches every query a little and none of them well**, and it would
        // sit in every result list forever.
        assertEquals(0, Chunker.split("").size)
        assertEquals(0, Chunker.split("   \n  \n").size)
    }

    // ---- the cosine --------------------------------------------------------

    @Test
    fun `cosine normalises by length, because not every model returns unit vectors`() {
        // A dot product of two long vectors would rank by magnitude rather than by meaning, so the
        // lengths are divided out. Two parallel vectors of different lengths score 1.
        assertEquals(1.0, SearchIndex.cosine(floatArrayOf(1f, 2f), floatArrayOf(2f, 4f)), 1e-6)
        assertEquals(0.0, SearchIndex.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-6)
        assertEquals(
            "and a zero vector matches nothing rather than everything",
            0.0,
            SearchIndex.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 1f)),
            1e-6,
        )
    }

    @Test
    fun `a query of a different length scores nothing rather than a wrong number`() {
        assertEquals(0.0, SearchIndex.cosine(floatArrayOf(1f, 2f), floatArrayOf(1f, 2f, 3f)), 1e-6)
    }

    // ---- what the walk has seen --------------------------------------------

    @Test
    fun `how many files are behind is a query, not a counter`() {
        // **The reason it is a table.** A file written while the app was closed was never told to
        // anything, so no counter can answer "how many are not in this index" — only the row the walk
        // leaves behind can. A file whose attempt **failed** is not "indexed at that time" either:
        // answering its time would make the next pass skip it for ever.
        built()
        // **The size is an argument, not a default.** It is a second opinion on "unchanged", and
        // `-1` was the sentinel a kept-along schema needed; with one schema there is nothing to be
        // unsure about, so every writer says what the directory entry already carried.
        index.note("a.txt", 1_000L, SearchIndex.INDEXED, size = 12L)
        index.note("b.txt", 2_000L, SearchIndex.FAILED, "it is not text", size = 40L)

        assertEquals("the indexed file's time", 1_000L, index.indexedAt("a.txt"))
        assertNull("a failed file has no time to compare", index.indexedAt("b.txt"))
        assertNull("and one never seen has none either", index.indexedAt("c.txt"))
        assertEquals("both files were met", 2, index.files())
        val (count, why) = index.failed()
        assertEquals("one failed", 1, count)
        assertEquals("with its reason", "it is not text", why)
    }

    @Test
    fun `forgetting a file forgets the walk's row with it`() {
        // **Or the next pass compares a time against an index that no longer holds the file** and
        // answers "up to date" about nothing.
        built()
        index.put("a.txt", "", Chunker.split("milk"), vectors()).getOrThrow()
        index.note("a.txt", 1_000L, SearchIndex.INDEXED, size = 12L)

        index.forget("a.txt")

        assertNull("no time is claimed", index.indexedAt("a.txt"))
        assertEquals("and no file is listed", 0, index.files())
        assertEquals("nothing is left to score", 0, index.size())
    }

    @Test
    fun `the embedder is asked for the text, not for its hash`() {
        // **The contract the first caller depends on.** The callback used to receive content
        // addresses, which cannot be embedded: a caller would have had to keep a hash-to-text map to
        // undo the answer it was handed. The hashes are the index's business, and the text is the
        // model's.
        built()
        val chunks = Chunker.split("the timeout is 90 seconds")
        val asked = mutableListOf<String>()

        index.put("a.txt", "", chunks) { texts ->
            asked += texts
            Result.success(texts.map { vector(4, 1f) })
        }.getOrThrow()

        assertEquals("one chunk", 1, asked.size)
        assertEquals("the text itself", chunks.single().text, asked.single())
    }

    @Test
    fun `the same paragraph twice in one file is embedded once`() {
        // **A hash is the text, so a repeated paragraph is one vector.** A file that says the same
        // thing in two places (a heading repeated under two sections) must not pay twice — the two
        // rows point at one hash. **Two different lines, one text**, which is the shape a file can
        // really produce: the same line number twice is not a file, and the primary key refuses it.
        built()
        val first = Chunker.Chunk("milk", start = 1, end = 1)
        val again = Chunker.Chunk("milk", start = 9, end = 9)
        var requests = 0
        val asked = mutableListOf<String>()

        index.put("a.txt", "", listOf(first, again)) { texts ->
            requests++
            asked += texts
            Result.success(texts.map { vector(4, 1f) })
        }.getOrThrow()

        assertEquals("one request", 1, requests)
        assertEquals("for one text", 1, asked.size)
        assertEquals("and two rows pointing at it", 2, index.size())
        assertEquals("but one vector", 1, index.vectors())
    }

    // ---- versions, and what a prune strands --------------------------------

    @Test
    fun `a version row survives a rewrite of the current file, because they are different keys`() {
        // **The whole reason the key is the blob.** A saved version and the file as it is now are two
        // rows in one table; writing the current text must not delete the version, which is what a
        // path-only key would do.
        built()
        index.put("a.txt", "", Chunker.split("the milk went sour")) { texts ->
            Result.success(texts.map { vector(4, 1f) })
        }.getOrThrow()
        index.put("a.txt", "abc1234", Chunker.split("the milk was poured away")) { texts ->
            Result.success(texts.map { vector(4, 2f) })
        }.getOrThrow()

        val versions = index.versions()

        assertEquals("one saved version: $versions", 1, versions.size)
        assertEquals("keyed by the blob", "abc1234" to "a.txt", versions.single().second to versions.single().first)
    }

    @Test
    fun `sweeping drops unreachable versions and keeps the current file`() {
        built()
        index.put("a.txt", "", Chunker.split("the milk went sour")) { texts ->
            Result.success(texts.map { vector(4, 1f) })
        }.getOrThrow()
        index.put("a.txt", "keep", Chunker.split("the milk was poured away")) { texts ->
            Result.success(texts.map { vector(4, 2f) })
        }.getOrThrow()
        index.put("a.txt", "drop", Chunker.split("a version nobody saved")) { texts ->
            Result.success(texts.map { vector(4, 3f) })
        }.getOrThrow()

        val (rows, _) = index.sweep(setOf("keep"))

        assertEquals("one row swept", 1, rows)
        assertEquals("the reachable version stays", setOf("a.txt" to "keep"), index.versions())
        // **The working tree is not a commit's business.** A prune says nothing about it, so its row
        // must survive a sweep that emptied every version.
        val current = index.nearest(vector(4, 1f), 10, history = false).getOrThrow()
        assertTrue("the current row survives", current.isNotEmpty())
        assertTrue(
            "and the dead vector went with it",
            index.vectors() <= 2,
        )
    }

    @Test
    fun `a sweep with nothing reachable releases every version vector`() {
        built()
        index.put("a.txt", "gone", Chunker.split("text nobody reaches")) { texts ->
            Result.success(texts.map { vector(4, 3f) })
        }.getOrThrow()
        assertEquals("one vector", 1, index.vectors())

        val (rows, vectors) = index.sweep(emptySet())

        assertEquals("one row", 1, rows)
        assertEquals("and its vector", 1, vectors)
        assertEquals("nothing left", 0, index.vectors())
    }

    @Test
    fun `a file whose text changed but whose time did not is noticed`() {
        // **Two numbers, because one of them lies.** A restore, a `cp -p` or two saves inside one
        // filesystem tick leave an edited file with its old modification time; without the size the
        // index would answer about text the project no longer has.
        built()
        index.note("a.txt", mtime = 1_000L, state = SearchIndex.INDEXED, size = 40L)

        assertEquals("the pair it was indexed at", 1_000L to 40L, index.seen("a.txt"))
        // Same time, different size: not the pair, so the walk re-reads it.
        assertTrue("a different size is a different file", index.seen("a.txt") != (1_000L to 41L))
        // Same pair: unchanged, and the walk spends nothing.
        assertEquals("an unchanged file", 1_000L to 40L, index.seen("a.txt"))
    }

    @Test
    fun `a failed file is never up to date, so the next pass retries it`() {
        built()
        index.note("a.txt", mtime = 1_000L, state = SearchIndex.FAILED, error = "it was too big", size = 40L)

        assertEquals("nothing to compare, so it is read again", null, index.seen("a.txt"))
        assertEquals("and the reason is kept", 1 to "it was too big", index.failed())
    }

    // ---- what the file is --------------------------------------------------

    @Test
    fun `the index lives in agent, which is not committed`() {
        // **Derived data in a repository is a file that conflicts on every clone and rebuilds itself
        // anyway.** `agent/` is already excluded, so the index inherits that.
        assertTrue(
            "the database is inside the app's own folder: ${index.file}",
            index.file.path.contains("/agent/"),
        )
    }
}
