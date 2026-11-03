package com.example.notes

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * **The `subagent` tool, and the `SUB` row its report arrives in.**
 *
 * The tool's whole promise is that it **returns before the work is done** — the model asked for the
 * work to start, not for its result — so the interesting tests are about what it says and what it
 * refuses, not about a subagent running (that is [AgentSubagentsTest] and, later, the runner).
 */
class SubagentToolTest {

    private lateinit var root: File
    private lateinit var ws: Workspace
    private lateinit var git: ProjectGit
    private lateinit var log: AgentLog
    private var limit = 3

    private val author = GitAuthor("Test", "test@example.com")

    @Before
    fun makeSpace() {
        root = File(System.getProperty("java.io.tmpdir"), "subtool-${System.nanoTime()}")
        ws = Workspace(File(root, "muffyuniverse")).apply { accountDir.mkdirs() }
        ws.projectDir("groceries").mkdirs()
        git = ProjectGit(ws, { null }, { author })
        git.init("groceries").getOrThrow()
        log = AgentLog.open(ws.projectDir("groceries"))
    }

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun tools() = AgentTools(
        ws = ws,
        project = "groceries",
        git = git,
        log = log,
        subagentLimit = { limit },
    )

    private fun call(args: String) =
        tools().run(Call(id = "1", tool = "subagent", args = args), Deadline.none())

    // ---- start --------------------------------------------------------------

    @Test
    fun `starting a subagent answers at once, with its id`() {
        val answer = call("""{"do":"start","task":"count the tests"}""")

        assertTrue("it worked: $answer", answer.startsWith("0"))
        assertTrue("and the id is in the answer: $answer", answer.contains("#1"))
        assertTrue(
            "**and it says not to wait** — a model told only \"started\" would poll or stall, and " +
                "the report arrives on its own: $answer",
            answer.contains("Do not wait"),
        )
        assertEquals(
            "the id is real: the registry has it",
            "count the tests",
            AgentSubagents(ws.accountDir).find("groceries", 1)?.task,
        )
    }

    @Test
    fun `a subagent without a task is refused, because there is nothing to start it on`() {
        val answer = call("""{"do":"start"}""")
        assertTrue("refused: $answer", answer.startsWith("1"))
        assertTrue("and says what is missing: $answer", answer.contains("task"))
        assertEquals("and nothing was minted", 0, AgentSubagents(ws.accountDir).read("groceries").size)
    }

    @Test
    fun `the ceiling is a promise, so starting past it is refused with a sentence`() {
        // **The operator's number is a ceiling on live subagents**, and a refusal that names the
        // number is what lets a model understand why — a silent drop would read as success and the
        // work would never happen.
        limit = 2
        call("""{"do":"start","task":"one"}""")
        call("""{"do":"start","task":"two"}""")

        val answer = call("""{"do":"start","task":"three"}""")

        assertTrue("refused: $answer", answer.startsWith("1"))
        assertTrue("naming the number: $answer", answer.contains("2 subagents"))
        assertEquals("and nothing was minted", 2, AgentSubagents(ws.accountDir).read("groceries").size)
    }

    @Test
    fun `a retired subagent frees its place in the count`() {
        limit = 1
        call("""{"do":"start","task":"one"}""")
        AgentSubagents(ws.accountDir).fitTo("groceries", limit = 0, busy = emptySet())

        val answer = call("""{"do":"start","task":"two"}""")

        assertTrue("the place is free again: $answer", answer.startsWith("0"))
        assertTrue("and the new id is 2, not 1: $answer", answer.contains("#2"))
    }

    // ---- write --------------------------------------------------------------

    @Test
    fun `writing to a live subagent sends it the task`() {
        call("""{"do":"start","task":"one"}""")
        val answer = call("""{"do":"write","id":1,"task":"now do this instead"}""")

        assertTrue("it worked: $answer", answer.startsWith("0"))
        assertTrue("and names the id: $answer", answer.contains("#1"))
    }

