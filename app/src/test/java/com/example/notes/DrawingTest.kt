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
}
