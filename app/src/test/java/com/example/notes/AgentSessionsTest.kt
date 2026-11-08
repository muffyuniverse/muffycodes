package com.example.notes

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The sessions on disk: a session outlives the screen, and the file is what says so.
 *
 * Every test here is about **what survives** — a restart, a hand-edited file, a build that wrote
 * a value this one does not know — because the failure mode of state on disk is not "wrong now",
 * it is "gone tomorrow" or "the app will not open".
 */
class AgentSessionsTest {

    private lateinit var account: File
    private lateinit var store: AgentSessions

    @Before
    fun fresh() {
        account = File(System.getProperty("java.io.tmpdir"), "sessions-${System.nanoTime()}")
        Workspace(account).ensure()
        store = AgentSessions(account)
    }

    private val file: File get() = File(account, ".muffy/${AgentSessions.AGENTS}")

    @Test
    fun `a session is written where the app looks for it, and read back whole`() {
        val session = AgentSession(project = "shopping")
            .needingHelp(AgentSession.StopReason.PROVIDER_REFUSED, at = 1_700_000_000_000)
            .reminded()
            .reminded()

        store.put(session)
        val read = store.read().single()

        assertTrue("the file is in .muffy, beside the locks", file.isFile)
        assertEquals("stopped, needing help", read.state, AgentSession.State.STOPPED)
        assertEquals("and its clock came with it", 1_700_000_000_000, read.askedAt)
        assertEquals("and how many reminders were spent", 2, read.reminders)
        assertEquals("so the next one is the second", "2nd reminder · shopping: stopped, the pinned provider refused", read.notice())
    }

    @Test
    fun `the reconnect ladder's start survives the file, or the window never ends`() {
        // **The bug this pins was a field that existed only in memory.** `retryFrom` is when the
        // ladder began, and `failed()` measures the operator's window from it. Every tick reads the
        // session from this file — so a `retryFrom` that was never written came back as 0, `failed()`
        // read that as "the ladder began just now", and the window could never elapse: the session
        // retried for ever and nobody was ever told.
        //
        // It was found by a test that waited for a stop and never got one, with the session still
        // saying "idle · waiting" after forty attempts.
        val retrying = AgentSession("shopping")
            .polled(1_000)
            .failed(at = 2_000)
            .failed(at = 3_000)

        store.put(retrying)
        val read = store.read().single()

        assertEquals("the ladder is still in progress", AgentSession.Sub.RECONNECTING, read.sub)
        assertEquals("and it remembers when it began", 2_000, read.retryFrom)
        assertEquals("so the count of tries came too", 2, read.retries)
    }

    @Test
    fun `a session survives a restart, so an agent does not quietly forget it was asked`() {
        // The failure this exists for: the app is killed while the agent is waiting for an
        // answer, and on the next open the session has to still be `asked` with its clock — or
        // the person is reminded from minute zero of a question asked yesterday.
        store.put(AgentSession("shopping").needingHelp(AgentSession.StopReason.PROVIDER_REFUSED, at = 1_000).reminded())

        val reopened = AgentSessions(account).read().single()

        assertEquals(AgentSession.State.STOPPED, reopened.state)
        assertEquals("the reminder count survived too", 1, reopened.reminders)
        assertTrue("and the ladder continues where it was", reopened.reminderDue(1_000 + 2 * AgentSession.REMIND_MS))
    }

    @Test
    fun `two projects keep two sessions`() {
        store.put(AgentSession("shopping"))
        store.put(AgentSession("notes").concluded(0))

        val read = store.read()

        assertEquals("both", 2, read.size)
        assertEquals("and sorted, so the list does not jump", listOf("notes", "shopping"), read.map { it.project })
    }

    @Test
    fun `two writers at once never lose the other's session`() {
        // **The file has two writers by design** — the screen writes when the person starts or stops
        // an agent, and the runner writes on every poll from its own worker. Each write reads the
        // whole file and writes the whole file back, so without a lock shared by both instances the
        // second write silently undoes the first: an agent somebody just switched off comes back,
        // and one they just switched on never appears.
        val threads = 4
        val each = 15
        val start = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(threads)
        repeat(threads) { t ->
            Thread {
                start.await()
                val writer = AgentSessions(account)
                repeat(each) { i ->
                    writer.put(
                        AgentSession(
                            project = "p-$t-$i",
                            state = AgentSession.State.IDLE,
                            lastPollAt = 1_000,
                        ),
                    )
                }
                done.countDown()
            }.start()
        }
        start.countDown()
        done.await(30, java.util.concurrent.TimeUnit.SECONDS)

        val sessions = AgentSessions(account).read()
        assertEquals("every session written survives", threads * each, sessions.size)
        assertEquals("and none twice", sessions.size, sessions.map { it.project }.toSet().size)
    }

