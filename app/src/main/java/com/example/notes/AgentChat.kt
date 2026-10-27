package com.example.notes

/**
 * The box: one conversation, its turns, and the lines a turn draws.
 *
 * **Everything here is read from the record.** The box has no memory of its own — no list of
 * messages kept beside the file — so what a person reads on the screen and what the model was
 * sent cannot disagree, and reopening the app shows the conversation as it actually happened
 * rather than as the last session remembered it.
 *
 * A **conversation** is one operator message and everything after it: the agent works, and it
 * ends when the agent asks or concludes. An earlier conversation is not reachable from the box;
 * it is in `session.log`, which is the evidence and is meant to be read.
 *
 * Here rather than in the screen because it is the part worth testing: what counts as a turn,
 * what a tool result says on one line, and what an idle agent looks like after forty polls.
 */
object AgentChat {

    /** The note a keepalive ping leaves behind. Four letters, named once. */
    const val PING = "ping"

    /**
     * One drawn line. [detail] is what tapping the turn adds.
     *
     * [tokens] is the row's own size, and it exists for a person: when a context is being cleared
     * up, "which of these is the expensive one" is a question a screen can answer at a glance.
     *
     * **It is the number the record stores, in the unit the limit is in.** It used to be counted
     * here, in bytes, from the formatted line — a second computation of a number the record already
     * held, in a unit nothing else in the app used, so a person's caption and the clear-up line could
     * disagree about the same row.
     */
    data class Line(
        val caption: String,
        val body: String,
        val failed: Boolean = false,
        val tokens: Long = 0,
        val detail: String = "",
        /** Out of the context: drawn struck through, so the conversation shows what it forgot. */
        val forgotten: Boolean = false,
    )

    /**
     * One turn: one request to the model, and everything it produced.
     *
     * Turns are the app's [AgentLog.TURN] rows, not tool calls: a reply that called two tools is
     * one turn, and counting calls instead would make the navigator's "3 of 6" describe something
     * the person never asked for.
     */
    data class Turn(val rows: List<AgentLog.Row>) {

        /**
         * True for a turn that only kept the conversation warm.
         *
         * Keepalive pings are the one thing that repeats for ever, so they are the one thing the box
         * does not draw turn by turn: forty of them would be forty identical lines, and the record
         * already keeps every one of them.
         *
         * **Keyed to the app's own note, not to a tool call.** A ping asks the model for nothing, so
         * there is no call row to recognise; what a ping leaves is the `ping` note, and a turn is
         * idle when it has that note and no calls at all.
         */
        val idle: Boolean
            get() = rows.none { it.kind == AgentLog.CALL } &&
                rows.any { it.kind == AgentLog.NOTE && it.name == PING }

        val lines: List<Line> get() = AgentChat.lines(rows)
    }

    /** One conversation, ready to draw. */
    data class Box(
        /** What the person asked, in their own words. */
        val told: String,
        /** The turns that did something, oldest first. */
        val turns: List<Turn>,
        /** How the idle polls after them read, or null when the agent is not waiting. */
        val idle: String?,
        /**
         * The conversation as **bubbles**: one per act, in the order they happened.
         *
         * The unit is a request and what came of it — not a line, and not a tool call. The
         * operator's message is the first, a call-and-its-result is one in the middle, and the
         * agent's own words are the last. That is the shape a person reads a conversation in, and
         * it is why this exists beside [turns]: a turn is how the *record* is divided (one request
         * to the model), and a bubble is how a *conversation* is divided. They are not the same
         * division and neither can be derived from the other.
         */
        val bubbles: List<Bubble> = emptyList(),
    )

    /**
     * One thing that happened, with its own footer.
     *
     * **A call and its result are one bubble**, because they are one act: the tool was run and this
     * is what came back. Two bubbles would make a person read the same event twice, and would put
     * the result further from the call than the arguments are.
     */
    data class Bubble(
        /** What this bubble is, in one word — `told`, the tool's name, or `agent`. */
        val caption: String,
        /** The words, or the one line a result reduces to. */
        val body: String,
        /** The whole of it, for the page view: arguments and the full result. */
        val detail: String = "",
        /**
         * **The row's own number**, which is how a person finds it in `session.log`.
         *
         * The box is a view of the record, so the handle it shows has to be the record's — not a
         * position in a list, which moves when anything before it is forgotten.
         */
        val id: Int = 0,
        /**
         * **The time, and not the date.** The date is the conversation's; a list where every row
         * repeats the same date is a column of noise. Empty for a row the app has not written yet.
         */
        val stamp: String = "",
        val failed: Boolean = false,
        /** Out of the context: drawn struck through, so the conversation shows what it forgot. */
        val forgotten: Boolean = false,
        /** True for the agent's own words, which are drawn as markdown rather than as a line. */
        val prose: Boolean = false,
        /** The tokens the model spent thinking, when the row knows — 0 when it does not. */
        val thinking: Long = 0,
        /** The tokens it wrote, when the row knows — 0 when it does not. */
        val output: Long = 0,
    )

