package com.example.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import org.junit.After
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * §4's history screen, as a person meets it: **one version at a time, turned by a
 * swipe or by the arrows at the corners.**
 *
 * The rest of the suite asks the view model what the versions are. That cannot
 * answer "is the version on the glass the one that was chosen", and this screen has
 * been wrong in both directions before — a list of buttons, then two arrows that
 * hid every version but one. So these tests paint the real screen.
 *
 * The falsification matters as much as the test: a screen that showed *every*
 * version's text at once would pass "the newest version is on the glass" and fail
 * "and the older one is not".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class HistoryScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @After
    fun putThePhoneAway() {
        scene.close()
    }

    private val scene: Scenario by lazy {
        Scenario(RuntimeEnvironment.getApplication(), signIn = true)
    }

    /**
     * A file with four versions, newest first: "four litres of milk", "three
     * litres of milk", "one litre of milk", and the empty file it was created as.
     *
     * Four, not three, because making a file is itself a version: that is the app
     * being honest about what happened, and a screen test that pretended otherwise
     * would be testing a history the app does not have.
     */
    private fun versions(): File {
        val file = scene.file("milk.txt", "one litre of milk")
        scene.openEditor(file)
        scene.saveAsVersion("three litres of milk")
        scene.saveAsVersion("four litres of milk")
        scene.closeEditor()
        scene.viewModel.openHistory(file)
        return file
    }

    @Composable
    private fun history() = MaterialTheme {
        Surface {
            HistoryScreen(
                viewModel = scene.viewModel,
                title = "milk.txt",
                onBack = {},
                onSelect = {},
            )
        }
    }

    /**
     * Waits until the screen shows [text] — **which is an assertion, not a pause.**
     *
     * `waitUntil` throws when the condition never becomes true, so a test built only on
     * `waitFor`/`waitForGone` still fails when the screen is wrong. They are named
     * "wait" because that is what they look like, and the three tests that use nothing
     * else say so here rather than each carrying the same paragraph.
     */
    private fun waitFor(text: String) {
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun waitForGone(text: String) {
        compose.waitUntil(20_000) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test
    fun `the newest version is the one on the glass, and it is the whole of it`() {
        versions()

        compose.setContent { history() }

        waitFor("four litres of milk")
        // The falsification: the older versions are not lying around on other pages
        // of the same screen, so "one version at a time" is what is being shown.
        assertEquals(
            "only one version is drawn",
            0,
            compose.onAllNodesWithText("one litre of milk", substring = true)
                .fetchSemanticsNodes().size,
        )
        assertEquals(
            "and only one copy of the newest",
            1,
            compose.onAllNodesWithText("four litres of milk", substring = true)
                .fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `the arrow at the corner turns back one version`() {
        versions()
        compose.setContent { history() }
        waitFor("four litres of milk")

        compose.onNodeWithText(">").performClick()

        waitFor("three litres of milk")
        waitForGone("four litres of milk")
    }

    @Test
    fun `a swipe turns the page as well as the arrow does`() {
        // A history a person can only move through by finding a small button is a
        // history that does not feel like pages, and the phone's own gesture is a
        // swipe. Same state, same result.
        versions()
        compose.setContent { history() }
        waitFor("four litres of milk")

        compose.onRoot().performTouchInput { swipeLeft() }

        waitFor("three litres of milk")
    }

    @Test
    fun `the oldest version cannot be turned further back, and the newest cannot go on`() {
        versions()
        compose.setContent { history() }
        waitFor("four litres of milk")

        // The newest page is the first page, so there is nothing before it.
        compose.onNodeWithText("<").assertIsNotEnabled()

        compose.onNodeWithText(">").performClick()
        waitFor("three litres of milk")
        compose.onNodeWithText(">").performClick()
        waitFor("one litre of milk")
        compose.onNodeWithText(">").assertIsEnabled()

        // The file's first version is the empty file it was made as, so the last
        // page is what the count says rather than a sentence on the page.
        compose.onNodeWithText(">").performClick()
        waitFor("4 of 4")

        // And the oldest is the last page, so the arrow on that side has nothing to
        // do — while the one going back towards the newest still does.
        compose.onNodeWithText(">").assertIsNotEnabled()
        compose.onNodeWithText("<").assertIsEnabled()
    }

    @Test
    fun `the row above the page says which version this is`() {
        versions()
        compose.setContent { history() }

        // "1 of 4 · newest first": the page number and the order agree, which is
        // what a person needs in order to know which way the arrows go.
        waitFor("1 of 4")

        compose.onNodeWithText(">").performClick()

        waitFor("2 of 4")
    }
}
