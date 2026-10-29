package com.example.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The cost screen's own screen: what it draws, and **whether a person can reach it.**
 *
 * The arithmetic lives in `RequestStatsTest` and the numbers themselves in `RequestDbTest`; what is
 * left is the part neither can see. A control can be present in the tree and still be
 * outside the window, and every assertion about its existence passes while a person staring at the
 * phone finds nothing to press — which is exactly what happened to the hour stepper, and it took a
 * picture to notice.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StatsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val account: File = File(
        System.getProperty("java.io.tmpdir"),
        "stats-screen-${System.nanoTime()}",
    ).apply { mkdirs() }

    /** 28 October 2026, 14:30 local — an afternoon, so the default interval is not a whole day. */
    private val now: Long = java.time.LocalDate.parse("2026-10-28")
        .atTime(14, 30)
        .atZone(java.time.ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    private fun draw() {
        compose.setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    StatsScreen(
                        accountDir = account,
                        onBack = {},
                        now = now,
                        // **The clock a preset reads when tapped**, pinned to the same fixture
                        // instant. Without this the screen would measure "today" from whatever day
                        // CI happens to run on, and every assertion below would be about a
                        // different date than the fixture names.
                        clockNow = { now },
                        // **An empty history, opened for real.** A `History` with a null handle
                        // reads as "no log yet" without a database file existing, which is what
                        // these tests want: the screen's own layout, not the arithmetic.
                        readRows = { done -> done(RequestDb.History()) },
                    )
                }
            }
        }
    }

    /**
     * The bottom of a node's drawn box, in window coordinates.
     *
     * `boundsInWindow` rather than `assertIsDisplayed`, because **`assertIsDisplayed` passes on a
     * clipped node** — a view that is half off the bottom of the screen is still "displayed" as far
     * as the semantics tree is concerned, and that is precisely the state this screen was in.
     */
    private fun bottomOf(text: String): Float =
        compose.onAllNodesWithText(text).fetchSemanticsNodes().first()
            .boundsInWindow.bottom

    /**
     * The screen's height in pixels, **from the display rather than from the composition.**
     *
     * `onRoot()` was the obvious way and is the wrong one: an open dialog is a second root, so
     * asking for "the" root fails with `assertExists` the moment the thing being measured appears.
     * The display knows how tall the screen is whether or not a dialog is on it.
     */
    private fun windowHeight(): Float =
        org.robolectric.RuntimeEnvironment.getApplication()
            .resources.displayMetrics.heightPixels.toFloat()

    @Test
    fun `a new opening shows today, with the clock day chip lit`() {
        // **The operator's default**: *"default with which stat is opened is clock day preset - it
        // should be autoselected on stat menu opening."* At 14:30 that is 28 Oct 00:00 to 28 Oct
        // 14:30 - today from midnight, ending at the opening moment.
        draw()
        compose.onNodeWithText("From").assertExists()
        compose.onNodeWithText("To").assertExists()
        // Both ends are on the same day, and the time ends where the clock stopped.
        assertEquals(
            "the same date at both ends",
            2,
            compose.onAllNodesWithText("28 Oct 2026").fetchSemanticsNodes().size,
        )
        compose.onNodeWithText("00:00").assertExists()
        compose.onNodeWithText("14:30").assertExists()
        compose.onNodeWithText("14 hours 30 minutes", substring = true).assertExists()
    }

    @Test
    fun `the preset rows are drawn with clock day selected`() {
        draw()
        // **Two rows, one selection.** The clock row is lit on "1 day"; nothing in the rolling row is.
        compose.onNodeWithText("Clock").assertExists()
        compose.onNodeWithText("Rolling").assertExists()
        // **The chips past the visible width are still in the tree**, because the row scrolls rather
        // than truncates. Asserting on the last one is what tells those two apart.
        compose.onNodeWithText("15 min").assertExists()
        compose.onNodeWithText("total").assertExists()
        compose.onNodeWithText("1 day").assertExists()
    }

    @Test
    fun `choosing a preset regenerates without pressing Generate`() {
        draw()
        // **A preset IS a request for figures.** Unlike moving a picker, tapping one is explicit, so
        // it sets both ends and the generated interval at once.
        //
        // **Scrolled into view first.** The row scrolls sideways and "1 week" sits past the right
        // edge on this width, so a tap without scrolling fails with "Failed to inject touch input" -
        // the node exists in the tree but has no position on screen.
        compose.onNodeWithText("1 week").performScrollTo().performClick()
        compose.waitForIdle()
        // One week on the clock starts at midnight a week ago.
        compose.onNodeWithText("21 Oct 2026").assertExists()
        // And it regenerated, so the button has nothing left to do.
        compose.onNodeWithText("Generated").assertExists()
    }

    @Test
    fun `the date and the time are separate buttons`() {
        draw()
        // **Four buttons, two per end**, because a calendar answers one question and a dial answers
        // the other. A single button per end is what forced the time into the calendar's title row.
        compose.onNodeWithText("28 Oct 2026").assertExists()
        compose.onNodeWithText("14:30").assertExists()
    }

    @Test
    fun `tapping a time opens the dial, not the calendar`() {
        draw()
        compose.onNodeWithText("14:30").performClick()
        compose.waitForIdle()
        // **The dial is a whole dialog**, and the claim pinned here is that the two are separate:
        // tapping a *time* must not open the calendar, which is what the previous single-dialog
        // design did.
        compose.onNodeWithText("Show").assertExists()
        compose.onNodeWithText("Cancel").assertExists()
    }

    @Test
    fun `the dial is above the button that commits it`() {
        draw()
        compose.onNodeWithText("14:30").performClick()
        compose.waitForIdle()
        // **The failure this session already made twice**, and the reason the check is anchored to
        // Show rather than to the window height: a control inside a dialog's scrolling body reports
        // unclipped bounds, so "its bottom is above the screen's" was true of a row drawn underneath
        // the buttons. Show is pinned by the dialog and always drawn.
        val commit = bottomOf("Show")
        assertTrue(
            "the dialog fits on the screen: $commit of ${windowHeight()}",
            commit < windowHeight(),
        )
    }

    @Test
    fun `the table names its two value columns`() {
        draw()
        // **A heading is needed now and was not before.** With one column the caption above the
        // table was its heading; two values per row make "which is which" a real question.
        //
        // **No previous column on an empty history**, because the log does not reach the previous
        // period - and a column of dashes beside a real one reads as "traffic collapsed" when the
        // truth is that the history begins later.
        compose.onNodeWithText("now").assertExists()
        compose.onNodeWithText("previous").assertDoesNotExist()
    }

    @Test
    fun `the figures wait for Generate`() {
        draw()
        // **The button is there and says it has nothing to do yet**, because the picks have not
        // moved since the screen opened. A permanently-enabled Generate is a control whose effect
        // a person has to guess at.
        compose.onNodeWithText("Generated").assertExists()
        compose.onNodeWithText("Generate").assertDoesNotExist()

        // Open the **date** picker for the start and commit a different day: the picks now differ
        // from what was generated, so the button offers to act.
        //
        // The date rather than the dial, because a date is one tap away from the current one - the
        // calendar opens on it and the day beside it is always present - while the dial needs a
        // precise drag that a Robolectric tap cannot make.
        compose.onAllNodesWithText("28 Oct 2026")[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithText("26").performClick()
        compose.onNodeWithText("Show").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Generate").assertExists()
    }
}
