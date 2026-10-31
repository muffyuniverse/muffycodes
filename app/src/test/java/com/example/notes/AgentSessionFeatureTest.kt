package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
        // **From the store, because the screen's copy is one write behind.** `sessionOf` returns the
        // list the view model took when the last tick *started*, so its `nextPollAt` is the time
        // before that poll moved the clock — and a tick taken at that time is in the past, which
        // `warmDue` rightly declines. The loop then ticks at the same stale instant forty times and
        // the session never moves at all. This is why the tests read `retries=0` after forty ticks
        // while every individual link in the ladder was correct.
        val session = scene.viewModel.readSession(project) ?: return
        tickAt(project, session.nextPollAt)
    }

    /** One tick at [at], waited out — the shape every tick in this class takes. */
    private fun tickAt(project: String, at: Long) {
        scene.viewModel.tickAgents(at)
        scene.await("the poll to finish", 20_000) {
            // **Asked, not compared.** The runner's set holds agent ids, which equal project names
            // today; a test that compared them directly would keep passing after they stopped being
            // the same thing, which is the one way this test could lie about the guard.
            !scene.viewModel.agentBusy(project)
        }
    }

    /**
     * Polls until the session gives up, **by moving the clock past the operator's window.**
     *
     * Two ticks, and the reason is the ladder's own shape: the first failure is the moment the
     * window starts, so `failed()` measures zero elapsed on it and only records `retryFrom`. The
     * second tick is the one that can see the window has been spent. A single jump to the end
     * cannot work, and a loop of forty ticks spends the window by accident — at one to four
     * seconds a pass, which is the schedule's business and not this test's.
     */
    /**
     * Polls until the session gives up, **at the session's own due time every time.**
     *
     * Two things make this less obvious than it looks, and both were bugs here first:
     *
     * **A tick has two deadlines, not one.** It must be past the operator's window *and* past the
     * session's own `nextPollAt` — the poll interval is four minutes and the floor window is thirty
     * seconds, so a jump to "the window spent" is still minutes early and `warmDue` declines it.
     * A declined tick does nothing at all, and the test then waits for a poll the app rightly did
     * not take.
     *
     * **And a tick is not always a ping.** If the burst left finished work behind, the tick takes
     * the wake path — a full turn — and a turn that succeeds *clears* the ladder. So the loop asks
     * the session what it did rather than assuming, and goes round again when the ladder restarted.
     * The jump only past the window happens once a failure is on the record, because that is the
     * moment the window is measured from.
     */
    private fun giveUp(project: String) {
        var guard = 0
        while (guard++ < 50) {
            // **Read from the store, not the screen.** A poll's outcome is written by a worker, so
            // the view model's list is one write behind the poll `tick` just watched finish.
            val session = scene.viewModel.readSession(project) ?: return
            if (session.state == AgentSession.State.STOPPED) {
                // **And then read it into the screen's list before returning.** This is the same
                // one-write-behind problem the comment above describes, and returning here left the
                // caller holding the list from *before* the poll that stopped the session - so a
                // strip computed straight afterwards could say "1 session" about a session the
                // store already had as stopped. It is a race rather than a wrong answer, which is
                // why it only appeared once the timing shifted; the fix is to end on a tick, like
                // every other assertion in this class does.
                shown(project)
                return
            }
            val due = session.nextPollAt
            val at = if (session.retryFrom > 0) {
                maxOf(due, session.retryFrom + scene.settings.reconnectMs + 1_000)
            } else {
                due
            }
            tickAt(project, at)
        }
        error("the session never gave up, which is the failure this test is for")
    }

    /**
     * The session as the screen would draw it, after a tick has been waited out.
     *
     * **A tick refreshes the view model's list *before* its worker writes the outcome**, so the
     * list a test reads straight afterwards is the moment before the poll finished. Every
     * assertion in this class about what a poll decided goes through here: one more tick at the
     * same time is due (it changes nothing — the session is stopped and a stopped session is not
     * polled), and it is what re-reads the store into the list the screen draws.
     */
    private fun shown(project: String): AgentSession? {
        val now = scene.viewModel.readSession(project)?.lastPollAt ?: 0L
        scene.viewModel.tickAgents(now)
        return scene.viewModel.sessionOf(project)
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
        // **The same context, not the same request.** Asserting the whole request was identical
        // encoded the opposite of the ruling: an identical request is answered by a gateway's own
        // response cache without reaching the upstream, so the provider's cache expires while the
        // app records a successful ping. The prefix - what the cache matches on - must be identical;
        // the final message now carries a marker so the request itself never repeats.
        assertEquals(
            "the second sent the same conversation as the first",
            door.pings[0].messages.dropLast(1).map { it.role to it.text },
            door.pings[1].messages.dropLast(1).map { it.role to it.text },
        )
        assertNotEquals(
            "but not the same request",
            door.pings[0].messages.last().text,
            door.pings[1].messages.last().text,
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
        giveUp("groceries")

        assertEquals(
            "one sentence, immediately",
            listOf("groceries: stopped, OpenRouter answered 401"),
            notices,
        )
        // **"0 sessions" is the right count and it reads oddly**, so it is worth saying why: the
        // session gave up, and a stopped session is not one the app is working on. What the strip
        // is for is the second half — somebody has to do something about this key.
        assertEquals(
            "and the strip says somebody is needed",
            "0 sessions · 1 needs you",
            AgentSession.strip(scene.viewModel.agentSessions),
        )

        val askedAt = shown("groceries")!!.askedAt
        scene.viewModel.tickAgents(askedAt + 30 * 60_000)
        assertEquals("half an hour is not an hour", 1, notices.size)

        scene.viewModel.tickAgents(askedAt + AgentSession.REMIND_MS)
        assertEquals(
            "then the second sentence says which one it is",
            "1st reminder · groceries: stopped, OpenRouter answered 401",
            notices.last(),
        )
        assertTrue(
            "and the log has it: ${Trace.report().lines.take(3)}",
            Trace.report().lines.any { it.contains("agent-reminder") },
        )
        assertEquals(
            "and it is still waiting for the person",
            AgentSession.State.STOPPED,
            shown("groceries")?.state,
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
        giveUp("groceries")
        val askedAt = shown("groceries")!!.askedAt

        scene.viewModel.tickAgents(askedAt + 4 * 60_000)
        assertEquals("four minutes is not five", 1, notices.size)

        scene.viewModel.tickAgents(askedAt + 5 * 60_000)
        assertEquals("and five is", 2, notices.size)
        assertEquals(
            "the first reminder is the first",
            "1st reminder · groceries: stopped, OpenRouter answered 401",
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
        giveUp("groceries")
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
            shown("groceries")?.reason,
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
        // **And it has to be installed, or the real door runs.** Without this the runner built an
        // `OpenRouterDoor` with no key on the test account, so every ping failed at "No OpenRouter
        // key on this account yet" — a true sentence about a fixture that never reached the door it
        // meant to. The test then read `asked=0` and blamed the ladder.
        scene.viewModel.chatDoor = { broken }
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
        // **The numbers, in the message.** This assertion was failing with "idle · waiting" and
        // nothing else, and every link in the chain had been checked by reading it. A failure that
        // says what the ladder actually saw - how many tries, when it started, what the window is -
        // is the difference between another round of reading and an answer.
        assertEquals(
            "it gave up: $last · ticks=$ticks retries=${session?.retries} " +
                "retryFrom=${session?.retryFrom} window=${scene.settings.reconnectMs} " +
                "asked=${broken.attempts} lastPollAt=${session?.lastPollAt} " +
                "nextPollAt=${session?.nextPollAt} keepAlive=${scene.settings.keepAlive} " +
                "trail=${Trace.report().lines.filter { it.contains("agent-") }.takeLast(6)}",
            AgentSession.State.STOPPED,
            session?.state,
        )
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

        // **Moved away rather than deleted, because the test is about the path being gone.**
        // `deleteRecursively()` is a tree walk, and on a checkout with a `.git` in it the walk can
        // leave something behind — which fails the precondition below and says nothing about the
        // behaviour under test. A rename makes the path disappear in one step, which is exactly the
        // fact the tick is being asked about.
        val away = File(project.parentFile, "groceries-gone")
        assertTrue(
            "the folder could be moved away: ${project.list()?.joinToString()}",
            project.renameTo(away),
        )
        assertFalse("the folder is really gone before the tick", project.isDirectory)
        assertFalse(
            "and it is gone by the path the runner asks about, not only by this File object",
            scene.viewModel.workspace!!.projectDir("groceries").isDirectory,
        )
        // **What the tick will actually see**, printed before it runs. This test failed on one shard
        // and passed on the other for six runs in a row, which is the shape of a race rather than a
        // rule - so the failure has to say WHICH fact was wrong: the session not in the store, the
        // store not on disk, or the tick reading a different account folder.
        val store = AgentSessions(scene.viewModel.workspace!!.accountDir)
        val before = store.read().map { it.project }
        assertTrue(
            "the session is in the store before the tick, and the store is " +
                "${scene.viewModel.workspace!!.accountDir}: $before",
            before.contains("groceries"),
        )
        assertFalse(
            "and the runner would see the folder as gone: " +
                "projectDir=${scene.viewModel.workspace!!.projectDir("groceries")}",
            scene.viewModel.workspace!!.projectDir("groceries").isDirectory,
        )
        // **Every `AgentSessions` store in the account folder, because the tick's is built by the
        // wiring and is not necessarily the one this test holds.** The runner binds its store to
        // `ws.accountDir` at construction, and `runnerFor` builds its own `Workspace` — so "the store
        // has the session" and "the RUNNER's store has the session" are two different claims.
        val storeFile = File(scene.viewModel.workspace!!.stateDir, AgentSessions.AGENTS)
        assertTrue(
            "the session store is on disk at $storeFile: exists=${storeFile.isFile}",
            storeFile.isFile,
        )
        scene.viewModel.tickAgents(due)
        // **What the tick left on disk, asked immediately.** The screen's list is a snapshot taken
        // when the tick was started, so "the screen still shows it" cannot distinguish a tick that
        // did nothing from one whose removal has not been posted yet. The store can.
        val afterTick = AgentSessions(scene.viewModel.workspace!!.accountDir).read().map { it.project }
        // **The tick answers on the main thread.** It posts the new session list there, so the
        // screen's own state is only true once that post has run — asserting straight after the
        // call was asserting against the list from before it.
        // **When it fails, say WHO put the folder back.** `folder=true` on its own has now cost two
        // separate investigations, because a recreated folder looks the same whichever writer made
        // it. The contents name the writer: `agent/` is the record, `project_prompt.txt` is the prompt
        // layer, and a result file or `release/` is a tool. Asked inside the message, so a passing run
        // pays nothing and a failing one pays once.
        scene.await(
            "the tick to drop the session: before=$before, after=$afterTick, " +
                "folder=${scene.viewModel.workspace!!.projectDir("groceries").isDirectory}, " +
                "store=${storeFile.isFile}, " +
                "contents=" + scene.viewModel.workspace!!.projectDir("groceries").let { back ->
                    if (!back.isDirectory) {
                        "gone"
                    } else {
                        back.walkTopDown().drop(1).take(20).joinToString(" ") {
                            it.relativeTo(back).path + (if (it.isDirectory) "/" else "")
                        }
                    }
                },
        ) {
            scene.viewModel.sessionOf("groceries") == null
        }

        assertEquals("the session is gone", null, scene.viewModel.sessionOf("groceries"))
        assertFalse(
            "and nothing is left in the file",
            AgentSessions(scene.viewModel.workspace!!.accountDir).read().any { it.project == "groceries" },
        )
    }

    // ---- the flake, as a rule rather than a race -------------------------

    @Test
    fun `reading a project's instructions does not recreate a folder that is gone`() {
        // **This is the flake, written down as a rule.** `the screen's tick drops a session whose
        // folder was deleted` failed three times across three pushes and passed on every re-run, and
        // the cause was not the tick: the SERVICE runs its own `tick()` on a fifteen-second clock, a
        // warm ping there called `Agent.warm()`, that read the project layer, and the layer
        // `mkdirs()`ed the folder — recreating the very thing the tick was about to remove the
        // session for. Whichever thread got there first decided whether the test passed.
        //
        // Asked directly, with no clock and no threads: read the project layer of a folder that does
        // not exist, and assert nothing was made.
        val project = File(scene.viewModel.workspace!!.accountDir, "groceries")
        assertFalse("the folder is not there to begin with", project.exists())

        val said = AgentPrompt.project(project)

        assertTrue("the fallback is still returned, so a brief is never empty", said.isNotBlank())
        assertFalse(
            "and NOTHING was made: a reader must not recreate a project the person deleted",
            project.exists(),
        )
    }

    @Test
    fun `the system prompt is still written on first use, because its folder is the app's`() {
        // **The two layers differ here on purpose.** The system prompt's folder is made by
        // `Workspace.ensure()` on every refresh, so writing into it is right; the project's folder is
        // the person's, so writing into it is how a deleted project comes back. A single shared rule
        // for both would have to pick one of those, and either choice is wrong for the other.
        val system = File(scene.viewModel.workspace!!.accountDir, Workspace.SYSTEM_PROJECT)
        val file = File(system, AgentPrompt.SYSTEM_FILE)
        file.delete()

        val said = AgentPrompt.system(scene.viewModel.workspace!!.accountDir)

        assertTrue("the fallback is returned", said.isNotBlank())
        assertTrue("and it was written down, so the person can edit it", file.isFile)
    }
}
