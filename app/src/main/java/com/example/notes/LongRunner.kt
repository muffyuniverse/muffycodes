package com.example.notes

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Where a command that may outlast its turn actually runs.
 *
 * Two threads, daemon, and **not the app's worker**: a thread here holds nothing but one command,
 * and a command in flight cannot keep the process alive after the turn that asked for it has
 * answered.
 */
interface LongRunner {

    fun submit(block: () -> String): Future<String>

    /**
     * Gives up the threads.
     *
     * **A runner that is not closed is a thread nobody can stop.** The pool's threads are daemons,
     * so a process still exits — but a *test* does not exit the process, and Gradle's executor waits
     * for every live thread in the fork. One `AgentRunner` per test, each with a pool, was enough to
     * hold a shard open until the job timed out.
     */
    fun close() {}

    companion object {

        fun real(): LongRunner = object : LongRunner {
            private val threads = AtomicInteger()

            /**
             * **Made on the first command, not when the runner is built** — and null until then.
             *
             * A pool created eagerly is two threads per view model, and the suite builds hundreds
             * that never run a slow command; that is what held a shard open. The threads are daemons,
             * so nothing hangs in the app — it is the *test* fork that waits for them.
             *
             * `close` reads this field rather than any lazy property, because asking a `lazy` whether
             * it was ever made makes it, and a close that builds the thing it is closing is the
             * opposite of what closing is for.
             */
            @Volatile
            private var live: ExecutorService? = null

            override fun submit(block: () -> String): Future<String> {
                val pool = synchronized(this) {
                    live ?: newPool().also { live = it }
                }
                return pool.submit<String> { block() }
            }

            private fun newPool(): ExecutorService = Executors.newFixedThreadPool(2) { task ->
                Thread(task, "muffy-long-${threads.incrementAndGet()}").apply {
                    isDaemon = true
                    priority = Thread.MIN_PRIORITY + 1
                }
            }

            override fun close() {
                live?.shutdownNow()
                live = null
            }
        }

        /**
         * Runs everything at once, on the calling thread — for a test that is not about a slow call.
         *
         * The future is already done when it is handed back, so nothing is ever handed over. A
         * suite using the real pool would be racing itself: a command in a test would finish
         * whenever it finished.
         */
        fun direct(): LongRunner = object : LongRunner {
            override fun submit(block: () -> String): Future<String> {
                val value = block()
                return object : Future<String> {
                    override fun get(): String = value
                    override fun get(timeout: Long, unit: TimeUnit): String = value
                    override fun cancel(mayInterrupt: Boolean): Boolean = false
                    override fun isCancelled(): Boolean = false
                    override fun isDone(): Boolean = true
                }
            }
        }
    }
}

/**
 * Commands that run **beside** the conversation instead of inside it.
 *
 * A turn is one thread on purpose: a burst cannot overlap another burst and the record has one
 * order. A command that reads a large tree, clones a repository or waits on a network is the one
 * thing that must not be given that thread for minutes — so it goes to a thread of its own, the turn
 * carries on, and the answer is collected later.
 *
 * **How "later" stays legal.** A reply's tool call must be answered before the next request goes
 * out, or every endpoint refuses the conversation. So the call is answered *now* — with the truth: it
 * is still running, and asking again with the same arguments will collect it. No fabricated turn, no
 * unanswered call, and the model decides when to come back for it. When it does, the work is already
 * done and the body is handed over without running anything twice.
 *
 * **The registry is why a command survives the turn that asked for it.** A long clone usually
 * outlives its turn: the agent hands it over, ends in `wait`, and the next poll four minutes on is a
 * different [Agent] over the same files. A future held in the turn's own field would die with the
 * turn, and "it is still running" would be a promise nobody could keep.
 *
 * Nothing here is persisted. A command still running when the process dies is gone, and the record
 * already says its wait ran out: a command is work this phone was doing, not a message somebody sent.
 */
class LongCommands(private val runner: LongRunner = LongRunner.real()) {

    private class Running(val body: Future<String>)

    /** Keyed by project, tool and arguments: the same request twice is the same command. */
    private val running = ConcurrentHashMap<String, Running>()

