package com.example.notes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File

/**
 * One scenario, written for a person.
 *
 * A scenario is a thing somebody does with the app, told as four parts: what was
 * on the screen, what they did, what the screen showed afterwards, and what the
 * result was. It is written into its own folder as two pictures and a page of
 * prose, so the report can be read without knowing anything about how it was
 * made.
 *
 * The prose rules are deliberate and narrow:
 *
 * - **No tests, no classes, no timings, no stack traces.** A report that says
 *   "ScreenshotTourTest took 1524 ms" tells a person nothing they can act on.
 * - **What a person would see**, in the words a person would use.
 * - **The result in one sentence**, because that is the claim being made.
 *
 * The technical half of a failure lives in the test's own output, where an agent
 * reads it. This is the other half.
 */
class ScenarioShots(
    private val compose: ComposeContentTestRule,
    /** The folder the scenario is written into, under the build's output. */
    private val folder: String,
    /** The name at the top of the page. */
    private val title: String,
    /** One line under the title, which is what the report's index shows. */
    private val about: String,
    /**
     * Shows the screen that belongs to the second half, when a scenario has one.
     *
     * Most scenarios stay on one screen: the thing that changes is a row, a
     * count, a list. Some do not — deleting a folder and then looking at its
     * history is two screens, and pretending otherwise made the second picture a
     * copy of the first with different words under it. A report that shows the
     * same screen twice and calls it a before and an after is worse than one that
     * shows a single picture and says why.
     */
    private val switchToAfter: () -> Unit = {},
) {

    private var happened: String? = null
    private var result: String? = null

    /**
     * True when the pictures are wanted.
     *
     * The scenario runs either way — it is a test, and a broken flow must fail a
     * pull request like any other. What is conditional is the *artifact*: a
     * request is the workhorse tier and does not need a report, while a build
     * carries one.
     */
    private val recording: Boolean
        get() = System.getProperty("roborazzi.test.record") == "true"

    /** The screen before anything is done. */
    fun before() = capture("before.png")

    /**
     * Does something, and records it in the words a person would use.
     *
     * The prose is the point: a picture of a list of files says very little on
     * its own, while "the + button is pressed and the name Shopping is typed"
     * says what the pictures cannot.
     */
    fun then(happened: String, action: () -> Unit) {
        this.happened = happened.trim()
        action()
    }

    /** The screen after it was done, which may be a different screen. */
    fun after() {
        switchToAfter()
        compose.waitForIdle()
        capture("after.png")
    }

    /** What came of it, in one sentence. */
    fun result(result: String) {
        this.result = result.trim()
    }

    private fun capture(fileName: String) {
        if (!recording) return
        compose.onRoot().captureRoboImage("build/outputs/scenarios/$folder/$fileName")
    }

    /** Writes the page. Called once, when the scenario has said everything. */
    fun write(): String {
        if (!recording) return folder
        val dir = File("build/outputs/scenarios/$folder")
        dir.mkdirs()
        val page = buildString {
            appendLine("# ${title.trimEnd('.')}")
            appendLine()
            // The line under the title is what the report's index shows. Keeping
            // it in the page rather than in a separate file means the index can be
            // built from the scenarios that are actually there, in order, without
            // anything having to remember what it wrote.
            appendLine("> $about")
            appendLine()
            appendLine("## Before")
            appendLine()
            appendLine("![The screen before](before.png)")
            appendLine()
            appendLine("## What happens")
            appendLine()
            appendLine(happened ?: "Nothing is done.")
            appendLine()
            appendLine("## After")
            appendLine()
            appendLine("![The screen after](after.png)")
            appendLine()
            appendLine("## The result")
            appendLine()
            appendLine(result ?: "Nothing changes.")
            appendLine()
        }
        File(dir, "README.md").writeText(page)
        return folder
    }

}

/**
 * Runs a scenario: renders [screen] once, and lets the body take the two
 * pictures and write the words around them.
 *
 * The screen is set up once and the scenario moves the app underneath it, which
 * is what a person does too: they do not open a new window to see the result of
 * tapping a button.
 */
fun scenario(
    compose: ComposeContentTestRule,
    folder: String,
    title: String,
    about: String,
    screen: @Composable () -> Unit,
    /** The screen the second half is taken on, when it is not the same one. */
    afterScreen: (@Composable () -> Unit)? = null,
    body: ScenarioShots.() -> Unit,
): String {
    // One `setContent` for the whole scenario, because a Compose test rule allows
    // exactly one. The switch is a piece of state read inside the composition, so
    // changing it recomposes into the other screen — the same way the app itself
    // moves from a list to a history view.
    val showingAfter = mutableStateOf(false)
    compose.setContent {
        if (showingAfter.value && afterScreen != null) afterScreen() else screen()
    }
    val shots = ScenarioShots(
        compose, folder, title, about,
        switchToAfter = { showingAfter.value = true },
    )
    shots.body()
    return shots.write()
}
