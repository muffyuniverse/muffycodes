package com.example.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import org.robolectric.RuntimeEnvironment
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws the screens and saves them as pictures, on the plain JVM.
 *
 * This is the tour that ships with every build, so it has to be quick and it has
 * to run anywhere — which is why it is Robolectric and not an emulator. There is
 * no device and no waiting; a screen is built, drawn to a bitmap and written to a
 * file in milliseconds.
 *
 * What these pictures are for is the question "does the feature work, and is the
 * screen I expected actually there". What they are NOT for is "does it look
 * right": a JVM draws its own fonts and spacing, and the truth of appearance is
 * the operator's eyes on a real install. Read these as evidence that the screens
 * render — not as the final word on how they look.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTourTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun onlyWhenRecording() {
        // A picture is only taken when the tour is asked for. Ordinary test runs
        // skip this class entirely, so they stay fast and leave no files behind.
        Assume.assumeTrue(System.getProperty("roborazzi.test.record") == "true")
    }

    private val viewModel: AppViewModel by lazy {
        AppViewModel(RuntimeEnvironment.getApplication())
    }

    /** Draws [content] with a theme and writes it to the tour's folder. */
    private fun shot(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            val settings = viewModel.settings
            MaterialTheme(
                colorScheme = if (settings.useDark) settings.theme.darkScheme()
                else settings.theme.lightScheme(),
            ) {
                Surface { content() }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name")
    }

    @Test
    fun theBrowserWithNoAccountYet() {
        shot("tour-01-no-account.png") {
            BrowserScreen(
                viewModel = viewModel,
                username = null,
                onOpenSettings = {},
                onOpenAccount = {},
                actions = emptyActions(),
                onAddProject = {},
                onRowSync = {},
                onRowRename = {},
                onRowDelete = {},
                onCreateFile = {},
                onCreateFolder = {},
            )
        }
    }

    @Test
    fun theSettingsScreen() {
        shot("tour-02-settings.png") {
            SettingsScreen(
                viewModel = viewModel,
                onBack = {},
                onSignIn = {},
                onRemoveAccount = {},
                onEditIdentity = {},
            )
        }
    }

    private fun emptyActions() = BrowserActions(
        onOpen = {},
        onRename = {},
        onMove = {},
        onDelete = {},
        onTogglePin = {},
        onHistory = {},
        onMoveIntoRepository = {},
        onKeepOnlyOnPhone = {},
    )
}
