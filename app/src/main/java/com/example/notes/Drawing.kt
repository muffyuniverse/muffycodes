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
        fun bounded(width: Int, height: Int): View {
            val shownWidth = width * zoom
            val shownHeight = height * zoom
            val marginX = shownWidth * EDGE_MARGIN
            val marginY = shownHeight * EDGE_MARGIN
            // The picture's left edge may sit anywhere from `marginX` to `screen - shownWidth + margin`.
            // Without a screen size here, the bound is expressed against the picture alone: never
            // further than its own width away from home, in either direction.
            val limitX = shownWidth - marginX
            val limitY = shownHeight - marginY
            return copy(
                panX = panX.coerceIn(-limitX, limitX),
                panY = panY.coerceIn(-limitY, limitY),
            )
        }

        companion object {
            /**
             * Zoomed out enough to see a **page** whole.
             *
             * **0.25 was too high, and a photograph of a page proved it.** A 1240-pixel page in a
             * 300-pixel window needs 0.24 to fit; at a 0.25 floor it was 310 pixels wide and
             * overflowed five pixels on each side, so "fit" did not fit and the person could not see
             * the whole page they had just made. 0.1 shows a page at a tenth, which is small but
             * whole — and a thumbnail is still recognisable as a page.
             */
            const val MIN_ZOOM = 0.1f

            /**
             * 8×, which is where a 1080-wide screenshot shows about 135 pixels across the screen —
             * enough to place a mark on one character.
             */
            const val MAX_ZOOM = 8f

            /** How much of the picture may be panned past its own edge. */
            const val EDGE_MARGIN = 0.5f

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
                val fit = minOf(surfaceWidth / pictureWidth, surfaceHeight / pictureHeight)
                val zoom = fit.coerceIn(MIN_ZOOM, 1f)
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
    tool: Drawing.Tool,
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
    var view by remember(picture) { mutableStateOf<Drawing.View?>(null) }
    var surface by remember(picture) { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    LaunchedEffect(surface, picture) {
        if (view == null && surface != androidx.compose.ui.unit.IntSize.Zero) {
            view = Drawing.View.fittedTo(picture.width, picture.height, surface.width.toFloat(), surface.height.toFloat())
        }
    }

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
        val liveView by rememberUpdatedState(view)
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
                        if (startCount == 1) {
                            val at = currentView.toPicture(down.position.x, down.position.y)
                            if (tool == Drawing.Tool.ERASER) {
                                erasing = true
                                Drawing.hit(liveStrokes, at, 8f / currentView.zoom)?.let(onErase)
                            } else {
                                drawing = true
                                current = listOf(at)
                            }
                        }

                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            // **Bounded on every frame, before any branch can `continue` past it**, so
                            // a pan cannot walk the picture off screen and leave a blank canvas with no
                            // gesture that brings it back. It sits above the branches for exactly that
                            // reason: two of them skip to the next frame.
                            currentView = currentView.bounded(picture.width, picture.height)
                            view = currentView
                            if (pressed.size >= 2) {
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
                                view = currentView
                                lastSpan = span
                                lastPan = middle
                                event.changes.forEach { it.consume() }
                                continue
                            }

                            if (!drawing && !erasing && pressed.size == 1) {
                                // A gesture that started as a pan (two fingers) and lost one keeps
                                // panning rather than suddenly drawing.
                                val at = pressed[0].position
                                currentView = currentView.pannedBy(at.x - lastPan.x, at.y - lastPan.y)
                                view = currentView
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
                                current = if (tool == Drawing.Tool.PEN) current + at else
                                    listOf(current.firstOrNull() ?: at, at)
                            }
                        } while (event.changes.any { it.pressed })

                        if (drawing && current.isNotEmpty()) {
                            onAdd(Drawing.Stroke(tool, current, colour, width))
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
            val window = windowSize
            if (window != androidx.compose.ui.unit.IntSize.Zero && window != surface) surface = window
            // **Fitted on the very first frame, not after a `LaunchedEffect` hop.** The effect runs
            // after the composition, so the first frame drew at 1:1 — and in a screenshot tour the
            // capture happens on a frame where the effect has not landed, which is why the picture
            // showed a corner of a page even though `fittedTo` was correct.
            val shown = view ?: Drawing.View.fittedTo(
                picture.width,
                picture.height,
                windowSize.width.toFloat(),
                windowSize.height.toFloat(),
            )
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
                        drawStrokes(listOf(Drawing.Stroke(tool, current, colour, width)), 1f)
                    }
                }
            }
        }
        // **The zoom, said out loud**, because a pinch is invisible once it has happened: a person who
        // has zoomed to 8× and cannot find their place needs a way to know, and a way back.
        // **The reset returns to the fitted view, not to 1:1** — "reset" means "show me the whole
        // picture again", which is where the person started, not the pixel size of the file.
        val shownZoom = view?.zoom ?: 1f
        if (view != null && surface != androidx.compose.ui.unit.IntSize.Zero) {
            val fitted = Drawing.View.fittedTo(picture.width, picture.height, surface.width.toFloat(), surface.height.toFloat())
            if (view != fitted) {
                TextButton(
                    onClick = { view = fitted },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                ) {
                    Text("${(shownZoom * 100).roundToInt()}% · fit")
                }
            }
        }
      }
    }
}
