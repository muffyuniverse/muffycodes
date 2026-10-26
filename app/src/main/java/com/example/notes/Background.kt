package com.example.notes

import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Where a `background` call's work runs, and where its result goes.
 *
 * **The file is the whole protocol.** A call to `background` is answered at once with a path under
 * `agent/background_results/`, and that file — which exists from the moment the command starts —
 * carries the state: "running since 14:02" while it runs, the result body once it lands, and the
 * reason if it stopped. The agent finds out by reading, which is the only thing it already knows how
 * to do. There is no registry to come back to, no arguments to repeat, and nothing the model has to
 * be taught beyond "the result is in that file".
 *
 * **One thread per background call, daemon, and no pool.** The previous design kept a fixed pool per
 * view model and hung the test suite; a thread created for the work that exists is not a resource to
 * reason about. There is no `close()` because there is nothing to close.
 *
 * **`agent/` is in git's exclude list**, so a result file is never committed and never pushed — the
 * app's own bookkeeping stays the app's.
 */
class Background(
    /**
     * Starts the work and calls back when it lands. Injected so a test can hold a command unstarted,
     * or finish it inline, without a thread either way.
     */
    private val launch: ((() -> String) -> Unit) = { work -> Thread(work).start() },
) {

    /** Only used to name threads; a number is all it is for, so a process-wide counter is right. */
    private val name = threads.incrementAndGet()

    /**
     * Starts [call] in the background and returns the sentence the agent reads.
     *
     * The file is created **before** the work starts, so a `read` a millisecond later finds the
     * truth rather than an empty folder. It is replaced atomically when the work lands: a reader
     * never sees half a result.
     */
    fun start(project: File, call: Call, work: () -> String): String {
        val file = resultFile(project, call)
        file.parentFile?.mkdirs()
        write(file, "running")
        launch {
            val body = try {
                work()
            } catch (e: StoppedException) {
                e.message ?: "stopped"
            } catch (e: Throwable) {
                "${AgentLog.FAILURE} ${Secrets.explain(e, "That command could not finish")}"
            }
            write(file, body)
            Trace.note("agent", "${project.name} · ${call.tool} finished in ${file.name}")
            body
        }
        Trace.note("agent", "${project.name} · ${call.tool} running in ${file.name}")
        return "${AgentLog.SUCCESS} ${call.tool} is running; its result will be written to " +
            "${relativeName(project, file)}"
    }

    /**
     * The file a call's result belongs in.
     *
     * **Named after the call, not after the moment**, so the path in the result row names the same
     * number as the `CALL` row above it and two clones of two repositories cannot overwrite each
     * other's result.
     */
    fun resultFile(project: File, call: Call): File =
        File(File(File(project, AgentTools.LONG_DIR), "background_results"), "${call.tool}-${call.id}.txt")

    /**
     * Writes [body] so a reader sees either all of it or none of it.
     *
     * `.new` and a rename, which is the same rule `agents.json` follows and for the same reason: a
     * `read` that lands mid-write would otherwise report a result that is half a sentence.
     */
    private fun write(file: File, body: String) {
        val fresh = File(file.parentFile, file.name + ".new")
        fresh.writeText(body)
        if (!fresh.renameTo(file)) {
            // A rename that fails still leaves the reader a file; the alternative is a result that
            // was written and then thrown away because the rename could not be done.
            fresh.copyTo(file, overwrite = true)
            fresh.delete()
        }
    }

    private fun relativeName(project: File, file: File): String =
        file.relativeTo(project).invariantSeparatorsPath

    companion object {

        /** What a test or a call with no threading gets: the work runs, and is finished on return. */
        fun inline(): Background = Background { work -> work(); Unit }

        /** Nothing runs in the background; used where the parameter is not exercised. */
        fun none(): Background = inline()

        /**
         * The real thing: one daemon thread per call, named for the tool, never joined.
         *
         * Daemon so a thread still waiting on a socket cannot hold the app — or Gradle's test fork —
         * open. Its `Future` is deliberately ignored: the result's only home is the file.
         */
        fun real(): Background = Background { work ->
            val thread = Thread({ runCatching { work() } }, "muffy-bg-$name")
            thread.isDaemon = true
            thread.priority = Thread.MIN_PRIORITY + 1
            thread.start()
        }

        private val threads = AtomicInteger()
    }
}
