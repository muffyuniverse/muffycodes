package com.example.notes

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.padding
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
     * **The widest stroke a typed number may ask for**, in picture pixels.
     *
     * A stroke is drawn as a rounded line; past about a fifth of the picture it stops being a line and
     * becomes a shape, and a person who typed 900 by mistake would fill the canvas with one drag. 64 is
     * generous — wider than any of the offered [WIDTHS] — and still a line.
     */
    const val MAX_WIDTH = 64f

    /**
     * **A stroke width, from what a person typed** — or null when it is not a number.
     *
     * The operator: "add stroke size field where you can type size number." Null rather than a clamp
     * for input that is not a number at all, because a field that turned "abc" into 24 would be a field
     * that typed something on the person's behalf. A number out of range *is* clamped, and that is the
     * difference: 900 says "as wide as you allow", "abc" says nothing.
     */
    fun widthOf(typed: String): Float? {
        // **A comma is a decimal point in half of Europe**, and the keyboard offers one on the number
        // pad in those locales. Replacing it is the whole of what this function does about input.
        val clean = typed.trim().replace(',', '.')
        val value = clean.toFloatOrNull() ?: return null
        if (value != value) return null // NaN, which `toFloatOrNull` accepts
        return value.coerceIn(1f, MAX_WIDTH)
    }

    /**
     * **A colour as `#RRGGBB`**, for the field beside the wheel.
     *
     * The hex form rather than `Color.toString()`'s, because it is the one every other tool a person
     * uses prints — a designer's spec sheet and a browser's inspector both say `#E53935`.
     */
    fun hex(colour: Int): String = "#%06X".format(colour and 0xFFFFFF)

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
        drawAll(ink, strokes)
        val bytes = ByteArrayOutputStream()
        canvas.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        canvas.recycle()
        return bytes.toByteArray()
    }

    /**
     * Draws [strokes] onto an android canvas, over whatever is already on it.
     *
     * **Private again, and the story is worth keeping.** It was `internal` for a while so a test
     * could look at the pixels — and that test then failed with every pixel still white, because
     * Robolectric's `Canvas` accepts draw calls and rasterises nothing. There is no way to ask
     * "did the stroke land" in this container, so the visibility was opened for a question that
     * cannot be asked here. The rasterising is checked by the screenshot tour on a device.
     */
    private fun drawAll(canvas: android.graphics.Canvas, strokes: List<Stroke>) {
        for (stroke in strokes) draw(canvas, stroke)
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

    /**
     * **What one finger does, as a value rather than a branch buried in the gesture handler.**
     *
     * The operator's rule, twice stated: "when any draw tool is selected you can only use it — no
     * movement, no zoom", and "when no tools selected one finger is for movement and two fingers for
     * zoom".
     *
     * It is a function here so it can be *asked*, which is the only way to test it: the gesture
     * handler is a `pointerInput` block and the canvas it draws on is not rasterised in a unit test.
     */
    enum class OneFinger {
        /** No tool: the finger moves the picture. */
        PAN,

        /** The eraser: the finger removes whole strokes. */
        ERASE,

        /** A pen, line or box: the finger draws. */
        DRAW,
    }

    /** What a single finger does with [tool] chosen, or with none. */
    fun oneFinger(tool: Tool?): OneFinger = when (tool) {
        null -> OneFinger.PAN
        Tool.ERASER -> OneFinger.ERASE
        else -> OneFinger.DRAW
    }

    /**
     * **Whether two fingers may zoom and pan the picture.**
     *
     * False with any tool chosen, which is the strict half of the operator's rule and the half that is
     * easy to lose: a pinch that moved the paper mid-stroke is movement the tool was supposed to
     * forbid. The stroke is still dropped and the pinch is still swallowed, so the gesture is not
     * silently reinterpreted as a draw.
     */
    fun twoFingers(tool: Tool?): Boolean = tool == null

    /**
     * How the picture is shown: how far in, and how far along.
     *
     * **One value, and every mapping goes through it.** A zoom and a pan are two numbers that must
     * agree — the point under a person's fingers has to stay under their fingers while they pinch —
     * and two separate states would let them drift apart on the frame after a gesture.
     *
     * **[zoom] of 1 is one picture pixel per screen pixel.** Below it the whole picture is visible and
     * a mark is coarse; above it a person can put a correction on a single letter of a screenshot,
     * which is the case this exists for.
     */
    data class View(val zoom: Float = 1f, val panX: Float = 0f, val panY: Float = 0f) {

        /** A screen point, as a point on the picture. */
        fun toPicture(x: Float, y: Float): Point = Point((x - panX) / zoom, (y - panY) / zoom)

        /** A picture point, as a point on the screen. */
        fun toScreen(point: Point): Point = Point(point.x * zoom + panX, point.y * zoom + panY)

        /**
         * Pinched by [factor] about the screen point ([x], [y]).
         *
         * **The focal point is what makes a pinch feel like a pinch**: whatever was under the fingers
         * stays under them, so the picture grows *around* the place being examined rather than around
         * its own corner. The algebra is the whole of it — the picture point under the fingers is
         * fixed, so the pan that keeps it there is `p - p·zoom'`.
         */
        fun zoomedBy(factor: Float, x: Float, y: Float): View {
            val next = (zoom * factor).coerceIn(MIN_ZOOM, MAX_ZOOM)
            // Nothing moved: at a limit the pan must not creep, or a pinch that hits the ceiling
            // would slide the picture sideways while the person thinks they are only zooming.
            if (next == zoom) return this
            val under = toPicture(x, y)
            return View(next, x - under.x * next, y - under.y * next)
        }

        /** Dragged by a finger, in screen pixels. */
        fun pannedBy(dx: Float, dy: Float): View = copy(panX = panX + dx, panY = panY + dy)

        /**
         * The view after a new picture size, so a zoom cannot leave the picture off screen.
         *
         * **Bounded rather than free**, because a picture panned entirely out of view is a blank
         * screen with no gesture that brings it back — the person would have to close and reopen the
         * file. The allowance is a margin either side so the edges can still be reached.
         */
        fun bounded(
            surfaceWidth: Float,
            surfaceHeight: Float,
            pictureWidth: Int,
            pictureHeight: Int,
        ): View {
            val shownWidth = pictureWidth * zoom
            val shownHeight = pictureHeight * zoom
            // **The one range that is right for both cases, and it is the same expression for both.**
            //
            // A pan is the picture's left edge in screen coordinates, so the rule is "the left edge
            // goes from the smaller of the two extremes to the larger":
            //
            // * **Picture wider than the window** (`surface - shown < 0`): the range is
            //   `[surface - shown, 0]`, so it can slide until its right edge meets the window's right
            //   edge and its left edge meets the window's left edge — every part reachable, nothing
            //   further.
            // * **Picture narrower than the window** (`surface - shown > 0`): the range is
            //   `[0, surface - shown]`, so it stays wholly inside the window and can be slid around
            //   in it.
            //
            // **This is what the corner drift was.** The old bound was measured against the picture
            // alone — it had no surface to compare with — so a *centred* small picture sat outside the
            // allowed range and was clamped toward the top-left on the very frame it became small
            // enough to fit. The operator: "why when you zoom out the drawing isnt centered on the
            // screen but moves to the upper left corner?" It was not a centring bug; the centre was
            // being refused.
            fun range(surface: Float, shown: Float): ClosedFloatingPointRange<Float> {
                val slack = surface - shown
                return if (slack >= 0f) 0f..slack else slack..0f
            }
            return copy(
                panX = panX.coerceIn(range(surfaceWidth, shownWidth)),
                panY = panY.coerceIn(range(surfaceHeight, shownHeight)),
            )
        }

        companion object {
            /**
             * **How far out a person may pinch: a third.** The operator's number — "limit min zoom by
             * 30%" — and it is a floor on the *gesture*, not on the fit.
             *
             * **The distinction is not pedantic, and it is the bug that was here before.** A 1240-pixel
             * page in a 300-pixel window needs 0.24 to fit whole, so a 0.25 floor made "fit" 310 pixels
             * wide and overflowed five pixels each side — the person could not see the page they had
             * just made. A floor applied to the fit would reintroduce exactly that at 0.3. See
             * [fittedTo], which is allowed below this because a picture that does not fit is not a
             * picture a person can look at.
             */
            const val MIN_ZOOM = 0.3f

            /**
             * **How far in a person may pinch: three times.** The operator's number — "and max zoom by
             * 300%". A 1080-wide screenshot at 3× shows 360 pixels across the screen, which is enough
             * to place a mark on one word.
             */
            const val MAX_ZOOM = 3f


            /**
             * The view that shows the whole picture in a surface of [surfaceWidth]×[surfaceHeight].
             *
             * **The default, because 1:1 is the wrong place to start.** A 1080-pixel picture in a
             * 320-pixel-wide window at 1:1 shows a third of it, and the person's first act is to zoom out
             * before they can see what they are annotating. Fitting first means the picture is whole on
             * arrival and zooming in is a deliberate choice rather than a correction.
             *
             * **Never larger than 1:1.** A small picture — a 100-pixel icon — is shown at its own size
             * rather than blown up to fill the window, because an upscaled picture is blurry and a person
             * checking a detail wants the pixels the file actually has.
             *
             * Centred, so a picture narrower than the surface sits in the middle rather than against an
             * edge.
             */
            fun fittedTo(pictureWidth: Int, pictureHeight: Int, surfaceWidth: Float, surfaceHeight: Float): View {
                if (pictureWidth <= 0 || pictureHeight <= 0 || surfaceWidth <= 0f || surfaceHeight <= 0f) {
                    return View()
                }
                // **The fit is exempt from [MIN_ZOOM]**, because it is not a zoom the person chose:
                // it is the answer to "show me the whole thing", and a floor would make that answer
                // no. It is still capped at 1:1 so a small icon is not blown up and blurred.
                val fit = minOf(surfaceWidth / pictureWidth, surfaceHeight / pictureHeight)
                val zoom = fit.coerceIn(0.01f, 1f)
                // The leftover space on each axis, halved: that is where the picture's top-left goes for
                // it to sit in the middle.
                val panX = (surfaceWidth - pictureWidth * zoom) / 2f
                val panY = (surfaceHeight - pictureHeight * zoom) / 2f
                return View(zoom, panX, panY)
            }
        }
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
    tool: Drawing.Tool?,
    colour: Int,
    width: Float,
    modifier: Modifier = Modifier,
    onAdd: (Drawing.Stroke) -> Unit,
    onErase: (Int) -> Unit,
) {
    // The stroke being drawn, in picture pixels, so the screen can show it as it happens.
    var current by remember { mutableStateOf<List<Drawing.Point>>(emptyList()) }
    // **One value for zoom and pan together**, so the two cannot disagree on the frame after a
    // gesture — see `Drawing.View`.
    // **Null until the surface has measured itself**, because the fit needs a size and a composition
    // has none. The first frame draws nothing; the second draws the picture whole.
    /**
     * The view, or **null while the person has not touched it** — in which case the fit is used.
     *
     * **Null means "still following the window", not "not measured yet".** The distinction is what
     * the previous version got wrong: it set `view` once from a `LaunchedEffect`, so the fit computed
     * from the *first* frame's measurements was kept for ever — and on the frame a screenshot tour
     * captures, that fit was the wrong one, which is why the picture showed a speck. Leaving it null
     * until a gesture changes it means every frame recomputes the fit from the window it actually has,
     * and the first gesture takes over from there.
     */
    var touched by remember(picture) { mutableStateOf<Drawing.View?>(null) }

    /**
     * **One finger draws, two fingers move the picture.**
     *
     * That split is the whole interaction: a canvas where a drag both drew and panned would do one of
     * them by accident, and a person would find their stroke had become a scroll. Counting pointers is
     * how the two are told apart without a mode switch nobody would remember to press.
     *
     * A second finger arriving **cancels the stroke in progress** rather than leaving a half-drawn
     * mark behind — the person's intent changed at that moment, so the mark up to it is not something
     * they meant.
     */
    // **The picture is framed, and the frame is the point.** A blank PNG is white and the page behind
    // it is white, so an unframed canvas is indistinguishable from one that failed to load — which is
    // exactly what the first tour picture showed: a white rectangle nobody could see. The border and
    // the shadow say "this is the picture, and it is here", and they cost nothing to draw.
    BoxWithConstraints(
        modifier = modifier
            .clipToBounds()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
    // **The window's size, which is what a fit is measured against.** A `DrawScope`'s `size` is the
    // canvas's own box — the picture's natural size — so reading it there made every fit 1:1.
    // **Guarded against an unbounded constraint.** A `BoxWithConstraints` inside a scrolling or
    // weighted parent reports `Infinity` for the axis that is not bounded, and `Infinity` in the fit
    // makes the zoom enormous — the picture became a speck in the middle of a blank page, which is
    // what the second tour picture showed. A non-finite side means "not measured yet", and zero is the
    // value the fit already treats as such.
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    fun side(value: androidx.compose.ui.unit.Dp): Int =
        if (value.value.isFinite()) (value.value * density).toInt() else 0
    val windowSize = androidx.compose.ui.unit.IntSize(side(maxWidth), side(maxHeight))
      Box(
        modifier = Modifier
            // **`fillMaxSize`, or the wrapper is the picture's size rather than the window's.** Without
            // it the inner Box wraps its content, the Canvas inside it becomes the picture's natural
            // size, and the surface a person draws on is a canvas bigger than the screen — which pushed
            // the tool row off and left the fitted picture as a speck in the corner.
            .fillMaxSize()
            .shadow(2.dp)
            .border(1.dp, MaterialTheme.colorScheme.outline)
            .clipToBounds(),
      ) {
        // **The list and the view, read through a holder rather than captured.** A `pointerInput`
        // block keeps the values it was built with, so an eraser drag that removed a stroke would go
        // on testing indices against the list from before that erase — removing the wrong stroke, or
        // none. Keying on `strokes` fixes the staleness but restarts the gesture handler, which ends a
        // continuous erase drag after one stroke. `rememberUpdatedState` gives the handler the current
        // value without restarting it, which is what both cases need.
        val liveStrokes by rememberUpdatedState(strokes)
        val liveView by rememberUpdatedState(touched)
        // **Fitted on the very first frame, not after a `LaunchedEffect` hop.** The effect runs after
        // the composition, so a capture can land on a frame where it has not — which is exactly what
        // the tour showed. `touched == null` means "follow the window", so the fit is recomputed from
        // whatever size the layout actually has, every frame, until a gesture takes over.
        val shown = touched ?: Drawing.View.fittedTo(
            picture.width,
            picture.height,
            windowSize.width.toFloat(),
            windowSize.height.toFloat(),
        )
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // **A description, so a test can find the surface it is drawing on.** The canvas has
                // no text and no children, so without this there is nothing to address — and the one
                // question that matters here ("does a stroke appear") is only askable through a touch.
                .semantics { contentDescription = "drawing canvas" }
                .pointerInput(tool, colour, width) {
                    awaitEachGesture {
                        // **The view as it is when this gesture begins**, so the arithmetic inside is
                        // not reading a state that a previous frame has already changed.
                        var currentView = liveView ?: Drawing.View()
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // What a single finger is doing: drawing, or erasing as it moves.
                        var drawing = false
                        var erasing = false
                        var lastPan = down.position
                        // The previous frame's finger span, for the pinch ratio.
                        var lastSpan = 0f
                        // The touch count at the moment the gesture began decides its kind, and a
                        // second finger arriving cancels a stroke rather than converting it.
                        val startCount = currentEvent.changes.size
                        // **Two modes, and the tool decides which — not the finger count.**
                        //
                        // | tool | one finger | two fingers |
                        // |---|---|---|
                        // | any (pen, line, box, eraser) | draw or erase | nothing |
                        // | none | move the picture | zoom |
                        //
                        // That is the operator's rule, twice stated: "when any draw tool is selected
                        // you can only use it — no movement, no zoom", and "when no tools selected one
                        // finger is for movement and two fingers for zoom".
                        //
                        // **The count cannot decide it, which is why this is read once from the
                        // tool.** A split by finger count alone would let a two-finger pinch move the
                        // paper while a pen was chosen, and the person's stroke would end in a
                        // jump they did not ask for. Nothing is decided by how many fingers are down
                        // except *inside* the no-tool mode, where that is the whole distinction.
                        var panning = false
                        val chosen = tool
                        // **No tool: the whole gesture is movement**, whether it began with one
                        // finger or two. One finger then pans, two pinch-and-pan, and losing one
                        // keeps panning.
                        if (startCount == 1) {
                            val at = currentView.toPicture(down.position.x, down.position.y)
                            // **The rule is `oneFinger`'s**, so this handler and a test cannot
                            // disagree about what a finger does.
                            when (oneFinger(chosen)) {
                                OneFinger.PAN -> panning = true
                                OneFinger.ERASE -> {
                                    erasing = true
                                    Drawing.hit(liveStrokes, at, 8f / currentView.zoom)?.let(onErase)
                                }
                                OneFinger.DRAW -> {
                                    drawing = true
                                    current = listOf(at)
                                }
                            }
                        } else if (oneFinger(chosen) == OneFinger.PAN) {
                            // **A gesture that began with two fingers, in the look mode**, is a
                            // pinch; if it loses one it keeps panning rather than becoming a stroke.
                            panning = true
                        }

                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            // **Bounded on every frame, before any branch can `continue` past it**, so
                            // a pan cannot walk the picture off screen and leave a blank canvas with no
                            // gesture that brings it back. It sits above the branches for exactly that
                            // reason: two of them skip to the next frame.
                            currentView = currentView.bounded(
                                windowSize.width.toFloat(),
                                windowSize.height.toFloat(),
                                picture.width,
                                picture.height,
                            )
                            touched = currentView
                            if (pressed.size >= 2) {
                                // **With a tool chosen, a second finger does nothing at all.** The
                                // operator: "when any draw tool is selected you can only use it — no
                                // movement, no zoom." That is stricter than the usual split, and it is
                                // deliberate: a pinch that arrived mid-stroke used to drop the stroke
                                // and move the paper, which is two surprises from one gesture.
                                //
                                // The stroke is still dropped, because the person changed what they
                                // were doing and a mark left half-made is not one they meant. What
                                // does not happen is the zoom.
                                if (!twoFingers(chosen)) {
                                    if (drawing || erasing) {
                                        current = emptyList()
                                        drawing = false
                                        erasing = false
                                    }
                                    event.changes.forEach { it.consume() }
                                    lastSpan = 0f
                                    continue
                                }
                                // **Two fingers: scale about their midpoint, and move with it.** Any
                                // stroke in progress is dropped, because the person has changed what
                                // they are doing — leaving the mark so far would be a mark they never
                                // finished.
                                if (drawing || erasing) {
                                    current = emptyList()
                                    drawing = false
                                    erasing = false
                                }
                                val a = pressed[0].position
                                val b = pressed[1].position
                                val span = (a - b).getDistance()
                                val middle = (a + b) / 2f
                                // **Compared with the previous frame's values**, which is what makes
                                // this incremental: one pinch is many small ratios, not one big one.
                                if (lastSpan > 0f && span > 0f) {
                                    currentView = currentView.zoomedBy(span / lastSpan, middle.x, middle.y)
                                }
                                currentView = currentView.pannedBy(
                                    middle.x - lastPan.x,
                                    middle.y - lastPan.y,
                                )
                                touched = currentView
                                lastSpan = span
                                lastPan = middle
                                event.changes.forEach { it.consume() }
                                continue
                            }

                            // **`panning`, not "not drawing"** — and the difference is a real bug.
                            // This branch used to fire whenever nothing was being drawn, so a gesture
                            // that *began* with two fingers while a pen was chosen fell through to
                            // here on the next frame and moved the picture: exactly the movement the
                            // tool is supposed to forbid. A gesture that began as a pinch and lost a
                            // finger also keeps panning, because `panning` is set once, when the
                            // gesture begins, rather than re-decided from the fingers still down.
                            if (panning && pressed.size == 1) {
                                val at = pressed[0].position
                                currentView = currentView.pannedBy(at.x - lastPan.x, at.y - lastPan.y)
                                touched = currentView
                                lastPan = at
                                pressed[0].consume()
                                continue
                            }

                            val change = pressed.firstOrNull() ?: break
                            val at = currentView.toPicture(change.position.x, change.position.y)
                            change.consume()
                            if (erasing) {
                                Drawing.hit(liveStrokes, at, 8f / currentView.zoom)?.let(onErase)
                            } else if (drawing) {
                                // A line and a box keep two points — where it started and where it is
                                // now. The pen keeps every one, because its shape is the path.
                                // **`chosen`, which is non-null whenever `drawing` is** — the flag is
                                // only set in the branch that checked it. Reading `tool` here would
                                // mean the handler could see a tool chosen *after* this gesture began,
                                // which is the staleness `liveStrokes` exists to avoid.
                                current = if (chosen == Drawing.Tool.PEN) current + at else
                                    listOf(current.firstOrNull() ?: at, at)
                            }
                        } while (event.changes.any { it.pressed })

                        if (drawing && current.isNotEmpty() && chosen != null) {
                            onAdd(Drawing.Stroke(chosen, current, colour, width))
                        }
                        current = emptyList()
                    }
                },
        ) {
            // **The surface's own size, which is what the fit needs and what the picture is drawn
            // into.** Read here because a composition knows its size only after layout.
            // **`size` is the DrawScope's own box**, which is the picture's natural size here because
            // the canvas is `fillMaxSize` inside the framed box — so it is *not* what the fit needs.
            // The fit needs the **window**, and the only place that is known is the layout. Reading
            // `size` here gave the picture's own width back, so `fittedTo` computed 1:1 every time and
            // the canvas opened unfitted — which is exactly what the tour picture showed: a corner of
            // a page at full size.

            // **Scaled and panned by hand rather than by a modifier**, because the strokes are in the
            // picture's coordinates and must move with it. A `graphicsLayer` would transform the
            // picture and leave the marks where they were.
            withTransform({
                translate(shown.panX, shown.panY)
                scale(shown.zoom, shown.zoom, pivot = Offset.Zero)
            }) {
                drawImage(
                    picture,
                    dstSize = androidx.compose.ui.unit.IntSize(picture.width, picture.height),
                )
                with(Drawing) { drawStrokes(strokes, 1f) }
                if (current.isNotEmpty()) {
                    with(Drawing) {
                        // **The tool in force, and only while a stroke is being made.** `current` is
                        // empty whenever no tool is chosen, so with nothing selected there is nothing
                        // to preview — and the non-null assertion is the link between those two
                        // facts rather than a guess about them.
                        drawStrokes(
                            listOf(Drawing.Stroke(tool ?: Drawing.Tool.PEN, current, colour, width)),
                            1f,
                        )
                    }
                }
            }
        }
        // **The zoom, said out loud**, because a pinch is invisible once it has happened: a person who
        // has zoomed to 8× and cannot find their place needs a way to know, and a way back.
        // **The reset returns to the fitted view, not to 1:1** — "reset" means "show me the whole
        // picture again", which is where the person started, not the pixel size of the file.
        val fittedNow = Drawing.View.fittedTo(
            picture.width,
            picture.height,
            windowSize.width.toFloat(),
            windowSize.height.toFloat(),
        )
        if (shown != fittedNow) {
            TextButton(
                onClick = { touched = null },
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            ) {
                Text("${(shown.zoom * 100).roundToInt()}% · fit")
            }
        }
      }
    }
}
