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

    /**
     * When the app last answered anything.
     *
     * A tap older than this cannot be what a new answer is about: something has already
     * been said since the press, so the press was served. This is the difference the
     * clock alone could not make — creating a folder produces a redraw *and* a sentence,
     * and the sentence was landing on a tap from ten seconds earlier.
     */
    private var lastAnswerAt = 0L

    @Volatile
    private var sink: Sink? = null

    private val clock = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss.SSS", Locale.US) }

    private fun stamp(at: Long): String = clock.get()!!.format(Date(at))

    /**
     * The writer: one thread, and one task per event.
     *
     * **One task per event, deliberately.** Batching was a "fewer file opens" saving that
     * needed a `flushing` flag to remember whether a batch was already queued — and a
     * flag like that, set on one thread and cleared on another, is exactly the kind of
     * state that gets stuck. When it did, every event after it silently went nowhere,
     * which is the one failure a log must never have. A single-threaded executor already
     * serialises the writes and keeps them in order; the file open per line is nothing
     * beside a git commit.
     */
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "trace").apply { isDaemon = true }
    }

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
     * ## The naming standard, because a log is only readable if every line obeys it
     *
     * A tap has two fields and each has one job:
     *
     * - **`what` is the element that was pressed.** Lowercase, one or two words, never a
     *   file name: `save`, `open file`, `lock`, `copy`, `confirm delete`. The name column
     *   is the thing a shell greps and a person scans, so it must be a small fixed
     *   vocabulary — `grep " copy "` should find every copy and nothing else.
     * - **`detail` is what it acted on and where it went**: a file name, a folder, a
     *   count. Free text, allowed spaces, because it is the tail of the line.
     *
     * Two conventions follow from that, and both were broken before they were written
     * down:
     *
     * - **A dialog's button is `confirm <what opened it>`.** `rename project` opens the
     *   window; `confirm rename project` is the button. Without the prefix, opening and
     *   confirming are two different acts with one name, and the log cannot tell whether
     *   a window was dismissed or a rename happened.
     * - **One gesture, one tap.** The thing that does the work records it, and nothing
     *   else does: a screen that also records gives one press two ids a millisecond
     *   apart, so every gesture half-answers itself and "answered twice" stops meaning
     *   anything.
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

    /**
     * The app's visible state changed — which answers a tap **only if the tap is what
     * caused it.**
     *
     * The first version answered whatever was being served, on the argument that a
     * change in the screen is the visible result of a press. It is — but the screen also
     * changes when nothing was pressed: a dialog is cancelled, a sheet is dismissed, a
     * back gesture returns. Each of those found the oldest unanswered tap waiting and
     * spent it, so a log whose whole purpose is "an id that appears once is a tap nobody
     * answered" reported three theme taps as answered by a cancel, a dismiss and a back
     * press. The query was not merely inaccurate: it said the opposite of the truth.
     *
     * So a claim now needs to be timely. What a person pressed shows itself at once; a
     * change arriving seconds later is the screen doing its own housekeeping, and it
     * carries id 0 while the unanswered tap stays unanswered.
     */
    fun changed(what: String, at: Long = now()) =
        answer(Kind.CHANGED, "state", what, at = at, withinMs = FRESH_MS)

    /**
     * How long after a tap its visible result still counts as that tap's answer.
     *
     * Generous, because the work between a press and a redraw goes through a worker and,
     * for a save, a commit. Tight enough that the seconds a person spends reading a dialog
     * are outside it — which is the interval the borrowing happened in.
     */
    const val FRESH_MS = 5_000L

    /**
     * How long the app's own words may take and still be the answer to a tap.
     *
     * Longer than a redraw, on purpose: a redraw arrives at once or is the screen's own
     * business, but the words after a sign-in wait for a network round trip, and a tap
     * that was answered slowly was answered.
     */
    const val ANSWER_MS = 60_000L

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

    /**
     * The one path every answer takes.
     *
     * `withinMs` is how stale a waiting tap may be and still be what this answers;
     * `Long.MAX_VALUE` means "the app is speaking, so it is answering", which is right for
     * words and wrong for a redraw (see [changed]).
     */
    private fun answer(
        kind: Kind,
        what: String,
        detail: String,
        at: Long = now(),
        withinMs: Long = ANSWER_MS,
    ) {
        try {
            val line: String
            synchronized(guard) {
                // **Two conditions, and both are needed.** Recent enough that it could be
                // what this answers, and made *after* the last answer — because a press
                // that has already been answered is not waiting for a second reply, and
                // the app sometimes speaks twice for one press (a redraw and a sentence).
                val act = unanswered.values.lastOrNull()
                    ?.takeIf { at - it.at <= withinMs && it.at >= lastAnswerAt }
                val id = act?.id ?: 0
                // Only a line that answered a tap moves the boundary. An answer that
                // claimed nothing is the app talking on its own account, and letting it
                // push the boundary forward would orphan a tap that is still waiting.
                if (act != null) lastAnswerAt = at
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
        // **A blank file is not a log.** The first version returned the file's contents
        // whenever the read did not throw — and on the day the file was empty that made
        // Share produce a heading and nothing else, while the ring still held a whole
        // session. A dump that can be empty is a dump that hides the evidence it exists
        // to carry, so an empty read falls through to what is in memory.
        val fromFile = sink?.read()?.takeIf { it.isNotBlank() }
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

    /**
     * Waits until everything recorded so far has been written.
     *
     * **The queue is single-threaded and in order,** so a task put behind the writes runs
     * after all of them: that is the whole mechanism. It is here rather than in the test
     * so that a test never has to write a marker into the log it is about to assert on —
     * which was the first version of this wait, and it made the log longer every time
     * somebody looked at it.
     *
     * Only a test needs this: the app writes and moves on, which is the point of a log.
     */
    internal fun awaitWrites() {
        val done = java.util.concurrent.CountDownLatch(1)
        try {
            writer.execute { done.countDown() }
        } catch (t: Throwable) {
            return
        }
        done.await(10, java.util.concurrent.TimeUnit.SECONDS)
    }

    // ---- writing ------------------------------------------------------------

    private fun emit(lines: List<String>) {
        if (lines.isEmpty()) return
        // The sink is read here, on the caller's thread, so a sink attached mid-session
        // takes effect from the next line rather than from the next batch.
        val target = sink ?: return
        try {
            writer.execute {
                try {
                    target.append(lines)
                } catch (t: Throwable) {
                    // A log that cannot be written is not a reason to stop the app.
                }
            }
        } catch (t: Throwable) {
            // The executor is shutting down, or the phone is out of threads.
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
