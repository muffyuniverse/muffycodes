package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The agent's record, tested as a file.
 *
 * Every claim here is about **what the two files hold**, not about an object in memory: the
 * whole design rests on `session.log` minus `context.log` being the agent's own judgement, and
 * that is a property of the text. A recorder with a tidy API and two files that had drifted
 * apart would pass any test that only asked it questions about itself.
 */
class AgentLogTest {

    private lateinit var project: File
    private lateinit var log: AgentLog

    @Before
    fun fresh() {
        project = File(
            System.getProperty("java.io.tmpdir"),
            "agent-log-${System.nanoTime()}",
        )
        project.mkdirs()
        log = AgentLog.open(project)
    }

    /**
     * The two files, read as files rather than through the record.
     *
     * A helper that opened the record to measure it would be measuring something it could have
     * changed — which is exactly the mistake this class exists to catch in the app.
     */
    private fun sessionLines(): List<String> = linesIn(File(project, "agent/session.log"))

    private fun contextLines(): List<String> = linesIn(File(project, "agent/context.log"))

    private fun linesIn(file: File): List<String> =
        if (file.isFile) file.readLines().filter { it.isNotBlank() } else emptyList()

    @Test
    fun `a turn is written to both files, with the same ids`() {
        // The property the diff depends on: one event, one id, two files.
        log.told("rename milk.txt to dairy.txt")
        log.call("tree", """{"path":"GitHub"}""")
        log.got("tree", "0\nGitHub/\nGitHub/milk.txt")

        val session = sessionLines()
        val context = contextLines()

        assertEquals("three events", 3, session.size)
        assertEquals("the same three in the context", session, context)
        assertTrue("in the order they happened", session[0].contains("TOLD"))
        assertTrue("with the operator's words", session[0].endsWith("rename milk.txt to dairy.txt"))
    }

    @Test
    fun `the ids are one sequence across both files`() {
        // Reading the id back out of the file rather than trusting the return value: an id
        // counter that restarted at 1 on every open would make the two files' ids collide, and
        // the diff would stop meaning what it is for.
        log.told("first")
        val second = log.note("note", "second")

        val reopened = AgentLog.open(project)
        val third = reopened.note("note", "third")

        assertTrue("the second id is above the first", second > 1)
        assertEquals("and the next open continues the sequence", second + 1, third)
    }

    @Test
    fun `a row the agent cleared is in the session and not in the context`() {
        // This is the diff's whole purpose, so it is asserted as a diff.
        log.told("a task")
        log.note("note", "this mattered")
        log.note("note", "this did not")
        val kept = linesIn(log.context).filter { it.contains("this mattered") }
        val dropped = linesIn(log.context).filter { it.contains("this did not") }
        assertEquals("one is in the context", 1, kept.size)
        assertEquals("both were written", 1, dropped.size)

        // The agent's clear-up edit, simulated the way he does it: rewrite the context without
        // the row, leaving the session untouched.
        val rows = log.contextRows().filterNot { it.detail.contains("this did not") }
        log.context.writeText(rows.joinToString("\n") { it.line } + "\n")

        assertEquals("the session still has it", 1, linesIn(log.session).count {
            it.contains("this did not")
        })
        assertEquals("and the context does not", 0, linesIn(log.context).count {
            it.contains("this did not")
        })
    }

    @Test
    fun `a body with newlines in it is one row, and comes back whole`() {
        val listing = "GitHub/\nGitHub/milk.txt\nGitHub/notes.txt"
        log.got("tree", "0\n$listing")

        val rows = AgentLog.open(project).rows()
        assertEquals("one event", 1, rows.size)
        assertEquals("and the body is intact", "0\n$listing", rows[0].body)
    }

    @Test
    fun `a half-written last line is not a row yet`() {
        // The box reads a file the app is appending to. Half a row shown as an event is
        // showing something that did not happen.
        log.told("finished")
        // Half a row, with no brace in it: the repository's brace check counts braces in
        // text as well as in code, and a fixture is not a reason to weaken a check.
        log.session.appendText("14:02:11.410   99 CALL  tree      \"path\":\"Git")

        val rows = AgentLog.open(project).rows()
        assertEquals("only the finished one", 1, rows.size)
        assertTrue("and it is the operator's", rows[0].kind == AgentLog.TOLD)
    }

