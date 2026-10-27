package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The deadline a command asks about, and the sentence it produces.
 *
 * **A deadline is checked, not enforced.** These tests are about the two things that check it — the
 * JGit monitor and the restore loop — and about the words a stopped command leaves behind, which is
 * the only part the person ever reads.
 */
class TimeoutsTest {

    @Test
    fun `zero means no deadline, and that is not the same as expired`() {
        // **The distinction the whole setting rests on.** A command with no limit must never stop
        // itself, and "expired" answering true at zero would stop every command on every phone.
        val none = Deadline.none()
        assertFalse("no limit never expires", none.expired)
        assertFalse("and not after a wait either", Deadline(0L).expired)
    }

    @Test
    fun `a deadline that has passed says so`() {
        val past = Deadline(20L)
        Thread.sleep(40)
        assertTrue("20 ms plus a 40 ms wait is past it", past.expired)
    }

    @Test
    fun `a deadline that has not passed says nothing`() {
        assertFalse(Deadline(60_000L).expired)
    }

    @Test
    fun `the stopped sentence says how long, and that nothing was kept`() {
        val said = Stopped.body("restore", Deadline(60_000L))
        assertTrue("it is a failure: $said", said.startsWith(AgentLog.FAILURE))
        assertTrue("it names the command: $said", said.contains("restore"))
        assertTrue("it says it was stopped: $said", said.contains("stopped after 60 s"))
        assertTrue("and what was left: $said", said.contains("nothing was kept"))
    }

    @Test
    fun `a command that is still running says where its result will be, and when it arrives`() {
        // **Report 13's sentence.** A command that outlasts the wait is not thrown away: the work
        // carries on in the background and its result arrives with the next batch of results. The
        // sentence has to name the file, because the file already exists by the time this is said.
        val said = Stopped.stillRunning("read", Deadline(60_000L), "agent/background_results/read-c1.txt")

        assertTrue("it is not a failure: $said", said.startsWith(AgentLog.SUCCESS))
        assertTrue("it names the command: $said", said.contains("read"))
        assertTrue("and how long was allowed: $said", said.contains("60 s"))
        assertTrue("it says the work carries on: $said", said.contains("still running"))
        assertTrue("and where the result will be: $said", said.contains("agent/background_results/read-c1.txt"))
        assertTrue(
            "and that it arrives with other results: $said",
            said.contains("next turns with other call results"),
        )
        assertFalse("it does not say the work was lost: $said", said.contains("nothing was kept"))
    }

    @Test
    fun `a limit that ran out is a throw, so every command can ask the same question`() {
        val past = Deadline(1L)
        Thread.sleep(5)
        try {
            past.check("read")
            throw AssertionError("check should have thrown")
        } catch (e: StoppedException) {
            assertTrue("with the sentence a result carries: ${e.message}", e.message!!.contains("stopped"))
        }
    }

    @Test
    fun `a stopped delete says part of it is gone instead of claiming nothing was kept`() {
        // **The one command whose sentence is different, because it is the one that can stop half
        // way.** Every other command that can be stopped writes nothing or writes all at once.
        val said = Stopped.partway("delete", Deadline(60_000L))
        assertTrue("it is a failure: $said", said.startsWith(AgentLog.FAILURE))
        assertTrue("it names the command: $said", said.contains("delete"))
        assertTrue("it says part of it is gone: $said", said.contains("already gone"))
        assertFalse("and does not claim nothing happened: $said", said.contains("nothing was kept"))
    }

    @Test
    fun `a foreground read stops on the operator's deadline, not only a slow command`() {
        // **The reason LONG had to go.** Nothing bounds a read by the size of the file any more, so
        // a read with no deadline is a turn that does not come back. One millisecond is already past
        // by the time the file has been opened and its path checked.
        val account = File(System.getProperty("java.io.tmpdir"), "timeouts-" + System.nanoTime())
        account.mkdirs()
        val ws = Workspace(account)
        val project = File(account, "shopping")
        project.mkdirs()
        File(project, "huge.log").writeText((1..20_000).joinToString("\n") { "line $it" })
        val git = ProjectGit(ws, { null }, { GitAuthor("Muffy", "muffy@example.com") })
        val log = AgentLog.open(project)

        val body = AgentTools(ws, "shopping", git, log)
            .run(Call("c1", "read", """{"path":"huge.log"}"""), Deadline(1))

        assertTrue("it is stopped rather than answered: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("and says so: $body", body.contains("stopped"))
    }

    @Test
    fun `a limit reads in the unit the operator typed, which is seconds`() {
        // **One unit, and it is the one in the row.** A limit of one minute was reported as
        // "1 min" while the field the person filled in says seconds, and two limits of the same
        // length then read differently depending on which branch of a `when` they fell into.
        assertEquals("60 s", Deadline.seconds(60_000L))
        assertEquals("180 s", Deadline.seconds(180_000L))
        assertEquals("45 s", Deadline.seconds(45_000L))
    }
}
