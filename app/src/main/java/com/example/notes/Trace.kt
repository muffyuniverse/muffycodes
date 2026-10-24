package com.example.notes

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * What the app was asked to do, what it did about it, and what never got an answer.
 *
 * ## Why this exists
 *
 * A phone has no console. When something on the screen does not work, the only
 * evidence a person can bring is a photograph — and a photograph of a working screen
 * two seconds after a broken tap says nothing about the tap. The bugs this was built
 * for were exactly that shape: a save that silently refused (a stale git lock), a
 * sync that failed behind a message nobody read, a history whose newest version was
 * older than the file. Every one of them looks, in a screenshot, like a normal screen.
 *
 * So: **every interactive element leaves a trace**, and a trace is not a diary — it is
 * a *pair*. A tap is an intent; an intent must be answered by a change or by words.
 * An intent with no answer is a broken sequence, and this file's job is to find those
 * rather than to record everything and leave the reading to a human.
 *
 * ## The shape
 *
 * - [tap] records an intent and remembers it as *waiting*.
 * - [changed] and [said] record a reaction; each answers the newest waiting intent.
 * - [failure] answers it in red, keeping the app's own words.
 * - [report] pairs them up: what was answered, how long it took, and what is still
 *   waiting past [staleMs] — those are the broken ones.
 *
 * Nothing here is allowed to throw. A trace that breaks the app it is watching is
 * worse than no trace, so every entry point swallows its own failure.
 *
 * ## Cost
 *
 * One string concatenation per event and a bounded ring in memory; the file copy is
 * appended on its own thread behind a lock. At ~40 events per second of continuous use
 * this is far below anything a person could notice, and [EVENTS] means the memory
 * cannot grow without a ceiling.
 */
object Trace {

    /** What an event is. */
    enum class Kind {
        /** Something a person did. */
        TAP,

        /** The app's visible state changed. */
        CHANGED,

        /** The app said something. */
        SAID,

        /** The app could not do what was asked. */
        FAIL,

        /** A fact worth keeping that answers nothing. */
        NOTE,
    }

    /** One line of the trace. */
    data class Event(
        val seq: Int,
        val at: Long,
        val kind: Kind,
        val what: String,
        val detail: String = "",
        /** The [Kind.TAP] this answers, when it answers one. */
        val forId: Int? = null,
    )

    /** An intent. */
    data class Act(val id: Int, val at: Long, val what: String, val detail: String = "")

    /**
     * An intent nothing answered.
     *
     * [waitedMs] is how long it had been waiting when the report was asked for, so the
     * same act reads differently as time passes — which is the honest way to show it:
     * a tap half a second old is not a bug yet.
     */
    data class Broken(val act: Act, val waitedMs: Long) {
        val line: String
            get() = "${act.what}${if (act.detail.isEmpty()) "" else " (${act.detail})"} — " +
                "no change and no message after ${waitedMs} ms"
    }

    /** Everything the screen shows, worked out once. */
    data class Report(
        val lines: List<String>,
        val waiting: List<Broken>,
        val failures: List<String>,
        val answered: Int,
        val total: Int,
    ) {
        /** The one sentence at the top of the screen. */
        val summary: String
            get() = when {
                total == 0 -> "Nothing has happened yet."
                waiting.isEmpty() && failures.isEmpty() -> "$answered of $total answered, nothing stuck."
                waiting.isEmpty() -> "$answered of $total answered, ${failures.size} failed."
                else -> "$answered of $total answered, ${waiting.size} still waiting, " +
                    "${failures.size} failed."
            }

        val isHealthy: Boolean get() = waiting.isEmpty() && failures.isEmpty()
    }

    /** How many events are kept in memory. Older ones fall off the end. */
    const val EVENTS = 600

    /** How many unanswered intents are remembered before the oldest is dropped. */
    const val WAITING = 40

    /** The file is trimmed to [FILE_KEEP] once it passes [FILE_LIMIT]. */
    const val FILE_LIMIT = 256 * 1024
    const val FILE_KEEP = 128 * 1024

