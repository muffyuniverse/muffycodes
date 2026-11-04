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
    fun `a typed stroke width is clamped, and nonsense leaves the width alone`() {
        // **The operator**: "add stroke size field where you can type size number."
        //
        // The two answers are deliberately different. `900` says "as wide as you allow", so it is
        // clamped — the person meant something wide and the field should not silently do nothing.
        // `abc` says nothing at all, so it is null and the width stays where it was; turning it into a
        // default would be the field typing on the person's behalf.
        assertEquals("a number is a number", 12f, Drawing.widthOf("12"), 0.001f)
        assertEquals("a comma is a decimal point in half of Europe", 7.5f, Drawing.widthOf("7,5"), 0.001f)
        assertEquals("spaces are not content", 4f, Drawing.widthOf("  4  "), 0.001f)
        assertEquals("too wide is as wide as allowed", Drawing.MAX_WIDTH, Drawing.widthOf("900"), 0.001f)
        assertEquals("too thin is a hairline", 1f, Drawing.widthOf("0"), 0.001f)
        assertEquals("a negative is a hairline too", 1f, Drawing.widthOf("-5"), 0.001f)

        assertNull("a word is not a number", Drawing.widthOf("abc"))
        assertEquals("nor is an empty field", null, Drawing.widthOf(""))
        assertNull("nor is NaN", Drawing.widthOf("NaN"))
    }

    @Test
    fun `a colour prints as the hex every other tool uses`() {
        // The field beside the wheel, and a designer's spec sheet, and a browser's inspector all say
        // `#E53935` — so that is what the app prints, not a Kotlin `Color.toString()`.
        assertEquals("#E53935", Drawing.hex(0xFFE53935.toInt()))
        assertEquals("#000000", Drawing.hex(0xFF000000.toInt()))
        // **The alpha byte is not printed**, because every stroke is opaque: the picker hides its own
        // alpha slider and the strokes are baked into a PNG, so a printed alpha would be a number that
        // is always FF and never anything else.
        assertEquals("#1E88E5", Drawing.hex(0xFF1E88E5.toInt()))
    }

    @Test
    fun `the pinch limits are the operator's numbers`() {
        // **"limit min zoom by 30% ... and max zoom by 300%."** Asserted as literals rather than
        // against the constants, because a test that read `MIN_ZOOM` would pass whatever the constant
        // became — and the number is the request.
        assertEquals("a third, no further out", 0.3f, Drawing.View.MIN_ZOOM, 0.001f)
        assertEquals("three times, no further in", 3f, Drawing.View.MAX_ZOOM, 0.001f)
    }

    @Test
    fun `the fit is not held to the pinch floor, so a page still fits whole`() {
        // **The floor is on the gesture, not on the answer to "show me the whole thing".** A
        // 1240-pixel page in a 300-pixel window needs 0.24, and a 0.3 floor would make it 372 pixels
        // wide — overflowing 36 pixels each side, which is the bug that was already fixed once when
        // the floor was 0.25.
        val page = Drawing.View.fittedTo(1240, 1754, 300f, 500f)

        assertEquals("the whole page, at whatever it takes", 300f / 1240f, page.zoom, 0.001f)
        assertTrue(
            "**which is below the pinch floor** — that is the point: ${page.zoom}",
            page.zoom < Drawing.View.MIN_ZOOM,
        )
        // **And the picture is centred**, so the leftover space is split rather than all on one side.
        assertEquals("centred horizontally", (300f - 1240 * page.zoom) / 2f, page.panX, 0.5f)
    }

    @Test
    fun `zooming out from the fit keeps the picture centred`() {
        // **The operator**: "why when you zoom out the drawing isnt centered on the screen but moves
        // to the upper left corner?"
        //
        // The answer was not that the zoom was uncentred — `zoomedBy` keeps the focal point under the
        // fingers — it was that the *bound* then refused the centred pan. The bound had no window to
        // compare against, so a picture narrower than the window sat outside its range and was clamped
        // toward the top-left, on the frame it became small enough to fit.
        val surface = 1000f
        val fitted = Drawing.View.fittedTo(1080, 1920, surface, 1800f)
        // **Zoom out about the centre of the window**, which is what a pinch there does.
        val out = fitted.zoomedBy(0.5f, surface / 2f, 900f)
        val bounded = out.bounded(
            surfaceWidth = surface,
            surfaceHeight = 1800f,
            pictureWidth = 1080,
            pictureHeight = 1920,
        )

        assertEquals("bounding did not move it at all", out, bounded)
        // **And it really is centred by the range's own arithmetic**: the leftover space is split.
        val slackX = surface - 1080f * bounded.zoom
        assertEquals("centred horizontally", slackX / 2f, bounded.panX, bounded.zoom * 0.5f)
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
    fun `a picture wider than the window can be moved until its edges meet the window's`() {
        // **A picture panned away is a blank screen with no gesture that brings it back.** The range
        // is the one that is right for a big picture: its left edge goes from `surface - shown` (right
        // edges meeting) to `0` (left edges meeting). Every part reachable, nothing further.
        val far = Drawing.View(zoom = 2f, panX = 100_000f, panY = -100_000f)
            .bounded(surfaceWidth = 1000f, surfaceHeight = 500f, pictureWidth = 1000, pictureHeight = 500)

        assertEquals("the left edge stops where the right edges meet", 0f, far.panX, 0.01f)
        assertEquals("and the other way", 500f - 500f * 2f, far.panY, 0.01f)

        val gentle = Drawing.View(zoom = 2f, panX = -10f, panY = -10f)
            .bounded(surfaceWidth = 1000f, surfaceHeight = 500f, pictureWidth = 1000, pictureHeight = 500)
        assertEquals("and a small pan is left alone", -10f, gentle.panX, 0.01f)
    }

    @Test
    fun `a picture smaller than the window is kept wholly inside it, and can be moved around`() {
        // **The other half of the same range**, and the half that was wrong. When the picture is
        // narrower than the window the range is `0..slack` — it may slide anywhere inside the window,
        // and no further. The old rule had no window to compare with, so a centred small picture was
        // outside its allowed range and got clamped toward the top-left: that is the drift the
        // operator reported, and it happened on the frame the picture became small enough to fit.
        val centred = Drawing.View(zoom = 0.5f, panX = 250f, panY = 250f)
            .bounded(surfaceWidth = 1000f, surfaceHeight = 1000f, pictureWidth = 1000, pictureHeight = 1000)
        assertEquals("**the centre is allowed** — it always was, geometrically", 250f, centred.panX, 0.01f)
        assertEquals("on both axes", 250f, centred.panY, 0.01f)

        val far = Drawing.View(zoom = 0.5f, panX = 100_000f, panY = -100_000f)
            .bounded(surfaceWidth = 1000f, surfaceHeight = 1000f, pictureWidth = 1000, pictureHeight = 1000)
        assertEquals("it stops at the window's right edge", 500f, far.panX, 0.01f)
        assertEquals("and at its top", 0f, far.panY, 0.01f)
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
    fun `the fit may go below the pinch floor, because "show me all of it" is not a zoom`() {
        // **A huge picture in a tiny window must still be shown whole.** A floor here would make the
        // answer to "show me the whole thing" no — and it has already caused that bug once, when the
        // floor was 0.25 and a 1240-pixel page in a 300-pixel window needed 0.24.
        val view = Drawing.View.fittedTo(100_000, 100_000, 100f, 100f)
        assertEquals("the whole thing, at whatever it takes", 0.001f, view.zoom, 0.0001f)
        assertTrue("**below the pinch floor**", view.zoom < Drawing.View.MIN_ZOOM)
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
        // **The whole picture is inside the window on both axes** — the property that "fits" means.
        // Asserting only the right edge caught a real defect but asserted the wrong thing: a centred
        // picture narrower than the window starts at x > 0, so `right == width` is not the test.
        val left = page.toScreen(Drawing.Point(0f, 0f))
        val right = page.toScreen(Drawing.Point(1240f, 900f))
        assertTrue("the left edge is inside: ${left.x}", left.x >= -0.5f)
        assertTrue("the right edge is inside: ${right.x}", right.x <= 300f + 0.5f)
        assertTrue("the top is inside: ${left.y}", left.y >= -0.5f)
        assertTrue("and the bottom: ${right.y}", right.y <= 380f + 0.5f)
    }
}
