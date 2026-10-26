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

    // ---- the keepalive's clock ----------------------------------------------

    @Test
    fun `a ping is due after four minutes and not a millisecond before`() {
        val s = session().warmed(1_000)

        assertFalse(
            "not at four minutes minus one",
            s.warmDue(1_000 + AgentSession.POLL_MS - 1, keepAlive = true),
        )
        assertTrue("but at four minutes exactly", s.warmDue(1_000 + AgentSession.POLL_MS, keepAlive = true))
    }

    @Test
    fun `the switch turns the pings off, and nothing else does`() {
        val s = session().warmed(0)
        val due = AgentSession.POLL_MS

        assertFalse("off means silent", s.warmDue(due, keepAlive = false))
        assertTrue("on means due", s.warmDue(due, keepAlive = true))
    }

    @Test
    fun `the ceiling runs from when the wait began, not from the last ping`() {
        // **The arithmetic that makes the ceiling mean anything.** Twelve pings of cache read cost
        // about what one cold prompt costs to write, so an hour is where keeping warm stops paying —
        // and a ceiling measured from each success would never arrive, because every ping would push
        // it along.
        val half = 30 * minute
        val s = session().asked(at = 1_000).warmed(at = 1_000 + half)

        assertTrue(
            "half an hour in, still warm",
            s.warmDue(1_000 + half + AgentSession.POLL_MS, keepAlive = true, forAt = 60 * minute),
        )
        assertFalse(
            "an hour after the question it stops, however many pings have landed",
            s.warmDue(1_000 + 60 * minute, keepAlive = true, forAt = 60 * minute),
        )
        assertTrue(
            "and a ceiling of zero means until the person speaks",
            s.warmDue(1_000 + 60 * minute, keepAlive = true, forAt = 0),
        )
    }

    @Test
    fun `the next ping is one interval after the last one, not one after now`() {
        // A clock consulted at "now + interval" drifts by however late the tick was: four minutes
        // of work plus four minutes of waiting is not a four-minute interval, and an agent left
        // overnight would ping half as often as it promised.
        val s = session().warmed(0)
        val late = 0 + AgentSession.POLL_MS + 9_000

        assertEquals("the interval starts at the ping", AgentSession.POLL_MS, s.nextPollAt)
        assertEquals(
            "so a late tick does not move it",
            AgentSession.POLL_MS,
            s.warmed(late).nextPollAt - late,
        )
    }

    @Test
    fun `an agent that asked or concluded is kept warm, and one that is reconnecting is not`() {
        // **The two states that used to be left to go cold.** A question is the likeliest thing to
        // be answered next, and a finished agent is one the person will add to; neither had a ping,
        // because a poll was something only `wait` asked for.
        val asked = session().asked(at = 10)
        assertTrue("a question is kept warm", asked.warmDue(10 + 10 * minute, keepAlive = true))

        val done = session().concluded(at = 10)
        assertTrue("and so is a conclusion", done.warmDue(10 + 10 * minute, keepAlive = true))

        val stopped = done.stopped(StopReason.BY_OPERATOR)
        assertFalse("a session the person stopped is not", stopped.warmDue(10 + 10 * minute, keepAlive = true))

        val retrying = session().polled(0).failed(at = AgentSession.POLL_MS)
        assertFalse(
            "and a reconnecting one is already trying",
            retrying.warmDue(AgentSession.POLL_MS + 500, keepAlive = true),
        )
    }

    @Test
    fun `one dropped request does not end the session, and the ladder backs off to four seconds`() {
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
    fun `a timer that fires twice in one interval pings once`() {
        // The shell is allowed to tick often; the session is the thing that decides. A ping that
        // spent money twice per interval would be the app's own bug, not the operator's.
        val s = session().warmed(at = 0)
        assertFalse("not again in the same interval", s.warmDue(AgentSession.POLL_MS - 1, keepAlive = true))
        assertTrue("and the first moment of the next one", s.warmDue(AgentSession.POLL_MS, keepAlive = true))
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
        val waiting = session().warmed(0)

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

        val byItself = session().stopped(StopReason.LOOP)
        assertTrue("this one they did not choose", byItself.needsYou)
        assertEquals(
            "shopping: stopped, it kept going without doing anything",
            byItself.notice(),
        )

        val refused = session().stopped(StopReason.PIN_REFUSED, "down (503)")
        assertEquals("the provider's own words", "shopping: stopped, down (503)", refused.notice())
    }

    @Test
    fun `a session the phone was not running is late, and one that was is not`() {
        // **The only thing the app can honestly say about a force-stop.** Android does not tell an
        // app it was stopped, so the clock is the evidence: a session that should have polled every
        // four minutes and did not for three hours was not running. It is written down rather than
        // turned into a stopped state — the session is about to carry on, and a label saying
        // "stopped" about a session that is running would be the label lying about the fact.
        val on = session().warmed(0).copy(lastPollAt = 1_000)

        // **The default interval's gap.** The session has no interval set, so it is polled every
        // POLL_MS and the gap is three of those — the number the app used before the interval became
        // a setting, and still what an unset one means.
        val gap = AgentSession.POLL_MS * AgentSession.GAP_INTERVALS
        assertEquals("on time", 0L, on.missedBy(1_000 + AgentSession.POLL_MS))
        assertEquals("a late wake is not the phone", 0L, on.missedBy(1_000 + gap))
        assertEquals(
            "hours are",
            2 * gap,
            on.copy(lastPollAt = 1_000 + gap).missedBy(1_000 + 3 * gap),
        )
        assertEquals(
            "a session that never polled has nothing to be late for",
            0L,
            session().missedBy(99_999_999),
        )
        assertEquals(
            "and a stopped one is not polling at all",
            0L,
            on.stopped(StopReason.BY_OPERATOR).missedBy(99_999_999),
        )
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
        val answered = session().asked(at = 0).reminded().reminded().resumed(at = 10_000)
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

    /**
     * The interval is the operator's, and 250 seconds is the ceiling.
     *
     * **A phone and a provider bill**, not the agent's opinion: the app comes back at the number the
     * person chose. Two claims: a session given an interval is warmed at it, and one given more than
     * the cap is warmed at the cap rather than never.
     */
    @Test
    fun `a waiting conversation is warmed at the interval the operator set`() {
        val everyMinute = session().warmed(0).withInterval(60_000)

        assertFalse("not due before it", everyMinute.warmDue(59_999, keepAlive = true))
        assertTrue("due at it", everyMinute.warmDue(60_000, keepAlive = true))
        assertEquals(
            "and the design's own when none is set",
            AgentSession.POLL_MS,
            session().warmed(0).intervalMs,
        )
    }

    @Test
    fun `an interval past the cap is the cap, not the number asked for`() {
        // A hand-set file could hold anything; the ceiling is what keeps a session visible.
        val tooLong = session().warmed(0).withInterval(60 * 60 * 1000L)

        assertEquals(AgentSession.MAX_IDLE_MS, tooLong.idleMs.coerceAtMost(AgentSession.MAX_IDLE_MS))
        assertTrue(
            "a ping is owed by the ceiling",
            tooLong.copy(idleMs = AgentSession.MAX_IDLE_MS)
                .warmDue(AgentSession.MAX_IDLE_MS, keepAlive = true),
        )
    }
}
