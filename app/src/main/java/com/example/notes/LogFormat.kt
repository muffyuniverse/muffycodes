package com.example.notes

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The one shape of a row, for every file this app writes.
 *
 * `time id kind name detail` — fixed columns, one line per event, and the same formatting
 * whether the row is the app's own trace or the agent's record of a turn. The trace and the
 * record are read by the same habits (`grep`, a shell, an eye on a phone), so they are formatted
 * by the same code: a second formatter is a second thing to keep in step, and it would drift on
 * the one detail that matters most, the escaping of a body that has newlines in it.
 *
 * `detail` is the only free-form part, and it is **escaped**: a newline becomes a backslash
 * and an `n`, a backslash becomes two backslashes. Every other choice was worse — a raw
 * newline breaks the file's one-row-per-event promise, and JSON-encoding the body puts a
 * backslash before every quote in a tool's arguments and stops the file being readable by
 * eye. Escaping is the only rule that keeps `grep 'CALL  edit'` working *and* keeps the body
 * intact.
 */
object LogFormat {

    private val clock = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    /** `14:02:11.410` — from the epoch millis the app records. */
    fun stamp(at: Long): String = clock.get()!!.format(Date(at))

    /**
     * One row of the trace, in the fixed columns: `time build state id kind name  detail`.
     *
     * **The build and the state are columns, not words in the detail.** A report from a phone is
     * only actionable if it says which build produced it — the trace held nine builds at once, and
     * "sync is broken" from v103 and from v149 are two different reports — and the first thing
     * anybody asks of a log is whether the app was open when the line was written. Both were being
     * written as prose (`Muffy Codes 149 (1793077053)` inside one NOTE), which is a column in the
     * only sense that it is on the line.
     *
     * `build` is the version **name**, four wide, because it is short (`149`, `1.4`) and sorts
     * beside itself in a shell. `state` is three wide and is `open` or `cls` — the app in front of
     * the person, or behind something else. `-` is used where neither is known, which is every row
     * written before the app has worked out which build it is.
     *
     * The id is padded to four so that a file read with its eyes has its columns straight, and the
     * kind is padded to seven for the same reason. [name] is never padded: it is the one column
     * whose width cannot be known, and padding it would push the body out of alignment for the long
     * names and not the short ones.
     */
    fun row(
        at: Long,
        id: Int,
        kind: String,
        name: String,
        detail: String,
        build: String = "-",
        state: String = "-",
    ): String = row(stamp(at), id, kind, name, detail, build, state)

    /**
     * The same row from a stamp that is already text.
     *
     * Needed by anything that **rewrites** a file it did not read through this class: the
     * agent's record is truncated at a burst boundary, and a row that came back with a fresh
     * timestamp would say the whole conversation happened at the moment it was trimmed.
     */
    fun row(
        stamp: String,
        id: Int,
        kind: String,
        name: String,
        detail: String,
        build: String = "-",
        state: String = "-",
    ): String =
        buildString {
            append(stamp)
            append(' ')
            append(build.padEnd(4))
            append(' ')
            append(state.padEnd(4))
            append(' ')
            append(id.toString().padStart(4))
            append(' ')
            append(kind.padEnd(7))
            append(' ')
            append(name)
            if (detail.isNotEmpty()) {
                append("  ")
                append(escaped(detail))
            }
        }

    /**
     * A **record** row: `time id size kind ctx answers name  detail`.
     *
     * **The clock is back, and the reason it left was wrong.** The row used to begin with
     * `14:02:11.410` and it was dropped on the argument that the record is a conversation rather
     * than a chronology — but `session.log` is described in its own words as *"the evidence: every
     * event, in the order it happened"*, and a chronology with no times cannot say when. The
     * question "how long did that tool take" had no answer in the file that exists to answer it,
     * because the gap between a call and its result is the time between two rows.
     *
     * **`answers` is which call a result is answering**, and it makes the pairing a fact instead of
     * a convention. A `GOT` row used to say only the tool's name, so both readers — `AgentChat` and
     * [Agent.messages] — kept a queue of unanswered calls and assumed the next result belonged to
     * the oldest one. That holds only because the writer interleaves `CALL GOT CALL GOT`; a reply
     * may carry several calls, and a writer that batched them would pair call 1 with result 3 with
     * nothing to show it had. `-` means "this row answers nothing", which is every row that is not
     * a result.
     *
     * **Neither column goes in the body.** The model is sent a row's body, and both of these are
     * facts about the record rather than things the model needs in order to work — the same
     * argument that put `ctx` in a column.
     *
     * **`size` is the row's cost in tokens**, stored rather than recomputed: it is the number the
     * agent chooses what to forget by, and two places computing it was two numbers that could
     * disagree. It is the cost of the **body** — what the row contributes to a request — and not of
     * the formatted line, which carries column padding the model never sees.
     *
     * The columns still stand in fixed order, so [flipCtx] can change the ctx column by text surgery
     * and no rewrite has to re-render a body that contains a backslash.
     */
    fun recordRow(
        at: Long,
        id: Int,
        size: Long,
        kind: String,
        ctx: String,
        answers: Int = 0,
        name: String,
        detail: String,
    ): String = recordRow(stamp(at), id, size, kind, ctx, answers, name, detail)

