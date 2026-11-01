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
 * `search` — finding a fact by what a file says, across the whole account.
 *
 * **The tool exists because the agent does not know where a fact lives.** `tree` answers about one
 * folder; `search` answers about a question. The operator's rule is what shapes it: *"all text files
 * are embedded and searched the same way with folders as search filter flags"* — so the difference
 * from `tree` is defaults and filters, not scope.
 *
 * Three things are worth testing and they are the three that break quietly:
 *
 * 1. **It crosses projects**, because a search that silently stayed in one project would answer
 *    "nothing" about a question it never asked.
 * 2. **`in` is a prefix on the path**, so `wiki/` means the wiki of *every* project and
 *    `groceries/wiki/` means one — and neither is a "scope".
 * 3. **`paths` counts files, not matches**, because a file matching forty times is one thing to read.
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

    // ---- `in` is a prefix, not a scope --------------------------------------

    @Test
    fun `a folder filter with one segment means that folder in every project`() {
        // **This is what "folders as filter flags" buys.** `wiki/` is not a mode called "wiki"; it is
        // a prefix, so it matches the wiki of every project at once. The alternative — a scope per
        // folder — is a schema the operator did not ask for.
        write("shopping", "wiki/milk.md", "milk is bought on Saturdays\n")
        write("work", "wiki/retries.md", "milk is not involved here\n")
        write("shopping", "notes.txt", "milk, twice\n")

        val answer = tools().run(call("""{"query":"milk","in":["wiki/"]}"""))

        val said = answer.replace("\n", " | ")
        assertTrue("both wikis are searched: $said", answer.contains("shopping/wiki/milk.md"))
        assertTrue("including the other project's: $said", answer.contains("work/wiki/retries.md"))
        assertFalse("and the loose note is not: $said", answer.contains("shopping/notes.txt"))
    }

    @Test
    fun `a filter with the project in it means one project`() {
        write("shopping", "wiki/milk.md", "milk is bought on Saturdays\n")
        write("work", "wiki/retries.md", "milk is not involved here\n")

        val answer = tools().run(call("""{"query":"milk","in":["shopping/wiki/"]}"""))

        assertTrue("the one asked for: $answer", answer.contains("shopping/wiki/milk.md"))
        assertFalse("and only that one: $answer", answer.contains("work/wiki/retries.md"))
    }

    @Test
    fun `a filter that matches nothing says so about the filter`() {
        // **The sentence has to name what was searched.** "nothing matches" without the filter reads
        // as "the account has no such text", and the agent then stops looking everywhere.
        write("shopping", "notes.txt", "milk\n")

        val answer = tools().run(call("""{"query":"milk","in":["nowhere/"]}"""))

        assertTrue("it is not a failure: $answer", answer.startsWith("0\n"))
        assertTrue("and names the folder: $answer", answer.contains("nowhere/"))
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
        assertTrue("the many file is named once: $said", answer.split("many.txt").size == 2)
        // **`(+39 more)`, with the plus.** The count is how many *further* matches there are, so the
        // sign is part of the sentence rather than decoration — and writing the assertion without it
        // is how this failed once while the answer was right in front of it.
        assertTrue("and says there is more in it: $said", answer.contains("(+39 more)"))
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
    fun `a walk that ends exactly at the limit does not promise more`() {
        // **"stopped at N" is only true when the walk stopped at N.** If the account holds exactly
        // `paths` matching files, there is nothing more, and promising a remainder sends the agent
        // looking for something that does not exist.
        for (i in 1..2) write("shopping", "note$i.txt", "milk $i\n")

        val answer = tools().run(call("""{"query":"milk","paths":2}"""))

        assertFalse("no false promise: $answer", answer.contains("stopped at"))
        assertEquals("both are there", 2, answer.lines().count { it.contains("note") })
    }

    // ---- history ------------------------------------------------------------

    @Test
    fun `history finds a version whose text is no longer in the file`() {
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
    fun `a search with no query is refused, and points at tree`() {
        // A search with no pattern is a listing, and `tree` is the tool for that. Answering with
        // every file in the account would be the most expensive way to say nothing.
        val answer = tools().run(call("""{"in":["wiki/"]}"""))

        assertTrue("it is a refusal: $answer", answer.startsWith("1\n"))
        assertTrue("and names the tool that does this: $answer", answer.contains("tree"))
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
