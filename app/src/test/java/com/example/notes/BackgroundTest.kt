package com.example.notes

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `background` — the file is the whole protocol.
 *
 * Every test here runs the work **inline**, so nothing acquires a thread and a suite of these costs
 * milliseconds. What is being tested is not threading: it is that the answer names a file, that the
 * file exists before the work does, that it carries the result once the work lands, and that a
 * command which is not slow is refused rather than given a file nobody will read.
 */
class BackgroundTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val project: File by lazy {
        tmp.newFolder("a project").also { folder ->
            File(folder, "agent").mkdirs()
        }
    }

    private fun call(tool: String, id: String = "12") = Call(id = id, tool = tool, args = "{}")

    @Test
    fun `the answer is the path the result will be written to`() {
        val bg = Background.inline()
        val said = bg.start(project, call("clone")) { "${AgentLog.SUCCESS} cloned" }

        // The sentence names the file, so the model needs no new instruction: it reads the path it
        // was given, which is the one thing it already knows how to do.
        assertTrue("it names the file: $said", said.contains("agent/background_results/clone-12.txt"))
        assertEquals(
            "the file is where the sentence said",
            "${AgentLog.SUCCESS} cloned",
            File(project, "agent/background_results/clone-12.txt").readText(),
        )
    }

    @Test
    fun `the file says it is running, and it is there before the work starts`() {
        // **Written before the work is launched**, so a read a millisecond later finds the truth
        // rather than an empty folder. The work here is captured and never run, which is exactly the
        // moment in question: the call has returned and the command has not finished.
        var held: (() -> String)? = null
        val bg = Background { work -> held = work }
        val said = bg.start(project, call("restore", id = "4")) { "landed" }

        val file = File(project, "agent/background_results/restore-4.txt")
        assertTrue("the answer names it: $said", said.contains("agent/background_results/restore-4.txt"))
        assertEquals("and the file already says what is happening", "running", file.readText())

        held!!.invoke()
        assertEquals("until the work lands", "landed", file.readText())
    }

    @Test
    fun `a result that lands replaces the running line`() {
        val bg = Background.inline()
        bg.start(project, call("clone", id = "7")) { "${AgentLog.FAILURE} could not reach GitHub" }

        val file = File(project, "agent/background_results/clone-7.txt")
        assertEquals(
            "the file holds the body and only the body",
            "${AgentLog.FAILURE} could not reach GitHub",
            file.readText(),
        )
        assertFalse(
            "and no trace of the placeholder",
            file.readText().contains("running"),
        )
        assertFalse(
            "nor a half-written temp file",
            File(project, "agent/background_results/clone-7.txt.new").exists(),
        )
    }

    @Test
    fun `a command that stopped leaves its reason in the file`() {
        // A background result nobody is waiting on still has to say why it is not there. Otherwise
        // the file reads `running` for ever and the agent keeps coming back to it.
        val bg = Background.inline()
        bg.start(project, call("clone", id = "9")) {
            throw StoppedException(Stopped.body("clone", Deadline(60_000L)))
        }

        val body = File(project, "agent/background_results/clone-9.txt").readText()
        assertTrue("it starts with a failure: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("and says it was stopped: $body", body.contains("stopped"))
        assertTrue("and that nothing was kept: $body", body.contains("nothing was kept"))
    }

    @Test
    fun `two calls do not share a file`() {
        // Named after the call, not after the moment: two clones in one turn are two results.
        val bg = Background.inline()
        bg.start(project, call("clone", id = "1")) { "first" }
        bg.start(project, call("clone", id = "2")) { "second" }

        assertEquals("first", File(project, "agent/background_results/clone-1.txt").readText())
        assertEquals("second", File(project, "agent/background_results/clone-2.txt").readText())
    }
}
