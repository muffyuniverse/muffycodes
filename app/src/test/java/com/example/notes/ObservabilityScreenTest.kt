package com.example.notes

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The trace screen, painted from a report it is handed.
 *
 * No recorder and no file behind it: the screen's job is to make a report readable, and a
 * test that had to produce the report by tapping would be testing the recorder twice and
 * this screen barely at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ObservabilityScreenTest {

    @get:Rule
    val compose = createComposeRule()

    /** The theme the screens are painted in, as the other screen tests do it. */
    @Composable
    private fun Frame(content: @Composable () -> Unit) {
        MaterialTheme { Surface { content() } }
    }

    private fun healthy() = Trace.Report(
        lines = listOf(
            "12:00:01.020  41 CHANGED state  at / · 1 rows · milk.txt",
            "12:00:00.980  41 TAP     Save     milk.txt",
        ),
        waiting = emptyList(),
        failures = emptyList(),
        answered = 1,
        taps = 1,
    )

    private fun stuck() = Trace.Report(
        lines = listOf("12:00:00.980  42 TAP     Save     milk.txt"),
        waiting = listOf(
            Trace.Broken(42, System.currentTimeMillis(), "Save", "milk.txt", 2_410),
        ),
        failures = listOf("FAIL sync: GitHub refused the push: rejected"),
        answered = 0,
        taps = 1,
    )

    private fun show(report: Trace.Report, onBack: () -> Unit = {}, onClear: () -> Unit = {}) {
        compose.setContent {
            Frame {
                Box {
                    ObservabilityScreen(
                        report = report,
                        onBack = onBack,
                        onShare = {},
                        onClear = onClear,
                    )
                }
            }
        }
    }

    @Test
    fun `a report with nothing stuck says so plainly`() {
        show(healthy())

        compose.onNodeWithText("1 of 1 answered, nothing stuck.").assertIsDisplayed()
        compose.onNodeWithText("CHANGED state", substring = true).assertIsDisplayed()
        compose.onNodeWithText("TAP", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a tap with no answer is the first thing on the screen, in words`() {
        show(stuck())

        // The finding, as a sentence: which element, and that nothing came back.
        compose.onNodeWithText(
            "Save (milk.txt) — no change and no message after 2410 ms",
            substring = true,
        ).assertIsDisplayed()
        compose.onNodeWithText("No answer yet").assertIsDisplayed()
        compose.onNodeWithText("GitHub refused the push", substring = true).assertIsDisplayed()
    }

    @Test
    fun `clearing is a thing a person asks for, not something that happens`() {
        var cleared = 0
        show(stuck(), onClear = { cleared++ })

        compose.onNodeWithText("Clear").performClick()
        assertEquals("cleared once, when asked", 1, cleared)
    }

    @Test
    fun `an empty trace does not pretend to be healthy`() {
        show(Trace.Report(emptyList(), emptyList(), emptyList(), 0, 0))

        compose.onAllNodesWithText("Nothing has happened yet.").assertCountEquals(1)
        compose.onNodeWithText("Tap anything and it appears here.", substring = true)
            .assertIsDisplayed()
        // No Share or Clear: there is nothing to hand over and nothing to clear, and a
        // control that does nothing is the kind of thing this app keeps having to fix.
        compose.onNodeWithText("Share").assertDoesNotExist()
        compose.onNodeWithText("Clear").assertDoesNotExist()
    }

    @Test
    fun `there is no Save, because the log is already written`() {
        // The whole point of the single-log design: a Save button would offer to write a
        // second, worse copy of something that is already on disk.
        show(healthy())

        compose.onNodeWithText("Save").assertDoesNotExist()
        compose.onNodeWithText("Share").assertIsDisplayed()
    }
}
