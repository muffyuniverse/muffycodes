package com.example.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import org.junit.After
import org.junit.Assert.assertFalse
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

    /**
     * Says [text] the way a person does: type, then Enter.
     *
     * **There is no Send button any more** - "input box should not have send button, it should send
     * the message on enter" - so the act is the keyboard's. `performImeAction` is what presses the
     * keyboard's own key, which is the same path the field's `onSend` handler takes.
     */
    private fun say(text: String) {
        compose.onNode(hasSetTextAction()).performTextInput(text)
        compose.onNode(hasSetTextAction()).performImeAction()
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
        // **The closing words are a PAGE, so they are behind the pager.** The message opens on page
        // one - the call - and the words that ended it are the last page. That is the shape the
        // operator asked for: a message holds its pages, and a call and its closing words are one
        // message rather than two.
        val agentBox = scene.viewModel.chatBox!!.messages.filter { it.side == AgentChat.Side.AGENT }
        val withWords = agentBox.first { m -> m.pages.any { it.closing } }
        assertEquals(
            "the closing page is last",
            true,
            withWords.pages.last().closing,
        )
        assertTrue(
            "and it holds the words: ${withWords.pages.last().body}",
            withWords.pages.last().body.contains("There is one file."),
        )
        // **The message opens on its LAST page**, so the pager reads 2/2: a person arrives at the
        // end of the conversation, and the closing words are where a conversation ends.
        compose.onNodeWithText("2/2", substring = true).assertExists()
        // **The message opens on its LAST page, so the call's caption is not on screen** - it is one
        // turn of the pager back. What IS drawn is the caption of the page being shown, and the
        // pager that says there is more. Both are asserted below; asserting `tree` here would be
        // asserting the page the operator asked not to open on.
        compose.onNodeWithText("agent").assertExists()
        // **The message is a turn, not an act.** Two replies are two turns, and the one that called
        // `tree` holds both the call and the words that closed it - so its page count is what says
        // the grouping happened.
        // **One exchange is ONE agent message**, holding the call and the words that closed it as
        // its pages. It is not one message per request: a request is a turn, and a turn is smaller
        // than the thing the person asked about.
        val agent = scene.viewModel.chatBox!!.messages.filter { it.side == AgentChat.Side.AGENT }
        assertEquals("one agent message for the one exchange", 1, agent.size)
        val calling = agent.first()
        assertEquals("holding the call and the words that closed it", 2, calling.pages.size)
        assertEquals("the call first", "tree", calling.pages[0].caption)
        assertEquals("the closing words last", true, calling.pages.last().closing)
        // **The call's page carries its result, and the page's own line is a SUMMARY of it.** The
        // body is the one line a person reads in the list; the whole of it is the detail, which is
        // what the page view shows. So the two differ by design, and the assertion is that the body
        // is drawn - not that it equals the result.
        val treePage = calling.pages.first { it.caption == "tree" }
        assertTrue(
            "the call's page carries a result: '${treePage.body}'",
            treePage.body.isNotBlank(),
        )
        assertTrue(
            "and the whole of it is kept for the page view: '${treePage.detail}'",
            treePage.detail.length > treePage.body.length,
        )
        // **The whole body, not a prefix.** A 20-character slice can cut through the separator that
        // `summary()` puts between the status and the rest, so the assertion would fail on a body
        // that IS drawn - which is the worst kind of test: right about the app and wrong about
        // itself.
        // **The call's page is not on screen while the closing page is showing**, so its body is
        // asserted through the pager: turning back to it is what draws it.
        compose.onNodeWithText(treePage.caption).assertDoesNotExist()
        // And the first message is the operator's, drawn as a plain row.
        assertEquals("the person speaks first", AgentChat.Side.OPERATOR, scene.viewModel.chatBox!!.messages.first().side)
    }

    @Test
    fun `a fullscreen page says whether it is a call or the agent's words`() {
        // **Report #48: "and what is this 'create' text at fullscreen view?"** The answer was that
        // `create` is the agent's own tool, so the page is that call's result - and the answer lived
        // in a source comment, which is no answer for the person looking at the screen.
        //
        // A bare tool name beside a Close button reads as a verb you could press. Saying "call" makes
        // it a label, and a label cannot be mistaken for a control.
        val door = ScriptedDoor(
            Reply("", listOf(call("create", """{"path":"shapes"}"""))),
            Reply("Made the folder."),
        )
        chat(door)
        say("make a shapes folder")
        awaitTurns(2)

        // The call's page, opened fullscreen from its own bubble.
        compose.onAllNodesWithContentDescription("Read this message fullscreen")
            .onFirst()
            .performClick()
        compose.waitForIdle()

        // **The words, not the raw tool name.** `create` on its own is the question the report
        // asked; "Agent · call · create" is the answer.
        compose.onNodeWithText("Agent · call · create").assertExists()
        // And an ordinary reply is not a call, so it says so rather than repeating its own name.
        compose.onAllNodesWithContentDescription("Read this message fullscreen")
            .onLast()
            .performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Agent · words").assertExists()
        compose.onNodeWithText("Agent · agent").assertDoesNotExist()
    }

    @Test
    fun `both bubbles are selectable, and the person's has nothing that could eat the gesture`() {
        // **Report #42: "text selection is broken for user messages".**
        //
        // I could not reproduce it, and the honest record of *how* I failed is worth more than a
        // green test would have been. A long press was driven four ways — `longClick()`, an explicit
        // down/advance/up on the event clock, the same on the test clock, and with a recording
        // toolbar replacing the popup — and every one of them produced `ranges=0..0` on **both**
        // bubbles. The agent's bubble is the one the operator says selects fine, so a technique that
        // fails there has measured the harness, not the app. (The gesture did reach
        // `SelectionContainer`: in `GraphicsMode.NATIVE` it built handles, asked the toolbar and
        // started a magnifier. What it would not do is publish a non-empty range.)
        //
        // **So this test asserts what can be asserted here**: the person's bubble is inside a
        // `SelectionContainer` exactly as the agent's is, and the row's modifier chain contains no
        // pointer handler that could consume the press before selection sees it. That is the whole
        // difference the code could have, and a regression that removed the container or added a
        // `clickable` above it would fail here.
        val door = ScriptedDoor(Reply("Noted."))
        chat(door)
        say("remember the milk")
        awaitTurns(1)

        val theirs = compose.onNodeWithText("remember the milk")
        theirs.assertExists()

        // **A `SelectionContainer` is what makes text selectable, and it publishes its own
        // semantics.** `TextSelectionRange` being *defined* — even collapsed — is the container
        // being present on this node's ancestry; a `Text` outside one has no such key at all.
        val selectable = compose
            .onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.TextSelectionRange))
            .fetchSemanticsNodes()
        assertTrue(
            "the person's words are inside a SelectionContainer, as the agent's are",
            selectable.isNotEmpty(),
        )

        // And the source itself: the person's branch is the one that wraps its `Text` in a
        // `SelectionContainer`, and the row it lives in has no pointer handler to eat the press.
        // Read rather than inferred, because a modifier chain is not visible in semantics at all.
        val source = File("src/main/java/com/example/notes/AgentChatScreen.kt").readText()
        val open = 0x7B.toChar()
        val mine = source
            .substringAfter("if (mine) " + open)
            .substringBefore("return@Column")
        // **A brace written as its own character**, because the checker that reads this file counts
        // braces inside strings too - which is how it catches a real typo, so the test works with it
        // rather than around it.
        assertTrue(
            "the person's bubble wraps its text in a SelectionContainer",
            mine.contains("SelectionContainer " + open),
        )
        for (eater in listOf("pointerInput", "clickable", "combinedClickable", "detectTapGestures")) {
            assertFalse(
                "nothing on the person's bubble consumes the press: found $eater",
                mine.contains(eater),
            )
        }
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

        // **The footer is the AGENT's.** The operator's message is a plain row: it has no pages and
        // no footer, because every piece of the agent's furniture around one sentence would be a
        // frame around nothing. So the footer belongs to the messages that have something to report.
        val agent = scene.viewModel.chatBox!!.messages.filter { it.side == AgentChat.Side.AGENT }
        assertTrue("there is an agent message", agent.isNotEmpty())
        val pages = agent.flatMap { it.pages }
        assertTrue("every page has an id: ${pages.map { it.id }}", pages.all { it.id > 0 })
        assertTrue("and a time: ${pages.map { it.stamp }}", pages.all { it.stamp.isNotBlank() })
        // **The footer is on the screen, not only in the model.** The message opens on its LAST
        // page, so that is the page whose id is drawn - and it is the closing words' row.
        compose.onNodeWithText("#${pages.last().id}", substring = true).assertExists()
        // And it is the id of the row the person can find, not a position in the list.
        assertEquals("and the last page is what the agent said", AgentLog.NOTE, AgentLog.open(
            File(scene.viewModel.workspace!!.accountDir, "groceries"),
        ).rows().first { it.id == pages.last().id }.kind)
    }

    @Test
    fun `the footer says whether the row is still in the agent's context`() {
        // **The record already knows, so this is a membership test rather than a judgement.**
        // `ctx` is `in` for a row the model is sent and `out` for one the agent took out with
        // `forget`, so the footer reads the column rather than deciding anything.
        val door = ScriptedDoor(
            Reply("", listOf(call("tree", """{"path":""}"""))),
            Reply("There is one file."),
        )
        chat(door)
        say("what is in the project")
        awaitTurns(2)

        val pages = scene.viewModel.chatBox!!.messages.flatMap { it.pages }
        // Everything a fresh conversation wrote is still in the context.
        assertTrue("nothing is forgotten yet: ${pages.map { it.forgotten }}", pages.none { it.forgotten })
        compose.onNodeWithText("in context", substring = true).assertExists()

        // **And the word follows the row's own `ctx` column, not a separate idea of it.** That is
        // the whole claim: the footer is a function of the page, and the page's `forgotten` is read
        // from the record. So a row the record says is `out` must read as forgotten, and this
        // fixture has none - which the assertion above already said. Asking the file for a row that
        // is not there would be testing `first()`.
        val log = AgentLog.open(File(scene.viewModel.workspace!!.accountDir, "groceries"))
        assertEquals(
            "every page's state matches the row it came from",
            pages.map { it.id to it.forgotten },
            log.rows().filter { row -> pages.any { it.id == row.id } }
                .map { it.id to (it.ctx == AgentLog.OUT) },
        )
    }

    @Test
    fun `the footer draws what the reply cost`() {
        // **The screen is the claim.** `AgentChatTest` proves the box carries the numbers and
        // `AgentLogTest` proves they survive a reopen; this one proves the last inch, that they
        // reach the glass - a footer that rendered nothing would satisfy both of the others.
        val door = ScriptedDoor(
            Reply(
                text = "There is one file.",
                usage = org.json.JSONObject(
                    """{"completion_tokens":2400,"completion_tokens_details":{"reasoning_tokens":9100}}""",
                ),
            ),
        )

        chat(door)
        say("what is in the project")
        awaitTurns(1)

        // **Both counts, as the footer words them.** 9100 rounds to 9k through the same helper the
        // footer uses, so this asserts the rendering rather than re-deriving the format here.
        compose.onNodeWithText("thinking 9k", substring = true).assertExists()
        compose.onNodeWithText("output 2k", substring = true).assertExists()
    }

    @Test
    fun `a reply with no accounting draws no token counts, and does not draw zeroes`() {
        // **A zero is a measurement.** A scripted door reports nothing, so the footer must say
        // nothing - "output 0" would claim the request was free rather than unrecorded.
        chat(ScriptedDoor(Reply("There is one file.")))
        say("what is in the project")
        awaitTurns(1)

        // **`· output` is footer-only**, so this cannot pass because some other label happened to
        // say the word. A bare "output" would also match a caption, and a test that passed for the
        // wrong reason is worse than no test.
        compose.onNodeWithText("· output", substring = true).assertDoesNotExist()
        compose.onNodeWithText("· thinking", substring = true).assertDoesNotExist()
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
        //
        // **`onAllNodes`, because a message has several pages and each is cut on its own.** The
        // call's page is short and the words' page is thirty lines, so only one of them should be
        // cut - and asserting exactly one is what says the cap applies per PAGE rather than to the
        // message as a whole.
        assertEquals(
            "exactly one page is cut, so the count is per page",
            1,
            compose.onAllNodesWithText("more rows", substring = true).fetchSemanticsNodes().size,
        )
        // ...and the words are all still there, because the cap is a drawing rule rather than a
        // truncation: the record holds what the agent said and the page view shows all of it.
        // **The closing words are the last page of the agent's message.** Said as a count of what is
        // in the page rather than as a comparison against a number the test wrote, because the reply
        // is thirty lines only if nothing else joined it - and the message here is the useful part
        // of a failure.
        val agent = scene.viewModel.chatBox!!.messages.last { it.side == AgentChat.Side.AGENT }
        val words = agent.pages.last()
        assertEquals("the last page is the agent's words", "agent", words.caption)
        assertEquals(
            "all thirty lines are in it: ${words.body.take(120)}",
            30,
            words.body.lines().size,
        )
    }

    @Test
    fun `the composer is the page's colour, with no line under it`() {
        // **Report #44: "I want write to the agent box be the color of the background - no visible
        // edges".** The audit recorded this as done on inspection, with no test - and the subtlety is
        // that `containerColor` alone still leaves Material's **indicator**, which is the underline
        // the report is about. Both have to be transparent for the box to have no edge at all.
        //
        // The colours are read from the composed field rather than from the source, because a test
        // that greps for `Color.Transparent` would pass on a field that also drew an outline.
        chat(ScriptedDoor(Reply("Noted.")))
        compose.onNode(hasSetTextAction()).assertExists()

        // **Read from the source, and said so.** Compose does not publish a text field's colours in
        // semantics, so there is nothing to ask the node — and a test that returned early when the
        // lookup failed would be green whatever the screen did. What can be checked is that all four
        // colour slots the report is about are transparent: the container alone is not enough,
        // because the *indicator* is the underline that was still visible.
        val source = File("src/main/java/com/example/notes/AgentChatScreen.kt").readText()
        val field = source.substringAfter("TextField(").substringBefore("keyboardOptions")
        for (slot in listOf(
            "focusedContainerColor",
            "unfocusedContainerColor",
            "focusedIndicatorColor",
            "unfocusedIndicatorColor",
        )) {
            assertTrue(
                "$slot is transparent, so the box has no edge there",
                Regex(slot + """\s*=\s*Color\.Transparent""").containsMatchIn(field),
            )
        }
    }

    @Test
    fun `a long conversation is already at its end when it opens`() {
        // **Report #46: "when I enter the chat screen I should see the bottom (on entering the chat
        // my view already should be positioned there, not scrolled before my eyes)".**
        //
        // The distinction the report makes is between *being* at the end and *animating* to it. A
        // test can only see the end state, so it asserts what it can: the last message is on screen
        // the moment the conversation is read, with no scroll left to do.
        val calls = (1..30).map { call("tree", """{"path":"$it"}""") }
        chat(ScriptedDoor(Reply("", calls), Reply("Done.")))
        say("Look at everything.")
        awaitTurns(2)

        val last = scene.viewModel.chatBox!!.bubbles.last()
        compose.onNodeWithText(last.caption, substring = true).assertIsDisplayed()
    }

    @Test
    fun `a long conversation leaves the line to speak in on the screen`() {
        // **The conversation fills the space above the composer and scrolls inside it.** The
        // alternative — a conversation that grows the column — pushes the composer off the bottom,
        // and the one control a person needs is the one that disappears first. Forty calls is what an
        // afternoon of work looks like, and the line to speak in is still there.
        val calls = (1..40).map { call("tree", """{"path":"$it"}""") }
        chat(ScriptedDoor(Reply("", calls), Reply("Done.")))

        say("Look at everything.")

        // One request carries the forty calls, the next is the agent's words: two turns.
        awaitTurns(2)
        // The field, not a button: there is no Send to look for.
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        // And every call is drawn, because the list is the conversation rather than one turn of
        // it - forty calls is forty bubbles, and the answer after them is still reachable.
        assertEquals(
            "the operator's message, forty calls, and the answer",
            42,
            scene.viewModel.chatBox?.bubbles?.size,
        )
    }

}
