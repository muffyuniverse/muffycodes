package com.example.notes

import java.io.File

/**
 * The agent's record: **two files, one row per event, one id.**
 *
 * `agent/session.log` is the evidence — every event, in the order it happened, appended at the
 * end. Its `ctx` column is `in` (the model is sent it), `out` (the agent decided it no longer
 * matters) or `app` (the app's own bookkeeping, or a reset took it). Every request is built from
 * these rows: the context is the `in` rows, in order.
 *
 * `agent/context.log` is the same rows the model is sent, and only those, as `id size name body` —
 * no `kind`, because the app rebuilds the conversation from `session.log`, and no `ctx`, because
 * everything in that file is in. It is appended to as a row enters the context and rewritten as
 * one leaves, so what the agent reads when it looks at its own memory is one small file rather
 * than a filter it has to apply to a large one.
 *
 * **The app writes the context file and never reads it.** It answers "what is in the context"
 * once, from the `ctx` column, and writes the second file from that answer. Two files asked the
 * same question is how they come to disagree.
 *
 * **Being in or out is a column, not a subtraction.** The difference between the two files *is*
 * the agent's judgement, and it can be read straight off the column: a forget marks rows `out`,
 * the app marks its own rows and whatever it trims `app`, so "what did it decide to forget" is
 * never confused with what the app did. And because a forget names ids rather than rewriting text,
 * compaction cannot alter a character of the evidence.
 *
 * The columns are [LogFormat]'s — the trace's own — because the record is read by the same habits:
 * `grep`, a shell, an eye on a phone.
 */
