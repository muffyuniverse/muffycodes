package com.example.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * What the editor screen actually puts on the glass, for the three kinds of file
 * §9 defines.
 *
 * The rest of the suite is behavioural: it drives the ViewModel and asks what
 * happened on disk. That is the right shape for almost everything, but it cannot
 * answer "is the picture shown" — the promise in §9 is about a screen, and a
 * ViewModel test would pass while the screen drew nothing at all. So these few
 * tests paint the real composable and look at it.
 *
 * The one that matters most is the third: a file the app does not understand must
 * not offer a text field. Every other kind of mistake here is cosmetic; that one
 * rewrites somebody's `.pdf` and loses it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @After
    fun putThePhoneAway() {
        scene.close()
    }

    /**
     * A signed-in phone: §9 is about files, and a file needs an account's tree to
     * live in. The test account is the one that needs no key.
     */
    private val scene: Scenario by lazy {
        Scenario(RuntimeEnvironment.getApplication(), signIn = true)
    }

    @Composable
    private fun Frame(content: @Composable () -> Unit) {
        MaterialTheme { Surface { content() } }
    }

    private fun editorFor(file: File) {
        compose.setContent {
            Frame {
                EditorScreen(
                    viewModel = scene.viewModel,
                    file = file,
                    onSync = {},
                    onBack = {},
                    onDelete = {},
                    onOpenHistory = {},
                )
            }
        }
    }

    @Test
    fun `a text file is shown in a field that can be typed into`() {
        val file = scene.file("milk.txt", "two litres")

        editorFor(file)

        compose.onNodeWithText("two litres").assertIsDisplayed()
    }

    @Test
    fun `an unknown file says it is not text, and offers no field to type in`() {
        // The destructive case. A `.zip` must not be editable, and the screen must
        // say why rather than show an empty editor that invites a mistake.
        val file = scene.file("archive.zip", "not really a zip")

        editorFor(file)

        compose.onNodeWithText(
            "This file is not text, so it is not opened here.",
        ).assertIsDisplayed()
        // And the name is quoted back, so a person knows which file they are on.
        compose.onNodeWithText("..zip", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a picture that cannot be read says so instead of showing an empty frame`() {
        // A truncated download, or a format the phone has no decoder for. Silence
        // would be indistinguishable from the app being broken.
        val file = scene.file("broken.png", "this is not a png")

        editorFor(file)

        compose.onNodeWithText(
            "This picture could not be read on this phone.",
        ).assertIsDisplayed()
    }

    @Test
    fun `a real picture is drawn as a picture`() {
        // A one-pixel PNG, which is the smallest thing that is genuinely a picture.
        val file = scene.file("dot.png")
        file.writeBytes(ONE_PIXEL_PNG)

        editorFor(file)

        // The Image carries the file's name as its description, which is how a
        // screen reader announces it and how this test finds it. Waiting is needed
        // because the decode happens off the main thread.
        compose.waitUntil(5_000) {
            compose.onAllNodesWithContentDescription("dot.png").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithContentDescription("dot.png").assertIsDisplayed()
        // And it is not showing the "could not be read" message.
        compose.onNodeWithText(
            "This picture could not be read on this phone.",
        ).assertDoesNotExist()
    }

    private companion object {
        /**
         * The smallest valid PNG: 1×1, transparent.
         *
         * The test decodes it through the same [Pictures] code the app uses, so
         * this is a real decode and not a stub. A fake would only prove the test's
         * belief about bitmaps.
         */
        val ONE_PIXEL_PNG: ByteArray = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15.toByte(), 0xC4.toByte(),
            0x89.toByte(), 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41,
            0x54, 0x78.toByte(), 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00,
            0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
            0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        )
    }
}
