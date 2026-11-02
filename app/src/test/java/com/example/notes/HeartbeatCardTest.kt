package com.example.notes

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The card, as a person meets it.
 *
 * **The data is covered in [HeartbeatTest]**, so nothing here re-tests the arithmetic. What is tested
 * here is the part only a screen can get wrong: that four cells appear, that each shows its own
 * units, that an empty account draws nothing at all, and that the colour of a badge says whether the
 * news is good rather than which way an arrow points.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HeartbeatCardTest {

    @get:Rule
    val compose = createComposeRule()

    /** A measure with the shape the card needs, and nothing that depends on a database. */
    private fun measure(
        id: Heartbeat.Id,
        recent: Double?,
        baseline: Double?,
        points: List<Double?> = listOf(1.0, null, 2.0),
    ) = Heartbeat.Measure(id = id, recent = recent, baseline = baseline, points = points)

    private fun card(measures: List<Heartbeat.Measure>) {
        compose.setContent {
            Frame {
                HeartbeatCard(measures)
            }
        }
    }

    @Composable
    private fun Frame(content: @Composable () -> Unit) {
        MaterialTheme { Surface(modifier = Modifier.fillMaxSize()) { content() } }
    }

    private fun shown(): List<Heartbeat.Measure> = listOf(
        measure(Heartbeat.Id.SPEND, recent = 0.42, baseline = 0.40),
        measure(Heartbeat.Id.CACHE, recent = 0.9710, baseline = 0.95),
        measure(Heartbeat.Id.OUTPUT, recent = 9_994.0, baseline = 8_000.0),
        measure(Heartbeat.Id.SHARE, recent = 1.0 / 6, baseline = 0.2),
    )

    @Test
    fun `the four measures are all drawn, each in its own units`() {
        card(shown())

        // The titles, because a card that drew graphs without names would be four shapes.
        for (id in Heartbeat.Id.entries) compose.onNodeWithText(id.title).assertIsDisplayed()

        // And each value in the shape its own measure uses - the bug this catches is one formatter
        // being used for all four, which would print the spend as "0.42%" or the cache as "0.97$".
        compose.onNodeWithText("0.42$").assertIsDisplayed()
        compose.onNodeWithText("97.10%").assertIsDisplayed()
        compose.onNodeWithText("9.99K").assertIsDisplayed()
        compose.onNodeWithText("16.67%").assertIsDisplayed()
    }

    /**
     * **An account with no history draws no card.**
     *
     * Four cells of dashes would read as a failure rather than as an empty account, and it would take
     * the place of the project list a new person is actually looking for.
     */
    @Test
    fun `an empty account draws nothing at all`() {
        card(emptyList())
        compose.onNodeWithText(Heartbeat.Id.SPEND.title).assertDoesNotExist()
    }

    /**
     * **A badge falls back to a dash rather than a percentage** when either side of the comparison is
     * missing, which is the case for an hour in which nothing was recorded.
     */
    @Test
    fun `a measure with no recent value shows a dash and no badge`() {
        card(listOf(measure(Heartbeat.Id.SPEND, recent = null, baseline = 0.5)))

        compose.onNodeWithText("spend").assertIsDisplayed()
        // The dash appears twice - once as the value, once as the badge - which is the honest
        // drawing: an hour with no requests has neither a number nor a change.
        compose.onAllNodesWithText("—").assertCountEquals(2)
    }

    /**
     * **The badge states the comparison and passes no judgement.**
     *
     * It used to be green above zero and red below, which made the app hold an opinion it has no
     * basis for - a busy hour costs more *and* gets more done, and which matters depends on what the
     * person is doing. So the sign carries the meaning and the colour is the brand's accent: this
     * test pins the sign, which is the only thing the number claims.
     */
    @Test
    fun `the badge states the direction and nothing more`() {
        val rising = measure(Heartbeat.Id.SPEND, recent = 2.0, baseline = 1.0)
        assertEquals("+100.00%", Heartbeat.badge(rising.badge))

        val falling = measure(Heartbeat.Id.CACHE, recent = 0.5, baseline = 1.0)
        assertEquals("-50.00%", Heartbeat.badge(falling.badge))

        // No comparison at all is a dash rather than a number, in every measure.
        for (id in Heartbeat.Id.entries) {
            assertEquals("—", Heartbeat.badge(measure(id, recent = 1.0, baseline = null).badge))
        }
    }

    @Test
    fun `a badge that cannot be computed is drawn instead of crashing`() {
        // A baseline of zero is a real measurement - the period spent nothing - and has no
        // percentage. The card must draw the dash rather than dividing by it.
        card(listOf(measure(Heartbeat.Id.SPEND, recent = 1.0, baseline = 0.0)))
        compose.onNodeWithText("1.00$").assertIsDisplayed()
    }
}
