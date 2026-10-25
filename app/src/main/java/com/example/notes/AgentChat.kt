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

    /** The tool that asks whether anything happened. Three letters, named once. */
    const val WAIT = "wait"

    /** One line as the box draws it. [detail] is what tapping the turn adds. */
    data class Line(
        val caption: String,
        val body: String,
        val failed: Boolean = false,
        val detail: String = "",
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
         * True for a turn that only asked whether anything happened.
         *
         * The idle polls are the one thing that repeats for ever, so they are the one thing the
         * box does not draw turn by turn: forty of them would be forty identical lines, and the
         * record already keeps every one of them.
         */
        val idle: Boolean
            get() {
                val calls = rows.filter { it.kind == AgentLog.CALL }
                return calls.isNotEmpty() && calls.all { it.name == WAIT }
            }

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
    )

    // ---- the conversation ---------------------------------------------------

    /**
     * The newest conversation in [rows], or null when nobody has said anything yet.
     *
     * "Newest" is the last burst the record holds, and a burst is the app's own split: an
     * operator's message opens one and a terminating turn closes it. That split is also what the
     * trim works on, so a conversation the box can show is never half a conversation.
     */
    fun conversation(rows: List<AgentLog.Row>): List<AgentLog.Row>? =
        AgentLog.bursts(rows).lastOrNull { burst -> burst.any { it.kind == AgentLog.TOLD } }

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
                val since = idle.first().rows.firstOrNull { it.time.isNotEmpty() }?.time.orEmpty()
                val checks = if (idle.size == 1) "1 check" else "${idle.size} checks"
                "idle since ${since.take(5)} · $checks"
            },
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
        val pending = ArrayDeque<AgentLog.Row>()
        for (row in rows) {
            if (row.broken) {
                out.add(Line("unreadable", oneLine(row.detail, 120)))
                continue
            }
            when (row.kind) {
                AgentLog.TURN, AgentLog.TOLD -> Unit
                AgentLog.CALL -> pending.addLast(row)
                AgentLog.GOT -> {
                    val call = pending.removeFirstOrNull()
                    if (call == null) {
                        // A result whose call is not in this conversation: a seal written when
                        // the app was reopened, answering a call from the conversation before.
                        // Drawn where it stands, so an interrupted turn is visible rather than
                        // silently missing its answer.
                        out.add(Line(row.name, summary(row.body), failed(row.body), row.body))
                    } else {
                        out.add(callLine(call, row))
                    }
                }

                AgentLog.ASK -> out.add(Line("asked", row.body, detail = row.body))
                AgentLog.CONCLUDE -> out.add(Line("concluded", row.body, detail = row.body))
                AgentLog.NOTE -> when (row.name) {
                    // The idle note belongs to the collapsed idle line, not to a turn.
                    "idle" -> Unit
                    "agent", "reason" -> out.add(
                        Line(row.name, oneLine(row.body, 200), detail = row.body)
                    )
                    // Anything else the app or the agent noted about the turn: "stopped"
                    // and its reason, most of all. It is the only thing a failed turn has to
                    // say, so it is drawn rather than hidden.
                    else -> out.add(Line(row.name, oneLine(row.body, 200), detail = row.body))
                }

                else -> out.add(Line(row.kind.lowercase(), oneLine(row.body, 120), detail = row.body))
            }
        }
        for (call in pending) {
            // A terminating call is drawn from its own ASK/CONCLUDE row, and `wait` from the
            // idle line, so a call still waiting here is one the turn never got to answer.
            if (call.name in AgentLog.TERMINATING) continue
            out.add(Line(call.name, "no answer yet", failed = true, detail = call.body))
        }
        return out
    }

    // ---- how a result becomes one line --------------------------------------

    private fun callLine(call: AgentLog.Row, got: AgentLog.Row): Line = Line(
        caption = call.name,
        body = summary(got.body),
        failed = failed(got.body),
        detail = "called with ${call.body}\n\n${got.body}",
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
