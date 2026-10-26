package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The box's reading of the record: what a conversation is, what a turn is, what a line says.
 *
 * These are rows fed in by hand rather than a burst played through [Agent], because what is being
 * pinned is the *shape* — a reply with two calls is one turn, a call and its result are one line,
 * forty idle polls are one line — and a scripted burst would make every one of these tests also a
 * test of the loop.
 */
class AgentChatTest {

    private var next = 1

    private fun row(
        kind: String,
        name: String,
        detail: String = "",
        size: Long = 40,
        ctx: String = AgentLog.IN,
    ): AgentLog.Row = AgentLog.Row(next++, size, kind, ctx, name, detail)

    private fun told(text: String) = row(AgentLog.TOLD, "operator", text)
    private fun turn() = row(AgentLog.TURN, "turn")
    private fun call(tool: String, args: String = "{}") = row(AgentLog.CALL, tool, args)
    private fun got(tool: String, body: String) = row(AgentLog.GOT, tool, body)
    private fun ask(question: String) = row(AgentLog.ASK, "question", question)

    // ---- what a conversation is ---------------------------------------------

    @Test
    fun `a conversation is the operator's message and everything after it`() {
        val box = AgentChat.box(
            listOf(
                told("first"),
                turn(),
                call("tree"),
                got("tree", "0\nmilk.txt"),
                row(AgentLog.CONCLUDE, "conclusion", "done"),
                told("second"),
                turn(),
                call("tree"),
                got("tree", "0\ndairy.txt"),
                row(AgentLog.CONCLUDE, "conclusion", "done again"),
            )
        )

        assertEquals("the newest one is the one on the screen", "second", box?.told)
        assertEquals("and it has its own turns", 1, box?.turns?.size)
    }

    @Test
    fun `an empty record has nothing to show`() {
        assertNull(AgentChat.box(emptyList()))
        assertNull("a record that starts mid-conversation is not a conversation", AgentChat.box(listOf(call("tree"))))
    }

    // ---- what a turn is -----------------------------------------------------

    @Test
    fun `a turn is a request, so two calls in one reply are one turn`() {
        val box = AgentChat.box(
            listOf(
                told("look"),
                turn(),
                call("tree"),
                got("tree", "0\n"),
                call("read"),
                got("read", "0\nhello"),
                row(AgentLog.CONCLUDE, "conclusion", "one file"),
            )
        )

        assertEquals("one request", 1, box?.turns?.size)
        assertEquals(
            "two tool lines and the ending, in the order they happened",
            listOf("tree", "read", "concluded"),
            box?.turns?.first()?.lines?.map { it.caption },
        )
    }

    @Test
    fun `a record written before the app marked its requests still draws, as one turn`() {
        // Older records have no boundary rows. Showing one turn is honest — their request
        // boundaries are not recoverable, and inventing them would be worse than saying less.
        val box = AgentChat.box(
            listOf(
                told("look"),
                call("tree"),
                got("tree", "0\n"),
                row(AgentLog.CONCLUDE, "conclusion", "one file"),
            )
        )

        assertEquals("one turn, because nothing says otherwise", 1, box?.turns?.size)
    }

    // ---- what a line says ---------------------------------------------------

    @Test
    fun `a call and its result are one line`() {
        val lines = AgentChat.lines(
            listOf(
                call("tree", """{"path":""}"""),
                got("tree", "0\n\nmilk.txt"),
            )
        )

        assertEquals("one line", 1, lines.size)
        assertEquals("named by the tool", "tree", lines[0].caption)
        assertEquals(
            "with the status and the one sentence that matters",
            "0 · milk.txt",
            lines[0].body,
        )
        assertFalse("and it worked", lines[0].failed)
        assertTrue("the arguments are kept for expansion", lines[0].detail.contains("path"))
    }

