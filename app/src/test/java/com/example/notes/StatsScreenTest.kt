package com.example.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
    fun `the two picks are drawn with their instants`() {
        draw()
        // **Midnight to the end of the minute `now` is in.** At 14:30 that is 14:31 - the minute
        // 14:30 counts, because an end is where the interval STOPS. Not 15:00, which was the answer
        // while the granularity was the hour.
        compose.onNodeWithText("From").assertExists()
        compose.onNodeWithText("To").assertExists()
        compose.onNodeWithText("28 Oct 2026, 00:00", substring = true).assertExists()
        compose.onNodeWithText("28 Oct 2026, 14:31", substring = true).assertExists()
    }

    @Test
    fun `the picker's hour can be reached, not merely present`() {
        draw()
        compose.onNodeWithText("00:00", substring = true).performClick()
        compose.waitForIdle()

        // **The hour stepper is the control this session added**, and on a 320x470 screen it sat
        // below the bottom of the dialog: present in the tree, unreachable by a thumb.
        //
        // **Anchored to Show rather than to the window's height, because the height check passed
        // while the control was invisible.** A node inside the dialog's scrolling body reports
        // unclipped bounds, so "its bottom is less than the screen's height" was true of a row
        // drawn underneath the buttons. Show is pinned by Material and always drawn, so "the hour
        // is above Show" is the claim that actually distinguishes the two states.
        val at = bottomOf("At")
        val commit = bottomOf("Show")
        assertTrue(
            "the hour row is above the button that commits it: $at vs $commit",
            at < commit,
        )
        assertTrue(
            "and both are on the screen: $commit of ${windowHeight()}",
            commit < windowHeight(),
        )
    }

    @Test
    fun `the dialog offers minutes as well as hours`() {
        draw()
        compose.onNodeWithText("00:00", substring = true).performClick()
        compose.waitForIdle()
        // **Both steppers, because the log keeps a minute table.** This is the reverse of the rule
        // that held while the finest summary was an hour: then, offering a minute let somebody pick
        // 19:23 for a table that could only count whole hours. Now 19:23 is exactly what the figure
        // covers.
        compose.onNodeWithText("minute").assertExists()
        compose.onNodeWithText("hour").assertExists()
    }

    @Test
    fun `the table says how long its interval is`() {
        draw()
        // **14 hours 31 minutes, and it says both.** Midnight to 14:31 is not a whole number of
        // hours, so the span carries the minutes rather than rounding to "14 hours" and hiding the
        // extent it actually covers.
        compose.onNodeWithText("14 hours 31 minutes", substring = true).assertExists()
    }

    @Test
    fun `the figures wait for Generate`() {
        draw()
        // **The button is there and says it has nothing to do yet**, because the picks have not
        // moved since the screen opened. A permanently-enabled Generate is a control whose effect
        // a person has to guess at.
        compose.onNodeWithText("Generated").assertExists()
        compose.onNodeWithText("Generate").assertDoesNotExist()

        // Open the From picker, move the hour on, and commit: the picks now differ from what was
        // generated, so the button offers to act.
        compose.onNodeWithText("00:00", substring = true).performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("+").fetchSemanticsNodes().let {
            assertTrue("the steppers are reachable", it.isNotEmpty())
        }
        compose.onAllNodesWithText("+")[0].performClick()
        compose.onNodeWithText("Show").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Generate").assertExists()
    }
}
