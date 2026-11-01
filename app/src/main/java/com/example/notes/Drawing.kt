package com.example.notes

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import java.io.ByteArrayOutputStream

/**
 * Drawing on a picture.
 *
 * **The strokes are the document, and the PNG is what they are baked into.** Everything here is a
 * function of a list of [Stroke]s, so the same list can be drawn on the screen and flattened into
 * bytes — one description, two uses, no second renderer to drift.
 *
 * **Coordinates are in the picture's own pixels, not the screen's.** A stroke made on a phone in
 * portrait must land in the same place when the same picture is opened on a tablet, and a canvas that
 * stored screen offsets would put it wherever the screen happened to be. So the drawing surface maps
 * screen points into picture points on the way in, and back on the way out.
 *
 * Four tools, and no more. Every tool is a mode inside the canvas, which is exactly the complexity the
 * file model avoids: a blank PNG is a sketch, a screenshot is an annotation, and a photo is a
 * correction — the tool does not need to know which.
 */
object Drawing {

    /** What a stroke does to the picture underneath it. */
    enum class Tool {
        /** Freehand: the path the finger took. */
        PEN,

        /** A straight line from where the finger went down to where it came up. */
        LINE,

        /** A rectangle between those two points. */
        BOX,

        /**
         * Rubs out **the strokes**, not the picture.
         *
         * **This is the decision that keeps the file honest.** An eraser that painted white would
         * destroy the picture underneath — a person erasing a wrong arrow over a screenshot would
         * lose the screenshot. Removing whole strokes is what an undo can express and what a person
         * means by "take that out".
         */
        ERASER,
    }

    /**
     * One mark: where it starts, where it goes, and what it is.
     *
     * [points] are in the **picture's** pixels. A line or a box uses the first and last; the pen uses
     * all of them.
     */
    data class Stroke(
        val tool: Tool,
        val points: List<Point>,
        val colour: Int,
        val width: Float,
    )

    /** A point in the picture's own coordinates. */
    data class Point(val x: Float, val y: Float)

    /** The colours a person may draw in. Red first: the commonest correction. */
    val COLOURS = listOf(
        0xFFE53935.toInt(), // red
        0xFF1E88E5.toInt(), // blue
        0xFF43A047.toInt(), // green
        0xFF000000.toInt(), // black
    )

    /** The widths offered, in picture pixels. */
    val WIDTHS = listOf(4f, 10f, 24f)

    /**
     * The strokes with the last one removed — **the whole of undo.**
     *
     * A stack of stroke lists is unnecessary: the strokes are a list, and undo is `dropLast`. Keeping
     * a stack of snapshots would let the two disagree after enough edits, and it is the same answer
     * with more ways to be wrong.
     */
    fun undo(strokes: List<Stroke>): List<Stroke> = strokes.dropLast(1)

    /**
     * The picture with [strokes] baked into it, as PNG bytes.
     *
     * **Drawn onto a copy, never onto the decoded original.** `BitmapFactory` may hand back a bitmap
     * that is the cache's own, and drawing on it would corrupt the picture for everything else that
     * reads it in this session.
     */
    fun flatten(picture: Bitmap, strokes: List<Stroke>): ByteArray {
        val canvas = Bitmap.createBitmap(picture.width, picture.height, Bitmap.Config.ARGB_8888)
        val ink = android.graphics.Canvas(canvas)
        ink.drawBitmap(picture, 0f, 0f, null)
        for (stroke in strokes) draw(ink, stroke)
        val bytes = ByteArrayOutputStream()
        canvas.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        canvas.recycle()
        return bytes.toByteArray()
    }

    /** One stroke onto an android canvas — the flattening half, shared with nothing else. */
    private fun draw(canvas: android.graphics.Canvas, stroke: Stroke) {
        if (stroke.points.isEmpty()) return
        val paint = android.graphics.Paint().apply {
            color = stroke.colour
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = stroke.width
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            isAntiAlias = true
        }
        val first = stroke.points.first()
        when (stroke.tool) {
            Tool.PEN -> {
                val path = android.graphics.Path()
                path.moveTo(first.x, first.y)
                for (point in stroke.points.drop(1)) path.lineTo(point.x, point.y)
                canvas.drawPath(path, paint)
            }
            Tool.LINE, Tool.BOX -> {
                // A tap with no drag draws nothing: a line needs two points, and a one-point line
                // would be an invisible dot the person cannot select to erase.
                val last = stroke.points.last()
                if (last == first) return
                if (stroke.tool == Tool.LINE) {
                    canvas.drawLine(first.x, first.y, last.x, last.y, paint)
                } else {
                    canvas.drawRect(
                        minOf(first.x, last.x),
                        minOf(first.y, last.y),
                        maxOf(first.x, last.x),
                        maxOf(first.y, last.y),
                        paint,
                    )
                }
            }
            // The eraser is not drawn — it *removes strokes*, which happens before flattening.
            Tool.ERASER -> Unit
        }
    }

