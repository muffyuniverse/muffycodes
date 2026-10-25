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
 * Every claim here is about **what the file holds**, not about an object in memory: the whole
 * design rests on the `ctx` column being the agent's own judgement — `out` is what it decided to
 * forget — and that is a property of the text. A recorder with a tidy API and a file that had
 * drifted from what it reported would pass any test that only asked it questions about itself.
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

    private fun contextLines(): List<String> = log.contextRows().map { it.line }

    /** A bare agent, for the one test that asks how rows become a conversation. */
    private fun agentFor(record: AgentLog): Agent {
        val dir = record.session.parentFile.parentFile
        val workspace = Workspace(dir.parentFile.parentFile)
        return Agent(
            accountDir = workspace.accountDir,
            project = dir.name,
            ws = workspace,
            git = ProjectGit(workspace, { null }, { GitAuthor("M", "m@example.com") }),
            log = record,
            door = ScriptedDoor(),
            model = ActiveModel("m", "p", "high"),
        )
    }

    private fun linesIn(file: File): List<String> =
        if (file.isFile) file.readLines().filter { it.isNotBlank() } else emptyList()

    @Test
    fun `a turn is written once, and the ctx column says what the model is sent`() {
        // **One file, so there is nothing to keep in step.** Every event is one row with one id,
        // and being in the context is a column rather than a second copy of the same line.
        log.told("rename milk.txt to dairy.txt")
        log.call("tree", """{"path":"GitHub"}""")
        log.got("tree", "0\nGitHub/\nGitHub/milk.txt")

        val rows = log.rows()
        assertEquals("three events", 3, rows.size)
        assertTrue("all three are in the context", rows.all { it.ctx == AgentLog.IN })
        assertTrue("in the order they happened", rows[0].kind == AgentLog.TOLD)
        assertEquals("with the operator's words", "rename milk.txt to dairy.txt", rows[0].body)
        assertEquals("and the context is exactly those rows", rows, log.contextRows())
    }

    @Test
    fun `the ids are one sequence over the whole record`() {
        // Reading the id back out of the file rather than trusting the return value: an id counter
        // that restarted at 1 on every open would make two rows share a number, and a forget would
        // then remove the wrong one.
        log.told("first")
        val second = log.note("note", "second")

        val reopened = AgentLog.open(project)
        val third = reopened.note("note", "third")

        assertTrue("the second id is above the first", second > 1)
        assertEquals("and the next open continues the sequence", second + 1, third)
    }

    @Test
    fun `a forgotten row stays in the file and leaves the context`() {
        // The audit in one assertion: the row is still readable, with its text, and the only thing
        // that changed about it is the column that says whether the model is sent it.
        log.told("a task")
        log.note("note", "this mattered")
        val gone = log.note("note", "this did not")

        log.forget(setOf(gone))

        assertEquals(
            "the text is still in the file",
            1,
            linesIn(log.session).count { it.contains("this did not") },
        )
        assertTrue("but not in the context", log.contextRows().none { it.id == gone })
        assertEquals("and it is the only thing he decided to lose", listOf(gone), log.thrownAway().map { it.id })
        assertTrue(
            "the row he kept is still in",
            log.contextRows().any { it.body == "this mattered" },
        )
    }

    // ---- flipping the column, and nothing else -------------------------------

    @Test
    fun `forgetting changes four characters and not one other byte`() {
        // **The mechanism the whole design leans on.** A row is taken out of the context by
        // rewriting its ctx column in place; the name and the body are carried over from the file
        // exactly as they were. The body here is chosen to be hostile: backslashes, a literal
        // backslash-n that is not a newline, real newlines (escaped in the file), and a quote.
        val nasty = "a\\b and \\n literally, then a real\nnewline, and a \"quote\""
        val id = log.got("read", "0\n$nasty")
        val before = linesIn(log.session).last()

        log.forget(setOf(id))

        val after = linesIn(log.session).first { it.contains(" GOT ") }
        assertEquals("the body is byte-identical", before.replace("in  read", "out read"), after)
        // And it still reads back as the same body, not as a mangled one.
        assertEquals("and decodes the same", "0\n$nasty", log.rows().first { it.id == id }.body)
    }

    @Test
    fun `the columns stay aligned after a flip, so the file still greps and parses`() {
        log.told("a task")
        val id = log.note("agent", "a note")
        log.forget(setOf(id))

        val line = linesIn(log.session).first { it.contains("a note") }
        // `14:02:11.410    2 NOTE    out agent    a note` — id padded to four, kind to seven,
        // ctx to three, so every column starts where it started before.
        assertTrue("ctx is the fourth column: $line", line.contains("NOTE    out "))
        assertEquals("and the row parses", id, AgentLog.parseRow(line).id)
        assertEquals("with its own name", "agent", AgentLog.parseRow(line).name)
    }

    @Test
    fun `an old build's record is read, not rejected`() {
        // **The update path, and the reason it is tested.** Both the row and the file changed: the
        // row gained a column, and the second file went away. A parser that treated yesterday's
        // five-column rows as unreadable would lose every session's memory on the phone it was
        // installed on — quietly, because a broken row is drawn rather than dropped.
        val record = File(project, "agent/${AgentLog.SESSION}")
        record.writeText(
            "14:02:11.410    1 TOLD    operator  the first thing\n" +
                "14:02:12.000    2 TOLD    operator  the second thing\n",
        )
        // The old context held only the second row; the first was trimmed out of it.
        File(project, "agent/${AgentLog.CONTEXT}")
            .writeText("14:02:12.000    2 TOLD    operator  the second thing\n")

        val reopened = AgentLog.open(project)

        assertEquals("both rows are read", 2, reopened.rows().size)
        assertEquals(
            "and the row the old context held is the one in",
            listOf(2),
            reopened.contextRows().map { it.id },
        )
        assertEquals(
            "the other is the app's, because the old file cannot say why it was out",
            AgentLog.APP,
            reopened.rows().first { it.id == 1 }.ctx,
        )
        assertFalse("and the old file is gone", File(project, "agent/${AgentLog.CONTEXT}").exists())
        assertTrue(
            "with the rows rewritten in the new shape, with their column",
            linesIn(record).all { it.contains("TOLD    app ") || it.contains("TOLD    in  ") },
        )
    }

    @Test
    fun `an old record with no second file keeps everything in the context`() {
        // Before the column existed both files held the same rows, so with no context file to
        // consult, `in` is the reading that loses nothing.
        val record = File(project, "agent/${AgentLog.SESSION}")
        record.writeText("14:02:11.410    1 TOLD    operator  a task\n")

        assertEquals("the row is in", 1, AgentLog.open(project).contextRows().size)
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

        assertEquals("a result was written", 3, rows.size)
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
    fun `the trim measures bytes, not rows`() {
        // The bug this pins: `limit` was passed to the burst chooser as a *row count*, so a 4 MB
        // limit asked for two million rows — more than this app will ever write — and the trim
        // could never fire. A limit that means nothing is a limit that does nothing.
        repeat(6) { n ->
            log.told("task $n " + "x".repeat(200))
            log.conclude("done")
        }

        val dropped = log.trimSession(limit = 1_500)

        assertTrue("a limit of 1.5 KB keeps about 750 bytes, not 750 rows", dropped > 0)
        assertTrue(
            "and the record is smaller than the limit",
            File(project, "agent/session.log").length() < 1_500,
        )
    }

    @Test
    fun `the newest conversation is kept even when it alone is over the limit`() {
        // Something has to be on the record. A trim that emptied the file would be a housekeeping
        // step that destroyed the thing it was housekeeping.
        log.told("everything " + "x".repeat(5_000))
        log.conclude("done")
        val before = sessionLines()

        val dropped = log.trimSession(limit = 100)

        assertEquals("nothing dropped", 0, dropped)
        assertEquals("and the file is untouched", before, sessionLines())
    }

    @Test
    fun `trimming before the limit does nothing at all`() {
        log.told("a task")
        val before = sessionLines()

        val dropped = log.trimSession(limit = AgentLog.SESSION_LIMIT)

        assertEquals("nothing dropped", 0, dropped)
        assertEquals("and the file is untouched", before, sessionLines())
    }

    // ---- resetting the working context --------------------------------------

    @Test
    fun `a reset empties the context and deletes nothing`() {
        log.told("rename milk.txt")
        log.call("tree", """{"path":"GitHub"}""")
        log.got("tree", "0\nGitHub/milk.txt")
        log.note("agent", "found it")
        val evidence = sessionLines()

        log.reset()

        assertEquals("the context is empty", 0, log.contextRows().size)
        assertEquals("the record grew by one", evidence.size + 1, sessionLines().size)
        assertTrue(
            "and every row that happened is still there — the lines changed, the ids did not",
            log.rows().map { it.id }.containsAll(evidence.map { AgentLog.parseRow(it).id }),
        )
        assertEquals("with the reset itself as the newest", "reset", log.rows().last().name)
    }

    @Test
    fun `a reset is the app's act, and is never read as the agent's`() {
        // **The one thing the audit must never confuse.** A reset empties the context, so a naive
        // reading would see the agent forgetting everything; the rows are marked `app` instead, and
        // the marker row is `app` too, so the judgement stays empty.
        log.told("a task")
        log.reset()

        assertTrue("nothing was judged", log.thrownAway().isEmpty())
        assertEquals(
            "and the rows say whose act it was",
            setOf(AgentLog.APP),
            log.rows().map { it.ctx }.toSet(),
        )
    }

    @Test
    fun `the ids carry on after a reset, and are never reused`() {
        log.told("one")
        val before = log.rows().maxOf { it.id }
        log.reset()
        log.told("two")

        val ids = log.rows().map { it.id }
        assertEquals("no id appears twice", ids.size, ids.toSet().size)
        assertTrue("and the new rows are above the reset", log.rows().last().id > before + 1)
    }

    @Test
    fun `each project numbers its own rows from one`() {
        // **One monotonic line per project, and the number's meaning is local to it.** A project is
        // the unit of everything else here — its own folder, its own repository, its own agent and
        // its own two files — so a shared counter across projects would need a store that outlives
        // any of them and would make "row 7" mean nothing until you said which project.
        val other = File(System.getProperty("java.io.tmpdir"), "agent-log-other-${System.nanoTime()}")
        other.mkdirs()
        val theirs = AgentLog.open(other)

        log.told("here")
        theirs.told("there")

        assertEquals("each starts at one", 1, log.rows().first().id)
        assertEquals("and so does the other", 1, theirs.rows().first().id)
        assertEquals("with its own counting after that", 2, log.told("here again"))
        assertEquals("from its own one", 2, theirs.told("there again"))
    }

    @Test
    fun `a call's wire id is the row's, so two projects never share a tool call id`() {
        // The rebuilt conversation names a call `call_<row id>`. Ids are per project, so a call id
        // is unique within a request — which is the only scope an endpoint checks — and two
        // projects' conversations can never be mixed into one request to begin with: a request is
        // built from one project's record.
        val other = File(System.getProperty("java.io.tmpdir"), "agent-log-other-${System.nanoTime()}")
        other.mkdirs()
        val theirs = AgentLog.open(other)
        log.call("tree", """{"path":"GitHub"}""")
        theirs.call("tree", """{"path":"GitHub"}""")

        assertEquals("the same number in two projects", log.rows().last().id, theirs.rows().last().id)
        val mine = agentFor(log).messages(log.contextRows()).flatMap { it.calls }.map { it.id }
        val all = agentFor(theirs).messages(theirs.contextRows()).flatMap { it.calls }.map { it.id }
        assertEquals("and each conversation uses it for its own call", mine, all)
        assertEquals("which is legitimate: one request never spans two projects", 1, mine.size)
    }

    @Test
    fun `ids keep counting after a trim drops the early rows`() {
        // **The question the whole audit rests on.** The session is trimmed from the *oldest* end,
        // so the highest id survives in the file and the counter resumes above it. If the trim had
        // ever taken the newest rows, the next row would be numbered over a number that used to
        // mean something else.
        for (i in 1..6) {
            log.told("task $i")
            log.note("agent", "working on $i")
        }
        val before = log.rows().maxOf { it.id }

        // A limit small enough that the trim really drops rows.
        val dropped = log.trimSession(limit = 400)
        assertTrue("the trim happened: dropped $dropped", dropped > 0)
        val after = log.rows()
        assertTrue("and it took the oldest", after.none { it.body == "task 1" })

        val fresh = AgentLog.open(project)
        fresh.told("the next thing")

        val ids = log.rows().map { it.id }
        assertEquals("no id appears twice after a trim", ids.size, ids.toSet().size)
        assertTrue(
            "and the newest row is above everything that came before: $ids",
            log.rows().last().id > before,
        )
    }

    @Test
    fun `a trim in one file does not let the other file's numbering start again`() {
        // The context and the session are trimmed by different rules at different times, so the
        // counter has to be above the highest id in **either** file — otherwise a context trimmed
        // down to one row would hand out its own old numbers again while the session still
        // remembered them.
        for (i in 1..4) {
            log.told("task $i")
            log.note("agent", "work $i")
        }
        val before = log.rows().maxOf { it.id }
        // One messagebox removed per request, so four conversations take four trims — which is the
        // rule being relied on here, not a detail of it.
        repeat(3) { log.trimContext(budget = 1) }
        assertTrue(
            "only the newest task is left: ${log.contextRows().map { it.body }}",
            log.contextRows().none { it.body == "task 1" || it.body == "task 2" || it.body == "task 3" },
        )
        assertEquals(
            "with one marker per trim, and none of them in the context: a marker is the app's row",
            0,
            log.contextRows().count { it.name == "trimmed" },
        )
        assertEquals(
            "and the newest one says how many went altogether",
            "6 rows: the context was over its limit",
            log.rows().last { it.name == "trimmed" }.body,
        )

        AgentLog.open(project).told("after the context was trimmed")

        val all = log.rows().map { it.id }
        assertEquals("still one id per row", all.size, all.toSet().size)
        assertTrue("and still counting upward", log.rows().last().id > before)
    }

    @Test
    fun `two writers at once never hand out the same number`() {
        // **Two `AgentLog` objects, two threads.** The guard inside the class is per instance, so
        // two of them over one project do not exclude each other: both can look at the file, both
        // see it unchanged since they opened, and both take the same next id. The counter has to be
        // checked against the file under a lock the *file* owns, not the object.
        val threads = 4
        val each = 25
        val start = java.util.concurrent.CountDownLatch(1)
        val done = java.util.concurrent.CountDownLatch(threads)
        repeat(threads) { t ->
            Thread {
                start.await()
                val writer = AgentLog.open(project)
                repeat(each) { writer.note("writer-$t", "row $it") }
                done.countDown()
            }.start()
        }
        start.countDown()
        done.await(30, java.util.concurrent.TimeUnit.SECONDS)

        val ids = log.rows().map { it.id }
        assertEquals("every row written", threads * each, ids.size)
        assertEquals(
            "and every id distinct: ${ids.sorted().take(8)}…",
            ids.size,
            ids.toSet().size,
        )
    }

    @Test
    fun `a second writer does not reuse the first writer's ids`() {
        // Two `AgentLog` objects for one project is not hypothetical: the box reads while a poll
        // writes. A counter held in memory would hand both writers the same number, and every
        // diff of the two files would then be lying.
        log.told("from the first")
        val other = AgentLog.open(project)
        val first = log.rows().maxOf { it.id }

        other.told("from the second")

        val all = log.rows().map { it.id }
        assertEquals("one id per row", all.size, all.toSet().size)
        assertTrue("and the later writer is above the earlier", log.rows().last().id > first)
    }

    @Test
    fun `a seal follows its call into the file the call is in`() {
        // After a reset the evidence holds a call the context does not. Sealing it into both
        // would put a tool result in the context with no call above it — a request every
        // endpoint refuses, written by the app's own repair.
        log.told("a task")
        log.call("tree", """{"path":"GitHub"}""")
        log.reset()

        val sealed = log.beginSession()

        assertEquals("the call was answered", 1, sealed)
        assertTrue(
            "in the evidence",
            sessionLines().any { it.contains("app was closed") },
        )
        assertFalse(
            "and not in the context",
            contextLines().any { it.contains("app was closed") },
        )
        assertEquals("and the context is still empty", 0, contextLines().size)
        assertEquals(
            "the seal is the app's row, like the call it answers",
            AgentLog.APP,
            log.rows().last().ctx,
        )
    }

    @Test
    fun `the autotrim removes one messagebox at a time`() {
        // The operator's own words: "yes, autotrim is one messagebox at a time". Dropping as much
        // as would fit would take conversations the agent might still be working from, all at
        // once, and give him no chance to clear it himself first.
        for (i in 1..4) {
            log.told("task $i")
            log.note("agent", "work on $i")
        }

        val dropped = log.trimContext(budget = 1)

        assertEquals("one conversation's rows", 2, dropped)
        assertEquals(
            "and the oldest is the one that went",
            listOf("task 2", "task 3", "task 4"),
            log.contextRows().filter { it.kind == AgentLog.TOLD }.map { it.body },
        )
        assertTrue(
            // The marker is the app's own row, so the model is not told about it: what the model
            // sees is simply a shorter context, which is the truth.
            "the marker says how many, and is the app's",
            log.rows().any { it.name == "trimmed" && it.ctx == AgentLog.APP },
        )
        assertEquals(
            "while the evidence keeps them all",
            4,
            log.rows().count { it.kind == AgentLog.TOLD },
        )
    }

    @Test
    fun `the autotrim will not empty a context of one conversation`() {
        // A context that is one conversation is a context in use: cutting it leaves a task with no
        // task, which is the one row the design says may never be removed.
        log.told("the only task")
        log.note("agent", "working")

        val dropped = log.trimContext(budget = 1)

        assertEquals("nothing is dropped", 0, dropped)
        assertEquals("and the task is still there", 2, log.contextRows().size)
    }

    // ---- the audit: the `out` column is his judgement ------------------------

    @Test
    fun `what the agent forgot is exactly what it named, and nothing else`() {
        log.told("make the list")
        val milkCall = log.call("read", """{"path":"GitHub/milk.txt"}""")
        val milkGot = log.got("read", "0\ntwo litres")
        val eggCall = log.call("read", """{"path":"GitHub/eggs.txt"}""")
        val eggGot = log.got("read", "0\na dozen")
        log.note("agent", "the eggs matter, the milk does not")

        log.forget(setOf(milkCall, milkGot))

        assertEquals(
            "the pair it named",
            listOf(milkCall, milkGot),
            log.thrownAway().map { it.id },
        )
        assertTrue(
            "and nothing it kept",
            log.rows().none { it.id in setOf(eggCall, eggGot) && it.ctx == AgentLog.OUT },
        )
    }

    @Test
    fun `the app's own rows are never called his judgement`() {
        // **The rows a subtraction would have misread.** Each is written for the app's own reasons,
        // so a plain "in the file but not in the context" reading would report them as the agent's
        // clearing — and the audit is only worth having if it is exact about whose decision it was.
        log.told("make the list")
        log.bookkeeping(AgentLog.TURN, "turn", "1")
        log.call("wait", "{}")
        log.got("wait", "0 still waiting")
        log.note("idle", "waiting for the operator")
        // The poll, again: the app's own, by design.
        log.bookkeeping(AgentLog.CALL, "wait", "{}")
        log.bookkeeping(AgentLog.GOT, "wait", "0 still waiting")
        log.bookkeeping(AgentLog.NOTE, "idle", "waiting for the operator")
        log.bookkeeping(AgentLog.NOTE, "retry", "reconnected after 3 tries")
        log.trimContext(budget = 1)

        assertTrue(
            "nothing here is his: ${log.rows().filter { it.ctx == AgentLog.OUT }.map { it.body }}",
            log.thrownAway().isEmpty(),
        )
        assertEquals(
            "and the app's rows say so",
            setOf(AgentLog.IN, AgentLog.APP),
            log.rows().map { it.ctx }.toSet(),
        )
    }

    @Test
    fun `a reset is the person's act, so nothing before it is read as his clearing`() {
        // A reset empties the context, which is shape-identical to the agent forgetting everything.
        repeat(3) { i ->
            log.told("task $i")
            log.call("read", """{"path":"GitHub/f$i.txt"}""")
            log.got("read", "0\nline")
        }
        log.reset()
        log.told("the new task")

        assertTrue("his judgement is empty", log.thrownAway().isEmpty())

        // But a forget he makes *after* the reset is still his.
        val call = log.call("read", """{"path":"GitHub/after.txt"}""")
        val got = log.got("read", "0\nnothing much")
        log.forget(setOf(call, got))
        assertEquals("one pair after the reset", listOf(call, got), log.thrownAway().map { it.id })
    }

    @Test
    fun `a seal the app wrote is not the agent's clearing either`() {
        // The one way a dangling call ends up outside the context: a reset took the context away
        // while the call was still unanswered. The next launch seals it as the app's own row — the
        // seal goes where its call is, which is `app`.
        log.told("make the list")
        log.call("read", """{"path":"GitHub/milk.txt"}""")
        log.reset()
        log.beginSession()

        assertTrue(
            "a seal was written",
            log.rows().any { it.kind == AgentLog.GOT && it.body.contains("closed before") },
        )
        assertTrue("and it is the app's, not his", log.thrownAway().isEmpty())
    }

    @Test
    fun `a row the model is not sent is still on the record`() {
        // The repeated poll is real evidence and is not sent: fifteen of them an hour would make the
        // request differ from itself, and the poll is only cheap while it is a cache hit.
        log.told("a task")
        val contextBefore = contextLines().size

        val poll = log.bookkeeping(AgentLog.CALL, "wait", "{}")

        assertEquals("the record keeps it", contextBefore + 1, sessionLines().size)
        assertEquals("the context does not", contextBefore, contextLines().size)
        assertEquals(
            "and it is marked as the app's, not as the agent's",
            AgentLog.APP,
            log.rows().first { it.id == poll }.ctx,
        )
    }
}
