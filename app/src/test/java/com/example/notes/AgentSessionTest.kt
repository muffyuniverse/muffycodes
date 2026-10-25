package com.example.notes

import com.example.notes.AgentSession.State
import com.example.notes.AgentSession.StopReason
import com.example.notes.AgentSession.Sub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session's rules, and an attempt to break each one.
 *
 * **Four minutes and one hour are tested by moving a number**, which is the whole reason the state
 * machine takes the clock as a parameter: a test that waited for the real interval would not be
 * run, and a rule that is not run is a rule nobody has checked. Every test here is written as a
 * falsification — the thing that would go wrong if the guard were missing — because "the poll
 * happens on time" is easy to believe and hard to check, and "the poll does not happen one
 * millisecond early" is the claim that catches an off-by-one.
 */
class AgentSessionTest {

    private fun session() = AgentSession(project = "shopping")

    private val minute = 60_000L

    // ---- the poll's clock ---------------------------------------------------

    @Test
    fun `a poll is due after four minutes and not a millisecond before`() {
        val s = session().polled(at = 1_000).waited(1_000)

        assertFalse(
            "not at four minutes minus one",
            s.pollDue(1_000 + AgentSession.POLL_MS - 1),
        )
        assertTrue("but at four minutes exactly", s.pollDue(1_000 + AgentSession.POLL_MS))
    }

    @Test
    fun `the next poll is one interval after the last one, not one after now`() {
        // A clock consulted at "now + interval" drifts by however late the tick was: four minutes
        // of work plus four minutes of waiting is not a four-minute interval, and an agent left
        // overnight would poll half as often as it promised.
        val s = session().polled(at = 0).waited(0)
        val late = 0 + AgentSession.POLL_MS + 9_000

        assertEquals("the interval starts at the poll", AgentSession.POLL_MS, s.nextPollAt)
        assertEquals("so a late tick does not move it", AgentSession.POLL_MS, s.polled(late).nextPollAt - late)
    }

    @Test
    fun `an agent that asked, concluded or is reconnecting is not polled`() {
        val asked = session().asked(at = 10)
        assertFalse("asked is waiting for a person, not for the clock", asked.pollDue(10 + 10 * minute))

        val done = session().concluded()
        assertFalse("finished has nothing to do", done.pollDue(10 + 10 * minute))

        val retrying = session().polled(0).failed(at = AgentSession.POLL_MS)
        assertFalse("a retry waits for its own ladder, not the interval", retrying.pollDue(AgentSession.POLL_MS + 500))
        assertTrue("and fires at the retry's time", retrying.pollDue(AgentSession.POLL_MS + 1_000))
    }

    @Test
    fun `one dropped poll does not end the session, and the ladder backs off to four seconds`() {
        // The retry ladder exists for a tunnel through a train. If a failure stopped the session,
        // the design's own promise — "must never stop until the operator removes it" — would be
        // false the first time a phone changed networks.
        val first = session().polled(0).failed(at = 300_000)
        assertTrue("still running", first.running)
        assertEquals("reconnecting", Sub.RECONNECTING, first.sub)
        assertEquals("one second", 1_000L, first.nextPollAt - 300_000)

        val second = first.failed(301_000)
        assertEquals("two seconds", 2_000L, second.nextPollAt - 301_000)
        val third = second.failed(303_000)
        assertEquals("four seconds", 4_000L, third.nextPollAt - 303_000)
        val fourth = third.failed(307_000)
        assertEquals("and no further", 4_000L, fourth.nextPollAt - 307_000)
        assertEquals("which is what the label says", "idle · reconnecting (4 s)", fourth.label())
    }

    @Test
    fun `a timer that fires twice in one interval polls once`() {
        // The shell is allowed to tick often; the session is the thing that decides. A poll that
        // spent money twice per interval would be the app's own bug, not the operator's.
        val s = session().polled(at = 0)
        assertFalse("not again in the same interval", s.pollDue(AgentSession.POLL_MS - 1))
        assertTrue("and the first moment of the next one", s.pollDue(AgentSession.POLL_MS))
    }

    // ---- the reminder ladder ------------------------------------------------

    @Test
    fun `a question reminds once an interval has passed, and not before`() {
        val asked = session().asked(at = 0)

        assertFalse("not a second before", asked.reminderDue(AgentSession.REMIND_MS - 1))
        assertTrue("and at the interval", asked.reminderDue(AgentSession.REMIND_MS))
    }

    @Test
    fun `each reminder moves the ladder by exactly one step`() {
        val once = session().asked(at = 0).reminded()

        assertFalse(
            "the second one is a whole interval after the first",
            once.reminderDue(AgentSession.REMIND_MS + 1),
        )
        assertTrue("and not before", once.reminderDue(2 * AgentSession.REMIND_MS))
    }

    @Test
    fun `a clock that went backwards does not fire reminders`() {
        // Time moves back: a timezone, a device synced to a different server, a person fixing the
        // date. `at - askedAt` going negative is not a reason to speak, and a reminder storm is
        // exactly what a naive comparison would produce on the next forward jump.
        val asked = session().asked(at = 5 * 60 * minute)

        assertFalse("before it was asked", asked.reminderDue(at = 0))
        assertFalse("and still not", asked.reminderDue(at = 5 * 60 * minute - 1))
    }

