package com.example.notes

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A command that outlasts the wait, and the conversation that goes on without it.
 *
 * The rule the whole thing exists for: **a reply's tool call must be answered before the next
 * request**, or every endpoint refuses the conversation. So a command that is still running is
 * answered now — laconically, and truthfully — and the work finishes beside the conversation.
 *
 * The clock here is a [CountDownLatch], not a sleep: a test that waited sixty seconds to prove a
 * sixty-second timeout would be a test nobody runs.
 */
class LongCommandsTest {

    private lateinit var commands: LongCommands
    private lateinit var runner: ControllableRunner

    /** A runner whose every command waits for the test to let it go. */
    private class ControllableRunner : LongRunner {
        val started = AtomicInteger()
        private var gate: CountDownLatch = CountDownLatch(1)

        override fun submit(block: () -> String): Future<String> {
            started.incrementAndGet()
            val gateNow = gate
            val answer = java.util.concurrent.CompletableFuture<String>()
            Thread {
                gateNow.await()
                answer.complete(block())
            }.apply { isDaemon = true }.start()
            return answer
        }

        /** Lets every command that is waiting finish. */
        fun release() {
            gate.countDown()
            gate = CountDownLatch(1)
        }
    }

    @Before
    fun fresh() {
        runner = ControllableRunner()
        commands = LongCommands(runner)
    }

    @After
    fun stopWatching() {
        runner.release()
    }

    private fun call(tool: String = "clone", args: String = "{}") = Call("c1", tool, args)

    @Test
    fun `a command that finishes in time is answered with its body`() {
        val answer = commands.answer("shopping", call(), 5_000) {
            "0 cloned"
        }
        // Every command in this runner waits for the gate, so a wait of five seconds would be a
        // five-second test. Released first, then asked.
        runner.release()
        assertEquals("0 cloned", answer)
    }

    @Test
    fun `a command that outlasts the wait is answered laconicly, and never with a failure`() {
        val answer = commands.answer("shopping", call(), 50) { "0 cloned" }

        // **Not a failure.** A slow command has not gone wrong, and a `1` would make the model tell
        // the person their clone failed.
        assertTrue("it is a result: $answer", answer.startsWith(AgentLog.SUCCESS))
        assertTrue("and it says what to do: $answer", answer.contains("still running"))
        assertTrue(
            "laconicly — one sentence, an instruction and no lecture: $answer",
            answer.length < 120,
        )
    }

    @Test
    fun `the same command asked again hands over the finished body instead of running it twice`() {
        var runs = 0
        val first = commands.answer("shopping", call(), 50) {
            runs++
            "0 cloned"
        }
        assertTrue("handed over", first.contains("still running"))
        assertEquals("it did not run twice while it was still going", 1, runs)

        runner.release()
        // Let the collector file the answer. It is a thread, so the wait is bounded by the test,
        // not by the app's own interval.
        val deadline = System.currentTimeMillis() + 5_000
        var second = ""
        while (System.currentTimeMillis() < deadline) {
            second = commands.answer("shopping", call(), 50) { runs++; "0 cloned again" }
            if (second == "0 cloned") break
            Thread.sleep(10)
        }
        assertEquals("the finished body", "0 cloned", second)
        assertEquals("and the command ran exactly once", 1, runs)
    }

    @Test
    fun `a different project or different arguments is a different command`() {
        commands.answer("shopping", call(), 50) { "0 first" }
        commands.answer("work", call(), 50) { "0 second" }
        assertEquals("one per project and arguments", 2, runner.started.get())
    }

    @Test
    fun `no wait means wait for it, which is the behaviour a turn had before this existed`() {
        // **Zero is not "hand it over immediately".** An app with no soft timeout set must behave
        // exactly as it did, or the person who never opened Settings would get a different agent.
        runner.release()
        val answer = commands.answer("shopping", call(), 0) { "0 cloned" }
        assertEquals("0 cloned", answer)
    }
}
