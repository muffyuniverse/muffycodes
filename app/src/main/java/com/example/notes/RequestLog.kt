package com.example.notes

import org.json.JSONObject
import java.io.File
import java.time.Instant
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
 * **One file, appended to for ever, and no day in its name.** The first version wrote a file per
 * local day, and that was the only place in the whole design a timezone appeared: a file named
 * after a local day spans a run of UTC hours that starts and ends somewhere else, so a table
 * bucketed by any other zone has to read its neighbours — and a retention rule that drops a day
 * drops hours another zone still needs. Neither failure is visible in a picture.
 *
 * A single file has no boundary to get wrong. Each row carries **an instant**, the table converts
 * it into whatever zone it is displaying, and retention is arithmetic on the same instants. The
 * per-day file's one advantage was being readable by eye, and the table is that reading.
 *
 * **It is not part of the conversation and never enters the context.** It lives in the system
 * project because that is the folder that belongs to the whole account rather than to one project,
 * and it is listed in [Workspace.APP_IGNORES] so that writing to it never shows up as a change to
 * commit. A log that made the app say "1 changed" would be a log nobody could afford to keep.
 */
object RequestLog {

    /** The folder inside the system project. */
    const val DIR = "requests"

    /** The one file. */
    const val FILE = "requests.log"

    /**
     * The columns, in order, and the reason the header is a constant rather than a literal.
     *
     * A log whose columns are only in the code that wrote them is a log that has to be reverse
     * engineered later. These names are what a person reads at the top of the file.
     */
    private val COLUMNS = listOf(
        // **The instant, and the only column with no timezone in it.** Everything the table does —
        // bucketing by hour, day, month — is arithmetic on this number, and arithmetic on an
        // instant gives the same answer wherever the phone is standing. A clock time does not:
        // "09:14" is two different instants in two zones, so a log read after a flight would have
        // rows that could not be placed.
        "utc",
        // The next two are for a person reading the file by eye. Nothing computes from them.
        "time",
        "day",
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
    internal fun line(now: Long, stats: Stats, zone: ZoneId = ZoneId.systemDefault()): String {
        val at = Instant.ofEpochMilli(now).atZone(zone)
        val time = String.format(
            "%02d:%02d:%02d.%03d",
            at.hour, at.minute, at.second, at.nano / 1_000_000,
        )
        // An absent number is written as nothing, not as 0: see Stats. A dash would be read as a
        // measurement of minus one by half the tools that will ever touch this file.
        fun num(v: Any?): String = v?.toString().orEmpty()
        // **Cost is written plainly, not in the shortest form Java can find.** `Double.toString`
        // renders a small number as "2.41E-5", which is correct, unreadable in a column, and
        // unparseable by anything that expects a decimal — the statistics table reads this file
        // back, so the two have to agree on the shape as well as the value.
        fun money(v: Double?): String =
            if (v == null) "" else String.format(java.util.Locale.UK, "%.10f", v)
        return listOf(
            now.toString(),
            time,
            at.toLocalDate().toString(),
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
     * Writes one response's statistics, then applies the retention rule.
     *
     * **Called from the door, on the thread that already has the numbers.** A log written from a
     * screen would miss every request made while the app was in the background, which is most of
     * them. Failures are swallowed: a log is evidence about the work, never a reason for the work
     * to fail, and a full disk should not stop an agent.
     *
     * **The retention check happens here rather than on a timer**, because the log grows on every
     * response: the moment to notice it has grown too large is the moment it grows. A timer would
     * have to pick a first-run delay and would prune an idle account on a schedule that meant
     * nothing.
     */
    fun write(
        accountDir: File,
        now: Long,
        stats: Stats,
        keepDays: Int = 0,
        zone: ZoneId = ZoneId.systemDefault(),
    ) {
        try {
            val dir = File(File(accountDir, Workspace.SYSTEM_PROJECT), DIR)
            if (!dir.isDirectory && !dir.mkdirs()) return
            val file = File(dir, FILE)
            val fresh = !file.isFile || file.length() == 0L
            file.appendText(
                buildString {
                    if (fresh) appendLine(COLUMNS.joinToString("\t"))
                    appendLine(line(now, stats, zone))
                },
            )
            prune(file, now, keepDays)
        } catch (t: Throwable) {
            // Deliberately silent. See above.
        }
    }

    /**
     * Drops rows older than [keepDays], and keeps the newest whatever it says.
     *
     * **A row is dropped by its instant**, which is the same rule the table buckets by, so a row is
     * never counted in one place and retained by another. A line whose instant will not parse is
     * kept: it is a line somebody wrote, and discarding what cannot be read is how a log loses its
     * own evidence.
     *
     * **[keepDays] of zero keeps everything**, which is the default: the whole purpose of this file
     * is being able to ask what something cost a year later.
     */
    private fun prune(file: File, now: Long, keepDays: Int) {
        if (keepDays <= 0 || !file.isFile) return
        val cutoff = now - keepDays.toLong() * 86_400_000L
        val lines = try {
            file.readLines()
        } catch (t: Throwable) {
            return
        }
        if (lines.isEmpty()) return
        val body = lines.drop(1).filter { raw ->
            val at = raw.substringBefore('\t').toLongOrNull()
            at == null || at >= cutoff
        }
        // Nothing to do, and no rewrite: a prune that rewrites an unchanged file is a prune that
        // can lose an append for no reason.
        if (body.size == lines.size - 1) return
        try {
            // **Written beside and renamed over**, because the door appends from another thread. A
            // rewrite in place could drop the response written between the read and the write -
            // which is exactly the response somebody would be looking for.
            val temp = File(file.parentFile, FILE + ".tmp")
            temp.writeText((listOf(lines.first()) + body).joinToString("\n", postfix = "\n"))
            if (!temp.renameTo(file)) temp.delete()
        } catch (t: Throwable) {
            // A prune that failed leaves the log whole, which is the right failure.
        }
    }

    /** The log itself. Used by tests and by the screen. */
    fun fileFor(accountDir: File): File =
        File(File(File(accountDir, Workspace.SYSTEM_PROJECT), DIR), FILE)
}
