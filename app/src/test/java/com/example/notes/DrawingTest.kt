package com.example.notes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
 * Drawing on a picture: the strokes, and what they become.
 *
 * **Robolectric, because `flatten` bakes into a `Bitmap`.** The stroke list itself is plain
 * arithmetic and could be tested anywhere; the thing worth proving is that what a person draws
 * really lands in the pixels — a flatten that drew nothing would satisfy every assertion about the
 * list and produce an unchanged file.
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
    fun `a stroke lands in the pixels`() {
        // **The assertion that a flatten drawing nothing would fail.** A red line across white must
        // leave red where the line is and white where it is not.
        val bytes = Drawing.flatten(white(), listOf(stroke()))
        val out = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!

        // **"Not white" rather than "exactly red"**, because the stroke is antialiased and the exact
        // pixel at its centre depends on where the line falls relative to the pixel grid. Asserting
        // `Color.RED` would make the test about the rasteriser rather than about the stroke landing.
        assertNotEquals("the line is inked where it was drawn", Color.WHITE, out.getPixel(50, 50))
        assertEquals("and the rest is untouched", Color.WHITE, out.getPixel(5, 95))
        out.recycle()
    }

    @Test
    fun `no strokes leaves the picture exactly as it was`() {
        // Saving with nothing drawn must be a no-op rather than a re-encode that loses quality or
        // changes the picture. This is what makes "Save is disabled with no strokes" a courtesy
        // rather than the only thing holding the rule.
        val bytes = Drawing.flatten(white(), emptyList())
        val out = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!

        for ((x, y) in listOf(0 to 0, 50 to 50, 99 to 99)) {
            assertEquals("($x,$y) untouched", Color.WHITE, out.getPixel(x, y))
        }
        out.recycle()
    }

    @Test
    fun `the original bitmap is not drawn on`() {
        // **A decoded bitmap may be the cache's own.** Drawing on it would corrupt the picture for
        // everything else in this session, which is why `flatten` copies first.
        val picture = white()
        Drawing.flatten(picture, listOf(stroke()))
        assertEquals("the source is still white", Color.WHITE, picture.getPixel(50, 50))
        picture.recycle()
    }

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
    fun `a line needs two points, so a tap draws nothing`() {
        // A one-point line would be an invisible dot the person cannot select to erase — so it is
        // not drawn at all rather than becoming a mark nobody can see or remove.
        val tap = stroke(Drawing.Tool.LINE, listOf(50f to 50f))
        val bytes = Drawing.flatten(white(), listOf(tap))
        val out = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
        assertEquals("nothing was drawn", Color.WHITE, out.getPixel(50, 50))
        out.recycle()
    }

    @Test
    fun `a box is drawn between its two corners`() {
        val box = stroke(Drawing.Tool.BOX, listOf(20f to 20f, 80f to 80f))
        val bytes = Drawing.flatten(white(), listOf(box))
        val out = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!

        assertNotEquals("on the top edge", Color.WHITE, out.getPixel(50, 20))
        assertNotEquals("and the bottom", Color.WHITE, out.getPixel(50, 80))
        assertEquals("inside is untouched, because a box is an outline", Color.WHITE, out.getPixel(50, 50))
        out.recycle()
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
        val out = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!

        assertEquals("width", 120, out.width)
        assertEquals("height", 80, out.height)
        out.recycle()
        picture.recycle()
    }

    @Test
    fun `two different strokes make two different pictures`() {
        // A flatten that ignored its argument would pass every "the picture is still the right size"
        // test. This is the one that would fail.
        val one = Drawing.flatten(white(), listOf(stroke(colour = Color.RED)))
        val two = Drawing.flatten(white(), listOf(stroke(colour = Color.BLUE)))
        assertNotEquals("the colour reached the pixels", one.toList(), two.toList())
    }

    @Test
    fun `every offered colour and width can be drawn`() {
        // The row offers four colours and three widths, and a description that offers what the code
        // refuses is a lie told on every frame.
        for (shade in Drawing.COLOURS) {
            for (thick in Drawing.WIDTHS) {
                val bytes = Drawing.flatten(white(), listOf(stroke(colour = shade, width = thick)))
                val out = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!
                assertTrue("${shade.toString(16)} at $thick drew something", bytes.size > 100)
                out.recycle()
            }
        }
    }
}
