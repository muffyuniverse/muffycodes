package com.example.notes

import java.io.File

/**
 * The agent's record: two files, the same rows, one id.
 *
 * `agent/session.log` is the evidence — every event, in order, and the app truncates it at a
 * generous size. `agent/context.log` is the working context — the same rows, minus the ones the
 * agent cleared out of it — and it is the only one of the two that is ever sent to the model.
 *
 * **The rows are identical, and that is the design.** They share an id and they share their text,
 * so `diff` between the two files is exactly the agent's judgement about what did not matter:
 * a row that is in `session.log` and not in `context.log` is one he decided to forget. A second
 * format, or a second id sequence, would make that diff a reconciliation instead of a glance.
 *
 * The columns are [LogFormat]'s — the trace's own — because both files are read by the same
 * habits: `grep`, a shell, an eye on a phone.
 */
class AgentLog private constructor(
    val session: File,
    val context: File,
) {

    private val guard = Any()

    /** The id the next row gets. Shared by both files, so a row's id means one thing. */
    private var next = 1

    /**
     * The session's length after this instance's own last append; `-1` until it writes.
     *
     * **A counter in memory goes stale the moment another writer touches the file**, and two
     * rows with one id is the one thing that breaks the audit this format is for — `session.log`
     * minus `context.log` is only the agent's judgement if an id means one row. [nextId] compares
     * the file against what this instance left and re-reads the highest id when they differ.
     */
    private var seen = -1L

    companion object {

        /** The folder name under a project: `agent/`. */
        const val DIR = "agent"
        const val SESSION = "session.log"
        const val CONTEXT = "context.log"

        /**
         * Generous, because this file keeps the repeated `wait` rows too.
         *
         * The context is trimmed by the agent to what it needs; the session is the evidence, and
         * an agent that polls every four minutes writes fifteen rows an hour — so the session's
         * limit has to be the sort of size that holds days, not the trace's 512 KB.
         */
        const val SESSION_LIMIT = 4L * 1024 * 1024

        /** The row kinds. Named once, so the box, the tools and the checks agree. */
        const val TOLD = "TOLD"

        /**
         * The app's own boundary: one request to the model starts here.
         *
         * The box counts turns — "3 of 6" — and a turn is a request, not a row. Nothing else in
         * the record says where one request ended and the next began: a reply with two calls in
         * it and two replies with one call each look exactly the same without this row.
         *
         * **Session only.** It is the app's bookkeeping, it carries a clock, and the model has no
         * use for it — sending it would put a changing row in the one part of the request that
         * has to be byte-identical between polls.
         */
        const val TURN = "TURN"

        const val CALL = "CALL"
        const val GOT = "GOT"
        const val ASK = "ASK"
        const val CONCLUDE = "CONCLUDE"
        const val NOTE = "NOTE"

        /**
         * The tools that end a turn.
         *
         * Named here rather than imported from [AgentTools], because the record has to be
         * readable — and sealable — without a workspace, a repository or a model behind it.
         */
        val TERMINATING = setOf("ask", "conclude", "wait")

        /**
         * The terminating tools that never get a result, because their words are their own row.
         *
         * `ask` and `conclude` end the turn and are never run, so nothing answers them — and a
         * conversation carrying a tool call with no tool message is one every endpoint refuses.
         * Their text is written as `ASK`/`CONCLUDE`, and that is what the model reads back.
         * `wait` is not among them: its call is answered once, so the idle agent's context stays
         * a legal conversation.
         */
        val SPEAKING = setOf("ask", "conclude")

        /** A result's first line: the call worked. */
        const val SUCCESS = "0"

        /** A result's first line: it did not, and the rest of the body says why. */
        const val FAILURE = "1"

        /**
         * `14:02:11.410    8 CALL  tree      {"path":"GitHub"}`
         *
         * The body is the only part that can hold a separator, and [LogFormat] escapes it — so
         * two spaces followed by anything is the detail, always.
         */
        private val ROW = Regex("""^(\d\d:\d\d:\d\d\.\d\d\d) +(\d+) +(\S+) +(\S+)  (.*)$""")

        /** Opens the record of the project in [projectDir], making the folder on first use. */
        fun open(projectDir: File): AgentLog {
            val dir = File(projectDir, DIR)
            dir.mkdirs()
            val log = AgentLog(File(dir, SESSION), File(dir, CONTEXT))
            log.load()
            return log
        }

        /**
         * One line as a row.
         *
         * Not private: the clear-up check reads a file the agent is about to write, and it has
         * to ask the same question this file asks of its own lines — otherwise a row the app
         * cannot read and a row the checker cannot read are two different rows.
         */
        fun parseRow(line: String): Row = try {
            val match = ROW.matchEntire(line)
            if (match == null) {
                Row("", 0, "?", "?", line, broken = true)
            } else {
                Row(
                    time = match.groupValues[1],
                    id = match.groupValues[2].toInt(),
                    kind = match.groupValues[3],
                    name = match.groupValues[4],
                    detail = match.groupValues[5],
                )
            }
        } catch (t: Throwable) {
            Row("", 0, "?", "?", line, broken = true)
        }

        /**
         * Why [after] is not a clear-up of [before], or null when it is.
         *
         * The agent shrinks its own context, and this is the whole of what it may do to it:
         * **remove whole rows.** Not rewrite them, not add them, not leave half a question. That
         * is what keeps the one auditable property of the two files true — `session.log` minus
         * `context.log` is exactly what the agent decided did not matter — and it is what keeps
         * the rebuilt conversation a request an endpoint will accept.
         *
         * Four rules, each with its own sentence, because a refusal that does not say which rule
         * it broke teaches the model nothing:
         * 1. every line still reads as a row;
         * 2. what is left is the **same lines**, in the same order — removal, nothing else;
         * 3. the newest task is still on the record — it is what the turn is for;
         * 4. a call and its result stay together, because a call with no answer is a request
         *    every endpoint refuses and an answer with no call is a message it cannot place.
         *
         * The lines are compared as text, not as rows re-rendered back into lines: "the same
         * row" is a claim about the file's bytes, and a comparison that went through the parser
         * twice could pass an edit that changed them.
         */
        fun clearUpProblem(before: List<String>, after: List<String>): String? {
            if (after.isEmpty()) return "Your context cannot be emptied: it holds the task"
            val rows = after.map { parseRow(it) }
            if (rows.any { it.broken }) {
                return "A line no longer reads as a row; keep the five columns exactly as they are"
            }
            var index = 0
            for (line in after) {
                while (index < before.size && before[index] != line) index++
                if (index == before.size) {
                    return "Clear-up removes rows. It does not change them or add new ones."
                }
                index++
            }
            val task = before.map { parseRow(it) }.lastOrNull { it.kind == TOLD }
            if (task != null && rows.none { it.id == task.id && it.kind == TOLD }) {
                return "The newest task stays: it is what you are working on"
            }
            val waiting = ArrayDeque<Row>()
            for (row in rows) {
                when (row.kind) {
                    CALL -> if (row.name !in SPEAKING) waiting.addLast(row)
                    GOT -> {
                        if (waiting.isEmpty()) {
                            return "A result with no call before it: remove the pair together"
                        }
                        waiting.removeFirst()
                    }
                }
            }
            val left = waiting.firstOrNull()
            if (left != null) {
                return "The call to ${left.name} has no result left: remove the pair together"
            }
            return null
        }

        /**
         * Splits rows into bursts, oldest first.
         *
         * A burst is one conversation: an operator's message starts it, and a terminating row
         * ends it. Trimming happens between bursts and never inside one — half a conversation is
         * a log that reads like a bug.
         */
        fun bursts(rows: List<Row>): List<List<Row>> {
            val out = mutableListOf<MutableList<Row>>()
            for (row in rows) {
                if (row.startsBurst || out.isEmpty()) out.add(mutableListOf())
                out.last().add(row)
                if (row.endsBurst) out.add(mutableListOf())
            }
            return out.filter { it.isNotEmpty() }.map { it.toList() }
        }

        /**
         * The newest whole bursts that fit [budget], and how many rows were dropped.
         *
         * Whole bursts only, so a conversation is never cut in half; the newest burst is always
         * kept even when it alone is over budget, because "the thing that just happened" is not
         * something a size limit gets to delete. [size] is what a burst costs — bytes here, and a
         * row count in the tests that think in rows.
         */
        fun keepNewest(
            rows: List<Row>,
            budget: Long = Long.MAX_VALUE,
            size: (Row) -> Long = { 1 },
        ): Pair<List<Row>, Int> {
            val kept = mutableListOf<List<Row>>()
            var total = 0L
            for (burst in bursts(rows).asReversed()) {
                val cost = burst.sumOf(size)
                if (kept.isNotEmpty() && total + cost > budget) break
                kept.add(burst)
                total += cost
            }
            val newest = kept.asReversed().flatten()
            return newest to (rows.size - newest.size)
        }
    }

    /** One row of either file. */
    data class Row(
        val time: String,
        val id: Int,
        val kind: String,
        val name: String,
        val detail: String,
        /** True for a line that did not parse: shown as a row that could not be read. */
        val broken: Boolean = false,
    ) {
        /** An operator's message begins a conversation, and a terminating tool ends it. */
        val startsBurst: Boolean get() = kind == TOLD
        val endsBurst: Boolean get() = kind == ASK || kind == CONCLUDE

        /**
         * The row as it stands in the file.
         *
         * [detail] is the file's own column — already escaped — so this uses [LogFormat.rawRow]
         * and not [LogFormat.row]: re-escaping a parsed row doubles a backslash every time the
         * file is rewritten, which is a record that quietly stops matching what was recorded.
         */
        val line: String
            get() = if (broken) detail else LogFormat.rawRow(time, id, kind, name, detail)

        /** The body as the agent wrote it, with its newlines back. */
        val body: String get() = LogFormat.unescaped(detail)
    }

    /**
     * Trims the working context to [budget] tokens, oldest conversation first.
     *
     * **Whole conversations, and never the newest one.** A call whose result was cut off is a
     * request every endpoint refuses, so the trim works in bursts like the session's own; and the
     * newest burst holds the task being worked on, which is the one thing §2.1 says may not be
     * removed. [keepNewest] already keeps the newest burst even when it alone is over budget —
     * that is why this can be a call rather than a policy.
     *
     * A marker row goes in both files, so the person reading the evidence still sees what happened
     * and the model is told the context was shortened rather than finding it mysteriously smaller.
     * The marker's name is not one [Agent.messages] renders.
     */
    fun trimContext(budget: Long, cost: (Row) -> Long = { 0L }): Int {
        synchronized(guard) {
            val rows = contextRows()
            if (rows.isEmpty() || budget <= 0L) return 0
            val (kept, dropped) = keepNewest(rows, budget = budget) { row ->
                // A row's own length is what the request pays for; [cost] lets a caller add the
                // constant overhead each row carries on the wire. Bytes here by default, because
                // the estimate is the app's and this is the file it is estimating.
                row.line.length.toLong() + cost(row)
            }
            if (dropped == 0) return 0
            val marker = LogFormat.row(
                System.currentTimeMillis(), nextId(), NOTE, "trimmed",
                "$dropped rows: the context was over its limit",
            )
            val text = (kept.map { it.line } + marker).joinToString("\n") + "\n"
            try {
                val fresh = File(context.parentFile, context.name + ".new")
                fresh.writeText(text)
                if (!fresh.renameTo(context)) {
                    context.writeText(text)
                    fresh.delete()
                }
            } catch (t: Throwable) {
                return 0
            }
            // The marker only exists in the context: putting it in the evidence too would claim a
            // row the agent never saw.
            append(session, marker)
            seen = size()
            return dropped
        }
    }

    // ---- the person's own operation ------------------------------------------

    /**
     * Throws the working context away, and keeps the evidence.
     *
     * **The app's operation, not the agent's.** The agent's clear-up may only remove rows and may
     * never remove the newest task — that is what [clearUpProblem] checks — because a context it
     * can empty is a context it can quietly restart from. A reset is the person saying "the
     * conversation starts from here", which is a different act, so it is a different door rather
     * than a relaxation of his.
     *
     * `session.log` is untouched: it is the evidence of what happened, and a reset is a fact
     * about the working context, not a way to make history disappear. The marker row is written
     * to **both** files under one id, so `session.log` minus `context.log` still means exactly
     * one thing — what the agent decided did not matter — with resetting listed as the app's own
     * act rather than looking like a row he removed.
     *
     * The marker's name is not one [Agent.messages] renders, so the model is not handed an
     * app-authored sentence: after a reset the next request is the instructions and the new task,
     * and nothing else.
     */
    fun reset(): Int = synchronized(guard) {
        val id = nextId()
        val line = LogFormat.row(
            System.currentTimeMillis(), id, NOTE, "reset", "the working context was cleared",
        )
        append(session, line)
        try {
            val fresh = File(context.parentFile, context.name + ".new")
            fresh.writeText("$line\n")
            // Beside and renamed: a reader must see the old context or the new one, never a file
            // that has been truncated and not yet refilled.
            if (!fresh.renameTo(context)) {
                context.writeText("$line\n")
                fresh.delete()
            }
        } catch (t: Throwable) {
            // The file is the failure. The session row still says a reset happened, which is the
            // half that must not be lost quietly — and the next turn's seal keeps the context
            // legal either way.
        }
        seen = size()
        id
    }

    // ---- writing ------------------------------------------------------------

    /** The operator's words. Appended to both, because this is what a turn is for. */
    fun told(text: String): Int = record(TOLD, "operator", text, both = true)

    /** A tool the agent called: the name, and its arguments as the model wrote them. */
    fun call(tool: String, args: String): Int = record(CALL, tool, args, both = true)

    /**
     * The answer to a call. Its first line is `0` or `1`, always.
     *
     * Written as its own row under the tool's name rather than folded into the call, because the
     * two happen at different times: the gap between them is how long the tool took, and a
     * session that stopped between them is exactly what a dangling call looks like.
     */
    fun got(tool: String, body: String): Int = record(GOT, tool, body, both = true)

    /** The terminating question, and the terminating statement. */
    fun ask(question: String): Int = record(ASK, "question", question, both = true)
    fun conclude(text: String): Int = record(CONCLUDE, "conclusion", text, both = true)

    /** A row the agent or the app wants on the record without it being a tool. */
    fun note(name: String, detail: String): Int = record(NOTE, name, detail, both = true)

    /**
     * A row that goes only to the session.
     *
     * For the repeated `wait` poll: the request is re-sent every four minutes, and the evidence
     * keeps every one of them, while the context keeps only the first — because a context that
     * grew by a timestamped row every four minutes would never be the same request twice, and
     * the whole poll is cheap only because it is a cache hit.
     */
    fun sessionOnly(kind: String, name: String, detail: String): Int =
        record(kind, name, detail, both = false)

    private fun record(kind: String, name: String, detail: String, both: Boolean): Int {
        synchronized(guard) {
            val id = nextId()
            val line = LogFormat.row(System.currentTimeMillis(), id, kind, name, detail)
            append(session, line)
            if (both) append(context, line)
            seen = size()
            return id
        }
    }

    /**
     * The next id, checked against the file rather than trusted from memory.
     *
     * The cheap case — this instance wrote last — is one `length()` call. The expensive case is
     * one re-read, and it happens only when somebody else appended: a second `AgentLog` for the
     * same project (the box reading while a poll writes), or the agent's own clear-up having
     * rewritten the context. Reusing an id there would make two different rows share one number,
     * and every diff of the two files would be lying.
     */
    private fun nextId(): Int {
        val length = size()
        if (length != seen) {
            next = maxOf(next, highestId() + 1)
            seen = length
        }
        return next++
    }

    private fun size(): Long = try {
        if (session.isFile) session.length() else 0L
    } catch (t: Throwable) {
        0L
    }

    private fun append(file: File, line: String) {
        try {
            file.parentFile?.mkdirs()
            file.appendText(line + "\n")
        } catch (t: Throwable) {
            // A record that cannot be written is worth more than the operation it describes:
            // the turn goes on, and the person still has the app. Recorded nowhere, because
            // there is nowhere to record it — the file is the failure.
        }
    }

    // ---- reading ------------------------------------------------------------

    /** Every row of the session, oldest first. The box renders from this. */
    fun rows(): List<Row> = parse(session)

    /** The rows the agent's context holds, oldest first. This is what is sent. */
    fun contextRows(): List<Row> = parse(context)

    private fun parse(file: File): List<Row> {
        val text = try {
            if (file.isFile) file.readText() else ""
        } catch (t: Throwable) {
            ""
        }
        if (text.isEmpty()) return emptyList()
        // **The last line is only a row when it is finished.** The app appends while the box
        // reads, so a line without its newline is one being written, and half a row shown as an
        // event is showing something that did not happen. Splitting puts an empty string last
        // when the file ends in a newline and the half-written row last when it does not, so
        // dropping it is the same rule either way.
        return text.split("\n").dropLast(1).map { parseRow(it) }
    }

    // ---- opening ------------------------------------------------------------

    /**
     * Reads what is there and numbers the next row.
     *
     * **Nothing is written here.** Opening the record is something the box does to draw a
     * screen, and a screen that repaired the file while reading it would be a screen with a
     * side effect on evidence. Sealing belongs to [beginSession].
     */
    private fun load() {
        synchronized(guard) {
            val highest = maxOf(
                rows().maxOfOrNull { it.id } ?: 0,
                contextRows().maxOfOrNull { it.id } ?: 0,
            )
            next = highest + 1
            seen = size()
        }
    }

    /** The highest id either file holds, or 0 when both are empty. */
    private fun highestId(): Int = maxOf(
        rows().maxOfOrNull { it.id } ?: 0,
        contextRows().maxOfOrNull { it.id } ?: 0,
    )

    /**
     * Seals a call that was never answered, and says how many.
     *
     * **A dangling call must be sealed before a session's first request.** The app can be
     * closed, killed, or lose its network between a call and its result, and no endpoint will
     * accept a conversation whose last assistant message calls a tool with no answer: the
     * agent would meet a refusal at the very start and have no way back. The seal says what
     * happened in words, so the record explains itself rather than looking truncated.
     */
    fun beginSession(): Int {
        synchronized(guard) {
            val pending = dangling(rows())
            // **The seal follows the call into the file the call is in.** After a reset the
            // evidence still holds a call the context no longer has, and sealing it into the
            // context would put a tool result there with no call above it — a request every
            // endpoint refuses, produced by the app's own repair.
            val inContext = dangling(contextRows()).map { it.id }.toSet()
            for (call in pending) {
                val said = "$FAILURE the app was closed before this answered"
                if (call.id in inContext) got(call.name, said) else sessionOnly(GOT, call.name, said)
            }
            return pending.size
        }
    }

    /**
     * The calls in [rows] with no answer after them.
     *
     * **A terminating call is never dangling.** `ask`, `conclude` and `wait` end the turn, so
     * they have no result by design — the conversation ends there rather than waiting for one.
     * Sealing them would put a `1 the app was closed` after a question the person may be
     * answering right now, which is both wrong and confusing to read.
     */
    fun dangling(rows: List<Row>): List<Row> {
        val out = mutableListOf<Row>()
        var pending: Row? = null
        for (row in rows) {
            when (row.kind) {
                CALL -> {
                    if (row.name in TERMINATING) {
                        pending = null
                    } else {
                        pending?.let { out.add(it) }
                        pending = row
                    }
                }

                GOT -> pending = null
            }
        }
        pending?.let { out.add(it) }
        return out
    }


    /**
     * Truncates the session at a burst boundary, keeping the newest conversations that fit.
     *
     * **The limit is bytes, and the choice is made in bytes.** Keeping "half the limit" of rows
     * rather than of bytes made the number mean nothing — a 4 MB limit asked for two million
     * rows, which is every row this app will ever write, so the trim would never have fired.
     * A row's length is its own line, which is exactly what the limit measures.
     *
     * Half the limit is the target rather than all of it, so the trim is not immediately undone
     * by the rows the next turn writes — the trace's own rule, reused rather than reinvented.
     */
    fun trimSession(limit: Long = SESSION_LIMIT): Int {
        synchronized(guard) {
            val size = try {
                if (session.isFile) session.length() else 0L
            } catch (t: Throwable) {
                0L
            }
            if (size <= limit) return 0
            val rows = rows()
            if (rows.isEmpty()) return 0
            // **The budget is named, not implied.** A trailing lambda binds to the last
            // parameter, so `keepNewest(rows) { … }` left the budget at its default and the trim
            // dropped nothing at all — the same "a limit that does nothing" as before, one
            // parameter to the left.
            val (kept, dropped) =
                keepNewest(rows, budget = limit / 2) { row -> row.line.length.toLong() + 1 }
            if (dropped == 0) return 0
            val marker = LogFormat.row(
                System.currentTimeMillis(), nextId(), NOTE, "trimmed",
                "$dropped rows from before ${kept.firstOrNull()?.time ?: "now"}",
            )
            try {
                val text = (listOf(marker) + kept.map { it.line }).joinToString("\n") + "\n"
                // **Written beside the file and renamed over it.** The box reads this file
                // while the app appends to it, so a trim that truncated in place would show a
                // reader a log that starts correctly and ends in the middle of a row.
                val next = File(session.parentFile, session.name + ".new")
                next.writeText(text)
                if (!next.renameTo(session)) {
                    session.writeText(text)
                    next.delete()
                }
            } catch (t: Throwable) {
                return 0
            }
            seen = size()
            return dropped
        }
    }
}
