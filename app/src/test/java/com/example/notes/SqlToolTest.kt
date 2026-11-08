package com.example.notes

import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The read-only guarantee, and the shape of what comes back.
 *
 * **The safety tests matter more than the feature tests**, because this is the one place a model's
 * own words reach a database. Every guard is tested *separately*: three that each have a hole and
 * none that share one is the whole design, and a single test that ran one write would not show
 * whether the other two were doing anything.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SqlToolTest {

    private val app = RuntimeEnvironment.getApplication()
    private lateinit var account: File
    private lateinit var dbFile: File

    @Before
    fun open() {
        account = File(app.cacheDir, "sqltool-${System.nanoTime()}")
        account.mkdirs()
        val db = RequestDb.open(account)
        dbFile = RequestDb.fileFor(account)
        // A little history, so a SELECT has something to find.
        for (h in 0 until 3) {
            RequestDb.insert(
                db,
                1_767_000_000_000L + h * 3_600_000L,
                RequestLog.Stats(
                    kind = RequestLog.Stats.Kind.TURN,
                    ms = 100,
                    provider = "p", model = "m",
                    usage = JSONObject(
                        """{"prompt_tokens":100,"completion_tokens":10,
                           "prompt_tokens_details":{"cached_tokens":40}}""",
                    ),
                    finish = "stop",
                ),
            )
        }
        db.close()
    }

    @After
    fun close() {
        account.deleteRecursively()
    }

    private fun run(sql: String, cap: Int = SqlTool.MAX_ROWS) = SqlTool.run(dbFile, sql, cap)

    // ---- what may run ---------------------------------------------------------

    @Test
    fun `a select runs`() {
        val rows = run("SELECT COUNT(*) AS n FROM requests").getOrThrow()
        assertEquals(listOf("n"), rows.columns)
        assertEquals("3", rows.rows.first().first())
    }

    @Test
    fun `a with clause runs, because it is still a read`() {
        val rows = run("WITH x AS (SELECT 1 AS a) SELECT a FROM x").getOrThrow()
        assertEquals("1", rows.rows.first().first())
    }

    /**
     * **Every way of writing a write is refused.**
     *
     * One at a time rather than one representative, because the allowlist reads only the first word:
     * a query that began with a comment would slip past a naive check, and `WITH … DELETE` is the
     * one that reads like a SELECT at the front and writes at the back.
     */
    @Test
    fun `everything that is not a read is refused`() {
        for (sql in listOf(
            "DROP TABLE requests",
            "INSERT INTO requests(utc) VALUES (1)",
            "UPDATE requests SET prompt = 0",
            "DELETE FROM requests",
            "CREATE TABLE x(a)",
            "ATTACH DATABASE '/tmp/x.db' AS x",
            "PRAGMA query_only = 0",
            // **The two that a naive `startsWith("SELECT")` would let through.**
            "/* a comment */ DROP TABLE requests",
            "-- a comment\nDROP TABLE requests",
            "  ",
        )) {
            assertFalse("refused: $sql", run(sql).isSuccess)
            assertTrue(
                "and the allowlist is what refused it, not SQLite: $sql",
                run(sql).exceptionOrNull()?.message?.contains("not a read") == true,
            )
        }

        // **`WITH … DELETE` is a write that begins with a read keyword**, so no allowlist can catch
        // it by looking at the first word. The database refuses it instead - which is the whole
        // reason the guards are layered rather than one string test.
        val sneaky = run("WITH x AS (SELECT 1) DELETE FROM requests")
        assertTrue("refused at the database", sneaky.isFailure)
        assertTrue(
            "because it is read-only: ${sneaky.exceptionOrNull()?.message}",
            sneaky.exceptionOrNull()?.message?.contains("readonly", ignoreCase = true) == true,
        )
    }

    /** The rows are still there afterwards, which is the only proof that nothing ran. */
    @Test
    fun `a refused write leaves the log exactly as it was`() {
        val before = run("SELECT COUNT(*) AS n FROM requests").getOrThrow().rows.first().first()
        run("DELETE FROM requests")
        val after = run("SELECT COUNT(*) AS n FROM requests").getOrThrow().rows.first().first()
        assertEquals(before, after)
    }

    // ---- what comes back ------------------------------------------------------

    @Test
    fun `a result says whether it is complete`() {
        val all = run("SELECT * FROM requests").getOrThrow()
        assertTrue("three rows is everything", all.complete)
        assertEquals(3, all.total)

        val capped = run("SELECT * FROM requests", cap = 2).getOrThrow()
        assertFalse("two of three is a prefix", capped.complete)
        assertEquals("the total is still known", 3, capped.total)
        assertEquals(2, capped.rows.size)
    }

    @Test
    fun `a summary says how many rows there were when it is capped`() {
        val capped = run("SELECT * FROM requests", cap = 1).getOrThrow()
        val said = SqlTool.summary(capped, "out.csv")
        assertTrue("names the cap: $said", said.contains("of 3"))
        assertTrue("and the file", said.contains("out.csv"))
    }

    @Test
    fun `the columns are the query's own names`() {
        val rows = run("SELECT utc AS at_ms, prompt AS input FROM requests").getOrThrow()
        assertEquals(listOf("at_ms", "input"), rows.columns)
    }

    // ---- the file -------------------------------------------------------------

    /**
     * **A value with a comma in it does not shift the columns.**
     *
     * The failure this prevents is silent in the worst way: a CSV whose columns have slid by one
     * still opens, still has the right number of rows, and says something false.
     */
    @Test
    fun `a comma in a value is quoted rather than shifting the columns`() {
        val csv = SqlTool.csv(
            listOf("model", "prompt"),
            listOf(listOf("a,b", "0.5"), listOf("plain", "1")),
        )
        assertEquals(
            """
            model,prompt
            "a,b",0.5
            plain,1

            """.trimIndent(),
            csv,
        )
    }

    @Test
    fun `a quote in a value is doubled`() {
        val csv = SqlTool.csv(listOf("said"), listOf(listOf("""he said "no"""")))
        assertTrue("doubled: $csv", csv.contains("""he said ""no""""))
    }

    @Test
    fun `an integral double is written without a trailing zero`() {
        // A column of `0.0` reads as broken to a person opening the file.
        val rows = run("SELECT 0.0 AS whole, 1.5 AS other").getOrThrow()
        assertEquals(listOf("0", "1.5"), rows.rows.first())
    }

    // ---- absences -------------------------------------------------------------

    @Test
    fun `a query that matches nothing is not an error`() {
        val rows = run("SELECT * FROM requests WHERE prompt > 1000").getOrThrow()
        assertTrue("no rows", rows.rows.isEmpty())
        assertTrue("and complete", rows.complete)
        assertEquals("only the header", 1, rows.csv.lines().count { it.isNotEmpty() })
    }

    @Test
    fun `a missing database says so rather than crashing`() {
        val gone = File(account, "nothing-here.db")
        val result = SqlTool.run(gone, "SELECT 1")
        assertTrue("refused", result.isFailure)
        assertTrue(
            "in words: ${result.exceptionOrNull()?.message}",
            result.exceptionOrNull()?.message?.contains("no request log") == true,
        )
    }

    /**
     * **A SQLite error reaches the agent in SQLite's own words.**
     *
     * "no such column: cached" is the sentence that lets a model fix its query. The alternative —
     * wrapping it as "the query failed" — leaves it guessing, and the next guess costs another turn.
     */
    @Test
    fun `a bad column says which column, in SQLite's words`() {
        val why = run("SELECT nonsense FROM requests").exceptionOrNull()?.message.orEmpty()
        assertTrue("names it: $why", why.contains("nonsense"))
    }

    // ---- the summary the conversation gets ------------------------------------

    @Test
    fun `the summary shows a few rows and says where the rest are`() {
        val many = run("SELECT * FROM requests", cap = 3).getOrThrow()
        val said = SqlTool.summary(many, "f.csv")
        assertTrue("names the file", said.contains("f.csv"))
        assertTrue("and where the rest is", said.contains("more in the file") || said.contains("3 rows"))
    }
}
