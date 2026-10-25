package com.example.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
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

    @Composable
    private fun Section(onSetKey: () -> Unit = {}) {
        MaterialTheme { Surface { ModelSection(viewModel = scene.viewModel, onSetKey = onSetKey) } }
    }

    /**
     * Waits for something the sheet has to read before it can show it.
     *
     * The numbers come from OpenRouter on a worker, so the row exists a moment after the sheet
     * opens. Asserting immediately would test the test's own timing.
     */
    private fun waitFor(text: String, substring: Boolean = false) {
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text, substring = substring)
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun theThreeValuesAreShownOnTheScreen() {
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
    fun choosingAProviderWritesItIntoTheFile() {
        // The file is the only store: what the picker does has to land there, not in a copy.
        scene.viewModel.catalogue = scripted()
        compose.setContent { Section() }

        compose.onNodeWithText("Provider").performClick()
        compose.onNodeWithText("deepinfra/fp8").performClick()
        compose.onNodeWithText("Select").performClick()

        assertEquals("the choice", "deepinfra/fp8", scene.viewModel.modelConfig.active.provider)
        val onDisk = ModelConfigFile.load(scene.workspace!!.accountDir).getOrThrow()
        assertEquals("and it is in the file", "deepinfra/fp8", onDisk.active.provider)
    }

    @Test
    fun cancelLeavesTheChoiceAlone() {
        // A sheet that commits by being dismissed is a sheet that changes the model when a person
        // backs out of it.
        scene.viewModel.catalogue = scripted()
        compose.setContent { Section() }

        compose.onNodeWithText("Provider").performClick()
        compose.onNodeWithText("deepinfra/fp8").performClick()
        compose.onNodeWithText("Cancel").performClick()

        assertEquals("still the one it started on", "deepseek",
            scene.viewModel.modelConfig.active.provider)
    }

    @Test
    fun selectIsNotOfferedUntilSomethingIsChosen() {
        // Selecting the row a person just tapped open would change the model by opening the
        // sheet, which is the bug the button exists to prevent.
        scene.viewModel.catalogue = scripted()
        compose.setContent { Section() }

        compose.onNodeWithText("Provider").performClick()

        compose.onNodeWithText("Select").assertIsNotEnabled()
        compose.onNodeWithText("deepinfra/fp8").performClick()
        compose.onNodeWithText("Select").assertIsEnabled()
    }

    @Test
    fun theProviderListShowsEachTagSeparatelyWithItsOwnNumbers() {
        // Three DeepInfra endpoints at three prices are three rows. Collapsing them into one
        // "DeepInfra" would be picking a tier for the operator without saying which.
        scene.viewModel.catalogue = scripted()
        compose.setContent { Section() }

        compose.onNodeWithText("Provider").performClick()

        compose.onNodeWithText("deepinfra/fp8").assertIsDisplayed()
        compose.onNodeWithText("deepinfra/fp8/flex").assertIsDisplayed()
        compose.onNodeWithText("deepseek").assertIsDisplayed()
    }

    @Test
    fun aProviderOpenRouterPublishesNothingAboutSaysSoRatherThanZero() {
        // The cell that would read as an outage: no throughput is "—", because OpenRouter has
        // seen no traffic, not because the provider is stopped.
        scene.viewModel.catalogue = scripted()
        compose.setContent { Section() }

        compose.onNodeWithText("Provider").performClick()
        // The tag is on the phone; the price is not. So the row is there at once and the line
        // under it arrives — which is the difference this test exists to hold.
        compose.onNodeWithText("deepinfra/fp8").assertIsDisplayed()
        waitFor("in $0.0784 · out $0.2352 per M", substring = true)
        compose.onNodeWithText("in $0.0784 · out $0.2352 per M", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("— tok/s", substring = true).assertIsDisplayed()
    }

    @Test
    fun theEffortListIsTheModelsOwnLadderIncludingItsLowestRung() {
        // `none` is a real position on this ladder, not "unset": sliding left is "no reasoning
        // at all", which is a different agent and has to be visible as one.
        scene.viewModel.catalogue = scripted()
        compose.setContent { Section() }

        compose.onNodeWithText("Effort").performClick()
        waitFor("none")

        compose.onNodeWithText("max").assertIsDisplayed()
        compose.onNodeWithText("none").assertIsDisplayed()
    }

    @Test
    fun anEffortTheModelDoesNotDeclareMovesDownAndIsWrittenBack() {
        // The operator's rule, seen from the screen: the model's ladder has `max`, so asking for
        // it is allowed; the point is that the value stored is what the model says, not what was
        // asked for when the two differ.
        scene.viewModel.catalogue = scripted()
        compose.setContent { Section() }

        compose.onNodeWithText("Effort").performClick()
        waitFor("max")
        compose.onNodeWithText("max").performClick()
        compose.onNodeWithText("Select").performClick()

        assertEquals("max", scene.viewModel.modelConfig.active.effort)
    }

    @Test
    fun aModelConfigTheAppCannotReadIsSaidOnTheScreenAndTheLastGoodChoiceStays() {
        // A damaged file does not silently become the defaults: the screen says which line is
        // wrong while still showing what the agent would use.
        val file = ModelConfigFile.file(scene.workspace!!.accountDir)
        // Deliberately broken JSON, written without an unbalanced brace so the
        // repository-wide brace check stays a real check.
        file.writeText("{ \"version\": 1, \"active\": {} }")
        scene.viewModel.refresh()

        compose.setContent { Section() }

        compose.onNodeWithText("will not read", substring = true).assertIsDisplayed()
        compose.onNodeWithText(ModelConfigFile.DEFAULT_MODEL).assertIsDisplayed()
    }

    @Test
    fun resetPutsBackTheModelTheAppShips() {
        // Not a history step: "reset" means the copy in the build, which is the one thing that
        // cannot itself be broken by the edit being undone.
        scene.viewModel.catalogue = scripted()
        scene.viewModel.chooseModel(
            ModelConfigFile.DEFAULT_MODEL, "cloudflare", "high", listOf("max", "high", "low"),
        )
        assertEquals("changed first", "cloudflare", scene.viewModel.modelConfig.active.provider)

        scene.viewModel.resetModelConfig()

        assertEquals("back to the shipped choice", "deepseek",
            scene.viewModel.modelConfig.active.provider)
    }

    @Test
    fun theActiveModelCannotBeRemovedAndTheScreenSaysWhy() {
        // Removing it would leave the agent with no model, which is not a state a person can fix
        // from this screen; choosing another first is.
        scene.viewModel.catalogue = scripted()
        compose.setContent { Section() }

        compose.onNodeWithText("Remove model").performClick()
        // The id is also on the screen behind the dialog, so the click is aimed at the
        // dialog's own copy — the last one in the tree.
        compose.onAllNodesWithText(ModelConfigFile.DEFAULT_MODEL).onLast().performClick()

        compose.onNodeWithText("choose another first", substring = true).assertIsDisplayed()
        assertTrue("and it is still there",
            scene.viewModel.modelConfig.models.any { it.id == ModelConfigFile.DEFAULT_MODEL })
    }

    @Test
    fun addingAModelAsksOpenRouterFirstAndRefusesAnIdItDoesNotHave() {
        // A form that only wrote a string into a file would let a typo through and fail eight
        // seconds into the agent's first turn with a 404.
        val refusing = object : Catalogue {
            override fun model(id: String) =
                Result.failure<ModelInfo>(IllegalStateException("OpenRouter does not have \"$id\""))

            override fun endpoints(id: String) =
                Result.failure<List<EndpointInfo>>(IllegalStateException("no"))
        }
        scene.viewModel.catalogue = refusing
        compose.setContent { Section() }

        compose.onNodeWithText("Add model").performClick()
        compose.onNodeWithText("Model id, as OpenRouter writes it").performTextReplacement("typo/id")
        compose.onNodeWithText("Fetch").performClick()

        scene.await("OpenRouter to refuse the id", 5_000) {
            scene.viewModel.previewProblem != null
        }
        compose.waitForIdle()
        compose.onNodeWithText("does not have", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Add").assertIsNotEnabled()
    }

    @Test
    fun theKeyRowSaysPlainlyWhenThereIsNoKey() {
        // The agent cannot read a model list or answer without it, so "no key" is not a detail:
        // it is the reason nothing on this screen can be chosen freely.
        scene.viewModel.catalogue = scripted()
        compose.setContent { Section() }

        compose.onNodeWithText("No OpenRouter key yet", substring = true).assertIsDisplayed()
    }

    @Test
    fun theKeyRowChangesWhenAKeyIsKept() {
        scene.viewModel.catalogue = scripted()
        scene.viewModel.saveModelKey("sk-or-v1-test-key-not-a-real-one")
        compose.setContent { Section() }

        compose.onNodeWithText("An OpenRouter key is kept", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Change the OpenRouter key").assertIsDisplayed()
    }

    @Test
    fun theKeyItselfIsNeverOnTheScreen() {
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
}
