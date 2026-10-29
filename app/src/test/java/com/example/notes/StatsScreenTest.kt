package com.example.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
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
    /** One response, as the log records it. Only the fields the totals read. */
    private fun stats(cost: Double): RequestLog.Stats = RequestLog.Stats(
        kind = RequestLog.Stats.Kind.TURN,
        ms = 1_000,
        provider = "test",
        model = "test",
        usage = org.json.JSONObject().apply {
            put("prompt_tokens", 1_000)
            put("completion_tokens", 100)
            put("cost", cost)
        },
        finish = "stop",
    )

    private val now: Long = java.time.LocalDate.parse("2026-10-28")
        .atTime(14, 30)
        .atZone(java.time.ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()

    /**
     * Draws the screen.
     *
     * **`withRows` seeds real responses into the account's database**, because the table only exists
     * when there is something to put in it: an empty history draws a sentence instead, and a test
     * asserting on a heading would be asserting about a screen that was never composed.
     *
     * **`rowsBefore` controls whether the previous period has anything in it**, which is the one
     * thing that decides whether the previous column is drawn at all.
     */
    private fun draw(withRows: Boolean = false, rowsBefore: Boolean = true) {
        if (withRows) {
            val hour = 3_600_000L
            if (rowsBefore) {
                // **Yesterday, not a few hours ago.** The default interval is today from midnight,
                // so its previous period is the whole of yesterday - a row two hours before *now*
                // would sit inside the current interval and prove nothing about the baseline.
                val midnight = java.time.LocalDate.parse("2026-10-28")
                    .atStartOfDay(java.time.ZoneId.systemDefault())
                    .toInstant().toEpochMilli()
                RequestDb.write(account, midnight - 2 * hour, stats(cost = 0.002))
                RequestDb.write(account, midnight - 3 * hour, stats(cost = 0.001))
            }
            // Two inside the current interval - today from midnight, so after 00:00.
            RequestDb.write(account, now - 1_000L, stats(cost = 0.003))
            RequestDb.write(account, now - 2_000L, stats(cost = 0.004))
        }
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
                        // **The real history off the account's directory**, so a test that seeds
                        // rows sees them and a test that does not gets the empty screen. Handing
                        // back a fixed empty `History` would make the table unreachable in every
                        // test, which is how the heading test below was passing vacuously before.
                        readRows = { done -> done(RequestDb.read(account)) },
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
        // **The two ends read DIFFERENT times**, and that is the default working: the interval is
        // today from midnight, so From is 00:00 and To is where the clock stopped.
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
        //
        // **Named by row, because every chip exists in both.** "15 min" is a control in the clock row
        // *and* in the rolling row, and the two mean different things - one starts at the hour's
        // beginning, the other fifteen minutes ago. Before the chips carried their row in the
        // description, a plain `onNodeWithText("15 min")` found two nodes and failed, which is the
        // same ambiguity a screen reader would hit.
        compose.onNodeWithContentDescription("Clock 15 min").assertExists()
        compose.onNodeWithContentDescription("Clock total").assertExists()
        compose.onNodeWithContentDescription("Clock 1 day").assertExists()
        compose.onNodeWithContentDescription("Rolling 1 day").assertExists()
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
        compose.onNodeWithContentDescription("Clock 1 week").performScrollTo().performClick()
        compose.waitForIdle()
        // **One week on the clock starts on the Monday of the current week**, which for 28 Oct 2026
        // - a Wednesday - is the 26th. It used to start at today's midnight, the same as "1 day".
        compose.onNodeWithText("26 Oct 2026").assertExists()
        // And it regenerated, so the button has nothing left to do.
        compose.onNodeWithText("Generated").assertExists()
    }

    @Test
    fun `the date and the time are separate buttons`() {
        draw()
        // **Four buttons, two per end**, because a calendar answers one question and a dial answers
        // the other. A single button per end is what forced the time into the calendar's title row.
        // **Two of each, because the default interval has today at both ends.** The From and To
        // buttons read the same date and the same time until a picker moves one of them - which is
        // the point of the default being a whole day so far, not a defect.
        assertEquals("a date button per end", 2, compose.onAllNodesWithText("28 Oct 2026").fetchSemanticsNodes().size)
        // **The times differ**, because the default interval starts at midnight: From is 00:00 and
        // To is the opening moment. Two identical times would mean the default had not been applied.
        compose.onNodeWithText("00:00").assertExists()
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
    fun `an empty history draws a sentence instead of a table of zeroes`() {
        draw()
        // **No heading at all**, because there is no table. A person opening this before the agent
        // has run is told why it is empty rather than shown a grid of dashes that reads like a fault
        // - and the headings below would be headings over nothing.
        // **Substring, because the caption is one string**: "14 hours 30 minutes · no log yet".
        compose.onNodeWithText("no log yet", substring = true).assertExists()
        compose.onNodeWithText("now").assertDoesNotExist()
    }

    @Test
    fun `the table names its two value columns`() {
        // **A heading is needed now and was not before.** With one column the caption above the
        // table was its heading; two values per row make "which is which" a real question.
        draw(withRows = true)
        compose.onNodeWithText("now").assertExists()
        // **The previous column appears once there is a baseline to compare against.** One response
        // an hour before the interval began is enough to make the period before it real.
        compose.onNodeWithText("previous").assertExists()
    }

    @Test
    fun `the previous column is dropped when the log does not reach it`() {
        // **A column of dashes beside a real one reads as "traffic collapsed"**, when the truth is
        // that the history simply begins later. So the column goes entirely rather than filling
        // with dashes.
        //
        // One row *inside* the current interval, and nothing before it, is exactly that shape.
        draw(withRows = true, rowsBefore = false)
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
        // **Named as a selectable, because a bare "26" is not unique in the calendar** - the
        // year-navigation row can carry the same digits, and the day cell is the selectable one.
        compose.onNode(hasText("26") and hasClickAction()).performClick()
        compose.onNodeWithText("Show").performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Generate").assertExists()
    }
}