    // ---- the conversation ---------------------------------------------------

    /**
     * The newest conversation in [rows], or null when nobody has said anything yet.
     *
     * "Newest" is the last burst the record holds, and a burst is the app's own split: **an
     * operator's message opens one, and the next operator's message opens the next.** That split is
     * also what the trim works on, so a conversation the box can show is never half a conversation.
     *
     * **The tail loop is gone with `ask`/`conclude`.** A burst used to end at a terminating row, so
     * the keepalive's rows that followed started a burst of their own with no operator message in
     * it — and reading only the last burst *with* a TOLD would have dropped them, which is why the
     * loop below existed. With no terminating row, a burst runs from one operator message to the
     * next, and the checks after the agent finished are part of the same conversation by
     * construction.
     */
    fun conversation(rows: List<AgentLog.Row>): List<AgentLog.Row>? {
        val bursts = AgentLog.bursts(rows)
        val start = bursts.indexOfLast { burst -> burst.any { it.kind == AgentLog.TOLD } }
        if (start < 0) return null
        // Everything from that operator message to the end of the record: the conversation, the
        // checks that followed it, and the checks still to come. A later operator message starts a
        // burst of its own and is simply not included.
        return bursts.drop(start).flatten()
    }

    /**
     * The turns of [conversation], oldest first.
     *
     * Rows before the first boundary are the first turn — which is how a record written before
     * the app marked its requests still draws: as one turn, because its request boundaries are
     * not recoverable and inventing them would be worse than saying less.
     */
    fun turns(conversation: List<AgentLog.Row>): List<Turn> {
        val out = mutableListOf<MutableList<AgentLog.Row>>()
        for (row in conversation) {
            when (row.kind) {
                // The operator's own line is drawn once, above the turns.
                AgentLog.TOLD -> Unit
                AgentLog.TURN -> out.add(mutableListOf())
                else -> {
                    if (out.isEmpty()) out.add(mutableListOf())
                    out.last().add(row)
                }
            }
        }
        return out.filter { it.isNotEmpty() }.map { Turn(it.toList()) }
    }

