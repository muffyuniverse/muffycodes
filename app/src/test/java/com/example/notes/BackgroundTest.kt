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
        val said = bg.start(project, call("restore")) { "${AgentLog.SUCCESS} restored" }

        // The sentence names the file, so the model needs no new instruction: it reads the path it
        // was given, which is the one thing it already knows how to do.
        assertTrue("it names the file: $said", said.contains("agent/background_results/restore-12.txt"))
        assertEquals(
            "the file is where the sentence said",
            "${AgentLog.SUCCESS} restored",
            File(project, "agent/background_results/restore-12.txt").readText(),
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
        bg.start(project, call("restore", id = "7")) { "${AgentLog.FAILURE} could not reach GitHub" }

        val file = File(project, "agent/background_results/restore-7.txt")
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
            File(project, "agent/background_results/restore-7.txt.new").exists(),
        )
    }

    @Test
    fun `a finished command is remembered with the path the agent was given`() {
        // **The app's own clock has to know a command landed**, or nothing can wake the agent for it.
        // The path is the same relative one the tool's answer named, so the note that wakes it and
        // the file it reads are the same address.
        val bg = Background({ work -> work() }, now = { 4_000 })

        val answer = bg.start(project, call("tree", id = "7")) { "${AgentLog.SUCCESS} three files" }

        val done = bg.finishedSince(project.name, 0)
        assertEquals("one completion", 1, done.size)
        assertEquals("for this project", project.name, done.single().project)
        assertEquals("naming the tool", "tree", done.single().tool)
        assertEquals("at the registry's time", 4_000L, done.single().at)
        assertTrue(
            "and the same path the answer gave: ${done.single().path} / $answer",
            answer.contains(done.single().path) && File(project, done.single().path).isFile,
        )
        assertTrue("nothing at or before the watermark", bg.finishedSince(project.name, 4_000).isEmpty())
        assertTrue("and nothing for another project", bg.finishedSince("another", 0).isEmpty())
    }

    @Test
    fun `a command that stopped leaves its reason in the file`() {
        // A background result nobody is waiting on still has to say why it is not there. Otherwise
        // the file reads `running` for ever and the agent keeps coming back to it.
        val bg = Background.inline()
        bg.start(project, call("restore", id = "9")) {
            throw StoppedException(Stopped.body("restore", Deadline(60_000L)))
        }

        val body = File(project, "agent/background_results/restore-9.txt").readText()
        assertTrue("it starts with a failure: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("and says it was stopped: $body", body.contains("stopped"))
        assertTrue("and that nothing was kept: $body", body.contains("nothing was kept"))
    }

    @Test
    fun `two calls do not share a file`() {
        // Named after the call, not after the moment: two commands in one turn are two results.
        val bg = Background.inline()
        bg.start(project, call("restore", id = "1")) { "first" }
        bg.start(project, call("restore", id = "2")) { "second" }

        assertEquals("first", File(project, "agent/background_results/restore-1.txt").readText())
        assertEquals("second", File(project, "agent/background_results/restore-2.txt").readText())
    }
}