    /** Bodies that finished and were not collected yet, kept for the turn that comes back for them. */
    private val finished = ConcurrentHashMap<String, String>()

    /** Keys that already have a watcher, so one command is watched once. */
    private val watching = ConcurrentHashMap.newKeySet<String>()

    /**
     * How [call] answered, waiting at most [waitMs] for it.
     *
     * Three outcomes and no fourth: a body that had already finished and was never collected; a
     * body that arrives inside the wait; and the handover, when it does not.
     */
    fun answer(project: String, call: Call, waitMs: Long, block: () -> String): String {
        val key = key(project, call)
        // **Collected, not run again.** The turn that asked was told to come back for this; the
        // work is done, and running it a second time would read the tree twice or clone twice.
        finished.remove(key)?.let {
            watching.remove(key)
            Trace.note("agent-long", "$project · ${call.tool} finished, handed over")
            return it
        }

        val body = running[key]?.body ?: runner.submit(block).also {
            running[key] = Running(it)
            // **Watched from the moment it starts**, not from the moment its wait runs out. A
            // command that finishes in two seconds and is asked about four minutes later has to be
            // waiting for collection, and a watcher started at handover time would miss it.
            collect(key, it)
        }
        val now = try {
            // **No budget means wait for ever**, which is the inline behaviour a turn had before
            // this existed: a handover is something a person asked for by setting a wait, never
            // something the app does because nobody said.
            if (waitMs <= 0L) body.get() else body.get(waitMs, TimeUnit.MILLISECONDS)
        } catch (t: Throwable) {
            // A timeout is the handover, not a failure.
            null
        }
        if (now != null) {
            running.remove(key)
            return now
        }
        if (body.isDone) {
            return try {
                body.get()
            } catch (t: Throwable) {
                "${AgentLog.FAILURE} ${Secrets.explain(t, "That command stopped before it finished")}"
            }
        }
        // Still running: it stays in the registry, and the body is handed over when it is asked
        // for. Traced, because "the agent did the same call twice" is the question this answers.
        Trace.note("agent-long", "$project · ${call.tool} still running after ${waitMs / 1000} s")
        return handover(call.tool)
    }

    /**
     * The result body that says a command is still going.
     *
     * **Laconic, and that is the design.** A result the model reads must be short enough to be worth
     * the tokens it costs, and the model does not need a lecture about timeouts — it needs to know
     * that the command has not failed, that it is still working, and what to do to collect it. One
     * sentence, and the second clause is the instruction.
     */
    fun handover(tool: String): String = "${AgentLog.SUCCESS} $tool is still running; call again"

    /** How many commands of [project] have not been collected. */
    fun pending(project: String): Int = running.keys.count { it.startsWith("$project\u0000") }

    /**
     * Files [body]'s answer under [key] when it lands, **once**, and never at the caller's expense.
     *
     * A thread of its own, doing nothing but waiting: the alternative is a turn polling every
     * command it once handed over, which is the kind of timer this app has spent its life removing.
     *
     * The watcher removes nothing. A body that arrives while the turn that asked is still waiting is
     * handed to that turn by [answer]; a body that arrives afterwards is left for the turn that comes
     * back for it. Removing the entry here would take the result away from the caller that is
     * sitting on the same future.
     */
    private fun collect(key: String, body: Future<String>) {
        if (!watching.add(key)) return
        Thread {
            val text = try {
                body.get()
            } catch (t: Throwable) {
                "${AgentLog.FAILURE} ${Secrets.explain(t, "That command stopped before it finished")}"
            }
            // Still registered means nobody has collected it: the turn that asked has moved on.
            if (running[key]?.body === body && finished.putIfAbsent(key, text) == null) {
                running.remove(key)
            }
            // **Forget the watch, not the answer.** The key may be asked for again by a later turn
            // — the same command in a new conversation is a new command — and a watch left behind
            // would stop the second one from ever being collected.
            watching.remove(key)
        }.apply {
            isDaemon = true
            name = "muffy-long-collect"
        }.start()
    }

    private fun key(project: String, call: Call): String =
        "$project\u0000${call.tool}\u0000${call.args}"

    /** Gives up the threads this registry was given. */
    fun close() = runner.close()
}
