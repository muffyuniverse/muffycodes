package com.example.notes

import java.io.File

/**
 * The agent's record: **one file, one row per event, one id, and a column that says whether the
 * model is sent it.**
 *
 * `agent/session.log` holds every event in order, and the app truncates it at a generous size.
 * Each row's `ctx` column is `in` (the model is sent it), `out` (the agent decided it no longer
 * matters) or `app` (it is the app's own bookkeeping, or a reset took it). The context is not a
 * second file: it is the `in` rows, in order, and that is what a request is built from.
 *
 * **Three states instead of two files is what makes the audit exact.** The old design kept the
 * context as a second file and called the difference between them the agent's judgement — which
 * needed exceptions for every row the app wrote to only one of the two. Here the judgement is
 * written down at the moment it is made, so "what did he decide to forget" is a column value and
 * not a subtraction; and because a forget names ids rather than rewriting text, compaction cannot
 * alter a single character of the evidence.
 *
 * The columns are [LogFormat]'s — the trace's own — because the record is read by the same habits:
 * `grep`, a shell, an eye on a phone.
 */
class AgentLog private constructor(
    /**
     * **The record, and the only record.** It used to be two files — the evidence and the working
     * context — with the context a materialised copy of "the rows that are in". A `ctx` column
     * says the same thing in the row itself, so there is one truth instead of two files that had
     * to be kept in step, and removing a row is a change to four characters rather than a rewrite
     * of the whole conversation in text.
     */
    val session: File,
) {

    /**
     * The lock this record's file is protected by — **shared by every instance over the file.**
     *
     * A per-instance lock excludes nothing that matters: two `AgentLog` objects over one project
     * can both read the file, both see it unchanged since they opened, and both take the same next
     * id — so two different rows would share a number, and forgetting one of them would remove the
     * wrong row.
     * Two writers over one project is the app's normal state — the screen's worker resetting a
     * context while the runner's worker appends a poll's rows — so the lock belongs to the file.
     */
    private val file: File get() = session

    private val guard: Any get() = lockFor(file)

    /** The id the next row gets. **One file, so one sequence** — a row's id means one row. */
    private var next = 1

    /**
     * The session's length after this instance's own last append; `-1` until it writes.
     *
     * **A counter in memory goes stale the moment another writer touches the file**, and two rows
     * with one id is the one thing that breaks this record: an id is how a row is taken out of the
     * context, so an id that meant two rows would forget the wrong one. [nextId] compares the file
     * against what this instance left and re-reads the highest id when they differ.
     */
    private var seen = -1L

    companion object {

        /** The folder name under a project: `agent/`. */
        const val DIR = "agent"
        const val SESSION = "session.log"

        /**
         * The old second file's name, kept because a record written by an older build has one.
         *
         * Nothing reads it. It is named here so [open] can fold it in once and delete it, rather
         * than leaving a file that says it is the context while nothing keeps it in step.
         */
        const val CONTEXT = "context.log"

        /** In the context: the rows the model is sent, in this order. */
        const val IN = "in"

        /** Out of the context, **because the agent decided so** — what [thrownAway] reports. */
        const val OUT = "out"

        /**
         * Out of the context because the app put it there: a poll's repeats, a turn boundary, a
         * trim, a reset. Bookkeeping rather than judgement, and the distinction the audit rests on.
         */
        const val APP = "app"

        /**
         * Generous, because this file keeps the repeated `wait` rows too.
         *
         * The context is trimmed by the agent to what it needs; the session is the evidence, and
         * an agent that polls every four minutes writes fifteen rows an hour — so the session's
         * limit has to be the sort of size that holds days, not the trace's 512 KB.
         */
        /**
         * How big `session.log` may get before its oldest turns go — **four megabytes, and only
         * when somebody asks for a number at all.**
         *
         * **The evidence's own horizon, and the only place rows leave for good.** Clearing up takes
         * a row out of the conversation and leaves it in the file; this drops it. It is therefore a
         * setting rather than a policy ([SettingsStore.sessionMb], zero by default), and this
         * constant is what a caller that names no number gets.
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
        val TERMINATING = setOf("ask", "conclude")

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
         * `14:02:11.410    8 CALL  tree      {"path":"notes"}`
         *
         * The body is the only part that can hold a separator, and [LogFormat] escapes it — so
         * two spaces followed by anything is the detail, always.
         */
        /**
         * Five columns: the record as this build writes it — `id size kind ctx name  detail`.
         *
         * **` *` and not `^` before the id**, because the id is padded to four and the size to five:
         * the first character of a row is a space. A row of yesterday starts with `14:02`, so the
         * two shapes are still disjoint by their first character.
         */
        private val ROW =
            Regex("""^ *(\d+) +(\d+) +(\S+) +(\S+) +(\S+)(?:  (.*))?$""")

        /** Six columns: the record as the build before this one wrote it, with a clock. */
        private val TIMED_ROW =
            Regex("""^(\d\d:\d\d:\d\d\.\d\d\d) +(\d+) +(\S+) +(\S+) +(\S+)(?:  (.*))?$""")

        /**
         * Five columns: the row an older build wrote, before `ctx` existed.
         *
         * **Read, not rejected.** An update that treated every row of yesterday's record as
         * unreadable would lose every session's memory on the phone it was installed on — and it
         * would do it quietly, because a broken row is drawn rather than dropped. So the old shape
         * parses, its ctx is left unknown, and [foldLegacy] resolves it from the old second file.
         */
        private val LEGACY_ROW =
            Regex("""^(\d\d:\d\d:\d\d\.\d\d\d) +(\d+) +(\S+) +(\S+)  (.*)$|^(\d\d:\d\d:\d\d\.\d\d\d) +(\d+) +(\S+) +(\S+)$""")

        /** A row from before the column existed, waiting to be told what it was. */
        const val UNKNOWN = ""

        /** True when [line] is a row in the shape this build writes, so it needs no re-rendering. */
        fun isCurrentShape(line: String): Boolean = ROW.matches(line)

        /**
         * One lock per record file, for the whole process.
         *
         * Keyed by the file rather than by the project so two `AgentLog`s from two threads share
         * it — and never removed, because a lock that is dropped while somebody holds it is worse
         * than a map that keeps a few strings.
         */
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()

        private fun lockFor(session: File): Any =
            locks.computeIfAbsent(session.absolutePath) { Any() }

        /** Opens the record of the project in [projectDir], making the folder on first use. */
        fun open(projectDir: File): AgentLog {
            val dir = File(projectDir, DIR)
            dir.mkdirs()
            val log = AgentLog(File(dir, SESSION))
            log.foldLegacy()
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
            val timed = if (match == null) TIMED_ROW.matchEntire(line) else null
            val legacy = if (match == null && timed == null) LEGACY_ROW.matchEntire(line) else null
            when {
                match != null -> Row(
                    id = match.groupValues[1].toInt(),
                    size = match.groupValues[2].toLong(),
                    kind = match.groupValues[3],
                    ctx = match.groupValues[4],
                    name = match.groupValues[5],
                    detail = match.groupValues[6],
                    raw = line,
                )

                // **This one's size is measured, because the file does not hold it.** A row from the
                // build with a clock is re-rendered in the new shape by [foldLegacy], so the number
                // is only needed between the parse and that rewrite.
                timed != null -> Row(
                    id = timed.groupValues[2].toInt(),
                    size = ContextBudget.tokens(LogFormat.unescaped(timed.groupValues[6])),
                    kind = timed.groupValues[3],
                    ctx = timed.groupValues[4],
                    name = timed.groupValues[5],
                    detail = timed.groupValues[6],
                    raw = line,
                )

                legacy != null -> {
                    // Either shape of the old row: with a body, or ending at the name.
                    val hasBody = legacy.groupValues[1].isNotEmpty()
                    val at = if (hasBody) 0 else 5
                    val detail = if (hasBody) legacy.groupValues[5] else ""
                    Row(
                        id = legacy.groupValues[at + 2].toInt(),
                        size = ContextBudget.tokens(LogFormat.unescaped(detail)),
                        kind = legacy.groupValues[at + 3],
                        ctx = UNKNOWN,
                        name = legacy.groupValues[at + 4],
                        detail = detail,
                        raw = line,
                    )
                }

                else -> Row(0, 0, "?", "?", "?", line, broken = true)
            }
        } catch (t: Throwable) {
            Row(0, 0, "?", "?", "?", line, broken = true)
        }

          /**
           * Why [ids] is not something the agent may forget, or null when it may.
           *
           * **By id, not by text.** The agent names the rows it has decided it no longer needs, and
           * this checks the three things that keep a context a conversation an endpoint accepts:
           *
           * 1. every id is **in** the context — an id it kept is not a removal, and an id already
           *    out is not one either, so a stale list is told so rather than silently doing half;
           * 2. the newest task stays: it is what the turn is for;
           * 3. a call and its result go together, because a call with no answer is a request every
           *    endpoint refuses and an answer with no call is a message it cannot place.
           *
           * **There is no fourth rule, and that is the point.** The old check compared the file's
           * lines before and after to prove the edit was removal only — a comparison that needed
           * escaping to round-trip, a newline rule, and an exception for the call in flight. A
           * forget carries no text at all, so the only thing it can change is the ctx column of the
           * rows it names. Text cannot be rewritten because text is not offered.
           */
          fun forgetProblem(rows: List<Row>, ids: Set<Int>): String? {
              if (ids.isEmpty()) return "Name at least one row to forget"
              val inContext = rows.filter { it.ctx == IN }
              if (inContext.isEmpty()) return "Your context is already empty"
              val named = inContext.filter { it.id in ids }
              val unknown = ids - named.map { it.id }.toSet()
              if (unknown.isNotEmpty()) {
                  val out = rows.filter { it.id in unknown }
                  return if (out.isEmpty()) {
                      "There is no row ${unknown.sorted().joinToString(", ")}"
                  } else {
                      "Row ${out.map { it.id }.sorted().joinToString(", ")} is not in the context"
                  }
              }
              val task = inContext.lastOrNull { it.kind == TOLD }
              if (task != null && task.id in ids) {
                  return "The newest task stays: it is what you are working on"
              }
              var open: Row? = null
              for (row in inContext) {
                  when (row.kind) {
                      CALL -> {
                          val left = open
                          if (left != null && left.id in ids) {
                              return "The call to ${left.name} has no result left: forget the pair"
                          }
                          open = row
                      }

                      GOT -> {
                          val call = open
                          open = null
                          if (call != null && (call.id in ids) != (row.id in ids)) {
                              return "The call to ${call.name} and its result go together"
                          }
                      }
                  }
              }
              // An unanswered call at the end is the one being made now: its result is written the
              // moment this returns, so forgetting it would leave the conversation illegal.
              val now = open
              if (now != null && now.id in ids) return "That is the call you are making now"
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

    /**
     * One row of the record.
     *
     * `size` is the row's cost in **tokens**, and it is stored rather than remembered: the agent
     * chooses what to forget by it, the app measures a context by summing it, and the person's
     * transcript shows it beside the row. Three readers, one number, written once.
     *
     * It is measured on the body **as the record holds it** — escaped, so a newline costs the two
     * characters the file spends on it rather than the one a request spends. That errs by a
     * character per line break, in the direction that asks the agent to clear up early, and it is
     * the only version of the number that `wc -c` on the file can confirm.
     */
    data class Row(
        val id: Int,
        val size: Long,
        val kind: String,
        /** `in`, `out` or `app` — whether the model is sent this row, and whose decision that was. */
        val ctx: String,
        val name: String,
        val detail: String,
        /** The line exactly as the file holds it, so a rewrite never has to re-render one. */
        val raw: String = "",
        /** True for a line that did not parse: shown as a row that could not be read. */
        val broken: Boolean = false,
    ) {
        /** An operator's message begins a conversation, and a terminating tool ends it. */
        val startsBurst: Boolean get() = kind == TOLD
        val endsBurst: Boolean get() = kind == ASK || kind == CONCLUDE

        /**
         * The row as it stands in the file — **verbatim**.
         *
         * A row that came out of the file is written back as the same characters, so a body
         * containing a backslash cannot drift: there is no escaping step on this path at all. A row
         * the app built in memory has no raw line yet, so it is rendered once, here.
         */
        val line: String
            get() = if (broken) detail else raw.ifEmpty {
                LogFormat.recordRow(id, size, kind, ctx, name, detail)
            }

        /** This row with its ctx column changed, and every other byte the same. */
        fun flipped(to: String): Row = if (ctx == to) {
            this
        } else {
            copy(ctx = to, raw = if (raw.isEmpty()) "" else LogFormat.flipCtx(raw, to))
        }

        /** The body as the agent wrote it, with its newlines back. */
        val body: String get() = LogFormat.unescaped(detail)

        /**
         * The row as **the model** reads it: `id name  body`.
         *
         * The columns a row carries serve four audiences: `kind` is how the app rebuilds a
         * conversation, `ctx` is the audit's own column, `size` is the measurement the app and the
         * person act on, and the padding is for an eye on a file. Only the id and the words are the
         * model's — and the size is **not**, because two numbers side by side in a record it is
         * about to delete from is a question it should not have to answer twice. `17 412 CALL read`
         * invites it to forget row 412.
         *
         * What it needs to choose with is in front of it: the clear-up line names the rows taking the
         * most room, and the transcript of its own conversation is what it reads. The size is a
         * handle for the app's line, not for its arguments.
         */
        val shown: String
            get() = buildString {
                append(id.toString().padStart(4))
                append(' ')
                append(name)
                if (detail.isNotEmpty()) {
                    append("  ")
                    append(detail)
                }
            }
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
     * A marker row goes into the record — `app`, so the model is not sent it — so the person
     * reading the evidence still sees what happened, while the model finds a context that is
     * smaller without being handed an app-authored sentence about it. The marker's name is not one
     * [Agent.messages] renders.
     */
    fun trimContext(budget: Long): Int {
        synchronized(guard) {
            val all = rows()
            val inContext = all.filter { it.ctx == IN }
            if (inContext.isEmpty() || budget <= 0L) return 0
            // **One messagebox at a time** — the operator's own words for the autotrim: "yes,
            // autotrim is one messagebox at a time". Dropping as many bursts as half the limit
            // would allow removes a conversation the agent might still be working from, all at
            // once, and gives him no chance to have cleared it himself; one burst per request
            // keeps this the app's last resort rather than a compaction of its own.
            val oldest = bursts(inContext).firstOrNull() ?: return 0
            // Nothing to drop, or nothing that may be: a context that is one conversation is a
            // conversation, and cutting it would leave a task with no task.
            if (oldest.size >= inContext.size) return 0
            // **What this trim did, not a running total.** Markers are the app's own rows and stay
            // in the record, so a total maintained by reading the previous marker home would count
            // those rows again on every trim. Each row reports its own act; the total is the sum,
            // computed wherever it is wanted.
            val said = "${oldest.size} rows: the context was over its limit"
            val marker = LogFormat.recordRow(nextId(), ContextBudget.tokens(said), NOTE, APP, "trimmed", said)
            // **Taken out, not taken away.** The rows the app trims stay in the record, marked
            // `app` rather than `out` — the app's doing, not the agent's judgement — so a trim can
            // never be read as something the model decided. And the marker is `app` too, so it does
            // not count against the limit it is reporting on.
            val ids = oldest.map { it.id }.toSet()
            // **Every row, not just the context's.** The file is the record: rewriting it from the
            // `in` rows alone would delete everything the agent forgot and everything the app had
            // already taken out — the audit and the evidence, in one silent step.
            val kept = all.map { if (it.id in ids) it.flipped(APP) else it }
            try {
                store(kept, marker)
            } catch (t: Throwable) {
                return 0
            }
            seen = size()
            return oldest.size
        }
    }

    // ---- the person's own operation ------------------------------------------

    /**
     * Empties the working context, and keeps everything that happened.
     *
     * **The person's operation, not the agent's.** The agent may forget rows but never the newest
     * task — that is what [forgetProblem] checks — because a context it can empty is a context it
     * can quietly restart from. A reset is the person saying "the conversation starts from here",
     * which is a different act, so it is a different door rather than a relaxation of his.
     *
     * Nothing is deleted: every row that was `in` becomes `app`, which is the truth — the app took
     * it out, the agent did not judge it. So [thrownAway] still reports only what the model chose
     * to drop, and a reset can never be misread as a decision.
     *
     * The marker's name is not one [Agent.messages] renders, so the model is not handed an
     * app-authored sentence: after a reset the next request is the instructions and the new task,
     * and nothing else.
     */
    fun reset(): Int = synchronized(guard) {
        val id = nextId()
        val said = "the working context was cleared"
        val marker = LogFormat.recordRow(id, ContextBudget.tokens(said), NOTE, APP, "reset", said)
        // **Marked, not erased.** Every row that was in stays in the record and reads `app`: the
        // person's act is not the agent's judgement, and it is not a way to make history disappear
        // either. After this the next request is the instructions and the new task.
        val marked = rows().map { if (it.ctx == IN) it.flipped(APP) else it }
        store(marked, marker)
        seen = size()
        id
    }

    /**
     * Brings a record written by an older build into this one, once.
     *
     * Two things changed, and both are about not losing what is already on the phone:
     *
     * 1. **The row gained a column.** An old row parses with its `ctx` unknown; the old
     *    `context.log` says which rows were in it, and a row it does not name was out because the
     *    app had trimmed it or the agent had cleared it — `app` is the honest answer, since the old
     *    file cannot tell those apart. With no old context file, every old row is `in`: before the
     *    column existed, both files held the same rows.
     * 2. **The second file is deleted.** Once folded, nothing keeps it in step, and a file that
     *    claims to be the context while nothing writes it is a lie waiting to be believed.
     *
     * 3. **The row lost its clock and gained a size**, so a row from the build before this one is
     *    re-rendered in the shape this build writes — and its size is measured from its body, which
     *    is the number the agent will choose by from now on.
     *
     * Rows are re-rendered, not flipped: an old row's line has no ctx column to flip.
     *
     * **"Is it old" is asked of the line, not remembered.** A row is rewritten when its ctx is
     * unknown *or* when the line it came from is not the shape this build writes — so a record left
     * over from either earlier build is brought forward by the same test, and a row that is already
     * current is never touched.
     */
    private fun foldLegacy() {
        val old = File(file.parentFile, CONTEXT)
        try {
            val rows = rows()
            // **A line that did not parse is left exactly as it is.** It is the evidence of
            // something the app could not read, and re-rendering it would be the app replacing
            // evidence with its own guess — and it would make every open rewrite the file, because
            // such a line is never in the current shape.
            if (rows.none { !it.broken && (it.ctx == UNKNOWN || !isCurrentShape(it.raw)) } &&
                !old.isFile
            ) {
                return
            }
            val inContext = if (old.isFile) {
                old.readText().split("\n").filter { it.isNotBlank() }.mapNotNull { line ->
                    // The old context held the same rows in the same columns, so the id is the
                    // second field — read positionally, because these lines are the format before
                    // this one and re-parsing them as the new shape is what would misread them.
                    line.trim().split(Regex(" +")).getOrNull(1)?.toIntOrNull()
                }.toSet()
            } else {
                null
            }
            val marked = rows.map { row ->
                // `raw` is dropped for exactly the rows that are not in this build's shape, so the
                // line is rendered again — with the column it gained, and the size it gained.
                val ctx = if (row.ctx == UNKNOWN) {
                    when {
                        inContext == null -> IN
                        row.id in inContext -> IN
                        else -> APP
                    }
                } else {
                    row.ctx
                }
                when {
                    row.broken -> row
                    isCurrentShape(row.raw) -> row.copy(ctx = ctx)
                    else -> row.copy(ctx = ctx, raw = "")
                }
            }
            // No marker: folding rewrites the rows it already has, and adds nothing.
            rewrite(marked.asReversed().map { it.line })
            if (old.isFile) old.delete()
            seen = size()
        } catch (t: Throwable) {
            // A record that cannot be folded is left exactly as it is, and the old file is not
            // deleted — so nothing is lost, and the next open tries again.
        }
    }

    // ---- writing ------------------------------------------------------------

    /** The operator's words, in the context: this is what a turn is for. */
    fun told(text: String): Int = record(TOLD, "operator", text)

    /** A tool the agent called: the name, and its arguments as the model wrote them. */
    fun call(tool: String, args: String): Int = record(CALL, tool, args)

    /**
     * The answer to a call. Its first line is `0` or `1`, always.
     *
     * Written as its own row under the tool's name rather than folded into the call, because the
     * two happen at different times: the gap between them is how long the tool took, and a
     * session that stopped between them is exactly what a dangling call looks like.
     */
    fun got(tool: String, body: String): Int = record(GOT, tool, body)

    /** The terminating question, and the terminating statement. */
    fun ask(question: String): Int = record(ASK, "question", question)
    fun conclude(text: String): Int = record(CONCLUDE, "conclusion", text)

    /** A row the agent or the app wants on the record without it being a tool. */
    fun note(name: String, detail: String): Int = record(NOTE, name, detail)

    /**
     * A row the app writes for itself, and that the model is never sent.
     *
     * For the repeated `wait` poll above all: the request is re-sent every four minutes, and the
     * record keeps every one of them, while the context keeps only the first — because a context
     * that grew by a timestamped row every four minutes would never be the same request twice, and
     * the whole poll is cheap only because it is a cache hit. The turn boundary and the reconnect
     * line are here for the same reason: they are how the *app* counts, not what the model said.
     */
    fun bookkeeping(kind: String, name: String, detail: String): Int =
        record(kind, name, detail, ctx = APP)

    private fun record(
        kind: String,
        name: String,
        detail: String,
        ctx: String = IN,
    ): Int {
        synchronized(guard) {
            val id = nextId()
            // **Measured once, here, and never again.** The body is what a request carries, so the
            // cost of the body is what this row costs — not the formatted line, whose padding the
            // model never sees.
            val line = LogFormat.recordRow(id, ContextBudget.tokens(detail), kind, ctx, name, detail)
            prepend(line)
            seen = size()
            return id
        }
    }

    /**
     * Takes rows out of the context, by id — **the agent's compaction, and the whole of it.**
     *
     * One call, one atomic rewrite: the named rows get `out` in their ctx column and nothing else
     * in the file moves. The caller has already asked [forgetProblem] whether this list is legal,
     * so this is the doing rather than the deciding. Returns how many rows changed.
     */
    fun forget(ids: Set<Int>): Int = synchronized(guard) {
        val rows = rows()
        if (forgetProblem(rows, ids) != null) return 0
        val said = ids.sorted().joinToString(",")
        val marker = LogFormat.recordRow(nextId(), ContextBudget.tokens(said), NOTE, APP, "forgot", said)
        val changed = rows.map { if (it.id in ids) it.flipped(OUT) else it }
        store(changed, marker)
        seen = size()
        ids.size
    }

    /**
     * Writes the whole record, beside it and then over it.
     *
     * **Beside and renamed, as everything else here is.** A rewrite in place that stopped halfway
     * would leave a record whose last row is half a row — and this file is the evidence, so a
     * reader must see either the old one whole or the new one whole.
     */
    /**
     * Puts [rows] on disk with [marker] as the newest, **newest first**.
     *
     * **The order is the whole point of the file's shape.** A record is read by something with a
     * limit: a `read` of it, an eye on a phone, a model with a context that is already full. The
     * newest rows are the ones that matter for every one of those, and newest-first means the first
     * page is the useful one — where oldest-first put the whole conversation between a reader and
     * what just happened.
     *
     * [rows] is oldest-first, the order every caller in this class thinks in. The reversal is here
     * and nowhere else, so a new writer cannot get it wrong in one place while getting it right in
     * another.
     */
    private fun store(rows: List<Row>, marker: String) =
        rewrite(listOf(marker) + rows.asReversed().map { it.line })

    /**
     * Writes the file, lines already in the order the file keeps them.
     *
     * Called by [store] for a whole rewrite, and by [append] for one new row at the front.
     */
    private fun rewrite(lines: List<String>) =
        Beside.write(file, lines.joinToString("\n") + "\n")

    /**
     * The next id, checked against the file rather than trusted from memory.
     *
     * The cheap case — this instance wrote last — is one `length()` call. The expensive case is
     * one re-read, and it happens only when somebody else appended: a second `AgentLog` for the
     * same project (the box reading while a poll writes), or the agent's own `forget` having
     * rewritten the file. Reusing an id there would make two different rows share one number — and
     * an id is the handle `forget` takes, so that would be a compaction that removes the wrong row.
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
        if (file.isFile) file.length() else 0L
    } catch (t: Throwable) {
        0L
    }

    /**
     * Puts [line] at the top of the file — the newest row.
     *
     * **A rewrite, not an append**, because the file's newest row is its first — and the price of
     * that is one rewrite of the file per row. It is bounded by the session cap
     * ([SettingsStore.sessionMb]), which is why a person who keeps the record whole is choosing to
     * pay it: a few milliseconds for a file of tens of kilobytes, and the first page of every read
     * is the part that just happened.
     */
    private fun prepend(line: String) {
        try {
            file.parentFile?.mkdirs()
            val old = if (file.isFile) file.readText() else ""
            Beside.write(file, line + "\n" + old)
        } catch (t: Throwable) {
            // A record that cannot be written is worth more than the operation it describes:
            // the turn goes on, and the person still has the app. Recorded nowhere, because
            // there is nowhere to record it — the file is the failure.
        }
    }

    // ---- reading ------------------------------------------------------------

    /**
     * Every row of the record, **oldest first** — the opposite of the file.
     *
     * The file keeps its newest row at the top, because a reader with a limit (a `read`, a person's
     * eye, a model with a full context) wants what just happened on the first page. Everything that
     * reasons about the conversation thinks oldest-first, so the reversal happens once, in [parse],
     * and no caller has to know which way round the file is.
     */
    fun rows(): List<Row> = parse(file)

    /**
     * The rows the model is sent, in order — **the rows whose ctx is `in`.**
     *
     * A filter, not a second file. Every row is written once, and being in or out of the context is
     * a property of the row: that is what makes the audit exact instead of a reconciliation.
     */
    fun contextRows(): List<Row> = rows().filter { it.ctx == IN }

    /**
     * **What the agent decided did not matter.**
     *
     * Compaction is normally the one part of an agent's memory nobody can check: a summary is
     * written and its source is gone, so "I kept what mattered" can be neither proved nor
     * disproved. Here nothing is summarised and nothing is deleted — a forget marks rows `out`,
     * and the rows stay on the record with their text — so this returns the judgement itself, in
     * the order it was made, and a row that was dropped by mistake can still be read afterwards.
     *
     * Nothing has to be excused from it. The app's own rows are `app` from birth, a trim marks
     * `app`, and a reset marks `app`: the three things that used to look like the agent's judgement
     * are now impossible to confuse with it, because the column says whose act put the row there.
     */
    fun thrownAway(): List<Row> = synchronized(guard) {
        // **One comparison, and no exceptions.** A row is `out` because the agent said so; the
        // app's own rows are `app`, and the person's reset marks the rows it took the same way. So
        // what the agent decided does not matter is exactly the `out` rows — no diff, no
        // subtraction, no marker to excuse, and nothing to misread after a reset.
        rows().filter { it.ctx == OUT }
    }

    private fun parse(file: File): List<Row> {
        val text = try {
            if (file.isFile) file.readText() else ""
        } catch (t: Throwable) {
            ""
        }
        if (text.isEmpty()) return emptyList()
        // **The last line is only a row when it is finished.** A file written by an older build was
        // appended to while the box read it, so a line without its newline is one being written, and
        // half a row shown as an event is showing something that did not happen. Splitting puts an
        // empty string last when the file ends in a newline and the half-written row last when it
        // does not, so dropping it is the same rule either way.
        //
        // **Then back to oldest-first**, which is the order every reader above thinks in. The file
        // keeps its newest row at the top; nothing outside this function has to know that.
        return text.split("\n").dropLast(1).map { parseRow(it) }.asReversed()
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
            next = highestId() + 1
            seen = size()
        }
    }

    /**
     * The highest id the record holds, or 0 when it is empty.
     *
     * **One file, one maximum.** This used to take the larger of two files' highest ids, which was
     * the right question while the context was a second file that could be shorter than the
     * evidence. The context is a filter over the one file now, so its maximum is the same number —
     * and asking twice is how a reader comes to believe there are two.
     */
    private fun highestId(): Int = rows().maxOfOrNull { it.id } ?: 0

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
            // **The seal goes where its call is.** After a reset the call is still in the record
            // and marked `app`, so its result is written `app` too: a result in the context with no
            // call above it is a request every endpoint refuses, produced by the app's own repair.
            val inContext = dangling(contextRows()).map { it.id }.toSet()
            for (call in pending) {
                val said = "$FAILURE the app was closed before this answered"
                if (call.id in inContext) {
                    got(call.name, said)
                } else {
                    bookkeeping(GOT, call.name, said)
                }
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
        // **Zero is the default and it means "keep everything".** The evidence is the only copy of
        // the conversation that outlives the process, and deleting the oldest turns is the
        // operator's decision, not the app's.
        if (limit <= 0L) return 0
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
                // Bytes of the line, because this cap is about the **file on disk** — it is a
                // storage budget, not a context one, and a file has bytes.
                keepNewest(rows, budget = limit / 2) { row -> row.line.length.toLong() + 1 }
            if (dropped == 0) return 0
            // **The id, not a clock.** The row this trim stopped above is named by the handle the
            // record already uses, so the sentence is still checkable in the file.
            val said = "$dropped rows older than #${kept.firstOrNull()?.id ?: 0}"
            val marker = LogFormat.recordRow(nextId(), ContextBudget.tokens(said), NOTE, APP, "trimmed", said)
            try {
                // The one place rows leave the record for good: this is the evidence's own limit,
                // and the reason the audit has a horizon rather than a history.
                store(kept, marker)
            } catch (t: Throwable) {
                return 0
            }
            seen = size()
            return dropped
        }
    }
}
