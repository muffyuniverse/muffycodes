package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
        val said = Stopped.body("clone", Deadline(60_000L))
        assertTrue("it is a failure: $said", said.startsWith(AgentLog.FAILURE))
        assertTrue("it names the command: $said", said.contains("clone"))
        assertTrue("it says it was stopped: $said", said.contains("stopped after 60 s"))
        assertTrue("and what was left: $said", said.contains("nothing was kept"))
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
