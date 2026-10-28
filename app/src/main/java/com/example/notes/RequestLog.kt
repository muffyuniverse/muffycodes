package com.example.notes

import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Every answer the model gave, one line each, in the system project.
 *
 * **Why this exists at all.** The agent's own record says what the model *said*; nothing said what
 * it *cost*. The keepalive made that gap a real hole: a ping throws its answer away, so a ping that
 * silently grew from twenty tokens to five hundred — because a provider ignored `max_tokens`, or
 * because the conversation it re-sent had doubled — looked exactly like a ping that was fine. The
 * numbers were already arriving on every response and were being dropped on the floor.
 *
 * **One file per day, one line per response, columns separated by a tab.** A day per file because
 * that is the unit a person asks about ("what did yesterday cost"), and a line per response because
 * that is the unit the provider bills. Tabs because the log is read by eye in a terminal and by
 * `awk` on a phone, and both want columns rather than JSON — the header line is what makes the
 * columns nameable, so it is written when a file is made rather than in every line.
 *
 * **It is not part of the conversation and never enters the context.** It lives in the system
 * project because that is the folder that belongs to the whole account rather than to one project,
 * and it is listed in [Workspace.APP_IGNORES] so that writing to it never shows up as a change to
 * commit. A log that made the app say "1 changed" would be a log nobody could afford to keep.
 */
object RequestLog {

    /** The folder inside the system project. */
    const val DIR = "requests"

    /**
     * The columns, in order, and the reason the header is a constant rather than a literal.
     *
     * A log whose columns are only in the code that wrote them is a log that has to be reverse
     * engineered later. These names are what a person reads at the top of the file.
     */
    private val COLUMNS = listOf(
        "time",
        "kind",
        "ms",
        "provider",
        "model",
        "prompt",
        "cached",
        "completion",
        "reasoning",
        "cost",
        "finish",
    )

    /**
     * What one response cost, as the provider reported it.
     *
     * **Every field is nullable on purpose.** A provider that omits `usage`, an answer that never
     * arrived, a ping whose body was discarded — all of these are real and none of them is a zero.
     * Writing a zero where the truth is "not reported" would make the log lie in the one direction
     * that matters, because a zero cost is exactly what a cached response looks like.
     */
    data class Stats(
        val kind: Kind,
        val ms: Long,
        val provider: String,
        val model: String,
        val usage: JSONObject?,
        val finish: String?,
    ) {
        enum class Kind(val label: String) {
            /** A turn: the person spoke and the answer was read. */
            TURN("turn"),

            /** The keepalive: the conversation re-sent for the provider's cache. */
            PING("ping"),
        }

        val promptTokens: Int? get() = usage?.optInt("prompt_tokens")?.takeIf { usage.has("prompt_tokens") }
        val completionTokens: Int? get() = usage?.optInt("completion_tokens")?.takeIf { usage.has("completion_tokens") }
        val cost: Double? get() = usage?.optDouble("cost")?.takeIf { usage.has("cost") }

        /** The part of the prompt the provider did not have to read, when it says. */
        val cachedTokens: Int?
            get() = usage?.optJSONObject("prompt_tokens_details")
                ?.let { if (it.has("cached_tokens")) it.optInt("cached_tokens") else null }

        /**
         * How much of the answer was thinking.
         *
         * **This is the column the keepalive needs.** A ping asks for one token, so a ping whose
         * reasoning is large is a provider that did not honour the cap — and that is invisible in
         * every other number here, because the completion count stays small.
         */
        val reasoningTokens: Int?
            get() = usage?.optJSONObject("completion_tokens_details")
                ?.let { if (it.has("reasoning_tokens")) it.optInt("reasoning_tokens") else null }
    }

    /**
     * One line, already formatted, for [stats] at [now].
     *
     * A function of its arguments so the format is testable without a file — the file is the easy
     * part and the columns are the thing that has to stay stable once a person has started reading
     * them.
     */
    internal fun line(now: Long, stats: Stats): String {
        val at = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalTime()
        val time = String.format(
            "%02d:%02d:%02d.%03d",
            at.hour, at.minute, at.second, at.nano / 1_000_000,
        )
        // An absent number is written as nothing, not as 0: see Stats. A dash would be read as a
        // measurement of minus one by half the tools that will ever touch this file.
        fun num(v: Any?): String = v?.toString().orEmpty()
        // **Cost is written plainly, not in the shortest form Java can find.** `Double.toString`
        // renders a small number as "2.41E-5", which is correct, unreadable in a column, and
        // unparseable by anything that expects a decimal - the statistics table reads this file
        // back, so the two have to agree on the shape as well as the value. Ten decimals is enough
        // for a single response on any model this app can be pointed at.
        fun money(v: Double?): String =
            if (v == null) "" else java.util.Locale.UK.let { String.format(it, "%.10f", v) }
        return listOf(
            time,
            stats.kind.label,
            stats.ms.toString(),
            stats.provider,
            stats.model,
            num(stats.promptTokens),
            num(stats.cachedTokens),
            num(stats.completionTokens),
            num(stats.reasoningTokens),
            money(stats.cost),
            stats.finish.orEmpty(),
        ).joinToString("\t")
    }

    /**
     * Writes one response's statistics, making the day's file and its header if they are new.
     *
     * **Called from the door, on the thread that already has the numbers.** A log written from a
     * screen would miss every request made while the app was in the background, which is most of
     * them. Failures are swallowed: a log is evidence about the work, never a reason for the work
     * to fail, and a full disk should not stop an agent.
     */
    fun write(accountDir: File, now: Long, stats: Stats) {
        try {
            val dir = File(File(accountDir, Workspace.SYSTEM_PROJECT), DIR)
            if (!dir.isDirectory && !dir.mkdirs()) return
            val day = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate()
            val file = File(dir, "${day}.log")
            val fresh = !file.isFile || file.length() == 0L
            file.appendText(
                buildString {
                    if (fresh) appendLine(COLUMNS.joinToString("\t"))
                    appendLine(line(now, stats))
                },
            )
        } catch (t: Throwable) {
            // Deliberately silent. See above.
        }
    }

    /** The file a given day's responses are written to. Used by tests and by the screen. */
    fun fileFor(accountDir: File, day: LocalDate): File =
        File(File(File(accountDir, Workspace.SYSTEM_PROJECT), DIR), "$day.log")
}
