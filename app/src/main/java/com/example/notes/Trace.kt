package com.example.notes

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * What the app was asked to do, and what answered — one line per event, in one file.
 *
 * ## The shape, and why this one
 *
 * ```
 * 16:03:11.412  41 TAP      open-file  milk.txt
 * 16:03:11.418  42 TAP      Save       milk.txt
 * 16:03:11.455  42 CHANGED  state      at / · 3 rows · milk.txtL
 * 16:03:20.001   0 REPORT   I-pressed-Save-and-nothing-appeared
 * ```
 *
 * Fixed columns: **time, id, kind, name, detail**. `id` is the second field precisely so
 * that a shell can count it:
 *
 * ```
 * awk '$3=="TAP"{print $2}' trace.log | sort | uniq -c | awk '$1==1'
 * ```
 *
 * That prints the ids of every tap that appears exactly once — a thing a person asked
 * for, with nothing that answered. **That is what "the app didn't react" is**, and it is
 * a query rather than a judgement.
 *
 * ## What the earlier version got wrong
 *
 * It had four artifacts: a ring in memory, an append-only file in the account, a digest
 * rewritten into Downloads every four seconds, and a separate report document on the bug
 * button. Reading "what did the app do" meant knowing which of four things to open, and
 * the digest destroyed its own history every four seconds — so an event seen a moment
 * late had already been overwritten. Three formats, one truth. This is one format.
 *
 * ## What a report is now
 *
 * **A line in the same stream** (`REPORT`, id 0, the person's words as the detail).
 * Everything above it is the context, because a log is ordered. There is no snapshot to
 * freeze and no second document to write: the file already holds it, and it was being
 * written before the button was pressed.
 *
 * ## Why the file and not only memory
 *
 * A phone has no console, and a screenshot cannot show a tap. The file is in Downloads —
 * the one place a person (and this container) can reach — is written as it happens, and
 * is a debug build's only output. In a release build no sink is ever attached: there is
 * nothing to write to and nothing written.
 */
object Trace {

    /** What an event is. */
    enum class Kind {
        /** Something a person did. Carries the id an answer will quote. */
        TAP,

        /** The app's visible state changed. Answers a tap. */
        CHANGED,

        /** The app said something. Answers a tap. */
        SAID,

        /** The app could not do what was asked. Answers a tap, in red. */
        FAIL,

        /** A fact worth keeping. Answers nothing, so it carries id 0. */
        NOTE,

        /** A person wrote down what went wrong. Answers nothing; it is a checkpoint. */
        REPORT,
    }

    /** One line. */
    data class Event(
        val at: Long,
        val id: Int,
        val kind: Kind,
        val what: String,
        val detail: String = "",
    ) {
        /** The line as it is written, and as it is read. */
        fun line(): String = buildString {
            append(stamp(at))
            append(' ')
            append(id.toString().padStart(4))
            append(' ')
            append(kind.name.padEnd(7))
            append(' ')
            append(what)
            if (detail.isNotEmpty()) {
                append("  ")
                append(detail)
            }
        }
    }

    /** An intent that nothing answered. */
    data class Broken(val id: Int, val at: Long, val what: String, val detail: String, val waitedMs: Long) {
        val line: String
            get() = "$what${if (detail.isEmpty()) "" else " ($detail)"} — " +
                "no change and no message after $waitedMs ms"
    }

    /** Everything the screen shows, worked out from the ring. */
    data class Report(
        val lines: List<String>,
        val waiting: List<Broken>,
        val failures: List<String>,
        val answered: Int,
        val taps: Int,
    ) {
        val summary: String
            get() = when {
                taps == 0 -> "Nothing has happened yet."
                waiting.isEmpty() && failures.isEmpty() -> "$answered of $taps answered, nothing stuck."
                waiting.isEmpty() -> "$answered of $taps answered, ${failures.size} failed."
                else -> "$answered of $taps answered, ${waiting.size} still waiting, ${failures.size} failed."
            }

        val isHealthy: Boolean get() = waiting.isEmpty() && failures.isEmpty()
    }

    /**
     * Where the lines go.
     *
     * An interface because the two implementations are genuinely different things: a
     * file on a test's disk, and a `MediaStore` entry that needs no permission on a
     * phone. Everything above this line is written once, and both get the same bytes.
     */
    interface Sink {
        /** Appends [lines], all of them, together. */
        fun append(lines: List<String>)

        /** Everything in the log, or null when it cannot be read. */
        fun read(): String?

        /** Where it is, in words a person can find. */
        fun where(): String?

        /** Wipes it, for the Clear button. */
        fun clear()
    }

    /** How many events are kept in the screen's ring. */
    const val EVENTS = 600

    /** How long a tap may go unanswered before it counts as broken. */
    var staleMs: Long = 2_000

    /** Once the log passes this, the oldest lines are dropped. */
    const val LOG_LIMIT = 512 * 1024

    /** How much is kept when it is trimmed, from the end. */
    const val LOG_KEEP = 256 * 1024

    private val guard = Any()
    private val events = ArrayDeque<Event>()

    /** Taps with no answer yet, by id. */
    private val unanswered = LinkedHashMap<Int, Event>()

    private var next = 1
    private var answered = 0
    private var taps = 0

    @Volatile
    private var sink: Sink? = null

    private val clock = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    private fun stamp(at: Long): String = clock.get()!!.format(Date(at))

    // The writer: lines are handed over here and written in batches, so forty events in
    // a second cost four opens of the file rather than forty, and nothing on the screen
    // ever waits for a write.
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "trace").apply { isDaemon = true }
    }
    private val pending = ArrayDeque<String>()
    private var flushing = false
    private val writeGuard = Any()

    /** Sends the log to [target], replacing wherever it went before. */
    fun attach(target: Sink?) {
        sink = target
    }

    /** Points the log at a plain file. The tests' sink, and the fallback. */
    fun attach(file: File?) {
        sink = file?.let { FileSink(it) }
    }

    /** Where the log is being written, for the screen to say. */
    val where: String? get() = sink?.where()

    // ---- recording ----------------------------------------------------------

    /**
     * Something a person did, and the id its answer will quote.
     *
     * [what] is the element's own name — "Save", "sync", "open-file" — so a log reads
     * like the screen it came from rather than like a stack trace. Spaces become
     * dashes so that the columns stay columns and `awk '{print $2}'` stays useful.
     */
    fun tap(what: String, detail: String = ""): Int {
        return try {
            var id = 0
            synchronized(guard) {
                id = next++
                taps++
                val event = Event(now(), id, Kind.TAP, slug(what), detail)
                unanswered[id] = event
                remember(event)
            }
            emit(listOf(Event(now(), id, Kind.TAP, slug(what), detail).line()))
            id
        } catch (t: Throwable) {
            0
        }
    }

    /** The app's own words, which answer whatever was being served. */
    fun said(text: String) = answer(Kind.SAID, "said", text)

    /** The app's visible state changed, which answers whatever was being served. */
    fun changed(what: String) = answer(Kind.CHANGED, "state", what)

    /** The app could not do what was asked, in its own words. */
    fun failure(said: String, what: String = "failed") = answer(Kind.FAIL, slug(what), said)

    /** A fact worth having that answers nothing. */
    fun note(what: String, detail: String = "") {
        try {
            val event = Event(now(), 0, Kind.NOTE, slug(what), detail)
            synchronized(guard) { remember(event) }
            emit(listOf(event.line()))
        } catch (t: Throwable) {
            // Nothing.
        }
    }

    /**
     * A person's own words, written into the stream where they happened.
     *
     * The checkpoint that makes a report and its context one document. Everything above
     * this line is what the app was doing when they decided something was wrong — which
     * is the part no description can supply.
     */
    fun checkpoint(note: String) {
        try {
            val said = note.trim().ifEmpty { "a problem was reported, with no words" }
            // **The sentence goes in the detail column, whole.** A person reading the log
            // should see their own words rather than a slug of them, and every other line
            // in the file is `time id kind name  detail` — so the report is the same
            // shape as the rest, and a reader needs no special case.
            val event = Event(now(), 0, Kind.REPORT, "report", said)
            synchronized(guard) { remember(event) }
            emit(listOf(event.line()))
        } catch (t: Throwable) {
            // Nothing.
        }
    }

    /** The one path every answer takes. */
    private fun answer(kind: Kind, what: String, detail: String) {
        try {
            val at = now()
            val line: String
            synchronized(guard) {
                val act = unanswered.values.lastOrNull()
                val id = act?.id ?: 0
                if (act != null) {
                    unanswered.remove(act.id)
                    answered++
                }
                val event = Event(at, id, kind, what, detail)
                remember(event)
                line = event.line()
            }
            emit(listOf(line))
        } catch (t: Throwable) {
            // Nothing.
        }
    }

    private fun remember(event: Event) {
        events.addLast(event)
        while (events.size > EVENTS) events.removeFirst()
    }

    private fun slug(text: String): String = text.trim().replace(Regex("\\s+"), "-")

    // ---- reading ------------------------------------------------------------

    /** Everything, worked out from the ring. */
    fun report(at: Long = now()): Report = try {
        synchronized(guard) {
            Report(
                lines = events.reversed().map { it.line() },
                waiting = unanswered.values.map {
                    Broken(it.id, it.at, it.what, it.detail, at - it.at)
                },
                failures = events.filter { it.kind == Kind.FAIL }
                    .reversed()
                    .map { "FAIL ${it.what}: ${it.detail}" },
                answered = answered,
                taps = taps,
            )
        }
    } catch (t: Throwable) {
        Report(emptyList(), emptyList(), emptyList(), 0, 0)
    }

    /** The taps that have waited too long — the broken sequences, and nothing else. */
    fun broken(at: Long = now()): List<Broken> = report(at).waiting.filter { it.waitedMs > staleMs }

    /**
     * What to hand somebody: the file as it stands, or the ring when there is no file.
     *
     * The file first, because it is the whole truth including what the ring dropped.
     */
    fun dump(at: Long = now()): String {
        val fromFile = sink?.read()
        if (fromFile != null) {
            return buildString {
                appendLine("Muffy Codes — what the app did")
                where?.let { appendLine("also at $it") }
                appendLine()
                append(fromFile)
            }
        }
        val r = report(at)
        return buildString {
            appendLine("Muffy Codes — what the app did")
            appendLine(r.summary)
            appendLine()
            r.lines.forEach { appendLine(it) }
        }
    }

    /** Empties the screen's ring. The file keeps its history, because it is evidence. */
    fun clearScreen() {
        synchronized(guard) {
            events.clear()
            answered = 0
            taps = 0
            unanswered.clear()
        }
        note("screen-cleared", "the file still holds everything")
    }

    /** Wipes the log as well. Only for a test, or a person starting a clean session. */
    fun clear() {
        clearScreen()
        sink?.clear()
    }

    /** Only for tests: a fresh slate. */
    internal fun resetForTest() {
        synchronized(guard) {
            events.clear()
            unanswered.clear()
            answered = 0
            taps = 0
            next = 1
        }
    }

    private fun now(): Long = System.currentTimeMillis()

    // ---- writing ------------------------------------------------------------

    private fun emit(lines: List<String>) {
        val target = sink ?: return
        synchronized(writeGuard) {
            pending.addAll(lines)
            if (flushing) return
            flushing = true
            try {
                writer.execute {
                    val batch = mutableListOf<String>()
                    synchronized(writeGuard) {
                        batch.addAll(pending)
                        pending.clear()
                        flushing = false
                    }
                    try {
                        target.append(batch)
                    } catch (t: Throwable) {
                        // A trace that cannot be written is not a reason to stop the app.
                    }
                }
            } catch (t: Throwable) {
                // The executor is shutting down, or the phone is out of threads.
                flushing = false
            }
        }
    }

    /** A plain file: what a test writes into, and the fallback when Downloads is not there. */
    class FileSink(private val file: File) : Sink {
        override fun append(lines: List<String>) {
            file.parentFile?.mkdirs()
            file.appendText(lines.joinToString("") { it + "\n" })
            trimIfLong()
        }

        override fun read(): String? = try {
            if (file.isFile) file.readText() else null
        } catch (t: Throwable) {
            null
        }

        override fun where(): String = file.absolutePath

        override fun clear() {
            try {
                file.writeText("")
            } catch (t: Throwable) {
                // Nothing to do: the file will be trimmed when it next grows.
            }
        }

        /**
         * Keeps the log from growing forever.
         *
         * The **rotation is written into the log** rather than into a side file, and the
         * id counter is not reset — so a tap recorded before the trim and answered after
         * it is still one tap with one id, and the pairing survives the boundary.
         */
        private fun trimIfLong() {
            if (file.length() <= LOG_LIMIT) return
            try {
                val all = file.readText()
                val kept = all.takeLast(LOG_KEEP)
                val from = kept.indexOf('\n').let { if (it < 0) 0 else it + 1 }
                file.writeText(
                    "… the oldest lines were dropped; ids keep counting …\n" + kept.substring(from),
                )
            } catch (t: Throwable) {
                // A log that could not be trimmed is still a log.
            }
        }
    }
}
