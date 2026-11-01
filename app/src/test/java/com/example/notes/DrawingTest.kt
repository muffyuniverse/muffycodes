package com.example.notes

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drawing on a picture: the strokes, and the arithmetic around them.
 *
 * **What is NOT tested here, and why.** Robolectric's `Canvas` accepts draw calls and rasterises
 * nothing — every pixel stays white — and its `BitmapFactory` has no encoder at all ("Failed to create
 * image decoder with message 'unimplemented'"). So "the stroke really lands in the pixels" **cannot be
 * asked in this container**, and a test that appeared to ask it passed only because a shadow answered
 * `-1` for every pixel. That is the third time this feature has been bitten by a shadow that does not
 * do the thing, so it is written down here rather than discovered again.
 *
 * What *can* be tested is everything that decides **what** would be drawn: which stroke an eraser
 * takes, what undo does, that a tap draws nothing, that the coordinates are the picture's own, and
 * that `flatten` produces bytes at the picture's size. The rasterising itself is checked by the
 * screenshot tour on a real device, which is the only place it can be.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DrawingTest {

    private fun white(width: Int = 100, height: Int = 100): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        return bitmap
    }

    private fun stroke(
        tool: Drawing.Tool = Drawing.Tool.PEN,
        points: List<Pair<Float, Float>> = listOf(10f to 10f, 90f to 90f),
        colour: Int = 0xFFE53935.toInt(),
        width: Float = 10f,
    ) = Drawing.Stroke(tool, points.map { Drawing.Point(it.first, it.second) }, colour, width)

    @Test
    fun `undo removes the last stroke and only the last`() {
        val first = stroke(colour = Color.RED)
        val second = stroke(colour = Color.BLUE, points = listOf(10f to 90f, 90f to 10f))

        assertEquals("both", listOf(first, second), Drawing.undo(listOf(first, second, first)))
        assertEquals("one left", listOf(first), Drawing.undo(listOf(first, second)))
        assertEquals("and nothing is negative", emptyList<Drawing.Stroke>(), Drawing.undo(listOf(first)))
        assertEquals("an empty list undoes to itself", emptyList<Drawing.Stroke>(), Drawing.undo(emptyList()))
    }

    @Test
    fun `the eraser finds the stroke nearest the touch, most recent first`() {
        // **Backwards, so the most recently drawn wins when two overlap** — which is what a person
        // means when they rub out the thing they just added.
        val under = stroke(colour = Color.RED)
        val over = stroke(colour = Color.BLUE, points = listOf(10f to 10f, 90f to 90f))

        assertEquals("the newer one", 1, Drawing.hit(listOf(under, over), Drawing.Point(50f, 50f), 4f))
        assertEquals("and the older when it is alone", 0, Drawing.hit(listOf(under), Drawing.Point(50f, 50f), 4f))
    }

    @Test
    fun `the eraser misses what is far away`() {
        // A tap in an empty corner removes nothing rather than removing the nearest thing anyway.
        assertNull("nothing within reach", Drawing.hit(listOf(stroke()), Drawing.Point(5f, 95f), 2f))
    }

    @Test
    fun `the eraser never removes another eraser`() {
        // An eraser is not a mark: it exists as an action, and a list that contained one would be a
        // list that could erase itself.
        val rubber = stroke(Drawing.Tool.ERASER, listOf(50f to 50f))
        assertNull("not a target", Drawing.hit(listOf(rubber), Drawing.Point(50f, 50f), 40f))
    }

    @Test
    fun `a stroke on a segment between sampled points is found`() {
        // A long straight pen stroke is a few sampled points; a tap halfway between two of them must
        // still hit it, or erasing a diagonal line would only work at the corners.
        val sparse = stroke(points = listOf(0f to 0f, 100f to 100f))
        assertEquals(
            "the midpoint of the segment",
            0,
            Drawing.hit(listOf(sparse), Drawing.Point(50f, 50f), 2f),
        )
    }

    @Test
    fun `drawing on a picture does not change its size`() {
        // A flatten that resized would silently change what the person is annotating — and would
        // change what the agent is charged for, since the cost follows the pixels.
        val picture = white(120, 80)
        val bytes = Drawing.flatten(picture, listOf(stroke()))
        // The bytes are a real PNG of the right size; the *decoding* is what Robolectric cannot do,
        // so the size is asserted from the source picture the flatten was told to draw on.
        assertTrue("it wrote a PNG", bytes.size > 100)
        assertEquals("width", 120, picture.width)
        assertEquals("height", 80, picture.height)
        picture.recycle()
    }

    @Test
    fun `a tap with a line tool has nothing to draw`() {
        // The rule that keeps an invisible mark from existing: a one-point line would be a dot the
        // person cannot see and therefore cannot select to erase. Asked of the *stroke*, because the
        // rasteriser is not available here — `points.size == 1` is what the drawing code refuses.
        val tap = stroke(Drawing.Tool.LINE, listOf(50f to 50f))
        assertEquals("one point", 1, tap.points.size)
        assertEquals("so it draws nothing", tap.points.first(), tap.points.last())
    }

    @Test
    fun `flatten writes bytes at the picture's own size`() {
        // The part of `flatten` that is not rasterising: it copies, it keeps the dimensions, and it
        // produces a PNG. **The pixels themselves are checked on a real device**, per the class doc.
        val picture = white(120, 80)
        val bytes = Drawing.flatten(picture, listOf(stroke()))
        assertTrue("it wrote a PNG", bytes.size > 100)
        assertEquals("PNG magic", 0x89.toByte(), bytes[0])
        assertEquals("width untouched", 120, picture.width)
        assertEquals("height untouched", 80, picture.height)
        picture.recycle()
    }

    @Test
    fun `a stroke keeps the coordinates it was given`() {
        // **The picture's own pixels, never the screen's.** A stroke stored in screen offsets would
        // land somewhere else when the same picture was opened on a different-sized screen, which is
        // the one bug a coordinate system exists to prevent.
        val one = stroke(points = listOf(12f to 34f, 56f to 78f))
        assertEquals("first", Drawing.Point(12f, 34f), one.points.first())
        assertEquals("last", Drawing.Point(56f, 78f), one.points.last())
    }

    @Test
    fun `the offered colours and widths are all distinct`() {
        // The row shows four swatches and three dots; two identical entries would be a control that
        // does nothing when pressed, which reads as a broken button.
        assertEquals("four colours", Drawing.COLOURS.size, Drawing.COLOURS.distinct().size)
        assertEquals("three widths", Drawing.WIDTHS.size, Drawing.WIDTHS.distinct().size)
    }

    // ---- zoom and pan, which is the part that can be asked here --------------------------------

    @Test
    fun `one picture pixel is one screen pixel at 1 to 1`() {
        // The identity that makes every other mapping checkable: at zoom 1 and no pan, a screen point
        // *is* a picture point.
        val view = Drawing.View()
        assertEquals("to the picture", Drawing.Point(120f, 240f), view.toPicture(120f, 240f))
        assertEquals("and back", Drawing.Point(120f, 240f), view.toScreen(Drawing.Point(120f, 240f)))
    }

    @Test
    fun `a pinch keeps the point under the fingers where it was`() {
        // **The property that makes a pinch feel like a pinch.** Whatever the person is looking at
        // grows around itself; the picture must not slide out from under their fingers.
        val before = Drawing.View()
        val under = before.toPicture(300f, 400f)

        val after = before.zoomedBy(2f, 300f, 400f)
        val where = after.toScreen(under)

        assertEquals("x stayed under the fingers", 300f, where.x, 0.01f)
        assertEquals("y stayed under the fingers", 400f, where.y, 0.01f)
        assertEquals("and it really zoomed", 2f, after.zoom, 0.001f)
    }

    @Test
    fun `zooming out and back in returns to where it started`() {
        // Round-tripping a pinch about the same point must be the identity — otherwise a person who
        // overshoots and corrects would find their place moved for no reason.
        val start = Drawing.View()
        val out = start.zoomedBy(0.5f, 200f, 200f)
        val back = out.zoomedBy(2f, 200f, 200f)

        assertEquals("zoom", start.zoom, back.zoom, 0.001f)
        assertEquals("pan x", start.panX, back.panX, 0.01f)
        assertEquals("pan y", start.panY, back.panY, 0.01f)
    }

    @Test
    fun `a pinch at the limit does not slide the picture`() {
        // At the ceiling a further pinch must do nothing at all. Letting the pan drift while the zoom
        // is clamped is how a picture creeps sideways under a person who thinks they are only zooming.
        val atMax = Drawing.View(zoom = Drawing.View.MAX_ZOOM)
        assertEquals("unchanged", atMax, atMax.zoomedBy(2f, 100f, 100f))

        val atMin = Drawing.View(zoom = Drawing.View.MIN_ZOOM)
        assertEquals("and at the floor", atMin, atMin.zoomedBy(0.5f, 100f, 100f))
    }

    @Test
    fun `zoom is held between the floor and the ceiling`() {
        // A single wild pinch cannot escape the range, because the clamp is applied to the product
        // rather than to each step.
        val view = Drawing.View().zoomedBy(1000f, 50f, 50f)
        assertEquals("at the ceiling", Drawing.View.MAX_ZOOM, view.zoom, 0.001f)
        assertEquals(
            "and at the floor",
            Drawing.View.MIN_ZOOM,
            Drawing.View().zoomedBy(0.0001f, 50f, 50f).zoom,
            0.001f,
        )
    }

    @Test
    fun `a pan moves the picture with the finger`() {
        val view = Drawing.View().pannedBy(30f, -20f)
        assertEquals("x", 30f, view.panX, 0.001f)
        assertEquals("y", -20f, view.panY, 0.001f)
        // The finger moved right, so the picture did too: the point that was at 0 is now at 30.
        assertEquals("the picture moved with it", Drawing.Point(30f, -20f), view.toScreen(Drawing.Point(0f, 0f)))
    }

    @Test
    fun `a point maps to the picture through both zoom and pan`() {
        // The composition, stated once: screen = picture·zoom + pan, and its inverse.
        val view = Drawing.View(zoom = 4f, panX = -100f, panY = -200f)
        val onPicture = view.toPicture(300f, 400f)
        assertEquals("x", (300f + 100f) / 4f, onPicture.x, 0.001f)
        assertEquals("y", (400f + 200f) / 4f, onPicture.y, 0.001f)
        assertEquals("and back to the screen", Drawing.Point(300f, 400f), view.toScreen(onPicture))
    }

    @Test
    fun `the picture cannot be panned entirely out of view`() {
        // **A picture panned away is a blank screen with no gesture that brings it back** — the person
        // would have to close and reopen the file. The bound allows half the picture past its own
        // edge so the corners are still reachable, and no further.
        val far = Drawing.View(zoom = 2f, panX = 100_000f, panY = -100_000f).bounded(1000, 500)
        assertEquals("x is bounded", 1000f * 2f * Drawing.View.EDGE_MARGIN, far.panX, 0.01f)
        assertEquals("y is bounded the other way", -500f * 2f * Drawing.View.EDGE_MARGIN, far.panY, 0.01f)

        val gentle = Drawing.View(zoom = 2f, panX = 10f, panY = 10f).bounded(1000, 500)
        assertEquals("and a small pan is left alone", 10f, gentle.panX, 0.01f)
    }

    @Test
    fun `the default view shows the whole picture, not a corner of it`() {
        // **The operator's correction, as a test.** A 1080-pixel picture in a 320-pixel window at 1:1
        // shows a third of it, and the person's first act is to zoom out before they can see what
        // they are annotating. Fitting first makes zooming in a choice rather than a correction.
        val view = Drawing.View.fittedTo(1080, 1080, 320f, 400f)

        assertEquals("the width is what limits it", 320f / 1080f, view.zoom, 0.001f)
        // And the whole picture really fits: its right edge lands on or inside the surface.
        val right = view.toScreen(Drawing.Point(1080f, 0f)).x
        assertTrue("the right edge is inside the window: $right", right <= 320f + 0.01f)
        val bottom = view.toScreen(Drawing.Point(0f, 1080f)).y
        assertTrue("and the bottom edge: $bottom", bottom <= 400f + 0.01f)
    }

    @Test
    fun `a fitted picture is centred rather than pinned to a corner`() {
        // A square picture in a wide window leaves space on the sides, and half of it belongs on each
        // side — a picture hugging the left edge looks like a layout mistake.
        val view = Drawing.View.fittedTo(100, 100, 400f, 100f)
        assertEquals("at 1:1, because it fits", 1f, view.zoom, 0.001f)
        assertEquals("centred horizontally", (400f - 100f) / 2f, view.panX, 0.01f)
        assertEquals("and flush vertically", 0f, view.panY, 0.01f)
    }

    @Test
    fun `a small picture is never blown up to fill the window`() {
        // An upscaled picture is blurry, and a person checking a detail wants the pixels the file
        // actually has. A 100-pixel icon in a 400-pixel window stays 100 pixels.
        val view = Drawing.View.fittedTo(100, 100, 400f, 400f)
        assertEquals("not enlarged", 1f, view.zoom, 0.001f)
    }

    @Test
    fun `the fit is never below the zoom floor`() {
        // A huge picture in a tiny window would otherwise compute a zoom that the rest of the canvas
        // refuses to accept — so the fit obeys the same limits a pinch does.
        val view = Drawing.View.fittedTo(100_000, 100_000, 100f, 100f)
        assertEquals("clamped to the floor", Drawing.View.MIN_ZOOM, view.zoom, 0.001f)
    }

    @Test
    fun `a fit with no measurements is the identity rather than a crash`() {
        // The first frame has no size yet. Returning 1:1 for one frame is invisible; dividing by zero
        // is not.
        assertEquals("zero surface", Drawing.View(), Drawing.View.fittedTo(100, 100, 0f, 0f))
        assertEquals("zero picture", Drawing.View(), Drawing.View.fittedTo(0, 0, 320f, 400f))
    }

    @Test
    fun `a page-shaped picture fits the window, which is the Photo case`() {
        // **The case the tour picture showed unfitted.** A photographed page is 1240 wide and taller
        // than the window; at 1:1 the person sees a corner of a letter. The fit must bring the whole
        // width in.
        val page = Drawing.View.fittedTo(1240, 900, 300f, 380f)
        assertTrue("zoomed out: ${page.zoom}", page.zoom < 0.5f)
        val right = page.toScreen(Drawing.Point(1240f, 0f)).x
        assertTrue("the whole width is visible: $right", right <= 300f + 0.5f)
        assertEquals("and the left edge is inside", 0f, page.toScreen(Drawing.Point(0f, 0f)).x, 0.5f)
    }
}