class AgentLog private constructor(
    /** The evidence: every event, once, in the order it happened. */
    val session: File,
    /**
     * The context: the rows the model is sent, and only those, without `kind` or `ctx`.
     *
     * Not a second source of truth. It is written from [session] on every whole-record write, so
     * it cannot say what the record does not, and the app never reads it back.
     */
    val context: File,
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

    private val guard: Any get() = gateFor(file)

    /** The file's numbers: the lock, and the id sequence that goes with it. */
    private val gate: Gate get() = gateFor(file)

    /**
     * The sequence and the file length it was worked out from — **one of these per file.**
     *
     * Held in the process's map rather than in an instance because the file has one sequence, and
     * because the numbers and the lock that protects them should not be two different things.
     */
    private class Gate {
        /** The id the next row gets; `0` until the file has been read. */
        var next: Int = 0

        /** The session's length when [next] was last checked against the file. */
        var seen: Long = -1L
    }

    companion object {

        /** The folder name under a project: `agent/`. */
        const val DIR = "agent"
        const val SESSION = "session.log"

        /**
         * The context file: the rows the model is sent, as `id size name  body`.
         *
         * Written by every whole-record write, and by each row that enters the context. When it is
         * missing — somebody deleted it, or the app was interrupted between two writes — [open]
         * writes it from the evidence, so the model is never shown a memory that is not there.
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
         * Five columns: `   8   142 GOT     in  read    0 · two litres`.
         *
         * The body is the only part that can hold a separator, and [LogFormat] escapes it — so
         * two spaces followed by anything is the detail, always.
         *
         * **` *` and not `^` before the id**, because the id is padded to four and the size to five:
         * the first character of a row is a space.
         */
        /**
         * A record row: `time id size kind ctx answers name  detail`.
         *
         * **The stamp is `\S+` and kept as text.** It is carried back out verbatim on a rewrite, so
         * parsing it into a number and formatting it again would be two chances to change it. A row
         * that cannot be parsed is a row this reader says it could not read, rather than one it
         * guesses at.
         */
        private val ROW =
            Regex("""^(\S+) +(\d+) +(\d+) +(\S+) +(\S+) +(\S+) +(\S+)(?:  (.*))?$""")

        /**
         * The one gate per record file, for the whole process — **and the id sequence with it.**
         *
         * Keyed by the file rather than by the project so two `AgentLog`s from two threads share
         * it — and never removed, because a lock that is dropped while somebody holds it is worse
         * than a map that keeps a few strings.
         *
         * **The numbers live here rather than in an instance, because the file has one sequence.**
         * A counter per instance is a counter per reader: two of them over one file each believe
         * they are the one that wrote last, and both hand out the same id. Keeping [Gate.next] and
         * [Gate.seen] behind the file's own lock means there is one answer to "what is the next id"
         * for everybody writing that file.
         */
        private val gates = java.util.concurrent.ConcurrentHashMap<String, Gate>()

        private fun gateFor(session: File): Gate =
            gates.computeIfAbsent(session.absolutePath) { Gate() }

        /**
         * Opens the record of the project in [projectDir], **without making the project.**
         *
         * `agent/` is made when it is written to ([append] makes its parent), so opening a record
         * has no reason to make anything. It used to `mkdirs()` the whole path, which meant **any
         * reader recreated a project folder the person had deleted** — the chat box reading a
         * conversation, a tick about to notice the folder was gone, a background result being
         * filed. A deleted project that reappears as an empty folder is a project the screen then
         * offers a conversation for, which is exactly the state `AgentRunner.tick` exists to
         * remove. Reported as an intermittent test failure: "the folder is really gone before the
         * tick", where the folder had been recreated between the delete and the assert.
         */
        fun open(projectDir: File): AgentLog {
            val dir = File(projectDir, DIR)
            val log = AgentLog(File(dir, SESSION), File(dir, CONTEXT))
            // **Read and sized under one hold of the lock.** Parsing outside it and then asking the
            // file's length inside is the race this class exists to prevent: another writer appends
            // between the two, and this instance adopts a length that describes rows it never saw —
            // so it thinks the file is unchanged and mints an id that is already taken.
            synchronized(log.guard) {
                val rows = log.parse(log.session)
                log.adopt(rows)
                // **The context file is written when it is absent, and never compared** — but only
                // where there is a record to write it from. A project whose folder is gone has no
                // rows, and making the folder to write an empty file into it is a reader creating
                // the project it was reading.
                if (!log.context.isFile && rows.isNotEmpty()) log.writeContext(rows)
            }
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
            if (match != null) {
                Row(
                    stamp = match.groupValues[1],
                    id = match.groupValues[2].toInt(),
                    size = match.groupValues[3].toLong(),
                    kind = match.groupValues[4],
                    ctx = match.groupValues[5],
                    answers = match.groupValues[6].toIntOrNull() ?: 0,
                    name = match.groupValues[7],
                    detail = match.groupValues[8],
                    raw = line,
                )
            } else {
                Row(0, 0, "?", "?", "?", line, broken = true)
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
        /**
         * When it happened, as `14:02:11.410` — **text, not a number.**
         *
         * The row is rewritten whole by a forget, a trim or a reset, and a rewrite must not restate
         * when something happened. Keeping the stamp as the characters the file holds makes that a
         * copy; parsing it into epoch millis and formatting it back would be two chances to change
         * it, in the one file whose job is to be the evidence.
         *
         * Empty for a row the app built in memory and has not written yet, and for a row that did
         * not parse.
         */
        val stamp: String = "",
        val id: Int,
        val size: Long,
        val kind: String,
        /** `in`, `out` or `app` — whether the model is sent this row, and whose decision that was. */
        val ctx: String,
        /**
         * The id of the call this row answers, or 0 when it answers nothing.
         *
         * **The pairing is a column now, not a queue.** A result used to say only the tool's name,
         * so both readers assumed the next result belonged to the oldest unanswered call — which
         * holds only while the writer interleaves calls and results. It does, today; nothing said it
         * had to, and a reply may carry several calls.
         */
        val answers: Int = 0,
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
                // **A row with no line yet is stamped as it is rendered.** Nothing builds one of
                // these except a test, which is why there is no clock to ask: every row the app
                // writes goes through `record`, which stamps it on the way into the file.
                LogFormat.recordRow(stamp, id, size, kind, ctx, answers, name, detail)
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
         * The row as `context.log` keeps it: `id size name  body`.
         *
         * **Two columns fewer than the evidence, and both are the app's.** `kind` is how the app
         * rebuilds a conversation and `ctx` is the audit's column — everything in this file is in,
         * so the column would say `in` on every line. What is left is what the model acts on: the
         * id `forget` takes, the cost of the row, and the words.
         *
         * The body is carried **verbatim**, already escaped as the evidence holds it, so the same
         * characters appear in both files and a reader can compare them.
         */
        val lean: String
            get() = LogFormat.contextRow(id, size, name, detail)
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
     * [Agent.messages] renders, and it never enters `context.log`: the rows that leave are removed
     * from that file, and the marker is not one of them.
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
            val marker = LogFormat.recordRow(System.currentTimeMillis(), nextId(), ContextBudget.tokens(said), NOTE, APP, 0, "trimmed", said)
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
     * and nothing else. `context.log` is emptied by the same write, which is what the model finds
     * when it looks at its own memory afterwards.
     */
    fun reset(): Int = synchronized(guard) {
        val id = nextId()
        val said = "the working context was cleared"
        val marker = LogFormat.recordRow(System.currentTimeMillis(), id, ContextBudget.tokens(said), NOTE, APP, 0, "reset", said)
        // **Marked, not erased.** Every row that was in stays in the record and reads `app`: the
        // person's act is not the agent's judgement, and it is not a way to make history disappear
        // either. After this the next request is the instructions and the new task.
        val marked = rows().map { if (it.ctx == IN) it.flipped(APP) else it }
        store(marked, marker)
        id
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
     * two happen at different times: **the gap between them is how long the tool took** — which is
     * why the rows now carry a time — and a session that stopped between them is exactly what a
     * dangling call looks like.
     *
     * **[answers] is the id of the call this result is for**, and it is what makes the pairing a
     * fact. Readers used to pair by position: a queue of unanswered calls, and the next result took
     * the oldest. That is right only while the writer interleaves calls and results, which it does
     * — but nothing said it had to, and a reply may carry several calls.
     *
     * A result with no call is possible and means something: the record can hold one after a reset,
     * and it names no call because there is none to name.
     */
    fun got(tool: String, body: String, answers: Int = 0): Int =
        record(GOT, tool, body, answers = answers)

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
        answers: Int = 0,
        now: Long = System.currentTimeMillis(),
    ): Int {
        synchronized(guard) {
            val id = nextId()
            // **Measured once, here, and never again.** The body is what a request carries, so the
            // cost of the body is what this row costs — not the formatted line, whose padding the
            // model never sees.
            val cost = ContextBudget.tokens(detail)
            append(file, LogFormat.recordRow(now, id, cost, kind, ctx, answers, name, detail))
            // **The context file grows with the evidence, one row at a time.** A row the app wrote
            // for itself (`app`) never enters it, which is what keeps a repeated `wait` out of the
            // request and the request byte-identical between polls.
            if (ctx == IN) append(context, LogFormat.contextRow(id, cost, name, detail))
            gate.seen = size()
            return id
        }
    }

    /**
     * Takes rows out of the context, by id — **the agent's compaction, and the whole of it.**
     *
     * One call, one atomic rewrite each: the named rows get `out` in the evidence's ctx column,
     * and they are removed from `context.log`. Nothing else in either file moves. The caller has
     * already asked [forgetProblem] whether this list is legal, so this is the doing rather than
     * the deciding. Returns how many rows changed.
     */
    fun forget(ids: Set<Int>): Int = synchronized(guard) {
        val rows = rows()
        if (forgetProblem(rows, ids) != null) return 0
        val said = ids.sorted().joinToString(",")
        val marker = LogFormat.recordRow(System.currentTimeMillis(), nextId(), ContextBudget.tokens(said), NOTE, APP, 0, "forgot", said)
        val changed = rows.map { if (it.id in ids) it.flipped(OUT) else it }
        store(changed, marker)
        ids.size
    }

    /**
     * Writes the whole record, beside it and then over it — **and the context with it.**
     *
     * Every whole-record write goes through here: a forget, both trims, a reset. So the two files
     * cannot disagree about what is in the context, because the context is written from the rows
     * that are `in` after the change. That is the whole of the keeping-in-step, and there is
     * nowhere else for a caller to get it wrong.
     *
     * **Beside and renamed, as everything else here is.** A rewrite in place that stopped halfway
     * would leave a record whose last row is half a row — and this file is the evidence, so a
     * reader must see either the old one whole or the new one whole.
     *
     * [rows] is in the order it happened, and [marker] is appended as the newest row.
     *
     * **Called with the lock held**, and it leaves the gate describing the file it just wrote — so
     * a caller cannot rewrite the record and then hand out an id from the version before it.
     */
    private fun store(rows: List<Row>, marker: String) {
        Beside.write(file, (rows.map { it.line } + marker).joinToString("\n") + "\n")
        writeContext(rows)
        gate.seen = size()
    }

    /** The context file's lines for [rows]: the rows that are `in`, as `id size name body`. */
    private fun contextLines(rows: List<Row>): List<String> =
        rows.filter { it.ctx == IN }.map { it.lean }

    /** Writes the context from [rows], unconditionally — the second half of a whole-record write. */
    private fun writeContext(rows: List<Row>) {
        val lines = contextLines(rows)
        try {
            Beside.write(context, if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n")
        } catch (t: Throwable) {
            // The context is what the model reads; the app reads the evidence. A record whose second
            // file cannot be written still answers every question the app asks, and the next open
            // finds the two out of step and writes it again.
        }
    }

    /**
     * The next id — **from the file's own sequence, under the file's own lock.**
     *
     * *Next* is not a number this object keeps: it belongs to the file, in [Gate], so two `AgentLog`s
     * over one project cannot each believe they are the one that wrote last. That was the bug this
     * used to have: the counter was per instance, so two writers opened on an empty file both took
     * id 1 and two rows shared a number — and an id is the handle `forget` takes, so a compaction
     * could remove the wrong row.
     *
     * The file is still asked, once per write, because a length is the only cheap way to tell that
     * something moved that this process did not do. Re-reading is the expensive case and it happens
     * only then.
     */
    private fun nextId(): Int {
        val g = gate
        val length = size()
        if (g.next == 0 || g.seen != length) {
            // **Re-read rather than trust.** The file moved outside this process, or a whole-record
            // write rewrote it — so the highest id is asked of the file and the gate taken from it.
            g.next = maxOf(g.next, (parse(file).maxOfOrNull { it.id } ?: 0) + 1)
            g.seen = length
        }
        return g.next++
    }

    private fun size(): Long = try {
        if (file.isFile) file.length() else 0L
    } catch (t: Throwable) {
        0L
    }

    /**
     * Adds one row to the end of [target].
     *
     * **An append, and the order is oldest first.** The file is a chronology: line one happened
     * first, and a line number stays the line number it was. That is what makes `read from 7 to 7`
     * mean the same row a turn later, and it is why the clear-up line can name a line at all.
     *
     * A write that stops halfway leaves half a line at the end; [parse] refuses a last line without
     * its newline, so a half row is never shown as an event, and the next append lands after it
     * rather than on top of it.
     */
    private fun append(target: File, line: String) {
        try {
            target.parentFile?.mkdirs()
            target.appendText(line + "\n")
        } catch (t: Throwable) {
            // A record that cannot be written is worth more than the operation it describes:
            // the turn goes on, and the person still has the app. Recorded nowhere, because
            // there is nowhere to record it — the file is the failure.
        }
    }

    // ---- reading ------------------------------------------------------------

    /**
     * Every row of the evidence, **in the order it happened** — the order it is written in.
     *
     * There is no reversal anywhere: line one is the first thing that happened, and a line number
     * is a handle that does not move. Everything above — the conversation, the audit, the trims —
     * reads this order, so the file and the app agree about what a position means.
     */
    fun rows(): List<Row> = parse(file)

    /**
     * The rows the model is sent, in order — **the rows whose ctx is `in`.**
     *
     * Answered from the evidence and never from `context.log`. That file is written from this
     * answer; asking both what the conversation is would be two sources of truth for one thing.
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
        // **The last line is only a row when it is finished.** A line without its newline is one
        // being written, and half a row shown as an event is showing something that did not happen.
        // Splitting puts an empty string last when the file ends in a newline and the half-written
        // row last when it does not, so dropping it is the same rule either way.
        return text.split("\n").dropLast(1).map { parseRow(it) }
    }

    // ---- opening ------------------------------------------------------------

    /**
     * Takes the numbers of the rows [open] read into the file's gate.
     *
     * **Called with the lock held, in the same hold as the parse.** That is the whole point: a
     * parse taken outside the lock and a length taken inside it describe two different files, so
     * the instance would believe it had seen rows it had not and would hand out an id already
     * written. Nothing is written here; sealing a dangling call belongs to [beginSession].
     */
    private fun adopt(rows: List<Row>) {
        val g = gate
        g.next = (rows.maxOfOrNull { it.id } ?: 0) + 1
        g.seen = size()
    }

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
            val marker = LogFormat.recordRow(System.currentTimeMillis(), nextId(), ContextBudget.tokens(said), NOTE, APP, 0, "trimmed", said)
            try {
                // The one place rows leave the record for good: this is the evidence's own limit,
                // and the reason the audit has a horizon rather than a history. Rows that go also
                // leave the context — the evidence's horizon is the record's, and a context row
                // with no row behind it is a conversation the app could no longer forget, because
                // `forgetProblem` reads the evidence.
                store(kept, marker)
            } catch (t: Throwable) {
                return 0
            }
            return dropped
        }
    }
}
