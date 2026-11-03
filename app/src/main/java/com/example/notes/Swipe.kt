package com.example.notes

/**
 * **The two decisions a swipe makes, as arithmetic.**
 *
 * They were inside the composable's drag lambda, which is where the wall came from: the *offset* was
 * clamped to a third of the width, so the page stopped dead at exactly the distance that would have
 * committed it, and the rest of the gesture did nothing. A finger pushing and a page not moving reads
 * as *"a threshold you need to overpower"* — which is exactly what the operator said.
 *
 * The rule is now split the way a person experiences it:
 *
 * - **While dragging, the page follows the finger.** No clamp, no bar, no threshold. Half a screen of
 *   pull is half a screen of travel.
 * - **On release, distance or speed decides.** That is the only place a bar belongs, because it is
 *   the only place a decision is made.
 *
 * **An end resists rather than stops.** With no page in that direction there is nowhere to go, so the
 * movement is divided instead of refused: the wall is felt rather than explained.
 */
object Swipe {

    /**
     * **How far the content sits while a finger is on it.**
     *
     * @param current the offset before this delta
     * @param delta the movement this frame
     * @param hasPrevious whether there is a page to the right (the offset grows toward it)
     * @param hasNext whether there is a page to the left
     *
     * **Not clamped.** A clamp here is the bug: it makes the page stop at the commit distance, so
     * the gesture has to be pushed past a wall that should not exist.
     */
    fun offsetFor(
        current: Float,
        delta: Float,
        hasPrevious: Boolean,
        hasNext: Boolean,
    ): Float {
        val wanted = current + delta
        // **Resisted at the ends, and only there.** A quarter of the movement, so it is plainly
        // possible to pull and plainly impossible to cross.
        return when {
            wanted > 0f && !hasPrevious -> wanted / 4f
            wanted < 0f && !hasNext -> wanted / 4f
            else -> wanted
        }
    }

    /**
     * **Which page release commits to: -1 previous, 0 stay, 1 next.**
     *
     * Positive [offset] is the page pulled to the right, which means the *previous* page — so the
     * sign of the answer is the opposite of the sign of the offset, which is why this reads backwards
     * and is worth saying once.
     *
     * **A flick counts as far enough.** A person reading on a phone does not drag a third of the
     * screen, they flick it; distance alone made a flick look like the gesture had been ignored. A
     * slow careful drag still has to reach the bar, so a nudge while reading cannot turn the page.
     */
    fun steps(
        offset: Float,
        width: Float,
        velocity: Float,
        hasPrevious: Boolean = true,
        hasNext: Boolean = true,
    ): Int {
        val bar = width / 3f
        // 1000 px/s is about a deliberate flick: fast enough that a person meant it, slow enough
        // that a hand settling on the glass does not.
        val flicked = velocity <= -FLICK || velocity >= FLICK
        val forward = offset <= -bar || (flicked && velocity < 0f)
        val back = offset >= bar || (flicked && velocity > 0f)
        return when {
            // **A direction with no page is not a step**, even when the flick says so.
            //
            // This is what a flick at the end used to do: `steps` answered 1 from velocity alone,
            // the settle animated the page the whole way off the screen, and `onStep` found no file
            // beside it and did nothing — leaving an empty screen with no way back except the
            // gesture in reverse. The bounce is the answer at an end, so the answer is 0.
            forward && !hasNext -> 0
            back && !hasPrevious -> 0
            forward -> 1
            back -> -1
            else -> 0
        }
    }

    /** The speed, in pixels per second, at which a gesture is a flick rather than a nudge. */
    const val FLICK = 1_000f
}
