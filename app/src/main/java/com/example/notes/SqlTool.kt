package com.example.notes

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Read-only SQL over the request log, so an agent can answer questions about the person's usage.
 *
 * **Why a database and not a tool with parameters.** "What did last week cost", "compare this Tuesday
 * to last", "which model costs most per answer" are one query language's worth of questions and
 * infinitely many tools' worth. A model that can write SQL answers the next question without an
 * update; a model with four fixed tools answers four.
 *
 * ## The three guards, and why one is not enough
 *
 * **Each layer alone has a hole, and they do not share one.**
 *
 * 1. `OPEN_READONLY` refuses writes at the file level, but SQLite will still open a *new* file for
 *    `ATTACH`, and a new file is not read-only because it did not exist to be opened read-only.
 * 2. `PRAGMA query_only` refuses writes at the connection level, including into a file attached
 *    afterwards - but the pragma is connection state, so a connection that is reopened without it
 *    loses the guarantee silently.
 * 3. **A first-keyword allowlist** refuses anything that is not `SELECT` or `WITH` before it is
 *    prepared, which is the only one of the three that can *explain* itself: an agent told "that is
 *    not a read" writes a different query, where an agent given `SQLiteReadOnlyDatabaseException`
 *    usually tries the same thing again.
 *
 * The allowlist is also the one that survives a future refactor - a new connection or a changed flag
 * cannot quietly turn it off, because it is a string test on the model's own text.
 *
 * ## Why results go to a file
 *
 * A thousand rows in a conversation is a thousand rows the agent re-reads on every following turn,
 * and the tail of it gets cut off by the context budget - so the answer would be both expensive and
 * silently incomplete. A file costs one line in the conversation and is inspectable afterwards.
 */
object SqlTool {

    /**
     * The most rows one query may return.
     *
     * **Stated in the answer, not silent.** An agent that gets a thousand rows back has no way to
     * tell whether it saw the whole result or a prefix, and a truncated total reported as a total is
     * the worst kind of wrong answer: confident and quietly false.
     */
    const val MAX_ROWS = 1_000

    /** How much of a long cell is kept in the file, so one runaway column cannot fill the disk. */
    private const val MAX_CELL = 2_000

    /**
     * The tables and columns a query may use, written for the model rather than for a document.
     *
     * **Given in the tool's description**, because a model told only "there is a database" will
     * write `SELECT * FROM requests` over a quarter of a million rows and learn nothing. Naming the
     * columns turns the first attempt into a real one.
     */
    val SCHEMA = """
        Three rollup tables and one raw table. All times are UTC milliseconds ('bucket' or 'utc').
        Compute a day with (bucket / 86400000) * 86400000, an hour with (bucket / 3600000) * 3600000.

        roll_day / roll_hour / roll_minute
          bucket      INTEGER  start of the bucket
          provider    TEXT     e.g. 'deepinfra/fp8/flex'
          model       TEXT     e.g. 'deepseek/deepseek-v4.1-flash'
          project     TEXT     the project that spent it, '' if not known
          n           INTEGER  how many requests
          prompt      INTEGER  input tokens
          cached      INTEGER  input tokens served from cache (a subset of prompt)
          completion  INTEGER  output tokens
          reasoning   INTEGER  thinking tokens (billed as output, counted apart)
          cost        REAL     US dollars
          reported    INTEGER  how many of these n reported a cost
          said        INTEGER  characters of answer, for stalled requests
          thinking    INTEGER  characters of thinking, for stalled requests

        requests    one row per request, kept about a minute before it is rolled up
          utc, kind, ms, prompt, cached, completion, reasoning, cost, reported,
          said, thinking, finish, provider, model, project
          kind: 0 = a turn, 1 = a cache keepalive, 2 = a stalled answer

        roll_minute covers 2 days. roll_hour and roll_day cover the retention period and are the
        right tables for anything older than two days.
    """.trimIndent()

    /** What one query produced. */
    data class Answer(
        val columns: List<String>,
        val rows: List<List<String>>,
        /** How many rows the query would have returned without the cap. */
        val total: Int,
        val csv: String,
    ) {
        /** Whether [rows] is everything the query found. */
        val complete: Boolean get() = total <= rows.size
    }

