package com.example.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * **The minted model key, and whether the app can honestly say it has one.**
 *
 * Two things are asserted here, and they are different questions:
 *
 * - **A write that cannot be read back is refused, not accepted.** The save is a write *and* a read
 *   back; a keystore that took the key and will not give it back must not be reported as kept, because
 *   the person would then wait for an agent that can never talk.
 * - **A refused save keeps its window open.** It used to close on the press, so the refusal arrived at
 *   a screen that was already gone.
 *
 * **What changed on 2026-11-10 (§3.11.4).** The key is no longer `model_key_<login>` — a record of its
 * own beside the vault. Minting writes the vault's `@openrouter` secret, so the read-back is the
 * vault's, and the question "is there a key" is `hasModelKey`, which asks the vault for that name at
 * OpenRouter's own host. There is one record now, which is why `ConnKind.LLM` had nothing left to do.
 *
 * Its own class rather than more tests in [ModelSectionTest], because a screen test costs memory and
 * that class already draws every shape on the screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ModelKeyTest {

    @get:Rule
    val compose = createComposeRule()

    private fun scenario(store: MemoryCredentials): Scenario =
        Scenario(RuntimeEnvironment.getApplication(), signIn = true, store = store)

    @Test
    fun `a key that cannot be read back is refused, not accepted`() {
        val forgetful = scenario(ForgetfulStore())
        try {
            val why = forgetful.viewModel.saveMintedKey("sk-or-v1-test-key-not-a-real-one")

            assertNotNull("it is refused", why)
            assertTrue("in words about reading it back: $why", "read it back" in why!!)
            assertNull(
                "and nothing claims it was kept",
                forgetful.credentials.secret(
                    project = null,
                    branch = null,
                    name = Schemas.MINTED_KEY_NAME,
                    host = Secrets.hostOf(OPENROUTER_BASE),
                ),
            )
        } finally {
            forgetful.close()
        }
    }

    @Test
    fun `a minted key is kept as the vault row a form names`() {
        // **The shape the operator asked for**: *"it should be used to basically mint an openrouter key
        // as a secret that then could be given an access scope."* So the row is a normal vault secret
        // with the name the shipped form already says (`"key": "@openrouter"`), and nothing else is
        // written — no second record for the chat to read.
        val scene = scenario(MemoryCredentials())
        try {
            assertNull("nothing to begin with", scene.viewModel.saveMintedKey("sk-or-v1-a-key"))

            assertTrue("and the app now says it has a model key", scene.viewModel.hasModelKey)
            assertEquals(
                "kept under the name the form names",
                "sk-or-v1-a-key",
                scene.credentials.secret(
                    project = null,
                    branch = null,
                    name = Schemas.MINTED_KEY_NAME,
                    host = Secrets.hostOf(OPENROUTER_BASE),
                ),
            )
        } finally {
            scene.close()
        }
    }

    @Test
    fun `the row says which name it wrote, so a form can be matched to it`() {
        // The commonest confusion is a form that names a secret nobody wrote. Saying the name on the
        // settings row is what makes that answerable without opening the vault and guessing.
        val scene = scenario(MemoryCredentials())
        try {
            scene.viewModel.saveMintedKey("sk-or-v1-a-key")
            compose.setContent {
                MaterialTheme {
                    Surface { MintedKeyRow(viewModel = scene.viewModel, onSetKey = {}) }
                }
            }

            compose.onNodeWithText("@${Schemas.MINTED_KEY_NAME}", substring = true).assertIsDisplayed()
            compose.onNodeWithText("No model key yet", substring = true).assertDoesNotExist()
        } finally {
            scene.close()
        }
    }

    @Test
    fun `a kept key closes the window and a refused one does not`() {
        // **The rule the report was about.** The dialog used to close on the press, so a key that
        // could not be read back was reported as kept — the refusal arrived at a screen that was
        // already gone. Only an answer closes it, and a refusal *is* an answer: the window stays
        // with the sentence in it.
        var closed = false

        val kept = keySaveVerdict(null) { closed = true }
        assertNull("a kept key says nothing", kept)
        assertTrue("and the window closes", closed)

        closed = false
        val refused = keySaveVerdict("The key was stored, and this phone could not read it back.") {
            closed = true
        }
        assertNotNull("a refused key has something to say", refused)
        assertTrue("about reading it back: $refused", "read it back" in refused!!)
        assertFalse("and the window stays open", closed)
    }
}

/** A store that takes a secret and cannot give it back: a keystore that refused the write. */
private class ForgetfulStore : MemoryCredentials() {
    override fun setSecret(name: String, value: String, host: String) = Unit
    override fun secret(name: String, host: String): String? = null
}
