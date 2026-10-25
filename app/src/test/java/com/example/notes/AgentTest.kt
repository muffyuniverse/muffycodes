package com.example.notes

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * One turn, driven through a scripted door.
 *
 * Everything here is about **what the agent did and what the record says it did** — the two
 * things a person debugging a session needs, and the two things a loop can get wrong while
 * looking fine from the outside. No test opens a socket: the door is the seam that keeps the
 * suite free, and the scripted implementation is what makes "two calls in one reply" a case a
 * test can actually produce.
 */
class AgentTest {

    private lateinit var account: File
    private lateinit var ws: Workspace
    private lateinit var git: ProjectGit
    private lateinit var log: AgentLog

    private val author = GitAuthor("Muffy", "muffy@example.com")

    @Before
    fun fresh() {
        account = File(System.getProperty("java.io.tmpdir"), "agent-${System.nanoTime()}")
        ws = Workspace(account)
        ws.ensure()
        val projectDir = File(ws.projectsDir, "shopping")
        ws.repoOf("shopping").mkdirs()
        ws.write(File(ws.repoOf("shopping"), "milk.txt"), "two litres")
        git = ProjectGit(ws, { null }, { author })
        git.init("shopping")
        log = AgentLog.open(projectDir)
    }

    private fun agent(door: Door) = Agent(
        accountDir = account,
        project = "shopping",
        ws = ws,
        git = git,
        log = log,
        door = door,
        model = ActiveModel("deepseek/deepseek-v4.1-flash-1017", "deepseek", "high"),
    )

    private fun call(name: String, args: String, id: String = "c1") = Call(id, name, args)

    private fun rows() = AgentLog.open(File(ws.projectsDir, "shopping")).rows()

    private fun callRows() = rows().filter { it.kind == AgentLog.CALL }

    private fun gotRows() = rows().filter { it.kind == AgentLog.GOT }

    // ---- the request --------------------------------------------------------

    @Test
    fun `the request carries the instructions, the project, the tools and the operator`() {
        // The claim is about the request's *contents*, which is why the scripted door keeps
        // what it was asked: a loop that built a request from nothing would still look right
        // from the outside until the model was charged for it.
        val door = ScriptedDoor(Reply("", listOf(call("conclude", """{"text":"done"}"""))))

        agent(door).burst("rename milk.txt to dairy.txt")

        val request = door.asked.first()
        assertTrue(
            "the instructions",
            request.system.contains("You are working inside a phone app"),
        )
        assertTrue("the project layer", request.system.contains("This project"))
        assertTrue(
            "and the operator's own words",
            request.messages.any { it.role == "user" && it.text == "rename milk.txt to dairy.txt" },
        )
        assertTrue("with the tools", request.tools.any { it.name == "read" })
        assertEquals(
            "on the model in the config",
            "deepseek/deepseek-v4.1-flash-1017",
            request.model,
        )
        assertEquals("and its pinned endpoint", "deepseek", request.provider)
    }

    @Test
    fun `the project's own instructions are in the request and are not overwritten`() {
        val projectDir = File(ws.projectsDir, "shopping")
        val file = File(projectDir, AgentPrompt.PROJECT_FILE)
        file.writeText("Always answer in one sentence.")

        val door = ScriptedDoor(Reply("", listOf(call("conclude", """{"text":"ok"}"""))))
        agent(door).burst("anything")

        assertTrue("what the person wrote", door.asked.first().system.contains("one sentence"))
        assertEquals(
            "and it stays written",
            "Always answer in one sentence.",
            file.readText(),
        )
    }

    // ---- the tools ----------------------------------------------------------

    @Test
    fun `a read is answered and recorded as a call and its result`() {
        val door = ScriptedDoor(
            Reply("", listOf(call("read", """{"path":"GitHub/milk.txt"}"""))),
            Reply("", listOf(call("conclude", """{"text":"read it"}"""))),
        )

        agent(door).burst("what is in milk.txt?")

        val got = gotRows().first()
        assertTrue("it worked: ${got.body}", got.body.startsWith(AgentLog.SUCCESS))
        assertTrue("and the body is the file", got.body.contains("two litres"))
        assertEquals("recorded against the tool", "read", got.name)
    }