    /**
     * Refuses anything that is not a read, **before** the database is opened.
     *
     * Skipping comments and whitespace first, because `/* hello */ DROP TABLE` is a write and a
     * naive `startsWith("SELECT")` would not see it.
     */
    fun isRead(sql: String): Boolean = lead(sql) in setOf("SELECT", "WITH")

    /** The first word, lowercased, with comments and leading whitespace removed. */
    private fun lead(sql: String): String {
        var i = 0
        val s = sql
        while (i < s.length) {
            when {
                s[i].isWhitespace() -> i++
                s.startsWith("--", i) -> i = s.indexOf('\n', i).let { if (it < 0) s.length else it + 1 }
                s.startsWith("/*", i) -> i = s.indexOf("*/", i).let { if (it < 0) s.length else it + 2 }
                else -> return s.substring(i).takeWhile { it.isLetter() }.lowercase(Locale.UK)
            }
        }
        return ""
    }

    /**
     * Runs [sql] and returns its rows.
     *
     * **Opened read-only, then made query-only, then run.** See the class note for why the order and
     * the redundancy are both deliberate.
     */
    fun run(dbFile: File, sql: String, cap: Int = MAX_ROWS): kotlin.Result<Answer> {
        if (!isRead(sql)) {
            return kotlin.Result.failure(
                IllegalArgumentException(
                    "That is not a read. Only SELECT and WITH are allowed - this database is the " +
                        "person's record of what they spent, and it is not writable from here.",
                ),
            )
        }
        if (!dbFile.exists()) {
            return kotlin.Result.failure(IllegalStateException("There is no request log yet."))
        }
        // **A local, not a `var` captured by the closure.** As a nullable `var` the compiler cannot
        // smart-cast it at the call, and the whole `try` then infers a common supertype - which is
        // how a `kotlin.Result<Answer>` becomes `Any` and the error points at the return type rather
        // than at anything wrong.
        var opened: SQLiteDatabase? = null
        return try {
            val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
            opened = db
            // **The second guard, and the one that covers `ATTACH`.** A database opened read-only
            // can still attach a *new* file and write to that, because the new file was not opened
            // read-only - it did not exist when the flag was applied.
            db.execSQL("PRAGMA query_only = 1")
            read(db, sql, cap)
        } catch (e: SQLiteException) {
            // **SQLite's own words, not wrapped.** "no such column: cached" is the sentence that
            // lets the model fix its query; "the query failed" is the sentence that makes it guess.
            kotlin.Result.failure(IllegalArgumentException(e.message ?: "the query could not run"))
        } catch (e: Throwable) {
            kotlin.Result.failure(e)
        } finally {
            try {
                opened?.close()
            } catch (t: Throwable) {
                // A close that fails leaves nothing to do; the result is already decided.
            }
        }
    }

    private fun read(db: SQLiteDatabase, sql: String, cap: Int): Answer {
        db.rawQuery(sql, null).use { c ->
            val columns = c.columnNames.toList()
            val rows = mutableListOf<List<String>>()
            var total = 0
            while (c.moveToNext()) {
                total++
                // **Counted past the cap, kept up to it.** The count is what lets the answer say
                // "showing 1,000 of 41,832" instead of implying it showed everything.
                if (rows.size < cap) {
                    rows += columns.indices.map { i -> cell(c, i) }
                }
            }
            return Answer(columns = columns, rows = rows, total = total, csv = csv(columns, rows))
        }
    }

    private fun cell(c: android.database.Cursor, i: Int): String {
        val text = when (c.getType(i)) {
            android.database.Cursor.FIELD_TYPE_NULL -> ""
            android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i).toString()
            android.database.Cursor.FIELD_TYPE_FLOAT -> {
                val d = c.getDouble(i)
                // **Integral doubles lose the trailing `.0`.** A cost column full of `0.0` reads as
                // broken to a person opening the file, and `0` is what SQLite itself would print.
                if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 1e15) {
                    d.toLong().toString()
                } else {
                    d.toString()
                }
            }
            else -> c.getString(i)
        }
        return if (text.length > MAX_CELL) text.take(MAX_CELL) + "…" else text
    }

    /** Rows as CSV, quoted to the rule that survives a spreadsheet. */
    fun csv(columns: List<String>, rows: List<List<String>>): String = buildString {
        append(columns.joinToString(",") { quote(it) }).append('\n')
        for (row in rows) append(row.joinToString(",") { quote(it) }).append('\n')
    }

    /**
     * The one rule that makes a CSV readable by everything else.
     *
     * **A quote is doubled, and a field containing a comma, quote or newline is wrapped.** Without
     * this a model name with a comma in it - or an answer that contained one - shifts every column
     * after it, and the file is silently wrong rather than visibly broken.
     */
    private fun quote(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }

    /**
     * The summary the conversation gets: shape, size, and the first few rows.
     *
     * **Not the rows themselves.** The file holds all of them; a handful here lets the agent answer
     * immediately without spending the conversation on data it can read again if it needs to.
     */
    fun summary(result: Answer, path: String): String = buildString {
        append(result.columns.joinToString(" | ")).append('\n')
        append("— ").append(result.rows.size).append(" rows")
        if (!result.complete) append(" of ${result.total} (capped at $MAX_ROWS)")
        append(" → ").append(path).append('\n')
        for (row in result.rows.take(PREVIEW_ROWS)) {
            append(row.joinToString(" | ").take(PREVIEW_WIDTH)).append('\n')
        }
        if (result.rows.size > PREVIEW_ROWS) {
            append("… ${result.rows.size - PREVIEW_ROWS} more in the file.")
        }
    }

    private const val PREVIEW_ROWS = 8
    private const val PREVIEW_WIDTH = 200

    /** An empty result, said in words rather than as a header with nothing under it. */
    fun emptyMessage(): String =
        "The query ran and matched nothing. The tables were readable - so this is an absence of " +
            "rows rather than a mistake, unless the interval is outside what is kept: minutes are " +
            "held for two days, hours and days for the retention period."
}
