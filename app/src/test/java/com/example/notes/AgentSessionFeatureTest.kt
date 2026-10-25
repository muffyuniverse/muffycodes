package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The agent being on: given a session, when the clock moves, then what the app does about it.
 *
 * Every test here reads like the design's table — idle polls, asked reminds, stopped speaks only
 * when nobody chose it — and every one drives the **real** view model, the real record and the
 * real `.muffy/agents.json`. What is scripted is the door, because the suite never opens a socket,
 * and what is moved is the clock, because four minutes and one hour do not fit in a test run.
 *
 * **Ticks are always taken at the session's own `nextPollAt`**, never at "start plus four
 * minutes": the session decides when it is due, so a test that computed the time itself would be
 * checking its own arithmetic instead of the app's.
 */
class AgentSessionFeatureTest : FeatureTest() {

    /** Answers every request with `wait`, and keeps what it was asked. */
    private class WaitDoor : Door {
        val asked = mutableListOf<Request>()
        override fun turn(request: Request): Result<Reply> {
            asked.add(request)
            return Result.success(Reply("", listOf(Call("call_${asked.size}", "wait", "{}"))))
        }
    }

    /** Refuses the first [times] requests, then waits. */
    private class FlakyDoor(private val times: Int) : Door {
        var attempts = 0
        override fun turn(request: Request): Result<Reply> {
            attempts++
            if (attempts <= times) {
                return Result.failure(IllegalStateException("OpenRouter answered 503"))
            }
            return Result.success(Reply("", listOf(Call("call_$attempts", "wait", "{}"))))
        }
    }

    private val notices = mutableListOf<String>()

    private fun listening() {
        scene.viewModel.agentNotifier = { notices += it.notice().orEmpty() }
    }

    /**
     * Opens the conversation before speaking.
     *
     * `sendChat` is the composer's action, so it needs the chat to be open — the same rule the
     * screen has. A test that called it with no conversation open would be exercising a path the
     * app cannot reach.
     */
    private fun openChat(project: String, door: Door) {
        scene.viewModel.chatDoor = { door }
        scene.viewModel.openChat(project)
    }

    private fun record(project: String): AgentLog =
        AgentLog.open(File(scene.viewModel.workspace!!.projectsDir, project))

    /** Runs one tick at the session's own due time, and waits for the poll it may start. */
    private fun tick(project: String) {
        val session = scene.viewModel.sessionOf(project) ?: return
        scene.viewModel.tickAgents(session.nextPollAt)
        scene.await("the poll to finish", 20_000) {
            project !in scene.viewModel.agentPollingInFlight()
        }
    }

    // ---- idle ---------------------------------------------------------------

    @Test
    fun `switching the agent on starts an idle session, and the strip says so`() {
        listening()
        scene.project("groceries")

        scene.viewModel.startAgent("groceries")

        val session = scene.viewModel.sessionOf("groceries")
        assertNotNull("there is a session", session)
        assertEquals("idle, waiting", "idle · waiting", session?.label())
        assertEquals("and the strip counts it", "1 session", AgentSession.strip(scene.viewModel.agentSessions))
        assertTrue(
            "the press is on the record: ${Trace.report().lines.take(4)}",
            Trace.report().lines.any { it.contains("TAP") && it.contains("start-agent") },
        )
        assertTrue("and it is written down, not remembered", File(
            scene.viewModel.workspace!!.accountDir,
            ".muffy/${AgentSessions.AGENTS}",
        ).isFile)
        assertTrue("nothing was said to anybody", notices.isEmpty())
    }

    @Test
    fun `a poll happens at four minutes and not a second earlier`() {
        listening()
        scene.project("groceries")
        val door = WaitDoor()
        scene.viewModel.chatDoor = { door }
        scene.viewModel.startAgent("groceries")
        val due = scene.viewModel.sessionOf("groceries")!!.nextPollAt

        scene.viewModel.tickAgents(due - 1)
        scene.await("the app to be quiet") { scene.viewModel.agentPollingInFlight().isEmpty() }
        assertEquals("a second early is early", 0, door.asked.size)

        scene.viewModel.tickAgents(due)
        scene.await("the poll") { door.asked.size == 1 }

        assertEquals("one request", 1, door.asked.size)
    }

