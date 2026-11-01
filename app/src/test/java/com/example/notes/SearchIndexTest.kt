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

    /**
     * A vector of [size] where every component is [value] — **so a query can be made to land near a
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
