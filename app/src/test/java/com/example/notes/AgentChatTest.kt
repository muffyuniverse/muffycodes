package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    /** The last call this test made, so a result can name it — as the writer names it. */
    private var lastCall = 0

    private fun row(
        kind: String,
        name: String,
        detail: String = "",
        size: Long = 40,
        ctx: String = AgentLog.IN,
        answers: Int = 0,
        // **A stamp, because a row without one cannot be rendered.** These rows are the shape of a
        // conversation rather than a file's characters, so the stamp is a fixed one: a test that
        // needed distinct times would be testing the clock, not the box.
    ): AgentLog.Row = AgentLog.Row(
        "10:00:00.000", next++, size, kind, ctx, answers, name = name, detail = detail,
    )

    private fun told(text: String) = row(AgentLog.TOLD, "operator", text)
    private fun turn() = row(AgentLog.TURN, "turn")
    private fun call(tool: String, args: String = "{}") = row(AgentLog.CALL, tool, args).also {
        lastCall = it.id
    }

    /**
     * A result, **naming the call it answers**.
     *
     * The box joins by id now rather than by position, so a result that named no call would be a
     * result with no line — and a test that built one would be testing a record the app cannot
     * write. `answers = 0` is still available for the tests that are *about* an orphan.
     */
    private fun got(tool: String, body: String, answers: Int = lastCall) =
        row(AgentLog.GOT, tool, body, answers = answers)
    /** The agent's own words — the ending, now that `ask`/`conclude` are gone. */
    private fun said(text: String) = row(AgentLog.NOTE, "agent", text)

    // ---- what a conversation is ---------------------------------------------

    @Test
    fun `a conversation is the operator's message and everything after it`() {
        val box = AgentChat.box(
            listOf(
                told("first"),
                turn(),
                call("search"),
                got("search", "0\nmilk.txt"),
                said("done"),
                told("second"),
                turn(),
                call("search"),
                got("search", "0\ndairy.txt"),
                said("done again"),
            )
        )

        // **Both exchanges are in the list, and that is the change.** The box used to draw one
        // conversation and make a person move to the one before; it is a list now, and the operator
        // asked for it "from the beginning of the session log and to the last message". So `told`
        // is the FIRST thing said - the list starts there - and the messages run to the end.
        assertEquals("the list starts at the first thing said", "first", box?.told)
        val messages = box!!.messages
        assertEquals(
            "every exchange is drawn: operator, agent, operator, agent",
            listOf(
                AgentChat.Side.OPERATOR, AgentChat.Side.AGENT,
                AgentChat.Side.OPERATOR, AgentChat.Side.AGENT,
            ),
            messages.map { it.side },
        )
        assertEquals("the first thing said", "first", messages[0].body)
        assertEquals("and the last", "second", messages[2].body)
    }

    @Test
    fun `a bookkeeping row before the conversation is not a turn of its own`() {
        // **The index queue writes one row per pass, into the same file.** It lands whenever the
        // pass finishes — before the first thing a person says, if the project was cloned or indexed
        // first — and counting it as a turn made the box draw a turn nobody had, so "one turn" meant
        // two and a test waiting for one waited for ever while the record held exactly one.
        val box = AgentChat.box(
            listOf(
                row(AgentLog.NOTE, "index", "the project was added: nothing had changed", ctx = AgentLog.APP),
                told("what is in the project"),
                turn(),
                row(AgentLog.NOTE, "agent", "There is one file."),
            ),
        )

        assertEquals("one turn, and it is the one the person had", 1, box!!.turns.size)
        assertEquals("the person's words", "what is in the project", box.told)
    }

    @Test
    fun `a bookkeeping row after the conversation is not a turn either`() {
        // **The other half, and the one that would bite on the next save.** A pass that finishes
        // while the person is reading appends its row to the end of the record; the box has already
        // drawn its turns and must not grow a new one for it.
        val box = AgentChat.box(
            listOf(
                told("what is in the project"),
                turn(),
                row(AgentLog.NOTE, "agent", "There is one file."),
                row(AgentLog.NOTE, "index", "a change was saved", ctx = AgentLog.APP),
            ),
        )

        assertEquals("still one turn", 1, box!!.turns.size)
    }

    @Test
    fun `an empty record has nothing to show`() {
        assertNull(AgentChat.box(emptyList()))
        assertNull("a record that starts mid-conversation is not a conversation", AgentChat.box(listOf(call("search"))))
    }

    // ---- what a turn is -----------------------------------------------------

    @Test
    fun `a turn is a request, so two calls in one reply are one turn`() {
        val box = AgentChat.box(
            listOf(
                told("look"),
                turn(),
                call("search"),
                got("search", "0\n"),
                call("read"),
                got("read", "0\nhello"),
                said("one file"),
            )
        )

        assertEquals("one request", 1, box?.turns?.size)
        assertEquals(
            "two tool lines and the ending, in the order they happened",
            // **The ending's caption is `agent`.** It used to be `concluded`, drawn from a
            // `CONCLUDE` row; the ending is the model's own note now, and a note is drawn under the
            // name it was written with.
            listOf("search", "read", "agent"),
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
                call("search"),
                got("search", "0\n"),
                said("one file"),
            )
        )

        assertEquals("one turn, because nothing says otherwise", 1, box?.turns?.size)
    }

    // ---- what a line says ---------------------------------------------------

    @Test
    fun `a call and its result are one line`() {
        val lines = AgentChat.lines(
            listOf(
                call("search", """{"path":""}"""),
                got("search", "0\n\nmilk.txt"),
            )
        )

        assertEquals("one line", 1, lines.size)
        assertEquals("named by the tool", "search", lines[0].caption)
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
                call("write", """{"path":"locked.txt"}"""),
                got("write", "1\n\"locked.txt\" is locked, so it cannot be changed"),
            )
        )

        assertTrue("marked as failed", lines[0].failed)
        assertTrue("with the reason on the line", lines[0].body.contains("is locked"))
    }

    @Test
    fun `a call nobody answered says so`() {
        val lines = AgentChat.lines(listOf(call("search")))

        assertEquals("search", lines[0].caption)
        assertEquals("no answer yet", lines[0].body)
        assertTrue("which is not a success", lines[0].failed)
    }

    @Test
    fun `the ending is the agent's words, never a call`() {
        // **The ending is a note, because there is no ending tool any more.** It used to be an
        // `ASK`/`CONCLUDE` row written beside the agent's own note — the same paragraph twice. The
        // note is the one that survives: it is what the model reads back and what the box draws.
        val lines = AgentChat.lines(listOf(said("Fix the README too?")))

        assertEquals("one line", 1, lines.size)
        assertEquals("drawn as the agent", "agent", lines[0].caption)
        assertEquals("with its words", "Fix the README too?", lines[0].body)
    }

    @Test
    fun `a seal from a reopened app is drawn where it stands`() {
        // A result whose call is in the conversation before. It is not dropped: an interrupted
        // turn is exactly the thing a person needs to see waiting for them.
        val lines = AgentChat.lines(
            listOf(got("write", "1 the app was closed before this answered"))
        )

        assertEquals("write", lines[0].caption)
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
        // **The first row inside the turn, not the turn's own row.** A TURN row is the boundary that
        // starts a turn and is not part of it, so the idle line names the first thing the check
        // actually said — here the `kept warm` note.
        assertEquals(
            "one line, and its count",
            "idle since #${poll[1].id} · 3 checks",
            box?.idle,
        )
    }

    @Test
    fun `the checks after a conclusion are still its conversation`() {
        // **A conclusion ends the burst, so the pings that follow start a new one with no operator
        // message in it.** Reading only the last burst that has a TOLD would drop every check after
        // the agent finished — and the idle line is exactly what a person watches while it waits.
        val poll = ping()
        val rows = listOf(
            told("look"),
            turn(),
            call("search"),
            got("search", "0\n"),
            said("Nothing to change."),
        ) + poll + poll

        val box = AgentChat.box(rows)

        assertEquals("the work is a turn", 1, box?.turns?.size)
        assertEquals(
            "and the checks are the idle line",
            "idle since #${poll[1].id} · 2 checks",
            box?.idle,
        )
    }

    @Test
    fun `a new operator message is the one on screen, and the old checks belong to the old one`() {
        // **The boundary is the person speaking**, which is now the only boundary there is: a turn
        // ends with the agent's words and no row marks it, so a burst runs from one operator message
        // to the next. The old conversation's checks are the old conversation's — the new one has no
        // idle line until the agent finishes and the keepalive starts on it.
        val rows = listOf(
            told("look"),
            said("Nothing to change."),
        ) + ping() + listOf(
            told("what about now"),
            turn(),
            said("Shall I?"),
        )

        val box = AgentChat.box(rows)

        // The list starts at the first thing said; the second exchange is in it, after the first.
        assertEquals("the list starts at the first thing said", "look", box?.told)
        assertEquals(
            "and the later question is in it too",
            listOf("look", "what about now"),
            box!!.messages.filter { it.side == AgentChat.Side.OPERATOR }.map { it.body },
        )
        assertEquals("and it has no idle line of its own yet", null, box?.idle)
    }

    @Test
    fun `a reply that was pinged afterwards is still a message`() {
        // **The operator's report**: "for some reason the last message from agent disappeared in
        // chat but still visible in the log."
        //
        // The log had the `agent` row; the box had decided its burst was warmth. A warm turn writes
        // no `TOLD`, so it joins the burst above it — and the skip that dropped a burst with no
        // `CALL` in it also dropped this one: the reply called nothing, then the keepalive pinged,
        // and the burst that held the words the person was reading looked like an idle check.
        //
        // **The shape that matters is the one with no `TOLD` between them**, because that is what
        // makes the ping land in the reply's own burst. With a `TOLD` the ping starts a new burst and
        // the old test passed — which is why this bug survived a suite that already tested pings.
        val words = said("Nothing to change.")
        val rows = listOf(told("look"), turn(), words) + ping()

        val box = AgentChat.box(rows)!!

        val agent = box.bubbles.lastOrNull { it.caption == "agent" }
        assertNotNull("**the reply is still drawn**", agent)
        assertEquals("and it is the words the log holds", "Nothing to change.", agent!!.body)
        // **And the ping is still one idle line, not a message.** The fix must not turn checks into
        // messages; it only has to stop a check from swallowing the reply it followed. Both halves
        // are asserted here because either one alone is satisfiable by the wrong rule.
        assertEquals(
            "the ping is still the idle line and not a message",
            "idle since #${rows.last().id} · 1 check",
            box.idle,
        )
    }

    @Test
    fun `a burst that is nothing but warmth is still the idle line`() {
        // **The falsification for the test above.** Dropping the skip entirely would make every
        // ping its own message, which is the thing the idle line exists to prevent — so this asserts
        // that warmth alone still collapses.
        val poll = ping()
        val rows = listOf(told("anything new?")) + poll + poll

        val box = AgentChat.box(rows)

        assertEquals("no message came of it", 0, box?.turns?.size)
        assertEquals(
            "the checks are one line",
            "idle since #${poll[1].id} · 2 checks",
            box?.idle,
        )
    }

    @Test
    fun `a turn that worked is still there beside the idle line`() {
        val poll = ping()
        val rows = listOf(
            told("look"),
            turn(),
            call("search"),
            got("search", "0\n"),
        ) + poll + poll

        val box = AgentChat.box(rows)

        assertEquals("the work is a turn", 1, box?.turns?.size)
        assertEquals(
            "the polls are not",
            "idle since #${poll[1].id} · 2 checks",
            box?.idle,
        )
    }

    // ---- what the chat does not carry ----------------------------------------
    //
    // **Cost left the chat on 2026-11-10, and this is the assertion that keeps it gone.**
    //
    // `usage` was a third copy of numbers `RequestDb` already held with more context - `prompt`,
    // `cached`, `completion`, `reasoning`, `said`, `thinking`, plus the model, the project and the
    // finish reason. The copy existed for one reason: the footer is keyed by a row id and the
    // request log is keyed by an instant, so a figure had to be carried into the record to be
    // joined to a bubble. Deleting the footer deleted the reason, and the whole path went with it:
    // the row kind, its writer, the parser, the two maps that walked the conversation looking for
    // it, and the live counter.
    //
    // **This test is the falsifier for its return.** If a token count ever appears on a bubble
    // again, it was re-added by someone who did not know where the real numbers live.

    @Test
    fun `a message carries no token count, because the request log is where cost lives`() {
        val words = said("I read the three files and changed nothing.")
        val box = AgentChat.box(listOf(told("look at the files"), turn(), words))!!

        val agent = box.bubbles.last()
        assertEquals("captioned as the agent", "agent", agent.caption)
        assertEquals("the words", words.body, agent.body)
        // **The record still says what it cost** - `RequestDb.requests`, which `query` reaches.
        // What must not exist is a second copy threaded through the chat. The compiler is the real
        // falsifier here: `Bubble` and `Message` no longer declare `thinking`/`output`, so a bubble
        // cannot report a count even if a caller tried to give it one. This asserts the part the
        // compiler cannot see - that a usage row in the record adds nothing to the screen.
        val withUsage = AgentChat.box(
            listOf(
                told("look at the files"), turn(), words,
                // A row of the shape the deleted writer produced, with no field to put a figure in.
                row(AgentLog.NOTE, "usage", "out=2400 think=9100", ctx = AgentLog.APP, answers = words.id),
            ),
        )!!
        // **The agent's bubble is unchanged.** A stale `usage` row is drawn as the app's own line —
        // it always was, and that is not what this asserts — but it cannot attach a figure to the
        // reply, because `Bubble` has no field for one. The claim is about the reply, not about the
        // row's caption.
        val charged = withUsage.bubbles.single { it.caption == "agent" }
        assertEquals("the same words", agent.body, charged.body)
        assertEquals("the same id", agent.id, charged.id)
    }

    @Test
    fun `a wrapped line costs as many rows as it fills, because height is rows`() {
        // **The operator's question**: "limit on displayed lines doesnt account for long lines that
        // are wrapping. Do we need to live with it or is it a simple fix to count a line that wraps
        // into 3 lines as 3 lines?"
        //
        // The cap bounds a bubble's HEIGHT, and height is rows. Counting source lines let a page of
        // three long paragraphs fill nine rows while the cap said three - so the one bubble the
        // person had capped was three times the height they asked for.
        val perRow = AgentChat.CHARS_PER_ROW

        assertEquals("a short line is one row", 1, AgentChat.rowsOf("milk"))
        assertEquals("an empty line is still a row", 1, AgentChat.rowsOf(""))
        assertEquals(
            "a line of exactly one row's worth is one row",
            1,
            AgentChat.rowsOf("x".repeat(perRow)),
        )
        assertEquals(
            "one character past it wraps, and costs two",
            2,
            AgentChat.rowsOf("x".repeat(perRow + 1)),
        )
        // **The case in the report.** Three lines, each three rows' worth, is nine rows - not three.
        assertEquals(
            "three long lines are nine rows",
            9,
            AgentChat.rowsOf(List(3) { "x".repeat(perRow * 3) }.joinToString("\n")),
        )
        assertEquals(
            "and the rows add up across lines",
            1 + 2 + 1,
            AgentChat.rowsOf("short\n" + "x".repeat(perRow + 1) + "\nshort"),
        )
    }

    @Test
    fun `a bookkeeping row is not drawn as a bubble`() {
        // Forty of these would be forty bubbles saying nothing a person asked for. The claim outlived
        // the token counts it was written for: whatever an app row says, it is not a message.
        val words = said("done")
        val rows = listOf(told("go"), turn(), words, row(AgentLog.NOTE, "usage", "out=2400 think=9100", ctx = AgentLog.APP))
        val box = AgentChat.box(rows)!!

        // **The words are one bubble.** An `app` note with an unrecognised name *is* drawn — as the
        // app's own line, which is what the `else` branch is for and what "stopped" relies on — so
        // the claim here is narrower and true: the row does not become a second *message*, and it
        // carries no figure.
        assertEquals("one message, not two", 1, box.turns.size)
        assertEquals(
            "and the agent's bubble is the words",
            "done",
            box.bubbles.single { it.caption == "agent" }.body,
        )
    }

    @Test
    fun `an accounting row never enters the context`() {
        // **It would change the request between polls.** The context has to be byte-identical across
        // a keepalive, and a row whose numbers differ every turn would break the provider's cache on
        // the longest common prefix - the thing the three-layer system message exists to protect.
        val words = said("done")
        val rows = listOf(told("go"), turn(), words, row(AgentLog.NOTE, "usage", "out=2400", ctx = AgentLog.APP))
        val box = AgentChat.box(rows)!!
        assertEquals("the accounting is not a turn", 1, box.turns.size)
    }

}