    /**
     * Which stroke an eraser touch at [at] removes, or null for none.
     *
     * **Nearest within the stroke's own width**, so a person does not have to be exact: they tap near
     * the arrow they mean rather than on it. The comparison is against each segment of a pen stroke
     * and against the two points of a line or box, which is enough for the shapes this canvas makes.
     */
    fun hit(strokes: List<Stroke>, at: Point, slack: Float): Int? {
        // Backwards, so the most recently drawn stroke wins when two overlap — which is what a person
        // means when they rub out the thing they just added.
        for (index in strokes.indices.reversed()) {
            val stroke = strokes[index]
            if (stroke.tool == Tool.ERASER) continue
            val reach = stroke.width / 2 + slack
            for (i in stroke.points.indices) {
                val point = stroke.points[i]
                if (distance(at, point) <= reach) return index
                // A segment between consecutive points, so a long straight stroke is not a set of
                // gaps between sampled points.
                if (i > 0 && distanceToSegment(at, stroke.points[i - 1], point) <= reach) return index
            }
        }
        return null
    }

    private fun distance(a: Point, b: Point): Float =
        kotlin.math.hypot(a.x - b.x, a.y - b.y)

    private fun distanceToSegment(at: Point, from: Point, to: Point): Float {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared == 0f) return distance(at, from)
        val t = (((at.x - from.x) * dx + (at.y - from.y) * dy) / lengthSquared).coerceIn(0f, 1f)
        return distance(at, Point(from.x + t * dx, from.y + t * dy))
    }

    /** Draws [strokes] on the screen. The screen half of [flatten]'s job, over the same list. */
    fun DrawScope.drawStrokes(strokes: List<Stroke>, scale: Float) {
        for (stroke in strokes) {
            if (stroke.tool == Tool.ERASER || stroke.points.isEmpty()) continue
            val colour = Color(stroke.colour)
            val width = stroke.width * scale
            val first = stroke.points.first()
            when (stroke.tool) {
                Tool.PEN -> {
                    val path = Path()
                    path.moveTo(first.x * scale, first.y * scale)
                    for (point in stroke.points.drop(1)) path.lineTo(point.x * scale, point.y * scale)
                    drawPath(path, colour, style = Stroke(width, cap = StrokeCap.Round))
                }
                Tool.LINE, Tool.BOX -> {
                    val last = stroke.points.last()
                    if (last == first) continue
                    if (stroke.tool == Tool.LINE) {
                        drawLine(
                            colour,
                            Offset(first.x * scale, first.y * scale),
                            Offset(last.x * scale, last.y * scale),
                            strokeWidth = width,
                            cap = StrokeCap.Round,
                        )
                    } else {
                        drawRect(
                            colour,
                            topLeft = Offset(
                                minOf(first.x, last.x) * scale,
                                minOf(first.y, last.y) * scale,
                            ),
                            size = androidx.compose.ui.geometry.Size(
                                kotlin.math.abs(last.x - first.x) * scale,
                                kotlin.math.abs(last.y - first.y) * scale,
                            ),
                            style = Stroke(width),
                        )
                    }
                }
                Tool.ERASER -> Unit
            }
        }
    }
}

/**
 * The picture with a pen over it.
 *
 * **One composable, and it owns the strokes while it is open.** D4: undo is session-only, and the
 * saved versions are git's job — so the stroke list dies with the screen, which is exactly the
 * lifetime the operator asked for and one fewer thing to keep in step with a file on disk.
 *
 * [onStrokesChanged] exists so the screen's Save button can know whether there is anything to save;
 * the strokes themselves never leave this composable except as bytes.
 */
@Composable
fun DrawingSurface(
    picture: ImageBitmap,
    strokes: List<Drawing.Stroke>,
    tool: Drawing.Tool,
    colour: Int,
    width: Float,
    modifier: Modifier = Modifier,
    onAdd: (Drawing.Stroke) -> Unit,
    onErase: (Int) -> Unit,
) {
    // The stroke being drawn, in picture pixels, so the screen can show it as it happens.
    var current by remember { mutableStateOf<List<Drawing.Point>>(emptyList()) }
    // **The scale is read inside the gesture, not passed in**, because the canvas measures itself
    // after layout and the finger arrives after that. `IntSize` → picture pixels is the one mapping,
    // so what is drawn and what is flattened cannot disagree.
    var scale by remember { mutableStateOf(1f) }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Image(
            bitmap = picture,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(tool, colour, width, scale) {
                    detectDragGestures(
                        onDragStart = { at ->
                            val point = Drawing.Point(at.x / scale, at.y / scale)
                            if (tool == Drawing.Tool.ERASER) {
                                Drawing.hit(strokes, point, 8f / scale)?.let(onErase)
                            } else {
                                current = listOf(point)
                            }
                        },
                        onDrag = { change, _ ->
                            if (tool == Drawing.Tool.ERASER) return@detectDragGestures
                            val point = Drawing.Point(change.position.x / scale, change.position.y / scale)
                            // A line and a box keep two points — where it started and where it is now.
                            // The pen keeps every one, because its shape is the path.
                            current = if (tool == Drawing.Tool.PEN) current + point else
                                listOf(current.firstOrNull() ?: point, point)
                        },
                        onDragEnd = {
                            if (current.isNotEmpty() && tool != Drawing.Tool.ERASER) {
                                onAdd(Drawing.Stroke(tool, current, colour, width))
                            }
                            current = emptyList()
                        },
                        onDragCancel = { current = emptyList() },
                    )
                },
        ) {
            // The surface is the picture's box, so `size` divided by the picture's own width is the
            // mapping between the two coordinate systems.
            scale = size.width / picture.width.toFloat()
            with(Drawing) { drawStrokes(strokes, scale) }
            if (current.isNotEmpty()) {
                with(Drawing) {
                    drawStrokes(
                        listOf(Drawing.Stroke(tool, current, colour, width)),
                        scale,
                    )
                }
            }
        }
    }
}
