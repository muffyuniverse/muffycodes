package com.example.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The key the agent spends, and the two ways a store can fail to hand it over.
 *
 * Both are states the real store reaches and the in-memory one never does: a phone whose screen is
 * locked — the ordinary case, because the agent polls with the screen off and the store's own key is
 * only usable while the phone is unlocked — and a write that cannot be read back. Both used to look
 * exactly like "this account has no key", which sent a person to type one they already had.
 *
 * Its own class rather than three more tests in [ModelSectionTest], because a screen test costs
 * memory and that class already draws every shape on the screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ModelKeyTest {

    @get:Rule
    val compose = createComposeRule()

    private fun scenario(store: MemoryCredentials): Scenario =
        Scenario(RuntimeEnvironment.getApplication(), signIn = true, store = store)

    @Test
    fun `a key that is stored and cannot be read now does not read as no key at all`() {
        val locked = scenario(LockedStore())
        try {
            compose.setContent {
                MaterialTheme { Surface { ModelSection(viewModel = locked.viewModel, onSetKey = {}) } }
            }

            compose.onNodeWithText("A key is kept for this account", substring = true).assertIsDisplayed()
            compose.onNodeWithText("No OpenRouter key yet", substring = true).assertDoesNotExist()
        } finally {
            locked.close()
        }
    }

    @Test
    fun `a key that cannot be read back keeps the dialog open with the reason`() {
        // The dialog used to dismiss itself the instant the button was pressed, so a refused key
        // arrived as a snackbar at a screen that was already gone — which reads exactly like a key
        // that was accepted. It closes on an answer now, and a refusal is an answer.
        val forgetful = scenario(ForgetfulStore())
        try {
            compose.setContent {
                MaterialTheme {
                    ModelKeyDialog(
                        onDismiss = {},
                        keep = { key -> forgetful.viewModel.saveModelKey(key) },
                    )
                }
            }

            compose.onNode(hasSetTextAction()).performTextInput("sk-or-v1-test-key-not-a-real-one")
            compose.onNodeWithText("Keep it").performClick()
            compose.waitForIdle()

            compose.onNodeWithText("could not read it back", substring = true).assertIsDisplayed()
            compose.onNodeWithText("Keep it").assertIsDisplayed()
        } finally {
            forgetful.close()
        }
    }
}

/** A store that takes a key and cannot give it back: a keystore that refused the write. */
private class ForgetfulStore : MemoryCredentials() {
    override fun setModelKey(login: String, key: String) = Unit
    override fun modelKey(login: String): String? = null
    override fun modelKeyState(login: String): KeyState = KeyState.NONE
}

/** A store that has the key and will not open it until the phone is unlocked. */
private class LockedStore : MemoryCredentials() {
    override fun setModelKey(login: String, key: String) = Unit
    override fun modelKey(login: String): String? = null
    override fun modelKeyState(login: String): KeyState = KeyState.LOCKED
}
