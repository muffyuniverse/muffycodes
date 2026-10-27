package com.example.notes

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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

    /**
     * The id of the last call this test made, so a result can name the call it answers.
     *
     * The record says the pairing now rather than leaving it to position, which means a result
     * written without one is a record the app cannot produce. Every `log.got(...)` here names this.
     */
    private var lastCall = 0

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

    private fun agent(
        door: Door,
        limits: ContextBudget.Limits? = null,
        // **Zero means wait for ever**, which is what a test that is not about a slow command wants:
        // no test acquires a deadline it did not ask for.
        foregroundMs: Long = 0L,
        bg: Background = Background.none(),
        clock: () -> Long = { System.currentTimeMillis() },
    ) = Agent(
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
        foregroundMs = foregroundMs,
        bg = bg,
        clock = clock,
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
        val door = ScriptedDoor(Reply("done"))

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

        val door = ScriptedDoor(Reply("ok"))
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
            Reply("read it"),
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
            Reply("understood"),
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
                    call("edit", """{"path":"milk.txt","grep":"two","replace":"three"}""")
                ),
            ),
            Reply("could not"),
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
                    call("edit", """{"path":"milk.txt","grep":"two","replace":"three"}""")
                ),
            ),
            Reply("done"),
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
                        """{"path":"project_prompt.txt","grep":"Nothing","replace":"Everything"}""",
                    )
                ),
            ),
            Reply("no"),
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
            Reply("Fix the README too?"),
            Reply("", listOf(call("read", """{"path":"README.md"}"""))),
            Reply("Fixed."),
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
    fun `the agent's own words are the seam a bare yes answers`() {
        // **The bug this exists for survives the tool that used to fix it.** With the burst boundary
        // in the way, a bare "yes" in the next turn answers nothing — because the question was never
        // in the request. It used to be `ask`, a tool whose words became a row; now the model simply
        // says the question, and the note is in the conversation like any other prose.
        val door = ScriptedDoor(Reply("Shall I also fix the README?"))
        val first = agent(door).burst("rename the milk file")

        assertTrue("it concluded", first is Agent.End.Concluded)
        assertEquals(
            "with its words",
            "Shall I also fix the README?",
            (first as Agent.End.Concluded).said,
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
    fun `words without a call are a conclusion, not a stopped session`() {
        // **The operator's ruling, and the better one.** A model that answered in prose has answered;
        // stopping the agent over the shape of its reply punishes the person for the model's grammar.
        // It used to stop with `LOOP`, which is now only for a turn that cannot end at all.
        val door = ScriptedDoor(Reply("I have nothing to do."))
        val end = agent(door).burst("hello")

        assertTrue("concluded: $end", end is Agent.End.Concluded)
        assertEquals("with what it said", "I have nothing to do.", (end as Agent.End.Concluded).said)
        assertEquals("and it was asked once, not twice", 1, door.asked.size)
        assertTrue("the words are the agent's own text in the context", log.contextRows().any {
            it.kind == AgentLog.NOTE && it.name == "agent" && it.body.contains("nothing to do")
        })
        // **One row carries the ending, and it is the agent's own.** The `CONCLUDE` row that used
        // to sit beside it said the same paragraph a second time, for the box alone.
        assertEquals(
            "the ending is the agent's own words, once",
            1,
            rows().count { it.kind == AgentLog.NOTE && it.name == "agent" },
        )
        assertFalse("and nothing was stopped", rows().any { it.name == "stopped" })
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
        // The guard is on *unproductive* calls, not on turns: a turn that keeps working is
        // allowed to keep working, and a turn limit would stop every healthy session.
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
    fun `a keepalive ping sends the conversation and writes nothing into it`() {
        // **The whole economy of the feature.** A ping is the conversation the person will add to,
        // sent again so the provider's cache is warm — so it must not add a row to the context, or
        // the next ping would be a different request and every one of them a cold prompt.
        val door = ScriptedDoor(Reply("nothing to do"))
        val agent = agent(door)
        agent.burst("look at it")

        val before = log.contextRows()
        val pings = (1..3).map { agent.warm() }

        assertTrue("every ping was sent: $pings", pings.all { it is Agent.Warm.Sent })
        assertEquals("three pings, no turns", 3, door.pings.size)
        assertEquals("and not one question asked", 1, door.asked.size)

        // **The same request every time.** One that added anything — a note, a timestamp, the
        // ping's own row — would be a miss for ever after, and the second ping would pay full price.
        assertEquals("the conversation as it stood", before.size, log.contextRows().size)
        assertEquals(
            "and the first ping sent exactly what the last one did",
            door.pings.first().messages.map { it.role to it.text },
            door.pings.last().messages.map { it.role to it.text },
        )

        // The evidence still shows the checks — that is the only place a ping is visible.
        val evidence = rows()
        assertEquals(
            "each ping left exactly one note",
            3,
            evidence.count { it.kind == AgentLog.NOTE && it.name == AgentChat.PING },
        )
        assertEquals(
            "and nothing of it reached the context",
            0,
            log.contextRows().count { it.name == AgentChat.PING },
        )
    }

    @Test
    fun `a wake is a turn, and the request says what finished and where`() {
        // **The one turn the app takes without being spoken to.** Its own work coming back is worth a
        // request, and the model has to be told: the conversation it already answered, plus one
        // sentence naming the file, is the whole difference between "do something" and "answer me
        // again the same way".
        val door = ScriptedDoor(
            Reply("started it"),
            Reply("", listOf(call("read", """{"path":"agent/background_results/tree-c9.txt"}"""))),
            Reply("three files, all fine"),
        )
        val agent = agent(door)
        agent.burst("look at everything")
        // The file the wake names really exists, so the read in the wake's turn is a read.
        ws.write(
            File(ws.projectDir("shopping"), "agent/background_results/tree-c9.txt"),
            "0\n3 files: milk.txt, bread.txt, eggs.txt",
        )
        val talked = door.asked.size

        val end = agent.wake(
            "A background command has finished: agent/background_results/tree-c9.txt (tree). " +
                "Read the result and carry on, or conclude if there is nothing to do.",
        )

        assertTrue("it took a turn: $end", end is Agent.End.Concluded)
        assertEquals("with the words it concluded", "three files, all fine", (end as Agent.End.Concluded).said)
        assertEquals("two requests: the read, then the conclusion", talked + 2, door.asked.size)
        assertTrue("and no ping was involved", door.pings.isEmpty())

        // **The note is in the request as a user message, at the end.** The system message cannot
        // carry it without rewriting the first bytes of a conversation that was already warm.
        val asked = door.asked[talked]
        assertEquals(
            "the last message is the app's own",
            "user" to "A background command has finished: agent/background_results/tree-c9.txt (tree). " +
                "Read the result and carry on, or conclude if there is nothing to do.",
            asked.messages.last().role to asked.messages.last().text,
        )
        assertTrue(
            "and nothing was put in the person's mouth",
            log.contextRows().none { it.kind == AgentLog.TOLD && it.body.contains("background command") },
        )
        assertTrue(
            "and the read it made found the result: ${gotRows().last { it.name == "read" }.body}",
            gotRows().last { it.name == "read" }.body.contains("3 files: milk.txt"),
        )
    }

    @Test
    fun `a ping that fails does not end the session and says nothing in the context`() {
        // A provider that will not take a one-token answer, a tunnel, a Doze window: none of them
        // is the person's work failing. The ping is missed and the next tick tries again.
        val door = object : Door {
            override fun turn(request: Request) = Result.success(Reply("nothing to do"))
            override fun ping(request: Request) =
                Result.failure<Unit>(IllegalStateException("OpenRouter answered 400"))
        }
        val warm = agent(door).warm()

        assertTrue("failed, and not on the ladder: $warm", warm is Agent.Warm.Failed)
        assertFalse("(nothing here is the person's to fix)", (warm as Agent.Warm.Failed).stopping)
    }

    // ---- the instructions, and the default that changed ----------------------

    @Test
    fun `an instructions file that exists is the person's, word for word`() {
        // **No list of the texts the app used to ship.** The app is being developed, not released,
        // so a file on the phone was written by this build or by a person — and comparing it
        // against half-remembered defaults would rewrite somebody's instructions to fix a phon
        // that does not exist.
        val file = File(File(account, Workspace.SYSTEM_PROJECT), AgentPrompt.SYSTEM_FILE)
        val theirs = "Work only in src/. Never touch the README.\n"
        file.writeText(theirs)

        val read = AgentPrompt.system(account)

        assertEquals("handed over as it is", theirs, read)
        assertEquals("and left alone on disk", theirs, file.readText())
    }

    @Test
    fun `a missing instructions file is written from the default, once`() {
        // The one thing the file system has to do here: a fresh account has no instructions, and an
        // agent with none is an agent inventing its own rules. **The file is in the system project**
        // — the one place the prompt lives, with no second path to fall back to.
        val file = File(File(account, Workspace.SYSTEM_PROJECT), AgentPrompt.SYSTEM_FILE)
        file.delete()

        assertEquals("the shipped text", AgentPrompt.SYSTEM_DEFAULT, AgentPrompt.system(account))
        assertEquals("and it is on disk now", AgentPrompt.SYSTEM_DEFAULT, file.readText())

        // **And an emptied file is a missing one.** A save that wrote nothing is not a person saying
        // the agent has no rules.
        file.writeText("   \n")
        assertEquals("still instructioned", AgentPrompt.SYSTEM_DEFAULT, AgentPrompt.system(account))
    }

    @Test
    fun `the default describes the record this build writes`() {
        // The prompt is the only place the agent is told where its memory lives, so the words have
        // to match the files: two of them, oldest line first, and the columns each one has.
        val now = AgentPrompt.SYSTEM_DEFAULT

        assertTrue("both files are named", now.contains("context.log") && now.contains("session.log"))
        assertTrue("with the context's own columns", now.contains("id size name body"))
        assertTrue("and the order they are written in", now.contains("oldest line first"))
        assertFalse("and no projection key that read no longer takes", now.contains("`raw`"))
    }

    // ---- a command that outlasts the wait ------------------------------------

    @Test
    fun `a command that outlasts the wait is handed over, and says when its result will arrive`() {
        // **Report 13, in the operator's own words:** "when command takes more than the timeout,
        // agent gets a result that tells that this command takes longer than the timeout allows and
        // will be returned on one of the next turns with other call results".
        //
        // What the app used to say was "stopped after 60 s; nothing was kept" - the app treating a
        // limit on how long it *waits* as a limit on the work. Here the limit has already run out
        // when the command starts, so the first check inside the walk expires and the handover is
        // what the turn is told about.
        val bg = Background.inline()
        val door = ScriptedDoor(
            Reply("", listOf(Call("c1", "tree", """{"path":""}"""))),
            Reply("done"),
        )
        // **The clock is the test's, and it jumps after the deadline is made.** `Deadline` reads the
        // clock once when it is constructed - that is "when the wait started" - so the jump has to
        // happen on a *later* read, or the deadline would begin already expired and the test would
        // be proving nothing. Two reads is exactly the shape of it: start, then a check that is two
        // seconds late.
        var reads = 0
        val clock = {
            // One jump, and it stays jumped: `expired` and the sentence's `expiredIn()` read the
            // clock separately, and a clock that went back would make the two disagree.
            if (reads++ == 0) 1_000_000L else 1_002_000L
        }
        val slow = agent(door, foregroundMs = 1_000L, bg = bg, clock = clock)

        slow.burst("go")

        val got = gotRows().first { it.name == "tree" }.body
        assertTrue("it says the command is still running: $got", "still running" in got)
        assertTrue("and names the limit it passed: $got", "after 1 s" in got)
        assertTrue("and where the result will be: $got", "background_results/tree-c1.txt" in got)
        assertTrue(
            "and that it arrives with other results, not alone: $got",
            "next turns with other call results" in got,
        )
        assertFalse("it does not claim the work was thrown away: $got", "nothing was kept" in got)
        // The file exists from the moment the work starts, which is what makes the path worth naming.
        assertTrue("and the result file is there to read", File(ws.projectDir("shopping"), "agent/background_results/tree-c1.txt").isFile)
    }

    @Test
    fun `a folder delete that ran out of time is not handed over, because part of it is gone`() {
        // **The one act that cannot be repeated.** A tree removal walks, so a stopped one has
        // removed part of the folder - and running it again would be a second delete of paths that
        // are already gone, reported as if it were the interrupted one. It keeps the sentence that
        // says part of it is already gone.
        val door = ScriptedDoor(
            Reply("", listOf(Call("c1", "delete", """{"path":"Shopping"}"""))),
            Reply("done"),
        )
        File(ws.projectDir("shopping"), "Shopping/inner.txt").apply { parentFile?.mkdirs() }.writeText("x")
        // The same clock: it is read once to start the deadline, then late on every check - so the
        // walk stops before its first entry, and this is the act that must NOT be handed over.
        var reads = 0
        val clock = {
            if (reads++ == 0) 1_000_000L else 1_002_000L
        }
        val slow = agent(door, foregroundMs = 1_000L, clock = clock)

        slow.burst("go")

        val got = gotRows().first { it.name == "delete" }.body
        assertFalse("it is not claimed to be running: $got", "still running" in got)
    }

    // ---- the key that is sent ------------------------------------------------

    @Test
    fun `the key a person stored is the key in the header`() {
        // **Report 9, and the reason it could stay open for four builds.** "The openrouter key isn't
        // passing through" is a claim about a header, and the header was built inside a method that
        // opens a socket - so the only way to answer the report was to read the source and believe
        // it. Reading is not evidence. This asks the door what it would send, which is now a
        // question it can answer without sending anything.
        val door = OpenRouterDoor(
            key = { "sk-or-v1-the-persons-key" },
            model = "deepseek/deepseek-v4.1-flash-1017",
            provider = "decart/fp4",
            effort = "high",
        )

        val headers = door.headers("sk-or-v1-the-persons-key").toMap()

        assertEquals("the token is a bearer token", "Bearer sk-or-v1-the-persons-key", headers["Authorization"])
        assertEquals("and the body is said to be JSON", "application/json", headers["Content-Type"])
        assertEquals("and the answer is asked for as JSON", "application/json", headers["Accept"])
        assertEquals("three headers, so nothing is added unnoticed", 3, headers.size)
    }

    @Test
    fun `a key with a newline in it cannot break the header`() {
        // **A header value is one line.** A token with a line break in it - from a paste, or from a
        // file with Windows endings - would either throw inside `setRequestProperty` or, on a
        // laxer stack, end the header and start another. The refusal happens before the socket is
        // opened, because the sentence a person reads should not depend on which stack they run.
        val door = OpenRouterDoor(
            key = { "sk-or-v1-abc\r\nX-Injected: yes" },
            model = "deepseek/deepseek-v4.1-flash-1017",
            provider = "decart/fp4",
            effort = "high",
        )

        val refusal = assertThrows(IllegalArgumentException::class.java) {
            door.headers("sk-or-v1-abc\r\nX-Injected: yes")
        }

        assertTrue("it says what is wrong", "line break" in refusal.message.orEmpty())
        assertTrue("and what to do", "Copy it again" in refusal.message.orEmpty())
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
    fun `a ping's body is the turn's body with one token asked for, and nothing else`() {
        // **The cache, stated as a fact about the bytes.** A provider matches the longest common
        // prefix, so everything except the answer's size has to be the same request — the tools, the
        // pin, the reasoning flag, the messages. One field added and one field changed is the whole
        // difference, and `max_tokens` is the changed one because zero is not a value endpoints take.
        val door = OpenRouterDoor(
            key = { "unused-by-this-test" },
            model = "deepseek/deepseek-v4.1-flash-1017",
            provider = "decart/fp4",
            effort = "high",
        )
        val request = Request(
            model = "deepseek/deepseek-v4.1-flash-1017",
            provider = "decart/fp4",
            effort = "high",
            system = "the instructions",
            messages = listOf(Msg.user("a task"), Msg.assistant("done")),
            tools = AgentTools.specs(),
        )

        val turn = door.body(request)
        val ping = door.body(request, OpenRouterDoor.PING_TOKENS)

        assertFalse("a turn asks for no size of its own", turn.has("max_tokens"))
        assertEquals("a ping asks for exactly one token", 1, ping.getInt("max_tokens"))
        val keys = turn.keys().asSequence().toSet()
        assertEquals(
            "and adds nothing else to the request",
            keys + "max_tokens",
            ping.keys().asSequence().toSet(),
        )
        for (key in keys) {
            assertEquals(
                "$key is the same on both, or the prompt is not the one being warmed",
                turn.get(key).toString(),
                ping.get(key).toString(),
            )
        }
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
            val answer = tool(spec.name, """{"path":"no-such-thing"}""")
            assertFalse(
                "${spec.name} is answered rather than unknown: $answer",
                answer.contains("There is no tool called"),
            )
        }
    }

    @Test
    fun `no key is accepted without being described`() {
        // **A key only the whitelist knows is a key found by guessing.** `raw` and `id` were keys
        // `read` took while its example named neither, and the example is what a model copies; the
        // description is where it learns the rest. Every key a command takes has to be visible to
        // the model in the spec it is sent — as a backticked name or as a field of the example.
        for (spec in AgentTools.specs()) {
            val keys = AgentTools.KEYS[spec.name] ?: continue
            val taught = spec.description + " " + spec.parameters.keys().asSequence().joinToString(" ")
            val silent = keys.filterNot { taught.contains("`$it`") || spec.parameters.has(it) }
            assertTrue("${spec.name} accepts no undescribed key: $silent", silent.isEmpty())
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
    fun `read with grep answers with the text the pattern matched, and nothing around it`() {
        // **Reported from the live log, three times.** "grep only gives lines!!! why not precise
        // selection!? it has freaking regex in the name", then "when you use grep as an argument to
        // edit the line numbers get passed as well — they shouldn't". A pattern selects text, so
        // the answer is the text: no line, no number, no window cut around it. That is also what
        // makes the answer pasteable into `edit`'s `replace`.
        ws.write(File(ws.repoOf("shopping"), "milk.txt"), "first\ntwo litres\nthird\n")

        val body = tool("read", """{"path":"milk.txt","grep":"litres"}""")

        assertEquals("the match itself, alone", "0\nlitres", body)
    }

    @Test
    fun `a pattern that matches a whole line answers with the line and its number`() {
        // **The shape follows from the pattern.** `^milk$` is a question about a line, so the
        // answer is that line with its number — the model is about to name a place. `milk` is a
        // question about text, so the answer is the text — the model is about to paste it. Asking
        // for a key would be asking the model to classify its own pattern, which is the choice
        // `find` got wrong three times in the live log.
        ws.write(File(ws.repoOf("shopping"), "milk.txt"), "first\nmilk\nthird\n")

        val body = tool("read", """{"path":"milk.txt","grep":"^milk$"}""")

        assertEquals("the whole line, and where it is", "0\n2: milk", body)
    }

    @Test
    fun `a pattern that matches part of a line answers with the matched text`() {
        ws.write(File(ws.repoOf("shopping"), "milk.txt"), "first\ntwo litres\nthird\n")

        val body = tool("read", """{"path":"milk.txt","grep":"litres"}""")

        assertEquals("the match alone, with no number to paste", "0\nlitres", body)
    }

    @Test
    fun `every match on a line is selected, not just the first`() {
        // A selector that answers one of the three numbers in a line is the "grep only gives
        // lines" complaint wearing a different hat: the pattern said what it wanted, and the
        // command answered part of it.
        ws.write(File(ws.repoOf("shopping"), "n.txt"), "a1 b22 c333\n")

        val body = tool("read", """{"path":"n.txt","grep":"\\d+"}""")

        assertEquals("all three", "0\n1\n22\n333", body)
    }

    @Test
    fun `the record is read as the file holds it, columns and all`() {
        // **There is no second view any more.** `read` answers with the file, so the agent sees the
        // same characters a person sees: `id size kind ctx name body`. It used to be a projection of
        // `id name body`, because two numbers side by side were thought to invite forgetting the
        // wrong row — but a projection the model cannot check is worse than a column it can ignore,
        // and `grep` needs the real columns to find a row by cost.
        log.told("make a list")
        val call = (log.call("read", """{"path":"milk.txt"}""")).also { lastCall = it }
        log.got("read", "0\ntwo litres", answers = lastCall)

        val body = tool("read", """{"path":"agent/session.log"}""")

        assertTrue("accepted: $body", body.startsWith(AgentLog.SUCCESS))
        // **The row now begins with its stamp**, so the id is no longer at the start of the line.
        // The assertion is what it always was — the row is findable by its id — and it is written to
        // say where the id sits rather than assuming it is first.
        assertTrue("the id is there: $body", Regex("""^\S+ +$call """, RegexOption.MULTILINE).containsMatchIn(body))
        assertTrue("and the words: $body", body.contains("two litres"))
        assertTrue("the kind, because the file has it: $body", body.contains("CALL"))
        assertTrue("and the ctx column: $body", body.contains(" in  "))
    }

    @Test
    fun `read shows the evidence as the file holds it, so a size is searchable`() {
        // **A file is shown as it is.** No projection, no second view: what `read` returns is what
        // `wc -c` and a person's editor see, so the size column is searchable with the same `grep`
        // that searches anything else.
        log.told("make a list")
        log.got("read", "0\n" + "x".repeat(700), answers = lastCall)

        val raw = tool("read", """{"path":"agent/session.log"}""")
        // A whole-line pattern, so the answer is `number: line` and the columns come with it.
        val searched = tool("read", """{"path":"agent/session.log","grep":".*2\\d\\d.*"}""")

        assertTrue("the columns are there: $raw", raw.contains("GOT"))
        // **A stamp, then the id, then the size.** The size is still the column the pattern finds;
        // the row simply has a time in front of it now, and a pattern anchored to the line's start
        // would have been searching for a shape the file no longer has.
        assertTrue(
            "including the size: $raw",
            Regex("""^\S+ +\d+ +\d+ +GOT""", RegexOption.MULTILINE).containsMatchIn(raw),
        )
        assertTrue("and a size is searchable: $searched", searched.contains("GOT"))
    }

    @Test
    fun `the context file is the rows in the context, without kind or ctx`() {
        // **What the agent reads when it looks at its own memory.** The two columns it lacks are
        // the app's: `kind` rebuilds the conversation, and every row in this file is `in`, so a ctx
        // column would say the same word on every line. A row the app wrote for itself never enters
        // it — that is what keeps a repeated poll out of the request.
        log.told("a task")
        val kept = log.got("read", "0  one file", answers = lastCall)
        val out = log.note("agent", "a note")
        log.bookkeeping(AgentLog.TURN, "turn", "1")
        log.forget(setOf(out))

        val body = tool("read", """{"path":"agent/context.log"}""")

        assertTrue("the row that is in: $body", body.contains("one file"))
        assertFalse("the row the agent forgot is gone: $body", body.contains("a note"))
        assertFalse("the app's own row was never there: $body", body.contains("TURN"))
        assertFalse("and no kind column: $body", body.contains("GOT"))
        assertFalse("and no ctx column: $body", body.contains(" in  "))
        assertTrue(
            "the id and the size stay: $body",
            Regex("^\\s*$kept +\\d+ ", RegexOption.MULTILINE).containsMatchIn(body),
        )
    }

    @Test
    fun `an id is not a key read takes, because a line is what a file has`() {
        // It was a key while the record was newest-first and a line number moved under it. The
        // files are appended to now, so a line is a handle again — and a key that is not in the
        // whitelist has to be refused by name rather than ignored, which is the `lines` mistake.
        log.told("a task")

        val body = tool("read", """{"path":"agent/session.log","id":3}""")

        assertTrue("refused: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("naming the key it did not take: $body", body.contains("id"))
        assertTrue("and what read does take: $body", body.contains("path"))
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
    fun `grep is applied inside the range, so the selection is what the range holds`() {
        // **The order is the whole contract, and it is unchanged.** The range says which part of the
        // file you are looking at; the pattern says what in that part is selected. What the answer
        // no longer carries is a position, so the proof is which text came back rather than which
        // number it was printed beside.
        ws.write(File(ws.repoOf("shopping"), "milk.txt"), "milk\nbread\nmilk again\neggs\nmilk last\n")

        // **`again` is part of the line, not of the match**, so this is a part-of-line pattern and
        // the answer is the text it selected.
        val body = tool("read", """{"path":"milk.txt","from":2,"to":4,"grep":"again"}""")

        assertEquals("only the one inside the window", "0\nagain", body)
    }

    @Test
    fun `a range that ends before it starts is refused, and says which is which`() {
        ws.write(File(ws.repoOf("shopping"), "milk.txt"), "one\ntwo\n")

        val body = tool("read", """{"path":"milk.txt","from":3,"to":1}""")

        assertTrue("refused: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("naming both numbers: $body", body.contains("3") && body.contains("1"))
    }

    // ---- one pattern, one meaning, in every command --------------------------

    @Test
    fun `an anchored pattern finds the same line in tree and in read`() {
        // **The bug this replaces.** `tree` tested the whole file before testing the lines, and `^`
        // only matches at the start of what it is given — so a file whose *second* line was `milk`
        // was skipped by a search and found by a read, with nothing in either answer to say why.
        val file = File(ws.repoOf("shopping"), "list.txt")
        ws.write(file, "bread\nmilk\neggs\n")

        val found = tool("tree", """{"path":"","grep":"^milk$"}""")
        val read = tool("read", """{"path":"list.txt","grep":"^milk$"}""")

        // `tree` answers where it is; `read` answers where it is too, because `^milk$` matches a
        // whole line and a whole-line question is a question about a place.
        assertTrue("the search finds it: $found", found.contains("list.txt:2: milk"))
        assertEquals("and the read agrees", "0\n2: milk", read)
    }

    @Test
    fun `a pattern ignores case in any script, not only in ascii`() {
        // `CASE_INSENSITIVE` alone folds US-ASCII, so an accented letter kept its case and the
        // description's one rule was two. The **accent** is what the test has to change case on:
        // `kraków` against `Kraków` is matched by ASCII folding alone and would prove nothing.
        val file = File(ws.repoOf("shopping"), "places.txt")
        ws.write(file, "Kraków\nZürich\n")

        val lower = tool("read", """{"path":"places.txt","grep":"KRAKÓW"}""")
        val upper = tool("read", """{"path":"places.txt","grep":"zÜRICH"}""")

        // Both patterns match a whole line, so both answers are `number: line`.
        assertEquals("the accented name is found either way", "0\n1: Kraków", lower)
        assertEquals("and so is the umlaut", "0\n2: Zürich", upper)
    }

    @Test
    fun `a pattern with a line break in it is refused, and points at the range`() {
        // No line contains a line break, so this pattern can only ever answer "nothing matches" about
        // a file that plainly holds the text — and the model would go looking for a mistake in its
        // path. Naming the tool that does what it wanted is what repairs the call.
        val body = tool("read", """{"path":"milk.txt","grep":"two\nlitres"}""")

        assertTrue("refused: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("saying why: $body", body.contains("line break"))
        assertTrue("and what to use instead: $body", body.contains("from"))
    }

    @Test
    fun `a search can answer with a folder's name, and a content search never can`() {
        // **A folder is a name, not a text.** `{"name":"agent"}` used to answer nothing, which reads
        // as "there is no such folder". `grep` stays files-only: a folder would be in the result
        // because something inside it matched, and that is a line nobody asked about.
        ws.write(File(ws.repoOf("shopping"), "notes/inner.txt"), "a needle here")

        val named = tool("tree", """{"path":"","name":"^notes$"}""")
        val grepped = tool("tree", """{"path":"","grep":"needle"}""")

        assertTrue("the folder is named: $named", named.lines().any { it.trim() == "notes/" })
        assertTrue("and the hit is a file and a line: $grepped", grepped.contains("notes/inner.txt:1: a needle here"))
        assertFalse(
            "but no bare folder rides along with a content search: $grepped",
            grepped.lines().any { it.trim().endsWith("/") },
        )
    }

    @Test
    fun `a hit in a very long line is selected whole, with no window around it`() {
        // **There is no excerpt any more.** The old answer cut a two-hundred-character window
        // around the match and marked the cut with an ellipsis, which meant a match could arrive
        // with characters the file does not contain beside it — and a `replace` built from that
        // answer wrote the ellipsis and the window into the file. A selector answers with the
        // match; how long the line was is `read from`/`to`'s business.
        val file = File(ws.repoOf("shopping"), "long.txt")
        ws.write(file, "x".repeat(250) + "NEEDLE" + "y".repeat(250))

        val body = tool("read", """{"path":"long.txt","grep":"NEEDLE"}""")

        assertEquals("the match, and only the match", "0\nNEEDLE", body)
    }

    @Test
    fun `a range written as text is read, and one that is not a number names its key`() {
        // **`optInt` answered the default on the phone and cast on the JVM**, so one call had two
        // behaviours and neither was visible from the other side. Every number is read from the
        // value now, and a number written as a string is a number.
        ws.write(File(ws.repoOf("shopping"), "milk.txt"), "one\ntwo\nthree\nfour\n")

        assertEquals("0\ntwo\nthree", tool("read", """{"path":"milk.txt","from":"2","to":"3"}"""))
        val bad = tool("read", """{"path":"milk.txt","from":"two"}""")
        assertTrue("refused: $bad", bad.startsWith(AgentLog.FAILURE))
        assertTrue("naming the key: $bad", bad.contains("`from`"))
        assertTrue("and what it must be: $bad", bad.contains("whole number"))
    }

    @Test
    fun `a link out of the project is not followed, in a listing or a search`() {
        // **`resolve` refuses `..` for a path a model wrote; a link is the same escape with no path
        // to check.** The rule has to hold for every entry a walk meets, or a search reads files the
        // instructions promise the agent cannot see.
        val outside = File(account, "elsewhere").apply { mkdirs() }
        ws.write(File(outside, "secret.txt"), "a needle outside the project")
        val link = File(ws.repoOf("shopping"), "escape")
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (e: Throwable) {
            return // a filesystem that cannot make one cannot be escaped through one either
        }

        val listed = tool("tree", """{"path":""}""")
        val found = tool("tree", """{"path":"","grep":"needle"}""")

        assertFalse("the link is not listed: $listed", listed.contains("escape"))
        assertFalse("and nothing behind it is read: $found", found.contains("needle"))
    }

    @Test
    fun `every command that changes something refuses the project's own folder`() {
        // **`{"path":""}` resolved to the project and `delete` removed it** — `.git`, the record and
        // every file, and then answered with a refusal about the history it could not write. `.` was
        // the same path by another name. A command that needs a file says so before it does anything.
        val before = File(ws.repoOf("shopping"), "milk.txt").readText()

        for (name in listOf("edit", "delete", "create", "move", "restore", "history")) {
            for (path in listOf("", ".")) {
                val body = tool(name, """{"path":"$path"}""")
                assertTrue(
                    "$name with \"$path\" is refused: $body",
                    body.startsWith(AgentLog.FAILURE),
                )
                assertTrue(
                    "$name with \"$path\" says what is wrong: $body",
                    body.contains("project's own folder"),
                )
            }
        }
        assertTrue("and the project is still there", File(ws.repoOf("shopping"), "milk.txt").isFile)
        assertEquals("as it was", before, File(ws.repoOf("shopping"), "milk.txt").readText())
    }

    @Test
    fun `a background command whose arguments are a string runs them`() {
        // `args` as a string used to be read as "no arguments at all", so the command ran against the
        // project's own folder and answered about a folder — a call that quietly dropped what it was
        // told, which is the same class of mistake as a wrong key.
        val body = tool(
            "background",
            """{"tool":"read","args":"{\"path\":\"milk.txt\"}"}""",
        )

        assertTrue("accepted: $body", body.startsWith(AgentLog.SUCCESS))
        val made = Regex("agent/background_results/read-\\S+\\.txt")
            .find(body)
            ?.value
            ?.let { File(ws.repoOf("shopping"), it) }
        assertTrue("with the path it was written to: $body", made != null)
        val wrote = made?.readText().orEmpty()
        assertTrue("and the work ran with the arguments it was given: $wrote", wrote.contains("two litres"))
    }

    @Test
    fun `the clock is asked inside a match, so one long line cannot outlast the deadline`() {
        // **A pattern is the one input that can take unbounded time in a single call.** Walking a
        // folder asks the deadline per entry and a read asks it per chunk, but a match is one call —
        // so the text reaches the engine through a view that asks the clock from inside `charAt`.
        //
        // The pattern here is linear and finds nothing, which is the point: the guarantee is about
        // the *call*, not about which patterns a given engine happens to find hard. Naming a
        // catastrophic pattern would be testing the JVM's optimiser — `(a+)+$` on forty characters
        // returns in a millisecond on this JDK and hangs on others.
        val expired = Deadline(1)
        Thread.sleep(10)
        val line = "a".repeat(50_000)

        var stopped = false
        try {
            tools().hit(line, Regex("a+$"), expired, "read")
        } catch (e: StoppedException) {
            stopped = true
        }

        assertTrue("the deadline was asked mid-match", stopped)
    }

    @Test
    fun `history with a pattern shows the line that matched, not the first line`() {
        val file = File(ws.repoOf("shopping"), "milk.txt")
        ws.write(file, "intro\ntwo litres of bread\noutro")
        git.commit("shopping", CommitMessage.SAVE)

        val body = tool("history", """{"path":"milk.txt","grep":"bread"}""")

        assertTrue("the line that matched: $body", body.contains("two litres of bread"))
        assertFalse("and not the line above it: $body", body.contains("intro"))
    }

    // ---- the edges of the commands a model writes by hand ---------------------

    @Test
    fun `depth is read as a number and clamped, so a silly one still lists something`() {
        // A model writes `"depth":"2"` and `"depth":99`. The first is a number it typed as text, and
        // the second is a request to walk the phone: the answer is a listing, not a refusal.
        ws.write(File(ws.repoOf("shopping"), "a/b/c/deep.txt"), "deep")

        val text = tool("tree", """{"path":"","depth":"3"}""")
        val silly = tool("tree", """{"path":"","depth":99}""")

        assertTrue("a depth written as text is read: $text", text.contains("a/b/c/"))
        assertTrue("and a huge one is clamped rather than refused: $silly", silly.startsWith(AgentLog.SUCCESS))
    }

    @Test
    fun `create will not write over a file, and says what is there`() {
        ws.write(File(ws.repoOf("shopping"), "milk.txt"), "two litres")

        val body = tool("create", """{"path":"milk.txt","text":"three"}""")

        assertTrue("refused: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("naming the path: $body", body.contains("milk.txt"))
        assertEquals("and nothing was written", "two litres", File(ws.repoOf("shopping"), "milk.txt").readText())
    }

    @Test
    fun `at zero inserts at the top of an empty file`() {
        ws.write(File(ws.repoOf("shopping"), "blank.txt"), "")

        val body = tool("edit", """{"path":"blank.txt","at":0,"replace":"first line"}""")

        assertTrue("accepted: $body", body.startsWith(AgentLog.SUCCESS))
        // An empty file is one empty line, so inserting above it keeps the newline that line ends
        // with — the file gains a line and loses none.
        assertEquals("and the line is the file", "first line\n", File(ws.repoOf("shopping"), "blank.txt").readText())
    }

    @Test
    fun `history of a file that has never been saved says so instead of refusing`() {
        ws.write(File(ws.repoOf("shopping"), "fresh.txt"), "not committed yet")

        val body = tool("history", """{"path":"fresh.txt"}""")

        assertTrue("accepted: $body", body.startsWith(AgentLog.SUCCESS))
        assertTrue("and it says what is true: $body", body.contains("no versions yet"))
    }

    @Test
    fun `a folder cannot be moved into itself, and the answer names the rule`() {
        ws.write(File(ws.repoOf("shopping"), "notes/inner.txt"), "x")

        val body = tool("move", """{"path":"notes","into":"notes"}""")

        assertTrue("refused: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("for the reason it happened: $body", body.contains("inside itself"))
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

        val body = tool("edit", """{"path":"milk.txt","grep":"one","at":1,"replace":"x"}""")

        assertTrue("refused: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("and it says why: $body", body.contains("one of"))
        assertEquals("with nothing changed", "one\ntwo", ws.read(File(ws.repoOf("shopping"), "milk.txt")))
    }

    @Test
    fun `edit replaces every match of a pattern, not a literal`() {
        // **The key was called `find` and behaved like one.** `before.replace(find, put)` took a
        // literal, so a model that wrote a regular expression had its metacharacters matched as
        // characters — and the description promised "wherever it appears", which a literal cannot
        // do for a pattern.
        ws.write(File(ws.repoOf("shopping"), "n.txt"), "a1 b22 c333\n")

        val body = tool("edit", """{"path":"n.txt","grep":"\\d+","replace":"N"}""")

        assertTrue("accepted: $body", body.startsWith(AgentLog.SUCCESS))
        assertEquals("every number, each replaced", "aN bN cN\n", ws.read(File(ws.repoOf("shopping"), "n.txt")))
    }

    @Test
    fun `edit can narrow its pattern to a range of lines`() {
        // **Reported from the live log:** "grep and from-to cant be used together (should have been
        // grep within from-to selection)". One selection with a bound is not two edits: the range
        // says where to look and the pattern says what to change, exactly as `read` composes them.
        val file = File(ws.repoOf("shopping"), "milk.txt")
        ws.write(file, "milk\nbread\nmilk again\neggs\nmilk last\n")

        val body = tool(
            "edit",
            """{"path":"milk.txt","from":2,"to":4,"grep":"milk","replace":"WATER"}""",
        )

        assertTrue("accepted: $body", body.startsWith(AgentLog.SUCCESS))
        assertEquals(
            "only the line inside the range changed",
            "milk\nbread\nWATER again\neggs\nmilk last\n",
            ws.read(file),
        )
    }

    @Test
    fun `a whole-line answer pasted back as a replacement loses its number`() {
        // The whole-line shape is `2: milk`, and the obvious next move is to paste it. Writing it
        // verbatim would put the number in the file — the same bug as before, arriving by the other
        // shape. The number is stripped only for a whole-line replacement, so a numbered list pasted
        // in as a part-of-line replacement keeps its numbers.
        val file = File(ws.repoOf("shopping"), "milk.txt")
        ws.write(file, "first\nmilk\nthird\n")

        val selected = tool("read", """{"path":"milk.txt","grep":"^milk$"}""")
        assertEquals("the whole-line answer", "0\n2: milk", selected)
        val text = selected.substringAfter('\n')

        // The pasted answer, with the word changed — so the assertion can tell "stripped the
        // number" from "wrote the same thing back and changed nothing".
        val pasted = text.replace("milk", "WATER")
        val args = org.json.JSONObject()
            .put("path", "milk.txt")
            .put("grep", "^milk$")
            .put("replace", pasted)
        val body = tool("edit", args.toString())

        assertTrue("accepted: $body", body.startsWith(AgentLog.SUCCESS))
        assertEquals("the file has the word, not the number", "first\nWATER\nthird\n", ws.read(file))
    }

    @Test
    fun `a replacement built from a grep answer carries no line number into the file`() {
        // **Reported from the live log:** "when you use grep as an argument to edit the line numbers
        // get passed as well — they shouldn't". The old answer was `2: litres`, so pasting it wrote
        // the number. The selector answers with the match now, and this is the test that keeps the
        // two ends agreeing: what `read grep` gives is exactly what `edit replace` can take.
        val file = File(ws.repoOf("shopping"), "milk.txt")
        ws.write(file, "first\ntwo litres\nthird\n")

        // **A part-of-line pattern**, because that is the shape whose answer is meant to be
        // pasted: `two litres` as a whole line would be answered `2: two litres`, and the
        // whole-line case has its own test below.
        val selected = tool("read", """{"path":"milk.txt","grep":"litres"}""")
        // The body's second line is the selection, with the status line above it — the same
        // "0 or 1, then the answer" shape every result has.
        assertEquals("what the selector answered", "0\nlitres", selected)
        val text = selected.substringAfter('\n')

        val args = org.json.JSONObject()
            .put("path", "milk.txt")
            .put("grep", "litres")
            .put("replace", text)
        val body = tool("edit", args.toString())

        assertTrue("accepted: $body", body.startsWith(AgentLog.SUCCESS))
        assertEquals("the file says the word, not a number", "first\ntwo litres\nthird\n", ws.read(file))
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

        val edited = tool("edit", """{"path":"milk.txt","grep":"one","replce":"three"}""")
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
    fun `a tool that no longer exists is refused like any unknown name, and the turn carries on`() {
        // **The bug this came from is gone with the tool.** `conclude {"txt":"done"}` used to fall
        // back to the reply's own prose and end the turn — an ending nobody asked for, described by
        // a sentence nobody wrote. There is no `conclude` at all now, so a model that still calls it
        // is calling an unknown tool, which is a refusal like any other and the turn carries on.
        val door = ScriptedDoor(
            Reply("", listOf(call("conclude", """{"txt":"done"}"""))),
            Reply("all done"),
        )

        val end = agent(door).burst("do the thing")

        assertEquals(
            "the words are what ended it",
            "all done",
            (end as Agent.End.Concluded).said,
        )
        val got = gotRows().first()
        assertTrue("and the unknown tool was refused: ${got.body}", got.body.contains("no tool called"))
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
        val call = (log.call("read", """{"path":"milk.txt"}""")).also { lastCall = it }
        val got = log.got("read", "0\ntwo litres", answers = lastCall)
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
        (log.call("tree", "{}")).also { lastCall = it }
        log.got("tree", "0\nnothing", answers = lastCall)
        val task = log.told("task two")

        val answer = forget(task)

        assertTrue("refused: $answer", answer.startsWith(AgentLog.FAILURE))
        assertTrue("and it says which rule: $answer", answer.contains("newest task"))
        assertTrue("the row is still in", log.contextRows().any { it.id == task })
    }

    @Test
    fun `a call and its result are forgotten together`() {
        log.told("a task")
        val call = (log.call("tree", "{}")).also { lastCall = it }
        val got = log.got("tree", "0\nnothing", answers = lastCall)

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
        val call = (log.call("read", """{"path":"milk.txt"}""")).also { lastCall = it }
        val got = log.got("read", "0\ntwo litres", answers = lastCall)
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
        val inFlight = (log.call("forget", """{"ids":[]}""")).also { lastCall = it }

        val answer = forget(inFlight)

        assertTrue("refused: $answer", answer.startsWith(AgentLog.FAILURE))
        assertTrue("and it says why: $answer", answer.contains("making now"))
    }

    @Test
    fun `a forget takes a range, because that is how a person says twelve rows`() {
        // **A range is the shape the work actually has.** Clearing up is never one row at a time:
        // it is "everything I did before this task", which is a block of ids. Writing them out costs
        // a line of JSON the model has to count, and counting is where a list gets one row wrong —
        // and a call/tool pair with its halves split is refused by the pair rule.
        log.told("task one")
        val pairs = (1..6).map {
            val c = (log.call("read", """{"path":"milk.txt"}""")).also { lastCall = it }
            log.got("read", "0\ntwo litres", answers = lastCall)
            c to log.rows().last().id
        }
        log.told("task two")

        // The first two pairs, named as one range: 2-7 is six rows, both ends included.
        val answer = tool("forget", """{"ids":"${pairs[0].first}-${pairs[2].second}"}""")

        assertTrue("accepted: $answer", answer.startsWith(AgentLog.SUCCESS))
        assertTrue("and it says how many went: $answer", answer.contains("6 rows"))
        val gone = log.thrownAway().map { it.id }
        assertEquals("exactly the six named", pairs.take(3).flatMap { listOf(it.first, it.second) }.toSet(), gone.toSet())
    }

    @Test
    fun `numbers and ranges can be mixed in one call, and both spellings of a range work`() {
        log.told("task one")
        repeat(4) {
            (log.call("read", """{"path":"milk.txt"}""")).also { lastCall = it }
            log.got("read", "0\ntwo litres", answers = lastCall)
        }
        log.told("task two")
        val rows = log.contextRows().map { it.id }
        // rows: 1 TOLD, 2..9 four pairs, 10 TOLD. Forget 2, 3, then 4 through 7.
        val named = rows.filter { it in setOf(2, 3) || it in 4..7 }.toSet()

        val withHyphen = tool("forget", """{"ids":"2 3 4-7"}""")

        assertTrue("accepted: $withHyphen", withHyphen.startsWith(AgentLog.SUCCESS))
        assertEquals("every number and every edge of the range went", named, log.thrownAway().map { it.id }.toSet())
    }

    @Test
    fun `a range written backwards is refused, and so is one nobody could have meant`() {
        log.told("task one")

        val backwards = tool("forget", """{"ids":"12-7"}""")
        val huge = tool("forget", """{"ids":"1-100000"}""")

        assertTrue("refused: $backwards", backwards.startsWith(AgentLog.FAILURE))
        assertTrue("naming both ends: $backwards", backwards.contains("12") && backwards.contains("7"))
        assertTrue("refused: $huge", huge.startsWith(AgentLog.FAILURE))
        assertTrue("saying how many it was: $huge", huge.contains("100000 rows"))
    }

    @Test
    fun `a negative number stays a number, so the answer is about the row and not the shape`() {
        // **A hyphen is a range only between two digits.** Reading `-3` as a range from nothing to
        // three would forget three rows the model never named; reading it as a number gets it the
        // sentence it needs about a row that does not exist.
        log.told("task one")

        val body = tool("forget", """{"ids":"-3"}""")

        assertTrue("refused: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("about the row: $body", body.contains("-3") || body.contains("no row"))
        assertTrue("and nothing was lost", log.thrownAway().isEmpty())
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
        (log.call("tree", """{"path":""}""")).also { lastCall = it }
        log.got("tree", "0\nmilk.txt", answers = lastCall)

        val messages = agent(ScriptedDoor()).messages(log.contextRows())

        val assistant = messages.first { it.role == "assistant" }
        assertTrue("its words", assistant.text.contains("I will look first"))
        assertEquals("and its call, in the same turn", 1, assistant.calls.size)
        assertEquals("the answer comes next", "tool", messages[1 + 1].role.let { messages[2].role })
    }

    @Test
    fun `a tool result is matched to its own call`() {
        log.told("do two")
        // **Two calls, and each result names its own.** The mechanical rewrite of this file made
        // both results answer the *last* call, which would have passed a weaker test and made this
        // one - "a tool result is matched to its own call" - impossible to fail.
        val read = (log.call("read", """{"path":"milk.txt"}""")).also { lastCall = it }
        val tree = (log.call("tree", """{"path":""}""")).also { lastCall = it }
        log.got("read", "0\nfile contents", answers = read)
        log.got("tree", "0\nmilk.txt", answers = tree)

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
            Reply("there is one file"),
        )

        agent(door).burst("what is in the project?")

        val kinds = rows().map { it.kind }
        assertEquals("the operator's words come first", AgentLog.TOLD, kinds.first())
        // **The turn ends with the agent's words**, which is what a conclusion is now: a note, not
        // a kind of its own. The app's own `ended` note follows it on the record — it is `app`, so
        // it is not part of the conversation — which is why this asks for the last row the model is
        // sent rather than the last row in the file.
        val conversation = log.contextRows()
        assertEquals("and the turn ends with the agent's words", AgentLog.NOTE, conversation.last().kind)
        assertEquals("which is the note it is drawn from", "agent", conversation.last().name)
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
            Reply("done"),
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
            Reply("ok"),
        )

        agent(door).burst("read something")

        val got = gotRows().first()
        assertTrue("refused: ${got.body}", got.body.startsWith(AgentLog.FAILURE))
    }

    @Test
    fun `a reason is written into the record and stays out of the person's files`() {
        val door = ScriptedDoor(
            Reply("", listOf(call("reason", """{"text":"milk.txt is the only file"}"""))),
            Reply("noted"),
        )

        agent(door).burst("think about it")

        assertTrue("in the record", rows().any {
            it.name == "reason" && it.body.contains("only file")
        })
        assertFalse(
            "and nowhere in the project's files",
            File(ws.accountDir, "shopping").walkTopDown()
                // **Both record files, not just the evidence.** `reason` enters the context, so the
                // sentence is in `context.log` as well — and the point of the test is that it is in
                // neither of the agent's files *as a project file*.
                .filter { it.isFile && it.parentFile?.name != AgentLog.DIR }
                .any { it.readText().contains("only file") },
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
            Reply("read what I could"),
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
        val direct = AgentTools(ws, "shopping", git, log, maxTokens = 200)

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
                        Reply("done")
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
        val next = ScriptedDoor(Reply("ok"))
        agent(next).burst("again")
        assertTrue("the next turn has the edit", next.asked.first().system.contains("three sentences"))
    }

    @Test
    fun `the instructions are a file too, and an edit reaches the next turn`() {
        // ① is shipped as a default but it is the account's file from the first turn on, so a
        // person can correct the app's own instructions — and the next turn is answered under
        // the correction. Anything else would make the file a decoration.
        val file = File(File(account, Workspace.SYSTEM_PROJECT), AgentPrompt.SYSTEM_FILE)
        file.writeText("You are working inside a phone app. Answer in Latin.")
        val first = ScriptedDoor(Reply("ok"))
        agent(first).burst("go")
        assertTrue("the edit is what was sent", first.asked.first().system.contains("in Latin"))

        file.writeText("You are working inside a phone app. Answer in Greek.")
        val second = ScriptedDoor(Reply("ok"))
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
        val door = ScriptedDoor(Reply("done"))

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
            Agent.End.Concluded("done").onto(AgentSession("shopping"), now = 5_000).state,
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
            Reply("done"),
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
        val door = ScriptedDoor(Reply("done"))

        Agent(account, "shopping", ws, git, log, door, model(), limits = limits).burst("go")

        val sent = door.asked.first().system
        assertTrue("a target, named: $sent", sent.contains("Compaction target"))
        assertTrue("as a floor", sent.contains("at least to that"))
        assertTrue("with cutting further wanted", sent.contains("further if the rows do not serve"))
        assertFalse("and no longer an empty 'there is no size'", sent.contains("no size to reach"))
    }

    @Test
    fun `the clear-up index names a line of the context file, and the biggest rows first`() {
        // **How the agent actually chooses.** A `read` of a large context stops at the tool's own
        // limit, so a model deciding what to remove either pages through it — a request per twenty
        // thousand characters — or guesses. The index names the row's line in `context.log`, which
        // is a position that stays put because the file is only ever appended to, and orders the
        // rows by what each costs.
        log.told("a small task")
        log.note("agent", "short note")
        // One big row: a result carrying a file's worth of text.
        (log.call("read", """{"path":"milk.txt"}""")).also { lastCall = it }
        log.got("read", "0\n" + "x".repeat(4_000), answers = lastCall)
        val limits = ContextBudget.Limits.of(triggerAsked = 100, hardAsked = 0, ceiling = 100_000)
        val door = ScriptedDoor(Reply("done"))

        agent(door, limits).burst("clear up please")

        val sent = door.asked.first().system
        val index = sent.substringAfter("The rows taking the most room")
        assertTrue("the biggest row is named first: $index", index.contains("line 4: #4"))
        assertTrue("with what it costs", index.contains("tokens"))
        assertTrue(
            "and the exact call to see it: $index",
            index.contains("`read agent/context.log from <line> to <line>`"),
        )

        // **The handle has to work.** Line four of the context file is that row, and this reads it
        // the way the sentence says to — the call the app told the model to make.
        val byLine = tool("read", """{"path":"agent/context.log","from":4,"to":4}""")
        assertTrue("the line reads that row: $byLine", byLine.startsWith(AgentLog.SUCCESS))
        assertTrue("and it is the big one: $byLine", byLine.contains("x".repeat(50)))
        assertFalse("without a kind column: $byLine", byLine.contains("GOT"))
    }

    @Test
    fun `a row's size is its body's cost, and the line it is stored in costs more`() {
        // **The size is a request's cost, not the file's.** A row's line carries an id, a size, a
        // kind, a ctx flag and padding that the model never sees; if the column measured those, the
        // app would ask the agent to clear up a context that is smaller than the number it quoted,
        // and the number would disagree with the wire every turn.
        log.told("a task")
        val body = "y".repeat(600)
        val id = log.got("read", body, answers = lastCall)

        val row = AgentLog.open(File(ws.accountDir, "shopping")).rows().first { it.id == id }

        assertEquals("the body's cost, exactly", ContextBudget.tokens(body), row.size)
        assertTrue(
            "and the line it lives in costs more, which is what it must not count: " +
                "${ContextBudget.tokens(row.line)} vs ${row.size}",
            ContextBudget.tokens(row.line) > row.size,
        )
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
            Reply("cleared up"),
            Reply("done"),
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
        val afterDoor = ScriptedDoor(Reply("ok"))
        agent(afterDoor, lenient).burst("again")

        assertFalse(
            "under the target, the next turn says nothing: ${afterDoor.asked.last().system.takeLast(120)}",
            afterDoor.asked.last().system.contains("Compaction target"),
        )
    }

    @Test
    fun `under the trigger nothing is said about the context at all`() {
        val limits = ContextBudget.Limits.of(triggerAsked = 900_000, hardAsked = 0, ceiling = 917_504)
        val door = ScriptedDoor(Reply("done"))

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
        val door = ScriptedDoor(Reply("done"))
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
        val door = ScriptedDoor(Reply("done"))

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
        val door = ScriptedDoor(Reply("done"))
        val agent = agent(door)

        log.reset()
        agent.burst("second")

        val sent = door.asked.first().messages.map { it.text }
        assertEquals("the context on disk, and the operator's own words", listOf("second"), sent)
    }

    @Test
    fun `a reset leaves the next request with the new task and nothing before it`() {
        // **Two bursts, and the second one is not the same conversation.** Each burst ends with the
        // model saying something and calling nothing, so this needs two of those — one for each.
        val door = ScriptedDoor(
            Reply("", listOf(call("tree", """{"path":""}"""))),
            Reply("done"),
            Reply("done again"),
            Reply("and once more"),
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
        // Three endings, three lines — written where the ending is decided rather than five times
        // over in five branches. `stopped` is the turn that never ends; words alone are a
        // conclusion now, not a stop, and they are the fourth case below.
        //
        // **Compared as a set, not by counting from an index.** The trace is one fixed-size ring
        // shared by every test in this JVM, so "the lines after position N" is a window that
        // shifts when an older line is evicted — which is exactly how this test first failed,
        // reading another test's lines and none of its own.
        val endings = listOf(
            Triple("concluded", listOf(Reply("done")), "concluded:"),
            Triple("words", listOf(Reply("just some words", emptyList())), "concluded:"),
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
            Reply("done"),
        )

        agent(door).burst("look")

        val call = callRows().first()
        assertEquals("the tool", "read", call.name)
        assertEquals("and exactly what it was given", """{"path":"milk.txt"}""", call.body)
        assertTrue("as JSON, so a reader can parse it", JSONObject(call.body).has("path"))
    }
}
