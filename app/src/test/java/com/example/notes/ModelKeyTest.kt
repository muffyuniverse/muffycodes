package com.example.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
                MaterialTheme {
                    Surface { KeyRow(viewModel = locked.viewModel, onSetKey = {}) }
                }
            }

            compose.onNodeWithText("A key is kept for this account", substring = true).assertIsDisplayed()
            compose.onNodeWithText("No OpenRouter key yet", substring = true).assertDoesNotExist()
        } finally {
            locked.close()
        }
    }

    @Test
    fun `a key that cannot be read back is refused, not accepted`() {
        // The save is a write **and a read back**. This store takes the key and cannot give it back,
        // which is what a keystore that refused the write looks like — and it must not be reported as
        // kept, because the person would then wait for an agent that can never talk.
        val forgetful = scenario(ForgetfulStore())
        try {
            val why = forgetful.viewModel.saveModelKey("sk-or-v1-test-key-not-a-real-one")

            assertNotNull("it is refused", why)
            assertTrue("in words about reading it back: $why", "read it back" in why!!)
            assertNull("and nothing claims it was kept", forgetful.credentials.modelKey(forgetful.login))
        } finally {
            forgetful.close()
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