    @Test
    fun `a path that leaves the project is refused, and the file is not touched`() {
        // The agent asks to go one folder up, which is where another project's work is. The
        // refusal is a sentence; the thing that must also be true is that nothing happened.
        val outside = File(ws.projectsDir, "groceries/notes.txt")
        outside.parentFile.mkdirs()
        outside.writeText("somebody else's work")

        val door = ScriptedDoor(
            Reply("", listOf(call("read", """{"path":"../groceries/notes.txt"}"""))),
            Reply("", listOf(call("conclude", """{"text":"understood"}"""))),
        )

        agent(door).burst("read the other project")

        val got = gotRows().first()
        assertTrue("refused: ${got.body}", got.body.startsWith(AgentLog.FAILURE))
        assertTrue("and it says why", got.body.contains("up out of the project"))
        assertFalse("the other project's text is not in the record", log.context.readText()
            .contains("somebody else's work"))
    }

    @Test
    fun `a locked file is refused to the agent, and stays as it was`() {
        // Wave 1's promise, seen from the agent's side: the lock is in the funnel, so this
        // refusal is not something the tools were taught.
        val locked = File(ws.repoOf("shopping"), "milk.txt")
        // Locked the way the screen does it: by path, through the funnel that owns the list.
        val path = ws.relative(locked) ?: error("the file is not in the account")
        ws.setLocked(setOf(ws.pathString(path)))

        val door = ScriptedDoor(
            Reply(
                "",
                listOf(
                    call("edit", """{"path":"GitHub/milk.txt","find":"two","replace":"three"}""")
                ),
            ),
            Reply("", listOf(call("conclude", """{"text":"could not"}"""))),
        )

        agent(door).burst("change the milk")

        assertTrue("refused", gotRows().first().body.startsWith(AgentLog.FAILURE))
        assertEquals("and the file is untouched", "two litres", locked.readText())
    }

    @Test
    fun `a write is committed and counted as unsent, and never pushed`() {
        val door = ScriptedDoor(
            Reply(
                "",
                listOf(
                    call("edit", """{"path":"GitHub/milk.txt","find":"two","replace":"three"}""")
                ),
            ),
            Reply("", listOf(call("conclude", """{"text":"done"}"""))),
        )

        agent(door).burst("make it three litres")

        assertEquals("the file", "three litres", File(ws.repoOf("shopping"), "milk.txt").readText())
        assertEquals("a version was written", 1, git.changes("shopping").ahead)
        assertEquals("and it is work GitHub has not seen", 1, ws.unsentCount("shopping"))
    }

    @Test
    fun `a file outside the two writable folders is refused`() {
        // `project_prompt.txt` is the person's, not the agent's: it can read it and cannot
        // rewrite its own instructions.
        val door = ScriptedDoor(
            Reply(
                "",
                listOf(
                    call(
                        "edit",
                        """{"path":"project_prompt.txt","find":"Nothing","replace":"Everything"}""",
                    )
                ),
            ),
            Reply("", listOf(call("conclude", """{"text":"no"}"""))),
        )

        agent(door).burst("rewrite your instructions")

        assertTrue("refused", gotRows().first().body.startsWith(AgentLog.FAILURE))
        assertTrue(
            "and the person's file is as it was",
            File(ws.projectsDir, "shopping/project_prompt.txt").readText()
                .contains("Nothing written here yet"),
        )
    }

    // ---- ending a turn ------------------------------------------------------

    @Test
    fun `ask ends the turn and the question is the seam the answer needs`() {
        // The bug this exists for: with the burst boundary in the way, a bare "yes" in the
        // next turn answers nothing, because the question was never in the request.
        val answer = call("ask", """{"question":"Shall I also fix the README?"}""")
        val door = ScriptedDoor(Reply("", listOf(answer)))
        val first = agent(door).burst("rename the milk file")

        assertTrue("it asked", first is Agent.End.Asked)
        assertEquals(
            "the question is in the record",
            "Shall I also fix the README?",
            (first as Agent.End.Asked).question,
        )

        val second = agent(door)
        second.burst("yes")

        val messages = door.asked.last().messages
        val questionAt = messages.indexOfFirst {
            it.role == "assistant" && it.text.contains("README")
        }
        val yesAt = messages.indexOfFirst { it.role == "user" && it.text == "yes" }
        assertTrue("the question is in the request", questionAt >= 0)
        assertTrue("before the answer, which is what makes it an answer", questionAt < yesAt)
    }

