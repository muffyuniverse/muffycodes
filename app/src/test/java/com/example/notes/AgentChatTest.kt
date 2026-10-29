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
                call("tree"),
                got("tree", "0\nmilk.txt"),
                said("done"),
                told("second"),
                turn(),
                call("tree"),
                got("tree", "0\ndairy.txt"),
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
                said("one file"),
            )
        )

        assertEquals("one request", 1, box?.turns?.size)
        assertEquals(
            "two tool lines and the ending, in the order they happened",
            // **The ending's caption is `agent`.** It used to be `concluded`, drawn from a
            // `CONCLUDE` row; the ending is the model's own note now, and a note is drawn under the
            // name it was written with.
            listOf("tree", "read", "agent"),
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
            call("tree"),
            got("tree", "0\n"),
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
    fun `a turn that worked is still there beside the idle line`() {
        val poll = ping()
        val rows = listOf(
            told("look"),
            turn(),
            call("tree"),
            got("tree", "0\n"),
        ) + poll + poll

        val box = AgentChat.box(rows)

        assertEquals("the work is a turn", 1, box?.turns?.size)
        assertEquals(
            "the polls are not",
            "idle since #${poll[1].id} · 2 checks",
            box?.idle,
        )
    }

    // ---- the footer's numbers -------------------------------------------------
    //
    // **What the footer is for.** Report 36 asked for "time, date and id"; `output` and `thinking`
    // were declared on `Bubble`, drawn by `footer()`, and for a long time set by nobody — a bubble
    // reported no tokens because no constructor ever gave it any. These tests are the falsifiers for
    // the persistence, and each one states the wrong behaviour it would catch.

    private fun usage(of: Int, output: Int?, thinking: Int?) =
        row(AgentLog.USAGE, "usage", listOfNotNull(
            output?.let { "out=$it" },
            thinking?.let { "think=$it" },
        ).joinToString(" "), ctx = AgentLog.APP, answers = of)

    @Test
    fun `the agent's words carry what the reply cost`() {
        // Fails if the numbers never leave the reply: the bubble would report 0 and the footer would
        // draw neither count, which is exactly the state this replaced.
        val words = said("I read the three files and changed nothing.")
        val rows = listOf(told("look at the files"), turn(), words, usage(words.id, 2400, 9100))
        val box = AgentChat.box(rows)!!

        val agent = box.bubbles.last()
        assertEquals("captioned as the agent", "agent", agent.caption)
        assertEquals("the tokens it wrote", 2400L, agent.output)
        assertEquals("and the ones it thought", 9100L, agent.thinking)
    }

    @Test
    fun `a bubble is not charged for a reply that came after it`() {
        // **The falsifier for reading in place.** The usage row is written *after* the reply, so a
        // reader that applied rows as it met them would have drawn this bubble already. Here a second
        // turn's accounting must land on the second turn's words and not on the first.
        // **One operator message**, because the box shows the conversation after the last one - a
        // fixture with two would draw only the second burst and the assertion would be about the
        // wrong thing. Two TURNs give the two replies.
        val first = said("nothing to change")
        val second = said("now I have changed it")
        val rows = listOf(
            told("first"), turn(), first, usage(first.id, 100, 200),
            turn(), second, usage(second.id, 300, 400),
        )
        val box = AgentChat.box(rows)!!

        val agents = box.bubbles.filter { it.caption == "agent" }
        assertEquals("both replies are shown", 2, agents.size)
        assertEquals("the first keeps its own numbers", 100L, agents[0].output)
        assertEquals("and its own thinking", 200L, agents[0].thinking)
        assertEquals("the second has its own", 300L, agents[1].output)
        assertEquals("not the first's", 400L, agents[1].thinking)
    }

    @Test
    fun `a tool call is not charged twice for the reply that asked for it`() {
        // **The reason the numbers go on the words rather than on every row of the turn.** One reply
        // produces a message and any number of calls. Spread across them, a person reading two
        // bubbles in one turn would see the same figure twice and reasonably add them.
        val ask = call("read_file", "{\"path\":\"a.kt\"}")
        val words = said("the file is fine")
        val rows = listOf(
            told("check a.kt"), turn(), ask, got("read_file", "0 ok"), words,
            usage(words.id, 500, 1200),
        )
        val box = AgentChat.box(rows)!!

        val tool = box.bubbles.first { it.caption == "read_file" }
        assertEquals("the call reports no tokens of its own", 0L, tool.output)
        assertEquals("and none of the thinking", 0L, tool.thinking)
        val agent = box.bubbles.last()
        assertEquals("the message carries the request's numbers", 500L, agent.output)
        assertEquals("once", 1200L, agent.thinking)
    }

    @Test
    fun `a reply with no accounting reports nothing rather than zero`() {
        // **Absent is not zero.** A scripted door, a provider that sent no `usage` block, and a log
        // written before this existed all look like this. The footer omits the count, and a zero
        // here would instead claim the request was free.
        val words = said("done")
        val rows = listOf(told("go"), turn(), words)
        val box = AgentChat.box(rows)!!

        val agent = box.bubbles.last()
        assertEquals("no output count", 0L, agent.output)
        assertEquals("no thinking count", 0L, agent.thinking)
    }

    @Test
    fun `half an accounting is still an accounting`() {
        // A provider may report the completion count and say nothing about reasoning. The missing
        // half must not become a zero that the footer then prints - `out=2400` alone is a real row.
        val words = said("done")
        val box = AgentChat.box(
            listOf(told("go"), turn(), words, usage(words.id, 2400, null)),
        )!!

        val agent = box.bubbles.last()
        assertEquals("the half that was reported", 2400L, agent.output)
        assertEquals("and the half that was not stays unknown", 0L, agent.thinking)
    }

    @Test
    fun `an accounting row names its own row, not the newest one`() {
        // **The falsifier for joining by position.** A usage row belongs to the row its `answers`
        // column names. If the reader took "the last agent bubble" instead, a log whose rows were
        // rewritten - a forget reorders nothing but rewrites everything - would silently re-point it.
        val first = said("earlier")
        val second = said("later")
        // Deliberately out of order: the second reply's accounting comes first in the file.
        val rows = listOf(
            told("go"), turn(), first, second,
            usage(second.id, 300, 400),
            usage(first.id, 100, 200),
        )
        val box = AgentChat.box(rows)!!

        val agents = box.bubbles.filter { it.caption == "agent" }
        assertEquals("the earlier reply", 100L, agents[0].output)
        assertEquals("the later reply", 300L, agents[1].output)
    }

    @Test
    fun `an unreadable accounting row costs nothing and loses no other bubble`() {
        // It is the app's own bookkeeping, so this should not happen - but a row is the evidence, and
        // a reader that threw on one bad line would lose the whole conversation to it. `spend` is
        // total: anything it does not recognise reads as nothing.
        assertEquals("empty", AgentChat.Spend(0, 0), AgentChat.spend(""))
        assertEquals("not key=value", AgentChat.Spend(0, 0), AgentChat.spend("garbage"))
        assertEquals("a key with no value", AgentChat.Spend(0, 0), AgentChat.spend("out="))
        assertEquals("a value that is not a number", AgentChat.Spend(0, 0), AgentChat.spend("out=lots"))
        // **A negative is not a token count**, so it is rejected rather than carried into a footer
        // that would hide it (`> 0`) while a sum elsewhere counted it.
        assertEquals("a negative is refused, the other half kept", AgentChat.Spend(0, 200), AgentChat.spend("out=-5 think=200"))
        assertEquals("both", AgentChat.Spend(2400, 9100), AgentChat.spend("out=2400 think=9100"))
        assertEquals("reversed order is the same fact", AgentChat.Spend(2400, 9100), AgentChat.spend("think=9100 out=2400"))
        // **An unknown key is ignored rather than fatal**, so a later version can add one and an
        // older build still reads the row.
        assertEquals("an unknown key", AgentChat.Spend(7, 0), AgentChat.spend("out=7 cost=0.003"))
    }

    @Test
    fun `a usage row is bookkeeping and is not drawn as a bubble`() {
        // Forty of these would be forty bubbles saying nothing a person asked for. The numbers reach
        // the screen through the bubble's footer, which is where the report put them.
        val words = said("done")
        val rows = listOf(told("go"), turn(), words, usage(words.id, 2400, 9100))
        val box = AgentChat.box(rows)!!

        assertEquals("one bubble for the words, and no bubble for the row", 2, box.bubbles.size)
        assertTrue(
            "nothing is captioned usage",
            box.bubbles.none { it.caption == "usage" },
        )
    }

    @Test
    fun `an accounting row never enters the context`() {
        // **It would change the request between polls.** The context has to be byte-identical across
        // a keepalive, and a row whose numbers differ every turn would break the provider's cache on
        // the longest common prefix - the thing the three-layer system message exists to protect.
        val words = said("done")
        val rows = listOf(told("go"), turn(), words, usage(words.id, 2400, 9100))
        val box = AgentChat.box(rows)!!
        assertEquals("the accounting is not a turn", 1, box.turns.size)
    }

}
