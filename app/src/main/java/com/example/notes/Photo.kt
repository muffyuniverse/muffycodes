package com.example.notes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/**
 * A text file's **view**, as a picture.
 *
 * The point is the operator's: *"a photo option for a text file to make an image based off its view"* —
 * so a person can circle a paragraph and say *this goes there*, which is the one correction that cannot
 * be said in words.
 *
 * **The view, not the source.** The person is annotating what they *see*: wrapped lines, headings,
 * a list laid out. Rendering the raw source would put hard-wrapped text under their pen, and the circle
 * would sit beside the thing it was meant to circle.
 *
 * **Drawn with the platform's own `Canvas` rather than a Compose snapshot.** A composable can be
 * rendered to a bitmap, but only from inside a composition with the right `GraphicsLayer` API — and
 * this has to work from a button press, be testable without a screen, and produce a file whose size the
 * caller chose. Laying the parsed blocks out on a `Canvas` is a hundred lines and every part of it is
 * arithmetic.
 *
 * **The cost, stated where it will be read:** the result is a *picture of text*, so a model reading it
 * is reading pixels rather than words. That is why a person sending one should send the text file's
 * path too — the drawing says **where**, the file says **what**.
 */
object Photo {

    /**
     * The width a rendered page is given.
     *
     * **1240**, which is A4 at 150 dpi. It is wide enough that a paragraph wraps to a readable number
     * of lines rather than one word per line, and narrow enough that the provider's resize to a 768
     * shortest side does not throw away the letters.
     */
    const val WIDTH = 1240

    /** The margin on all four sides, in pixels. */
    private const val MARGIN = 64

    /** Body text size, in pixels — about 11 pt at this width, which is comfortable to read and to circle. */
    private const val BODY = 34f

    /** The height of one line of body text. */
    private const val LEADING = 48f

    /** The gap between blocks. */
    private const val GAP = 22f

    /**
     * How tall a page may get before it is refused.
     *
     * **A cap rather than a scroll.** A rendered page is one PNG, and a 100,000-line file would be a
     * bitmap larger than any phone can hold. The cap is generous — a few hundred pages — and a file
     * beyond it is refused in words rather than silently truncated, because a photograph of the first
     * half of a document is worse than being told it is too long.
     */
    const val MAX_HEIGHT = 20_000

    /** Why a file could not be photographed, as a sentence for the person. */
    class PhotoFailed(val said: String) : Exception(said)