    /**
     * The same record row from a stamp that is already text.
     *
     * Needed by anything that **rewrites** a record it did not read through this class: a forget,
     * both trims and a reset rewrite the whole file, and a row that came back with a fresh
     * timestamp would say the whole conversation happened at the moment it was rewritten.
     */
    fun recordRow(
        stamp: String,
        id: Int,
        size: Long,
        kind: String,
        ctx: String,
        answers: Int,
        name: String,
        detail: String,
    ): String = buildString {
        append(stamp)
        append(' ')
        append(id.toString().padStart(4))
        append(' ')
        append(size.toString().padStart(5))
        append(' ')
        append(kind.padEnd(7))
        append(' ')
        append(ctx.padEnd(3))
        append(' ')
        // Five wide like the id it names, and `-` where there is nothing to name: a column that is
        // sometimes blank and sometimes an id is a column nobody can scan.
        append(if (answers > 0) answers.toString().padStart(4) else "-".padStart(4))
        append(' ')
        append(name)
        if (detail.isNotEmpty()) {
            append("  ")
            append(escaped(detail))
        }
    }

    /**
     * A row as `agent/context.log` keeps it: `id size name  body` — **no kind, no ctx.**
     *
     * The two columns it leaves out are the app's: `kind` is how a conversation is rebuilt, and
     * everything in this file is in the context, so a `ctx` column would read `in` on every line.
     * What is left is what the model acts on — the id `forget` takes, what the row costs, and the
     * words — and the body is escaped exactly as the evidence escapes it, so the same row is the
     * same characters in both files.
     */
    fun contextRow(id: Int, size: Long, name: String, detail: String): String = buildString {
        append(id.toString().padStart(4))
        append(' ')
        append(size.toString().padStart(5))
        append(' ')
        append(name)
        if (detail.isNotEmpty()) {
            append("  ")
            append(escaped(detail))
        }
    }

    /**
     * The same line with its ctx column changed, and **not one other byte moved.**
     *
     * Text surgery, deliberately: the alternative is re-rendering the row from its parsed parts,
     * and that is where a detail containing a backslash drifts a little on every rewrite. The
     * match stops at the fourth column, so the name and the body are carried over exactly as the
     * file had them.
     */
    fun flipCtx(line: String, ctx: String): String {
        val match = HEAD.find(line) ?: return line
        // **Rebuilt from the columns the caller may not touch, taken as they stand.** The stamp is
        // carried over verbatim rather than reformatted: a rewrite must not restate when something
        // happened, and a `SimpleDateFormat` round trip is a chance to change it.
        val stamp = match.groupValues[1]
        val id = match.groupValues[2].padStart(4)
        val size = match.groupValues[3].padStart(5)
        val kind = match.groupValues[4].padEnd(7)
        val answers = match.groupValues[5].padStart(4)
        // Everything after the separator belongs to the name and the body, and is carried over as
        // the same characters: this function has no way to touch them.
        val rest = line.substring(match.range.last + 1)
        return "$stamp $id $size $kind ${ctx.padEnd(3)} $answers $rest"
    }

    /**
     * The fixed columns before the name: stamp, id, size, kind, ctx, answers.
     *
     * **`ctx` is three wide and may be padded, so it is `\S+`** — the same rule as the other columns
     * — and the trailing space group ends the match immediately before the name.
     */
    private val HEAD = Regex("""^(\S+) +(\d+) +(\d+) +(\S+) +(\S+) +(\S+) +""")

    /** The body's one escaping rule: a newline and a backslash, in that order. */
    fun escaped(text: String): String = text.replace("\\", "\\\\").replace("\n", "\\n")

    /**
     * The inverse, for a reader that has to show a body under its captions.
     *
     * Order matters here and not in [escaped]: a body that legitimately contains the two
     * characters `\` `n` must come back as those two characters, so the pair is recognised
     * before a single backslash is.
     */
    fun unescaped(text: String): String = buildString {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (text[i + 1]) {
                    'n' -> {
                        append('\n')
                        i += 2
                    }

                    '\\' -> {
                        append('\\')
                        i += 2
                    }

                    else -> {
                        append(c)
                        i += 1
                    }
                }
            } else {
                append(c)
                i += 1
            }
        }
    }
}
