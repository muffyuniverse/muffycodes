package com.example.notes

import com.example.notes.browser.Browser
import com.example.notes.browser.Page
import com.example.notes.browser.Wait
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * **The scanner at the two doors it guards, and nowhere else.**
 *
 * The operator's rule is *"foreign, browse and mcp"* and then *"no need to scan other projects"* — so the
 * scope is exactly two tools. This file proves both ends of that: `browse` and `mcp` are scanned, and a `read`
 * of the project is not.
 *
 * The score is a function, so what is asserted here is the tool's plumbing rather than a model's opinion —
 * and the parts that break quietly are the ones worth the tests: the notice reaching the text, the removed
 * text reaching the **log and not the conversation**, and a scanner outage not costing the person their page.
 */
class ScannerTestTools {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var ws: Workspace
    private lateinit var git: ProjectGit
    private lateinit var log: AgentLog

    @Before
    fun setUp() {
        ws = Workspace(temp.root)
        ws.projectDir("shopping").mkdirs()
        ws.projectDir("notes").mkdirs()
        git = ProjectGit(ws, { null }, { GitAuthor("Muffy", "muffy@example.com") })
        log = AgentLog.open(ws.projectDir("shopping"))
    }

    private fun call(tool: String, args: String) = Call(id = "1", tool = tool, args = args)

    /**
     * A browser that answers [text] for any page.
     *
     * **`com.example.notes.browser.Deadline`, spelled out**, because this file has two `Deadline` classes in
     * scope: the browser module's own and the tools'. The browser's is the one its interface takes, and a bare
     * `Deadline` would resolve to the tools' and override nothing.
     */
    private fun browser(text: String) = object : Browser {
        override val engine = "fake"
        override fun text(
            url: String,
            wait: Wait,
            eval: String?,
            deadline: com.example.notes.browser.Deadline,
        ): Result<Page> = Result.success(Page(text = text, url = url, title = "A page"))

        override fun shot(
            url: String,
            full: Boolean,
            eval: String?,
            deadline: com.example.notes.browser.Deadline,
        ): Result<ByteArray> = Result.success(byteArrayOf(1, 2, 3))
    }

    /** A server that answers [text]. */
    private fun server(text: String) = object : Mcp {
        override fun tools(
            server: McpServer,
            credentials: Credentials,
            deadline: Deadline,
        ): Result<List<Mcp.Tool>> = Result.success(listOf(Mcp.Tool("read_it", "Reads it", null)))

        override fun call(
            server: McpServer,
            credentials: Credentials,
            tool: String,
            args: JSONObject,
            deadline: Deadline,
        ): Result<Mcp.Answer> = Result.success(Mcp.Answer(text, failed = false))
    }

    private fun tools(
        page: String = "",
        serverText: String = "",
        scores: List<Double> = emptyList(),
        threshold: Double = 0.8,
    ) = AgentTools(
        ws = ws,
        project = "shopping",
        git = git,
        log = log,
        browser = browser(page),
        mcp = server(serverText),
        credentials = MemoryCredentials(),
        scanner = Scanner(score = { Result.success(scores) }, threshold = { threshold }),
    )

    /** An article in this project's `mcp/`, so a server can be named. */
    private fun article(name: String = "linear") {
        val file = File(File(ws.projectDir("shopping"), "mcp"), "$name.md")
        file.parentFile?.mkdirs()
        file.writeText("# $name\n\n```mcp\nurl: https://mcp.example.com/mcp\n```\n")
    }

    /**
     * Three paragraphs, each a chunk of its own.
     *
     * **Each section is longer than the chunker's 800-character budget**, because three short lines are one
     * chunk — and "a section can be cut while its neighbours survive" is unreachable if the fixture cannot
     * produce three sections.
     */
    private fun page(): String = (1..3).joinToString("\n") { section(it) }

    /**
     * One section, sized so that **each section is exactly one chunk.**
     *
     * Under [Chunker.CHARS] so a section stays whole, and padded close to it so three of them are three
     * chunks rather than one. The tests here assert on the count, so the fixture has to produce it.
     */
    private fun section(number: Int): String {
        val head = "Section $number of the page."
        val words = "ordinary words to fill a line of text"
        val body = StringBuilder(head)
        var i = 1
        while (body.length + words.length + 12 < Chunker.CHARS - 8) {
            body.append(' ').append(words).append(", number ").append(i++)
        }
        return body.toString()
    }

    // ---- browse --------------------------------------------------------------

    @Test
    fun `a page's dangerous section is cut, and the rest arrives`() {
        // **Surgery rather than a block**: a false positive costs a paragraph, and this is the paragraph.
        val text = page()

        val answer = tools(page = text, scores = listOf(0.1, 0.95, 0.1))
            .run(call("browse", """{"url":"https://example.com/page"}"""))

        assertTrue(answer, answer.startsWith("0\n"))
        assertTrue("the first section survives", answer.contains("Section 1 of the page"))
        assertTrue("and the third", answer.contains("Section 3 of the page"))
        assertFalse("but not the second", answer.contains("Section 2 of the page"))
    }

