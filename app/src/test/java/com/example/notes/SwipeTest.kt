package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * **The swipe's two rules, and the wall that is gone.**
 *
 * Falsification for the whole file: put the clamp back in [Swipe.offsetFor] — the `coerceIn` that
 * capped the offset at a third of the width — and the first test fails, because the page stops at the
 * bar instead of following the finger past it.
 */
class SwipeTest {

    private val width = 1000f

    // ---- while the finger is down -------------------------------------------

    @Test
    fun `the page follows the finger past the distance that would commit it`() {
        // **The reported bug, as arithmetic.** Dragging 600px on a 1000px screen is well past the
        // 333px bar. The old code answered 333 — the page stopped dead there — so the remaining
        // 267px of gesture did nothing, which is the "threshold you need to overpower" feel.
        val offset = Swipe.offsetFor(0f, -600f, hasPrevious = true, hasNext = true)

        assertEquals("**the page is where the finger is**", -600f, offset, 0.01f)
    }

    @Test
    fun `a drag is cumulative and never snaps back on its own`() {
        // Ten frames of a slow drag, each a small delta. Nothing may clamp, so the total is the sum.
        var offset = 0f
        repeat(10) { offset = Swipe.offsetFor(offset, -40f, hasPrevious = true, hasNext = true) }

        assertEquals("every pixel counted", -400f, offset, 0.01f)
    }

    @Test
    fun `a page with nothing beside it resists rather than stops`() {
        // **A wall you can feel, not one you hit.** The content still moves — a quarter of the
        // gesture — so the end is perceptible without the page ever leaving the screen.
        val offset = Swipe.offsetFor(0f, -400f, hasPrevious = false, hasNext = false)

        assertEquals("a quarter of the movement", -100f, offset, 0.01f)
    }

    @Test
    fun `resistance at an end does not apply in the direction that has a page`() {
        // The two directions are independent: the last page still swipes back to its neighbour.
        assertEquals(
            "pulling toward the page that exists moves the whole way",
            400f,
            Swipe.offsetFor(0f, 400f, hasPrevious = true, hasNext = false),
            0.01f,
        )
        assertEquals(
            // **The sign stays with the direction of the pull.** Resisted means a quarter of the
            // movement, not a movement the other way.
            "and pulling past the end is resisted",
            -100f,
            Swipe.offsetFor(0f, -400f, hasPrevious = true, hasNext = false),
            0.01f,
        )
    }

    // ---- on release ---------------------------------------------------------

    @Test
    fun `release past a third of the width turns the page`() {
        assertEquals(
            "pulled left far enough is the next page",
            1,
            Swipe.steps(-400f, width, velocity = 0f),
        )
        assertEquals(
            "pulled right far enough is the previous one",
            -1,
            Swipe.steps(400f, width, velocity = 0f),
        )
    }

    @Test
    fun `release short of a third stays put`() {
        assertEquals("a nudge is not a turn", 0, Swipe.steps(-200f, width, velocity = 0f))
        assertEquals("nor is a nudge the other way", 0, Swipe.steps(200f, width, velocity = 0f))
    }

    @Test
    fun `a flick turns the page without travelling a third of the width`() {
        // **A person reading on a phone flicks; they do not drag a third of the screen.** Distance
        // alone made a flick look like the gesture had been ignored — the second half of the same
        // complaint.
        assertEquals("a quick flick left", 1, Swipe.steps(-80f, width, velocity = -1_400f))
        assertEquals("a quick flick right", -1, Swipe.steps(80f, width, velocity = 1_400f))
    }

    @Test
    fun `a slow short drag is still not a turn`() {
        // **The other side of the flick rule.** Without this, any velocity above the threshold would
        // turn the page and a hand settling on the glass would flip it.
        assertEquals("slow and short stays", 0, Swipe.steps(-80f, width, velocity = -300f))
    }

    @Test
    fun `a flick at the end bounces instead of committing to a page that is not there`() {
        // **This shipped as a blank screen.** `steps` answered from velocity alone, so a flick on the
        // last page animated it the whole way off the edge; `onStep` then found no file beside it and
        // did nothing, leaving an empty page with nothing on it and no way back except the gesture in
        // reverse. The bounce is the answer at an end, so the answer is 0.
        assertEquals(
            "**a flick past the last page stays**",
            0,
            Swipe.steps(-200f, width, velocity = -1_800f, hasPrevious = true, hasNext = false),
        )
        assertEquals(
            "and a flick before the first stays",
            0,
            Swipe.steps(200f, width, velocity = 1_800f, hasPrevious = false, hasNext = true),
        )
    }

    @Test
    fun `dragging past a third at an end still does not commit`() {
        // The same rule by distance rather than speed: a long drag at the end is a person finding the
        // wall, not a request for a page.
        assertEquals(
            "dragged far, nowhere to go",
            0,
            Swipe.steps(-900f, width, velocity = 0f, hasPrevious = true, hasNext = false),
        )
    }

    @Test
    fun `a flick backwards while already pulled forwards turns back, not on`() {
        // **The offset and the velocity can disagree**, and the gesture is the newer information: a
        // person who pulled halfway and then flicked the other way changed their mind. Reading the
        // offset alone would commit the page they were abandoning.
        assertEquals(
            "the flick decides",
            -1,
            Swipe.steps(200f, width, velocity = 1_400f),
        )
    }
}