    @Test
    fun `a terminating tool called with others is refused rather than obeyed`() {
        // The endpoints never emit two calls, so a reply that does is a sign the model is
        // improvising — and obeying it would end the turn on a call whose sibling was dropped.
        val door = ScriptedDoor(
            Reply(
                "",
                listOf(
                    call("conclude", """{"text":"done"}""", id="c1"),
                    call("read", """{"path":"GitHub/milk.txt"}""", id="c2"),
                ),
            ),
            Reply("", listOf(call("conclude", """{"text":"now really done"}"""))),
        )

        val end = agent(door).burst("do two things at once")

        assertTrue("the turn went on", end is Agent.End.Concluded)
        // **One sentence, for the call that broke the rule.** The other call in the reply was
        // run and answered, because the model's mistake is not a reason to throw its work away.
        assertEquals("both calls were answered", 2, gotRows().size)
        assertEquals(
            "and only the terminating one was refused",
            1,
            gotRows().count { it.body.contains("terminating tool") },
        )
        assertTrue(
            "the other one really ran",
            gotRows().any { it.body.contains("two litres") },
        )
        assertEquals("and it was told alone the second time", 1, callRows().count {
            it.name == "conclude" && it.detail.contains("really done")
        })
    }

    @Test
    fun `words without a call end the turn instead of looping`() {
        val door = ScriptedDoor(Reply("I have nothing to do."))
        val end = agent(door).burst("hello")

        assertTrue("stopped", end is Agent.End.Stopped)
        assertEquals("and it asked once, not twice", 1, door.asked.size)
        assertTrue("with the reason on the record", rows().any {
            it.name == "stopped" && it.body.contains("no tool call")
        })
    }

    @Test
    fun `a door that fails stops the turn and says so`() {
        val door = object : Door {
            override fun turn(request: Request): Result<Reply> =
                Result.failure(IllegalStateException("OpenRouter answered 429"))
        }

        val end = agent(door).burst("do the thing")

        assertTrue("stopped", end is Agent.End.Stopped)
        assertTrue("and the reason is on the record", rows().any {
            it.name == "stopped" && it.body.contains("429")
        })
    }

    @Test
    fun `calls that change nothing and ask nothing stop the loop`() {
        // The guard is on *unproductive* calls, not on turns: an idle agent waits for ever by
        // design, and a turn limit would stop every healthy session.
        val replies = List(AgentTools.IDLE_CALLS + 2) {
            Reply("", listOf(call("tree", """{"path":"GitHub"}""")))
        }
        val door = ScriptedDoor(*replies.toTypedArray())

        val end = agent(door).burst("look at it for ever")

        assertTrue("stopped", end is Agent.End.Stopped)
        assertEquals(
            "the guard's number of calls, then no request",
            AgentTools.IDLE_CALLS,
            door.asked.size,
        )
        assertTrue("and it says why", rows().any { it.name == "stopped" })
    }

    @Test
    fun `wait ends the turn and is not repeated into the context`() {
        // The evidence keeps every poll; the context keeps one. A context that grew a
        // timestamped row every four minutes would never be the same request twice.
        val door = ScriptedDoor(Reply("", listOf(call("wait", "{}"))))
        val end = agent(door).burst("nothing for now")

        assertTrue("idle", end is Agent.End.Waited)
        assertEquals("one call row", 1, callRows().count { it.name == "wait" })
    }

    // ---- the record as the model reads it -----------------------------------

    @Test
    fun `a rebuilt conversation puts the prose and the calls of one reply together`() {
        // On the wire they were one assistant message; a rebuild that split them would show the
        // model two turns where there was one, and the calls would look unprompted.
        log.told("do it")
        log.note("agent", "I will look first.")
        log.call("tree", """{"path":"GitHub"}""")
        log.got("tree", "0\nGitHub/milk.txt")

        val messages = agent(ScriptedDoor()).messages(log.contextRows())

        val assistant = messages.first { it.role == "assistant" }
        assertTrue("its words", assistant.text.contains("I will look first"))
        assertEquals("and its call, in the same turn", 1, assistant.calls.size)
        assertEquals("the answer comes next", "tool", messages[1 + 1].role.let { messages[2].role })
    }

    @Test
    fun `a tool result is matched to its own call`() {
        log.told("do two")
        log.call("read", """{"path":"GitHub/milk.txt"}""")
        log.call("tree", """{"path":"GitHub"}""")
        log.got("read", "0\nfile contents")
        log.got("tree", "0\nGitHub/milk.txt")

        val messages = agent(ScriptedDoor()).messages(log.contextRows())
        val toolMessages = messages.filter { it.role == "tool" }

        assertEquals("two results", 2, toolMessages.size)
        assertEquals("the first answers the first call", "0\nfile contents", toolMessages[0].text)
        assertEquals(
            "and they do not share an id",
            false,
            toolMessages[0].callId == toolMessages[1].callId,
        )
        // Each result's id is one of the ids the calls were given.
        val ids = messages.filter { it.role == "assistant" }.flatMap { it.calls }.map { it.id }
        assertTrue("both are answered by a call that exists", ids.containsAll(
            toolMessages.map { it.callId }
        ))
    }

