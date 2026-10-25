package com.example.notes

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

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

    /** Answers every request with `wait`, and counts them. */
    private class WaitDoor : Door {
        val asked = mutableListOf<Request>()
        override fun turn(request: Request): Result<Reply> {
            asked.add(request)
            return Result.success(Reply("", listOf(Call("call_${asked.size}", "wait", "{}"))))
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

    private fun runner(door: Door = WaitDoor()): AgentRunner = AgentRunner(
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
        val door = WaitDoor()
        val runner = runner(door)

        // A minute before it is due. This is the case that must be free: an idle phone that
        // spends a request a second is the bug the whole four-minute interval exists to avoid.
        runner.tick(1_000 + 60_000)
        settle(runner)

        assertTrue("no request", door.asked.isEmpty())
        assertTrue("and nothing said to anybody", notices.isEmpty())
        assertEquals("and the session is untouched", 1_000L, runner.sessions().single().lastPollAt)
    }

    @Test
    fun `at its own due time a runner polls once and writes it down`() {
        on("shopping", now = 1_000)
        val door = WaitDoor()
        val runner = runner(door)
        val due = runner.sessions().single().nextPollAt

        runner.tick(due)
        settle(runner)

        assertEquals("one request", 1, door.asked.size)
        val session = runner.sessions().single()
        assertEquals("claimed at the tick", due, session.lastPollAt)
        assertEquals("and waiting again", AgentSession.Sub.WAITING, session.sub)
    }

    @Test
    fun `two runners over one account do not poll the same session twice`() {
        // **The service and the screen share a clock.** A poll writes `lastPollAt` before the
        // request goes out, so the second ticker reads a session that is not due — no lock, and no
        // double spend. Without the claim, one person looking at their phone would cost two
        // requests every four minutes for as long as the app was open.
        on("shopping", now = 1_000)
        val floor = WaitDoor()
        val screen = runner(floor)
        val service = runner(floor)
        val due = service.sessions().single().nextPollAt

        screen.tick(due)
        service.tick(due)
        settle(screen)
        settle(service)

        assertEquals("one request between them", 1, floor.asked.size)
    }

    @Test
    fun `a project that is gone takes its session with it`() {
        on("shopping", now = 1_000)
        val runner = runner()
        File(ws.projectsDir, "shopping").deleteRecursively()

        runner.tick(1_000 + AgentSession.POLL_MS)

        assertTrue("the session is gone", runner.sessions().isEmpty())
        assertTrue(
            "and the trace says why",
            Trace.report().lines.any { it.contains("agent-gone") && it.contains("shopping") },
        )
    }

    // ---- what a poll does to the session -----------------------------------

    @Test
    fun `a poll that waits leaves the session idle, and one that asks wakes the person`() {
        on("shopping", now = 1_000)
        val waiting = runner(WaitDoor())
        waiting.tick(1_000 + AgentSession.POLL_MS)
        settle(waiting)
        assertEquals("idle", AgentSession.State.IDLE, waiting.sessions().single().state)
        assertTrue("and nobody was told", notices.isEmpty())

        // Now a poll whose answer is a question.
        on("shopping", now = 1_000)
        val asking = runner(
            ScriptedDoor(Reply("", listOf(Call("c1", "ask", """{"question":"Which branch?"}"""))))
        )
        asking.tick(1_000 + AgentSession.POLL_MS)
        settle(asking)

        val session = asking.sessions().single()
        assertEquals("asked", AgentSession.State.ASKED, session.state)
        assertEquals("and the person was told once", 1, notices.size)
        assertTrue("in the words the reminder uses", notices.single().notice()!!.contains("asked something"))
    }

    @Test
    fun `a poll that cannot reach the provider reconnects rather than stopping`() {
        on("shopping", now = 1_000)
        var attempts = 0
        val door = object : Door {
            override fun turn(request: Request): Result<Reply> {
                attempts++
                return Result.failure(IllegalStateException("OpenRouter answered 503"))
            }
        }
        val runner = runner(door)

        runner.tick(1_000 + AgentSession.POLL_MS)
        settle(runner)

        val session = runner.sessions().single()
        assertEquals("still on", AgentSession.State.IDLE, session.state)
        assertEquals("reconnecting", AgentSession.Sub.RECONNECTING, session.sub)
        assertEquals("after one try", 1, session.retries)
        assertTrue("and the next attempt is seconds away, not minutes", session.nextPollAt < 1_000 + AgentSession.POLL_MS + 10_000)
        // A session that needs nobody does not wake anybody: a tunnel is not a question.
        assertTrue("and nobody was woken up for it", notices.isEmpty())
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

        // Answering it — the session stops needing anybody — ends the ladder.
        val asked = runner.sessions().single()
        AgentSessions(ws.accountDir).put(asked.waited(2_000 + 11 * 60_000))
        runner.tick(2_000 + 20 * 60_000)

        assertEquals("nothing more once dealt with", 2, notices.size)
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