    /**
     * Renders [text] as a picture of its own view, as PNG bytes.
     *
     * Returns the bytes and the height it settled at, so a caller can say how big the page became.
     */
    fun render(text: String, baseDir: java.io.File? = null): Result<Rendered> {
        val blocks = Markdown.parse(text)
        if (blocks.isEmpty()) {
            return Result.failure(PhotoFailed("There is nothing in this file to photograph."))
        }
        return try {
            // **Laid out twice: once to measure, once to draw.** A `Canvas` cannot be resized after it
            // has drawn, and the height is only known once every line has been wrapped — so the first
            // pass runs the same code with a null canvas and returns the height.
            val height = layout(blocks, baseDir, null)
            if (height > MAX_HEIGHT) {
                return Result.failure(
                    PhotoFailed(
                        "This file is too long to photograph in one page: it would be " +
                            "$height pixels tall and the most is $MAX_HEIGHT.",
                    ),
                )
            }
            val bitmap = Bitmap.createBitmap(WIDTH, height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            layout(blocks, baseDir, canvas)
            val bytes = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
            bitmap.recycle()
            Result.success(Rendered(bytes.toByteArray(), WIDTH, height))
        } catch (e: OutOfMemoryError) {
            Result.failure(PhotoFailed("This file is too large to photograph on this phone."))
        } catch (e: Exception) {
            Result.failure(PhotoFailed("This file could not be photographed: ${e.message ?: "unknown reason"}"))
        }
    }

    /** What a render produced: the bytes, and the page's size. */
    data class Rendered(val bytes: ByteArray, val width: Int, val height: Int) {
        /** What showing this to a model costs, from the page's pixels. */
        val tokens: Long get() = ContextBudget.pictureTokens(width, height)
    }

    /**
     * The name a photograph gets: the file's own name and a short hash of what was rendered.
     *
     * **The hash is of the content**, so the same page photographed twice is the same name — a folder
     * does not fill with near-identical copies — and a page that has changed gets a new one, which is
     * what makes it safe to leave beside the file.
     */
    fun nameFor(textFile: java.io.File, rendered: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(rendered)
        val short = digest.take(4).joinToString("") { "%02x".format(it) }
        val base = textFile.name.substringBeforeLast('.', textFile.name)
        return "$base-$short.png"
    }

    /**
     * Lays [blocks] out from the top, drawing onto [canvas] when it is not null, and returns the height.
     *
     * **One function for measuring and drawing**, so the two cannot disagree — the classic bug here is
     * a measurement that forgets a gap and a picture with its last line clipped.
     */
    private fun layout(blocks: List<Markdown.Block>, baseDir: java.io.File?, canvas: Canvas?): Int {
        var y = MARGIN.toFloat()
        for (block in blocks) {
            y = when (block) {
                is Markdown.Block.Heading -> {
                    val size = when (block.level) {
                        1 -> BODY * 1.7f
                        2 -> BODY * 1.4f
                        else -> BODY * 1.15f
                    }
                    val paint = paint(size, bold = true)
                    val lines = wrap(block.text, paint, WIDTH - MARGIN * 2)
                    val height = lines.size * size * 1.35f
                    canvas?.let { drawLines(it, lines, paint, MARGIN.toFloat(), y, size * 1.35f) }
                    y + height + GAP
                }

                is Markdown.Block.Paragraph -> {
                    val paint = paint(BODY, bold = false)
                    val lines = wrap(block.text, paint, WIDTH - MARGIN * 2)
                    canvas?.let { drawLines(it, lines, paint, MARGIN.toFloat(), y, LEADING) }
                    y + lines.size * LEADING + GAP
                }

                is Markdown.Block.Item -> {
                    val paint = paint(BODY, bold = false)
                    // Indented by depth, and the marker sits in the margin of that indent so the
                    // text of every item lines up whatever its marker is.
                    val indent = MARGIN + block.depth * 40f
                    val markerWidth = 46f
                    val lines = wrap(block.text, paint, (WIDTH - indent - markerWidth - MARGIN).toInt())
                    canvas?.let {
                        it.drawText(block.marker, indent, y + BODY, paint)
                        drawLines(it, lines, paint, indent + markerWidth, y, LEADING)
                    }
                    y + lines.size * LEADING + GAP / 2
                }

                is Markdown.Block.Code -> {
                    val paint = paint(BODY * 0.85f, bold = false, mono = true)
                    val lines = block.text.split('\n').flatMap {
                        wrap(it.ifEmpty { " " }, paint, WIDTH - MARGIN * 2 - 32)
                    }
                    val boxHeight = lines.size * (BODY * 0.85f * 1.5f) + 24f
                    canvas?.let {
                        val shade = Paint().apply { color = Color.rgb(244, 244, 246) }
                        it.drawRect(
                            MARGIN.toFloat(), y, (WIDTH - MARGIN).toFloat(), y + boxHeight, shade,
                        )
                        drawLines(it, lines, paint, MARGIN + 16f, y + 12f, BODY * 0.85f * 1.5f)
                    }
                    y + boxHeight + GAP
                }

                is Markdown.Block.Quote -> {
                    val paint = paint(BODY, bold = false)
                    val lines = wrap(block.text, paint, WIDTH - MARGIN * 2 - 32)
                    canvas?.let {
                        val bar = Paint().apply { color = Color.rgb(200, 200, 205) }
                        it.drawRect(
                            MARGIN.toFloat(), y, MARGIN + 6f, y + lines.size * LEADING, bar,
                        )
                        drawLines(it, lines, paint, MARGIN + 32f, y, LEADING)
                    }
                    y + lines.size * LEADING + GAP
                }

                Markdown.Block.Rule -> {
                    canvas?.let {
                        val line = Paint().apply {
                            color = Color.rgb(210, 210, 215)
                            strokeWidth = 2f
                        }
                        it.drawLine(MARGIN.toFloat(), y, (WIDTH - MARGIN).toFloat(), y, line)
                    }
                    y + GAP * 1.5f
                }

                is Markdown.Block.Image -> {
                    // **A picture is named rather than embedded.** Drawing one would mean decoding and
                    // scaling inside a layout pass, and a missing file would leave a gap whose size
                    // nobody can see. The sentence says what was there, which is what the alt text is
                    // for.
                    val paint = paint(BODY * 0.9f, bold = false)
                    val said = "[\u25a1 ${block.alt.ifBlank { block.source }}]"
                    val lines = wrap(said, paint, WIDTH - MARGIN * 2)
                    canvas?.let { drawLines(it, lines, paint, MARGIN.toFloat(), y, LEADING) }
                    y + lines.size * LEADING + GAP
                }
            }
        }
        return (y + MARGIN).toInt()
    }

    /** A paint for one kind of block. */
    private fun paint(size: Float, bold: Boolean, mono: Boolean = false): Paint = Paint().apply {
        color = Color.rgb(24, 24, 28)
        textSize = size
        isAntiAlias = true
        typeface = when {
            mono -> Typeface.MONOSPACE
            bold -> Typeface.create(Typeface.SERIF, Typeface.BOLD)
            else -> Typeface.SERIF
        }
    }

    /**
     * Wraps [text] to [width] pixels, breaking on spaces and keeping words whole.
     *
     * **A word longer than the line is broken rather than allowed to overflow**, because a URL in a
     * note is common and a line running off the page is a paragraph the person cannot circle.
     */
    private fun wrap(text: String, paint: Paint, width: Int): List<String> {
        if (text.isEmpty()) return listOf("")
        val out = mutableListOf<String>()
        val line = StringBuilder()
        for (word in text.split(' ')) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) <= width) {
                line.setLength(0)
                line.append(candidate)
            } else {
                if (line.isNotEmpty()) out.add(line.toString())
                line.setLength(0)
                // A single word too wide for the line is broken at the character that fits.
                var rest = word
                while (paint.measureText(rest) > width && rest.length > 1) {
                    var cut = rest.length - 1
                    while (cut > 1 && paint.measureText(rest.substring(0, cut)) > width) cut--
                    out.add(rest.substring(0, cut))
                    rest = rest.substring(cut)
                }
                line.append(rest)
            }
        }
        if (line.isNotEmpty()) out.add(line.toString())
        return if (out.isEmpty()) listOf("") else out
    }

    /** Draws [lines] from [top], one per [leading] pixels. */
    private fun drawLines(
        canvas: Canvas,
        lines: List<String>,
        paint: Paint,
        left: Float,
        top: Float,
        leading: Float,
    ) {
        for ((index, line) in lines.withIndex()) {
            canvas.drawText(line, left, top + paint.textSize + index * leading, paint)
        }
    }
}