    @Test
    fun `a line that does not parse is shown as a row that says so`() {
        // Never dropped: a log that silently loses the evidence is worse than one with a
        // blemish, because the blemish is the thing somebody needs to see.
        log.told("fine")
        log.session.appendText("this is not a row\n")

        val rows = AgentLog.open(project).rows()
        assertEquals("both lines are rows", 2, rows.size)
        assertTrue("the second one is marked", rows[1].broken)
        assertEquals("and it keeps its text", "this is not a row", rows[1].detail)
    }

    @Test
    fun `a call that was never answered is sealed when the record is opened`() {
        // The app can be closed between a call and its result. The model's next request must
        // not carry a call with no answer — every endpoint refuses that, and an agent that
        // meets the refusal at the start of a session has no way back.
        log.told("do the thing")
        log.call("tree", "{}")
        // Opening alone does nothing: a screen that drew the box must not have edited the
        // evidence. The seal belongs to the start of a session.
        val before = sessionLines()
        AgentLog.open(project)
        assertEquals("opening writes nothing", before, sessionLines())

        // Nothing has been sealed yet, so the call is still waiting for its result.
        val sealed = AgentLog.open(project)
        assertEquals("the call was dangling", 1, sealed.beginSession())
        val rows = sealed.rows()

        assertEquals("a result was written", 4, rows.size)
        assertTrue("for the call", rows.last().kind == AgentLog.GOT)
        assertEquals("under the tool's name", "tree", rows.last().name)
        assertTrue("with a failure and a reason", rows.last().body.startsWith("1 "))
        assertTrue(
            "that says what happened: ${rows.last().body}",
            rows.last().body.contains("closed before this answered"),
        )
        assertTrue("and nothing is dangling now", sealed.dangling(rows).isEmpty())
    }

    @Test
    fun `opening twice does not seal the same call twice`() {
        log.call("read", """{"path":"GitHub/a.txt"}""")
        AgentLog.open(project).beginSession()
        val after = AgentLog.open(project)
        after.beginSession()

        assertEquals("one seal, not two", 2, after.rows().size)
    }

    @Test
    fun `a conversation is a burst, from the operator to the terminating row`() {
        log.told("first task")
        log.call("tree", "{}")
        log.got("tree", "0\nnothing")
        log.ask("shall I?")
        log.told("second task")
        log.conclude("done")

        val bursts = AgentLog.bursts(log.rows())

        assertEquals("two conversations", 2, bursts.size)
        assertEquals("the first ends with the question", 4, bursts[0].size)
        assertEquals("the second is the answer", 2, bursts[1].size)
    }

    @Test
    fun `trimming keeps whole conversations, newest first, and says what went`() {
        // A trim inside a burst leaves half a conversation on the record, which reads as a bug
        // in the agent rather than as housekeeping.
        repeat(6) { n ->
            log.told("task $n")
            log.call("tree", "{}")
            log.got("tree", "0\nnothing")
            log.conclude("finished $n")
        }
        val before = log.rows().size

        val dropped = log.trimSession(limit = 1)

        val rows = AgentLog.open(project).rows()
        assertTrue("something went", dropped > 0)
        assertTrue("and it says what: ${rows.first().detail}", rows.first().detail.contains("rows"))
        assertEquals("the newest conversation is whole", 4, rows.count { it.kind != AgentLog.NOTE })
        assertTrue("less than before", rows.size < before)
        // Every row kept is a whole burst: a TOLD followed by three, three times.
        val kept = rows.filterNot { it.name == "trimmed" }
        assertTrue("it starts with an operator's message", kept.first().kind == AgentLog.TOLD)
        assertTrue("and ends with a conclusion", kept.last().kind == AgentLog.CONCLUDE)
    }

    @Test
    fun `trimming before the limit does nothing at all`() {
        log.told("a task")
        val before = sessionLines()

        val dropped = log.trimSession(limit = AgentLog.SESSION_LIMIT)

        assertEquals("nothing dropped", 0, dropped)
        assertEquals("and the file is untouched", before, sessionLines())
    }

    @Test
    fun `the session keeps a row the context does not want`() {
        // The repeated poll is real evidence and is not sent: fifteen of them an hour would make
        // the request differ from itself, and the poll is only cheap while it is a cache hit.
        log.told("a task")
        val contextBefore = contextLines().size

        log.sessionOnly(AgentLog.CALL, "wait", "{}")

        assertEquals("the session keeps it", contextBefore + 1, sessionLines().size)
        assertEquals("the context does not", contextBefore, contextLines().size)
        assertFalse("and it is not a row the box has to explain", log.rows().isEmpty())
    }
}
