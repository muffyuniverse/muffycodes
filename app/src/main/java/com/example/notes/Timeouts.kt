package com.example.notes

import org.eclipse.jgit.lib.ProgressMonitor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * How long a command may run, and whether it must stop.
 *
 * **A deadline is a question the work asks, not a timer that kills it.** Nothing in this app is a
 * subprocess: a clone is JGit reading sockets in this process, a restore is a loop writing files, a
 * read is a stream of characters. So the only way to stop one honestly is for it to look at [expired]
 * and stop itself — a timer that merely stopped *waiting* would tell the agent a command had failed
 * while the command carried on writing into the project underneath the next request.
 *
 * **Every command gets one**, because any command can be slow on a phone: a `read` of a huge log, a
 * `tree` of a huge project, a `history` of a file saved a thousand times. [Deadline.check] is what
 * each of them calls inside its own loop. The commands that write once — `create`, `edit`, `move` —
 * have either finished or not started when the time passes, so "nothing was kept" is true for them
 * without their asking anything.
 */
class Deadline(
    private val ms: Long,
    /**
     * The clock, so a test can say when "now" is.
     *
     * **Not a sleep.** A test that proves expiry by waiting is a test whose result depends on how
     * busy the machine is — and the wait it adds is real time in a suite that has a budget. With the
     * clock injected, "the deadline has passed" is a statement about the code instead of about the
     * runner, and the two tests that used to sleep now do not.
     */
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    private val started = now()

    /** True when [ms] has passed. A deadline of zero or less means "no deadline", never "expired". */
    val expired: Boolean get() = ms > 0L && now() - started >= ms

    /** How long it has been, for a sentence about it. */
    val elapsedMs: Long get() = now() - started

    /**
     * Throws [StoppedException] when the time is up. **The one place a command asks.**
     *
     * A deadline of zero or less never throws, because [expired] is false for it — so "no limit"
     * is [none] and not a null to be checked at every call site. Everything that can take time
     * asks this inside its own loop: a timer over work that never looks at it would stop the
     * *waiting* and leave the work running under the next request.
     */
    fun check(tool: String) {
        if (expired) throw StoppedException(Stopped.body(tool, this))
    }

    /**
     * The sentence a result carries when a command stopped because the time ran out.
     *
     * **Seconds, always**, because seconds are what the operator typed in the row. Converting 60 s
     * to "1 min" made a person who set one minute read a sentence about a different unit than the
     * one in front of them, and made two limits that are the same length read differently.
     */
    fun expiredIn(): String = "after ${ms / 1000} s"

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

        /**
         * A length of time in the unit the operator chose, which is seconds.
         *
         * Minutes are not offered: the row asks for seconds, and a limit of 900 is written `900 s`.
         * One unit in one sentence is the whole point.
         */
        fun seconds(ms: Long): String = "${ms / 1000} s"
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

    /**
     * What a stopped command says when it had **already changed something**.
     *
     * Only a recursive delete can honestly say this. Every other command that can be stopped either
     * writes nothing or writes all at once — a copy that stopped deletes the half it made before
     * rethrowing — so "nothing was kept" is true for them. A folder removal stopped part of the way
     * has removed part of the folder, and a sentence claiming otherwise is the one lie this app
     * cannot afford to tell.
     */
    fun partway(tool: String, deadline: Deadline): String =
        "${AgentLog.FAILURE} $tool stopped ${deadline.expiredIn()}; part of it is already gone"

    /**
     * What a command that **ran past the operator's wait but is still running** says.
     *
     * This is the sentence report 13 asked for, and it is a different sentence from the two above
     * because it describes a different thing. "Stopped, nothing was kept" was the app choosing to
     * throw the work away because the *waiting* ran out — which is not what the person asked for.
     * They asked for the work to carry on and the result to arrive with the next batch of results,
     * so the command is moved to the background and this is what the turn is told.
     *
     * **It says the path**, because the file already exists — [Background.start] writes it before
     * the work starts — so the model can read it on any later turn, and knows exactly which file.
     * And it says it will arrive **with other call results**, which is the phrase the operator used
     * and the thing the model needs to know in order not to sit and wait for it.
     */
    fun stillRunning(tool: String, deadline: Deadline, path: String): String =
        "${AgentLog.SUCCESS} $tool is taking longer than the ${deadline.expiredIn()} allowed for a " +
            "command in the conversation, so it is still running. Its result will be written to " +
            "$path and will arrive on one of the next turns with other call results."
}

/**
 * The provider never acknowledged the request, **so nothing was produced.**
 *
 * Separate from [Idle] because the two are different facts and lead to different sentences. This
 * one means the request may not have been read at all: no tokens were generated, nothing was
 * billed as an answer, and repeating the attempt costs the same as the first one. That is what
 * makes an ack timeout safe to retry without thinking about it.
 *
 * [stream] is carried so the sentence can say what was *not* received, which is the part a person
 * can act on — "no answer started" is checkable, "the request timed out" is not.
 */
class Unacknowledged(val waitedMs: Long, val stream: Streamed) :
    java.io.IOException("The provider did not begin an answer within ${waitedMs / 1000} seconds")

/**
 * An answer had begun and then stopped arriving, **which is a connection that died.**
 *
 * The distinction from [Unacknowledged] is the whole reason the two clocks exist: a model thinking
 * hard is quiet, and killing it would throw away a long answer that was being written. This is only
 * raised after the first chunk, so a quiet stream is never mistaken for a dead one until the idle
 * number has actually passed.
 */
class Idle(val waitedMs: Long, val stream: Streamed) :
    java.io.IOException("The answer stopped for ${waitedMs / 1000} seconds")