    @Test
    fun `a poll says nothing in the person's name`() {
        listening()
        scene.project("groceries")
        val door = WaitDoor()
        openChat("groceries", door)
        scene.viewModel.startAgent("groceries")
        scene.viewModel.sendChat("keep an eye on it")
        scene.await("the first turn") { door.asked.isNotEmpty() }
        val before = record("groceries").rows().count { it.kind == AgentLog.TOLD }

        tick("groceries")

        val rows = record("groceries").rows()
        assertEquals("no operator message was invented", before, rows.count { it.kind == AgentLog.TOLD })
        assertTrue("but the poll is a turn in the record", rows.count { it.kind == AgentLog.TURN } >= 2)
        assertEquals(
            "and the session is idle again",
            "idle · waiting",
            scene.viewModel.sessionOf("groceries")?.label(),
        )
    }

    @Test
    fun `two polls send the same context twice`() {
        // **The whole reason the repeated `wait` is kept out of the context.** A provider's
        // implicit cache matches on the longest common prefix, so a context that grew a row every
        // four minutes would never be the same request twice, and the poll would stop being cheap.
        listening()
        scene.project("groceries")
        val door = WaitDoor()
        openChat("groceries", door)
        scene.viewModel.startAgent("groceries")
        scene.viewModel.sendChat("keep an eye on it")
        scene.await("the first turn") { door.asked.isNotEmpty() }
        val before = record("groceries").contextRows().size

        tick("groceries")
        val first = door.asked.size
        tick("groceries")
        val second = door.asked.size

        assertEquals("the context did not grow by a poll", before, record("groceries").contextRows().size)
        assertTrue("and two polls happened", second > first)
        assertEquals(
            "the second poll sent exactly what the first did",
            door.asked[first - 1].messages.map { it.role to it.text },
            door.asked[second - 1].messages.map { it.role to it.text },
        )
    }

    // ---- asked --------------------------------------------------------------

    @Test
    fun `a question needs a person at once, and the reminder waits for the interval`() {
        listening()
        scene.project("groceries")
        val door = ScriptedDoor(
            Reply("", listOf(Call("c1", "ask", """{"question":"Which branch?"}"""))),
        )
        openChat("groceries", door)
        scene.viewModel.startAgent("groceries")
        scene.viewModel.sendChat("do it")
        scene.await("the question") {
            scene.viewModel.sessionOf("groceries")?.state == AgentSession.State.ASKED
        }

        assertEquals(
            "one sentence, immediately",
            listOf("groceries: the agent asked something"),
            notices,
        )
        assertEquals(
            "and the strip says somebody is needed",
            "1 session · 1 needs you",
            AgentSession.strip(scene.viewModel.agentSessions),
        )

        val askedAt = scene.viewModel.sessionOf("groceries")!!.askedAt
        scene.viewModel.tickAgents(askedAt + 30 * 60_000)
        assertEquals("half an hour is not an hour", 1, notices.size)

        scene.viewModel.tickAgents(askedAt + AgentSession.REMIND_MS)
        assertEquals(
            "then the second sentence says which one it is",
            "1st reminder · groceries: the agent asked something",
            notices.last(),
        )
        assertTrue(
            "and the log has it: ${Trace.report().lines.take(3)}",
            Trace.report().lines.any { it.contains("agent-reminder") },
        )
        assertEquals(
            "and it is still waiting for the person",
            AgentSession.State.ASKED,
            scene.viewModel.sessionOf("groceries")?.state,
        )
    }

    @Test
    fun `the reminder interval is the setting's, not the design's`() {
        // The default is one hour, and the field exists so a person can want something else.
        // A reminder that ignored the setting would pass every test written with the default.
        listening()
        scene.project("groceries")
        val door = ScriptedDoor(
            Reply("", listOf(Call("c1", "ask", """{"question":"Which branch?"}"""))),
        )
        openChat("groceries", door)
        scene.settings.keepRemind(5)
        scene.viewModel.startAgent("groceries")
        scene.viewModel.sendChat("do it")
        scene.await("the question") {
            scene.viewModel.sessionOf("groceries")?.state == AgentSession.State.ASKED
        }
        val askedAt = scene.viewModel.sessionOf("groceries")!!.askedAt

        scene.viewModel.tickAgents(askedAt + 4 * 60_000)
        assertEquals("four minutes is not five", 1, notices.size)

        scene.viewModel.tickAgents(askedAt + 5 * 60_000)
        assertEquals("and five is", 2, notices.size)
        assertEquals(
            "the first reminder is the first",
            "1st reminder · groceries: the agent asked something",
            notices.last(),
        )
        assertTrue(
            "and the press is on the record",
            Trace.report().lines.any { it.contains("TAP") && it.contains("remind-me") },
        )
    }

