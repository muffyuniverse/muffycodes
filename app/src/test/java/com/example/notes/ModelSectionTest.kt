package com.example.notes

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
 * **The model block, painted for real — and it is now a list of files.**
 *
 * The three pickers (model, provider, effort) are gone with the catalogue that fed them, so what is left
 * to ask is one question: does the screen name the templates in the folder, and does a press write the
 * pointer it promised? Everything about *which* model a template asks for belongs to the file, so the
 * tests that used to drive a picker are replaced by tests that drive a file list.
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
        Schemas.write(account, name, schema).getOrThrow()
    }

    // ---- the folder is the manager ---------------------------------------------

    @Test
    fun `the row says how many templates there are, and opens the folder they live in`() {
        // **One row, and it is a door.** The operator's design: a button that opens the folder, and the
        // browser and editor are the management UI. So there is nothing else on this screen to assert —
        // the count says whether anything is there, and the press asks for the folder.
        var opened = 0
        compose.setContent { Section(onOpenTemplates = { opened++ }) }

        // The shipped three, written when the folder was first read.
        compose.onNodeWithText("3 templates — tap to edit").assertIsDisplayed()
        compose.onNodeWithText("Request templates").performClick()

        assertEquals("the row asked for the folder", 1, opened)
    }

    @Test
    fun `a folder with one template says one, not three`() {
        // The count is read, not remembered: a person who deletes two in the browser comes back to a row
        // that says so. This is the assertion that would fail if the list were cached at construction.
        Schemas.ensure(account)
        Schemas.remove(account, "embedding.json")
        Schemas.remove(account, "rerank.json")
        compose.setContent { Section() }

        compose.onNodeWithText("1 template — tap to edit").assertIsDisplayed()
    }

    @Test
    fun `an empty folder says so in words a person can act on`() {
        Schemas.ensure(account)
        for (name in Schemas.names(account)) Schemas.remove(account, name)
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

    // ---- the two vector models point at files ----------------------------------

    @Test
    fun `the embedding and rerank rows say not chosen before anything is`() {
        compose.setContent { Section() }

        compose.onNodeWithText("Embedding model").assertIsDisplayed()
        compose.onNodeWithText("Rerank model").assertIsDisplayed()
        // Both unnamed, which is the honest state of a fresh account — and the search feature says so
        // rather than inventing a template on the person's behalf.
        compose.onNodeWithText("not chosen").assertIsDisplayed()
    }

    @Test
    fun `choosing an embedding template writes its name and the size, not a model id`() {
        write("mine.json")
        scene.viewModel.setEmbeddingSchema("mine.json")
        compose.setContent { Section() }

        // **The pointer is a file name.** That is what makes editing the file change the behaviour of
        // every worktree that names it, which is the point of having one folder.
        assertEquals("mine.json", scene.viewModel.modelConfig.embedding?.schema)
        // And the size is kept beside it, because the index that is compared against was built at a
        // size the template's `{{dimensions}}` cannot tell us.
        assertEquals(1024, scene.viewModel.modelConfig.embedding?.dimensions)
        compose.onNodeWithText("mine.json").assertIsDisplayed()
    }

    @Test
    fun `a rerank template is given no size, because the job has none`() {
        write("rank.json", Schema("https://x.test/v1", url = "rerank", body = org.json.JSONObject("{}")))
        scene.viewModel.setRerankSchema("rank.json")

        assertEquals("rank.json", scene.viewModel.modelConfig.rerank?.schema)
        // A `dimensions` the server never asked for is a field it may refuse.
        assertEquals(null, scene.viewModel.modelConfig.rerank?.dimensions)
    }

    @Test
    fun `a new size asks first and says what the rebuild costs`() {
        // **Not a preference**: the vectors on disk are in the old template's coordinate system, and a
        // cosine between two of those looks like a score. So the question is drawn before anything is
        // written, and the person can say no.
        write("a.json")
        scene.viewModel.setEmbeddingSchema("a.json")
        scene.viewModel.cycleEmbeddingDimensions()

        // A different size is a different space, so it is pending rather than applied.
        assertEquals(null, scene.viewModel.modelConfig.embedding?.dimensions?.takeIf { it == 2048 })
        assertTrue(
            "the sentence names the size and the cost: ${scene.viewModel.pendingPickedSaid}",
            scene.viewModel.pendingPickedSaid.contains("2048") &&
                scene.viewModel.pendingPickedSaid.contains("rebuilds"),
        )
    }

    @Test
    fun `declining keeps the template that works`() {
        write("a.json")
        scene.viewModel.setEmbeddingSchema("a.json")
        scene.viewModel.cycleEmbeddingDimensions()
        scene.viewModel.cancelPicked()

        assertEquals("a.json", scene.viewModel.modelConfig.embedding?.schema)
        assertEquals("and its size", 1024, scene.viewModel.modelConfig.embedding?.dimensions)
    }

    @Test
    fun `agreeing writes the new size`() {
        write("a.json")
        scene.viewModel.setEmbeddingSchema("a.json")
        scene.viewModel.cycleEmbeddingDimensions()
        scene.viewModel.confirmPicked()

        assertEquals(2048, scene.viewModel.modelConfig.embedding?.dimensions)
    }

    @Test
    fun `a different template for the same job asks, because it may be another model`() {
        // The pointer's name is part of the space (`PickedModel.space`), so pointing at another file is
        // a new coordinate system until the person says so — even if both files happen to name the same
        // model, because the app cannot read "happens to" anywhere it could check.
        write("a.json")
        write("b.json")
        scene.viewModel.setEmbeddingSchema("a.json")
        scene.viewModel.setEmbeddingSchema("b.json")

        assertEquals("a.json is still the one in force", "a.json", scene.viewModel.modelConfig.embedding?.schema)
        assertTrue(scene.viewModel.pendingPickedSaid.isNotEmpty())
    }

    @Test
    fun `clearing the embedding pointer turns the feature off rather than picking one`() {
        write("a.json")
        scene.viewModel.setEmbeddingSchema("a.json")

        scene.viewModel.clearPicked(PickedKind.EMBEDDING)

        // Null, not a default: a model choice nobody made is worse than a feature that says it is off.
        assertEquals(null, scene.viewModel.modelConfig.embedding)
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
