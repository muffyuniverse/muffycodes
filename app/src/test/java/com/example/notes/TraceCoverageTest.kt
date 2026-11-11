package com.example.notes

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * **The acts that are traced, proven to reach the log.**
 *
 * The tap-vocabulary check proves a name is in the standard. It does not prove the log records the
 * act, and until this file existed four of this stretch's acts had no test at all: `query`
 * and `swipe file` were written with confident comments and no evidence, and the
 * heartbeat read was not traced at all.
 *
 * Each test asserts the *name* appears in the newest rows rather than only that the counter moved,
 * because a counter would pass on any tap and the name is what says which act it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TraceCoverageTest {

    private val app = RuntimeEnvironment.getApplication()

    /** The newest rows, as text, so a failure shows what was actually written. */
    private fun newest(): String = Trace.report().lines.take(4).joinToString("\n")

    /**
     * **A query that writes a file is recorded, with how much it wrote.**
     *
     * The one tool whose effect lands in a file rather than in the conversation, so a log that could
     * not say "the agent queried the log" would leave a person unable to tell what produced a CSV.
     */
    @Test
    fun `the query tool records that it ran, and how many rows it wrote`() {
        val root = File(app.cacheDir, "trace-${System.nanoTime()}").apply { mkdirs() }
        try {
            val ws = Workspace.single(root)
            ws.projectDir("shopping").mkdirs()
            val git = ProjectGit(ws, { null }, { GitAuthor("Muffy", "muffy@example.com") })
            val log = AgentLog.open(ws.projectDir("shopping"))
            // A real log, because `query` refuses to invent one.
            val db = RequestDb.open(ws.accountDir)
            RequestDb.insert(
                db, System.currentTimeMillis(),
                RequestLog.Stats(
                    kind = RequestLog.Stats.Kind.TURN, ms = 100, provider = "p", model = "m",
                    usage = org.json.JSONObject("""{"prompt_tokens":10,"completion_tokens":1}"""),
                    finish = "stop",
                ),
            )
            db.close()

            val before = Trace.report().taps
            val answer = AgentTools(ws, "shopping", git, log).run(
                Call(id = "1", tool = "query", args = """{"sql":"SELECT COUNT(*) AS n FROM requests"}"""),
            )

            // **The summary starts with the column header**, then the count, then the path - so the
            // assertion is on what the answer says rather than on a status prefix this tool does not
            // emit. (`query` returns prose, like every other tool that produces a document.)
            assertTrue("the column is named: $answer", answer.startsWith("n"))
            assertTrue("and the row was found: $answer", answer.contains("1 rows"))
            // **The file is filed, not named by the caller** (operator, 2026-11-10): the answer
            // names `agent/sql/query-<ms>.csv`, and the `path` argument is gone.
            val where = Regex("""agent/sql/query-\d+\.csv""").find(answer)?.value
                ?: error("the answer names no file: $answer")
            assertTrue("and a file was written", File(ws.projectDir("shopping"), where).isFile)
            assertTrue("the tap was recorded", Trace.report().taps > before)
            assertTrue(
                "and named: ${newest()}",
                newest().contains("TAP") && newest().contains("query"),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * **The heartbeat read records how long it took.**
     *
     * It is the one read that opens a database from thirty call sites, so "did the card stall" has
     * to be answerable from a log written by a person holding the phone. Without a line there is no
     * evidence that a read happened at all, and a slow device is indistinguishable from a card that
     * simply looks old.
     */
    @Test
    fun `the heartbeat read records that it ran, and how long it took`() {
        val phone = Scenario(app, signIn = true)
        try {
            phone.await("the account to be ready") { phone.viewModel.workspace != null }
            val account = phone.viewModel.workspace?.accountDir ?: error("signed in, so there is one")
            // A request, so the read has something to publish - an empty card is deliberately not
            // recorded, since it is the ordinary state of an account nobody has used.
            RequestDb.write(
                account, System.currentTimeMillis(),
                RequestLog.Stats(
                    kind = RequestLog.Stats.Kind.TURN, ms = 100, provider = "p", model = "m",
                    usage = org.json.JSONObject("""{"prompt_tokens":10,"completion_tokens":1}"""),
                    finish = "stop",
                ),
            )

            phone.viewModel.refresh()
            phone.await("the card to show the request") {
                phone.viewModel.heartbeat.firstOrNull { it.id == Heartbeat.Id.REQUESTS }?.recent != null
            }

            assertTrue(
                "the read is in the log: ${newest()}",
                Trace.report().lines.take(8).any { it.contains("heartbeat") && it.contains("ms") },
            )
        } finally {
            phone.close()
        }
    }
}