    @Test
    fun `an agent in ask is not polled, however long the person takes`() {
        listening()
        scene.project("groceries")
        val door = ScriptedDoor(
            Reply("", listOf(Call("c1", "ask", """{"question":"Which branch?"}"""))),
        )
        openChat("groceries", door)
        scene.viewModel.startAgent("groceries")
        scene.viewModel.sendChat("do it")
        scene.await("the question") {
            scene.viewModel.sessionOf("groceries")?.state == AgentSession.State.ASKED
        }
        val asked = door.asked.size

        repeat(10) { tick("groceries") }

        assertEquals("no new request while a person is being waited for", asked, door.asked.size)
    }

    // ---- stopped ------------------------------------------------------------

    @Test
    fun `stopping the agent is silent, and the strip forgets it`() {
        listening()
        scene.project("groceries")
        scene.viewModel.startAgent("groceries")

        scene.viewModel.stopAgent("groceries")

        assertEquals(
            "stopped by the person",
            AgentSession.StopReason.BY_OPERATOR,
            scene.viewModel.sessionOf("groceries")?.reason,
        )
        assertTrue("and nothing was said about it", notices.isEmpty())
        assertEquals("and the strip is gone", "", AgentSession.strip(scene.viewModel.agentSessions))
        assertTrue(
            "the press is on the record",
            Trace.report().lines.any { it.contains("TAP") && it.contains("stop-agent") },
        )
    }

    @Test
    fun `a provider that keeps refusing ends the session with words`() {
        // A blip reconnects; a wall stops. The difference is eight tries, and what a person reads
        // afterwards is the provider's own sentence rather than the app's guess at it.
        listening()
        scene.project("groceries")
        // **One door, not a new one per poll.** A lambda that built a door on every call would
        // reset its own attempt count, and a failure count that resets is not a failure count.
        val broken = FlakyDoor(times = Int.MAX_VALUE)
        scene.viewModel.chatDoor = { broken }
        scene.viewModel.startAgent("groceries")

        var last = ""
        var ticks = 0
        while (scene.viewModel.sessionOf("groceries")?.state != AgentSession.State.STOPPED &&
            ticks < AgentSession.MAX_RETRIES + 4
        ) {
            tick("groceries")
            ticks++
            last = scene.viewModel.sessionOf("groceries")?.label().orEmpty()
        }

        val session = scene.viewModel.sessionOf("groceries")
        assertEquals("it gave up: $last", AgentSession.State.STOPPED, session?.state)
        assertEquals("and knows why", AgentSession.StopReason.PIN_REFUSED, session?.reason)
        assertTrue("and said so once", notices.any { it.contains("groceries") })
        assertTrue("with the reason in the label: $last", last.contains("OpenRouter answered 503"))
    }

    @Test
    fun `one dropped poll reconnects instead of ending the session`() {
        listening()
        scene.project("groceries")
        val flaky = FlakyDoor(times = 1)
        scene.viewModel.chatDoor = { flaky }
        scene.viewModel.startAgent("groceries")

        tick("groceries")

        val reconnecting = scene.viewModel.sessionOf("groceries")
        assertEquals("still on", true, reconnecting?.running)
        assertEquals("reconnecting", AgentSession.Sub.RECONNECTING, reconnecting?.sub)
        assertEquals("after one try", 1, reconnecting?.retries)

        // The retry is one second later, and it works: the ladder resets.
        scene.viewModel.tickAgents(reconnecting!!.nextPollAt)
        scene.await("the retry") {
            scene.viewModel.sessionOf("groceries")?.sub == AgentSession.Sub.WAITING
        }

        assertEquals("back to waiting", "idle · waiting", scene.viewModel.sessionOf("groceries")?.label())
        assertEquals("and the ladder is reset", 0, scene.viewModel.sessionOf("groceries")?.retries)
    }