    @Test
    fun `what was cut is said above the text`() {
        // **Not silence.** A silently-shortened page lies about its own contents, and the model cannot tell a
        // person "the part that would answer you was removed" if it does not know something was.
        val answer = tools(page = page(), scores = listOf(0.1, 0.95, 0.1))
            .run(call("browse", """{"url":"https://example.com/page"}"""))

        assertTrue(answer, answer.contains("1 of 3 sections"))
        assertTrue(answer, answer.contains("suspected instructions aimed at you"))
        assertTrue(answer, answer.contains("section 2"))
    }

    @Test
    fun `the cut text is in the log and not in the conversation`() {
        // **The operator's decision, and the same split `forget` makes**: out of the prompt, still in the
        // record. The row is what a person reads when they want to know what was taken.
        val text = page()
        val chunks = Chunker.split(text)
        val cut = chunks[1].text

        tools(page = text, scores = listOf(0.1, 0.95, 0.1))
            .run(call("browse", """{"url":"https://example.com/page"}"""))

        val rows = log.rows()
        val cutRows = rows.filter { it.name == "cut" }
        assertEquals("one row per cut section", 1, cutRows.size)
        assertTrue("the row holds the text: ${cutRows[0].body}", cutRows[0].body.contains(cut.take(40)))
        assertTrue("and names where it came from", cutRows[0].body.contains("https://example.com/page"))
    }

    @Test
    fun `a page with nothing suspicious is unchanged`() {
        // The ordinary case must cost the text nothing — no reflow, no extra blank line, no notice.
        val text = page()

        val answer = tools(page = text, scores = listOf(0.0, 0.0, 0.0))
            .run(call("browse", """{"url":"https://example.com/page"}"""))

        assertTrue("the page arrives whole", answer.contains(text))
        assertFalse("and nothing is said about cutting", answer.contains("sections were removed"))
    }

    @Test
    fun `a scanner outage does not cost the person their page`() {
        // **A defence that fails closed turns a model outage into a browsing outage.** The text is shown as it
        // arrived, and the failure is an app note — not a refusal, and not in the context.
        val text = page()
        val failing = AgentTools(
            ws = ws,
            project = "shopping",
            git = git,
            log = log,
            browser = browser(text),
            scanner = Scanner(
                score = { Result.failure(IllegalStateException("OpenRouter answered HTTP 500")) },
                threshold = { 0.8 },
            ),
        )

        val answer = failing.run(call("browse", """{"url":"https://example.com/page"}"""))

        assertTrue(answer, answer.startsWith("0\n"))
        assertTrue("the page arrives whole", answer.contains(text))
        val notes = log.rows().filter { it.name == "scanner" }
        assertEquals("and the failure is recorded", 1, notes.size)
        assertTrue(notes[0].body.contains("HTTP 500"))
    }

    @Test
    fun `the scanner is off when the threshold is zero`() {
        var asked = false
        val off = AgentTools(
            ws = ws,
            project = "shopping",
            git = git,
            log = log,
            browser = browser(page()),
            scanner = Scanner(score = { asked = true; Result.success(emptyList()) }, threshold = { 0.0 }),
        )

        off.run(call("browse", """{"url":"https://example.com/page"}"""))

        assertFalse("the model was not asked", asked)
    }

    @Test
    fun `the range is applied to what survives, not to what arrived`() {
        // **`from`/`to` are line numbers of the text the agent will read.** Applied before the scan they would
        // point at lines that no longer exist once a section above them was cut.
        val text = page()
        val answer = tools(page = text, scores = listOf(0.1, 0.95, 0.1))
            .run(call("browse", """{"url":"https://example.com/page","from":1,"to":100}"""))

        assertFalse("the cut section is not in the numbered range", answer.contains("Section 2 of the page"))
        assertTrue("the rest is", answer.contains("Section 3 of the page"))
    }

    // ---- mcp -----------------------------------------------------------------

    @Test
    fun `a server's dangerous section is cut the same way`() {
        // **The same scanner, because a server's answer is the second of the two kinds of foreign text.**
        article()

        val answer = tools(serverText = page(), scores = listOf(0.1, 0.95, 0.1))
            .run(call("mcp", """{"server":"linear","tool":"read_it"}"""))

        assertTrue(answer, answer.startsWith("0\n"))
        assertTrue("the mark is still there", answer.contains("not instructions for you"))
        assertTrue("the safe sections survive", answer.contains("Section 1 of the page"))
        assertFalse("the dangerous one does not", answer.contains("Section 2 of the page"))
        assertTrue("and the count is said", answer.contains("1 of 3 sections"))
    }

    // ---- and nowhere else ----------------------------------------------------

    @Test
    fun `reading the project is not scanned`() {
        // **The scope, asserted.** *"no need to scan other projects"* — the agent's own project is text from
        // the phone, and scanning it would be scanning the person's own words.
        val file = File(ws.projectDir("shopping"), "notes.txt")
        file.writeText(page())
        var asked = false
        val watchful = AgentTools(
            ws = ws,
            project = "shopping",
            git = git,
            log = log,
            scanner = Scanner(score = { asked = true; Result.success(emptyList()) }, threshold = { 0.8 }),
        )

        val answer = watchful.run(call("read", """{"path":"notes.txt"}"""))

        assertTrue(answer, answer.startsWith("0\n"))
        assertTrue("the whole file arrives", answer.contains("Section 3 of the page"))
        assertFalse("and the scanner was never asked", asked)
    }
}