    @Test
    fun `writing one project does not forget another`() {
        // The trap: a write that serialises the one session it was given, deleting the rest of
        // the file. It would look right in every single-agent test.
        store.put(AgentSession("shopping"))
        store.put(AgentSession("notes"))

        store.put(AgentSession("notes").needingHelp(AgentSession.StopReason.PROVIDER_REFUSED, at = 5))

        assertEquals("both are still there", 2, store.read().size)
        assertEquals("and the second one changed", AgentSession.State.STOPPED, store.read().first { it.project == "notes" }.state)
    }

    @Test
    fun `a file that will not parse is empty, not a crash`() {
        // Hand-edited, half-written by a kill, or from another build. The app has to open.
        file.parentFile.mkdirs()
        file.writeText("this is not json, and it does not even try to be")

        assertEquals("no sessions", emptyList<AgentSession>(), store.read())

        store.put(AgentSession("shopping"))
        assertEquals("and it can be written again", 1, store.read().size)
    }

    @Test
    fun `a value this build does not know falls back rather than throwing`() {
        file.parentFile.mkdirs()
        file.writeText(
            JSONObject().put(
                "shopping",
                JSONObject()
                    .put("state", "SLEEPING")
                    .put("sub", "MEDITATING")
                    .put("reason", "BECAUSE")
                    .put("retries", -3),
            ).toString(),
        )

        val read = store.read().single()

        assertEquals("an unknown state is idle", AgentSession.State.IDLE, read.state)
        assertEquals("an unknown sub-state is waiting", AgentSession.Sub.WAITING, read.sub)
        assertEquals("an unknown reason is the general one", AgentSession.StopReason.ERROR, read.reason)
        assertEquals("and a negative retry count is none", 0, read.retries)
    }

    @Test
    fun `a project that is gone loses its session and nothing else`() {
        store.put(AgentSession("shopping"))
        store.put(AgentSession("notes"))

        store.remove("shopping")

        assertEquals("only the survivor", listOf("notes"), store.read().map { it.project })
        store.remove("never-existed")
        assertEquals("and removing nothing changes nothing", 1, store.read().size)
    }

    @Test
    fun `a session with no reason reads back with none`() {
        store.put(AgentSession("shopping"))

        assertNull("a running session has no reason", store.read().single().reason)
    }

    // ---- two branches are two agents (§13d) --------------------------------

    @Test
    fun `two branches of one project are two sessions, and neither overwrites the other`() {
        // **The whole point of the keying change.** Before this, `agents.json` was keyed by project, so
        // starting an agent on `feature` would have replaced main's session — and main's agent would
        // have disappeared with no message. The second write is the one that used to win.
        // **Two different states on purpose.** With both the same, "main's state survived" would pass
        // even if feature had overwritten main — the assertion has to be able to fail.
        store.put(AgentSession("Shopping", state = AgentSession.State.STOPPED))
        store.put(AgentSession("Shopping", branch = "feature", state = AgentSession.State.IDLE))

        val read = store.read()

        assertEquals("two rows", 2, read.size)
        assertEquals("main first, because it sorts first", "main", read[0].branch)
        assertEquals("then the branch", "feature", read[1].branch)
        assertEquals(
            "and main's state survived the second write",
            AgentSession.State.STOPPED,
            read[0].state,
        )
        assertEquals(
            "while feature has its own",
            AgentSession.State.IDLE,
            read[1].state,
        )
    }

    @Test
    fun `removing one branch leaves the other branch and the project alone`() {
        // **The two silent mistakes this signature prevents**: forgetting main when feature was meant
        // leaves an agent running, and forgetting the whole project kills an agent nobody asked about.
        store.put(AgentSession("Shopping"))
        store.put(AgentSession("Shopping", branch = "feature"))

        store.remove("Shopping", "feature")

        val read = store.read()
        assertEquals("one row left", 1, read.size)
        assertEquals("and it is main", "main", read[0].branch)
    }

    @Test
    fun `a branch whose name contains a slash keeps its whole name`() {
        // **`feature/x` is legal git**, so the key cannot be split on the last slash — and it must not
        // be split on every slash either. The project name cannot contain one, so the FIRST slash is
        // the boundary and everything after it is the branch.
        val session = AgentSession("Shopping", branch = "feature/x")
        store.put(session)

        val read = store.read().single()

        assertEquals("the project is the part before the first slash", "Shopping", read.project)
        assertEquals("and the branch is everything after it", "feature/x", read.branch)
    }

    @Test
    fun `the key is project-slash-branch, and main is not special`() {
        // **One shape**, so a reader never has to ask which kind of key it is looking at.
        assertEquals("Shopping/main", AgentSession.keyOf("Shopping", "main"))
        assertEquals("Shopping/feature", AgentSession.keyOf("Shopping", "feature"))
        assertEquals("Shopping/feature/x", AgentSession.keyOf("Shopping", "feature/x"))

        // The first slash is the boundary, because a project name cannot contain one and a branch can.
        assertEquals("Shopping", AgentSession.projectOf("Shopping/feature/x"))
        assertEquals("feature/x", AgentSession.branchOf("Shopping/feature/x"))

        // A key with no slash is a project with no branch said — which can only mean main.
        assertEquals("main", AgentSession.branchOf("Shopping"))
    }
}
