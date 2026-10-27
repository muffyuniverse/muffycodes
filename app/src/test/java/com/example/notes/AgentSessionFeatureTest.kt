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

    /**
     * Ends every turn with words — a conclusion now — and counts the keepalive pings.
     *
     * **Two counters, because they are two different acts.** A turn is the person talking; a ping is
     * the app keeping the cache warm, and a test that could not tell them apart would read a warm
     * conversation as activity.
     */
    private class KeepDoor : Door {
        val asked = mutableListOf<Request>()
        val pings = mutableListOf<Request>()
        override fun turn(request: Request): Result<Reply> {
            asked.add(request)
            return Result.success(Reply("nothing to change", emptyList()))
        }
        override fun ping(request: Request): Result<Unit> {
            pings.add(request)
            return Result.success(Unit)
        }
    }

    /** Refuses the first [times] pings as the provider would, then warms. */
    /**
     * A door that refuses, for [times] attempts.
     *
     * **[refusePings] leaves `turn` alone**, which is the shape of the failure that actually stops a
     * session: the work went fine, then the provider started refusing, and the keepalive's own
     * attempts are what run out. A *turn* failure is retryable by design — the ladder owns it.
     */
    private class FlakyDoor(
        private val times: Int,
        private val code: Int = 503,
        /** When true, `turn` answers and only the pings fail — "the provider went away", not "the work failed". */
        private val refusePings: Boolean = false,
    ) : Door {
        var attempts = 0
        override fun turn(request: Request): Result<Reply> {
            if (refusePings) return Result.success(Reply("nothing to change", emptyList()))
            attempts++
            return if (attempts <= times) {
                Result.failure(Refused(code, "OpenRouter answered $code"))
            } else {
                Result.success(Reply("nothing to change", emptyList()))
            }
        }

        override fun ping(request: Request): Result<Unit> {
            attempts++
            return if (attempts <= times) {
                Result.failure(Refused(code, "OpenRouter answered $code"))
            } else {
                Result.success(Unit)
            }
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
        AgentLog.open(File(scene.viewModel.workspace!!.accountDir, project))

    /** Runs one tick at the session's own due time, and waits for the poll it may start. */
    /**
     * Polls [project] at its own due time, and waits until the poll is finished — screen included.
     *
     * **The in-flight flag is the whole signal, and it is the app's own.** It is cleared after the
     * screen has been told, so waiting on it cannot read a session as it stood before the poll. It
     * used to be cleared earlier, and this helper is what noticed: the assertion saw `waiting` where
     * the app had already decided `reconnecting`.
     */
    private fun tick(project: String) {
        val session = scene.viewModel.sessionOf(project) ?: return
        scene.viewModel.tickAgents(session.nextPollAt)
        scene.await("the poll to finish", 20_000) {
            // **Asked, not compared.** The runner's set holds agent ids, which equal project names
            // today; a test that compared them directly would keep passing after they stopped being
            // the same thing, which is the one way this test could lie about the guard.
            !scene.viewModel.agentBusy(project)
        }
    }

    @Test
    fun `one switch in settings stops every agent on the phone`() {
        // **The brief assumed this existed and it never did.** Each box has its own Stop, and the
        // strip says how many are on — but a person who wants them all off had to open each project
        // to do it, which is the one thing a settings switch is for.
        listening()
        scene.project("groceries")
        scene.project("letters")
        scene.viewModel.startAgent("groceries")
        scene.viewModel.startAgent("letters")
        assertEquals("two on", "2 sessions", AgentSession.strip(scene.viewModel.agentSessions))

        scene.viewModel.stopAllAgents()

        assertEquals(
            "every one of them off",
            setOf(AgentSession.State.STOPPED),
            scene.viewModel.agentSessions.map { it.state }.toSet(),
        )
        assertEquals(
            "and each one says who stopped it",
            setOf(AgentSession.StopReason.BY_OPERATOR),
            scene.viewModel.agentSessions.map { it.reason }.toSet(),
        )
        assertEquals(
            "so the strip has nothing left to ask about",
            "",
            AgentSession.strip(scene.viewModel.agentSessions),
        )
        assertTrue("and nobody was woken up to be told", notices.isEmpty())
        assertTrue(
            "the one press is on the record, not two",
            Trace.report().lines.count { it.contains("TAP") && it.contains("stop-all-agents") } == 1,
        )
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
        val door = KeepDoor()
        scene.viewModel.chatDoor = { door }
        scene.viewModel.startAgent("groceries")
        val due = scene.viewModel.sessionOf("groceries")!!.nextPollAt

        scene.viewModel.tickAgents(due - 1)
        scene.await("the app to be quiet") { scene.viewModel.agentPollingInFlight().isEmpty() }
        assertEquals("a second early is early", 0, door.pings.size)

        scene.viewModel.tickAgents(due)
        scene.await("the ping") { door.pings.size == 1 }

        assertEquals("one request", 1, door.pings.size)
        assertEquals("and not one turn", 0, door.asked.size)
    }

    @Test
    fun `a ping says nothing in the person's name, and asks nothing`() {
        listening()
        scene.project("groceries")
        val door = KeepDoor()
        openChat("groceries", door)
        scene.viewModel.startAgent("groceries")
        scene.viewModel.sendChat("keep an eye on it")
        scene.await("the first turn") { door.asked.isNotEmpty() }
        scene.await("the agent to be quiet") { !scene.viewModel.chatWorking }
        val before = record("groceries").rows().count { it.kind == AgentLog.TOLD }
        val turns = record("groceries").rows().count { it.kind == AgentLog.TURN }

        tick("groceries")

        val rows = record("groceries").rows()
        assertEquals("no operator message was invented", before, rows.count { it.kind == AgentLog.TOLD })
        assertEquals("the ping is a turn in the record", turns + 1, rows.count { it.kind == AgentLog.TURN })
        assertTrue("and the box can see it", rows.any { it.name == AgentChat.PING })
        assertEquals("the model was asked nothing", 1, door.asked.size)
        assertEquals(
            "and the session is where the turn left it",
            "idle · finished",
            scene.viewModel.sessionOf("groceries")?.label(),
        )
    }

    @Test
    fun `two pings send the same context twice`() {
        // **The whole reason a ping writes nothing into the context.** A provider's implicit cache
        // matches on the longest common prefix, so a conversation that grew a row every four minutes
        // would never be the same request twice, and the ping would stop being cheap.
        listening()
        scene.project("groceries")
        val door = KeepDoor()
        openChat("groceries", door)
        scene.viewModel.startAgent("groceries")
        scene.viewModel.sendChat("keep an eye on it")
        scene.await("the first turn") { door.asked.isNotEmpty() }
        scene.await("the agent to be quiet") { !scene.viewModel.chatWorking }
        val before = record("groceries").contextRows().size

        tick("groceries")
        tick("groceries")

        assertEquals("the context did not grow by a ping", before, record("groceries").contextRows().size)
        assertEquals("and two pings happened", 2, door.pings.size)
        assertEquals(
            "the second sent exactly what the first did",
            door.pings[0].messages.map { it.role to it.text },
            door.pings[1].messages.map { it.role to it.text },
        )
        assertEquals("and nothing was asked", 1, door.asked.size)
    }

    // ---- needing help -------------------------------------------------------

    @Test
    fun `a wall needs a person at once, and the reminder waits for the interval`() {
        // **The one situation worth a notification**, in the operator's own words: something went
        // wrong and the ladder ran out. It used to be a question the agent had asked; the tool is
        // gone, so the same machinery is driven by the case that remains.
        listening()
        scene.project("groceries")
        // **The turn succeeds; the pings that follow it do not.** That is the shape of the real
        // failure this state is for: the work went fine, then the provider started refusing, the
        // ladder tried, and it gave up. A *turn* failure is retryable by design — the ladder owns it
        // — so a session only stops when the keepalive's own attempts are spent.
        // **One door for both halves, installed before the runner is built.** `openChat` sets
        // `chatDoor` and then builds the runner, so a door set afterwards leaves the runner with the
        // one it was made with — and the pings would go to a door that never refuses.
        openChat("groceries", FlakyDoor(times = Int.MAX_VALUE, code = 401, refusePings = true))
        scene.settings.keepReconnect(SettingsStore.MIN_RECONNECT_SECONDS)
        scene.viewModel.startAgent("groceries")
        scene.viewModel.sendChat("do it")
        scene.await("the turn to finish") {
            scene.viewModel.sessionOf("groceries")?.sub == AgentSession.Sub.FINISHED
        }
        // Now the ladder: each tick is one attempt, and the window is the floor.
        var guard = 0
        while (scene.viewModel.sessionOf("groceries")?.state != AgentSession.State.STOPPED &&
            guard++ < 40
        ) {
            tick("groceries")
        }

        assertEquals(
            "one sentence, immediately",
            listOf("groceries: stopped, the pinned provider refused"),
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
            "1st reminder · groceries: stopped, the pinned provider refused",
            notices.last(),
        )
        assertTrue(
            "and the log has it: ${Trace.report().lines.take(3)}",
            Trace.report().lines.any { it.contains("agent-reminder") },
        )
        assertEquals(
            "and it is still waiting for the person",
            AgentSession.State.STOPPED,
            scene.viewModel.sessionOf("groceries")?.state,
        )
    }

    @Test
    fun `the reminder interval is the setting's, not the design's`() {
        // The default is one hour, and the field exists so a person can want something else.
        // A reminder that ignored the setting would pass every test written with the default.
        listening()
        scene.project("groceries")
        scene.settings.keepRemind(5)
        // **One door for both halves, installed before the runner is built.** `openChat` sets
        // `chatDoor` and then builds the runner, so a door set afterwards leaves the runner with the
        // one it was made with — and the pings would go to a door that never refuses.
        openChat("groceries", FlakyDoor(times = Int.MAX_VALUE, code = 401, refusePings = true))
        scene.settings.keepReconnect(SettingsStore.MIN_RECONNECT_SECONDS)
        scene.viewModel.startAgent("groceries")
        scene.viewModel.sendChat("do it")
        scene.await("the turn to finish") {
            scene.viewModel.sessionOf("groceries")?.sub == AgentSession.Sub.FINISHED
        }
        var guard = 0
        while (scene.viewModel.sessionOf("groceries")?.state != AgentSession.State.STOPPED &&
            guard++ < 40
        ) {
            tick("groceries")
        }
        val askedAt = scene.viewModel.sessionOf("groceries")!!.askedAt

        scene.viewModel.tickAgents(askedAt + 4 * 60_000)
        assertEquals("four minutes is not five", 1, notices.size)

        scene.viewModel.tickAgents(askedAt + 5 * 60_000)
        assertEquals("and five is", 2, notices.size)
        assertEquals(
            "the first reminder is the first",
            "1st reminder · groceries: stopped, the pinned provider refused",
            notices.last(),
        )
        assertTrue(
            "and the press is on the record",
            Trace.report().lines.any { it.contains("TAP") && it.contains("remind-me") },
        )
    }

    @Test
    fun `a session that needs help is not polled, however long the person takes`() {
        // **A stopped session is not warmed.** Its problem is the person's to fix — a refused key,
        // an empty balance — and a ping every few minutes would spend money on a session that
        // cannot work while charging somebody to be reminded of it.
        listening()
        scene.project("groceries")
        val broken = FlakyDoor(times = Int.MAX_VALUE, code = 401, refusePings = true)
        openChat("groceries", broken)
        scene.settings.keepReconnect(SettingsStore.MIN_RECONNECT_SECONDS)
        scene.viewModel.startAgent("groceries")
        scene.viewModel.sendChat("do it")
        scene.await("the turn to finish") {
            scene.viewModel.sessionOf("groceries")?.sub == AgentSession.Sub.FINISHED
        }
        var guard = 0
        while (scene.viewModel.sessionOf("groceries")?.state != AgentSession.State.STOPPED &&
            guard++ < 40
        ) {
            tick("groceries")
        }
        val asked = broken.attempts

        repeat(10) { tick("groceries") }

        assertEquals("no new request while a person is needed", asked, broken.attempts)
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
    fun `a key the provider refuses ends the session with words`() {
        // **The one ping failure that is the person's to fix.** A blip reconnects; a wall stops. The
        // difference is how long the ladder tried, and what a person reads afterwards is the
        // provider's own sentence rather than the app's guess at it. A 5xx is not this case: that is
        // a ping missed.
        listening()
        scene.project("groceries")
        // **The floor, because this test drives real ticks.** The window is a duration now, so a
        // default of ten minutes would mean ten minutes of real time before the session gave up —
        // the shortest the setting allows is the old eight-try window, which is what this needs.
        scene.settings.keepReconnect(SettingsStore.MIN_RECONNECT_SECONDS)
        // **One door, not a new one per poll.** A lambda that built a door on every call would
        // reset its own attempt count, and a failure count that resets is not a failure count.
        val broken = FlakyDoor(times = Int.MAX_VALUE, code = 401)
        scene.viewModel.startAgent("groceries")

        var last = ""
        var ticks = 0
        while (scene.viewModel.sessionOf("groceries")?.state != AgentSession.State.STOPPED &&
            // **A count of ticks, because the ladder's own limit is a duration.** Each tick moves
            // the clock by the retry delay, so half a minute of window is a handful of ticks; the
            // ceiling here is what stops the loop if the ladder never gives up.
            ticks < 40
        ) {
            tick("groceries")
            ticks++
            last = scene.viewModel.sessionOf("groceries")?.label().orEmpty()
        }

        val session = scene.viewModel.sessionOf("groceries")
        assertEquals("it gave up: $last", AgentSession.State.STOPPED, session?.state)
        assertEquals("and knows why", AgentSession.StopReason.PIN_REFUSED, session?.reason)
        assertTrue("and said so once", notices.any { it.contains("groceries") })
        assertTrue("with the reason in the label: $last", last.contains("OpenRouter answered 401"))
    }

    @Test
    fun `one dropped ping is missed, and the session goes on untouched`() {
        // A 5xx is the provider having a bad minute, not the person's key being wrong. Nothing is
        // retried in a burst, nothing is written down, and the session is not moved: the next tick
        // tries again an interval later.
        listening()
        scene.project("groceries")
        val flaky = FlakyDoor(times = 1)
        scene.viewModel.chatDoor = { flaky }
        scene.viewModel.startAgent("groceries")

        tick("groceries")

        val after = scene.viewModel.sessionOf("groceries")
        assertEquals("still on", true, after?.running)
        assertEquals("not even reconnecting", AgentSession.Sub.WAITING, after?.sub)
        assertEquals("and no ladder", 0, after?.retries)
        assertEquals("the label says what it always said", "idle · waiting", after?.label())
        assertTrue("and nobody was woken for it", notices.isEmpty())

        // The next interval, and it lands: the clock moves, nothing else does.
        scene.viewModel.tickAgents(after!!.nextPollAt)
        scene.await("the next ping") { flaky.attempts == 2 }
        assertEquals("still nobody", 0, scene.viewModel.sessionOf("groceries")?.retries)
    }

    @Test
    fun `speaking to a stopped agent switches it back on`() {
        listening()
        scene.project("groceries")
        val door = ScriptedDoor(
            Reply("done"),
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
            Reply("done"),
            Reply("done"),
        )
        openChat("groceries", door)
        scene.viewModel.startAgent("groceries")
        scene.viewModel.sendChat("first")
        scene.await("the first turn") { door.asked.size == 1 }
        scene.await("the agent to be quiet") { !scene.viewModel.chatWorking }

        File(scene.viewModel.workspace!!.accountDir, "groceries/project_prompt.txt")
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
            Reply("", listOf(Call("c1", "tree", """{"path":""}"""))),
            Reply("there is one file"),
            Reply("done again"),
        )
        openChat("groceries", door)
        scene.viewModel.startAgent("groceries")
        scene.viewModel.sendChat("what is in the project?")
        scene.await("the first turn") { door.asked.size == 2 }
        scene.await("the agent to be quiet") { !scene.viewModel.chatWorking }
        val evidence = record("groceries").rows()

        scene.viewModel.resetAgent("groceries")
        // **Wait on the trace, not on the file.** The row is written before the note that says so,
        // and a test that waited on the file read the app mid-act.
        scene.await("the reset to be written") {
            Trace.report().lines.any { it.contains("agent-reset") }
        }

        assertEquals(
            "the working context is empty: nothing is in, and the reset row is the app's",
            emptyList<String>(),
            record("groceries").contextRows().map { it.name },
        )
        assertEquals(
            "and the rows that were in are marked app, not thrown away",
            record("groceries").rows().filter { it.ctx != AgentLog.APP },
            emptyList<AgentLog.Row>(),
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
    fun `an agent that answers with words and no call concludes, and the box shows the words`() {
        // **It used to stop the session.** The operator's ruling is that a reply in prose is an
        // answer: the words are the conclusion, nothing needs the person, and the strip says the
        // agent finished instead of asking to be resolved.
        listening()
        val door = ScriptedDoor(
            Reply("I think the milk is fine.", emptyList()),
            Reply("done"),
        )
        openChat("groceries", door)
        scene.viewModel.startAgent("groceries")

        scene.viewModel.sendChat("is the milk still good?")
        scene.await("the turn to end") {
            scene.viewModel.sessionOf("groceries")
                ?.let { it.state == AgentSession.State.IDLE && it.sub == AgentSession.Sub.FINISHED } == true
        }

        val session = scene.viewModel.sessionOf("groceries")!!
        assertEquals("nothing is wrong", null, session.reason)
        assertFalse("and it is not asking to be resolved", session.needsYou)
        // **One row, not two.** The conclusion used to be written twice — once as the agent's own
        // note (which is what the model reads back) and once as a `CONCLUDE` row for the box. The
        // second is gone; the note is what the box draws, and there is exactly one of it.
        assertEquals("the box draws the words as the conclusion", "I think the milk is fine.",
            record("groceries").rows().single { it.kind == AgentLog.NOTE && it.name == "agent" }.body)
        assertFalse(
            "and nothing was written down as a stop",
            record("groceries").rows().any { it.name == "stopped" },
        )

        scene.viewModel.sendChat("try again")
        scene.await("the second turn") { door.asked.size == 2 }

        assertTrue("and the agent is still on", scene.viewModel.sessionOf("groceries")?.running == true)
    }

    @Test
    fun `a concluded turn is idle and finished, not waiting`() {
        listening()
        val door = ScriptedDoor(Reply("done"))
        openChat("groceries", door)
        scene.viewModel.startAgent("groceries")
        scene.viewModel.sendChat("do the thing")
        scene.await("the turn") { door.asked.isNotEmpty() }
        scene.await("the agent to be quiet") { !scene.viewModel.chatWorking }

        val session = scene.viewModel.sessionOf("groceries")!!
        assertEquals("finished", AgentSession.Sub.FINISHED, session.sub)
        assertEquals("and idle", AgentSession.State.IDLE, session.state)
        assertEquals("which reads as itself", "idle · finished", session.label())
        assertEquals("and nobody is needed", false, session.needsYou)
        assertEquals("and the sentence is the ending's own", "The agent finished", scene.viewModel.message)
    }

    @Test
    fun `the two context limits are the settings', not the design's`() {
        // Written with numbers the defaults would fail: a screen test on the same fields would
        // pass with the values ignored, and the agent would warn at the wrong size for ever.
        listening()
        scene.settings.keepTrigger(1)
        assertEquals("the number typed is what is stored", 1, scene.settings.triggerTokens)
        assertEquals("and the hard limit is still off", 0, scene.settings.hardTokens)
        val door = ScriptedDoor(Reply("done"))
        openChat("groceries", door)
        scene.viewModel.startAgent("groceries")

        scene.viewModel.sendChat("do it")
        scene.await("the turn") { door.asked.isNotEmpty() }

        assertTrue(
            "one token is over a trigger of one: ${door.asked.first().system.takeLast(160)}",
            door.asked.first().system.contains("Clear up your context"),
        )
        assertTrue(
            "the request says so on the trace",
            Trace.report().lines.any { it.contains("agent-clear-up") },
        )
    }

    @Test
    fun `a session that was on gets its clock back without anybody pressing anything`() {
        // **What a reboot leaves behind.** The service is sticky, so a killed process comes back —
        // but a phone that was switched off does not restart it, and the app is the first thing that
        // can. Opening the app reads the sessions off disk and asks for the clock; nothing is pressed,
        // and the agent polls at its own due time.
        listening()
        scene.project("groceries")
        scene.viewModel.startAgent("groceries")
        val due = scene.viewModel.sessionOf("groceries")!!.nextPollAt
        val door = KeepDoor()

        val reopened = scene.reopen()
        reopened.chatDoor = { door }
        reopened.tickAgents(due)

        // **Wait for the request, not for the claim.** A ping writes `lastPollAt` *before* the
        // request goes out — that is what makes two tickers safe — so waiting on the timestamp
        // returns the instant the claim is written and then reads the door a moment too early. The
        // signal that the ping is over is the in-flight set, and it is cleared after the screen was
        // told.
        scene.await("the resumed ping to finish", 20_000) {
            door.pings.size == 1 && reopened.agentPollingInFlight().isEmpty()
        }
        assertEquals("one request, with nobody looking at a button", 1, door.pings.size)
        assertEquals(
            "and the session is waiting again",
            AgentSession.Sub.WAITING,
            reopened.sessionOf("groceries")?.sub,
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
    fun `the screen's tick drops a session whose folder was deleted`() {
        listening()
        scene.project("groceries")
        scene.viewModel.startAgent("groceries")
        val project = File(scene.viewModel.workspace!!.accountDir, "groceries")
        val due = scene.viewModel.sessionOf("groceries")!!.nextPollAt

        project.deleteRecursively()
        assertFalse("the folder is really gone before the tick", project.isDirectory)
        scene.viewModel.tickAgents(due)
        // **The tick answers on the main thread.** It posts the new session list there, so the
        // screen's own state is only true once that post has run — asserting straight after the
        // call was asserting against the list from before it.
        scene.await("the tick to drop the session") {
            scene.viewModel.sessionOf("groceries") == null
        }

        assertEquals("the session is gone", null, scene.viewModel.sessionOf("groceries"))
        assertFalse(
            "and nothing is left in the file",
            AgentSessions(scene.viewModel.workspace!!.accountDir).read().any { it.project == "groceries" },
        )
    }
}
