package com.example.notes

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The one shape of a row, for every file this app writes.
 *
 * `time id kind name detail` — fixed columns, one line per event, and the same formatting
 * whether the row is the app's own trace or the agent's record of a turn. The two files are
 * read by the same habits (`grep`, a shell, an eye on a phone), so they are formatted by the
 * same code: a second formatter is a second thing to keep in step, and it would drift on the
 * one detail that matters most, the escaping of a body that has newlines in it.
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
     * One row, in the fixed columns.
     *
     * The id is padded to four so that a file read with its eyes has its columns straight,
     * and the kind is padded to seven for the same reason. [name] is never padded: it is the
     * one column whose width cannot be known, and padding it would push the body out of
     * alignment for the long names and not the short ones.
     */
    fun row(at: Long, id: Int, kind: String, name: String, detail: String): String =
        row(stamp(at), id, kind, name, detail)

    /**
     * The same row from a stamp that is already text.
     *
     * Needed by anything that **rewrites** a file it did not read through this class: the
     * agent's session is truncated at a burst boundary, and a row that came back with a fresh
     * timestamp would say the whole conversation happened at the moment it was trimmed.
     */
    fun row(stamp: String, id: Int, kind: String, name: String, detail: String): String =
        buildString {
            append(stamp)
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
     * The same line, from a detail that is **already escaped**.
     *
     * For a row that came back out of a file: its detail column is the escaped form, and putting
     * it through [escaped] a second time doubles every backslash. A trim that did that would
     * rewrite the two characters backslash-n as four, and every trim would add two more — the
     * file drifting one character at a time away from what was actually recorded.
     */
    fun rawRow(stamp: String, id: Int, kind: String, name: String, escapedDetail: String): String =
        buildString {
            append(stamp)
            append(' ')
            append(id.toString().padStart(4))
            append(' ')
            append(kind.padEnd(7))
            append(' ')
            append(name)
            if (escapedDetail.isNotEmpty()) {
                append("  ")
                append(escapedDetail)
            }
        }

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