    @Test
    fun `speaking to a stopped agent switches it back on`() {
        listening()
        scene.project("groceries")
        val door = ScriptedDoor(
            Reply("", listOf(Call("c1", "conclude", """{"text":"done"}"""))),
        )
        openChat("groceries", door)
        scene.viewModel.startAgent("groceries")
        scene.viewModel.stopAgent("groceries")

        scene.viewModel.sendChat("one more thing")
        scene.await("the turn") {
            scene.viewModel.sessionOf("groceries")?.state != AgentSession.State.STOPPED
        }

        assertTrue("the person is the switch", scene.viewModel.sessionOf("groceries")?.running == true)
    }

    // ---- the context the agent is answered with -----------------------------

    @Test
    fun `an edit to the project's instructions reaches the next turn`() {
        listening()
        val door = ScriptedDoor(
            Reply("", listOf(Call("c1", "conclude", """{"text":"done"}"""))),
            Reply("", listOf(Call("c2", "conclude", """{"text":"done"}"""))),
        )
        openChat("groceries", door)
        scene.viewModel.startAgent("groceries")
        scene.viewModel.sendChat("first")
        scene.await("the first turn") { door.asked.size == 1 }
        scene.await("the agent to be quiet") { !scene.viewModel.chatWorking }

        File(scene.viewModel.workspace!!.projectsDir, "groceries/project_prompt.txt")
            .writeText("Always answer in French.")
        scene.viewModel.sendChat("second")
        scene.await("the second turn") { door.asked.size == 2 }

        assertFalse(
            "the turn before the edit did not have it",
            door.asked[0].system.contains("French"),
        )
        assertTrue(
            "and the turn after it does",
            door.asked[1].system.contains("Always answer in French."),
        )
    }

    @Test
    fun `resetting clears the working context, keeps the evidence, and says so`() {
        listening()
        val door = ScriptedDoor(
            Reply("", listOf(Call("c1", "tree", """{"path":"GitHub"}"""))),
            Reply("", listOf(Call("c2", "conclude", """{"text":"there is one file"}"""))),
            Reply("", listOf(Call("c3", "conclude", """{"text":"done again"}"""))),
        )
        openChat("groceries", door)
        scene.viewModel.startAgent("groceries")
        scene.viewModel.sendChat("what is in the project?")
        scene.await("the first turn") { door.asked.size == 2 }
        scene.await("the agent to be quiet") { !scene.viewModel.chatWorking }
        val evidence = record("groceries").rows()

        scene.viewModel.resetAgent("groceries")
        scene.await("the reset to be written") {
            record("groceries").contextRows().size == 1
        }

        assertEquals(
            "the working context is the reset and nothing else",
            listOf("reset"),
            record("groceries").contextRows().map { it.name },
        )
        assertEquals(
            "and the evidence grew by exactly one row",
            evidence.size + 1,
            record("groceries").rows().size,
        )
        assertTrue(
            "with everything that happened still in it",
            record("groceries").rows().any { it.kind == AgentLog.CALL && it.name == "tree" },
        )
        assertTrue(
            "the press and the act are on the trace",
            Trace.report().lines.any { it.contains("TAP") && it.contains("reset-agent") } &&
                Trace.report().lines.any { it.contains("agent-reset") },
        )

        scene.viewModel.sendChat("now something else")
        scene.await("the turn after the reset") { door.asked.size == 3 }

        assertEquals(
            "the request after a reset is the new task alone",
            listOf("now something else"),
            door.asked.last().messages.map { it.text },
        )
    }

    @Test
    fun `a session survives the app being reopened`() {
        listening()
        scene.project("groceries")
        scene.viewModel.startAgent("groceries")
        scene.viewModel.stopAgent("groceries")

        val reopened = scene.reopen()

        assertEquals(
            "the session came off the disk",
            AgentSession.StopReason.BY_OPERATOR,
            reopened.sessionOf("groceries")?.reason,
        )
    }

    @Test
    fun `a project that is gone takes its session with it`() {
        listening()
        scene.project("groceries")
        scene.viewModel.startAgent("groceries")
        val project = File(scene.viewModel.workspace!!.projectsDir, "groceries")
        val due = scene.viewModel.sessionOf("groceries")!!.nextPollAt

        project.deleteRecursively()
        scene.viewModel.tickAgents(due)

        assertEquals("the session is gone", null, scene.viewModel.sessionOf("groceries"))
        assertFalse(
            "and nothing is left in the file",
            AgentSessions(scene.viewModel.workspace!!.accountDir).read().any { it.project == "groceries" },
        )
    }
}
