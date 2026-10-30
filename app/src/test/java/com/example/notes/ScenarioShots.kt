package com.example.notes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import android.os.Looper
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.isRoot
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.robolectric.Shadows.shadowOf

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
/** The identity a scenario passes when it stays on one screen, so `after` can tell. */
private val NO_SWITCH: () -> Unit = {}

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
    private val switchToAfter: () -> Unit = NO_SWITCH,
    /**
     * True once the screen in the second half has what it needs to draw itself.
     *
     * **Only the screens that read the repository need this.** `waitForIdle` spins the
     * composition's own clock and returns before a worker's answer is posted back, so a second
     * screen that loads was photographed mid-load - scenario 09's "after" was a picture of
     * "Reading the history…", which is a true state of the app and evidence of nothing.
     *
     * The default is "already ready", so the twenty-three pictures that were right stay exactly
     * as they were. This is a predicate rather than a pause, and a predicate over the app's own
     * state rather than over the composition, because asking compose to go idle again is what an
     * animating dialog can never do - five window scenarios failed that way when this was tried in
     * the shared path.
     */
    private val ready: () -> Boolean = { true },
    /**
     * Whether the window holds a text field, whose cursor never lets the clock settle.
     *
     * **A field is the one thing that makes `waitForIdle` hang for ever.** Its caret blinks on the
     * composition's own clock, so every reading of the semantics tree - which is what taking a
     * picture does - waits for an animation that has no end. Freezing the clock is the documented
     * answer, and it is opt-in rather than shared because the animations that *should* finish (a
     * dialog opening, a list scrolling) are what make the other pictures correct.
     */
    private val frozenClock: Boolean = false,
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
    fun before() {
        if (frozenClock) {
            compose.mainClock.autoAdvance = false
            compose.mainClock.advanceTimeBy(500)
        }
        capture("before.png")
    }

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

    /**
     * The screen after it was done, which may be a different screen.
     *
     * **It waits for work that was posted from another thread, and `waitForIdle` does not.**
     * Every screen that reads the repository asks the worker to do it and posts the answer back
     * to the main looper. A compose idle wait spins the composition's own clock and never gives
     * that post its turn, so the picture was taken of the spinner: scenario 09's "after" was a
     * screen reading "Reading the history…", which is a true state of the app and evidence of
     * nothing about the feature it is a picture of.
     *
     * So the looper is pumped until the app stops changing, and a short settle is allowed after
     * that for a post that arrives while the last frame is being drawn. The timeout is a ceiling,
     * not a wait: a screen with nothing to load leaves this loop on its first pass.
     */
    /**
     * The screen after it was done, which may be a different screen.
     *
     * **The extra wait is only for the scenarios that ask for it.** `waitForIdle` spins the
     * composition's own clock and returns before a worker's answer is posted back, so a second
     * screen that reads the repository was photographed mid-load — scenario 09's "after" was a
     * picture of "Reading the history…".
     *
     * The repair has to be narrow, and two earlier attempts were not: draining the looper for
     * *every* scenario broke the five window ones with `AppNotIdleException`, because a dialog
     * animates for as long as it is shown and never goes idle. A scenario whose second screen is
     * already drawn therefore pays nothing here, which is twenty-three of the twenty-four.
     */
    fun after() {
        // **`waitForIdle` only when a screen is actually swapped, and this was a real hang.** A
        // window holding a text field keeps a blinking cursor on a Compose clock that never
        // settles, so an unconditional idle wait timed out after sixty seconds and reported
        // "infinite composition loop" - a false alarm about the wrong thing. A scenario that stays
        // on the screen it opened has nothing to settle, so it is not asked to.
        val swaps = switchToAfter !== NO_SWITCH
        if (swaps) {
            switchToAfter()
            compose.waitForIdle()
        }
        if (!ready()) {
            // **The looper only.** Draining it is what delivers a worker's answer; asking compose
            // to go idle again is the thing an animating window can never do. Guarded by `ready`,
            // so nothing that could be a dialog ever reaches it.
            val looper = shadowOf(Looper.getMainLooper())
            val deadline = System.currentTimeMillis() + 10_000
            while (!ready() && System.currentTimeMillis() < deadline) {
                looper.idle()
                Thread.sleep(8)
            }
            compose.waitForIdle()
        }
        capture("after.png")
        // **Not unfrozen here either.** Restoring it would put the never-settling cursor back before
        // the rule's own teardown, which waits for idle - the same hang one layer down. Each scenario
        // is its own test instance with its own rule, so nothing later inherits it.
    }

    /** What came of it, in one sentence. */
    fun result(result: String) {
        this.result = result.trim()
    }

    /**
     * The picture, taken of **whatever is on top.**
     *
     * `onRoot()` answers with the *first* root, and a dialog is a second window with its own root
     * drawn over the first. A scenario that opens a window and captures would then have photographed
     * the screen behind it - the picture would look right in the sense that it is the app, and wrong
     * in the only sense that matters: the thing the scenario is about would not be in it.
     *
     * So the roots are asked for and the last one is taken. Composition order puts the newest window
     * last, which is the one a person is looking at.
     */
    private fun capture(fileName: String) {
        if (!recording) return
        val roots = compose.onAllNodes(isRoot()).fetchSemanticsNodes()
        val top = roots.size - 1
        compose.onAllNodes(isRoot())[top].captureRoboImage("build/outputs/scenarios/$folder/$fileName")
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
    /**
     * True once the second screen has what it needs to draw itself.
     *
     * Only for a second screen that reads the repository: its answer arrives from the worker, and a
     * composition idle wait returns before that post lands. See [ScenarioShots.ready].
     */
    ready: () -> Boolean = { true },
    /** See [ScenarioShots.frozenClock]: a window with a text field needs it. */
    frozenClock: Boolean = false,
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
        ready = ready,
        frozenClock = frozenClock,
    )
    shots.body()
    return shots.write()
}
