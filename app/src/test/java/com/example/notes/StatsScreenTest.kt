package com.example.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
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
    private fun draw(
        withRows: Boolean = false,
        rowsBefore: Boolean = true,
        /**
         * Where the log begins, when a test needs it to begin *after* the previous period.
         *
         * **The dash case is unreachable without this.** Every other fixture writes a row at `now`,
         * so `oldest` is always recent and the previous window is always inside the log — which is
         * why the test below could not ask for a dash by leaving the previous period empty.
         */
        oldestAt: Long? = null,
    ) {
        if (oldestAt != null) {
            RequestDb.write(account, oldestAt, stats(cost = 0.001))
        }
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
        // **The caption that used to be here is gone** — "and on before, now and interval lines, we
        // dont need them" — so what is asserted is the pickers, which are the thing the person
        // reads and changes. The duration was a restatement of the interval they had just set.
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
    fun `choosing a preset moves the interval and waits for Generate`() {
        draw()
        // **A preset sets the interval; it does not read the log.** The operator's report:
        // "actually I want for presets not to be generated automatically - I want every interval to
        // be generated via 'generate' button".
        //
        // This reverses what the screen used to do, and the test that asserted the old rule is the
        // one this replaces. The table is the expensive half - it reads the whole log - and a person
        // comparing three intervals paid for three reads to look at two of them. It also let the
        // interval on screen and the interval in the table disagree for as long as the read took,
        // with nothing saying which was stale.
        //
        // **Scrolled into view first.** The row scrolls sideways and "1 week" sits past the right
        // edge on this width, so a tap without scrolling fails with "Failed to inject touch input" -
        // the node exists in the tree but has no position on screen.
        compose.onNodeWithContentDescription("Clock 1 week").performScrollTo().performClick()
        compose.waitForIdle()
        // **One week on the clock starts on the Monday of the current week**, which for 28 Oct 2026
        // - a Wednesday - is the 26th. It used to start at today's midnight, the same as "1 day".
        // The picker moved, which is the half that happens on the tap.
        compose.onNodeWithText("26 Oct 2026").assertExists()
        // **And the button says there is work to do.** "Generate" rather than "Generated" is the
        // whole point: the picks have moved and the table has not followed them.
        compose.onNodeWithText("Generate").assertExists()
    }

    @Test
    fun `nothing describes the interval under the button`() {
        // **The operator's report**: "and on before, now and interval lines, we dont need them".
        // They described the interval the table is about, and the table *is* that interval - a
        // person who set it up knows which one they generated, and a line repeating it is a line to
        // read past on every visit.
        //
        // **Asserted as an absence, because an absence is the change.** A test that only checked
        // the table still drew would pass with all three lines back on screen.
        draw(withRows = true)
        compose.onNodeWithText("now:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("before:", substring = true).assertDoesNotExist()
        // The duration as a caption is gone too. It still appears inside a button (the picker's own
        // label), so this asserts the *caption* by asking for the exact sentence the caption used.
        compose.onAllNodesWithText("14 hours 30 minutes").assertCountEquals(0)
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
        // **The sentence, and not the table.** The caption lines are gone, but this one fact
        // survives because it is a different kind of fact: it says the table is empty because the
        // log begins after the interval, which is the one thing a person cannot work out from the
        // numbers.
        compose.onNodeWithText("no log yet", substring = true).assertExists()
        compose.onNodeWithText("requests").assertDoesNotExist()
    }

    @Test
    fun `the table has one value column and a change column, and no headings`() {
        // **The operator's report**: "we dont need before column either - its data is only used for
        // comparison and column with relative change is good enough on its own", and then "and this
        // way we dont need title headers for the table either".
        //
        // Both are right and they are one decision: a number printed beside another number makes the
        // reader do the subtraction the change column has already done, and a heading over the only
        // value column names what the reader can see.
        //
        // **Asserted as absences, because the change is what is missing.** A test that only checked
        // the rows still drew would pass with the old three-column table restored.
        draw(withRows = true)
        compose.onNodeWithText("now").assertDoesNotExist()
        compose.onNodeWithText("before").assertDoesNotExist()
        // And the rows that matter are still there, with their change.
        compose.onNodeWithText("requests").assertExists()
        compose.onNodeWithText("cost $").assertExists()
    }

    @Test
    fun `a change is a dash when the log does not reach the period before`() {
        // **The other half of the distinction above, and the only case where a dash is honest.**
        // The log begins *inside* the current interval, so the previous period is partly or wholly
        // before anything was recorded — and a change computed from that would be a comparison
        // against a window the app never saw.
        //
        // A dash says "no number here", which is true. `0%` would say "nothing changed", which is a
        // claim about a period nobody measured.
        draw(withRows = true, rowsBefore = false, oldestAt = now - 60_000L)
        compose.onNodeWithText("requests").assertExists()
        assertTrue(
            "the change cells are dashes",
            compose.onAllNodesWithText(StatsFormat.NONE).fetchSemanticsNodes().isNotEmpty(),
        )
        compose.onAllNodesWithText("%", substring = true).assertCountEquals(0)
    }

    @Test
    fun `a row with nothing behind it shows a change against zero, not a blank`() {
        // **This test had the rule backwards, and the run proved it.** I wrote it expecting a dash
        // when the previous period holds no rows, reasoning that a rise from zero "reads as a jump
        // that never happened".
        //
        // The app disagrees, and the app is right: `showPrevious` asks whether the **log reaches**
        // the previous period, not whether that period has traffic in it. If the log covers the
        // window and nothing was requested, then nothing *was* requested — that is a measurement, and
        // the change against it is real. A dash there would be the app claiming it does not know
        // something it does.
        //
        // The dash belongs to the case where the log does not reach back at all, which is a different
        // test. This one pins the distinction so neither can drift into the other.
        draw(withRows = true, rowsBefore = false)
        compose.onNodeWithText("requests").assertExists()
        assertTrue(
            "a change is computed, because the window is inside the log",
            compose.onAllNodesWithText("%", substring = true).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun `the button says it has nothing to do until a pick moves`() {
        draw()
        // **The button is there and says it has nothing to do yet**, because the picks have not
        // moved since the screen opened. A permanently-enabled Generate is a control whose effect a
        // person has to guess at.
        //
        // **What makes it change is not tested here.** Moving a picker means driving Material's
        // calendar or dial, and this harness does not lay either out at a size that composes their
        // controls - so a test that tapped one would be testing Robolectric's layout, not this
        // decision. The decision itself is `RequestStats.pending`, asserted directly in
        // `RequestStatsTest`; the picker's appearance is a scenario screenshot, where a person can
        // see it.
        compose.onNodeWithText("Generated").assertExists()
        compose.onNodeWithText("Generate").assertDoesNotExist()
    }
}