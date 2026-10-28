package com.example.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * The box, through a whole turn that costs nothing.
 *
 * The door is scripted, so this is the one test in the suite that watches a conversation happen
 * from the outside: what the person typed, what the agent called, what the app did with the
 * result, and what the record says afterwards. Everything else about the box is [AgentChatTest]'s
 * — the shapes are pinned there, and this pins the wiring.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class AgentChatScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @After
    fun putThePhoneAway() {
        scene.close()
    }

    private val scene: Scenario by lazy {
        Scenario(RuntimeEnvironment.getApplication(), signIn = true)
    }

    private fun call(tool: String, args: String = "{}") = Call("call_$tool", tool, args)

    /** Puts the box on the screen and opens the project's conversation. */
    private fun chat(door: Door) {
        scene.project("groceries")
        scene.viewModel.chatDoor = { door }
        compose.setContent {
            MaterialTheme {
                Surface {
                    AgentChatScreen(viewModel = scene.viewModel, onBack = {})
                }
            }
        }
        scene.viewModel.openChat("groceries")
        // **Not `compose.waitUntil`.** The read is posted from the worker to the main looper,
        // and this harness only runs the looper while the scenario's own wait is idling it —
        // a compose wait spins the clock and never gives the post its turn.
        scene.await("the conversation to be read") { scene.viewModel.chatGeneration > 0 }
    }

    private fun say(text: String) {
        compose.onNode(hasSetTextAction()).performTextInput(text)
        compose.onNodeWithText("Send").performClick()
    }

    /**
     * Waits for [count] turns **and for the burst to be over**.
     *
     * The last turn appears a moment before the turn ends — the agent still has to close it and
     * say what happened — and a test that closed the phone in that gap would be racing the
     * worker that is writing the record.
     */
    private fun awaitTurns(count: Int) {
        scene.await("$count turns") {
            scene.viewModel.chatBox?.turns?.size == count && !scene.viewModel.chatWorking
        }
        compose.waitForIdle()
    }

    @Test
    fun `the box draws what was asked, what the agent called, and what it answered`() {
        val door = ScriptedDoor(
            Reply("", listOf(call("tree", """{"path":""}"""))),
            Reply("There is one file."),
        )

        chat(door)
        say("what is in the project")
        awaitTurns(2)

        // **Everything at once, oldest first.** The box used to draw one turn and make a person
        // press ‹ to see the call that produced the answer they were reading; a conversation is a
        // list and a list scrolls.
        compose.onNodeWithText("what is in the project", substring = true).assertExists()
        // **The caption is `agent`, not `concluded`.** The `CONCLUDE` row is gone; the ending is the
        // model's own note, which is drawn under the same caption as any other thing it said.
        compose.onNodeWithText("agent").assertExists()
        compose.onNodeWithText("There is one file.", substring = true).assertExists()
        // And the call is on the same screen as the answer it produced, with its result on the
        // same line - which is the thing the navigator was making a person work for.
        compose.onNodeWithText("tree").assertExists()
        // **The result's own words**, not a token count: the footer is a footer now and the size
        // column that used to be in the bubble body is gone.
        compose.onNodeWithText("milk.txt", substring = true).assertExists()
    }

    @Test
    fun `the record is what the box read, and it holds the app's turn boundaries`() {
        // **Two calls, because the assertion below is about calls plural.** It asked for two or
        // more while the script made one, so it could only ever have passed by accident.
        val door = ScriptedDoor(
            Reply("", listOf(call("tree", """{"path":""}"""))),
            Reply("", listOf(call("tree", """{"path":"."}"""))),
            Reply("There is one file."),
        )

        chat(door)
        say("what is in the project")
        awaitTurns(3)

        val log = AgentLog.open(File(scene.viewModel.workspace!!.accountDir, "groceries"))
        val kinds = log.rows().map { it.kind }
        assertTrue("the operator's words are on the record", kinds.contains(AgentLog.TOLD))
        assertTrue("so are the calls", kinds.count { it == AgentLog.CALL } >= 2)
        assertTrue("and their answers", kinds.contains(AgentLog.GOT))
        // **The ending is the agent's own words, not a row of its own kind.** There is no
        // `CONCLUDE` any more: what marks a turn as finished is the model having said something and
        // called nothing, and the row that carries it is the same `agent` note as any other prose.
        assertTrue(
            "and the ending is the agent's words",
            log.rows().any { it.kind == AgentLog.NOTE && it.name == "agent" },
        )
        // The box counted three turns, so the record has to say where one request ended.
        assertEquals(
            "one boundary per request",
            3,
            log.rows().count { it.kind == AgentLog.TURN },
        )
        assertEquals(
            "and the boundary never reaches the model",
            0,
            log.contextRows().count { it.kind == AgentLog.TURN },
        )
    }

    @Test
    fun `the conversation is bubbles, and every one of them is on the screen`() {
        val door = ScriptedDoor(
            Reply("", listOf(call("tree", """{"path":""}"""))),
            Reply("There is one file."),
        )

        chat(door)
        say("what is in the project")
        awaitTurns(2)

        // **Three bubbles, and the count is the point.** The operator's message, the call with its
        // result, and the agent's words - one per act. The turn boundaries are not bubbles: a
        // `TURN` row says where a request ended, which is bookkeeping about the record rather than
        // something that happened in the conversation.
        assertEquals(
            "the operator's message, the call, and the answer",
            listOf("you", "tree", "agent"),
            scene.viewModel.chatBox?.bubbles?.map { it.caption },
        )
        // The call and its result are **one** bubble, not two: they are one act.
        assertEquals("three bubbles for three acts", 3, scene.viewModel.chatBox?.bubbles?.size)
        // And the first one is the person's own words, so the list reads as an answer to them.
        assertEquals("the operator's message comes first", "what is in the project",
            scene.viewModel.chatBox?.bubbles?.first()?.body)
    }

    @Test
    fun `an answer to the agent's question carries the question into the next request`() {
        // The seam, through the box: the person answers in the composer, and without the
        // question in the next request their "yes" answers nothing. **It is carried as the
        // agent's own words** — which is all it ever needed to be. It used to be an `ask` call
        // whose text became a row; now the model simply says the question and calls nothing.
        val door = ScriptedDoor(
            Reply("Fix the README too?"),
            Reply("Fixed."),
        )

        chat(door)
        say("look at the readme")
        scene.await("the question") { door.asked.size == 1 }
        compose.waitForIdle()
        compose.onNodeWithText("Fix the README too?", substring = true).assertExists()

        say("yes")
        scene.await("the answer's turn") { door.asked.size == 2 && !scene.viewModel.chatWorking }

        val messages = door.asked.last().messages
        assertTrue(
            "the question came back with it",
            messages.any { it.role == "assistant" && it.text.contains("Fix the README too?") },
        )
        assertTrue(
            "and the answer is what the model was told",
            messages.any { it.role == "user" && it.text == "yes" },
        )
        // The calls-and-answers invariant has its own test, with calls in it; what this one
        // pins is the seam and the path a person's answer takes to the model.
    }

    @Test
    fun `an idle agent is one line, not a turn`() {
        // **A real ping, through the app's own clock.** The conversation ends with a conclusion, and
        // the four-minute tick keeps it warm: the rows a ping writes reach the screen as the line
        // they are meant to be — [AgentChatTest] pins the collapsing, this pins the wiring.
        val door = ScriptedDoor(Reply("Nothing to do."))

        chat(door)
        scene.viewModel.startAgent("groceries")
        say("anything new?")
        scene.await("the turn") { door.asked.size == 1 && !scene.viewModel.chatWorking }

        val due = scene.viewModel.sessionOf("groceries")!!.nextPollAt
        scene.viewModel.tickAgents(due)
        scene.await("the ping") { door.pings.size == 1 }
        scene.await("the idle line") { scene.viewModel.chatBox?.idle?.contains("1 check") == true }
        compose.waitForIdle()

        compose.onNodeWithText("anything new?", substring = true).assertExists()
        compose.onNodeWithText("idle since", substring = true).assertExists()
        compose.onNodeWithText("1 check", substring = true).assertExists()
    }
    @Test
    fun `every bubble has a footer naming the row it came from`() {
        // **The id is how a person finds the row in `session.log`**, which is what the box is a
        // view of. The time comes with it because a transcript without one is a list of things
        // that might have happened in any order.
        val door = ScriptedDoor(
            Reply("", listOf(call("tree", """{"path":""}"""))),
            Reply("There is one file."),
        )

        chat(door)
        say("what is in the project")
        awaitTurns(2)

        val bubbles = scene.viewModel.chatBox!!.bubbles
        assertTrue("every bubble has an id: ${bubbles.map { it.id }}", bubbles.all { it.id > 0 })
        assertTrue("and a time: ${bubbles.map { it.stamp }}", bubbles.all { it.stamp.isNotBlank() })
        // **The footer is on the screen, not only in the model.** The first bubble's id is drawn.
        compose.onNodeWithText("#${bubbles.first().id}", substring = true).assertExists()
        // And it is the id of the row the person can find, not a position in the list.
        assertEquals("the first bubble is the operator's own row", AgentLog.TOLD, AgentLog.open(
            File(scene.viewModel.workspace!!.accountDir, "groceries"),
        ).rows().first { it.id == bubbles.first().id }.kind)
    }

    @Test
    fun `a message past the cap says so rather than looking shorter than it is`() {
        // **A bubble that simply stopped would be indistinguishable from one that had finished.**
        // The cap hides nothing - it asks for one tap - and the sentence is what makes that true.
        val door = ScriptedDoor(
            Reply("", listOf(call("tree", """{"path":""}"""))),
            Reply((1..30).joinToString("\n") { "line $it" }),
        )
        scene.settings.keepBubbleLines(5)

        chat(door)
        say("what is in the project")
        awaitTurns(2)

        // The screen says how much of it it is not showing...
        compose.onNodeWithText("more lines", substring = true).assertExists()
        // ...and the words are all still there, because the cap is a drawing rule rather than a
        // truncation: the record holds what the agent said and the page view shows all of it.
        // **The last bubble is the agent's, and the thirty lines are all in it.** Said as a count
        // of what is in the bubble rather than as a comparison against a number the test wrote,
        // because the reply is thirty lines only if nothing else joined it - and the message here
        // is the useful part of a failure.
        val agent = scene.viewModel.chatBox!!.bubbles.last()
        assertEquals("the last bubble is the agent's words", "agent", agent.caption)
        assertEquals(
            "all thirty lines are in it: ${agent.body.take(120)}",
            30,
            agent.body.lines().size,
        )
    }

    @Test
    fun `a long conversation leaves the line to speak in on the screen`() {
        // **The box stops at three quarters of the window and scrolls.** The alternative — a
        // conversation that grows the column — pushes the composer off the bottom, and the one control
        // a person needs is the one that disappears first. Forty calls is what an afternoon of work
        // looks like, and the Send button is still there.
        val calls = (1..40).map { call("tree", """{"path":"$it"}""") }
        chat(ScriptedDoor(Reply("", calls), Reply("Done.")))

        say("Look at everything.")

        // One request carries the forty calls, the next is the agent's words: two turns.
        awaitTurns(2)
        compose.onNodeWithText("Send").assertIsDisplayed()
        // And every call is drawn, because the list is the conversation rather than one turn of
        // it - forty calls is forty bubbles, and the answer after them is still reachable.
        assertEquals(
            "the operator's message, forty calls, and the answer",
            42,
            scene.viewModel.chatBox?.bubbles?.size,
        )
    }

}
