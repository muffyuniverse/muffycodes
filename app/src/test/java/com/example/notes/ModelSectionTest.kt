package com.example.notes

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
 * The model rows, painted for real.
 *
 * The three values are one choice made in steps, and each step changes what the next one offers:
 * the effort ladder belongs to the model, and the provider has to be one the model lists. So the
 * questions here are about what the screen shows after each press, which a unit test of the file
 * cannot answer.
 *
 * **OpenRouter is answered by a script, never by a socket**, which is the rule the whole suite
 * keeps: a picker that could only be checked by paying for a key is a picker checked by hand.
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

    /** Answers the two catalogue calls from fixed data, the way the app would read them. */
    private fun scripted(endpoints: List<EndpointInfo> = DEFAULT_ENDPOINTS): Catalogue =
        object : Catalogue {
            override fun model(id: String): Result<ModelInfo> = Result.success(
                ModelInfo(
                    id = id,
                    name = "DeepSeek: DeepSeek V4.1 Flash 1017",
                    releasedAt = 1792225624L,
                    contextLength = 1048576,
                    maxOutput = 393216,
                    inputModalities = listOf("text", "image"),
                    outputModalities = listOf("text"),
                    intelligence = 39.5,
                    efforts = listOf("max", "high", "low", "none"),
                    defaultEffort = "high",
                )
            )

            override fun endpoints(id: String): Result<List<EndpointInfo>> = Result.success(endpoints)
        }

    /**
     * The section's rows, and the key above them — which is where both are now.
     *
     * The key stopped being the last line of the model rows and became the first field of the
     * agent block. This harness draws them in that order, so a test that asks about either is
     * asking about the screen a person sees.
     */
    @Composable
    private fun Section(onSetKey: () -> Unit = {}) {
        MaterialTheme {
            Surface {
                Column {
                    KeyRow(viewModel = scene.viewModel, onSetKey = onSetKey)
                    ModelSection(viewModel = scene.viewModel)
                }
            }
        }
    }

    @Test
    fun `the three values are shown on the screen`() {
        scene.viewModel.catalogue = scripted()
        compose.setContent { Section() }

        compose.onNodeWithText("Model").assertIsDisplayed()
        compose.onNodeWithText("Provider").assertIsDisplayed()
        compose.onNodeWithText("Effort").assertIsDisplayed()
        compose.onNodeWithText(ModelConfigFile.DEFAULT_MODEL).assertIsDisplayed()
        compose.onNodeWithText("deepseek").assertIsDisplayed()
        compose.onNodeWithText("high").assertIsDisplayed()
    }

    @Test
    fun `choosing a provider writes it into the file`() {
        // The file is the only store, so the claim is about the file: what the picker sets has to
        // land there rather than in a copy held by a screen.
        //
        // Driven through the view model rather than through the sheet, because this harness
        // composes a dialog's content and then reports it as not displayed — an assertion made
        // against it would be measuring the harness, not the app. The sheet's own rendering is
        // covered by the rows above, which are outside any dialog.
        scene.viewModel.catalogue = scripted()
        scene.viewModel.chooseModel(
            ModelConfigFile.DEFAULT_MODEL, "deepinfra/fp8", "high", listOf("max", "high", "low"),
        )

        assertEquals("the choice", "deepinfra/fp8", scene.viewModel.modelConfig.active.provider)
        val onDisk = ModelConfigFile.load(scene.workspace!!.accountDir).getOrThrow()
        assertEquals("and it is in the file", "deepinfra/fp8", onDisk.active.provider)
    }

    @Test
    fun `moving a provider changes the fallback order, and the file is the record of it`() {
        // **Report 25**: "I want ability to rearrange provider endpoints on the provider selection
        // card ... so that their order is the fallback order". The order is now load-bearing - the
        // door walks it when an endpoint fails - so a move has to be a change to the *file*, not a
        // rearrangement of a list the screen keeps.
        //
        // Driven through the view model, for the reason the neighbouring test gives: this harness
        // reports a dialog's content as not displayed, so an assertion made by tapping inside one
        // measures the harness rather than the app.
        val model = ModelConfigFile.DEFAULT_MODEL
        scene.viewModel.addProvider(model, "deepinfra/fp8")
        scene.viewModel.addProvider(model, "cloudflare")
        val before = scene.viewModel.modelConfig.providersOf(model)
        assertTrue("the fixture has at least three endpoints: $before", before.size >= 3)

        // Move the second endpoint to the front.
        scene.viewModel.moveProvider(model, 1, 0)

        val after = scene.viewModel.modelConfig.providersOf(model)
        assertEquals("the moved endpoint is first", before[1], after[0])
        assertEquals("and nothing was lost", before.size, after.size)
        assertEquals("and the rest kept their order", before[0], after[1])

        val onDisk = ModelConfigFile.load(scene.workspace!!.accountDir).getOrThrow()
        assertEquals("the file holds the new order", after, onDisk.providersOf(model))
    }

    @Test
    fun `a move that would go past either end does nothing`() {
        // **A drag that ends where it started is something a finger does constantly**, and so is a
        // press on an arrow at the top of the list. Neither is an error, and neither may reorder
        // anything: an out-of-range move that wrapped would send the first endpoint to the bottom on
        // a mis-tap, which is the one way this could lose the person's arrangement.
        val model = ModelConfigFile.DEFAULT_MODEL
        val before = scene.viewModel.modelConfig.providersOf(model)
        assertTrue("the fixture has more than one", before.size > 1)

        scene.viewModel.moveProvider(model, 0, -1)
        scene.viewModel.moveProvider(model, before.lastIndex, before.size)
        scene.viewModel.moveProvider(model, 0, 0)

        assertEquals(
            "the order is untouched",
            before,
            scene.viewModel.modelConfig.providersOf(model),
        )
    }

    @Test
    fun `changing provider carries that provider's reply reserve, not the old one`() {
        // **The 96k bug with the sign flipped.** The window and the reply reserve are facts about a
        // *pin*, not about the model: `deepinfra/fp8` keeps 131,072 of a 1,048,576 window and
        // `cloudflare` keeps 943,718 of the same window. Carrying the old reserve across a provider
        // change left the agent clamped to an endpoint it was no longer using — the app would have
        // trimmed at 917,504 on an endpoint that only accepts 104,858.
        scene.viewModel.catalogue = scripted(
            listOf(
                endpoint("deepinfra/fp8", reply = 131_072),
                endpoint("cloudflare", reply = 943_718),
            ),
        )
        scene.viewModel.readCatalogue(ModelConfigFile.DEFAULT_MODEL)
        scene.await("the endpoints", 5_000) { scene.viewModel.modelEndpoints.size == 2 }

        scene.viewModel.chooseModel(
            ModelConfigFile.DEFAULT_MODEL, "deepinfra/fp8", "high", listOf("max", "high", "low"),
            contextLength = 1_048_576, maxOutput = 131_072,
        )
        scene.viewModel.chooseModel(
            ModelConfigFile.DEFAULT_MODEL, "cloudflare", "high", listOf("max", "high", "low"),
            contextLength = 1_048_576, maxOutput = 943_718,
        )

        assertEquals("the new reserve", 943_718, scene.viewModel.modelConfig.active.maxOutput)
        assertEquals(
            "and the clamp follows it",
            104_858L,
            ContextBudget.ceiling(
                scene.viewModel.modelConfig.active.provider,
                scene.viewModel.modelConfig.active.contextLength,
                scene.viewModel.modelConfig.active.maxOutput,
            ),
        )
    }

    /** One endpoint, with only the two numbers this file's tests care about. */
    private fun endpoint(tag: String, reply: Int) = EndpointInfo(
        tag = tag, providerName = tag, contextLength = 1_048_576, maxPrompt = null,
        maxOutput = reply, priceIn = 0.1, priceOut = 0.2, priceCache = null,
        tokensPerSecond = null, uptime = null, tools = true,
    )

    @Test
    fun `a provider that cannot call tools is not one the agent can use`() {
        // The add form greys these out, because a provider without tool support cannot run an
        // agent at all. The fact is on the endpoint, so this is where it is asserted.
        val noTools = listOf(
            EndpointInfo(
                tag = "somewhere/text-only", providerName = "Somewhere", contextLength = 1000,
                maxPrompt = null, maxOutput = 1000, priceIn = 0.1, priceOut = 0.2,
                priceCache = null, tokensPerSecond = null, uptime = null, tools = false,
            )
        )
        scene.viewModel.catalogue = scripted(noTools)

        scene.viewModel.readCatalogue(ModelConfigFile.DEFAULT_MODEL)
        scene.await("the endpoints", 5_000) { scene.viewModel.modelEndpoints.isNotEmpty() }

        assertTrue(
            "the endpoint says it cannot call tools",
            scene.viewModel.modelEndpoints.none { it.tools },
        )
    }

    @Test
    fun `a model with nothing in its catalogue cannot be read`() {
        // A model whose endpoint list is empty is a failure, not an empty screen: "no providers"
        // and "no answer yet" look the same on a phone and mean different things.
        scene.viewModel.catalogue = object : Catalogue {
            override fun model(id: String) = Result.success(
                ModelInfo(id, "x", null, 1000, 1000, emptyList(), emptyList(), null, emptyList(), null)
            )

            override fun endpoints(id: String) =
                Result.failure<List<EndpointInfo>>(IllegalArgumentException("no providers"))
        }

        scene.viewModel.readCatalogue("x")
        scene.await("the refusal", 5_000) { scene.viewModel.catalogueProblem != null }

        assertTrue(
            "it says why: ${scene.viewModel.catalogueProblem}",
            scene.viewModel.catalogueProblem!!.contains("no providers"),
        )
    }

    @Test
    fun `a model config the app cannot read is said on the screen and the last good choice stays`() {
        // A damaged file does not quietly become the defaults: the screen says the file will not
        // read while still showing what the agent would use. Both are true at once, and a person
        // needs both.
        val file = ModelConfigFile.file(scene.workspace!!.accountDir)
        // Broken JSON, written without an unbalanced brace so the repository-wide brace check
        // stays a real check.
        file.writeText("{ \"version\": 1, \"active\": {} }")
        scene.viewModel.refresh()

        compose.setContent { Section() }

        compose.onNodeWithText("will not read", substring = true).assertIsDisplayed()
        compose.onNodeWithText(ModelConfigFile.DEFAULT_MODEL).assertIsDisplayed()
    }

    @Test
    fun `reset puts back the model the app ships`() {
        // Not a history step: "reset" means the copy in the build, which is the one thing that
        // cannot itself be broken by the edit being undone.
        scene.viewModel.catalogue = scripted()
        scene.viewModel.chooseModel(
            ModelConfigFile.DEFAULT_MODEL, "cloudflare", "high", listOf("max", "high", "low"),
        )
        assertEquals("changed first", "cloudflare", scene.viewModel.modelConfig.active.provider)

        scene.viewModel.resetModelConfig()

        assertEquals(
            "back to the shipped choice",
            "deepseek",
            scene.viewModel.modelConfig.active.provider,
        )
    }

    @Test
    fun `the active model cannot be removed and the choice says why`() {
        // Removing it would leave the agent with no model, which is not a state a person can fix
        // from this screen; choosing another first is.
        val refusal = scene.viewModel.removeModel(ModelConfigFile.DEFAULT_MODEL)

        assertTrue("refused", refusal.isFailure)
        assertTrue(
            "and it says what to do instead: ${refusal.exceptionOrNull()?.message}",
            refusal.exceptionOrNull()!!.message!!.contains("choose another first"),
        )
        assertTrue(
            "and the model is still there",
            scene.viewModel.modelConfig.models.any { it.id == ModelConfigFile.DEFAULT_MODEL },
        )
    }

    @Test
    fun `adding a model with no providers is refused`() {
        assertTrue("nothing to pin to", scene.viewModel.addModel("x/y", emptyList()).isFailure)
        assertTrue(
            "and one that is already there",
            scene.viewModel.addModel(ModelConfigFile.DEFAULT_MODEL, listOf("deepseek")).isFailure,
        )
    }

    @Test
    fun `the context line names the window, the reply and what is left for input`() {
        // **Reported from the live log, twice.** "context 900k of 1000k — what does that mean? I
        // somehow have the context full or what?" and then, after a rewording, "is it what provider
        // can take out of what model can?". The second question was the right reading, and a line
        // that needs it asked twice is a line that does not say what its numbers are.
        //
        // Three numbers now, each named: the model's window, the room this endpoint keeps for its
        // reply, and the input that is left. The middle one is the whole difference between the
        // other two, so it is on the line rather than left to be subtracted in a person's head.
        scene.viewModel.catalogue = scripted()
        compose.setContent { Section() }

        scene.await("the catalogue", 5_000) { scene.viewModel.modelInfo != null }

        // 1,048,576 window, 393,216 kept by the model — the deepseek pin is 655,360.
        compose.onNodeWithText("Context: 1.0M", substring = true).assertIsDisplayed()
        compose.onNodeWithText("reply 393k", substring = true).assertIsDisplayed()
        compose.onNodeWithText("input 655k", substring = true).assertIsDisplayed()
    }

    @Test
    fun `there is no button to press to find out, because the screen reads it itself`() {
        // The button was the only way to ask, so the screen showed the floor until somebody
        // pressed it — the same floor that once had a million-token model trimmed at 96k. One
        // line that is always current beats a line and a control that explains it.
        scene.viewModel.catalogue = scripted()
        compose.setContent { Section() }

        scene.await("the read the screen does for itself", 5_000) {
            scene.viewModel.modelInfo != null
        }

        compose.onNodeWithText("Read").assertDoesNotExist()
    }

    @Test
    fun `the numbers are read on arrival, for the model actually in use`() {
        // The button used to be the only way to find out, which meant a screen that showed the
        // floor — the same floor that once had a million-token model trimmed at 96k. Arriving at
        // the screen is now enough.
        scene.viewModel.catalogue = scripted()
        compose.setContent { Section() }

        scene.await("the read the screen does for itself", 5_000) {
            scene.viewModel.modelInfo?.contextLength == 1048576
        }

        assertEquals(
            "and it is the model in use that was asked about",
            ModelConfigFile.DEFAULT_MODEL,
            scene.viewModel.modelInfo!!.id,
        )
    }

    @Test
    fun `the key row says plainly when there is no key`() {
        // The agent cannot read a model list or answer without it, so "no key" is not a detail:
        // it is the reason nothing on this screen can be chosen freely.
        scene.viewModel.catalogue = scripted()
        compose.setContent { Section() }

        compose.onNodeWithText("No OpenRouter key yet", substring = true).assertIsDisplayed()
    }

    @Test
    fun `the key row changes when a key is kept`() {
        scene.viewModel.catalogue = scripted()
        scene.viewModel.saveModelKey("sk-or-v1-test-key-not-a-real-one")
        compose.setContent { Section() }

        compose.onNodeWithText("An OpenRouter key is kept", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Change key").assertIsDisplayed()
    }

    @Test
    fun `the key itself is never on the screen`() {
        // The same rule as the GitHub token: it can be typed and kept, and it is never shown
        // back. A screen that could show it is a screenshot away from a spend.
        scene.viewModel.catalogue = scripted()
        scene.viewModel.saveModelKey("sk-or-v1-test-key-not-a-real-one")
        compose.setContent { Section() }

        compose.onNodeWithText("sk-or-v1", substring = true).assertDoesNotExist()
    }

    private companion object {
        val DEFAULT_ENDPOINTS = listOf(
            EndpointInfo(
                tag = "deepseek", providerName = "DeepSeek", contextLength = 1048576,
                maxPrompt = null, maxOutput = 393216,
                priceIn = 0.15, priceOut = 0.60, priceCache = 0.003,
                tokensPerSecond = null, uptime = null, tools = true,
            ),
            EndpointInfo(
                tag = "deepinfra/fp8", providerName = "DeepInfra", contextLength = 1048576,
                maxPrompt = null, maxOutput = 131072,
                priceIn = 0.098, priceOut = 0.294, priceCache = 0.0029,
                tokensPerSecond = null, uptime = null, tools = true,
            ),
            EndpointInfo(
                tag = "deepinfra/fp8/flex", providerName = "DeepInfra", contextLength = 1048576,
                maxPrompt = null, maxOutput = 131072,
                priceIn = 0.0784, priceOut = 0.2352, priceCache = 0.0024,
                tokensPerSecond = null, uptime = null, tools = true,
            ),
        )
    }
    @Test
    fun `a key kept while the screen is up changes the row`() {
        // **The reported failure.** The row is drawn from a plain property, so Compose had no reason
        // to read it again after a dialog wrote — and a person who had just kept their key saw the
        // screen still saying "No OpenRouter key yet", twice, and reported that the key was not
        // passing through.
        scene.viewModel.catalogue = scripted()
        compose.setContent { Section() }
        compose.onNodeWithText("No OpenRouter key yet", substring = true).assertIsDisplayed()

        compose.runOnIdle { scene.viewModel.saveModelKey("sk-or-v1-test-key-not-a-real-one") }
        compose.waitForIdle()

        compose.onNodeWithText("An OpenRouter key is kept", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Change key").assertIsDisplayed()
    }
}
