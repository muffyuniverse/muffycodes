package com.example.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * **The chat's request form** — the button, the window it opens, and what choosing one writes.
 *
 * The operator's shape (§3.11.3): a template is a property of *a chat*, not of a branch, so the door
 * moved from the branch row to the chat's own top bar — and it opens by itself when a chat has not
 * chosen one, because a chat with nowhere to send is a screen with a field a person can type into and
 * nothing that will happen.
 *
 * Two rules are the point of the file:
 *
 * - **once, not every frame** — a dismissed window must stay dismissed for that conversation, which is
 *   what makes it a gate rather than a nag;
 * - **the choice is a pointer, not a copy** — choosing writes `Schemas.setPointer`, which is the same
 *   fact the runner reads, so the button's label and the next turn's request cannot disagree.
 */
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ChatFormTest {

    @get:Rule
    val compose = createComposeRule()

    private val scene: Scenario by lazy { Scenario(RuntimeEnvironment.getApplication(), signIn = true) }

    @After
    fun putThePhoneAway() {
        scene.close()
    }

    /** A phone with a project and a chat open, with no form chosen. */
    private fun chat() {
        scene.project("groceries")
        compose.setContent {
            MaterialTheme {
                Surface {
                    AgentChatScreen(viewModel = scene.viewModel, onBack = {})
                }
            }
        }
        scene.viewModel.openChat("groceries")
        compose.waitForIdle()
    }

    // ---- what the button says ----------------------------------------------

    @Test
    fun `a chat with nothing chosen says so, and a chosen form is named`() {
        // **The name *is* the answer.** "Choose a form" is the one enabled control on a chat that
        // cannot send, and a name in the same place afterwards says which request is in force.
        chat()

        assertNull("nothing is chosen to begin with", scene.viewModel.chatFormName)
        compose.onNodeWithText("Choose a form").assertIsDisplayed()

        scene.viewModel.chooseForm(Schemas.UNIT)
        compose.waitForIdle()

        assertEquals("deepseek-1017", scene.viewModel.chatFormName)
        compose.onNodeWithText("deepseek-1017").assertIsDisplayed()
    }

    // ---- the window --------------------------------------------------------

    @Test
    fun `the picker lists the shipped form with the model it asks`() {
        // **The model id under the name**, because two forms that differ only in their model would
        // otherwise read as two identical rows.
        chat()

        compose.onNodeWithText("This chat needs a request form.").assertIsDisplayed()
        compose.onNodeWithText("deepseek-1017").assertIsDisplayed()
        compose.onNodeWithText("deepseek/deepseek-v4.1-flash-1017").assertIsDisplayed()
    }

    @Test
    fun `the window says where forms live and where a key is made`() {
        // **The operator's three answers in one window**: what this is, where it lives, and what to do
        // if it is not there yet. A picker with rows and no sentence teaches nothing about the next
        // time a person needs a form.
        chat()

        val sentences = compose.onAllNodesWithText("shared/requests/chat/", substring = true)
        assertTrue("it names the folder forms live in", sentences.fetchSemanticsNodes().isNotEmpty())
        val key = compose.onAllNodesWithText("Settings → Secrets", substring = true)
        assertTrue("and where a key comes from", key.fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `choosing a form writes the pointer the runner reads`() {
        // **One fact, two readers.** The button's label and the next turn's request both come from
        // `Schemas.pointer`, so choosing must write exactly that rather than a second record.
        chat()

        compose.onNodeWithText("deepseek-1017").performClick()
        compose.waitForIdle()

        val ws = scene.workspace!!
        assertEquals(
            "the pointer names the shipped unit",
            Schemas.unitPath(ShapeKind.CHAT, Schemas.UNIT),
            Schemas.pointer(ws.accountDir, "groceries", AgentSession.MAIN),
        )
        assertTrue(
            "and the person is told: ${scene.viewModel.message}",
            scene.viewModel.message.orEmpty().contains("deepseek-1017"),
        )
    }

    @Test
    fun `a form that is not there is refused rather than pointed at`() {
        // A pointer to a missing file would refuse every turn with a sentence about a file — the
        // refusal belongs at the choice, where the person can act on it.
        chat()

        scene.viewModel.chooseForm("no-such-form.jsonc")

        val ws = scene.workspace!!
        assertNull(
            "nothing was written",
            Schemas.pointer(ws.accountDir, "groceries", AgentSession.MAIN),
        )
        assertTrue(
            "and it says what is missing: ${scene.viewModel.message}",
            scene.viewModel.message.orEmpty().contains("no-such-form"),
        )
    }

    @Test
    fun `a dismissed window does not open again on the next redraw`() {
        // **The rule that makes it a gate rather than a nag.** Dismissing leaves the chat read-only;
        // it must not put the same window back the moment anything redraws, which is what a person
        // would experience as a window that will not close.
        chat()

        compose.onNodeWithText("Not now").performClick()
        compose.waitForIdle()
        // A redraw of the same conversation: the generation moves, which recomposes everything the
        // screen holds keyed on it.
        scene.viewModel.readChat()
        compose.waitForIdle()

        val still = compose.onAllNodesWithText("This chat needs a request form.")
        assertEquals("it stayed closed", 0, still.fetchSemanticsNodes().size)
    }

    @Test
    fun `the choice belongs to the chat, not to the branch`() {
        // **The operator's correction to §3.6.** A form is chosen when a chat is opened, so two
        // branches are two chats with two choices — and a branch that has chosen nothing still
        // inherits, which is the state every conversation starts in.
        chat()
        scene.viewModel.chooseForm(Schemas.UNIT)

        scene.viewModel.openChat("groceries", "feature")

        assertNull(
            "another branch has chosen nothing",
            scene.viewModel.chatFormName,
        )
    }
}
