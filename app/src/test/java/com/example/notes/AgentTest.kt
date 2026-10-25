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

    private fun model() = ActiveModel("deepseek/deepseek-v4.1-flash-1017", "deepseek", "high")

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
    fun `every call the model reads has an answer`() {
        // **The invariant every endpoint enforces.** A tool call with no tool message after it
        // is a refused request, and the agent would meet the refusal at the start of the next
        // turn with no way back — which is what a terminating `ask` used to leave behind: its
        // call row was written, nothing ever answered it, and the next request carried it.
        // One real call in each burst, because a conversation made only of questions and
        // answers has no calls to check — and an invariant that holds because there is nothing
        // to hold it against is not a test.
        val door = ScriptedDoor(
            Reply("", listOf(call("tree", """{"path":"GitHub"}"""))),
            Reply("", listOf(call("ask", """{"question":"Fix the README too?"}"""))),
            Reply("", listOf(call("read", """{"path":"GitHub/README.md"}"""))),
            Reply("", listOf(call("conclude", """{"text":"Fixed."}"""))),
        )
        val agent = agent(door)
        agent.burst("look at the readme")
        agent.burst("yes")

        val messages = agent.messages(log.contextRows())
        val asked = messages.filter { it.role == "assistant" }.flatMap { it.calls }.map { it.id }
        val answered = messages.filter { it.role == "tool" }.map { it.callId }
        assertTrue("something was called", asked.isNotEmpty())
        assertEquals("and each thing called was answered", asked.toSet(), answered.toSet())
    }

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

        // **The context's last message is a call with a result.** `wait` never runs, so nothing
        // in the loop answers it — and a conversation whose last assistant message calls a tool
        // with no answer is refused by every endpoint. This is what makes an idle agent's next
        // poll a legal request.
        val inContext = log.contextRows().filter { it.name == "wait" }
        assertEquals("the context holds the pair, in order", listOf(AgentLog.CALL, AgentLog.GOT), inContext.map { it.kind })
        assertTrue("and the result says it is still waiting", inContext.last().body.startsWith("0"))
    }

    @Test
    fun `a second poll is in the evidence and not in the context`() {
        // The idle agent polls for ever; the context is asked to stay the same. Without this,
        // every four minutes would add a timestamped row to the thing that is supposed to be
        // byte-identical — which is the whole reason the poll is cheap.
        val door = ScriptedDoor(
            Reply("", listOf(call("wait", "{}"))),
            Reply("", listOf(call("wait", "{}"))),
        )
        val agent = agent(door)
        agent.burst("nothing for now")
        agent.burst("still nothing")

        assertEquals("the evidence keeps both polls", 2, callRows().count { it.name == "wait" })
        assertEquals(
            "the context keeps the first pair only",
            2,
            log.contextRows().count { it.name == "wait" },
        )
    }

    // ---- the agent's own clear-up -------------------------------------------

    private fun tools() = AgentTools(ws, "shopping", git, log)

    /** Writes [text] into the context as the agent's `edit` would, and answers the result. */
    private fun clearUp(text: String): String = tools().run(
        Call(
            "c9",
            "edit",
            JSONObject().put("path", "agent/context.log").put("text", text).toString(),
        )
    )

    /** The context as the agent reads it: the rows' own lines, and nothing else. */
    private fun contextText(): String = log.contextRows().joinToString("\n") { it.line } + "\n"

    @Test
    fun `clearing up the context drops rows from it and leaves them in the evidence`() {
        log.told("task one")
        log.call("tree", "{}")
        log.got("tree", "0\nnothing")
        log.told("task two")
        log.call("read", "{}")
        log.got("read", "0\nhello")

        // Forget the first conversation, keep the task being worked on.
        val rows = log.contextRows()
        val kept = rows.dropWhile { it.kind != AgentLog.TOLD || it.body != "task two" }

        val answer = clearUp(kept.joinToString("\n") { it.line } + "\n")

        assertTrue("it worked: $answer", answer.startsWith(AgentLog.SUCCESS))
        assertEquals("the context kept the task and what followed", 3, log.contextRows().size)
        assertEquals("the evidence kept everything", 6, log.rows().size)
        assertEquals(
            "and the difference is exactly what he forgot",
            3,
            log.rows().size - log.contextRows().size,
        )
    }

    @Test
    fun `the newest task cannot be cleared away`() {
        log.told("task one")
        log.call("tree", "{}")
        log.got("tree", "0\nnothing")
        log.told("task two")

        // Everything except the newest task: a clear-up that forgets what it is doing.
        val answer = clearUp(log.contextRows().filter { it.body != "task two" }
            .joinToString("\n") { it.line } + "\n")

        assertTrue("refused: $answer", answer.startsWith(AgentLog.FAILURE))
        assertTrue("and it says which rule: $answer", answer.contains("newest task"))
    }

    @Test
    fun `a call and its result are cleared away together`() {
        log.told("a task")
        log.call("tree", "{}")
        log.got("tree", "0\nnothing")

        // The call kept and the result dropped: a request whose assistant calls a tool nobody
        // answered, which every endpoint refuses.
        val answer = clearUp(log.contextRows().filter { it.kind != AgentLog.GOT }
            .joinToString("\n") { it.line } + "\n")

        assertTrue("refused: $answer", answer.startsWith(AgentLog.FAILURE))
        assertTrue("and it names the pair: $answer", answer.contains("pair"))
    }

    @Test
    fun `clear-up removes rows and does not rewrite or add them`() {
        log.told("a task")
        val changed = contextText().replace("a task", "a different task")

        val answer = clearUp(changed)

        assertTrue("refused: $answer", answer.startsWith(AgentLog.FAILURE))
        assertTrue("and it says why: $answer", answer.contains("does not change them"))
    }

    @Test
    fun `the evidence is not the agent's to write`() {
        log.told("a task")
        val before = File(log.session.path).readText()

        val answer = tools().run(
            Call(
                "c8",
                "edit",
                JSONObject().put("path", "agent/session.log").put("text", "")
                    .toString(),
            )
        )

        assertTrue("refused: $answer", answer.startsWith(AgentLog.FAILURE))
        assertTrue("and it says what the file is: $answer", answer.contains("evidence"))
        assertEquals("which is untouched", before, File(log.session.path).readText())
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

    // ---- the context is the sources, not a copy of them ----------------------

    @Test
    fun `the instructions are read at the start of a turn, so an edit arrives at the next one`() {
        // **One brief per conversation, and the next turn sees the change.** A layer read per
        // request would answer one turn under two different briefs; a layer cached across turns
        // would never see the edit at all. The door does the editing, because that is the only
        // way to change the file *while* the agent is working.
        val projectDir = File(ws.projectsDir, "shopping")
        val brief = File(projectDir, AgentPrompt.PROJECT_FILE)
        brief.writeText("Answer in one sentence.")

        val door = object : Door {
            val asked = mutableListOf<Request>()
            override fun turn(request: Request): Result<Reply> {
                asked.add(request)
                if (asked.size == 1) brief.writeText("Answer in three sentences.")
                return Result.success(
                    if (asked.size == 1) {
                        Reply("", listOf(Call("c1", "tree", """{"path":"GitHub"}""")))
                    } else {
                        Reply("", listOf(Call("c2", "conclude", """{"text":"done"}""")))
                    },
                )
            }
        }
        Agent(account, "shopping", ws, git, log, door, model()).burst("start")

        assertEquals("two requests", 2, door.asked.size)
        assertTrue("the first was under the brief it began with", door.asked[0].system.contains("one sentence"))
        assertFalse("and not the edit made while it worked", door.asked[0].system.contains("three sentences"))
        assertTrue(
            "the second request of the same turn did not change briefs",
            door.asked[1].system.contains("one sentence"),
        )

        brief.writeText("Answer in three sentences.")
        val next = ScriptedDoor(Reply("", listOf(Call("c3", "conclude", """{"text":"ok"}"""))))
        agent(next).burst("again")
        assertTrue("the next turn has the edit", next.asked.first().system.contains("three sentences"))
    }

    @Test
    fun `the instructions are a file too, and an edit reaches the next turn`() {
        // ① is shipped as a default but it is the account's file from the first turn on, so a
        // person can correct the app's own instructions — and the next turn is answered under
        // the correction. Anything else would make the file a decoration.
        val file = File(account, AgentPrompt.SYSTEM_FILE)
        file.writeText("You are working inside a phone app. Answer in Latin.")
        val first = ScriptedDoor(Reply("", listOf(Call("c1", "conclude", """{"text":"ok"}"""))))
        agent(first).burst("go")
        assertTrue("the edit is what was sent", first.asked.first().system.contains("in Latin"))

        file.writeText("You are working inside a phone app. Answer in Greek.")
        val second = ScriptedDoor(Reply("", listOf(Call("c2", "conclude", """{"text":"ok"}"""))))
        agent(second).burst("go again")

        assertTrue("and the next edit is too", second.asked.first().system.contains("in Greek"))
        assertFalse("with the old one gone", second.asked.first().system.contains("in Latin"))
    }

    @Test
    fun `the trace says which model and endpoint answered the turn`() {
        val before = Trace.report().lines.toSet()
        val door = ScriptedDoor(Reply("", listOf(call("conclude", """{"text":"done"}"""))))

        agent(door).burst("go")

        val added = Trace.report().lines.filterNot { it in before }
        assertEquals(
            "one line, naming both: $added",
            1,
            added.count {
                it.contains("agent-request") && it.contains("deepseek/deepseek-v4.1-flash-1017") &&
                    it.contains("deepseek")
            },
        )
    }

    @Test
    fun `an edit to the project layer leaves the instructions above it as they were`() {
        // The cache is a prefix match. ① first and ② after it is what makes editing the project's
        // brief cost a re-read of the brief rather than of everything the app was told.
        val dir = File(ws.projectsDir, "shopping")
        val brief = File(dir, AgentPrompt.PROJECT_FILE)
        brief.writeText("Old brief.")
        val before = agent(ScriptedDoor()).request().system

        brief.writeText("A new brief, of a different length.")
        val after = agent(ScriptedDoor()).request().system

        val head = before.substringBefore(AgentPrompt.SEPARATOR)
        assertEquals("the instructions are byte-identical", head, after.substringBefore(AgentPrompt.SEPARATOR))
        assertTrue("the old brief is gone", !after.contains("Old brief."))
        assertTrue("and the new one is there", after.contains("A new brief"))
    }

    @Test
    fun `the working context is read from its file, not remembered`() {
        // A row put there by somebody else — the agent's own edit tool, or a person with a shell.
        // If the loop kept a transcript in memory this would be the request that never noticed.
        val dir = File(ws.projectsDir, "shopping")
        log.told("first")
        val door = ScriptedDoor(Reply("", listOf(Call("c1", "conclude", """{"text":"done"}"""))))
        val agent = agent(door)

        File(dir, "${AgentLog.DIR}/${AgentLog.CONTEXT}").writeText("")
        agent.burst("second")

        val sent = door.asked.first().messages.map { it.text }
        assertEquals("the context on disk, and the operator's own words", listOf("second"), sent)
    }

    @Test
    fun `a reset leaves the next request with the new task and nothing before it`() {
        val door = ScriptedDoor(
            Reply("", listOf(call("tree", """{"path":"GitHub"}"""))),
            Reply("", listOf(call("conclude", """{"text":"done"}"""))),
            Reply("", listOf(Call("c3", "conclude", """{"text":"done again"}"""))),
        )
        val agent = agent(door)
        agent.burst("look at the folder")
        val before = log.contextRows().size

        log.reset()
        agent.burst("now something else")

        assertEquals("the working context was emptied", 1, log.contextRows().count { it.kind == AgentLog.CALL })
        assertTrue("and it is the new turn's", before > log.contextRows().size - 1)
        val third = door.asked.last().messages
        assertEquals(
            "the third request is the new task alone",
            listOf("now something else"),
            third.map { it.text },
        )
        assertTrue(
            "with nothing of the old conversation in it",
            third.none { it.text.contains("GitHub") },
        )
        assertTrue(
            "while the evidence still has all of it",
            log.rows().any { it.kind == AgentLog.CALL && it.name == "tree" },
        )
    }

    @Test
    fun `an answer with no call above it is not sent`() {
        // What a reset can leave behind: the evidence answers a call the context no longer has.
        // A tool message the model cannot place is a request every endpoint refuses.
        log.told("do it")
        log.sessionOnly(AgentLog.GOT, "tree", "0\nleft over from before the reset")
        log.note("agent", "Carrying on.")

        val messages = agent(ScriptedDoor()).messages(log.contextRows())

        assertTrue("no tool message", messages.none { it.role == "tool" })
        assertFalse(
            "and no trace of it: ${messages.map { it.text }}",
            messages.any { it.text.contains("left over") },
        )
    }

    @Test
    fun `every way a turn ends is on the trace, and once`() {
        // Four endings, four lines — written where the ending is decided rather than five times
        // over in five branches.
        //
        // **Compared as a set, not by counting from an index.** The trace is one fixed-size ring
        // shared by every test in this JVM, so "the lines after position N" is a window that
        // shifts when an older line is evicted — which is exactly how this test first failed,
        // reading another test's lines and none of its own.
        val endings = listOf(
            Triple("asked", listOf(Reply("", listOf(call("ask", """{"question":"Which?"}""")))), "asked:"),
            Triple("concluded", listOf(Reply("", listOf(call("conclude", """{"text":"done"}""")))), "concluded:"),
            Triple("waited", listOf(Reply("", listOf(call("wait", "{}")))), "idle:"),
            Triple("stopped", listOf(Reply("just some words", emptyList())), "stopped:"),
        )
        for ((name, replies, word) in endings) {
            val before = Trace.report().lines.toSet()
            agent(ScriptedDoor(*replies.toTypedArray())).burst("go")
            val added = Trace.report().lines.filterNot { it in before }
            assertEquals(
                "$name is on the trace exactly once: $added",
                1,
                added.count { it.contains(word) },
            )
        }
    }

    @Test
    fun `a turn that never ends stops at the request budget`() {
        // **The backstop, not a policy.** The idle guard is the policy — calls that change
        // nothing — and this is the one thing above it: a model that changes something every time
        // and never ends the turn cannot hold the app's only worker for ever. Reaching it here
        // costs three requests because the budget is a parameter; the constant is still forty.
        var n = 0
        val door = object : Door {
            override fun turn(request: Request): Result<Reply> = Result.success(
                Reply(
                    "",
                    // A file each time, so the idle guard never fires: each call really changes
                    // the project, and the turn still does not end.
                    listOf(Call("c${++n}", "create", """{"path":"GitHub/f$n.txt","text":"$n"}""")),
                ),
            )
        }

        val end = Agent(account, "shopping", ws, git, log, door, model(), maxRequests = 3)
            .burst("go on then")

        assertTrue("it stopped: $end", end is Agent.End.Stopped)
        assertEquals("because of the budget", AgentSession.StopReason.LOOP, (end as Agent.End.Stopped).reason)
        assertEquals("after exactly three requests", 3, n)
        assertEquals("and the record says so", true, rows().any { it.body.contains("3 requests") })
        assertTrue(
            "and the box is told by the one sentence the ending has",
            end.plainly().contains("3 requests"),
        )
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