    @Test
    fun `a refusal is marked, and says its reason`() {
        val lines = AgentChat.lines(
            listOf(
                call("edit", """{"path":"locked.txt"}"""),
                got("edit", "1\n\"locked.txt\" is locked, so it cannot be changed"),
            )
        )

        assertTrue("marked as failed", lines[0].failed)
        assertTrue("with the reason on the line", lines[0].body.contains("is locked"))
    }

    @Test
    fun `a call nobody answered says so`() {
        val lines = AgentChat.lines(listOf(call("tree")))

        assertEquals("tree", lines[0].caption)
        assertEquals("no answer yet", lines[0].body)
        assertTrue("which is not a success", lines[0].failed)
    }

    @Test
    fun `the ending is the agent's words, never a call`() {
        val lines = AgentChat.lines(
            listOf(
                call("ask", """{"question":"Fix the README too?"}"""),
                ask("Fix the README too?"),
            )
        )

        assertEquals("one line", 1, lines.size)
        assertEquals("drawn as the question", "asked", lines[0].caption)
        assertEquals("with its words", "Fix the README too?", lines[0].body)
    }

    @Test
    fun `a seal from a reopened app is drawn where it stands`() {
        // A result whose call is in the conversation before. It is not dropped: an interrupted
        // turn is exactly the thing a person needs to see waiting for them.
        val lines = AgentChat.lines(
            listOf(got("edit", "1 the app was closed before this answered"))
        )

        assertEquals("edit", lines[0].caption)
        assertTrue("and it is visibly a failure", lines[0].failed)
    }

    // ---- the idle pings -----------------------------------------------------

    /** One keepalive ping as the record keeps it: the app's turn row and its note, both `app`. */
    private fun ping() = listOf(
        row(AgentLog.TURN, "turn", "ping", ctx = AgentLog.APP),
        row(AgentLog.NOTE, AgentChat.PING, "kept warm", ctx = AgentLog.APP),
    )

    @Test
    fun `forty idle pings are one line`() {
        val poll = ping()
        val rows = listOf(told("anything new?")) + poll + poll + poll

        val box = AgentChat.box(rows)

        assertEquals("no turn to step through", 0, box?.turns?.size)
        assertEquals(
            "one line, and its count",
            "idle since #${poll.first().id} · 3 checks",
            box?.idle,
        )
    }

    @Test
    fun `the checks after a conclusion are still its conversation`() {
        // **A conclusion ends the burst, so the pings that follow start a new one with no operator
        // message in it.** Reading only the last burst that has a TOLD would drop every check after
        // the agent finished — and the idle line is exactly what a person watches while it waits.
        val rows = listOf(
            told("look"),
            turn(),
            call("tree"),
            got("tree", "0\n"),
            row(AgentLog.CONCLUDE, "conclusion", "Nothing to change."),
        ) + ping() + ping()

        val box = AgentChat.box(rows)

        assertEquals("the work is a turn", 1, box?.turns?.size)
        assertEquals(
            "and the checks are the idle line",
            "idle since #${ping().first().id} · 2 checks",
            box?.idle,
        )
    }

    @Test
    fun `a new question ends the tail, and its checks are its own`() {
        // The other half of the rule: a burst that holds an ending is a new answer to the person, not
        // a check on the old one.
        val rows = listOf(
            told("look"),
            row(AgentLog.CONCLUDE, "conclusion", "Nothing to change."),
        ) + ping() + listOf(
            told("what about now"),
            turn(),
            ask("Shall I?"),
        )

        val box = AgentChat.box(rows)

        assertEquals("the new conversation is the one on screen", "what about now", box?.told)
        assertEquals("and it has no idle line of its own yet", null, box?.idle)
    }

    @Test
    fun `a turn that worked is still there beside the idle line`() {
        val rows = listOf(
            told("look"),
            turn(),
            call("tree"),
            got("tree", "0\n"),
        ) + ping() + ping()

        val box = AgentChat.box(rows)

        assertEquals("the work is a turn", 1, box?.turns?.size)
        assertEquals(
            "the polls are not",
            "idle since #${ping().first().id} · 2 checks",
            box?.idle,
        )
    }
}
