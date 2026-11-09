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
 * **Search by meaning, inside one project.**
 *
 * The operator's decision is the shape of this file: *"semantic search is per project, make it this
 * way, so that account wide semantic search is forbidden and scope must be project and lower."* So
 * the three things tested hardest are the three that would break that:
 *
 * 1. **The index is this project's** — another project's words are not reachable, and its text is
 *    never read. A search that quietly reached across would be reading a vector space it cannot score
 *    and a project it was not given.
 * 2. **The index keeps itself current** — a file written after the last search is embedded on the
 *    next one, and a file that did not change costs nothing. That is the whole reason the write
 *    happens here rather than in a save hook four different writers would each have to remember.
 * 3. **Every failure says what happened** — no model, no dimension, a reranker that is down, a file
 *    too big, a pass out of time. No regex fallback: a text search standing in for a meaning search
 *    answers a question nobody asked while looking like the one that was.
 *
 * **No socket.** [Embedder] is an interface for exactly this: the fake below turns words into axes,
 * so "closer in meaning" is arithmetic a test can predict, and nothing here costs money.
 */
// **Robolectric, because the index is SQLite.** `SQLiteDatabase` is the platform's, so a plain JVM
// test throws at the first `open` — the same reason `SearchIndexTest` runs this way.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SemanticSearchTest {

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

    private fun write(project: String, path: String, text: String): File {
        val file = File(File(ws.accountDir, project), path)
        file.parentFile?.mkdirs()
        file.writeText(text)
        return file
    }

    private fun engine(
        embedder: Embedder = Fake(),
        model: PickedModel? = PickedModel("embedding.json", dimensions = 4),
        rerank: PickedModel? = null,
    ) = SemanticSearch(
        ws = ws,
        project = "shopping",
        embedder = embedder,
        embedding = { model },
        rerank = { rerank },
        // **The folder, answered the way the app answers it**: a template is read for the model id it
        // names, so a pointer alone is not enough to embed — the file has to exist and to say which
        // model. A test that named a pointer and stubbed the embedder would be testing its own stub.
        schema = { name ->
            Result.success(
                Schema(
                    address = "https://x.test/v1",
                    url = "embeddings",
                    body = org.json.JSONObject(
                        """{"model":"${name.substringBeforeLast('.')}","input":"{{input}}"}""",
                    ),
                ),
            )
        },
    )

    // ---- 1. what it answers ------------------------------------------------

    @Test
    fun `a file is found by what it is about, and the answer says which folder was searched`() {
        write("shopping", "milk.txt", "the milk went sour\n")
        write("shopping", "money.txt", "we spent money on bread\n")

        val answer = engine().answer(null, "milk", paths = 10, depth = 8, Deadline.none()).getOrThrow()

        // **`1-2`, because the chunk really is two lines.** A file ending in a newline splits to
        // `["the milk went sour", ""]`, and `Chunker` takes whole lines — so the chunk covers both
        // and the answer says so. That range is exactly what `read {from: 1, to: 2}` takes, which is
        // the point of naming it: a model can go straight to the text it was shown.
        assertTrue("the right file: $answer", answer.contains("shopping/milk.txt:1-2: the milk went sour"))
        assertFalse("and not the other one: $answer", answer.contains("money.txt"))
        // **The extent, on every answer.** A person reading "nothing matches" cannot tell an empty
        // project from a search that only looked in one folder, and the second is this tool's design.
        assertTrue("it names the project it searched: $answer", answer.contains("shopping/ only"))
        assertTrue("and says why: $answer", answer.contains("per project"))
    }

    @Test
    fun `another project's words are not reachable, because the index is this project's`() {
        // **The operator's boundary, tested from the side that would break it.** `groceries` holds the
        // word the query is about, and `shopping` does not — so an account-wide index would answer
        // with it, and a per-project one cannot.
        write("groceries", "list.txt", "milk milk milk\n")
        write("shopping", "money.txt", "we spent money on bread\n")

        val answer = engine().answer(null, "milk", paths = 10, depth = 8, Deadline.none()).getOrThrow()

        assertFalse("nothing from the other project: $answer", answer.contains("groceries/"))
        assertFalse("not even its file name: $answer", answer.contains("list.txt"))
    }

    @Test
    fun `scope narrows within the project, and a scope outside it answers nothing there`() {
        write("shopping", "milk.txt", "the milk went sour\n")
        write("shopping", "wiki/milk.md", "the milk went sour\n")

        val onlyWiki = Regex("wiki/")
        val answer = engine().answer(onlyWiki, "milk", paths = 10, depth = 8, Deadline.none()).getOrThrow()

        assertTrue("the file in scope: $answer", answer.contains("shopping/wiki/milk.md"))
        assertFalse("and not the one outside it: $answer", answer.contains("shopping/milk.txt"))
    }

    @Test
    fun `depth bounds the answer the same way the text walk does`() {
        // **One arithmetic in both modes**, or `depth` would mean two things and the second would be
        // a surprise: the account is level 0, a project is 1, so a project's own file is 2.
        write("shopping", "top.txt", "milk\n")
        write("shopping", "a/b/deep.txt", "milk\n")

        val shallow = engine().answer(null, "milk", paths = 10, depth = 2, Deadline.none()).getOrThrow()
        assertTrue("the top-level file: $shallow", shallow.contains("shopping/top.txt"))
        assertFalse("but not the deep one: $shallow", shallow.contains("a/b/deep.txt"))

        val deep = engine().answer(null, "milk", paths = 10, depth = 4, Deadline.none()).getOrThrow()
        assertTrue("raising depth reaches it: $deep", deep.contains("shopping/a/b/deep.txt"))
    }

    // ---- 1b. saved versions ------------------------------------------------

    /**
     * A project with a repository, and a file saved twice with different text.
     *
     * **The commits are real**, written through [ProjectGit] the way the app writes them, because the
     * history pass reads git rather than a fixture: a fake repository would test the fake.
     */
    private fun savedTwice(): String {
        val git = ProjectGit(ws, { null }, { GitAuthor("Test", "test@example.com") })
        write("shopping", "milk.txt", "the milk went sour\n")
        // **`init` is the first save.** It commits whatever is already on disk — so the text written
        // above is the initial commit's, and a `commit` right after it would find nothing to write
        // and answer null. The version that says "sour" is therefore the *oldest* commit, which is
        // exactly the one a search has to reach back for.
        git.init("shopping").getOrThrow()
        write("shopping", "milk.txt", "the milk was poured away\n")
        requireNotNull(git.commit("shopping", "second"))
        return RepoHistory(ws.projectDir("shopping")).log().last().id
    }

    @Test
    fun `history finds text that was deleted, and names the save it was in`() {
        val second = savedTwice()
        write("shopping", "milk.txt", "nothing about dairy here\n")

        val answer = engine()
            .answer(null, "milk", paths = 10, depth = 8, Deadline.none(), history = true)
            .getOrThrow()

        // **`path@sha7`, which is what `read {revision}` takes** — so the row is usable as the next
        // call with no translation, and the model never has to be told which kind of id it holds.
        assertTrue("the deleted line, with its save: $answer", answer.contains("shopping/milk.txt@${second.take(7)}"))
        assertTrue("and the text: $answer", answer.contains("sour"))
    }

    @Test
    fun `a version older than forty saves is still found`() {
        // **The window is gone, and this is the test that says so.** The history pass used to read
        // the newest forty commits, inherited from the text-mode list where forty is how far a person
        // scrolls. For an index that was the wrong question - "where did I write about this" is not
        // "somewhere in the last forty saves" - and the operator's answer was that history is
        // unbounded: the app is for medium projects, and squashing a bigger one is git's job.
        val git = ProjectGit(ws, { null }, { GitAuthor("Test", "test@example.com") })
        // **The oldest version says something the others do not**, so the search has one candidate
        // rather than forty-two equal ones. The first attempt asked for "milk", which every version
        // holds — so the answer was the top ten of a tie, and the oldest was correctly absent from it.
        // A test that cannot tell "not indexed" from "ranked eleventh" proves nothing.
        write("shopping", "milk.txt", "the milk went sour and it cost money\n")
        git.init("shopping").getOrThrow()
        // **Read now, not after the saves.** `log()` answers newest-first with its own limit, so
        // asking it at the end for "the last one" gives the oldest *of the window it returned* rather
        // than the oldest save - which is exactly the mistake this test made first.
        val oldest = RepoHistory(ws.projectDir("shopping")).log().first().id
        // **Forty-one saves on top**, which is one past the window that used to exist. Not sixty:
        // each save is a real commit in a real repository, and the point is the boundary rather than
        // the distance - a test that proves "the window is gone" in four seconds rather than seven is
        // a test that runs in every shard.
        for (i in 1..41) {
            write("shopping", "milk.txt", "the milk was poured away, revision $i\n")
            requireNotNull(git.commit("shopping", "save $i"))
        }

        // **Only the oldest version mentions money**, so this answer is a fact about the index rather
        // than about a ranking among equals.
        val answer = engine()
            .answer(null, "money", paths = 10, depth = 8, Deadline.none(), history = true)
            .getOrThrow()

        assertTrue(
            "the oldest save is still indexed: $answer",
            answer.contains("milk.txt@${oldest.take(7)}"),
        )
    }

    @Test
    fun `without history, a deleted line is not answered`() {
        savedTwice()
        write("shopping", "milk.txt", "nothing about dairy here\n")

        val answer = engine().answer(null, "milk", paths = 10, depth = 8, Deadline.none()).getOrThrow()

        assertFalse("the old text is not in the answer: $answer", answer.contains("sour"))
    }

    @Test
    fun `a saved picture is never indexed, and its bytes never reach a vector`() {
        // **A picture's bytes are not text, and `looksBinary` is a guess that a PNG survives.** It
        // decodes as UTF-8 with replacement characters, holds plenty of bytes that are valid
        // characters, and passes. Indexing it would embed noise at 400 KB a version and pollute every
        // answer it appeared in - so the rule is the *kind*, which is a fact about the name.
        val git = ProjectGit(ws, { null }, { GitAuthor("Test", "test@example.com") })
        write("shopping", "milk.txt", "the milk went sour\n")
        val picture = File(File(ws.accountDir, "shopping"), "page.png")
        // **Real PNG magic bytes, then text a search could match** - so a pass that indexed it would
        // be caught by the words rather than only by the count.
        picture.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
            "milk milk milk the milk went sour".toByteArray())
        git.init("shopping").getOrThrow()
        // A second save, so the picture is a *version* as well as a current file - the history half
        // is where the guess used to be the only check.
        write("shopping", "milk.txt", "the milk was poured away\n")
        requireNotNull(git.commit("shopping", "second"))

        val answer = engine()
            .answer(null, "milk", paths = 10, depth = 8, Deadline.none(), history = true)
            .getOrThrow()

        assertFalse("the picture is not a row: $answer", answer.contains("page.png"))
        assertTrue("while the text still is: $answer", answer.contains("milk.txt"))
    }

    @Test
    fun `a version that changes one line does not re-embed the whole file`() {
        // **The measurement the whole design rests on.** A file's chunks are keyed by their text, so
        // a version differing in one paragraph shares every other vector with the version before it —
        // which is why "index the history" is not "index the project forty times over".
        val git = ProjectGit(ws, { null }, { GitAuthor("Test", "test@example.com") })
        val body = (1..40).joinToString("\n") { "line $it about milk" }
        write("shopping", "long.txt", body + "\n")
        // **`init` is the first version**, and it holds every line of the body.
        git.init("shopping").getOrThrow()
        write("shopping", "long.txt", body + "\nthe last line mentions bread\n")
        requireNotNull(git.commit("shopping", "second"))

        val fake = Fake()
        engine(fake).index(Deadline.none()).getOrThrow()

        // **The second version's cost is the paragraph that changed**, plus the unchanged tail of the
        // overlapping chunk — nowhere near the whole file twice.
        val chunksForLong = fake.texts.count { it.contains("about milk") }
        assertTrue(
            "the shared lines were embedded once, not once per version: $chunksForLong requests",
            chunksForLong < 40,
        )
    }

    @Test
    fun `a second pass over an unchanged history embeds nothing`() {
        savedTwice()
        val first = engine().index(Deadline.none()).getOrThrow()
        assertTrue("the versions were indexed: ${first.said()}", first.versions > 0)

        val fake = Fake()
        val again = engine(fake).index(Deadline.none()).getOrThrow()

        assertEquals("nothing new: ${again.said()}", 0, again.versions)
        // **Nothing embedded at all**, which is stronger than "no new versions": the working tree is
        // unchanged (mtime and size) and every version's text is already held, so the pass costs no
        // request rather than a request that happens to add nothing.
        assertTrue("and no request was made: ${fake.texts}", fake.texts.isEmpty())
    }

    @Test
    fun `a project with no repository has no versions and is still searchable`() {
        write("shopping", "milk.txt", "the milk went sour\n")

        val caught = engine().index(Deadline.none()).getOrThrow()

        assertEquals("no versions: ${caught.said()}", 0, caught.versions)
        assertTrue("and the file is indexed: ${caught.said()}", caught.done == 1)
    }

    @Test
    fun `a row no commit reaches is left out and counted, rather than answered with a dead id`() {
        // **The prune case, built by hand**: the index holds a version of text that never existed in
        // this repository, which is what a rewritten history leaves behind.
        savedTwice()
        val index = SearchIndex.open(ws.projectDir("shopping"))
        try {
            index.declare("test/embed", 4).getOrThrow()
            val fake = Fake()
            val ghost = "the milk went sour\n"
            index.put("milk.txt", "0".repeat(40), Chunker.split(ghost)) { texts ->
                Result.success(texts.map { text -> fake.embed(listOf(text), "test/embed", 4).getOrThrow().first() })
            }.getOrThrow()
        } finally {
            index.close()
        }

        val answer = engine()
            .answer(null, "milk", paths = 10, depth = 8, Deadline.none(), history = true)
            .getOrThrow()

        // **The ghost's text must not be answered at all**, because its only id is a blob no commit
        // reaches and `read` could not resolve it. The live version of the same words is still
        // answered — that is the point of dropping the row rather than the answer.
        assertFalse("no dead id is handed out: $answer", answer.contains("milk.txt@0000000"))
        assertTrue("and the loss is said out loud: $answer", answer.contains("no longer in this project"))
    }

    // ---- 2. keeping the index current --------------------------------------

    @Test
    fun `a file written after the last search is indexed on the next one`() {
        write("shopping", "milk.txt", "the milk went sour\n")

        engine().answer(null, "milk", paths = 10, depth = 8, Deadline.none()).getOrThrow()
        write("shopping", "bread.txt", "we spent money on bread\n")
        val answer = engine().answer(null, "bread", paths = 10, depth = 8, Deadline.none()).getOrThrow()

        assertTrue("the new file is there: $answer", answer.contains("shopping/bread.txt"))
    }

    @Test
    fun `an unchanged project costs one comparison per file and one request for the query`() {
        // **The claim that makes indexing inside a search affordable.** The second search must embed
        // the query and nothing else; if it re-embedded the files, every search would cost the price
        // of the whole project.
        val fake = Fake()
        write("shopping", "milk.txt", "the milk went sour\n")
        write("shopping", "bread.txt", "we spent money on bread\n")

        engine(fake).answer(null, "milk", paths = 10, depth = 8, Deadline.none()).getOrThrow()
        val afterFirst = fake.texts.size
        engine(fake).answer(null, "milk", paths = 10, depth = 8, Deadline.none()).getOrThrow()

        assertEquals("only the query was embedded the second time", afterFirst + 1, fake.texts.size)
        assertEquals("and it was the query", "milk", fake.texts.last())
    }

    @Test
    fun `a file saved again with new text is re-indexed, because its time changed`() {
        val file = write("shopping", "milk.txt", "the milk went sour\n")
        engine().answer(null, "milk", paths = 10, depth = 8, Deadline.none()).getOrThrow()

        // **A different time, guaranteed**: the comparison is the file's own number, and a test that
        // wrote twice inside one millisecond would be asserting that the filesystem has a clock.
        val later = file.lastModified() + 5_000
        file.writeText("the bread went stale\n")
        file.setLastModified(later)

        val answer = engine().answer(null, "bread", paths = 10, depth = 8, Deadline.none()).getOrThrow()
        assertTrue("the new text is what is indexed: $answer", answer.contains("bread went stale"))
    }

    @Test
    fun `a folder the agent owns is not indexed, and neither is a link out of the project`() {
        // `agent/` holds the log, the fetched pages and this index itself — indexing it would embed
        // the record of the search into the thing being searched.
        write("shopping", "agent/notes.txt", "milk in the log\n")
        write("shopping", "milk.txt", "the milk went sour\n")

        val outside = File(temp.root, "elsewhere").apply { mkdirs() }
        File(outside, "secret.txt").writeText("milk outside the project\n")
        val link = File(ws.projectDir("shopping"), "escape")
        val linked = try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
            true
        } catch (e: Throwable) {
            false // a filesystem that cannot make one cannot be escaped through one either
        }

        val answer = engine().answer(null, "milk", paths = 10, depth = 8, Deadline.none()).getOrThrow()

        assertFalse("not the app's own folder: $answer", answer.contains("agent/"))
        if (linked) assertFalse("and not behind the link: $answer", answer.contains("secret.txt"))
    }

    // ---- 3. what it says when it cannot -----------------------------------

    @Test
    fun `no embedding model is a refusal that names the setting`() {
        val answer = engine(model = null).answer(null, "milk", 10, 8, Deadline.none())

        assertTrue("refused", answer.isFailure)
        val said = answer.exceptionOrNull()?.message.orEmpty()
        assertTrue("naming the row: $said", said.contains("Embedding model"))
        assertTrue("and the folder it is in: $said", said.contains("Settings"))
    }

    @Test
    fun `a model chosen after the index was built is refused rather than ignored`() {
        // **The index's record used to win in silence**, so choosing a new embedding model in Settings
        // had no effect and said nothing — a silent no-op. Same dimensions do not rescue it: two
        // models at 1024 are two coordinate systems, and a cosine between them is a number that looks
        // like a score. The refusal names both models and the way out.
        write("shopping", "milk.txt", "the milk went sour\n")
        engine().answer(null, "milk", 10, 8, Deadline.none()).getOrThrow()

        val switched = engine(model = PickedModel("other.json", dimensions = 4))
            .answer(null, "milk", 10, 8, Deadline.none())

        assertTrue("refused", switched.isFailure)
        val said = switched.exceptionOrNull()?.message.orEmpty()
        // **The index remembers the model id** — read from the template when it was built, because a
        // template can be edited to ask for another model while its name stays the same. The sentence
        // names both ids and the file that asks for the second, which is what a person can act on.
        assertTrue("naming the model that built it: $said", said.contains("embedding"))
        assertTrue("and the model asked for now: $said", said.contains("other"))
        assertTrue("and the template that asks: $said", said.contains("other.json"))
        assertTrue("and that they cannot be compared: $said", said.contains("cannot be compared"))
        assertTrue("and what empties it: $said", said.contains("emptied"))
    }

    @Test
    fun `an index whose config was lost still answers, because the index remembers its model`() {
        // **The other side of that rule.** A phone whose `model_conf.json` is gone has an index that
        // knows exactly what it is; refusing then would throw away work for no reason.
        write("shopping", "milk.txt", "the milk went sour\n")
        engine().answer(null, "milk", 10, 8, Deadline.none()).getOrThrow()

        val answer = engine(model = null).answer(null, "milk", 10, 8, Deadline.none())

        assertTrue("it still answers: ${answer.exceptionOrNull()?.message}", answer.isSuccess)
        assertTrue("with the file", answer.getOrThrow().contains("shopping/milk.txt"))
    }

    @Test
    fun `an embedding model with no dimension is refused, because an index is one size`() {
        val answer = engine(model = PickedModel("embedding.json", dimensions = null))
            .answer(null, "milk", 10, 8, Deadline.none())

        val said = answer.exceptionOrNull()?.message.orEmpty()
        assertTrue("it says what is missing: $said", said.contains("dimension"))
        assertTrue("and what to do: $said", said.contains("Settings"))
    }

    @Test
    fun `a skip larger than the index takes is recorded with its size`() {
        write("shopping", "big.txt", "milk ".repeat(SemanticSearch.MAX_CHARS / 4))
        write("shopping", "milk.txt", "the milk went sour\n")

        val answer = engine().answer(null, "milk", paths = 10, depth = 8, Deadline.none()).getOrThrow()

        assertTrue("the file that fits is there: $answer", answer.contains("shopping/milk.txt"))
        assertTrue("and the one that did not is named: $answer", answer.contains("could not be indexed"))
        assertTrue("with the reason: $answer", answer.contains("kB"))
    }

    @Test
    fun `a pass that runs out of time answers with what it has and says what is left`() {
        // **A clock that has already run out.** `Deadline` reads the clock once when it is made, so a
        // jump on the second read is "the limit passed while the work was starting" — which is
        // exactly the case a search must survive with a sentence rather than with nothing.
        write("shopping", "milk.txt", "the milk went sour\n")
        var reads = 0
        val clock = { if (reads++ == 0) 0L else 10_000L }

        val answer = engine()
            .answer(null, "milk", 10, 8, Deadline(1_000L, clock))
            .getOrThrow()

        assertTrue("it says the pass stopped: $answer", answer.contains("indexing stopped"))
        assertTrue("and names the limit: $answer", answer.contains("time limit"))
    }

    @Test
    fun `a rerank model reorders the answer and its score is what is shown`() {
        // **Two files about milk, and the vectors prefer one of them.** Cosine ignores length, so the
        // difference has to be in the mixture: `a.txt` says milk and something else, `b.txt` says only
        // milk — so `b` is the vectors' first. The fake reranker reverses, which is the only way to
        // tell its order from the vector order.
        write("shopping", "a.txt", "milk timeout timeout\n")
        write("shopping", "b.txt", "milk\n")
        val fake = Fake(reverses = true)

        val answer = engine(fake, rerank = PickedModel("rerank.json"))
            .answer(null, "milk", paths = 10, depth = 8, Deadline.none()).getOrThrow()

        val rows = answer.lines().filter { it.startsWith("shopping/") }
        assertEquals("both files", 2, rows.size)
        assertTrue("the reranker's first is first: $answer", rows[0].startsWith("shopping/a.txt"))
        assertTrue("with its score: $answer", rows[0].contains("[0.90]"))
        assertTrue("and the second carries the lower one: $answer", rows[1].contains("[0.80]"))
    }

    @Test
    fun `a reranker that is down answers in vector order and says so`() {
        // **Two files, because a reranker is only consulted about a list it can reorder.** With one
        // candidate there is nothing to reorder and nothing to be down about — the first version of
        // this test asked for a note about a step that correctly never ran.
        write("shopping", "milk.txt", "the milk went sour\n")
        write("shopping", "toast.txt", "milk and bread\n")
        val fake = Fake(brokenRerank = true)

        val answer = engine(fake, rerank = PickedModel("test/rerank", "test"))
            .answer(null, "milk", paths = 10, depth = 8, Deadline.none()).getOrThrow()

        // **Still a meaning search**, so this is a note rather than a failure: the vector order is the
        // answer the index gave, and it answers the question that was asked. The reason travels
        // because a person whose model is misconfigured deserves to see it.
        assertTrue("the hit is there: $answer", answer.contains("shopping/milk.txt"))
        assertTrue("and the failure is named: $answer", answer.contains("vector order"))
        assertTrue("with its reason: $answer", answer.contains("reranker is down"))
    }

    /**
     * An [Embedder] that turns four words into four axes.
     *
     * **Deterministic on purpose**: a vector count per keyword means "closer in meaning" is arithmetic
     * the assertions can predict, and the test cannot pass by luck the way a random vector would.
     */
    private class Fake(
        /** Answer the rerank in reverse, with descending scores — a reorder a test can see. */
        val reverses: Boolean = false,
        val brokenRerank: Boolean = false,
    ) : Embedder {

        /** Every text embedded, in the order it was asked for. */
        val texts = mutableListOf<String>()

        override fun embed(texts: List<String>, model: String, dims: Int): Result<List<FloatArray>> {
            this.texts += texts
            return Result.success(texts.map { vector(it) })
        }

        override fun rerank(
            query: String,
            documents: List<String>,
            model: String,
        ): Result<List<Embedder.Ranked>> {
            if (brokenRerank) {
                return Result.failure(IllegalStateException("the reranker is down"))
            }
            if (!reverses) {
                // **A cosine, the same function the index uses**, so "the reranker agreed with the
                // vectors" is one arithmetic rather than two that could drift.
                val about = vector(query)
                val scored = documents.mapIndexed { i, d ->
                    Embedder.Ranked(i, SearchIndex.cosine(about, vector(d)))
                }
                return Result.success(scored.sortedByDescending { it.score })
            }
            return Result.success(
                documents.indices.reversed().mapIndexed { place, at ->
                    Embedder.Ranked(at, 0.9 - place * 0.1)
                },
            )
        }

        private fun vector(text: String): FloatArray = FloatArray(WORDS.size) { i ->
            var from = 0
            var count = 0
            while (true) {
                val at = text.indexOf(WORDS[i], from, ignoreCase = true)
                if (at < 0) break
                count++
                from = at + WORDS[i].length
            }
            count.toFloat()
        }

        companion object {
            /** The four axes. Any text not using them is the zero vector, which matches nothing. */
            val WORDS = listOf("milk", "bread", "timeout", "money")
        }
    }
}