    /**
     * The conversation as bubbles, **one per act.**
     *
     * The three shapes, and why each is one bubble:
     *
     *  - **The operator's message**, first, always. It is what the rest of the list is an answer to.
     *  - **A call and its result**, joined by `answers` rather than by position — so a reply that
     *    called two tools is two bubbles and the order the results arrived in does not matter.
     *  - **The agent's own words**, last, as prose. This is the ending, and there is no `CONCLUDE`
     *    row any more: the words are the agent's note, which is what the model reads back, so
     *    saying them twice was the duplication this app avoids everywhere else.
     *
     * **A keepalive check is not a bubble.** Forty of them would be forty identical bubbles, and
     * what a person wants from them is one line saying the agent is still there — which is what
     * [Box.idle] is.
     */
    fun bubbles(conversation: List<AgentLog.Row>): List<Bubble> {
        val out = mutableListOf<Bubble>()
        val pending = mutableMapOf<Int, AgentLog.Row>()
        for (row in conversation) {
            if (row.broken) {
                out.add(
                    Bubble(
                        caption = "unreadable",
                        body = oneLine(row.detail, 120),
                        detail = row.detail,
                        id = row.id,
                        stamp = row.stamp,
                        failed = true,
                        forgotten = row.ctx == AgentLog.OUT,
                    ),
                )
                continue
            }
            val forgot = row.ctx == AgentLog.OUT
            when (row.kind) {
                // **The request boundaries are not bubbles.** A `TURN` row marks where one request
                // to the model ended; it is bookkeeping about the record's shape, not something
                // that happened in the conversation.
                AgentLog.TURN -> Unit
                AgentLog.TOLD -> out.add(
                    Bubble(
                        caption = "you",
                        body = row.body,
                        detail = row.body,
                        id = row.id,
                        stamp = row.stamp,
                        forgotten = forgot,
                    ),
                )
                AgentLog.CALL -> pending[row.id] = row
                AgentLog.GOT -> {
                    val call = pending.remove(row.answers)
                    if (call == null) {
                        // A result whose call is not in this conversation: a seal written when the
                        // app was reopened, answering a call from the conversation before. Drawn
                        // where it stands, so an interrupted turn is visible rather than silently
                        // missing its answer.
                        out.add(
                            Bubble(
                                caption = row.name,
                                body = summary(row.body),
                                detail = row.body,
                                id = row.id,
                                stamp = row.stamp,
                                failed = failed(row.body),
                                forgotten = forgot,
                            ),
                        )
                    } else {
                        // **One bubble, and it carries the call's id** — the call is what the person
                        // is looking for in the record, and the result is the same act.
                        out.add(
                            Bubble(
                                caption = call.name,
                                body = summary(row.body),
                                detail = "asked with ${call.body}\n\nanswered with ${row.body}",
                                id = call.id,
                                stamp = call.stamp,
                                failed = failed(row.body),
                                forgotten = forgot || call.ctx == AgentLog.OUT,
                            ),
                        )
                    }
                }
                AgentLog.NOTE -> when (row.name) {
                    // The ping's own note belongs to the collapsed idle line, not to a bubble.
                    PING -> Unit
                    // **`ended` is a fact about the loop, not about the conversation.** It says why
                    // the turn stopped — "no tool call, so the words are the conclusion" — which is
                    // bookkeeping about the app's own decision. The words themselves are the `agent`
                    // note right beside it, and drawing both would put the same ending on screen
                    // twice with one of them phrased as machinery.
                    "ended" -> Unit
                    "agent" -> out.add(
                        Bubble(
                            caption = "agent",
                            body = row.body,
                            detail = row.body,
                            id = row.id,
                            stamp = row.stamp,
                            forgotten = forgot,
                            prose = true,
                        ),
                    )
                    // Anything else the app or the agent noted: "stopped" and its reason, most of
                    // all. It is the only thing a failed turn has to say, so it is drawn.
                    else -> out.add(
                        Bubble(
                            caption = row.name,
                            body = oneLine(row.body, 200),
                            detail = row.body,
                            id = row.id,
                            stamp = row.stamp,
                            forgotten = forgot,
                        ),
                    )
                }
                else -> Unit
            }
        }
        return out
    }

    /** The whole conversation as the box draws it, or null when there is none yet. */
    fun box(rows: List<AgentLog.Row>): Box? {
        val conversation = conversation(rows) ?: return null
        val told = conversation.firstOrNull { it.kind == AgentLog.TOLD } ?: return null
        val all = turns(conversation)
        var productive = all.size
        while (productive > 0 && all[productive - 1].idle) productive--
        val idle = all.subList(productive, all.size)
        return Box(
            told = told.body,
            turns = all.subList(0, productive),
            idle = if (idle.isEmpty()) {
                null
            } else {
                // **The id, because the record has no clock.** A row in a file is not where a time
                // belongs — the app's own trace still says when everything happened — and the id is
                // the handle the person can find the row by, so it is what the line names.
                val since = idle.first().rows.firstOrNull()?.id
                val checks = if (idle.size == 1) "1 check" else "${idle.size} checks"
                if (since == null) checks else "idle since #$since · $checks"
            },
            bubbles = bubbles(conversation),
        )
    }

    // ---- one turn's lines ---------------------------------------------------