    @Test
    fun `writing to a retired subagent is refused, and the refusal names its folder`() {
        // **The operator's rule, in words**: *"if chat agent tries to write to an agent (by id) who
        // has been removed then he gets exception"*. The "exception" is a refusal the model can act
        // on — a Kotlin exception would end the turn with a sentence nobody wrote.
        //
        // The folder is in the sentence because the record is the only account of what that subagent
        // did, and a person looking for it needs somewhere to look.
        call("""{"do":"start","task":"one"}""")
        AgentSubagents(ws.accountDir).fitTo("groceries", limit = 0, busy = emptySet())

        val answer = call("""{"do":"write","id":1,"task":"carry on"}""")

        assertTrue("refused: $answer", answer.startsWith("1"))
        assertTrue("saying it was retired: $answer", answer.contains("retired"))
        assertTrue("and naming the record: $answer", answer.contains("agent/1"))
        assertTrue("and how to get another: $answer", answer.contains("Start a new one"))
    }

    @Test
    fun `writing to an id that never existed says so, without calling it retired`() {
        // Two different facts, two different sentences. A model told "retired" about #7 would think
        // a subagent it never started had been taken away.
        val answer = call("""{"do":"write","id":7,"task":"x"}""")

        assertTrue("refused: $answer", answer.startsWith("1"))
        assertTrue("no such subagent: $answer", answer.contains("No subagent #7"))
        assertFalse("and not described as retired: $answer", answer.contains("retired"))
    }

    @Test
    fun `write without an id is refused`() {
        call("""{"do":"start","task":"one"}""")
        val answer = call("""{"do":"write","task":"x"}""")
        assertTrue("refused: $answer", answer.startsWith("1"))
        assertTrue("and says what is missing: $answer", answer.contains("id"))
    }

    // ---- list ---------------------------------------------------------------

    @Test
    fun `list says which subagents exist and what each was last given`() {
        call("""{"do":"start","task":"count the tests"}""")
        call("""{"do":"start","task":"read the log"}""")

        val answer = call("""{"do":"list"}""")

        assertTrue("it worked: $answer", answer.startsWith("0"))
        assertTrue("with both ids: $answer", answer.contains("#1") && answer.contains("#2"))
        assertTrue("and their tasks: $answer", answer.contains("count the tests"))
        assertTrue("and their state: $answer", answer.contains("live"))
    }

    @Test
    fun `list in a project with no subagents says how to make one`() {
        val answer = call("""{"do":"list"}""")
        assertTrue("it worked: $answer", answer.startsWith("0"))
        assertTrue("and says nothing exists: $answer", answer.contains("no subagents"))
        assertTrue("and how to start one: $answer", answer.contains("subagent"))
    }

    @Test
    fun `a retired subagent is listed as retired, because its record still exists`() {
        call("""{"do":"start","task":"one"}""")
        AgentSubagents(ws.accountDir).fitTo("groceries", limit = 0, busy = emptySet())

        val answer = call("""{"do":"list"}""")

        assertTrue("still listed: $answer", answer.contains("#1"))
        assertTrue("as retired: $answer", answer.contains("retired"))
    }

    @Test
    fun `list says working, asleep and live apart, because live answered none of them`() {
        // **The question a model actually asks.** A blank task means "nothing outstanding" for a
        // subagent mid-turn and for one that finished an hour ago alike, so `live` was being said
        // about three different situations — and one of them refuses the next task.
        val registry = AgentSubagents(ws.accountDir)
        val live = registry.mint("groceries", "waiting for work")
        registry.touchTask("groceries", live.id, "")
        val working = registry.mint("groceries", "mid-turn")
        registry.touchTask("groceries", working.id, "")
        registry.busy("groceries", working.id, working = true)
        val asleep = registry.mint("groceries", "reached its ceiling")
        registry.touchTask("groceries", asleep.id, "")
        registry.sleep("groceries", asleep.id, asleep = true)

        val said = call("""{"do":"list"}""")

        assertTrue("the waiting one is live: $said", said.contains("#${live.id} · live"))
        assertTrue(
            "**the running one says working** — a second task would be refused: $said",
            said.contains("#${working.id} · working"),
        )
        assertTrue(
            "**the sleeping one says asleep** — a task wakes it: $said",
            said.contains("#${asleep.id} · asleep"),
        )
    }

    @Test
    fun `list says how many of how many allowed are in use`() {
        // "May I start another" is the question that follows "what exists", and the answer is a
        // number the model cannot see anywhere else.
        limit = 3
        val registry = AgentSubagents(ws.accountDir)
        registry.mint("groceries", "one")
        registry.mint("groceries", "two")

        val said = call("""{"do":"list"}""")

        // **The body, not the status.** `success` prefixes `0\n`, so "the first line" is the first
        // line of what the model reads, which is the second line of the raw answer.
        assertTrue("the count is in the first line: $said", said.startsWith("0\n2 of 3 allowed"))
    }