    @Test
    fun `an hour-long interval of zero says it once and never again`() {
        // A person may decide they do not want to be reminded. That is a setting, not a bug, and
        // the very first notice still happens — the session tells them when it happens.
        val asked = session().asked(at = 0)

        assertFalse("no reminder ever", asked.reminderDue(10 * 24 * 60 * minute, intervalMs = 0))
        assertEquals("but it is still worth one sentence", "shopping: the agent asked something", asked.notice())
    }

    @Test
    fun `a healthy agent is silent, however long it waits`() {
        val waiting = session().polled(0).waited(0)

        assertNull("nothing to say about an agent in wait", waiting.notice())
        assertFalse("and nothing to remind", waiting.reminderDue(100 * 60 * minute))
        assertFalse("it is not on the strip's list either", waiting.needsYou)
    }

    @Test
    fun `a stopped session speaks only when the person did not stop it`() {
        val byThem = session().stopped(StopReason.BY_OPERATOR)
        assertFalse("they were there", byThem.needsYou)
        assertNull("so nothing to say", byThem.notice())
        assertEquals("and the strip does not count it", "", AgentSession.strip(listOf(byThem)))

        val byThePhone = session().stopped(StopReason.BY_THE_PHONE)
        assertTrue("this one they did not choose", byThePhone.needsYou)
        assertEquals(
            "shopping: stopped, the phone stopped the app",
            byThePhone.notice(),
        )

        val refused = session().stopped(StopReason.PIN_REFUSED, "down (503)")
        assertEquals("the provider's own words", "shopping: stopped, down (503)", refused.notice())
    }

    @Test
    fun `a reminder says which one it is, and the teens are not second`() {
        // **`11st` is what a naive `% 10` produces**, and it is the kind of wrong that survives
        // review because nobody counts to eleven in a test.
        assertEquals("1st", AgentSession.ordinal(1))
        assertEquals("2nd", AgentSession.ordinal(2))
        assertEquals("3rd", AgentSession.ordinal(3))
        assertEquals("4th", AgentSession.ordinal(4))
        assertEquals("11th", AgentSession.ordinal(11))
        assertEquals("12th", AgentSession.ordinal(12))
        assertEquals("13th", AgentSession.ordinal(13))
        assertEquals("21st", AgentSession.ordinal(21))
        assertEquals("111th", AgentSession.ordinal(111))

        // **Counted in reminders, not in notifications.** The sentence that comes with the
        // question is not a reminder; the first repeat is the first one.
        val first = session().asked(at = 0).reminded()
        assertEquals("1st reminder · shopping: the agent asked something", first.notice())
        val second = first.reminded()
        assertEquals(
            "2nd reminder · shopping: the agent asked something",
            second.notice(),
        )
    }

    @Test
    fun `answering the question puts the reminder ladder back to nothing`() {
        // The trap: the count survives the answer, so the *next* question's first reminder is
        // already called "4th" and fires immediately.
        val answered = session().asked(at = 0).reminded().reminded().waited(at = 10_000)
        val next = answered.asked(at = 20_000)

        assertEquals("no reminders spent", 0, next.reminders)
        assertFalse("and the interval starts again", next.reminderDue(20_000 + AgentSession.REMIND_MS - 1))
    }

    // ---- what a person reads ------------------------------------------------

    @Test
    fun `the label never says a negative wait`() {
        // A label computed as `now - lastPoll` reads "reconnecting (-2 s)" the moment the clock
        // moves back, which looks like a bug in the app rather than in the phone's clock.
        val s = session().polled(at = 10 * minute)

        assertEquals("four minutes", AgentSession.POLL_MS, s.untilPoll(10 * minute))
        assertEquals("and never less than none", 0, s.untilPoll(100 * minute))
    }

    @Test
    fun `the strip counts agents that are on, and only those that need somebody`() {
        val sessions = listOf(
            AgentSession("a", state = State.IDLE, sub = Sub.WAITING),
            AgentSession("b", state = State.ASKED, askedAt = 1),
            AgentSession("c", state = State.STOPPED, reason = StopReason.BY_OPERATOR),
        )

        assertEquals("two are on, one needs you", "2 sessions · 1 needs you", AgentSession.strip(sessions))
        assertEquals(
            "one agent, said in the singular",
            "1 session",
            AgentSession.strip(listOf(sessions[0])),
        )
        assertEquals(
            "nothing running at all is no strip",
            "",
            AgentSession.strip(listOf(sessions[2])),
        )
    }

    @Test
    fun `an unknown stop reason still says something a person can act on`() {
        // The state file is on disk and may be edited, or written by a newer build. A label of
        // "stopped · null" would be the app showing its own plumbing.
        val odd = AgentSession("a", state = State.STOPPED, reason = null)

        assertTrue("it needs you", odd.needsYou)
        assertEquals("stopped · not running", odd.label())
    }
}
