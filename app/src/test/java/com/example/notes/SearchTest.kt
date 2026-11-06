package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `search` — **one question, six filters**, and the command that replaced `tree` and `history`.
 *
 * The operator's shape: *"scope (path of folder/file which branch to search), query (optional
 * filecontent filter), regex/semantic (type of search - when there is query), history (optional flag
 * to also search historic file versions), depth (how deeply search the branch), rows (how many
 * results to show)"*.
 *
 * Four things are worth testing and they are the four that break quietly:
 *
 * 1. **It crosses projects**, because a search that silently stayed in one project would answer
 *    "nothing" about a question it never asked.
 * 2. **`scope` is a pattern over the path**, so one key covers a folder (`wiki/`), a project
 *    (`^shopping/`), a file (`milk.txt`) and a name suffix (`\\.md$`). The last of those is what
 *    removed `tree`'s separate `name` argument.
 * 3. **No `query` is a listing, not an error.** `scope` plus `depth` is "what is here" — the same
 *    walk with nothing filtered out. That is the objection the operator answered and the reason
 *    `tree` could go.
 * 4. **`paths` counts paths, not matches**, because a file matching forty times is one thing to read.
 */
class SearchTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var ws: Workspace
    private lateinit var git: ProjectGit
    private lateinit var log: AgentLog

    @Before
    fun setUp() {
        ws = Workspace(temp.root)
        ws.projectDir("shopping").mkdirs()
        git = ProjectGit(ws, { null }, { GitAuthor("Muffy", "muffy@example.com") })
        log = AgentLog.open(ws.projectDir("shopping"))
    }

    private fun tools() = AgentTools(ws, "shopping", git, log)

    private fun call(args: String) = Call(id = "1", tool = "search", args = args)

    /** Writes a file, making the folders on the way, and returns it. */
    private fun write(project: String, path: String, text: String): File {
        val file = File(File(ws.accountDir, project), path)
        file.parentFile?.mkdirs()
        file.writeText(text)
        return file
    }

    // ---- it crosses projects ------------------------------------------------

    @Test
    fun `a fact in another project is found without being told to look there`() {
        // **The reason `search` is not `tree` with a `grep`.** The agent has a question and does not
        // know which project holds the answer. If the search started at the current project it would
        // answer "nothing matches" — a true sentence about the wrong question, which is worse than a
        // refusal because the agent believes it.
        write("shopping", "notes.txt", "milk\neggs\n")
        write("work", "notes.txt", "the retry timeout is ninety seconds\n")

        val answer = tools().run(call("""{"query":"retry timeout"}"""))

        assertTrue("it is an answer, not a refusal: $answer", answer.startsWith("0\n"))
        assertTrue("and it names the other project: $answer", answer.contains("work/notes.txt"))
        assertFalse("and not the project it was already in", answer.contains("shopping/notes.txt"))
    }

    @Test
    fun `the system project is searched too, because the core articles live there`() {
        // The system project is a project like any other to a walk of the account, which is what
        // makes the core `wiki/` findable without a second rule saying so.
        write("shopping", "notes.txt", "milk\n")
        write("system", "muffycodes_data/wiki/retries.md", "exponential backoff, capped at 90 s\n")

        val answer = tools().run(call("""{"query":"exponential backoff"}"""))

        assertTrue("found: $answer", answer.contains("system/muffycodes_data/wiki/retries.md"))
    }

    // ---- `scope` is a pattern over the path ---------------------------------

    @Test
    fun `a scope with one segment means that folder in every project`() {
        // **A pattern, not a list of prefixes.** `wiki/` is not a mode called "wiki"; it is a
        // substring of the path, so it finds the wiki of every project at once.
        write("shopping", "wiki/milk.md", "milk is bought on Saturdays\n")
        write("work", "wiki/retries.md", "milk is not involved here\n")
        write("shopping", "notes.txt", "milk, twice\n")

        val answer = tools().run(call("""{"query":"milk","scope":"wiki/"}"""))

        val said = answer.replace("\n", " | ")
        assertTrue("both wikis are searched: $said", answer.contains("shopping/wiki/milk.md"))
        assertTrue("including the other project's: $said", answer.contains("work/wiki/retries.md"))
        assertFalse("and the loose note is not: $said", answer.contains("shopping/notes.txt"))
    }

    @Test
    fun `a scope with the project in it means one project`() {
        write("shopping", "wiki/milk.md", "milk is bought on Saturdays\n")
        write("work", "wiki/retries.md", "milk is not involved here\n")

        val answer = tools().run(call("""{"query":"milk","scope":"shopping/wiki/"}"""))

        assertTrue("the one asked for: $answer", answer.contains("shopping/wiki/milk.md"))
        assertFalse("and only that one: $answer", answer.contains("work/wiki/retries.md"))
    }

    @Test
    fun `a scope that matches nothing says so about the scope`() {
        // **The sentence has to name what was searched.** "nothing matches" without the scope reads
        // as "the account has no such text", and the agent then stops looking everywhere.
        write("shopping", "notes.txt", "milk\n")

        val answer = tools().run(call("""{"query":"milk","scope":"nowhere/"}"""))

        assertTrue("it is not a failure: $answer", answer.startsWith("0\n"))
        assertTrue("and names the scope: $answer", answer.contains("nowhere/"))
    }

    @Test
    fun `a scope that is a name suffix replaces tree's name argument`() {
        // **The reason `tree` could lose its `name` key.** The basename is part of the path, so a
        // pattern that anchors at the end of the path is the same filter — and it works on the whole
        // account rather than one folder, which is more than `tree` could do.
        write("shopping", "milk.md", "# milk\n")
        write("shopping", "milk.txt", "milk, twice\n")
        write("work", "notes.md", "# other\n")

        val answer = tools().run(call("""{"scope":"\\.md$"}"""))

        assertTrue("the markdown is listed: $answer", answer.contains("shopping/milk.md"))
        assertTrue("including the other project's: $answer", answer.contains("work/notes.md"))
        assertFalse("and the text file is not: $answer", answer.contains("shopping/milk.txt"))
    }

    @Test
    fun `a scope naming one file answers that file and its folder's siblings not at all`() {
        write("shopping", "milk.txt", "milk\n")
        write("shopping", "eggs.txt", "eggs\n")

        val answer = tools().run(call("""{"scope":"milk\\.txt$"}"""))

        assertTrue("the file asked for: $answer", answer.contains("shopping/milk.txt"))
        assertFalse("and nothing beside it: $answer", answer.contains("eggs.txt"))
    }

    // ---- `paths` counts files ----------------------------------------------

    @Test
    fun `a file matching many times is one answer, and says how many more`() {
        // **A count of files, because a file is what the agent reads next.** Forty matches in one
        // file is one thing to open, and listing it forty times would fill the answer with the same
        // path and hide the second file that also matched.
        write("shopping", "many.txt", (1..40).joinToString("\n") { "milk $it" })
        write("shopping", "once.txt", "milk once\n")

        val answer = tools().run(call("""{"query":"milk"}"""))

        // **One line in the message, because CI truncates at a newline.** An assertion that fails
        // with half its evidence missing is one that costs a second CI run to understand.
        val said = answer.replace("\n", " | ")
        // **One heading per file, not one per match.** The path appears in each of its own rows,
        // so counting occurrences would be counting lines; what matters is that the file is not
        // listed as a second answer beside itself.
        assertTrue("the many file is one answer: $said", answer.split("many.txt").size == 22)
        // **Twenty rows, then a count of what was left.** The old answer was one row and
        // `(+39 more)` — a number a model could not act on, which made it search again to find
        // places the answer had already counted. Twenty is `HITS_PER_FILE`, and the remainder is
        // still counted so a model knows whether it is looking at a file with twenty matches or two
        // thousand.
        assertTrue("twenty rows: $said", answer.contains("many.txt:20: milk 20"))
        assertFalse("and no twenty-first: $said", answer.contains("many.txt:21:"))
        assertTrue(
            "and says how many were left: $said",
            answer.contains("(+20 more lines match"),
        )
        assertTrue("and the second file is not crowded out: $said", answer.contains("once.txt"))
    }

    @Test
    fun `paths stops the answer and says so, rather than trailing off`() {
        // **A list that just ends is a list a model reads as complete.** The note is what tells it
        // there is more to ask for, and the sentence names the two things that would narrow it.
        for (i in 1..5) write("shopping", "note$i.txt", "milk $i\n")

        val answer = tools().run(call("""{"query":"milk","paths":2}"""))

        assertTrue("it says it stopped: $answer", answer.contains("stopped at 2 paths"))
        assertTrue("and how to see the rest: $answer", answer.contains("narrow"))
        assertEquals(
            "and there are exactly two hits",
            2,
            answer.lines().count { it.contains("note") },
        )
    }

    @Test
    fun `a listing cut off inside a folder still says it stopped`() {
        // **The bug the first version had.** When the entry that filled the limit was a *folder*, its
        // contents were never visited and nothing recorded that they had been skipped — so a
        // truncated listing looked complete, which is the one thing this note exists to prevent.
        // Two entries in `shopping/`, and the second is a folder with a file inside it.
        write("shopping", "a.txt", "a\n")
        write("shopping", "z/inner.txt", "inner\n")

        val answer = tools().run(call("""{"scope":"^shopping/","paths":2}"""))

        assertEquals("two entries", 2, answer.lines().count { it.contains("shopping/") })
        assertTrue("and it says there was more: $answer", answer.contains("stopped at 2 paths"))
    }

    @Test
    fun `a walk that ends exactly at the limit does not promise more`() {
        // **"stopped at N" is only true when the walk stopped at N.** If the account holds exactly
        // `paths` matching files, there is nothing more, and promising a remainder sends the agent
        // looking for something that does not exist.
        for (i in 1..2) write("shopping", "note$i.txt", "milk $i\n")

        val answer = tools().run(call("""{"query":"milk","paths":2}"""))

        assertFalse("no false promise: $answer", answer.contains("stopped at"))
        assertEquals("both are there", 2, answer.lines().count { it.contains("note") })
    }

    // ---- history: the same search, one axis wider ---------------------------

    @Test
    fun `a version whose text is no longer in the file is still found`() {
        // **The operator's reason for wanting it**: *"I believe history search will be important."*
        // A line that was deleted is unreachable by any current-file search, and it is exactly what
        // a person asks for when they want to know why something used to be the way it was.
        val repo = File(ws.accountDir, "shopping")
        val file = File(repo, "retries.txt")
        file.writeText("the timeout is 90 seconds\n")
        git.init("shopping").getOrThrow()
        git.commit("shopping", "Started")

        file.writeText("the timeout is 30 seconds\n")
        git.commit("shopping", "Shortened")

        // **The current file no longer says 90**, so a search without `history` must not find it.
        val now = tools().run(call("""{"query":"90 seconds"}"""))
        val past = tools().run(call("""{"query":"90 seconds","history":true}"""))

        assertTrue("the old text is not in the file: $now", now.contains("nothing matches"))
        assertTrue(
            "but history finds the version: ${past.replace("\n", " | ")}",
            past.contains("retries.txt@"),
        )
        // **`path@sha7`, which `read {revision}` and `restore` already accept** — so a hit is
        // directly usable as the next call, with no translation.
        val shown = Regex("""retries\.txt@([0-9a-f]{7})""").find(past)?.groupValues?.get(1)
        assertTrue("and the sha is seven characters: $shown", shown != null && shown.length == 7)
    }

    @Test
    fun `history with no query lists a file's versions, which is what the old command did`() {
        // **A version list is a listing too.** The merged command reaches it with `scope` naming one
        // file and `history` set, and each row is `path@sha7` plus the line that tells the saves
        // apart — so nothing the old command answered became unreachable.
        val repo = File(ws.accountDir, "shopping")
        val file = File(repo, "milk.txt")
        file.writeText("one litre\n")
        git.init("shopping").getOrThrow()
        git.commit("shopping", "save 1")
        file.writeText("three litres\n")
        git.commit("shopping", "save 2")

        val answer = tools().run(call("""{"scope":"milk\\.txt$","history":true}"""))

        assertTrue("a version is named: $answer", answer.contains("milk.txt@"))
        assertTrue("the newer text is there: $answer", answer.contains("three litres"))
        assertTrue("and the older: $answer", answer.contains("one litre"))
        // **One row per version, each with the seven-character id `read` takes.**
        val ids = Regex("""milk\.txt@([0-9a-f]{7})""").findAll(answer).map { it.groupValues[1] }.toList()
        assertTrue("at least two versions: $answer", ids.size >= 2)
        assertTrue("all seven characters: $ids", ids.all { it.length == 7 })
    }

    @Test
    fun `a version hit can be read with the revision form read already takes`() {
        // **The whole value of `path@sha7` is that it is not a new syntax.** This asserts the join:
        // the sha a search answers with is the sha `read {revision}` accepts.
        val repo = File(ws.accountDir, "shopping")
        val file = File(repo, "notes.txt")
        file.writeText("first version\n")
        git.init("shopping").getOrThrow()
        git.commit("shopping", "Started")
        file.writeText("second version\n")
        git.commit("shopping", "Changed")

        val answer = tools().run(call("""{"query":"first version","history":true}"""))
        val sha = Regex("""notes\.txt@([0-9a-f]{7})""").find(answer)?.groupValues?.get(1)
        assertTrue("a sha came back: $answer", sha != null)

        // The same sha, given to `read` as a revision, returns the old text.
        val read = tools().run(
            Call(id = "2", tool = "read", args = """{"path":"notes.txt","revision":"$sha"}"""),
        )
        assertTrue("and read takes it: ${read.replace("\n", " | ")}", read.contains("first version"))
    }

    // ---- what it refuses ----------------------------------------------------

    @Test
    fun `a search with no query lists what is in scope`() {
        // **The objection the operator answered.** I argued a listing was a degenerate search; they
        // said *"why searching all is degenerative? you just provide scope and get all of current
        // paths inside of it in return."* They were right: the filters are independent axes, and
        // setting none is setting none — not the absence of a question.
        write("shopping", "milk.txt", "milk\n")
        write("shopping", "src/Main.kt", "fun main() {}\n")
        write("work", "notes.txt", "other\n")

        val answer = tools().run(call("""{"scope":"^shopping/"}"""))

        assertTrue("it is an answer: $answer", answer.startsWith("0\n"))
        assertTrue("the file is listed: $answer", answer.contains("shopping/milk.txt"))
        assertTrue("and the nested one: $answer", answer.contains("shopping/src/Main.kt"))
        // **A folder is listed with its slash**, which is how `tree` said "this is a folder" and
        // what tells a model it may look inside rather than read it.
        assertTrue("and the folder, with a slash: $answer", answer.contains("shopping/src/"))
        assertFalse("and nothing outside the scope: $answer", answer.contains("work/notes.txt"))
        // **No line numbers or excerpts in a listing** — there is no match to point at.
        assertFalse("a listing is paths, not matches: $answer", answer.contains(":1:"))
    }

    @Test
    fun `a listing is not cut at ten, because it is read whole`() {
        // **`paths` means the same thing in both modes, but what a sensible number is depends on the
        // question.** A search answers a handful; a listing is a folder's contents, and stopping it
        // at ten would make `scope` useless for "what is here".
        for (i in 1..15) write("shopping", "note$i.txt", "text $i\n")

        val answer = tools().run(call("""{"scope":"^shopping/"}"""))

        assertEquals("all fifteen", 15, answer.lines().count { it.contains("note") })
        assertFalse("and no note about stopping: $answer", answer.contains("stopped at"))
    }

    @Test
    fun `depth counts folders below the account`() {
        // **`depth` is counted from the account, not from the project**, which is the one thing the
        // merge changed about it: `tree` counted from the project it was opened in, and the merged
        // command has no such starting point — `scope` is a pattern, so there is no root to measure
        // from. The account is the only well-defined origin, and the numbers in this test are the
        // arithmetic of that: the account is level 0, a project is 1.
        write("shopping", "top.txt", "top\n")
        write("shopping", "a/one.txt", "one\n")
        write("shopping", "a/b/two.txt", "two\n")

        // 3 reaches `a/one.txt` (account 0, shopping 1, a 2, the file 3).
        val shallow = tools().run(call("""{"scope":"^shopping/","depth":3}"""))
        assertTrue("one level down is there: $shallow", shallow.contains("shopping/a/one.txt"))
        assertFalse("two levels is not: $shallow", shallow.contains("shopping/a/b/two.txt"))

        val deep = tools().run(call("""{"scope":"^shopping/","depth":5}"""))
        assertTrue("raising depth reaches it: $deep", deep.contains("shopping/a/b/two.txt"))
    }

    @Test
    fun `paths bounds a listing when it is given`() {
        for (i in 1..5) write("shopping", "note$i.txt", "text $i\n")

        val answer = tools().run(call("""{"scope":"note","paths":2}"""))

        // **The scope names the files, so the project folder is not one of the two.** With a scope
        // of `^shopping/` the folder itself occupies a row, which is correct for a listing and would
        // make this test count one file where two were asked for.
        assertEquals("two of them", 2, answer.lines().count { it.contains("note") })
        assertTrue("and it says it stopped: $answer", answer.contains("stopped at 2 paths"))
    }

    // ---- `mode`: what the query means --------------------------------------

    @Test
    fun `mode semantic without a model is refused, and names the setting`() {
        // **No regex fallback.** The operator's rule: a failure is a reason, not a text search
        // nobody asked for — because a hedge makes a model believe it searched by meaning when it
        // searched by wording, and it then acts on an answer to a different question.
        //
        // **And no silent nothing when the app has no engine at all.** A tool set built without one —
        // this test's — must say which setting is missing rather than answer "nothing matches",
        // which would read as an empty project.
        write("shopping", "milk.txt", "milk is bought on Saturdays\n")

        val answer = tools().run(call("""{"query":"dairy","mode":"semantic"}"""))

        assertTrue("it is a refusal: $answer", answer.startsWith("1\n"))
        assertTrue("and names what is missing: $answer", answer.contains("Embedding model"))
        assertFalse("and answers nothing at all: $answer", answer.contains("milk.txt"))
    }

    @Test
    fun `a semantic scope that names another project is refused by name`() {
        // **The operator's boundary, refused rather than narrowed**: *"account wide semantic search is
        // forbidden and scope must be project and lower"*. Answering "nothing matches" about a folder
        // the engine never looked in is the silent no-op this app treats as a defect, so the refusal
        // names the project and the folder that can be searched.
        write("groceries", "list.txt", "milk\n")

        val answer = tools().run(call("""{"scope":"groceries/","query":"milk","mode":"semantic"}"""))

        assertTrue("it is a refusal: $answer", answer.startsWith("1\n"))
        assertTrue("naming the project it refused: $answer", answer.contains("groceries"))
        assertTrue("and the one it can search: $answer", answer.contains("shopping/"))
        assertTrue("and the rule: $answer", answer.contains("per project"))
    }

    @Test
    fun `semantic history is answered by the engine rather than refused here`() {
        // **It used to be a refusal, and the refusal was true.** The index held only the current text
        // of each file, so `history` would have answered a narrower question under a wider name. The
        // index holds saved versions now, keyed by their content, so the flag reaches the engine — and
        // what this tool owes is that it does not stop it on the way.
        //
        // The engine's own behaviour is `SemanticSearchTest`'s: this test's job is the boundary, and
        // the boundary is that a tool set with no engine still refuses with the reason that names the
        // setting, rather than quietly dropping the flag.
        val answer = tools().run(
            call("""{"query":"milk","mode":"semantic","history":true}"""),
        )

        assertTrue("it is a refusal: $answer", answer.startsWith("1\n"))
        assertTrue("and it names the missing setting: $answer", answer.contains("Embedding model"))
        assertFalse("and never says versions are unindexed: $answer", answer.contains("not indexed"))
    }

    @Test
    fun `mode without a query is refused, because there is nothing to interpret`() {
        // **Silently ignoring it is the dangerous option**: a model that asked for semantic and got
        // a listing would believe its question was answered.
        val answer = tools().run(call("""{"scope":"shopping/","mode":"regex"}"""))

        assertTrue("it is a refusal: $answer", answer.startsWith("1\n"))
        assertTrue("and names the missing key: $answer", answer.contains("query"))
    }

    @Test
    fun `a mode that is neither regex nor semantic is refused, naming both`() {
        val answer = tools().run(call("""{"query":"milk","mode":"fuzzy"}"""))

        assertTrue("it is a refusal: $answer", answer.startsWith("1\n"))
        assertTrue("and names the value it cannot take: $answer", answer.contains("fuzzy"))
        assertTrue("and both it can: $answer", answer.contains("regex") && answer.contains("semantic"))
    }

    @Test
    fun `mode regex is accepted and is what the default already does`() {
        write("shopping", "milk.txt", "milk is bought on Saturdays\n")

        val said = tools().run(call("""{"query":"Saturdays","mode":"regex"}"""))
        val implied = tools().run(call("""{"query":"Saturdays"}"""))

        assertEquals("the same answer either way", implied, said)
    }

    @Test
    fun `every key is optional, and the schema says so`() {
        // **A schema that marked all six required would forbid the listing**, which is the mode the
        // operator named — *"you just provide scope and get all of current paths inside of it in
        // return"*. `spec()` defaults `required` to the example's keys, which is right for `read`
        // and wrong here, so this command passes none and this test is what says so.
        val spec = AgentTools.specs().first { it.name == "search" }
        val required = spec.parameters.optJSONArray("required")

        assertEquals("nothing is required", 0, required?.length() ?: -1)
        // **And the call it allows is the one that must work.** `{}` lists the account; if that is
        // not reachable, the default scope is unreachable too.
        val bare = tools().run(call("""{}"""))
        assertTrue("a bare call is a listing, not a refusal: ${bare.take(60)}", bare.startsWith("0\n"))
        assertTrue("and it names the project: $bare", bare.contains("shopping/"))
    }

    @Test
    fun `a broken pattern is a refusal that quotes the mistake`() {
        val answer = tools().run(call("""{"query":"["}"""))

        assertTrue("it is a refusal: $answer", answer.startsWith("1\n"))
        assertTrue("and says the pattern is the problem: $answer", answer.contains("pattern"))
    }

    @Test
    fun `a key the tool does not take is refused before anything is searched`() {
        // **The same rule every tool has**, and it matters more here: `lines` was a key once and
        // silently became "everything". A search that ignored an unknown key would answer about a
        // different question than the one asked.
        val answer = tools().run(call("""{"query":"milk","lines":5}"""))

        assertTrue("it is a refusal: $answer", answer.startsWith("1\n"))
        assertTrue("and names the key: $answer", answer.contains("lines"))
    }

    @Test
    fun `a binary file is not searched, and its name is not a lie`() {
        // **Text only, which is what "all text files are searched" says.** A PNG whose bytes happen
        // to contain the query would be a hit the agent then tries to read as a file.
        val png = File(File(ws.accountDir, "shopping"), "logo.png")
        png.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 'm'.code.toByte()))
        write("shopping", "notes.txt", "milk\n")

        val answer = tools().run(call("""{"query":"milk"}"""))

        assertTrue("the text file is found: $answer", answer.contains("notes.txt"))
        assertFalse("and the picture is not: $answer", answer.contains("logo.png"))
    }
}