    /**
     * How long an intent may go unanswered before it counts as broken.
     *
     * Two seconds is longer than any tap in this app takes to reach the screen — the
     * slowest thing it does is a git commit on a worker — and short enough that a
     * person looking at a dead screen sees it flagged while they are still puzzled.
     */
    var staleMs: Long = 2_000

    private val guard = Any()

    private val events = ArrayDeque<Event>()

    /** Intents with no answer yet, oldest first. */
    private val waiting = LinkedHashMap<Int, Act>()

    private var next = 1
    private var answered = 0

    // The file copy, on its own thread so that no reply on the screen waits for a
    // write. A single thread keeps the lines in the order they happened.
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "trace").apply { isDaemon = true }
    }
    private val writeGuard = Any()

    @Volatile
    private var file: File? = null

    /**
     * A formatter per thread.
     *
     * `SimpleDateFormat` keeps its state in the formatter, so sharing one between the
     * screen and the writing thread produces interleaved digits — a timestamp that is
     * quietly wrong is worse than none, because it is the field a person trusts.
     */
    private val clock = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    private fun stamp(at: Long): String = clock.get()!!.format(Date(at))

    /** How many taps have happened. The count a report is "N of M" against. */
    private var acts = 0

    /**
     * Sends the durable copy of the trace to [target].
     *
     * A trace that only lives in memory is lost exactly when it is wanted: when the app
     * is closed after going wrong. The account's `.muffy/` is where the app keeps what
     * it owns, and it is outside both histories, so this cannot appear in a version.
     */
    fun attach(target: File?) {
        file = target
    }

    /** Where the trace is being written, for the screen to say. */
    val where: String? get() = file?.absolutePath

    /**
     * Something a person did.
     *
     * [what] is the element's own name — "Save", "Sync testrepo", "row: milk.txt" — so
     * that a report reads like the screen it came from rather than like a log.
     */
    fun tap(what: String, detail: String = ""): Int {
        return try {
            val act: Act
            synchronized(guard) {
                val id = next++
                acts++
                act = Act(id, now(), what, detail)
                waiting[id] = act
                if (waiting.size > WAITING) {
                    // The oldest unanswered intent goes. Dropping it loses a broken
                    // sequence, so it is written down before it is forgotten — the file
                    // keeps what the buffer cannot.
                    val oldest = waiting.values.first()
                    waiting.remove(oldest.id)
                    appendLocked(Event(0, now(), Kind.NOTE, "dropped", oldest.what))
                }
                appendLocked(Event(id, act.at, Kind.TAP, what, detail))
            }
            write(act.at, "TAP", what, detail)
            act.id
        } catch (t: Throwable) {
            0
        }
    }

    /** The app's own words, which answer whatever was being served. */
    fun said(text: String) {
        try {
            val at = now()
            val forId = synchronized(guard) {
                val id = answerLocked()
                appendLocked(Event(next++, at, Kind.SAID, "said", text, id))
                id
            }
            write(at, "SAID", "said", text, forId)
        } catch (t: Throwable) {
            // Nothing.
        }
    }

    /**
     * The app's visible state changed, which answers whatever was being served.
     *
     * [what] names the change well enough to read ("folder milk/ (3 entries)"), because
     * "changed" on its own answers a question nobody asked.
     */
    fun changed(what: String) {
        try {
            val at = now()
            val forId = synchronized(guard) {
                val id = answerLocked()
                appendLocked(Event(next++, at, Kind.CHANGED, "state", what, id))
                id
            }
            write(at, "CHANGED", "state", what, forId)
        } catch (t: Throwable) {
            // Nothing.
        }
    }

    /** The app could not do what was asked, in its own words. */
    fun failure(said: String, what: String = "failed") {
        try {
            val at = now()
            val forId = synchronized(guard) {
                val id = answerLocked()
                appendLocked(Event(next++, at, Kind.FAIL, what, said, id))
                id
            }
            write(at, "FAIL", what, said, forId)
        } catch (t: Throwable) {
            // Nothing.
        }
    }

    /** A fact worth having that answers nothing — a count, a branch, a path. */
    fun note(what: String, detail: String = "") {
        try {
            val at = now()
            synchronized(guard) { appendLocked(Event(next++, at, Kind.NOTE, what, detail)) }
            write(at, "NOTE", what, detail)
        } catch (t: Throwable) {
            // Nothing.
        }
    }

    /**
     * Answers the newest intent that is still waiting, and says which it was.
     *
     * The newest, because the UI serves one tap at a time: an intent older than the one
     * just served either got its answer already or never will, and pretending a later
     * change belongs to an earlier tap would hide exactly the broken sequence this is
     * here to find.
     */
    private fun answerLocked(): Int? {
        val act = waiting.values.lastOrNull() ?: return null
        waiting.remove(act.id)
        answered++
        return act.id
    }

    private fun appendLocked(event: Event) {
        events.addLast(event)
        while (events.size > EVENTS) events.removeFirst()
    }

    /** Everything, worked out. */
    fun report(at: Long = now()): Report {
        return try {
            val lines: List<String>
            val waitingNow: List<Broken>
            val failures: List<String>
            val answeredNow: Int
            val totalNow: Int
            synchronized(guard) {
                lines = events.reversed().map { e ->
                    val took = e.forId?.let { id ->
                        events.firstOrNull { it.seq == id }
                            ?.let { "  +${e.at - it.at}ms" }
                            .orEmpty()
                    }.orEmpty()
                    val who = e.forId?.let { "  ← #$it" }.orEmpty()
                    "${stamp(e.at)}${took.padEnd(9)} ${e.kind.name.padEnd(7)} ${e.what}" +
                        (if (e.detail.isEmpty()) "" else "  ${e.detail}") + who
                }
                waitingNow = waiting.values.map { Broken(it, at - it.at) }
                failures = events.filter { it.kind == Kind.FAIL }
                    .reversed()
                    .map { "FAIL ${it.what}: ${it.detail}" }
                answeredNow = answered
                totalNow = acts
            }
            Report(lines, waitingNow, failures, answeredNow, totalNow)
        } catch (t: Throwable) {
            Report(emptyList(), emptyList(), emptyList(), 0, 0)
        }
    }

    /** The broken sequences only — what "is the app reacting?" is asking. */
    fun broken(at: Long = now()): List<Broken> =
        report(at).waiting.filter { it.waitedMs > staleMs }

    /**
     * A report, frozen at the moment somebody decided something was wrong.
     *
     * **Frozen, not read later.** The dialog the person then types in is itself a
     * stream of taps, and a report assembled after they close it would show a trace in
     * which the last thing that happened is "report" — the one event that is certainly
     * not the bug. So the state is taken on the press, and everything after it is
     * text the person chose to add.
     */
    data class Frozen(
        val report: Report,
        val at: Long,
        /** The sentences a report should carry that the trace cannot know. */
        val context: List<Pair<String, String>>,
    ) {
        /** The taps with no answer, as sentences. Empty when the app was reacting. */
        fun stuck(staleAfter: Long = Trace.staleMs): List<Broken> =
            report.waiting.filter { it.waitedMs > staleAfter }
    }

    /**
     * Takes the report now.
     *
     * [context] is what the screen knows and the trace does not: which screen, which
     * account, which folder, which file. It is passed in rather than read from a view
     * model here, so that this file stays a recorder and never reaches back into the
     * app it is watching.
     */
    fun freeze(context: List<Pair<String, String>> = emptyList()): Frozen =
        Frozen(report(), now(), context)

    /**
     * The report as one piece of text a person can send somebody.
     *
     * The order is deliberate: what they say first, because it is the only part with
     * their intent in it; then what the app already knows was unanswered; then the
     * context; then the log. A reader should be able to stop after the first two
     * sections and know what to look at.
     */
    fun reportText(
        frozen: Frozen,
        note: String,
        staleAfter: Long = Trace.staleMs,
    ): String {
        val stuck = frozen.stuck(staleAfter)
        val said = note.trim()
        return buildString {
            appendLine("Muffy Codes — problem report")
            appendLine()
            appendLine(if (said.isEmpty()) "(nothing written down)" else said)
            appendLine()
            if (stuck.isEmpty()) {
                appendLine("Nothing was waiting for an answer when this was sent.")
            } else {
                appendLine("Taps with no answer at the moment of the report:")
                stuck.forEach { appendLine("  · ${it.line}") }
            }
            if (frozen.report.failures.isNotEmpty()) {
                appendLine()
                appendLine("Refused:")
                frozen.report.failures.take(20).forEach { appendLine("  · $it") }
            }
            if (frozen.context.isNotEmpty()) {
                appendLine()
                appendLine("Where:")
                frozen.context.forEach { (k, v) -> appendLine("  $k: $v") }
            }
            appendLine()
            appendLine("— the app's own record, newest first (frozen at ${stamp(frozen.at)}) —")
            frozen.report.lines.forEach { appendLine(it) }
            appendLine()
            appendLine(frozen.report.summary)
            where?.let { appendLine("Also in $it") }
        }
    }

    /** The whole trace as one piece of text, for sharing. */
    fun dump(at: Long = now()): String {
        val r = report(at)
        val stuck = r.waiting.filter { it.waitedMs > staleMs }
        return buildString {
            appendLine("Muffy Codes — what the app did")
            appendLine(r.summary)
            appendLine()
            if (stuck.isNotEmpty()) {
                appendLine("Broken sequences (a tap with no answer):")
                stuck.forEach { appendLine("  · ${it.line}") }
                appendLine()
            }
            if (r.failures.isNotEmpty()) {
                appendLine("Failures:")
                r.failures.take(40).forEach { appendLine("  · $it") }
                appendLine()
            }
            appendLine("Newest first:")
            r.lines.forEach { appendLine(it) }
            where?.let {
                appendLine()
                appendLine("Also written to $it")
            }
        }
    }

    /** Forgets everything, including what was still waiting. */
    fun clear() {
        try {
            synchronized(guard) {
                events.clear()
                waiting.clear()
                answered = 0
                acts = 0
                next = 1
            }
            write(now(), "NOTE", "cleared", "the trace was cleared by hand")
        } catch (t: Throwable) {
            // Nothing.
        }
    }

    /** Only for tests: a fresh slate with the file copy untouched. */
    internal fun resetForTest() = clear()

    private fun now(): Long = System.currentTimeMillis()

    // ---- the durable copy ---------------------------------------------------

    private fun write(at: Long, kind: String, what: String, detail: String, forId: Int? = null) {
        val target = file ?: return
        val said = buildString {
            append(stamp(at))
            append("  ")
            append(kind.padEnd(7))
            append(" ")
            append(what)
            if (detail.isNotEmpty()) append("  ").append(detail)
            if (forId != null) append("  <- #").append(forId)
            append('\n')
        }
        try {
            writer.execute {
                try {
                    synchronized(writeGuard) {
                        target.parentFile?.mkdirs()
                        target.appendText(said)
                        trimIfLong(target)
                    }
                } catch (t: Throwable) {
                    // A trace that cannot be written is not a reason to stop the app.
                }
            }
        } catch (t: Throwable) {
            // The executor is shutting down, or the phone is out of threads.
        }
    }

    /**
     * Keeps the file from growing forever: past [FILE_LIMIT] it keeps the last
     * [FILE_KEEP] bytes, on a line boundary so that no line is cut in half.
     */
    private fun trimIfLong(target: File) {
        if (target.length() <= FILE_LIMIT) return
        val all = target.readText()
        val kept = all.takeLast(FILE_KEEP)
        val from = kept.indexOf('\n').let { if (it < 0) 0 else it + 1 }
        target.writeText("… earlier lines were dropped …\n" + kept.substring(from))
    }
}
