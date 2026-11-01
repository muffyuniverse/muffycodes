package com.example.notes

/**
 * **The app's own lines in the conversation: what went wrong, said once, until it is over.**
 *
 * Not to be confused with [Notices], which is the Android notification permission. This is about rows.
 *
 * ## What a notice is, and what it is not
 *
 * A notice is a row the app wrote for itself — `ctx = app` — drawn in the chat as a red line *after*
 * the message that raised it, and gone the moment the person or the agent says anything else. It is
 * not a message: it is not in `messages()`, it does not begin a burst, and the model never sees it.
 * The operator's words: *"app error messages should be separate from agent's or user's messages, they
 * are added as the last message after the one that triggered them to show up, and they are removed
 * from chat message list and session log with the next message or burst page from user or agent."*
 *
 * ## Why there is no state
 *
 * **A notice has no lifecycle of its own, and that is the whole design.** It is a function of the
 * record: this row was written, and nothing has been said since. There is no "raise a notice" call, no
 * "clear the notice" call, and therefore no code path that can forget to clear one — the failure that
 * makes notices rot on a screen for hours. [open] answers the question from the rows every time it is
 * asked.
 *
 * **"Removed from the session log" is a flip, not a delete**, the same mechanism `forget` uses: the
 * row's `ctx` becomes `out`, so it leaves the conversation and stays in the evidence. Deleting it
 * would mean the record of what the app told the person could be rewritten, and the record is the one
 * thing in this app that is never rewritten.
 */
object ChatNotices {

    /**
     * The vocabulary — **a closed list, because a name is what the screen decides how to say.**
     *
     * A notice whose name is not here is a note the app wrote for its own reasons (a trim, a reset, a
     * forgotten row) and not something to put in front of the person. That distinction is the whole
     * reason for a list rather than "any `app` row": the app writes far more notes than it has things
     * to say, and showing them all would turn the conversation into the app's diary.
     */
    val NAMES = setOf(
        /** The turn was cut short: a refused key, a transport that failed, a loop, a budget. */
        "stopped",
        /** A request was retried after a failure that may fix itself. */
        "retrying",
        /** The app could not reach GitHub, so the work did not leave the phone. */
        "offline",
    )

    /** True when [row] is a notice the screen should draw while it is open. */
    fun isNotice(row: AgentLog.Row): Boolean =
        row.kind == AgentLog.NOTE && row.ctx == AgentLog.APP && row.name in NAMES

    /**
     * The notices still open at the end of [rows], oldest first.
     *
     * **The rule, in one sentence: a notice is open until somebody speaks after it.**
     *
     * "Speaks" means a row that is part of the conversation — a `TOLD` from the person or an `OUT`
     * row from the agent — with a **higher id** than the notice. Ids are the record's own order, so
     * this cannot be fooled by a clock that went backwards or two rows in the same millisecond.
     *
     * **A notice already flipped to `out` is not open**, which is the half that makes the flip
     * authoritative: once the app has decided a notice is over and written that down, re-deriving
     * must not bring it back.
     */
    fun open(rows: List<AgentLog.Row>): List<AgentLog.Row> {
        val lastSpoken = rows.filter { it.isSpoken }.maxOfOrNull { it.id } ?: 0
        return rows.filter { isNotice(it) && it.id > lastSpoken }
    }

    /**
     * The ids to flip, given the whole record — **what the writer calls after every append.**
     *
     * A notice that is no longer [open] but is still `app` has been answered, and flipping it is how
     * "removed from the session log" happens without deleting anything. This returns only the rows
     * that need the change, so a caller can do nothing on the common case where the answer is empty.
     */
    fun toClose(rows: List<AgentLog.Row>): Set<Int> {
        // **Not `id !in open`.** That was the first version and it was wrong in the one case that
        // matters: when the agent speaks after two notices, `open` is empty, so *every* notice looked
        // "not open" and every one was returned — including ones already flipped. The test said
        // `expected:<[2]> but was:<[]>` on a different shape and caught the same confusion.
        //
        // The question is about the **column**, not about the derivation: a notice needs flipping when
        // it is still `app` and the derivation says it is no longer open.
        val open = open(rows).map { it.id }.toSet()
        return rows.filter { isNotice(it) && it.ctx != AgentLog.OUT && it.id !in open }
            .map { it.id }
            .toSet()
    }

    /**
     * True for a row that is part of the conversation, and so ends a notice.
     *
     * **`ctx == IN`, not `ctx == OUT`.** The agent's own words are a `NOTE` written with the context's
     * column — `note("agent", …)` — so they are `in`. Checking for `out` would have counted only the
     * rows the agent had *forgotten*, which is the opposite of speaking.
     *
     * So: a person's message, or anything the model is sent that is not itself a notice. A `CALL` or a
     * `GOT` counts too, and should: the agent doing something about the failure is at least as much an
     * answer as saying so.
     */
    private val AgentLog.Row.isSpoken: Boolean
        get() = kind == AgentLog.TOLD || (ctx == AgentLog.IN && !isNotice(this))
}