    // ---- the record ---------------------------------------------------------

    @Test
    fun `the record holds the operator, the calls, their answers and the ending`() {
        val door = ScriptedDoor(
            Reply("", listOf(call("tree", """{"path":"GitHub"}"""))),
            Reply("", listOf(call("conclude", """{"text":"there is one file"}"""))),
        )

        agent(door).burst("what is in the project?")

        val kinds = rows().map { it.kind }
        assertEquals("the operator's words come first", AgentLog.TOLD, kinds.first())
        assertEquals("and the turn ends with a conclusion", AgentLog.CONCLUDE, kinds.last())
        assertTrue("with a call and its result in between", kinds.contains(AgentLog.CALL))
        assertTrue(kinds.contains(AgentLog.GOT))
    }

    @Test
    fun `every call in the record has an answer after it`() {
        // The property the whole "an id that appears once" query rests on, asserted from the
        // other side: a turn that ended cannot leave a call unanswered.
        val door = ScriptedDoor(
            Reply("", listOf(call("tree", """{"path":"GitHub"}"""))),
            Reply("", listOf(call("read", """{"path":"GitHub/milk.txt"}"""))),
            Reply("", listOf(call("conclude", """{"text":"done"}"""))),
        )

        agent(door).burst("look around")

        assertTrue(
            "nothing dangles",
            AgentLog.open(File(ws.projectsDir, "shopping")).dangling(rows()).isEmpty(),
        )
    }

    @Test
    fun `an argument the tool cannot parse is refused, not swallowed`() {
        val door = ScriptedDoor(
            Reply("", listOf(call("read", "this is not json"))),
            Reply("", listOf(call("conclude", """{"text":"ok"}"""))),
        )

        agent(door).burst("read something")

        val got = gotRows().first()
        assertTrue("refused: ${got.body}", got.body.startsWith(AgentLog.FAILURE))
    }

    @Test
    fun `a reason is written into the record and stays out of the person's files`() {
        val door = ScriptedDoor(
            Reply("", listOf(call("reason", """{"text":"milk.txt is the only file"}"""))),
            Reply("", listOf(call("conclude", """{"text":"noted"}"""))),
        )

        agent(door).burst("think about it")

        assertTrue("in the record", rows().any {
            it.name == "reason" && it.body.contains("only file")
        })
        assertFalse(
            "and nowhere in the project's files",
            File(ws.projectsDir, "shopping").walkTopDown().filter { it.isFile }
                .any { it.name != "session.log" && it.name != "context.log" &&
                    it.readText().contains("only file") },
        )
    }

    @Test
    fun `a tool result is truncated with a one, and says how much it is showing`() {
        // Never silently: a model told "here is the file" and handed half of it will edit the
        // wrong half with complete confidence.
        val big = (1..400).joinToString("\n") { "line $it of the file" }
        ws.write(File(ws.repoOf("shopping"), "big.txt"), big)
        git.commit("shopping", "big")

        val door = ScriptedDoor(
            Reply("", listOf(call("read", """{"path":"GitHub/big.txt"}"""))),
            Reply("", listOf(call("conclude", """{"text":"read what I could"}"""))),
        )

        Agent(
            accountDir = account, project = "shopping", ws = ws, git = git, log = log,
            door = door,
            model = ActiveModel("deepseek/deepseek-v4.1-flash-1017", "deepseek", "high"),
            maxResult = 500,
        ).burst("read the whole thing")

        val got = gotRows().first()
        assertTrue("it says it is not whole", got.body.startsWith(AgentLog.FAILURE))
        assertTrue("and how much: ${got.body.takeLast(80)}", got.body.contains("showing first 500"))
    }

    @Test
    fun `the record's numbers are the tool's arguments, not the app's guesses`() {
        val door = ScriptedDoor(
            Reply("", listOf(call("read", """{"path":"GitHub/milk.txt"}"""))),
            Reply("", listOf(call("conclude", """{"text":"done"}"""))),
        )

        agent(door).burst("look")

        val call = callRows().first()
        assertEquals("the tool", "read", call.name)
        assertEquals("and exactly what it was given", """{"path":"GitHub/milk.txt"}""", call.body)
        assertTrue("as JSON, so a reader can parse it", JSONObject(call.body).has("path"))
    }
}
