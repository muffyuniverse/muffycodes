package com.example.notes

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The clock on its own, with no screen behind it.
 *
 * This is the part the foreground service runs while nobody is looking: which session is due, what
 * a poll costs, and where the session ends up afterwards. It is tested without a view model because
 * a service has none — and because the two callers sharing one implementation is the whole point,
 * so the implementation had better work when it is the only thing running.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class AgentRunnerTest {

    private lateinit var root: File
    private lateinit var ws: Workspace
    private lateinit var git: ProjectGit
    private lateinit var settings: SettingsStore

    private val author = GitAuthor("Muffy", "muffy@example.com")

    private val app: android.app.Application get() = org.robolectric.RuntimeEnvironment.getApplication()

    /**
     * Counts keepalive pings — **and fails loudly if the runner asks a turn**, because it never
     * should: the tick's whole job is the cache, and a turn from here is a second door into a
     * conversation the person is not talking in.
     */
    /**
     * A door whose ping can be held open, so "did two go out at once" is observed rather than timed.
     *
     * **Not a sleep.** A test that proved serialism by waiting would pass on a fast machine and fail
     * on a slow one, and would be measuring the runner's speed rather than its shape. This holds the
     * first ping inside the door until the test lets it go, which makes the second ping's absence a
     * fact instead of a guess.
     */
    private class HeldDoor : Door {
        val pings = mutableListOf<Request>()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        override fun turn(request: Request): Result<Reply> = Result.success(Reply("nothing to do"))
        override fun ping(request: Request): Result<Unit> {
            pings.add(request)
            entered.countDown()
            release.await()
            return Result.success(Unit)
        }
    }

    private class PingDoor(private val answer: () -> Result<Unit> = { Result.success(Unit) }) : Door {
        val pings = mutableListOf<Request>()
        var turns = 0
        override fun turn(request: Request): Result<Reply> {
            turns++
            return Result.success(Reply("nothing to do"))
        }
        override fun ping(request: Request): Result<Unit> {
            pings.add(request)
            return answer()
        }
    }

    private val notices = mutableListOf<AgentSession>()
    private val runners = mutableListOf<AgentRunner>()

    @Before
    fun fresh() {
        root = File(System.getProperty("java.io.tmpdir"), "runner-${System.nanoTime()}")
        ws = Workspace(root)
        ws.ensure()
        ws.repoOf("shopping").mkdirs()
        ws.write(File(ws.repoOf("shopping"), "milk.txt"), "two litres")
        git = ProjectGit(ws, { null }, { author })
        git.init("shopping")
        // The settings store is a phone's preferences, so it needs a phone: the runner reads the
        // reminder interval and the two context limits from it, and a fake would test the fake.
        settings = SettingsStore(app, "runner-settings-${System.nanoTime()}")
    }

    @After
    fun clean() {
        runners.forEach { it.close() }
        root.deleteRecursively()
    }

    private fun runner(door: Door = PingDoor()): AgentRunner = AgentRunner(
        ws = ws,
        git = git,
        settings = settings,
        model = { ActiveModel("deepseek/deepseek-v4.1-flash-1017", "deepseek", "high") },
        door = { door },
        notify = { notices += it },
    ).also { runners += it }

    /** Waits until no poll is in flight, so an assertion is not read mid-turn. */
    private fun settle(runner: AgentRunner) {
        val deadline = System.currentTimeMillis() + 20_000
        while (runner.polling().isNotEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(5)
    }

    private fun on(project: String, now: Long) {
        AgentSessions(ws.accountDir).put(
            AgentSession(project = project, state = AgentSession.State.IDLE, lastPollAt = now),
        )
    }

    // ---- which session is due ----------------------------------------------

    @Test
    fun `asleep, a runner polls nothing and says nothing`() {
        on("shopping", now = 1_000)
        val door = PingDoor()
        val runner = runner(door)

        // A minute before it is due. This is the case that must be free: an idle phone that
        // spends a request a second is the bug the whole four-minute interval exists to avoid.
        runner.tick(1_000 + 60_000)
        settle(runner)

        assertTrue("no request", door.pings.isEmpty())
        assertEquals("and never a turn", 0, door.turns)
        assertTrue("and nothing said to anybody", notices.isEmpty())
        assertEquals("and the session is untouched", 1_000L, runner.sessions().single().lastPollAt)
    }

    @Test
    fun `at its own due time the conversation is warmed once, and nothing is asked`() {
        on("shopping", now = 1_000)
        val door = PingDoor()
        val runner = runner(door)
        val due = runner.sessions().single().nextPollAt

        runner.tick(due)
        settle(runner)

        assertEquals("one ping", 1, door.pings.size)
        assertEquals("and not one turn", 0, door.turns)
        val session = runner.sessions().single()
        assertEquals("claimed at the tick", due, session.lastPollAt)
        assertEquals("and still waiting", AgentSession.Sub.WAITING, session.sub)
    }

    @Test
    fun `two runners over one account do not poll the same session twice`() {
        // **The service and the screen share a clock.** A poll writes `lastPollAt` before the
        // request goes out, so the second ticker reads a session that is not due — no lock, and no
        // double spend. Without the claim, one person looking at their phone would cost two
        // requests every four minutes for as long as the app was open.
        on("shopping", now = 1_000)
        val floor = PingDoor()
        val screen = runner(floor)
        val service = runner(floor)
        val due = service.sessions().single().nextPollAt

        screen.tick(due)
        service.tick(due)
        settle(screen)
        settle(service)

        assertEquals("one ping between them", 1, floor.pings.size)
    }

    @Test
    fun `two projects due at once go out one at a time, and the second waits its turn`() {
        // **The operator's ruling, and the reason it is a test rather than a comment:** "keep it
        // serial - it will be easier to manage, predictable and simple". A single-threaded executor
        // is one word to change, so the decision needs something that fails when it is changed.
        //
        // What is being pinned is not speed. It is that the app never has two of its own requests
        // in flight at the same time: a ping buys warmth cheaply, and three at once is three times
        // the bill for the same warmth.
        on("shopping", now = 1_000)
        on("work", now = 1_000)
        val door = HeldDoor()
        val runner = runner(door)
        val due = runner.sessions().first().nextPollAt

        runner.tick(due)
        // The first ping is inside the door and cannot return. If the runner were parallel the
        // second would be inside it too, which is exactly what the assertion below would see.
        assertTrue("the first ping went out", door.entered.await(20, TimeUnit.SECONDS))
        assertEquals("and nothing else did", 1, door.pings.size)

        door.release.countDown()
        settle(runner)
        assertEquals("both went out in the end", 2, door.pings.size)
    }

    // ---- what the phone took, and what it did not ---------------------------

    @Test
    fun `a session the phone was not running is written down as a gap, and goes on`() {
        // A force-stop, an OEM battery manager, a Doze window the system never lifted: none of them
        // announce themselves, and all of them look the same from inside — a session whose last ping
        // is hours old. The row is how a hole becomes visible instead of looking like a check that
        // took no time; the session carries on, because "it must never stop until the operator
        // removes it" is the stronger rule.
        //
        // **The row does not wait for a ping to be worth writing.** Three hours of absence is also
        // past the keepalive's ceiling, so a gap read inside the ping path would have been lost —
        // and the cache was gone hours ago, so there is nothing to warm either.
        on("shopping", now = 1_000)
        val door = PingDoor()
        val runner = runner(door)

        runner.tick(1_000 + 3 * 60 * 60 * 1000L + 12 * 60_000L)
        settle(runner)

        val log = AgentLog.open(File(ws.accountDir, "shopping"))
        val gap = log.rows().single { it.name == "gap" }
        assertEquals(
            "how long, in words a person reads: ${gap.body}",
            "the phone did not run the agent for 3 h 12 min",
            gap.body,
        )
        assertEquals("and it is the app's row, not the model's", AgentLog.APP, gap.ctx)
        assertTrue("nothing was sent to a cache that has been cold for hours", door.pings.isEmpty())
        assertEquals("and the wait starts again from the wake", 1_000 + 3 * 60 * 60 * 1000L + 12 * 60_000L, runner.sessions().single().lastPollAt)
        assertEquals("idle", AgentSession.State.IDLE, runner.sessions().single().state)
        assertTrue(
            "and the trace says it",
            Trace.report().lines.any { it.contains("agent-gap") && it.contains("shopping") },
        )
    }

    @Test
    fun `a poll that is a little late is not the phone stopping anything`() {
        // A late wake, a network change, a short Doze window: ordinary, and a row about each of them
        // would bury the one that matters.
        on("shopping", now = 1_000)
        val runner = runner()

        runner.tick(1_000 + AgentSession.POLL_MS + 30_000L)
        settle(runner)

        assertTrue(
            "nothing to say",
            AgentLog.open(File(ws.accountDir, "shopping")).rows().none { it.name == "gap" },
        )
    }

    @Test
    fun `the tray's stop writes the same session the box does`() {
        // One act, one session write. The box's Stop also interrupts the request in flight, which is
        // the part a notification cannot reach; what it must not do is invent a second meaning for
        // "the person stopped it".
        on("shopping", now = 1_000)
        val runner = runner()

        runner.stop("shopping")

        val session = runner.sessions().single()
        assertEquals("stopped", AgentSession.State.STOPPED, session.state)
        assertEquals("by the person", AgentSession.StopReason.BY_OPERATOR, session.reason)
        assertTrue(
            "and the trace records the press",
            // The trace spells a press with the name slugged: `stop agent` is `stop-agent` there.
            Trace.report().lines.any { it.contains("TAP") && it.contains("stop-agent") },
        )
    }

    @Test
    fun `the service's tick drops a session whose folder is gone, and says why`() {
        on("shopping", now = 1_000)
        val runner = runner()
        File(ws.accountDir, "shopping").deleteRecursively()

        runner.tick(1_000 + AgentSession.POLL_MS)

        assertTrue("the session is gone", runner.sessions().isEmpty())
        assertTrue(
            "and the trace says why",
            Trace.report().lines.any { it.contains("agent-gone") && it.contains("shopping") },
        )
    }

    // ---- what a ping does to the session ------------------------------------

    // ---- a finished command wakes the agent ---------------------------------

    @Test
    fun `a finished background command wakes the agent with a turn, once`() {
        // **The one thing worth a turn the person did not ask for.** Its own work coming back: the
        // model is asked with the file named in the conversation, so it can read the result and act.
        on("shopping", now = 1_000)
        // **The registry's clock is the test's**, so a completion's time and a tick's are the same
        // axis: the watermark is a comparison of two numbers that have to mean the same thing.
        val bg = Background(launch = { it() }, now = { 1_001 })
        val door = ScriptedDoor(Reply("", listOf(Call("c1", "conclude", """{"text":"three files"}"""))))
        val runner = AgentRunner(
            ws = ws,
            git = git,
            settings = settings,
            model = { ActiveModel("deepseek/deepseek-v4.1-flash-1017", "deepseek", "high") },
            door = { door },
            notify = { notices += it },
            bg = bg,
        ).also { runners += it }

        // A command starts and lands while the agent is idle.
        bg.start(ws.projectDir("shopping"), Call("c9", "tree", "{}")) { "0 three files" }

        runner.tick(1_001)
        settle(runner)

        assertEquals("one turn was taken", 1, door.asked.size)
        assertTrue(
            "and it says what finished: ${door.asked.firstOrNull()?.messages?.last()?.text}",
            door.asked.first().messages.last().text.contains("agent/background_results/tree-c9.txt"),
        )
        val session = runner.sessions().single()
        assertEquals("its ending moved the session", AgentSession.Sub.FINISHED, session.sub)
        assertEquals("and the watermark moved with it", 1_001L, session.wokenThrough)

        // The same file does not wake it again, however many ticks follow.
        runner.tick(2_000)
        settle(runner)
        runner.tick(3_000)
        settle(runner)
        assertEquals("still one turn for one command", 1, door.asked.size)
    }

    @Test
    fun `a second command landing later wakes the agent again`() {
        // **The watermark is a time, not a flag.** A flag cleared by the first wake would lose the
        // second command, which is the ordinary case for a model that starts work in parallel.
        on("shopping", now = 1_000)
        var at = 5_000L
        val bg = Background(launch = { it() }, now = { at })
        val door = ScriptedDoor(
            Reply("", listOf(Call("c1", "conclude", """{"text":"one"}"""))),
            Reply("", listOf(Call("c2", "conclude", """{"text":"two"}"""))),
        )
        val runner = AgentRunner(
            ws = ws, git = git, settings = settings,
            model = { ActiveModel("deepseek/deepseek-v4.1-flash-1017", "deepseek", "high") },
            door = { door }, notify = { notices += it }, bg = bg,
        ).also { runners += it }

        bg.start(ws.projectDir("shopping"), Call("c1", "tree", "{}")) { "0 one" }
        runner.tick(1_001)
        settle(runner)
        assertEquals("the first turned it", 1, door.asked.size)

        // A second command finishes later than the watermark.
        at = 6_000
        bg.start(ws.projectDir("shopping"), Call("c2", "read", """{"path":"milk.txt"}""")) { "0 two litres" }
        runner.tick(6_001)
        settle(runner)

        assertEquals("the second turned it again", 2, door.asked.size)
        assertTrue(
            "and the note names the second file",
            door.asked.last().messages.last().text.contains("read-c2.txt"),
        )
    }

    @Test
    fun `a stopped session is not woken, and an asked one is`() {
        // **The person's stop wins, and an answered question does not block its own answer.** A
        // session they switched off is not woken for anything; one waiting on an answer is woken,
        // because the result may be exactly what it was waiting for.
        on("shopping", now = 1_000)
        var at = 5_000L
        val bg = Background(launch = { it() }, now = { at })
        val door = ScriptedDoor(
            // **The wake's own reply.** The session is put into `asked` by the test, because that is
            // the state being tested; the wake then answers for itself, which is what a real turn
            // does when the result it was waiting for arrives.
            Reply("", listOf(Call("c1", "conclude", """{"text":"that was the branch I meant"}"""))),
        )
        val runner = AgentRunner(
            ws = ws, git = git, settings = settings,
            model = { ActiveModel("deepseek/deepseek-v4.1-flash-1017", "deepseek", "high") },
            door = { door }, notify = { notices += it }, bg = bg,
        ).also { runners += it }

        // Asked, and then a command lands.
        AgentSessions(ws.accountDir).put(
            AgentSessions(ws.accountDir).read().single().asked(1_000),
        )
        bg.start(ws.projectDir("shopping"), Call("c1", "tree", "{}")) { "0 one" }
        runner.tick(5_001)
        settle(runner)

        assertEquals("a question is woken", 1, door.asked.size)
        assertEquals("and the wake's ending replaced it", AgentSession.Sub.FINISHED, runner.sessions().single().sub)

        // Stopped, and then a command lands: nothing is spent.
        runner.stop("shopping")
        at = 9_000
        bg.start(ws.projectDir("shopping"), Call("c2", "tree", "{}")) { "0 two" }
        runner.tick(9_001)
        settle(runner)

        assertEquals("a stopped session is left alone", 1, door.asked.size)
    }

    @Test
    fun `a result that landed while the agent was stopped is collected when it is switched on`() {
        // **Stopping pauses the debt; switching on collects it.** The completion stays in the registry
        // while the session is off, so a command that landed in that gap is not lost — the fresh
        // session `startAgent` writes carries no watermark, and the next tick wakes it with the file.
        // Nobody has to remember that a result was waiting.
        on("shopping", now = 1_000)
        var at = 5_000L
        val bg = Background(launch = { it() }, now = { at })
        val door = ScriptedDoor(Reply("", listOf(Call("c1", "conclude", """{"text":"read it"}"""))))
        val runner = AgentRunner(
            ws = ws, git = git, settings = settings,
            model = { ActiveModel("deepseek/deepseek-v4.1-flash-1017", "deepseek", "high") },
            door = { door }, notify = { notices += it }, bg = bg,
        ).also { runners += it }

        runner.stop("shopping")
        bg.start(ws.projectDir("shopping"), Call("c1", "tree", "{}")) { "0 one" }
        runner.tick(5_001)
        settle(runner)
        assertEquals("a stopped session spends nothing", 0, door.asked.size)

        // The person switches it back on: a new session record, which is what `startAgent` writes.
        on("shopping", now = 60_000)
        runner.tick(60_001)
        settle(runner)

        assertEquals("switching on collects it", 1, door.asked.size)
        assertTrue(
            "and names the file that arrived while it was off: " +
                door.asked.single().messages.last().text,
            door.asked.single().messages.last().text.contains("tree-c1.txt"),
        )
    }

    @Test
    fun `two commands that land together are one turn, and it names both`() {
        // **One wake can be owed several results.** A model that starts two commands before its
        // turn ends has two files waiting; waking twice would spend two requests to read two files
        // it could have read in one. The note lists them both, and the second file does not wake it
        // again afterwards because the watermark is the tick's time, past both completions.
        on("shopping", now = 1_000)
        val bg = Background(launch = { it() }, now = { 5_000 })
        val door = ScriptedDoor(Reply("", listOf(Call("c1", "conclude", """{"text":"both read"}"""))))
        val runner = AgentRunner(
            ws = ws, git = git, settings = settings,
            model = { ActiveModel("deepseek/deepseek-v4.1-flash-1017", "deepseek", "high") },
            door = { door }, notify = { notices += it }, bg = bg,
        ).also { runners += it }

        bg.start(ws.projectDir("shopping"), Call("c1", "tree", "{}")) { "0 one" }
        bg.start(ws.projectDir("shopping"), Call("c2", "read", """{"path":"milk.txt"}""")) { "0 two" }
        runner.tick(5_001)
        settle(runner)

        assertEquals("one turn for two results", 1, door.asked.size)
        val said = door.asked.single().messages.last().text
        assertTrue("it says there were two: $said", said.startsWith("2 background commands have finished"))
        assertTrue("naming the first", said.contains("tree-c1.txt"))
        assertTrue("and the second", said.contains("read-c2.txt"))

        runner.tick(9_000)
        settle(runner)
        assertEquals("and neither wakes it again", 1, door.asked.size)
    }

    @Test
    fun `a session a screen is holding is not woken under it`() {
        // **A turn already in flight is a turn.** The screen takes the hold before it asks, so the
        // tick cannot put a second request on the same conversation — two endings for one session,
        // two sets of results, and the last one to land wins. The completion is not lost: it waits.
        on("shopping", now = 1_000)
        val bg = Background(launch = { it() }, now = { 5_000 })
        val door = ScriptedDoor(Reply("", listOf(Call("c1", "conclude", """{"text":"now I can"}"""))))
        val runner = AgentRunner(
            ws = ws, git = git, settings = settings,
            model = { ActiveModel("deepseek/deepseek-v4.1-flash-1017", "deepseek", "high") },
            door = { door }, notify = { notices += it }, bg = bg,
        ).also { runners += it }

        val release = runner.hold("shopping")
        bg.start(ws.projectDir("shopping"), Call("c1", "tree", "{}")) { "0 one" }
        runner.tick(5_001)
        assertEquals("nothing went out while the screen had it", 0, door.asked.size)

        release()
        runner.tick(5_002)
        settle(runner)
        assertEquals("and it goes out the moment the screen lets go", 1, door.asked.size)
    }

    @Test
    fun `a command that finished for a project that is gone wakes nothing`() {
        // The folder is the project. A session whose folder was deleted — by the person, by another
        // app, by a phone tidying up — is removed rather than polled into a directory that is not
        // there, and its waiting result has nowhere to be read from.
        on("shopping", now = 1_000)
        val bg = Background(launch = { it() }, now = { 5_000 })
        val door = ScriptedDoor(Reply("", listOf(Call("c1", "conclude", """{"text":"no"}"""))))
        val runner = AgentRunner(
            ws = ws, git = git, settings = settings,
            model = { ActiveModel("deepseek/deepseek-v4.1-flash-1017", "deepseek", "high") },
            door = { door }, notify = { notices += it }, bg = bg,
        ).also { runners += it }

        bg.start(ws.projectDir("shopping"), Call("c1", "tree", "{}")) { "0 one" }
        ws.projectDir("shopping").deleteRecursively()
        runner.tick(5_001)
        settle(runner)

        assertEquals("no turn was taken", 0, door.asked.size)
        assertTrue("and the session went with the folder", runner.sessions().isEmpty())
    }

    @Test
    fun `a wake outranks a ping, and the ping waits its own interval afterwards`() {
        // Both are due at once — a command landed and four minutes have passed — and the wake is the
        // one worth paying for. Afterwards the clock has moved, so the ping is not fired on its heels.
        on("shopping", now = 1_000)
        val bg = Background(launch = { it() }, now = { 1_000 + 2 * AgentSession.POLL_MS })
        val door = ScriptedDoor(Reply("", listOf(Call("c1", "conclude", """{"text":"done"}"""))))
        val runner = AgentRunner(
            ws = ws, git = git, settings = settings,
            model = { ActiveModel("deepseek/deepseek-v4.1-flash-1017", "deepseek", "high") },
            door = { door }, notify = { notices += it }, bg = bg,
        ).also { runners += it }

        bg.start(ws.projectDir("shopping"), Call("c1", "tree", "{}")) { "0 one" }
        runner.tick(1_000 + 2 * AgentSession.POLL_MS)
        settle(runner)

        assertEquals("a turn, not a ping", 1, door.asked.size)
        assertEquals("and no ping at all", 0, door.pings.size)
    }

    @Test
    fun `the switch in settings stops every ping on the phone`() {
        // **One switch, and it is the person's.** Off means nothing goes out while the agent waits:
        // no cache is kept warm, nothing is spent, and the conversation is exactly as the last turn
        // left it — the next sentence they type pays for a fresh prompt, which is the trade.
        on("shopping", now = 1_000)
        val door = PingDoor()
        settings.keepWarm(false)
        val runner = runner(door)

        // **Two intervals, not ten.** Ten would be a gap — the phone having been asleep — and the
        // gap's own row moves the clock whatever the switch says.
        runner.tick(1_000 + 2 * AgentSession.POLL_MS)
        settle(runner)

        assertTrue("nothing was sent", door.pings.isEmpty())
        assertEquals("and the clock did not move", 1_000L, runner.sessions().single().lastPollAt)

        // Back on, and the same tick that did nothing now warms.
        settings.keepWarm(true)
        runner.tick(1_000 + 2 * AgentSession.POLL_MS)
        settle(runner)
        assertEquals("one ping", 1, door.pings.size)
    }

    @Test
    fun `a ping leaves the session exactly where it was, and tells nobody`() {
        // **A ping is not a turn.** It cannot ask, cannot conclude and cannot stop anything: the
        // session's state, sub-state and retries are what the last real turn left, and the only
        // thing a ping moves is the clock.
        on("shopping", now = 1_000)
        val waiting = runner(PingDoor())
        waiting.tick(1_000 + AgentSession.POLL_MS)
        settle(waiting)

        val session = waiting.sessions().single()
        assertEquals("idle", AgentSession.State.IDLE, session.state)
        assertEquals("waiting, as it was", AgentSession.Sub.WAITING, session.sub)
        assertEquals("and the clock moved", 1_000 + AgentSession.POLL_MS, session.lastPollAt)
        assertTrue("and nobody was told", notices.isEmpty())
    }

    @Test
    fun `a ping that does not arrive is missed, and the session is not stopped for it`() {
        // A provider that will not take a one-token answer, a tunnel, a Doze window: a courtesy that
        // did not arrive. Nothing is retried in a burst, nothing is written into the record, and the
        // session is where it was — the next tick tries again an interval later.
        on("shopping", now = 1_000)
        val door = PingDoor { Result.failure(IllegalStateException("OpenRouter answered 400")) }
        val runner = runner(door)

        runner.tick(1_000 + AgentSession.POLL_MS)
        settle(runner)

        val session = runner.sessions().single()
        assertEquals("still on", AgentSession.State.IDLE, session.state)
        assertEquals("not even reconnecting: a ping has no ladder", 0, session.retries)
        assertEquals("and the clock moved so the next try is an interval away", 1_000 + AgentSession.POLL_MS, session.lastPollAt)
        assertTrue(
            "the trace says it did not arrive",
            Trace.report().lines.any { it.contains("agent-warm-failed") },
        )
    }

    @Test
    fun `a key that was refused goes on the ladder, and the outage is written down once`() {
        // **The one refusal a ping may act on**, because it will not heal by waiting. The ladder is
        // the app's usual one, so the person learns about a dead key with the session stopped — and
        // the outage is one row and a count rather than eight events.
        on("shopping", now = 1_000)
        var down = true
        val door = PingDoor {
            if (down) Result.failure(Refused(401, "OpenRouter answered 401: no such key"))
            else Result.success(Unit)
        }
        val runner = runner(door)
        val log = AgentLog.open(File(ws.accountDir, "shopping"))

        runner.tick(1_000 + AgentSession.POLL_MS)
        settle(runner)
        assertEquals("one try", 1, runner.sessions().single().retries)
        assertEquals("and the refusal is the session's words", true, runner.sessions().single().lastWords.contains("401"))

        repeat(2) {
            val at = runner.sessions().single().nextPollAt
            runner.tick(at)
            settle(runner)
        }
        assertEquals("after three attempts", 3, runner.sessions().single().retries)
        assertEquals(
            "the first failure is in the record, and only it",
            1,
            log.rows().count { it.name == "stopped" },
        )

        // And it comes back.
        down = false
        runner.tick(runner.sessions().single().nextPollAt)
        settle(runner)

        val session = runner.sessions().single()
        assertEquals("the ladder is cleared", 0, session.retries)
        assertEquals("and it is waiting again", AgentSession.Sub.WAITING, session.sub)
        val back = log.rows().single { it.name == "retry" }
        assertEquals("one line, with the count", "reconnected after 3 tries", back.body)
        assertEquals("and it is evidence, not context", 0, log.contextRows().count { it.name == "retry" })
    }

    @Test
    fun `a session that asked is warmed too, and a stopped one is not`() {
        // **The two states that were left to go cold.** A question is the thing most likely to be
        // answered next, and a concluded agent is one the person will add to — both had no ping at
        // all, because a poll was something only `wait` asked for.
        on("shopping", now = 1_000)
        val sessions = AgentSessions(ws.accountDir)
        sessions.put(sessions.read().single().asked(1_000))
        val door = PingDoor()
        val runner = runner(door)

        runner.tick(1_000 + AgentSession.POLL_MS)
        settle(runner)
        assertEquals("a question is kept warm", 1, door.pings.size)
        assertEquals("and it is still a question", AgentSession.State.ASKED, runner.sessions().single().state)

        // The person stops it: nothing more is spent on a conversation they switched off.
        runner.stop("shopping")
        runner.tick(1_000 + 10 * AgentSession.POLL_MS)
        settle(runner)
        assertEquals("and a stopped session is left alone", 1, door.pings.size)
    }

    @Test
    fun `the reminder is the settings' interval, repeated, and it stops when answered`() {
        on("shopping", now = 1_000)
        AgentSessions(ws.accountDir).put(
            AgentSessions(ws.accountDir).read().single().asked(2_000),
        )
        settings.keepRemind(5)
        val runner = runner()

        // The question itself was announced when it was asked; what the runner adds is the
        // ladder behind it. So the first repeat is one interval later, and the second is two.
        runner.tick(2_000 + 4 * 60_000)
        assertEquals("four minutes is not five", 0, notices.size)

        runner.tick(2_000 + 5 * 60_000)
        assertEquals("five is", 1, notices.size)
        assertEquals("and it says which one it is", "1st", notices.last().notice()!!.take(3))

        runner.tick(2_000 + 10 * 60_000)
        assertEquals("the ladder continues while nobody answers", 2, notices.size)

        // **A tick can leave a poll in flight, and that poll writes the session when it finishes.**
        // Answering while one is running let the worker put back the copy it had read before the
        // answer — asked, reminders and all — and notify on it. Waiting here is waiting for the app
        // to be quiet, which is what the next line is a statement about.
        settle(runner)

        // Answering it — the session stops needing anybody — ends the ladder.
        val asked = runner.sessions().single()
        AgentSessions(ws.accountDir).put(asked.resumed(2_000 + 11 * 60_000))
        runner.tick(2_000 + 20 * 60_000)
        settle(runner)

        assertEquals("nothing more once dealt with", 2, notices.size)
    }

    @Test
    fun `the timeout and the result length are the person's numbers, clamped to what works`() {
        // Both were constants before the audit: two minutes for an answer and twenty thousand
        // characters for a result. The brief asked for them as fields, and the clamps are the part
        // that matters — a timeout of zero retries for ever, and a result of zero is a hang.
        // **In seconds, like every other limit on the screen.** It was minutes and sat beside two
        // limits in seconds, which is what "how wait for the model is different from stop command
        // after?" was really asking about - so the default is 240, not 4.
        assertEquals("four minutes by default", 240, settings.timeoutSeconds)
        assertEquals("six thousand tokens by default", 6_000, settings.maxResult)

        settings.keepTimeout(0)
        assertEquals("under a minute cannot finish", 60, settings.timeoutSeconds)
        settings.keepTimeout(60_000)
        assertEquals("half an hour is where it stops holding the worker", 1_800, settings.timeoutSeconds)

        settings.keepMaxResult(0)
        assertEquals("a result of nothing is a hang", 1_000, settings.maxResult)
        settings.keepMaxResult(99_000_000)
        // **The field's own bound, not the one that matters.** What a result may really carry is the
        // model's input window, applied where the agent is built; this is the largest window the app
        // can be pointed at, so the field can never be the limit the window should be.
        assertEquals("and the field stops at the largest window there is", 4_194_304, settings.maxResult)
    }

    @Test
    fun `a runner with nothing to do leaves every file alone`() {
        val runner = runner()
        val before = File(ws.accountDir, ".muffy/${AgentSessions.AGENTS}")
        assertTrue("no state file yet", !before.isFile)

        runner.tick(1_000)

        assertTrue("and none written for an account with no sessions", !before.isFile)
        assertNull("nothing to report", runner.sessions().firstOrNull())
    }
}