    @Test
    fun `a retired subagent does not count against the ceiling in the list`() {
        // It kept its place in the file, so counting it would tell the model it is full when it is
        // not — and a model that believes it is full stops asking.
        limit = 1
        val registry = AgentSubagents(ws.accountDir)
        registry.mint("groceries", "the one that was retired")
        registry.fitTo("groceries", limit = 0, busy = emptySet())

        val said = call("""{"do":"list"}""")

        assertTrue("nothing live of one allowed: $said", said.startsWith("0\n0 of 1 allowed"))
    }

    @Test
    fun `an unknown do is refused with the three that exist`() {
        val answer = call("""{"do":"explode"}""")
        assertTrue("refused: $answer", answer.startsWith("1"))
        assertTrue("naming them: $answer", answer.contains("start") && answer.contains("write") && answer.contains("list"))
    }

    // ---- the report row -----------------------------------------------------

    @Test
    fun `a report reaches the chat agent as its own row, not as a tool result`() {
        // **The delivery path.** A tool result is a call the model is waiting on; a subagent is
        // another agent with its own clock. So the report is a `SUB` row in the chat agent's log,
        // and it arrives as a message that is not the person's and not the model's own.
        log.report(from = 2, body = "0\ncounted 41 tests")

        val rows = log.rows()
        val sub = rows.last()
        assertEquals("it is a SUB row", AgentLog.SUB, sub.kind)
        assertEquals("named by the subagent's id", "#2", sub.name)
        assertTrue("with the body", sub.body.contains("41 tests"))

        val messages = Agent(
            accountDir = ws.accountDir,
            project = "groceries",
            ws = ws,
            git = git,
            log = log,
            door = ScriptedDoor(),
            model = ActiveModel("m", "p", "high"),
        ).messages(log.contextRows())

        val said = messages.last()
        assertEquals("**a user message**, the only role every endpoint allows mid-array", "user", said.role)
        assertTrue(
            "**and it says what it is**, so a model cannot mistake it for the person: ${said.text}",
            said.text.contains("subagent's report"),
        )
        assertTrue("naming which one: ${said.text}", said.text.contains("#2"))
    }

    @Test
    fun `a report is not merged into the assistant's own paragraph`() {
        // The falsification for the row kind: if `SUB` fell through to the `NOTE` branch that
        // handles `reason`, the chat agent would read a subagent's words as something it had
        // written itself — and would then be answering its own report.
        log.note("agent", "I will look at the folder.")
        log.report(from = 1, body = "0\nthere are 12 files")

        val messages = Agent(
            accountDir = ws.accountDir,
            project = "groceries",
            ws = ws,
            git = git,
            log = log,
            door = ScriptedDoor(),
            model = ActiveModel("m", "p", "high"),
        ).messages(log.contextRows())

        val assistant = messages.filter { it.role == "assistant" }
        assertTrue(
            "the report is not in the assistant's paragraph: $assistant",
            assistant.none { it.text.contains("12 files") },
        )
    }

    @Test
    fun `a big report says its own size in its first line`() {
        // **A `SUB` row does not pass through `forConversation`**, which is the one place the result
        // limit applies — so a report can be bigger than any row the app has written, and one bigger
        // than the model's window fails at the endpoint rather than in the app. The clear-up line
        // already names the biggest rows by id; this is the part that turns "the endpoint refused"
        // into "the app told the model what it was carrying".
        log.report(from = 3, body = "0\n" + "x".repeat(200), big = 40_000)

        val body = log.rows().last().body
        assertTrue(
            "the size is named: $body",
            Regex("""\[this report is about [0-9.]+k? tokens]""").containsMatchIn(body),
        )
        assertTrue("before the report itself", body.indexOf("tokens]") < body.indexOf("xxxx"))
    }

    @Test
    fun `a small report says nothing about its size, because the number would be noise`() {
        log.report(from = 1, body = "0\ndone")
        val body = log.rows().last().body
        assertFalse("no size line: $body", body.contains("tokens]"))
    }
}
