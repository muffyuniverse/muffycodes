package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which of the app's own lines are still open, and when they close.
 *
 * **The rule under test is one sentence**: a notice is open until somebody speaks after it. Everything
 * here is a way of asking that sentence a hard question — an id out of order, a row already flipped, a
 * note the app wrote for itself and nobody should see.
 *
 * No Robolectric: [ChatNotices] reads rows and answers a list, so the whole rule is testable without a
 * screen, a clock or a file.
 */
class ChatNoticesTest {

    private fun row(
        id: Int,
        kind: String = AgentLog.NOTE,
        ctx: String = AgentLog.APP,
        name: String = "stopped",
        detail: String = "the key was refused",
    ) = AgentLog.Row(
        id = id,
        size = 1,
        kind = kind,
        ctx = ctx,
        name = name,
        detail = detail,
    )

    @Test
    fun `a notice with nothing after it is open`() {
        val rows = listOf(
            row(1, kind = AgentLog.TOLD, ctx = AgentLog.IN, name = "told", detail = "do the thing"),
            row(2),
        )

        val open = ChatNotices.open(rows)

        assertEquals("the app's line is still the last word", listOf(2), open.map { it.id })
    }

    @Test
    fun `a notice the agent has spoken after is closed`() {
        // **The whole point.** A turn that failed says so; the next thing the agent says is the answer,
        // and the failure is history. Leaving it on the screen would be the app still complaining
        // about something that has since been dealt with.
        val rows = listOf(
            row(1, kind = AgentLog.TOLD, ctx = AgentLog.IN, name = "told", detail = "do the thing"),
            row(2),
            row(3, kind = AgentLog.NOTE, ctx = AgentLog.IN, name = "agent", detail = "done"),
        )

        assertTrue("nothing is open", ChatNotices.open(rows).isEmpty())
    }

    @Test
    fun `a notice the person has spoken after is closed`() {
        val rows = listOf(
            row(1, kind = AgentLog.TOLD, ctx = AgentLog.IN, name = "told", detail = "first"),
            row(2),
            row(3, kind = AgentLog.TOLD, ctx = AgentLog.IN, name = "told", detail = "second"),
        )

        assertTrue("the person moved on", ChatNotices.open(rows).isEmpty())
    }

    @Test
    fun `a row already flipped out is not open, however the ids fall`() {
        // **The flip is authoritative.** `toClose` writes `out` into the record, and a derivation that
        // ignored the column would bring a closed notice back the moment the row order was odd — for
        // instance when a trim has removed the message that closed it. Asking the column is what makes
        // "removed from the session log" survive a reader that cannot see the whole history.
        val rows = listOf(
            row(1, kind = AgentLog.TOLD, ctx = AgentLog.IN, name = "told", detail = "do the thing"),
            row(2, ctx = AgentLog.OUT),
        )

        assertTrue("flipped, so closed", ChatNotices.open(rows).isEmpty())
    }

    @Test
    fun `a note the app wrote for itself is not a notice`() {
        // **The reason the vocabulary is a closed list.** The app writes far more notes than it has
        // things to say — a trim, a reset, a forgotten row, a ping — and drawing them all would turn
        // the conversation into the app's diary. Only the three names in `NAMES` are addressed to the
        // person.
        val rows = listOf(
            row(1, kind = AgentLog.TOLD, ctx = AgentLog.IN, name = "told", detail = "go"),
            row(2, name = "trimmed", detail = "removed 4 rows"),
            row(3, name = "ping", detail = "kept warm"),
            row(4, name = "reset", detail = "the log was reset"),
        )

        assertTrue("none of those is a notice", ChatNotices.open(rows).isEmpty())
        for (name in listOf("trimmed", "ping", "reset")) {
            assertFalse(
                "$name is not in the vocabulary",
                ChatNotices.isNotice(row(9, name = name)),
            )
        }
    }

    @Test
    fun `a notice is not a message, so it cannot be in the agent's context`() {
        // The other half of "separate from agent's or user's messages": it is `app`, so `contextRows`
        // never sends it, and the model is never told the app's opinion of the last turn.
        val notice = row(1)

        assertEquals("the app's column", AgentLog.APP, notice.ctx)
        assertTrue("and it is a note", notice.kind == AgentLog.NOTE)
        assertFalse("so it is not a burst start", notice.startsBurst)
    }

    @Test
    fun `toClose names only the notices that are still app and no longer open`() {
        val rows = listOf(
            row(1, kind = AgentLog.TOLD, ctx = AgentLog.IN, name = "told", detail = "go"),
            row(2),
            row(3, kind = AgentLog.NOTE, ctx = AgentLog.IN, name = "agent", detail = "done"),
            row(4),
        )

        val closing = ChatNotices.toClose(rows)

        assertEquals("the answered one, and not the open one", setOf(2), closing)
    }

    @Test
    fun `toClose is empty when every notice is open or already closed`() {
        // **The common case has to cost nothing**, because the writer calls this after every append.
        val openOnly = listOf(row(1, kind = AgentLog.TOLD, ctx = AgentLog.IN, name = "told"), row(2))
        assertTrue("nothing to do", ChatNotices.toClose(openOnly).isEmpty())

        val closedOnly = listOf(
            row(1, kind = AgentLog.TOLD, ctx = AgentLog.IN, name = "told"),
            row(2, ctx = AgentLog.OUT),
        )
        assertTrue("and nothing to do again", ChatNotices.toClose(closedOnly).isEmpty())
    }

    @Test
    fun `the order is the record's ids, not the order the rows arrived in`() {
        // **Ids, because a clock can go backwards and two rows can share a millisecond.** A derivation
        // that compared timestamps would occasionally decide a notice came after the answer that
        // closed it, and put a stale failure under a fresh reply.
        val rows = listOf(
            row(5, kind = AgentLog.NOTE, ctx = AgentLog.IN, name = "agent", detail = "done"),
            row(3, kind = AgentLog.TOLD, ctx = AgentLog.IN, name = "told", detail = "go"),
            row(4),
        )

        assertTrue("4 is before 5, so it closed", ChatNotices.open(rows).isEmpty())
    }
}
