package com.example.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
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
 * The arithmetic lives in `RequestStatsTest` and the numbers in the log live in `RequestLogTest`;
 * what is left is the part neither can see. A control can be present in the tree and still be
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
                        readRows = { done -> done(emptyList()) },
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

    /** The window's own height, so "off the bottom" can be decided rather than guessed. */
    private fun windowHeight(): Float = compose.onRoot().fetchSemanticsNode().boundsInWindow.height

    @Test
    fun `the two picks are drawn with their instants`() {
        draw()
        // The default interval: midnight to the next hour boundary after 14:30, which is 15:00.
        compose.onNodeWithText("From").assertExists()
        compose.onNodeWithText("To").assertExists()
        compose.onNodeWithText("28 Oct 2026, 00:00", substring = true).assertExists()
        compose.onNodeWithText("28 Oct 2026, 15:00", substring = true).assertExists()
    }

    @Test
    fun `the picker's hour can be reached, not merely present`() {
        draw()
        compose.onNodeWithText("00:00", substring = true).performClick()
        compose.waitForIdle()

        // **The hour stepper is the control this session added**, and on a 320x470 screen it sat
        // below the bottom of the dialog: present in the tree, unreachable by a thumb. Both its
        // half and the "Show" button that commits it must be inside the window.
        val at = bottomOf("At")
        assertTrue(
            "the hour row is on the screen: its bottom is $at of ${windowHeight()}",
            at < windowHeight(),
        )
        assertTrue(
            "and so is Show, or the pick cannot be committed: " +
                "${bottomOf("Show")} of ${windowHeight()}",
            bottomOf("Show") < windowHeight(),
        )
    }

    @Test
    fun `the dialog offers no minutes, because the table counts hours`() {
        draw()
        compose.onNodeWithText("00:00", substring = true).performClick()
        compose.waitForIdle()
        // **A minute stepper is not a smaller control, it is a different promise.** Offering one
        // lets somebody pick 19:23 for a table that can only count whole hours.
        compose.onNodeWithText("minute").assertDoesNotExist()
        compose.onNodeWithText(":00", substring = true).assertExists()
    }

    @Test
    fun `the table says how many hours it covers`() {
        draw()
        // Fifteen hours, from midnight to the 15:00 boundary - the interval's own extent, so a
        // figure has a scale rather than being a number with no span.
        compose.onNodeWithText("15 hours", substring = true).assertExists()
    }
}