    /**
     * What one turn says, in the order it happened.
     *
     * A call and its result are **one line**, because that is how a person reads a transcript:
     * the tool, then whether it worked and the one sentence that matters. The arguments and the
     * whole result are kept for expansion — they are the answer to "why did it do that", which is
     * a different question from "what happened".
     */
    fun lines(rows: List<AgentLog.Row>): List<Line> {
        val out = mutableListOf<Line>()
        /**
         * The calls still waiting for a result, **by id** — not a queue.
         *
         * The result names the call it answers, so the order results arrive in stops being
         * load-bearing. A queue was right only while the writer interleaved calls and results; it
         * does, but the record no longer has to be read that way.
         */
        val pending = mutableMapOf<Int, AgentLog.Row>()
        for (row in rows) {
            // **The row's own number, from the row.** It is stored at write time, the agent chooses
            // what to forget by it, and a second computation here is a second answer to the same
            // question — which is what made a person's caption disagree with the clear-up line.
            val size = row.size
            // **A row the agent forgot is still read here, and is marked.** The box shows the whole
            // record — that is what makes the forgetting visible instead of something to take on
            // trust — so the flag travels with the line and the row is drawn struck through.
            val forgot = row.ctx == AgentLog.OUT
            if (row.broken) {
                out.add(Line("unreadable", oneLine(row.detail, 120), tokens = size, forgotten = forgot))
                continue
            }
            when (row.kind) {
                AgentLog.TURN, AgentLog.TOLD -> Unit
                AgentLog.CALL -> pending[row.id] = row
                AgentLog.GOT -> {
                    val call = pending.remove(row.answers)
                    if (call == null) {
                        // A result whose call is not in this conversation: a seal written when
                        // the app was reopened, answering a call from the conversation before.
                        // Drawn where it stands, so an interrupted turn is visible rather than
                        // silently missing its answer.
                        out.add(
                            Line(
                                caption = row.name,
                                body = summary(row.body),
                                failed = failed(row.body),
                                tokens = size,
                                detail = row.body,
                                forgotten = forgot,
                            ),
                        )
                    } else {
                        out.add(callLine(call, row))
                    }
                }

                AgentLog.NOTE -> when (row.name) {
                    // The ping's own note belongs to the collapsed idle line, not to a turn.
                    PING -> Unit
                    "agent", "reason" -> out.add(
                        Line(
                            row.name,
                            oneLine(row.body, 200),
                            tokens = size,
                            detail = row.body,
                            forgotten = forgot,
                        ),
                    )
                    // Anything else the app or the agent noted about the turn: "stopped"
                    // and its reason, most of all. It is the only thing a failed turn has to
                    // say, so it is drawn rather than hidden.
                    else -> out.add(
                        Line(
                            row.name,
                            oneLine(row.body, 200),
                            tokens = size,
                            detail = row.body,
                            forgotten = forgot,
                        ),
                    )
                }

                else -> out.add(
                    Line(
                        row.kind.lowercase(),
                        oneLine(row.body, 120),
                        tokens = size,
                        detail = row.body,
                        forgotten = forgot,
                    ),
                )
            }
        }
        // **A map iterates in no useful order**, and these are the calls that never got an answer —
        // which is a sequence a person reads. Sorted by id, which is the order they were made.
        for (call in pending.values.sortedBy { it.id }) {
            // A terminating call is drawn from its own ASK/CONCLUDE row, and `wait` from the
            // idle line, so a call still waiting here is one the turn never got to answer.
            out.add(
                Line(
                    call.name,
                    "no answer yet",
                    failed = true,
                    tokens = call.size,
                    detail = call.body,
                ),
            )
        }
        return out
    }

    // ---- how a result becomes one line --------------------------------------

    private fun callLine(call: AgentLog.Row, got: AgentLog.Row): Line = Line(
        caption = call.name,
        body = summary(got.body),
        failed = failed(got.body),
        // **The pair's size, and the call's was the one that could be huge.** A call carries the
        // model's arguments — a whole file's text, when it used `edit` to write one — so the row a
        // person most needs the size of is often the one with no visible body at all. Both numbers
        // are the rows' own, written once when each row was written.
        tokens = call.size + got.size,
        detail = "called with ${call.body}\n\n${got.body}",
        // The pair is forgotten together or not at all — that is a rule the tool enforces — so
        // either row's state is the pair's.
        forgotten = call.ctx == AgentLog.OUT || got.ctx == AgentLog.OUT,
    )

    /** The first line is `0` or `1`; the rest is the one sentence worth showing beside it. */
    private fun summary(body: String): String {
        val lines = body.trim().lines().filter { it.isNotBlank() }
        val status = lines.firstOrNull()?.trim().orEmpty()
        val rest = lines.drop(1).joinToString(" ") { it.trim() }
        val text = if (rest.isEmpty()) status else "$status · $rest"
        return oneLine(text, 160)
    }

    private fun failed(body: String): Boolean = body.trimStart().startsWith(AgentLog.FAILURE)

    private fun oneLine(text: String, max: Int): String {
        val flat = text.trim().lines().joinToString(" ") { it.trim() }.trim()
        return if (flat.length <= max) flat else flat.take(max - 1) + "…"
    }
}
