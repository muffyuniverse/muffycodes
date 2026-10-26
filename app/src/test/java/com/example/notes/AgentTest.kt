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
        val projectDir = File(ws.accountDir, "shopping")
        ws.repoOf("shopping").mkdirs()
        ws.write(File(ws.repoOf("shopping"), "milk.txt"), "two litres")
        git = ProjectGit(ws, { null }, { author })
        git.init("shopping")
        log = AgentLog.open(projectDir)
    }

    private fun agent(door: Door, limits: ContextBudget.Limits? = null) = Agent(
        accountDir = account,
        project = "shopping",
        ws = ws,
        git = git,
        log = log,
        door = door,
        model = ActiveModel("deepseek/deepseek-v4.1-flash-1017", "deepseek", "high"),
        // No limits means the defaults: no trigger, no trim, which is what most of these tests
        // want — a request that behaves as if the context had all the room in the world.
        limits = limits ?: ContextBudget.Limits.of(0, 0, ContextBudget.UNKNOWN_CEILING),
    )

    private fun call(name: String, args: String, id: String = "c1") = Call(id, name, args)

    private fun model() = ActiveModel("deepseek/deepseek-v4.1-flash-1017", "deepseek", "high")

    private fun rows() = AgentLog.open(File(ws.accountDir, "shopping")).rows()

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
        val projectDir = File(ws.accountDir, "shopping")
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
            Reply("", listOf(call("read", """{"path":"milk.txt"}"""))),
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
        val outside = File(ws.accountDir, "groceries/notes.txt")
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
        assertFalse("the other project's text is not in the record", log.session.readText()
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
                    call("edit", """{"path":"milk.txt","find":"two","replace":"three"}""")
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
                    call("edit", """{"path":"milk.txt","find":"two","replace":"three"}""")
                ),
            ),
            Reply("", listOf(call("conclude", """{"text":"done"}"""))),
        )

        agent(door).burst("make it three litres")

        assertEquals("the file", "three litres", File(ws.repoOf("shopping"), "milk.txt").readText())
        // **A version, in the repository, not a number kept beside it.** The row's count is a diff
        // against the last push now — and this project has no remote, so there is nothing to compare
        // it with; what the tool promised is that the write was *committed*, and that is what the
        // history says.
        val versions = git.versions("shopping", "milk.txt")
        assertEquals("the newest version holds what the agent wrote", "three litres", versions.first().second)
        assertEquals("and the one before it is what was there", "two litres", versions[1].second)
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
            File(ws.accountDir, "shopping/project_prompt.txt").readText()
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
            Reply("", listOf(call("tree", """{"path":""}"""))),
            Reply("", listOf(call("ask", """{"question":"Fix the README too?"}"""))),
            Reply("", listOf(call("read", """{"path":"README.md"}"""))),
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
                    call("read", """{"path":"milk.txt"}""", id="c2"),
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
            Reply("", listOf(call("tree", """{"path":""}""")))
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

    // ---- the instructions, and the default that changed ----------------------

    @Test
    fun `an instructions file that is still the app's old default is replaced`() {
        // **The update path, and the reason it is tested.** The one-record round rewrote ①, and the
        // file is only written when missing — so a phone that had already run the agent kept the text
        // that tells the model to edit `agent/context.log`, a file that no longer exists. The agent
        // would be refused for ever, and the person's only clue would be an agent that seemed confused.
        val file = File(account, AgentPrompt.SYSTEM_FILE)
        file.writeText(AgentPrompt.PREVIOUS_SYSTEM.first())

        val read = AgentPrompt.system(account)

        assertEquals("the new text is what the agent is given", AgentPrompt.SYSTEM_DEFAULT, read)
        assertEquals("and it is what the file holds now", AgentPrompt.SYSTEM_DEFAULT, file.readText())
        assertFalse("so nothing tells it to edit a file that is gone", read.contains("context.log"))
    }

    @Test
    fun `every default this app ever shipped is recognised, not only the oldest one`() {
        // **The list, not the first entry.** `PREVIOUS_SYSTEM.first()` was the text two rounds ago,
        // and a test that only ever used that one would go on passing while a phone holding the
        // *last* release's unedited default kept instructions this build has changed. Each one is
        // checked, so adding a default without adding it to the list is a failing test rather than a
        // phone left behind.
        for ((index, before) in AgentPrompt.PREVIOUS_SYSTEM.withIndex()) {
            val file = File(account, AgentPrompt.SYSTEM_FILE)
            file.writeText(before)
            assertEquals(
                "entry $index of the shipped defaults is replaced",
                AgentPrompt.SYSTEM_DEFAULT,
                AgentPrompt.system(account),
            )
            assertEquals("and the file holds it now", AgentPrompt.SYSTEM_DEFAULT, file.readText())
            file.delete()
        }
    }

    @Test
    fun `the newest previous default is the text this build replaced`() {
        // **The entry that matters most, because it is what most phones are holding.** A phone that
        // ran the previous release has an ① file with its unedited default — which is this build's
        // predecessor, and whose sentence about a result "coming back as a refusal" is now wrong,
        // because a result too big is filed in a file instead. Recognising it needs those words
        // exactly, so the entry is the old text rather than a paraphrase of it, and it is last
        // because it is the most recent.
        val newest = AgentPrompt.PREVIOUS_SYSTEM.last()
        val older = AgentPrompt.PREVIOUS_SYSTEM[AgentPrompt.PREVIOUS_SYSTEM.size - 2]
        val now = AgentPrompt.SYSTEM_DEFAULT

        assertTrue(
            "it is the text that described a result refused for its size",
            newest.contains("comes back as a refusal naming its"),
        )
        assertFalse("and this build files it instead", now.contains("comes back as a refusal"))
        assertTrue("the layout is the merge's", newest.contains("The project folder is the repository"))
        assertFalse("and it predates the handover rule", newest.contains("still running; call again"))
        assertTrue(
            "the one before it is the cut-short text the round before replaced",
            older.contains("showing first N of M"),
        )
        assertTrue("a previous default is not the current one", newest != now)
    }

    @Test
    fun `re-indenting the old default does not make it the person's file`() {
        // What is being asked is whether any of the *words* changed. A file somebody reflowed is
        // still the app's instructions, and the app's instructions are the ones that must be current.
        val file = File(account, AgentPrompt.SYSTEM_FILE)
        file.writeText(AgentPrompt.PREVIOUS_SYSTEM.first().replace("\n", "\n   "))

        assertEquals("replaced anyway", AgentPrompt.SYSTEM_DEFAULT, AgentPrompt.system(account))
    }

    @Test
    fun `an instructions file the person edited is kept, whatever it says`() {
        val file = File(account, AgentPrompt.SYSTEM_FILE)
        val theirs = AgentPrompt.PREVIOUS_SYSTEM.first() + "\n\nAlso: never touch the README."
        file.writeText(theirs)

        val read = AgentPrompt.system(account)

        assertTrue("their sentence survives", read.contains("never touch the README"))
        assertEquals("and nothing of their file was rewritten", theirs, file.readText())
    }

    // ---- what the door says when it has no key --------------------------------

    @Test
    fun `a locked phone is said as locked, not as an account with no key`() {
        // The agent polls with the screen off, and the store's key is only usable while the phone is
        // unlocked — so "no key" is the wrong sentence for the ordinary case, and it sends a person
        // to type a key they already have. The door has to say which of the two it is, because it is
        // the only thing that knows.
        val locked = OpenRouterDoor(
            key = { null },
            model = "deepseek/deepseek-v4.1-flash-1017",
            provider = "decart/fp4",
            effort = "high",
            keyState = { KeyState.LOCKED },
        )
        val missing = OpenRouterDoor(
            key = { null },
            model = "deepseek/deepseek-v4.1-flash-1017",
            provider = "decart/fp4",
            effort = "high",
            keyState = { KeyState.NONE },
        )
        val request = Request(
            model = "deepseek/deepseek-v4.1-flash-1017",
            provider = "decart/fp4",
            effort = "high",
            system = "",
            messages = emptyList(),
            tools = emptyList(),
        )

        val lockedWords = locked.turn(request).exceptionOrNull()?.message.orEmpty()
        val missingWords = missing.turn(request).exceptionOrNull()?.message.orEmpty()

        assertTrue("it says the phone is locked: $lockedWords", "locked" in lockedWords)
        assertTrue("and that the key is kept: $lockedWords", "unlock" in lockedWords)
        assertFalse("it does not claim the account has no key", "No OpenRouter key" in lockedWords)
        assertTrue("while an account with none is told so: $missingWords", "No OpenRouter key" in missingWords)
    }

    // ---- what goes on the wire ------------------------------------------------

    @Test
    fun `the request names one provider tag, forbids fallbacks, and asks for no reasoning`() {
        // **Three agreed rules in one body, and none of them is visible when it is wrong.** A base
        // slug in `only` turns the pin back into autorouting inside one provider, `allow_fallbacks`
        // true spends money on an endpoint nobody chose, and reasoning tokens are sent and thrown
        // away. The body is a function of its own because the call around it needs a socket.
        val door = OpenRouterDoor(
            key = { "unused-by-this-test" },
            model = "deepseek/deepseek-v4.1-flash-1017",
            provider = "decart/fp4",
            effort = "high",
        )

        val body = door.body(
            Request(
                model = "deepseek/deepseek-v4.1-flash-1017",
                provider = "decart/fp4",
                effort = "high",
                system = "the instructions",
                messages = listOf(Msg.user("a task")),
                tools = AgentTools.specs(),
            ),
        )

        assertEquals("the tag, never the base slug", "decart/fp4", body.getJSONObject("provider").getJSONArray("only").getString(0))
        assertFalse("and nothing beside it", body.getJSONObject("provider").getBoolean("allow_fallbacks"))
        assertTrue("reasoning is not requested back", body.getJSONObject("reasoning").getBoolean("exclude"))
        assertEquals("the model's own way of saying effort", "high", body.getString("reasoning_effort"))
        assertEquals("tool calling is the model's choice", "auto", body.getString("tool_choice"))
        assertFalse("and the answer is not streamed", body.getBoolean("stream"))
    }

    @Test
    fun `every tool the app offers is one the dispatch answers`() {
        // **The list and the `when` drift apart in both directions.** A tool the specs advertise and
        // nothing answers is a model calling something that always fails; a tool the `when` answers
        // and the specs do not mention is code no model can reach. Both were possible while there
        // were three dispatch tables, and one of them was written twice.
        //
        // A path that does not exist is used for every call, because `delete` and `create` would act
        // on the project folder if they were handed nothing.
        for (spec in AgentTools.specs()) {
            if (spec.name in AgentTools.TERMINATING) continue
            val answer = tool(spec.name, """{"path":"no-such-thing"}""")
            assertFalse(
                "${spec.name} is answered rather than unknown: $answer",
                answer.contains("There is no tool called"),
            )
        }
    }

    // ---- the agent's search: tree and read with grep -------------------------

    /** One call, answered the way the loop answers it. */
    private fun tool(name: String, args: String): String =
        tools().run(Call("c7", name, args))

    @Test
    fun `tree with a name answers with the paths that match and nothing else`() {
        ws.write(File(ws.repoOf("shopping"), "README.md"), "the readme")
        ws.write(File(ws.repoOf("shopping"), "notes.md"), "notes")

        val body = tool("tree", """{"path":"","name":"\\.md$"}""")

        assertTrue("accepted: $body", body.startsWith(AgentLog.SUCCESS))
        assertTrue("the readme is in the answer: $body", body.contains("README.md"))
        assertTrue("and so are the notes: $body", body.contains("notes.md"))
        assertFalse("but not the file that did not match: $body", body.contains("milk.txt"))
    }

    @Test
    fun `tree with grep answers with the line that matched, and where it is`() {
        ws.write(File(ws.repoOf("shopping"), "README.md"), "one line\ntwo litres\n")

        val body = tool("tree", """{"path":"","grep":"litres"}""")

        assertTrue(
            "the file, the line number and the line: $body",
            body.contains("README.md:2: two litres"),
        )
        assertTrue("and the other file's own line: $body", body.contains("milk.txt:1: two litres"))
    }

    @Test
    fun `read with grep answers with numbered lines and leaves the rest out`() {
        ws.write(File(ws.repoOf("shopping"), "milk.txt"), "first\ntwo litres\nthird\n")

        val body = tool("read", """{"path":"milk.txt","grep":"litres"}""")

        assertTrue("the line and its number: $body", body.contains("2: two litres"))
        assertFalse("and not the lines that did not match: $body", body.contains("first"))
        assertFalse("nor the one after it: $body", body.contains("third"))
    }

    @Test
    fun `a read of the file the record used to be says where it went`() {
        // The one name an older instruction set still points at. An agent whose prompt the person
        // edited keeps that prompt — so the app has to answer the question it will ask, rather than
        // "there is no file", which leaves it guessing and trying to edit the other one.
        val body = tool("read", """{"path":"agent/context.log"}""")

        assertTrue("refused: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("naming the file it became: $body", body.contains("agent/session.log"))
        assertTrue("and the tool that does what it wanted: $body", body.contains("forget"))
    }

    @Test
    fun `a pattern that is not a regular expression is refused, and says so about the pattern`() {
        // **A model writing `[` is making a mistake about a regular expression.** The answer it
        // needs is that sentence; "that tool could not be run" would say nothing about what to fix.
        val body = tool("read", """{"path":"milk.txt","grep":"["}""")

        assertTrue("refused: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("about the pattern: $body", body.contains("pattern"))
    }

    // ---- the one range language: from / to, then grep inside it ---------------

    @Test
    fun `a read range is inclusive at both ends and to alone means the file`() {
        ws.write(File(ws.repoOf("shopping"), "milk.txt"), "one\ntwo\nthree\nfour\n")

        assertEquals("0\ntwo\nthree", tool("read", """{"path":"milk.txt","from":2,"to":3}"""))
        assertEquals("0\nthree\nfour\n", tool("read", """{"path":"milk.txt","from":3}"""))
        assertEquals("0\none\ntwo", tool("read", """{"path":"milk.txt","to":2}"""))
    }

    @Test
    fun `grep is applied inside the range, and the line numbers are the file's`() {
        // **The order is the whole contract.** The range says which part of the file you are looking
        // at; the pattern says which lines of that part. If grep ran first, the numbers it printed
        // would be numbers in the *result*, and the `edit` that followed would change the wrong line.
        ws.write(File(ws.repoOf("shopping"), "milk.txt"), "milk\nbread\nmilk again\neggs\nmilk last\n")

        val body = tool("read", """{"path":"milk.txt","from":2,"to":4,"grep":"milk"}""")

        assertTrue("the hit inside the window: $body", body.contains("3: milk again"))
        assertFalse("not the one before it: $body", body.contains("1: milk"))
        assertFalse("nor the one after it: $body", body.contains("5: milk last"))
    }

    @Test
    fun `a range that ends before it starts is refused, and says which is which`() {
        ws.write(File(ws.repoOf("shopping"), "milk.txt"), "one\ntwo\n")

        val body = tool("read", """{"path":"milk.txt","from":3,"to":1}""")

        assertTrue("refused: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("naming both numbers: $body", body.contains("3") && body.contains("1"))
    }

    // ---- edit: one shape at a time -----------------------------------------

    @Test
    fun `edit replaces a block of lines by number`() {
        ws.write(File(ws.repoOf("shopping"), "milk.txt"), "one\ntwo\nthree\nfour")

        val body = tool("edit", """{"path":"milk.txt","from":2,"to":3,"replace":"TWO"}""")

        assertTrue("accepted: $body", body.startsWith(AgentLog.SUCCESS))
        assertEquals("the block was replaced, the rest untouched", "one\nTWO\nfour",
            ws.read(File(ws.repoOf("shopping"), "milk.txt")))
    }

    @Test
    fun `edit inserts after the line it is given, and 0 puts the line at the top`() {
        ws.write(File(ws.repoOf("shopping"), "milk.txt"), "one\ntwo\nthree")

        assertTrue(tool("edit", """{"path":"milk.txt","at":1,"replace":"and half"}""")
            .startsWith(AgentLog.SUCCESS))
        assertEquals("one\nand half\ntwo\nthree", ws.read(File(ws.repoOf("shopping"), "milk.txt")))

        assertTrue(tool("edit", """{"path":"milk.txt","at":0,"replace":"first"}""")
            .startsWith(AgentLog.SUCCESS))
        assertEquals("first\none\nand half\ntwo\nthree", ws.read(File(ws.repoOf("shopping"), "milk.txt")))
    }

    @Test
    fun `an at beyond the file is refused rather than silently appended`() {
        ws.write(File(ws.repoOf("shopping"), "milk.txt"), "one\ntwo")

        val body = tool("edit", """{"path":"milk.txt","at":9,"replace":"nowhere"}""")

        assertTrue("refused: $body", body.startsWith(AgentLog.FAILURE))
        assertEquals("and the file is untouched", "one\ntwo", ws.read(File(ws.repoOf("shopping"), "milk.txt")))
    }

    @Test
    fun `edit will not take two shapes at once`() {
        ws.write(File(ws.repoOf("shopping"), "milk.txt"), "one\ntwo")

        val body = tool("edit", """{"path":"milk.txt","find":"one","at":1,"replace":"x"}""")

        assertTrue("refused: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("and it says why: $body", body.contains("one of"))
        assertEquals("with nothing changed", "one\ntwo", ws.read(File(ws.repoOf("shopping"), "milk.txt")))
    }

    @Test
    fun `a replacement can be a read call, and the paste never enters the conversation`() {
        // The whole reason for the shape: two hundred kilobytes of one file going into another
        // must not be refused for being large, because the limit is about what enters the context.
        val big = (1..40).joinToString("\n") { "row $it" }
        ws.write(File(ws.repoOf("shopping"), "notes.txt"), big)
        ws.write(File(ws.repoOf("shopping"), "todo.txt"), "top\nbottom")

        val body = tool(
            "edit",
            """{"path":"todo.txt","at":1,"replace":{"tool":"read","args":{"path":"notes.txt"}}}""",
        )

        assertTrue("accepted: $body", body.startsWith(AgentLog.SUCCESS))
        assertEquals("the whole source came through", "top\n$big\nbottom",
            ws.read(File(ws.repoOf("shopping"), "todo.txt")))
    }

    @Test
    fun `a replacement that names any tool but read is refused`() {
        ws.write(File(ws.repoOf("shopping"), "todo.txt"), "text")

        val body = tool(
            "edit",
            """{"path":"todo.txt","at":0,"replace":{"tool":"delete","args":{"path":"notes.txt"}}}""",
        )

        assertTrue("refused: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("it says which tool it was: $body", body.contains("delete"))
        assertEquals("and nothing was written", "text", ws.read(File(ws.repoOf("shopping"), "todo.txt")))
    }

    @Test
    fun `delete takes the whole folder, which is what its description says`() {
        // **The tool does what the spec promises.** The spec said "one file or an empty folder" while
        // the tool removed any folder and everything in it — and no test called `delete` at all, which
        // is why the two could drift apart for a release without anything noticing.
        val folder = File(ws.repoOf("shopping"), "notes")
        ws.write(File(folder, "one.txt"), "one")
        ws.write(File(folder, "inner/two.txt"), "two")
        git.commit("shopping", "notes")

        val body = tool("delete", """{"path":"notes"}""")

        assertTrue("accepted: $body", body.startsWith(AgentLog.SUCCESS))
        assertFalse("the folder is gone", folder.exists())
    }

    // ---- a key the command does not take --------------------------------------

    @Test
    fun `a key a command does not take is refused, and the refusal teaches the command`() {
        // **`lines` is the one that matters.** It was a real argument in a shipped description, so a
        // model will keep sending it — and it used to be ignored, which turned "forty lines from
        // here" into "everything from here". A refusal that names the key, the keys that work and
        // the command's own example is the only answer that repairs the model's idea of it.
        ws.write(File(ws.repoOf("shopping"), "milk.txt"), (1..40).joinToString("\n") { "line $it" })

        val body = tool("read", """{"path":"milk.txt","from":10,"lines":5}""")

        assertTrue("refused: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("naming the key: $body", body.contains("`lines`"))
        assertTrue("and the ones that work: $body", body.contains("`from`"))
        assertTrue("and that it was renamed: $body", body.contains("not a key any more"))
        assertTrue(
            "and its own example, so the answer shows the shape: $body",
            body.contains("relative to the project"),
        )
        assertFalse("nothing of the file came through", body.contains("line 40"))
    }

    @Test
    fun `a wrong key stops the command before it does anything`() {
        // The check is before the work, not inside it: a `create` with a misspelled key must leave
        // no file, and an `edit` with one must leave the file as it stood.
        val file = File(ws.repoOf("shopping"), "milk.txt")
        ws.write(file, "one\ntwo")

        val edited = tool("edit", """{"path":"milk.txt","find":"one","replce":"three"}""")
        val created = tool("create", """{"path":"new.txt","text":"hi","force":true}""")

        assertTrue("the edit is refused: $edited", edited.startsWith(AgentLog.FAILURE))
        assertTrue("the create is refused: $created", created.startsWith(AgentLog.FAILURE))
        assertEquals("and the file is untouched", "one\ntwo", file.readText())
        assertFalse("and nothing was made", File(ws.repoOf("shopping"), "new.txt").exists())
    }

    @Test
    fun `a background command is checked before the work starts`() {
        // Otherwise the refusal lands in the result file, and the model reads it only once it thinks
        // the command has finished — a wrong key answered minutes after it was sent.
        ws.write(File(ws.repoOf("shopping"), "milk.txt"), "one")

        val body = tool("background", """{"tool":"read","args":{"path":"milk.txt","lines":5}}""")

        assertTrue("refused at once: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("naming the key: $body", body.contains("`lines`"))
    }

    @Test
    fun `a conclude with a key it does not take does not end the turn`() {
        // **An ending nobody asked for is worse than a refusal.** `conclude {"txt":"done"}` used to
        // fall back to the reply's own prose and end the turn, so the model was told nothing and the
        // person was told whatever the model happened to say in passing.
        val door = ScriptedDoor(
            Reply("", listOf(call("conclude", """{"txt":"done"}"""))),
            Reply("", listOf(call("conclude", """{"text":"all done"}"""))),
        )

        val end = agent(door).burst("do the thing")

        assertEquals(
            "the second conclude is the one that ended it",
            "all done",
            (end as Agent.End.Concluded).said,
        )
        val got = gotRows().first()
        assertTrue("the first was refused: ${got.body}", got.body.contains("`txt`"))
    }

    @Test
    fun `every tool says which keys it takes, and its example uses no other`() {
        // The pair that keeps a description honest: a command with no key list cannot be checked,
        // and an example showing a key the command refuses is a description that teaches a refusal.
        val specs = AgentTools.specs()
        for (spec in specs) {
            val took = AgentTools.KEYS[spec.name] ?: error("${spec.name} declares no keys")
            val shown = spec.parameters.keys().asSequence().toSet()
            assertEquals(
                "${spec.name}'s example shows only keys it takes",
                emptySet<String>(),
                shown - took,
            )
        }
        assertEquals(
            "and no key list names a command that is not offered",
            emptySet<String>(),
            AgentTools.KEYS.keys - specs.map { it.name }.toSet(),
        )
    }

    // ---- read at a version --------------------------------------------------

    @Test
    fun `read shows a version without putting it back`() {
        // **The middle step between history and restore.** Without it the only way to see what an
        // older commit said is to put it back, which changes the file the person is working on.
        val file = File(ws.repoOf("shopping"), "milk.txt")
        ws.write(file, "one litre")
        val first = git.commit("shopping", "first")!!
        ws.write(file, "three litres")
        git.commit("shopping", "second")

        val body = tool("read", """{"path":"milk.txt","revision":"$first"}""")

        assertEquals("the text as it stood at that commit", "0\none litre", body)
        assertEquals("and the file itself did not move", "three litres", ws.read(file))
    }

    @Test
    fun `an unknown revision is refused, and names it`() {
        ws.write(File(ws.repoOf("shopping"), "milk.txt"), "two litres")

        val body = tool("read", """{"path":"milk.txt","revision":"0000000"}""")

        assertTrue("refused: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("naming the id: $body", body.contains("0000000"))
    }

    // ---- history: a save is not the same row twice --------------------------

    @Test
    fun `a history row says what the version contained, so twenty saves are told apart`() {
        // **Save commits one word, so the message tells versions apart not at all.** The file's own
        // first line is the only thing that does, and `versionsOf` has already loaded it.
        val file = File(ws.repoOf("shopping"), "milk.txt")
        ws.write(file, "one litre and a lemon")
        git.commit("shopping", CommitMessage.SAVE)
        ws.write(file, "three litres")
        git.commit("shopping", CommitMessage.SAVE)

        val body = tool("history", """{"path":"milk.txt"}""")

        assertTrue("accepted: $body", body.startsWith(AgentLog.SUCCESS))
        assertTrue("the newer save is recognisable: $body", body.contains("three litres"))
        assertTrue("and so is the older one: $body", body.contains("one litre and a lemon"))
        assertTrue("both rows say the same word, which is why the excerpt exists: $body",
            body.split(CommitMessage.SAVE).size == 3)
    }

    @Test
    fun `history greps the file's text at each version, not the commit messages`() {
        val file = File(ws.repoOf("shopping"), "milk.txt")
        ws.write(file, "two litres of milk")
        git.commit("shopping", "first")
        ws.write(file, "two litres of bread")
        git.commit("shopping", "second")

        val body = tool("history", """{"path":"milk.txt","grep":"bread"}""")

        assertTrue("the version whose text matched: $body", body.contains("bread"))
        assertFalse("and not the one whose text did not: $body", body.contains("of milk"))
    }

    // ---- move with a copy flag ---------------------------------------------

    @Test
    fun `move with copy leaves the original, and a taken name is refused`() {
        val dir = ws.repoOf("shopping")
        ws.write(File(dir, "milk.txt"), "two litres")
        ws.createFolder(dir, "drafts")

        val body = tool("move", """{"path":"milk.txt","into":"drafts","copy":true}""")

        assertTrue("accepted: $body", body.startsWith(AgentLog.SUCCESS))
        assertTrue("the copy is there", File(dir, "drafts/milk.txt").isFile)
        assertTrue("and the original stayed", File(dir, "milk.txt").isFile)

        val again = tool("move", """{"path":"milk.txt","into":"drafts","copy":true}""")
        assertTrue("the second copy is refused: $again", again.startsWith(AgentLog.FAILURE))
        assertFalse("and no numbered name was invented", File(dir, "drafts/milk 2.txt").exists())
    }

    @Test
    fun `a copy that also asks for a new name is refused, because it is two acts`() {
        val dir = ws.repoOf("shopping")
        ws.write(File(dir, "milk.txt"), "two litres")
        ws.createFolder(dir, "drafts")

        val body = tool("move", """{"path":"milk.txt","into":"drafts","copy":true,"name":"dairy.txt"}""")

        assertTrue("refused: $body", body.startsWith(AgentLog.FAILURE))
        assertFalse("nothing was copied", File(dir, "drafts/milk.txt").exists())
        assertFalse("and nothing was renamed", File(dir, "drafts/dairy.txt").exists())
    }

    // ---- background: a result that lands in a file ---------------------------

    @Test
    fun `any command can be put in the background, and the answer is the path before the work starts`() {
        // **The device is weak and the files are big.** Even a copy can take a while, and grepping
        // an enormous log is the case this exists for — so the old refusal of "everything else
        // finishes in milliseconds" was a claim about the phone that the phone does not make.
        //
        // The file exists when the answer comes back — "running" until the work lands, then the
        // body — so a `read` a millisecond later finds the truth rather than an empty folder.
        val body = tool("background", """{"tool":"read","args":{"path":"milk.txt"}}""")

        assertTrue("accepted: $body", body.startsWith(AgentLog.SUCCESS))
        val path = body.substringAfter("written to ").trim()
        assertTrue("it names where the result goes: $body", path.contains("background_results/"))
        assertTrue(
            "and that file is already there: $path",
            File(ws.projectDir("shopping"), path).isFile,
        )
    }

    @Test
    fun `two backgrounded commands keep two results, even of the same tool`() {
        // **The path is named after the call, and the inner call used to have no id of its own.** It
        // took one from the arguments, defaulting to "0", so two backgrounded reads both wrote
        // `read-0.txt` — the second answer replaced the first, silently, and the agent read it as the
        // answer to its own question.
        ws.write(File(ws.repoOf("shopping"), "milk.txt"), "the milk")
        ws.write(File(ws.repoOf("shopping"), "bread.txt"), "the bread")
        val direct = tools()

        val one = direct.run(Call("c1", "background", """{"tool":"read","args":{"path":"milk.txt"}}"""))
        val two = direct.run(Call("c2", "background", """{"tool":"read","args":{"path":"bread.txt"}}"""))

        assertTrue("both worked: $one / $two", one.startsWith(AgentLog.SUCCESS) && two.startsWith(AgentLog.SUCCESS))
        assertEquals("the first answer", "0\nthe milk", File(ws.projectDir("shopping"), "agent/background_results/read-c1.txt").readText())
        assertEquals("the second answer", "0\nthe bread", File(ws.projectDir("shopping"), "agent/background_results/read-c2.txt").readText())
    }

    @Test
    fun `the three that end a turn cannot be put in the background`() {
        // There is no result to file: the turn ends on them.
        listOf("ask", "conclude", "wait").forEach { tool ->
            val body = tool("background", """{"tool":"$tool","args":{}}""")
            assertTrue("$tool is refused: $body", body.startsWith(AgentLog.FAILURE))
            assertTrue("and says why: $body", body.contains("ends the turn"))
        }
    }

    @Test
    fun `background with no command named is refused, and says what is missing`() {
        val body = tool("background", """{"args":{"url":"https://github.com/o/r"}}""")

        assertTrue("refused: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("it asks for the command: $body", body.contains("which command"))
    }

    // ---- the agent's own compaction: forget, by id ---------------------------

    private fun tools() = AgentTools(ws, "shopping", git, log)

    /** Calls `forget` the way the loop does, and answers the result. */
    private fun forget(vararg ids: Int): String = tools().run(
        Call("c9", "forget", JSONObject().put("ids", org.json.JSONArray(ids.toList())).toString()),
    )

    @Test
    fun `forgetting a pair takes it out of the context and leaves the text on the record`() {
        log.told("task one")
        val call = log.call("read", """{"path":"milk.txt"}""")
        val got = log.got("read", "0\ntwo litres")
        log.told("task two")

        val answer = forget(call, got)

        assertTrue("accepted: $answer", answer.startsWith(AgentLog.SUCCESS))
        assertTrue("the body says what went: $answer", answer.contains("forgot 2 rows"))
        assertTrue(
            "and it says the task is still there, by id: $answer",
            answer.contains("The task is still"),
        )
        assertTrue(
            "the rows are out of the context",
            log.contextRows().none { it.id == call || it.id == got },
        )
        assertEquals(
            "and still on the record, marked out",
            setOf(AgentLog.OUT),
            log.rows().filter { it.id == call || it.id == got }.map { it.ctx }.toSet(),
        )
        assertEquals(
            "exactly the two of them are his judgement",
            listOf(call, got),
            log.thrownAway().map { it.id },
        )
    }

    @Test
    fun `a forget cannot carry text, so the record cannot be rewritten`() {
        // **The class of bug that is now impossible.** Compaction used to be an `edit`, and the app
        // had to prove the new text was the old text minus some lines — with rules about whole rows,
        // final newlines and escaping. A forget names ids, so there is no text to change.
        log.told("a task")
        val answer = tools().run(
            Call("c9", "edit", JSONObject().put("path", "agent/session.log")
                .put("text", "").toString()),
        )

        assertTrue("the edit is refused: $answer", answer.startsWith(AgentLog.FAILURE))
        assertTrue("and it says whose file it is: $answer", answer.contains("record"))
    }

    @Test
    fun `the newest task cannot be forgotten`() {
        log.told("task one")
        log.call("tree", "{}")
        log.got("tree", "0\nnothing")
        val task = log.told("task two")

        val answer = forget(task)

        assertTrue("refused: $answer", answer.startsWith(AgentLog.FAILURE))
        assertTrue("and it says which rule: $answer", answer.contains("newest task"))
        assertTrue("the row is still in", log.contextRows().any { it.id == task })
    }

    @Test
    fun `a call and its result are forgotten together`() {
        log.told("a task")
        val call = log.call("tree", "{}")
        val got = log.got("tree", "0\nnothing")

        val onlyCall = forget(call)
        assertTrue("the call alone is refused: $onlyCall", onlyCall.startsWith(AgentLog.FAILURE))
        assertTrue("and it names the pair, not the ids: $onlyCall", onlyCall.contains("go together"))

        val onlyGot = forget(got)
        assertTrue("and the result alone: $onlyGot", onlyGot.startsWith(AgentLog.FAILURE))

        val both = forget(call, got)
        assertTrue("together they may go: $both", both.startsWith(AgentLog.SUCCESS))
        assertTrue("and they did", log.contextRows().none { it.id == call || it.id == got })
    }

    @Test
    fun `a row that is already out is told so rather than quietly skipped`() {
        log.told("a task")
        val call = log.call("read", """{"path":"milk.txt"}""")
        val got = log.got("read", "0\ntwo litres")
        forget(call, got)

        val again = forget(call, got)

        assertTrue("refused: $again", again.startsWith(AgentLog.FAILURE))
        assertTrue("and the reason names the row: $again", again.contains("not in the context"))
    }

    @Test
    fun `the call being made now cannot be forgotten`() {
        // The row for a call is written before the tool runs, so `forget`'s own CALL is in the
        // context and unanswered while it decides. Forgetting it would leave a call with no result,
        // which every endpoint refuses — and the answer says which row it is rather than pretending
        // the list was fine.
        log.told("a task")
        log.note("agent", "a note that can go")
        val inFlight = log.call("forget", """{"ids":[]}""")

        val answer = forget(inFlight)

        assertTrue("refused: $answer", answer.startsWith(AgentLog.FAILURE))
        assertTrue("and it says why: $answer", answer.contains("making now"))
    }

    @Test
    fun `a forget that names nothing is a refusal, not an empty success`() {
        log.told("a task")
        val answer = tools().run(Call("c9", "forget", """{"ids":[]}"""))
        assertTrue("refused: $answer", answer.startsWith(AgentLog.FAILURE))
        assertTrue("and it asks for a row: $answer", answer.contains("at least one row"))
    }

    // ---- the record as the model reads it -----------------------------------

    @Test
    fun `a rebuilt conversation puts the prose and the calls of one reply together`() {
        // On the wire they were one assistant message; a rebuild that split them would show the
        // model two turns where there was one, and the calls would look unprompted.
        log.told("do it")
        log.note("agent", "I will look first.")
        log.call("tree", """{"path":""}""")
        log.got("tree", "0\nmilk.txt")

        val messages = agent(ScriptedDoor()).messages(log.contextRows())

        val assistant = messages.first { it.role == "assistant" }
        assertTrue("its words", assistant.text.contains("I will look first"))
        assertEquals("and its call, in the same turn", 1, assistant.calls.size)
        assertEquals("the answer comes next", "tool", messages[1 + 1].role.let { messages[2].role })
    }

    @Test
    fun `a tool result is matched to its own call`() {
        log.told("do two")
        log.call("read", """{"path":"milk.txt"}""")
        log.call("tree", """{"path":""}""")
        log.got("read", "0\nfile contents")
        log.got("tree", "0\nmilk.txt")

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
            Reply("", listOf(call("tree", """{"path":""}"""))),
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
            Reply("", listOf(call("tree", """{"path":""}"""))),
            Reply("", listOf(call("read", """{"path":"milk.txt"}"""))),
            Reply("", listOf(call("conclude", """{"text":"done"}"""))),
        )

        agent(door).burst("look around")

        assertTrue(
            "nothing dangles",
            AgentLog.open(File(ws.accountDir, "shopping")).dangling(rows()).isEmpty(),
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
            File(ws.accountDir, "shopping").walkTopDown().filter { it.isFile }
                .any { it.name != "session.log" &&
                    it.readText().contains("only file") },
        )
    }

    @Test
    fun `a result over the limit is written to a file, and the answer is its path`() {
        // **Filed, not cut and not refused.** The body already exists when the limit is asked about,
        // so the whole of it goes to the same folder a background command uses and the conversation
        // gets the path. Nothing is lost and nothing is decided twice: a model that is shown half a
        // file edits the wrong half with complete confidence, and a model told only "too big, narrow
        // it" has to guess by how much.
        val big = (1..400).joinToString("\n") { "line $it of the file" }
        ws.write(File(ws.repoOf("shopping"), "big.txt"), big)
        git.commit("shopping", "big")

        val door = ScriptedDoor(
            Reply("", listOf(call("read", """{"path":"big.txt"}"""))),
            Reply("", listOf(call("conclude", """{"text":"read what I could"}"""))),
        )

        Agent(
            accountDir = account, project = "shopping", ws = ws, git = git, log = log,
            door = door,
            model = ActiveModel("deepseek/deepseek-v4.1-flash-1017", "deepseek", "high"),
            maxResult = 500,
        ).burst("read the whole thing")

        val got = gotRows().first()
        assertTrue("it worked, and was filed: ${got.body}", got.body.startsWith(AgentLog.SUCCESS))
        assertTrue("it says the limit it passed: ${got.body}", got.body.contains("500"))
        assertFalse("nothing of the file came through", got.body.contains("line 400 of the file"))

        val path = Regex("agent/background_results/read-\\S+\\.txt").find(got.body)
            ?.value ?: error("the answer names the file it was written to: ${got.body}")
        val filed = File(ws.accountDir, "shopping/$path")
        assertTrue("and the file is there: $path", filed.isFile)
        assertEquals("holding the whole result", big, filed.readText())
    }

    @Test
    fun `a refusal too big to fit is filed too, and keeps its failure`() {
        // The status belongs to the call, not to the filing. A refusal that had to be filed is
        // still a refusal — otherwise a failed edit that quoted a lot of the file would read as a
        // success that happened to leave a note.
        val big = (1..400).joinToString("\n") { "line $it of the file" }
        ws.write(File(ws.repoOf("shopping"), "big.txt"), big)
        val direct = AgentTools(ws, "shopping", git, log, maxChars = 200)

        val said = direct.forConversation(
            Call("c9", "read", """{"path":"big.txt"}"""),
            direct.run(Call("c9", "read", """{"path":"big.txt"}""")).replaceFirst("0\n", "1\n"),
        )

        assertTrue("still a refusal: $said", said.startsWith(AgentLog.FAILURE))
        assertTrue("and it says where: $said", said.contains("background_results/read-c9.txt"))
    }

    // ---- the context is the sources, not a copy of them ----------------------

    @Test
    fun `the instructions are read at the start of a turn, so an edit arrives at the next one`() {
        // **One brief per conversation, and the next turn sees the change.** A layer read per
        // request would answer one turn under two different briefs; a layer cached across turns
        // would never see the edit at all. The door does the editing, because that is the only
        // way to change the file *while* the agent is working.
        val projectDir = File(ws.accountDir, "shopping")
        val brief = File(projectDir, AgentPrompt.PROJECT_FILE)
        brief.writeText("Answer in one sentence.")

        val door = object : Door {
            val asked = mutableListOf<Request>()
            override fun turn(request: Request): Result<Reply> {
                asked.add(request)
                if (asked.size == 1) brief.writeText("Answer in three sentences.")
                return Result.success(
                    if (asked.size == 1) {
                        Reply("", listOf(Call("c1", "tree", """{"path":""}""")))
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

    // ---- the two context limits ---------------------------------------------

    @Test
    fun `over the trigger the request ends with the app's clear-up line, and the instructions are untouched`() {
        // Round 5 §1.8: over the trigger the model is told to clear up, **with the numbers** —
        // "you are over" teaches nothing. And the line goes at the *end* of the system message, so
        // the instructions above it are still the same bytes: that is the difference between a
        // warning that costs a line and one that costs a re-read of everything.
        val dir = File(ws.accountDir, "shopping")
        val prompt = AgentPrompt.read(account, dir).message
        val limits = ContextBudget.Limits.of(triggerAsked = 1, hardAsked = 0, ceiling = 100_000)
        val door = ScriptedDoor(Reply("", listOf(call("conclude", """{"text":"done"}"""))))

        Agent(account, "shopping", ws, git, log, door, model(), limits = limits).burst("go")

        val sent = door.asked.first().system
        assertTrue("the instruction: $sent", sent.contains("Clear up your context"))
        // The three numbers are named for what they are, and **not pinned to a value here**: the
        // size is an estimate and a test that spelled it would be testing arithmetic rather than
        // the sentence. What matters is that each is present and says which it is.
        assertTrue("the size it was over", sent.contains("tokens when this turn began"))
        assertTrue("the limit the target comes from", sent.contains("The limit is"))
        assertTrue("and the wall behind it", sent.contains("takes 100k tokens of input"))
        assertTrue("the instructions are still the prefix", sent.startsWith(prompt.trim()))
        assertTrue(
            "and the line is after them, not inside them",
            sent.indexOf("Clear up your context") > sent.indexOf(AgentPrompt.SEPARATOR),
        )
    }

    @Test
    fun `the operator's words cut a turn short, and its calls are answered anyway`() {
        // v1's C8, as corrected by the operator: a message cannot be sent *beside* a request in
        // flight, so the request is abandoned, **the calls it carried get their result anyway**
        // (`1 interrupted`) because a call with no answer is an illegal conversation, and the
        // person's words become the next turn — tool results first, then the message.
        var speak = false
        val door = object : Door {
            val asked = mutableListOf<Request>()
            override fun turn(request: Request): Result<Reply> {
                asked.add(request)
                // The person types while this request is out.
                if (asked.size == 1) speak = true
                return Result.success(
                    Reply("", listOf(Call("c${asked.size}", "tree", """{"path":""}"""))),
                )
            }
        }
        val agent = Agent(
            account, "shopping", ws, git, log, door, model(),
            cut = { if (speak) "the operator spoke" else null },
        )

        val end = agent.burst("look around")

        assertTrue("cut short, not stopped: $end", end is Agent.End.Interrupted)
        val calls = callRows()
        assertEquals("the command the reply carried is on the record", 1, calls.size)
        assertEquals("and the next request is not sent", 1, door.asked.size)
        val answered = log.rows().first { it.kind == AgentLog.GOT }
        assertTrue(
            "with an answer that says it was interrupted: ${answered.body}",
            answered.body.startsWith(AgentLog.FAILURE) && answered.body.contains("interrupted"),
        )
        // The record stays a conversation: every call has a result after it.
        assertTrue("every call is answered", log.dangling(log.rows()).isEmpty())

        // The person is done speaking: the next turn is a normal one.
        speak = false
        agent.burst("never mind, do the other thing")
        // **The next burst's first request**, not its last: this door keeps answering with a
        // command, so the second burst loops until the idle guard and its last request is the
        // twentieth.
        val second = door.asked[1].messages
        val tool = second.indexOfFirst { it.role == "tool" }
        val words = second.indexOfLast { it.role == "user" }
        assertEquals("the words are last", second.lastIndex, words)
        assertTrue(
            "and the interrupted answer is before them: ${second.map { it.role }}",
            tool in 0 until words,
        )
        assertTrue(
            "saying what happened: ${second[tool].text}",
            second[tool].text.contains("interrupted"),
        )
    }

    @Test
    fun `stopping mid-turn is a stop, and answering 'what' does not undo it`() {
        // The other half of the same mechanism: the operator pressed stop, so the turn ends as a
        // stop — not as an interruption — and the session goes off.
        val door = object : Door {
            override fun turn(request: Request): Result<Reply> =
                Result.success(Reply("", listOf(Call("c1", "tree", """{"path":""}"""))))
        }
        val agent = Agent(
            account, "shopping", ws, git, log, door, model(),
            cut = { Agent.BY_OPERATOR },
        )

        val end = agent.burst("go")

        assertTrue("a stop: $end", end is Agent.End.Stopped)
        assertEquals(
            "with the reason the session needs",
            AgentSession.StopReason.BY_OPERATOR,
            (end as Agent.End.Stopped).reason,
        )
        assertFalse("and not something to retry", end.retryable)
        assertEquals("the session stays stopped", AgentSession.State.STOPPED, end.after(
            AgentSession("shopping", state = AgentSession.State.IDLE), 1_000,
        ).state)
    }

    @Test
    fun `an operator's stop is not rewritten by a harder ending`() {
        // **The two copies of this rule disagreed, and this is the case that told them apart.** The
        // screen kept the operator's stop against every ending; the runner kept it only against an
        // ending that left the session running — so a provider refusing the key a second after the
        // person pressed stop replaced "I stopped it" with the app's own reason, and the session
        // started asking to be resolved. The person's word wins, and there is now one rule saying so.
        val stopped = AgentSession(
            project = "shopping",
            state = AgentSession.State.STOPPED,
            reason = AgentSession.StopReason.BY_OPERATOR,
            lastWords = "the key was refused",
        )
        val wall = Agent.End.Stopped(
            "OpenRouter answered 401",
            reason = AgentSession.StopReason.PIN_REFUSED,
        )

        assertEquals(
            "the operator's reason, not the provider's",
            AgentSession.StopReason.BY_OPERATOR,
            wall.onto(stopped, now = 5_000).reason,
        )
        assertEquals(
            "and it is still the furthest thing from needing the person",
            false,
            wall.onto(stopped, now = 5_000).needsYou,
        )
        assertEquals(
            "while an ending that arrives at a running session is obeyed",
            AgentSession.State.IDLE,
            Agent.End.Waited.onto(AgentSession("shopping"), now = 5_000).state,
        )
    }

    @Test
    fun `the system message is the same bytes on every request of one turn`() {
        // **The cache, and the reason the line is measured once.** The system message is the first
        // message in the array, so a size that changed per request would break the prefix the poll
        // and the cache depend on — on every request of the turn that contains the warning, which
        // is the turn where a re-read is most expensive.
        val limits = ContextBudget.Limits.of(triggerAsked = 1, hardAsked = 0, ceiling = 100_000)
        val door = ScriptedDoor(
            Reply("", listOf(call("c1", "tree", """{"path":""}"""))),
            Reply("", listOf(call("c2", "read", """{"path":"milk.txt"}"""))),
            Reply("", listOf(Call("c3", "conclude", """{"text":"done"}"""))),
        )

        Agent(account, "shopping", ws, git, log, door, model(), limits = limits).burst("go")

        assertEquals("three requests, and the context grew between them", 3, door.asked.size)
        val systems = door.asked.map { it.system }.toSet()
        assertEquals("one system message for the whole turn", 1, systems.size)
        assertTrue(
            "while the conversation below it did grow",
            door.asked[2].messages.size > door.asked[0].messages.size,
        )
    }

    @Test
    fun `the clear-up names a target to reach, and may be cut past it`() {
        // The operator's correction: "target compaction should be 'at least to' — so that agent on
        // compaction must cut at least to 'target' size with more if information is not directly
        // related to his current task". A floor, not a ceiling: exactly at the target is the least
        // that counts, and cutting further is what is wanted when rows do not serve the task.
        val limits = ContextBudget.Limits.of(triggerAsked = 1, hardAsked = 0, ceiling = 100_000)
        val door = ScriptedDoor(Reply("", listOf(call("conclude", """{"text":"done"}"""))))

        Agent(account, "shopping", ws, git, log, door, model(), limits = limits).burst("go")

        val sent = door.asked.first().system
        assertTrue("a target, named: $sent", sent.contains("Compaction target"))
        assertTrue("as a floor", sent.contains("at least to that"))
        assertTrue("with cutting further wanted", sent.contains("further if the rows do not serve"))
        assertFalse("and no longer an empty 'there is no size'", sent.contains("no size to reach"))
    }

    @Test
    fun `the clear-up index names a line a read can open, and the biggest rows first`() {
        // **How the agent actually chooses.** A `read` of a large context stops at the tool's own
        // limit, so a model deciding what to remove either pages through it — a request per twenty
        // thousand characters — or guesses. The index gives the line number the read tool takes and
        // the row's id as a stable label, ordered by what each costs.
        log.told("a small task")
        log.note("agent", "short note")
        // One big row: a result carrying a file's worth of text.
        log.call("read", """{"path":"milk.txt"}""")
        log.got("read", "0\n" + "x".repeat(4_000))
        val limits = ContextBudget.Limits.of(triggerAsked = 100, hardAsked = 0, ceiling = 100_000)
        val door = ScriptedDoor(Reply("", listOf(call("conclude", """{"text":"done"}"""))))

        agent(door, limits).burst("clear up please")

        val sent = door.asked.first().system
        val index = sent.substringAfter("The rows taking the most room")
        assertTrue("the biggest row is named first: $index", index.contains("#4 GOT read"))
        assertTrue("with its line to read", index.contains("line 4:"))
        assertTrue("and its size", index.contains("tokens"))
        assertTrue("and it says how to look", sent.contains("read agent/session.log from <line>"))

        // The line number is the row's position in the record, so a read of it returns that row.
        val context = File(ws.accountDir, "shopping/${AgentLog.DIR}/${AgentLog.SESSION}")
        val fourth = context.readLines()[3]
        assertTrue("the row at that line is the big one: $fourth", fourth.contains("GOT"))
        assertTrue("and the id in the index is on it", fourth.contains(" 4 "))
    }

    @Test
    fun `a clear-up is judged by its rules, and the next turn is what measures`() {
        // **Nothing verifies a size.** The design's check is structural — whole rows, calls with
        // their results, never the newest task — and the number is the *next turn's* business,
        // because the estimate is the app's and a refusal on it would be a refusal on arithmetic.
        // So: the agent removes one row with its own edit tool, the app trims nothing, and the
        // turn after that says nothing at all.
        log.told("task one")
        log.note("agent", "working on the first thing")
        log.told("task two")
        // A trigger of one token, so the turn is told about the target whatever the measurement —
        // the assertion is about the rule, not about the app's arithmetic.
        val limits = ContextBudget.Limits.of(triggerAsked = 1, hardAsked = 0, ceiling = 1_000_000)
        val first = log.rows().first { it.kind == AgentLog.TOLD }
        val door = ScriptedDoor(
            Reply("", listOf(call("forget", """{"ids":[${first.id}]}"""))),
            Reply("", listOf(Call("c2", "conclude", """{"text":"cleared up"}"""))),
            Reply("", listOf(Call("c3", "conclude", """{"text":"done"}"""))),
        )
        val agent = agent(door, limits)

        agent.burst("clear it up")
        assertTrue(
            "the first turn was told: ${door.asked.first().system.takeLast(200)}",
            door.asked.first().system.contains("Compaction target"),
        )
        val got = log.rows().first { it.kind == AgentLog.GOT }
        assertTrue(
            "the agent's own forget was accepted: ${got.body}",
            got.body.startsWith(AgentLog.SUCCESS),
        )
        assertTrue("the row is out of the context", log.contextRows().none { it.id == first.id })
        assertEquals(
            "and it is still on the record, marked out",
            AgentLog.OUT,
            log.rows().first { it.id == first.id }.ctx,
        )
        assertTrue(
            "and the app trimmed nothing itself",
            log.rows().none { it.name == "trimmed" },
        )

        // **A trigger above what the context now costs**, so the assertion is about the rule and
        // not about arithmetic: the same content, a limit it fits under, and no warning.
        val now = agent.size()
        val lenient = ContextBudget.Limits.of(
            triggerAsked = now + 1,
            hardAsked = 0,
            ceiling = 1_000_000,
        )
        val afterDoor = ScriptedDoor(Reply("", listOf(Call("c9", "conclude", """{"text":"ok"}"""))))
        agent(afterDoor, lenient).burst("again")

        assertFalse(
            "under the target, the next turn says nothing: ${afterDoor.asked.last().system.takeLast(120)}",
            afterDoor.asked.last().system.contains("Compaction target"),
        )
    }

    @Test
    fun `under the trigger nothing is said about the context at all`() {
        val limits = ContextBudget.Limits.of(triggerAsked = 900_000, hardAsked = 0, ceiling = 917_504)
        val door = ScriptedDoor(Reply("", listOf(call("conclude", """{"text":"done"}"""))))

        Agent(account, "shopping", ws, git, log, door, model(), limits = limits).burst("go")

        assertFalse(
            "a request that fits says nothing about fitting",
            door.asked.first().system.contains("Clear up your context"),
        )
    }

    @Test
    fun `over the hard limit the app trims the oldest conversations and says how many`() {
        // The app's act, not the agent's: whole bursts, oldest first, and the newest one kept even
        // when it alone is over — because it holds the task being worked on.
        for (i in 1..6) {
            log.told("task $i")
            log.note("agent", "working on $i")
        }
        // A small but real pair of numbers: the trigger is what the agent is asked at, and the
        // hard limit sits above it, because the app's trim is the last resort and never the first
        // thing that happens. The content is a few hundred tokens, so both are crossed.
        val limits = ContextBudget.Limits.of(triggerAsked = 100, hardAsked = 140, ceiling = 100_000)
        val door = ScriptedDoor(Reply("", listOf(call("conclude", """{"text":"done"}"""))))
        val before = Trace.report().lines.toSet()

        Agent(account, "shopping", ws, git, log, door, model(), limits = limits).burst("the seventh")

        val added = Trace.report().lines.filterNot { it in before }
        assertEquals("the trim is on the trace", 1, added.count { it.contains("agent-trim") })
        val context = log.contextRows()
        assertTrue(
            "the oldest task is gone from the context: $context",
            context.none { it.body == "task 1" },
        )
        assertTrue(
            "and the newest is still there",
            context.any { it.body == "the seventh" },
        )
        assertTrue(
            "while the evidence still has every one of them",
            log.rows().count { it.kind == AgentLog.TOLD } == 7,
        )
        assertTrue(
            "and the request was built from what was left",
            door.asked.first().messages.none { it.text == "task 1" },
        )
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
        val dir = File(ws.accountDir, "shopping")
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
    fun `the working context is read from the record, not remembered`() {
        // A row taken out of the context by somebody else — a reset, a trim, or a person with a
        // shell. If the loop kept a transcript in memory this is the request that never notices.
        log.told("first")
        val door = ScriptedDoor(Reply("", listOf(Call("c1", "conclude", """{"text":"done"}"""))))
        val agent = agent(door)

        log.reset()
        agent.burst("second")

        val sent = door.asked.first().messages.map { it.text }
        assertEquals("the context on disk, and the operator's own words", listOf("second"), sent)
    }

    @Test
    fun `a reset leaves the next request with the new task and nothing before it`() {
        val door = ScriptedDoor(
            Reply("", listOf(call("tree", """{"path":""}"""))),
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
            third.none { it.text.contains("milk") },
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
        log.bookkeeping(AgentLog.GOT, "tree", "0\nleft over from before the reset")
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
                    listOf(Call("c${++n}", "create", """{"path":"f$n.txt","text":"$n"}""")),
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
            Reply("", listOf(call("read", """{"path":"milk.txt"}"""))),
            Reply("", listOf(call("conclude", """{"text":"done"}"""))),
        )

        agent(door).burst("look")

        val call = callRows().first()
        assertEquals("the tool", "read", call.name)
        assertEquals("and exactly what it was given", """{"path":"milk.txt"}""", call.body)
        assertTrue("as JSON, so a reader can parse it", JSONObject(call.body).has("path"))
    }
}
