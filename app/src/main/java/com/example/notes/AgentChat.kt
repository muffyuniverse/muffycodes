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
        /**
         * The conversation as **messages**: one per side, alternating, oldest first.
         *
         * This is what the screen draws. [bubbles] is kept for the tests that pin the per-act
         * reading, and because the two units answer different questions - a bubble is "what
         * happened", a message is "who said it and what did they do about it".
         */
        val messages: List<Message> = emptyList(),
    )

    /**
     * **Who a message is from**, which is the only thing that decides how it is drawn.
     *
     * Two sides, and they look different on purpose: the operator's message is a plain row with no
     * pages and no footer, because it has no tool calls and no turns; the agent's is a container of
     * pages with a footer, because a turn does. Report: "user messages are less complicated - they
     * dont have multiple toolcalls and turns, - they are just a plain message row".
     */
    enum class Side { OPERATOR, AGENT }

    /**
     * **One page of an agent's message: one tool call and what came back.**
     *
     * A call and its result are one page because they are one act - the tool was run and this is
     * what it said - and two pages would make a person turn a page to read the answer to the
     * question they just read.
     */
    data class Page(
        /** The tool's name, or `agent` for the closing words. */
        val caption: String,
        /** The one line the result reduces to, for the page's own body. */
        val body: String,
        /** The whole of it: the arguments and the full result. */
        val detail: String,
        /** The row's number, which is how a person finds it in `session.log`. */
        val id: Int = 0,
        val stamp: String = "",
        val failed: Boolean = false,
        val forgotten: Boolean = false,
        /** True for the closing words, which are drawn as markdown rather than as a line. */
        val prose: Boolean = false,
    )

    /**
     * **One message: a side of the conversation, not one act.**
     *
     * The operator asked for a list that alternates `user · agent · user · agent` from the first row
     * of the record to the last. So a message is **one turn** of the agent - every call and result
     * it made, as pages, ending with the words it said - and the operator's is a single plain row.
     *
     * **This replaces the bubble-per-act unit**, which drew `user · call · call · call · agent-words`
     * and was the shape that was reported as wrong. The record did not change: `AgentLog.TURN`
     * already divides it, and [bubbles] used to skip that row.
     */
    data class Message(
        val side: Side,
        /**
         * The pages, for an agent's message: one per call, then the closing words.
         *
         * **Empty for the operator's**, which is the whole of what "plain row" means - there is
         * nothing to turn to.
         */
        val pages: List<Page> = emptyList(),
        /** The words, for the operator's message; empty for the agent's, whose words are a page. */
        val body: String = "",
        /** The row's number, for the footer. The operator's message carries its own too. */
        val id: Int = 0,
        val stamp: String = "",
        val forgotten: Boolean = false,
        val thinking: Long = 0,
        val output: Long = 0,
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
                // **Bookkeeping, like TURN — and unlike TURN it starts nothing.** The accounting
                // names a row inside a turn; drawing it as a line would put "usage out=2400" in the
                // conversation, which is a number the footer already shows against the words it
                // belongs to.
                AgentLog.USAGE -> Unit
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
    /** What one reply was charged for, as the [AgentLog.USAGE] row stores it. */
    /**
     * What one reply was charged for, as the [AgentLog.USAGE] row stores it.
     *
     * **A `data class`, not a `value class`.** Two numbers is two fields, and a value class in Kotlin
     * has exactly one - so the inline form here would not compile, and wrapping the pair in a holder
     * to qualify would cost an allocation to save none.
     */
    data class Spend(val output: Long, val thinking: Long)

    /**
     * Reads `out=123 think=45`, **and tolerates either half being missing.**
     *
     * A provider may report the completion count and say nothing about reasoning, so the two keys
     * are independent. A missing key becomes 0 here, which the footer reads as "not recorded" and
     * omits - the same rule the stats screen follows with its dashes, and the reason this returns
     * a value rather than two nullable fields: the footer's absence test is `> 0`, so a negative or
     * absent number would be indistinguishable anyway.
     *
     * A row whose detail is unrecognisable yields 0/0 rather than throwing. It is app bookkeeping
     * written by this app, but a row is still the evidence, and a reader that throws on one bad
     * line loses the whole conversation to it.
     */
    fun spend(detail: String): Spend {
        var output = 0L
        var thinking = 0L
        for (part in detail.split(' ')) {
            val at = part.indexOf('=')
            if (at <= 0) continue
            // **A negative is not a token count** - nobody produced -5 of anything - so it is
            // rejected rather than carried into a footer that would then hide it (`> 0` is the
            // footer's test) while a sum somewhere else counted it.
            val n = part.substring(at + 1).toLongOrNull()?.takeIf { it >= 0 } ?: continue
            when (part.substring(0, at)) {
                "out" -> output = n
                "think" -> thinking = n
            }
        }
        return Spend(output, thinking)
    }

    fun bubbles(conversation: List<AgentLog.Row>): List<Bubble> {
        val out = mutableListOf<Bubble>()
        val pending = mutableMapOf<Int, AgentLog.Row>()
        // **What each reply was charged for, by the row it accounts for.** Built up front rather
        // than as the loop reaches it, because the usage row is written after the reply and so
        // always sits *below* the bubble it describes - applied in place, a bubble would have been
        // drawn before its numbers arrived.
        val charged = conversation.mapNotNull { row ->
            if (row.kind == AgentLog.USAGE) row.answers to spend(row.detail) else null
        }.toMap()
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
                    "agent" -> {
                        val cost = charged[row.id]
                        out.add(
                            Bubble(
                                caption = "agent",
                                body = row.body,
                                detail = row.body,
                                id = row.id,
                                stamp = row.stamp,
                                forgotten = forgot,
                                prose = true,
                                // **Zero when this reply has no accounting**, which is what the
                                // footer reads as "not recorded" - it prints nothing rather than a
                                // zero, because a zero is a measurement.
                                thinking = cost?.thinking ?: 0,
                                output = cost?.output ?: 0,
                            ),
                        )
                    }
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

    /**
     * The conversation as **messages**: one per side, alternating, oldest first.
     *
     * The shape the operator asked for, and the unit is a turn:
     *
     *  - **The operator's message** is a plain row. No pages, no footer - it has no calls.
     *  - **The agent's message** is one turn: a page per call-and-result, then **the closing words
     *    as the last page.** That is what "ends with a finishing message from the agent" means: the
     *    ending is the last thing in the message, not a message of its own.
     *
     * **The turn boundary is `AgentLog.TURN`**, which the record already writes before each request.
     * Nothing is inferred from gaps, and no new bookkeeping is needed - [bubbles] simply skipped that
     * row, which is why it drew a bubble per act.
     *
     * A turn that only kept the conversation warm has no pages and is not drawn: forty pings would
     * be forty empty messages, and [Box.idle] is the one line that says the agent is still there.
     */
    fun messages(conversation: List<AgentLog.Row>): List<Message> {
        val out = mutableListOf<Message>()
        // **What each reply was charged for, by the row it accounts for.** Built up front because
        // the usage row is written after the reply and so sits *below* the page it describes.
        val charged = conversation.mapNotNull { row ->
            if (row.kind == AgentLog.USAGE) row.answers to spend(row.detail) else null
        }.toMap()
        // The operator's message opens the conversation, and the record holds exactly one per
        // burst - see `conversation()`, which starts the burst at it.
        val told = conversation.firstOrNull { it.kind == AgentLog.TOLD }
        if (told != null) {
            out.add(
                Message(
                    side = Side.OPERATOR,
                    body = told.body,
                    id = told.id,
                    stamp = told.stamp,
                    forgotten = told.ctx == AgentLog.OUT,
                ),
            )
        }
        // **A BURST, not a turn.** `AgentLog.TURN` is written before every request, so one request
        // is one turn - and a reply that calls a tool and then speaks is TWO turns, which grouped
        // as two messages would put the call and the words that close it in different messages. The
        // unit the operator asked for is everything the agent did about one thing the person said,
        // and that is the burst: `TOLD` starts one, and the next `TOLD` ends it.
        for (burst in AgentLog.bursts(conversation)) {
            // The first burst is the operator's own message and the work that answered it. Its
            // `TOLD` row is drawn above as the operator's message, so the burst is drawn here as
            // the agent's - the two halves of one exchange.
            if (burst.none { it.kind != AgentLog.TOLD }) continue
            // A burst that only kept the conversation warm is the idle line, not a message.
            if (burst.none { it.kind == AgentLog.CALL } &&
                burst.any { it.kind == AgentLog.NOTE && it.name == PING }
            ) {
                continue
            }
            val pages = mutableListOf<Page>()
            val pending = mutableMapOf<Int, AgentLog.Row>()
            var words: Page? = null
            // **The accounting rides on the closing words**, so the footer shows what the turn cost
            // against the sentence that ended it.
            var wordsCost: Spend? = null
            for (row in burst) {
                if (row.broken) {
                    pages.add(
                        Page(
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
                    // The operator's own line is the message above, not a page of this one.
                    AgentLog.TOLD -> Unit
                    AgentLog.CALL -> pending[row.id] = row
                    AgentLog.GOT -> {
                        val call = pending.remove(row.answers)
                        if (call == null) {
                            // A result whose call is not in this turn: a seal written when the app
                            // was reopened. Drawn where it stands, so an interrupted turn is visible
                            // rather than silently missing its answer.
                            pages.add(
                                Page(
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
                            // **One page, carrying the call's id** - the call is what a person looks
                            // for in the record, and the result is the same act.
                            pages.add(
                                Page(
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
                        PING -> Unit
                        // **`ended` is machinery, not conversation.** The words themselves are the
                        // `agent` note beside it, and drawing both would say the ending twice.
                        "ended" -> Unit
                        "agent" -> {
                            val cost = charged[row.id]
                            words = Page(
                                caption = "agent",
                                body = row.body,
                                detail = row.body,
                                id = row.id,
                                stamp = row.stamp,
                                forgotten = forgot,
                                prose = true,
                            )
                            // The accounting is read below, from this page's id: the footer is one
                            // per message, and a figure per page would invite adding them up.
                            wordsCost = cost
                        }
                        // "stopped" and its reason, most of all: the only thing a failed turn has to
                        // say, so it is drawn.
                        else -> pages.add(
                            Page(
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
            // **The closing words are the last page.** Appended rather than emitted in place, so a
            // turn that spoke and then called a tool still ends with its words - which is what
            // "ends with a finishing message from the agent" asks for.
            if (words != null) pages.add(words)
            if (pages.isEmpty()) continue
            val spend = wordsCost
            out.add(
                Message(
                    side = Side.AGENT,
                    pages = pages,
                    id = pages.first().id,
                    stamp = pages.first().stamp,
                    thinking = spend?.thinking ?: 0,
                    output = spend?.output ?: 0,
                ),
            )
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
            messages = messages(conversation),
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
