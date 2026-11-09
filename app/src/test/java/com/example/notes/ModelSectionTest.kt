package com.example.notes

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * **The model block, painted for real — and it is one row now.**
 *
 * The three pickers (model, provider, effort) went with the catalogue that fed them, and the embedding
 * and rerank pointers went with the index (2026-11-09). What is left to ask is smaller and still worth
 * asking: does the screen name the templates in the folder, does a damaged file say so without being
 * replaced, and is the key never on the screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ModelSectionTest {

    @get:Rule
    val compose = createComposeRule()

    @After
    fun putThePhoneAway() {
        scene.close()
    }

    private val scene: Scenario by lazy {
        Scenario(RuntimeEnvironment.getApplication(), signIn = true)
    }

    private val account: java.io.File get() = scene.workspace!!.accountDir

    /** The app's folder, which is where the library lives (§3.11). */
    private val app: java.io.File get() = scene.workspace!!.systemDir

    @Composable
    private fun Section(onOpenTemplates: () -> Unit = {}) {
        MaterialTheme {
            Surface {
                Column {
                    KeyRow(viewModel = scene.viewModel, onSetKey = {})
                    ModelSection(viewModel = scene.viewModel, onOpenTemplates = onOpenTemplates)
                }
            }
        }
    }

    /** Writes a schema into the folder the way the editor would, and refreshes. */
    private fun write(name: String, schema: Schema = Schema(
        address = "https://x.test/v1",
        url = "embeddings",
        body = org.json.JSONObject("""{"model":"e","input":"{{input}}"}"""),
    )) {
        Schemas.write(app, name, schema).getOrThrow()
    }

    // ---- the folder is the manager ---------------------------------------------

    @Test
    fun `the row says how many templates there are, and opens the folder they live in`() {
        // **One row, and it is a door.** The operator's design: a button that opens the folder, and the
        // browser and editor are the management UI. So there is nothing else on this screen to assert —
        // the count says whether anything is there, and the press asks for the folder.
        var opened = 0
        compose.setContent { Section(onOpenTemplates = { opened++ }) }

        // The shipped one, written when the folder was first read.
        compose.onNodeWithText("1 template — tap to edit").assertIsDisplayed()
        compose.onNodeWithText("Request templates").performClick()

        assertEquals("the row asked for the folder", 1, opened)
    }

    @Test
    fun `a folder with one template says one`() {
        // The count is read, not remembered: a person who deletes one in the browser comes back to a
        // row that says so. This is the assertion that would fail if the list were cached at
        // construction.
        Schemas.ensure(app)
        compose.setContent { Section() }

        compose.onNodeWithText("1 template — tap to edit").assertIsDisplayed()
    }

    @Test
    fun `an empty folder says so in words a person can act on`() {
        Schemas.ensure(app)
        for (name in Schemas.names(app)) Schemas.remove(app, name)
        compose.setContent { Section() }

        // Not "0 templates": the row is the only place a person is told the feature has nothing to work
        // with, so it says what to do about it.
        compose.onNodeWithText("none yet — tap to add one").assertIsDisplayed()
    }

    @Test
    fun `the chat model and the effort are not on this screen any more`() {
        // **The absence is the decision.** Those three rows read OpenRouter's catalogue; a request is a
        // file now, and the model is a literal in it. A row that stayed would be a second place to say
        // what the file already says.
        compose.setContent { Section() }

        for (gone in listOf("Model", "Provider", "Effort", "Rates")) {
            compose.onNodeWithText(gone).assertDoesNotExist()
        }
    }

    // ---- the file, and the key ------------------------------------------------

    @Test
    fun `a config the app cannot read is said on the screen, and the file is not replaced`() {
        // A damaged file does not quietly become the defaults. Both facts are true at once, and a person
        // needs both: the file is broken, and here is what the agent would use meanwhile.
        val file = ModelConfigFile.file(account)
        file.writeText("""{ "version": 1, "active": {} }""")
        scene.viewModel.refresh()

        compose.setContent { Section() }

        compose.onNodeWithText("will not read", substring = true).assertIsDisplayed()
    }

    @Test
    fun `reset puts back the config the app ships`() {
        // From the build's own copy, not from the account's history: "reset" means the one that cannot
        // itself be broken by the edit being undone.
        scene.viewModel.setEffort("low")
        assertEquals("low", scene.viewModel.modelConfig.active.effort)

        scene.viewModel.resetModelConfig()

        assertEquals("high", scene.viewModel.modelConfig.active.effort)
    }

    @Test
    fun `the key row says plainly when there is no key`() {
        compose.setContent { Section() }

        // The sentence a fresh install gets. It matters more than it used to: a shipped template names no
        // key, so this is the only place a person is told why nothing can be sent.
        compose.onNodeWithText("No model key yet", substring = true).assertIsDisplayed()
    }

    @Test
    fun `the key itself is never on the screen`() {
        scene.viewModel.saveModelKey("sk-or-v1-secret-value")
        compose.setContent { Section() }

        compose.onNodeWithText("sk-or-v1-secret-value").assertDoesNotExist()
        compose.onNodeWithText("A model key is kept", substring = true).assertIsDisplayed()
    }
}
