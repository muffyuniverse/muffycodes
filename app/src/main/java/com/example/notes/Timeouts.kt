package com.example.notes

import org.eclipse.jgit.lib.ProgressMonitor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * How long a command may run, and whether it must stop.
 *
 * **A deadline is a question the work asks, not a timer that kills it.** Nothing in this app is a
 * subprocess: `clone` is JGit reading sockets in this process, `restore` is a loop writing files. So
 * the only way to stop one honestly is for it to look at [expired] and stop itself — a timer that
 * merely stopped *waiting* would tell the agent a command had failed while the command carried on
 * writing into the project underneath the next request.
 *
 * Two commands can outlast a turn and only two: [AgentTools.LONG]. Everything else finishes or
 * throws in microseconds, which is why a deadline is never attached to them.
 */
class Deadline(private val ms: Long) {

    private val started = System.currentTimeMillis()

    /** True when [ms] has passed. A deadline of zero or less means "no deadline", never "expired". */
    val expired: Boolean get() = ms > 0L && System.currentTimeMillis() - started >= ms

    /** How long it has been, for a sentence about it. */
    val elapsedMs: Long get() = System.currentTimeMillis() - started

    /** The sentence a result carries when a command stopped because the time ran out. */
    fun expiredIn(): String = "after ${seconds(ms)}"

    /**
     * The same deadline as JGit's own stop button.
     *
     * `clone` fetches object by object and asks its monitor between them, so this is a real stop:
     * the transport closes, the partial pack is discarded, and the call returns. That is the whole
     * reason [expired] alone is not enough — JGit never looks at it.
     */
    fun monitor(): ProgressMonitor = object : ProgressMonitor {
        private val cancelled = AtomicBoolean(false)

        override fun start(totalTasks: Int) {}
        override fun beginTask(title: String?, totalWork: Int) {}
        override fun update(bytes: Int) {
            if (expired) cancelled.set(true)
        }
        override fun endTask() {}
        override fun isCancelled(): Boolean = cancelled.get() || expired
        override fun showDuration(enabled: Boolean) {}
    }

    companion object {
        /** A deadline that never passes, for a caller with no time limit. */
        fun none(): Deadline = Deadline(0L)

        /** "60 s" or "2 min", because a person reads the sentence this ends up in. */
        fun seconds(ms: Long): String = when {
            ms < 60_000L -> "${ms / 1000} s"
            ms % 60_000L == 0L -> "${ms / 60_000L} min"
            else -> "${ms / 1000} s"
        }
    }
}

/**
 * Thrown by work that stopped itself because its [Deadline] passed.
 *
 * **A kind of throw, not a failure.** Everything that can stop is inside a `try` that already turns
 * exceptions into a `RemoteResult`, and "the operator's time ran out" would otherwise arrive at the
 * person as "Something went wrong". Catching this first is what keeps the two apart.
 */
class StoppedException(message: String) : Exception(message)

/**
 * What a stopped command leaves behind, in the words the caller will read.
 *
 * **"Nothing was kept" is a claim this class has to keep true**, which is why both commands that can
 * be stopped build their work in a `.partial` place and move it into position only when they finish.
 * An abandoned `clone` that had been writing straight into the project folder would make that
 * sentence a lie and leave a project that half exists.
 */
object Stopped {

    /**
     * What a stopped command's result says.
     *
     * **One sentence for both places a result can land** — the context and a `background` file —
     * because the model reads whichever one it asked for, and a difference in wording between them
     * would be a difference it has to learn.
     */
    fun body(tool: String, deadline: Deadline): String =
        "${AgentLog.FAILURE} $tool stopped ${deadline.expiredIn()}; nothing was kept"
}
