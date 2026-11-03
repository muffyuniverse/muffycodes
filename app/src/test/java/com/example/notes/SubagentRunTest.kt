package com.example.notes

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * **One subagent's turn: its own log, its own scope, and a report into the chat agent's record.**
 *
 * The three things worth proving are the three that make a subagent a subagent rather than a tool
 * call: it writes its record **somewhere else** (`agent/<id>/`, not `agent/`), it **cannot reach**
 * outside its project, and its answer arrives as a `SUB` row in the conversation that hired it.
 */
@RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [34])
class SubagentRunTest {

    private lateinit var root: File
    private lateinit var ws: Workspace
    private lateinit var git: ProjectGit
    private lateinit var settings: SettingsStore
    private lateinit var chat: AgentLog

    private val author = GitAuthor("Test", "test@example.com")
    private val model = ActiveModel("deepseek/deepseek-v4.1-flash-1017", "deepseek", "high")

    @Before
    fun makeSpace() {
        root = File(System.getProperty("java.io.tmpdir"), "subrun-${System.nanoTime()}")
        ws = Workspace(File(root, "muffyuniverse")).apply { accountDir.mkdirs() }
        ws.projectDir("groceries").mkdirs()
        ws.projectDir("notes").mkdirs()
        File(ws.projectDir("groceries"), "list.txt").writeText("milk\neggs\n")
        File(ws.projectDir("notes"), "secret.txt").writeText("not for you\n")
        git = ProjectGit(ws, { null }, { author })
        git.init("groceries").getOrThrow()
        git.init("notes").getOrThrow()
        chat = AgentLog.open(ws.projectDir("groceries"))
        settings = SettingsStore(org.robolectric.RuntimeEnvironment.getApplication())
    }

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun run(door: Door, report: (String, Int, String) -> Unit = { _, _, _ -> }) = SubagentRun(
        ws = ws,
        git = git,
        settings = settings,
        door = { door },
        model = { model },
        report = report,
    )

    // ---- its own record -----------------------------------------------------

    @Test
    fun `a subagent writes its own record, not the chat agent's`() {
        // **Two agents writing one file is the lost-update shape the app avoids everywhere.** The
        // chat agent's record is `agent/`; a subagent's is `agent/<id>/`, which is also what makes
        // its context survive between tasks.
        val before = chat.rows().size

        run(ScriptedDoor(Reply("counted 41 tests"))).run("groceries", id = 2, task = "count the tests")

        assertEquals("the chat agent's record is untouched", before, chat.rows().size)
        val mine = AgentLog.openAt(File(ws.projectDir("groceries"), "${AgentLog.DIR}/2"))
        assertTrue(
            "**and the subagent's own record has the work**: ${mine.rows().map { it.kind }}",
            mine.rows().isNotEmpty(),
        )
    }

    @Test
    fun `the subagent's record is inside the project, so the existing exclude keeps it out of git`() {
        // The `agent/` line in `.git/info/exclude` matches the whole subtree, so `agent/2/` needs
        // no second line — and a record that reached GitHub would be the person's history polluted
        // with the app's bookkeeping.
        run(ScriptedDoor(Reply("done"))).run("groceries", id = 1, task = "look")

        val excluded = File(ws.projectDir("groceries"), ".git/info/exclude").readText()
        assertTrue("the one line covers every id: $excluded", excluded.contains("agent/"))
    }

    // ---- the scope ----------------------------------------------------------

    @Test
    fun `a subagent cannot read another project, even though its prompt allows the chat agent to`() {
        // **The boundary, through a real turn.** A subagent whose model asks for `../notes/…` gets a
        // refusal, and the other project's text never enters its context — which is what a shorter
        // tool list could not have achieved, because `read` is a tool it legitimately has.
        val door = ScriptedDoor(
            Reply("", listOf(Call("c1", "read", """{"path":"../notes/secret.txt"}"""))),
            Reply("I could not read it."),
        )

        run(door).run("groceries", id = 1, task = "read the notes project")

        val got = AgentLog.openAt(File(ws.projectDir("groceries"), "${AgentLog.DIR}/1"))
            .rows()
            .filter { it.kind == AgentLog.GOT }
            .first()
        assertTrue("refused: ${got.body}", got.body.startsWith(AgentLog.FAILURE))
        assertFalse(
            "**and the other project's text is not in the answer**",
            got.body.contains("not for you"),
        )
        assertTrue(
            "the refusal names the boundary: ${got.body}",
            got.body.contains("outside groceries"),
        )
    }

    @Test
    fun `a subagent can read and write its own project`() {
        val door = ScriptedDoor(
            Reply("", listOf(Call("c1", "create", """{"path":"counted.txt","text":"41"}"""))),
            Reply("wrote it"),
        )

        run(door).run("groceries", id = 1, task = "write the count")

        assertEquals(
            "the file is in the project",
            "41",
            File(ws.projectDir("groceries"), "counted.txt").readText(),
        )
    }

    // ---- the report ---------------------------------------------------------

    @Test
    fun `what it says becomes a report, and a report is result-shaped`() {
        var said: Triple<String, Int, String>? = null
        run(ScriptedDoor(Reply("counted 41 tests"))).run("groceries", 3) { p, id, body ->
            said = Triple(p, id, body)
        }

        val (project, id, body) = said ?: error("a report was expected")
        assertEquals("for the project", "groceries", project)
        assertEquals("from the right subagent", 3, id)
        assertTrue(
            "**`0` first**, so the model reads it as an outcome rather than as prose: $body",
            body.startsWith("0\n"),
        )
        assertTrue("with what it said: $body", body.contains("counted 41 tests"))
    }

    @Test
    fun `a subagent that says nothing still reports, because silence is work with no trace`() {
        // The app's rule is that everything that exists is reachable. A subagent that finished and
        // wrote nothing would be a task the chat agent believes is still running.
        var body: String? = null
        run(ScriptedDoor(Reply(""))).run("groceries", 1) { _, _, b -> body = b }

        assertTrue("a report was made: $body", body != null)
        assertTrue("and it says the subagent finished: $body", body!!.contains("nothing to report"))
    }

    @Test
    fun `a failure is reported too, or the task looks like it is still running`() {
        // A door that refuses is the commonest failure and the one a silent catch would hide.
        val door = object : Door {
            override fun turn(request: Request): Result<Reply> =
                Result.failure(IllegalStateException("the provider refused"))
            override fun ping(request: Request): Result<Unit> = Result.success(Unit)
        }

        var body: String? = null
        run(door).run("groceries", 4) { _, _, b -> body = b }

        assertTrue("reported: $body", body != null)
        assertTrue(
            "**as a failure**, status 1, so the model knows it was not an answer: $body",
            body!!.startsWith("1\n"),
        )
        assertTrue("and naming the subagent: $body", body.contains("#4"))
    }

    @Test
    fun `a project that is gone does not run, and says so rather than making the folder`() {
        // **The bug this codebase spent a day fixing, in a new place.** A subagent for a deleted
        // project must not recreate it — `AgentLog.open` no longer makes a folder, and the guard
        // here is the second half of the same rule.
        val gone = ws.projectDir("gone")

        var reported = false
        val done = run(ScriptedDoor(Reply("x"))).run("gone", 1) { _, _, _ -> reported = true }

        assertTrue("it did not run: $done", done is SubagentRun.Done.Failed)
        assertFalse("and nothing was created", gone.exists())
        assertFalse("and no report, because there is no conversation to report into", reported)
    }
}
