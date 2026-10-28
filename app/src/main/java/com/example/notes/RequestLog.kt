package com.example.notes

import org.json.JSONObject
import java.io.File
import java.time.Instant

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

    /** The `span` of a line that covers one response. Everything else is an aggregate. */
    const val SPAN_RAW = "raw"

    /**
     * The `kind` of an aggregate row.
     *
     * **An aggregate has no kind of its own**, because it sums turns and keepalives whose kinds
     * differed. Writing any one of them would be false; writing an empty cell would make a reader
     * think the line was corrupt. `all` is a word for the thing that is actually true.
     *
     * It is also what the parser keys on: a `kind` it does not recognise returns null, so `all`
     * appearing in the kind column is the marker that says "read this by its span instead".
     */
    const val KIND_ALL = "all"

    /**
     * The `span` values an aggregate may have, narrowest first.
     *
     * **No `total`**: there is no total *row*, deliberately. A stored total would be a second,
     * contradictory fact about the same money the moment any hour is rolled again. The total column
     * is the sum of the year rows, computed when the table is drawn.
     */
    val AGGREGATE_SPANS = listOf("hour", "day", "month", "year")

    /**
     * The columns, in order, and the reason the header is a constant rather than a literal.
     *
     * A log whose columns are only in the code that wrote them is a log that has to be reverse
     * engineered later. These names are what a person reads at the top of the file.
     */
    private val COLUMNS = listOf(
        // **One time column, and it is an instant.**
        //
        // The first version also wrote the local clock time and the local day, "for a person
        // reading the file by eye". They are gone, and the reason is not that they were unused -
        // they were never read — but that **they could be wrong**. Local time is a function of the
        // instant and the zone the phone is in *now*; written down, it becomes a second fact that
        // stops agreeing with the first the moment somebody flies somewhere. A file holding two
        // contradictory times is worse than a file holding one that needs converting, because a
        // reader has to know which to trust.
        //
        // Everything local - the hour a bucket is labelled with, the day it falls on, the caption -
        // is computed when the table is drawn. `2026-10-28T11:14:22.301Z` is readable at a glance
        // and has no zone to be misread in.
        "utc",
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
        // **What had arrived, for a stall.** Blank on every other row, where `usage` is the better
        // answer. On a stall these are the only numbers there are, and the difference between them
        // is the difference between "the model was thinking" and "the model was writing".
        "said",
        "thinking",
        // **What this line covers**, for the rolled-up rows that come later.
        //
        // `raw` is one response. `hour`/`day`/`month`/`year` are aggregates that cover many, and
        // they are what keep a month's column from re-reading a month of lines.
        //
        // **At the end of the line, not in front, and that is a compatibility decision.** The plan
        // said "in front of the existing columns", which reads better - the shape of the row is the
        // first thing to know. But the first cell is the instant, and every parser here starts by
        // reading it: putting `span` in front would make `Instant.parse("raw")` throw on every line
        // already on the phone, and the whole history would vanish. Appending means an old line
        // simply lacks the columns and is read as `raw`/1, which is what it is.
        "span",
        // **How many requests the row covers, and how many of them reported a cost.**
        //
        // Not derivable from the numbers: `cost` is a sum, so "cost per request" needs a count of
        // requests, and a sum alone cannot say how many responses contributed to it. Without these
        // two a rollup's average is silently wrong in the plausible direction - it divides a large
        // sum by one, and the answer still looks like money.
        "requests",
        "cost reported",
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
        /**
         * How much had arrived when the stream died, for a stall.
         *
         * Null for a finished request, where `usage` is the better answer. For a stall this is the
         * only evidence there is: it is what distinguishes a connection that died mid-answer from
         * one that never started, and it is the closest thing to the billed amount that exists.
         */
        val received: Received? = null,
    ) {
        /** What a stalled stream had produced, in the two things the app counts as it arrives. */
        data class Received(val said: Int, val thinking: Int)
        enum class Kind(val label: String) {
            /** A turn: the person spoke and the answer was read. */
            TURN("turn"),

            /** The keepalive: the conversation re-sent for the provider's cache. */
            PING("ping"),

            /**
             * An answer that began and then stopped arriving.
             *
             * **A row of its own, and the reason is money.** A refusal or an ack timeout produces
             * no output, and OpenRouter's zero-completion insurance means it is not billed - so
             * those leave no row and the table stays honest. A stall is not that: the model had
             * already generated tokens, and whether they were billed depends on whether the
             * upstream supports cancellation. Some do and some do not, and for the ones that do
             * not, the whole generation is charged after the connection dies.
             *
             * So this row is written with `usage` absent, which leaves every token and cost column
             * blank rather than showing a zero. A zero would say "free", and the honest answer is
             * "we do not know" - and a blank `reasoning` would say "nothing was produced", when
             * what is true is that something was, we just never received the accounting for it.
             */
            STALL("stall"),
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
            // **The instant, in the one form that says so.** ISO-8601 with a trailing Z is
            // readable at a glance and cannot be mistaken for a local time.
            Instant.ofEpochMilli(now).toString(),
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
            stats.received?.said?.toString().orEmpty(),
            stats.received?.thinking?.toString().orEmpty(),
            // A line written as it happens is one raw response, and it reported a cost or it did
            // not. The rolled-up rows are written by the compactor, which supplies its own values.
            SPAN_RAW,
            "1",
            // **Through `stats.cost`, not through `usage` again.** The two must not be able to
            // disagree: if this re-checked the JSON while `line()`'s cost column used the accessor,
            // a future change to one would leave the log saying a cost was reported and showing
            // none, or the reverse.
            if (stats.cost != null) "1" else "0",
        ).joinToString("\t")
    }

    /**
     * One aggregate row, as a line: the same sixteen cells as [line], mostly empty.
     *
     * **Blank where a sum has no meaning.** An aggregate covers many responses with different
     * providers, models, durations and finish reasons, and there is no true single value for any of
     * them. Writing zeros would say "zero milliseconds" and "no provider"; writing blanks says what
     * is true, which is that this line is not about any one response.
     *
     * The `kind` is [KIND_ALL] rather than one of the real kinds, because an hour holds turns and
     * keepalives together and naming one of them would be false.
     */
    internal fun aggregateLine(rolled: RequestStats.Rolled): String {
        fun num(v: Any?): String = v?.toString().orEmpty()
        fun money(v: Double?): String =
            if (v == null) "" else String.format(java.util.Locale.UK, "%.10f", v)
        return listOf(
            // **The start of the span**, which is the one instant an aggregate is about: every row
            // it covers falls at or after it and before the next unit begins.
            Instant.ofEpochMilli(rolled.fromMs).toString(),
            KIND_ALL,
            "",
            "",
            "",
            num(rolled.prompt.takeIf { it != 0L }),
            num(rolled.cached.takeIf { it != 0L }),
            num(rolled.completion.takeIf { it != 0L }),
            num(rolled.reasoning.takeIf { it != 0L }),
            money(rolled.cost.takeIf { rolled.costReported > 0 }),
            "",
            num(rolled.said.takeIf { it != 0L }),
            num(rolled.thinking.takeIf { it != 0L }),
            rolled.span,
            rolled.requests.toString(),
            rolled.costReported.toString(),
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
    ) {
        try {
            val dir = File(File(accountDir, Workspace.SYSTEM_PROJECT), DIR)
            if (!dir.isDirectory && !dir.mkdirs()) return
            val file = File(dir, FILE)
            val fresh = !file.isFile || file.length() == 0L
            file.appendText(
                buildString {
                    if (fresh) appendLine(COLUMNS.joinToString("\t"))
                    appendLine(line(now, stats))
                },
            )
            // **No retention here, deliberately.** This runs once per response, and a retention
            // pass reads the whole file - at ten thousand requests a day that is the entire log
            // re-read ten thousand times. Raw rows are now bounded by [compact], which drops them
            // the moment they are rolled, so there is nothing for a per-write prune to do.
        } catch (t: Throwable) {
            // Deliberately silent. See above.
        }
    }

    /** One line of the file: what it says, and the characters it already has. */
    private class Entry(val row: RequestStats.Row?, val text: String)

    /**
     * Rolls every closed hour into one aggregate row, drops the raw rows it covers, rebuilds the
     * coarser levels, and applies the retention rule — **in one pass, with one rewrite**.
     *
     * **The one destructive operation on this file**, so the order is the whole design:
     *
     * 1. **Roll** every closed hour from its raw rows. The hour in progress is left alone: an
     *    aggregate for it would be contradicted by the next request.
     * 2. **Rebuild** `day`, `month` and `year` from the hour rows.
     * 3. **Then** apply [keepDays] to the hour rows.
     *
     * **Step 3 must come after step 2, and that is not a detail.** Rebuilding a day from hours that
     * retention has already dropped would delete the day: the hours are gone, so the rebuilt day
     * covers only what is left of it, and the rest of the history disappears one level at a time.
     * Deriving first and pruning second means an old year keeps its year, month and day rows after
     * its hours have gone - the columns still answer, they just cannot be broken down further.
     *
     * **A line this code cannot parse is written back untouched** - not parsed into a Row and
     * rendered again, which would silently drop whatever it held.
     *
     * Returns the number of raw rows removed, so a test can tell "did nothing" from "worked".
     */
    fun compact(accountDir: File, now: Long, keepDays: Int = 0): Int {
        val file = fileFor(accountDir)
        if (!file.isFile) return 0
        return try {
            val (header, entries) = readEntries(file) ?: return 0
            val parsed = entries.filter { it.row != null }

            // ---- 1. roll the closed hours ----
            val rawEntries = parsed.filter { it.row!!.span == SPAN_RAW }
            val closed = RequestStats.closable(rawEntries.map { it.row!! }, "hour", now)

            // **Identified by the line's own text**, not by its instant: two responses can share a
            // millisecond, and a comparison on time would drop both when one was meant. The text is
            // the identity the file itself uses.
            val rolledText = closed.values.flatten()
                .map { rolled -> rawEntries.first { it.row === rolled }.text }
                .toSet()
            val removed = rolledText.size

            val freshHours: List<String> = closed.mapNotNull { (from, group) ->
                RequestStats.rollup(group, "hour", from)?.let(::aggregateLine)
            }
            // Existing hour rows, plus the ones just built. Raw rows in the open hour survive.
            val hours: List<Entry> = parsed.filter {
                it.row!!.span == "hour" || (it.row.span == SPAN_RAW && it.text !in rolledText)
            }

            // ---- 2. rebuild the coarser levels from the hours ----
            val rebuilt = rebuild(hours.mapNotNull { it.row } + freshHours.mapNotNull { RequestStats.parse(it) })

            // ---- 3. then bound the hours by age ----
            val all = hours + rebuilt.map { Entry(RequestStats.parse(it), it) }
            val kept = prune(all, now, keepDays)

            writeWhole(
                file,
                header,
                // **Unreadable lines first**, because they are kept verbatim and belong to no level.
                entries.filter { it.row == null }.map { it.text } +
                    // Then every level the compaction now owns: the surviving hours (already
                    // pruned) and the coarser rows derived from them.
                    kept.map { it.text },
            )
            removed
        } catch (t: Throwable) {
            // A compaction that failed leaves the log whole, which is the right failure.
            0
        }
    }

    /**
     * The `day`, `month` and `year` rows that the given `hour` rows imply.
     *
     * **Derived, not incrementally maintained.** Each level is recomputed from the level below it,
     * so `day` is the sum of its hours by construction rather than a second opinion. That is what
     * makes a change of semantics - and the windows in `docs/PLAN-request-history.md` §1 were
     * revised twice - a recombination rather than a migration.
     *
     * **Only units that can be proved from the hours in hand are rebuilt.** After retention has
     * dropped a year of hours, recomputing that year's days from what is left would produce days
     * covering a fraction of themselves - history deleted a level at a time. A unit is rebuilt only
     * when every hour it has was available to the rebuild, which for retention means: the unit is
     * entirely at or after the oldest hour still present.
     */
    private fun rebuild(hours: List<RequestStats.Row>): List<String> {
        if (hours.isEmpty()) return emptyList()
        val oldest = hours.minOf { it.atMs }
        val fresh = mutableListOf<String>()
        var level = hours
        for (span in listOf("day", "month", "year")) {
            val closed = RequestStats.closable(level, span, Long.MAX_VALUE)
                // Only units whose finer rows are all present: a unit that starts before the oldest
                // hour we have may have lost some.
                .filterKeys { start -> start >= oldest }
            val lines = closed.mapNotNull { (from, group) ->
                RequestStats.rollup(group, span, from)?.let(::aggregateLine)
            }
            fresh += lines
            level = lines.mapNotNull { RequestStats.parse(it) }
        }
        return fresh
    }

    /**
     * Every line of the file, with its header, or null when there is nothing to read.
     *
     * **Streamed.** The compactor is the second thing to read this file and it must not be the one
     * allocation that undoes the streaming read: a year at ten thousand requests a day is millions
     * of lines. `useLines` holds one at a time.
     */
    private fun readEntries(file: File): Pair<String, List<Entry>>? {
        val entries = ArrayList<Entry>()
        var header: String? = null
        file.useLines { lines ->
            for ((i, text) in lines.withIndex()) {
                if (i == 0) {
                    header = text
                } else if (text.isNotBlank()) {
                    entries += Entry(RequestStats.parse(text), text)
                }
            }
        }
        return header?.let { it to entries }
    }

    /**
     * The whole file, beside it and renamed over.
     *
     * **The only way this file is ever rewritten.** A write in place that stopped halfway would
     * leave a record whose last row is half a row, and this file is evidence: a reader must see
     * either the old one whole or the new one whole.
     */
    private fun writeWhole(file: File, header: String, body: List<String>) {
        val temp = File(file.parentFile, FILE + ".tmp")
        temp.writeText((listOf(header) + body).joinToString("\n", postfix = "\n"))
        if (!temp.renameTo(file)) temp.delete()
    }

    /**
     * Drops `hour` rows older than [keepDays], and **keeps every coarser row whatever its age**.
     *
     * **What the knob bounds is the history the table can show in detail**, not the file's size.
     * The pyramid means an old year costs **one row**, while its ten thousand hours cost eight
     * thousand — so dropping the hours loses almost all the bytes and almost none of the
     * information: the month and year columns still answer, they just cannot be broken down
     * further. Dropping the year rows instead would lose the total column to save one line.
     *
     * **[keepDays] of zero keeps everything**, which is the default. With the pyramid that is
     * ~1.4 MB a year against ~420 MB of raw at ten thousand requests a day, so "everything" is a
     * reasonable default for the first time.
     *
     * **Called from [compact], never from a write.** A retention pass reads the whole file, and
     * doing that once per response is what this used to do: at ten thousand requests a day the
     * entire log was re-read ten thousand times. Compaction already reads the file and already
     * rewrites it, so the retention rides along for free.
     *
     * A line whose instant will not parse, or whose span this code does not recognise, is **kept**:
     * it is a line somebody wrote, and discarding what cannot be read is how a log loses its own
     * evidence.
     */
    private fun prune(entries: List<Entry>, now: Long, keepDays: Int): List<Entry> {
        if (keepDays <= 0) return entries
        val cutoff = now - keepDays.toLong() * 86_400_000L
        return entries.filter { entry ->
            // **Only hour rows are bounded.** Everything above them is cheap and everything below
            // them is already gone, so the hours are the only level where age costs anything.
            val row = entry.row ?: return@filter true
            if (row.span != "hour") return@filter true
            row.atMs >= cutoff
        }
    }

    /** The log itself. Used by tests and by the screen. */
    fun fileFor(accountDir: File): File =
        File(File(File(accountDir, Workspace.SYSTEM_PROJECT), DIR), FILE)
}
