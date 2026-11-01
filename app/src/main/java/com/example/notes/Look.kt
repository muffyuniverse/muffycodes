package com.example.notes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

/**
 * Turning a file on disk into something a model can **see**.
 *
 * Three steps, and each one exists because of a measured fact rather than a preference:
 *
 * 1. **Decode** the file, refusing anything that is not a picture.
 * 2. **Fit** it to the size the provider will charge for anyway ([ContextBudget.fitted]) — so the
 *    number a person is shown is the number that will be billed, and the request is a couple of
 *    hundred kilobytes rather than several megabytes.
 * 3. **Encode** it as a `data:` URL, which is the only form the chat contract accepts for an image.
 *
 * **Why this is not in `AgentTools`.** The arithmetic is worth testing without a project, a git
 * repository or a deadline, and the tools class is none of those things' friend. Everything here is
 * a function of a file and a pair of numbers.
 *
 * **`BitmapFactory` is the platform's decoder**, so this is the one part of the app that needs a real
 * device or Robolectric — which is why the file's *name* is checked before it is opened, and why the
 * failure is a sentence rather than an exception.
 */
object Look {

    /**
     * The formats `look` promises, and the reason the list is short.
     *
     * `BitmapFactory` opens more than this — BMP, and HEIC on some devices — but *the model* may not
     * take them, and a promise the app cannot keep is worse than a narrow one. These four are
     * accepted by every vision endpoint this app can reach.
     */
    val EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif")

    /**
     * A size a person can start a drawing at.
     *
     * **The default is the phone screenshot shape**, on the operator's instruction, and it is the right
     * one rather than the cheapest: the thing a person draws on is almost always a screenshot they
     * took, so the canvas should be the shape they already have in their hand. A square would be
     * cheaper — 765 tokens against 1,105 — and would fit nothing they own.
     *
     * [label] is what the dialogue shows; the numbers are what the file becomes.
     */
    data class Canvas(val label: String, val width: Int, val height: Int) {
        /** What a picture this size costs, once the provider's resize has had it. */
        val tokens: Long get() = ContextBudget.pictureTokens(width, height)
    }

    /**
     * The sizes offered when a picture is made.
     *
     * **Four, because a dialogue is not a form.** A5 at 300 dpi or a custom field would be a
     * settings screen inside a drawing screen; these four are the shapes a phone's own pictures
     * take, and the first one is the common case.
     */
    val CANVASES = listOf(
        Canvas("Phone screenshot", 1080, 1920),
        Canvas("Square", 1080, 1080),
        Canvas("Landscape", 1920, 1080),
        Canvas("A4 page", 1240, 1754),
    )

    /** The canvas a new picture starts at, per the operator: the shape already in their hand. */
    val DEFAULT_CANVAS: Canvas get() = CANVASES.first()

    /**
     * A blank white picture of [canvas]'s size, as PNG bytes.
     *
     * **Written immediately, before anything draws on it.** A person who opens a new picture and backs
     * out has an empty picture rather than a lost one, and a file that exists is a file the list can
     * show, the editor can open, and git can record.
     *
     * White rather than transparent: a transparent PNG over a dark theme is a black rectangle, and the
     * person cannot tell whether they are drawing on nothing or the app failed to draw.
     */
    fun blank(canvas: Canvas): ByteArray {
        val bitmap = Bitmap.createBitmap(canvas.width, canvas.height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        val bytes = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        bitmap.recycle()
        return bytes.toByteArray()
    }

    /** What one picture became: the data URL, and the size it was actually sent at. */
    data class Seen(val url: String, val width: Int, val height: Int) {
        /** What it costs, from the size it was sent at. */
        val tokens: Long get() = ContextBudget.pictureTokens(width, height)
    }

    /**
     * Read [file] as a picture, or say why not.
     *
     * **The name is checked before the file is opened.** A `.bmp` would decode perfectly well and be
     * refused by the provider, so catching it here turns a confusing failure at the far end of a
     * request into a sentence the agent can act on — choose a different file, or ask the person for
     * one in a format that works.
     */
    fun read(file: File): Result<Seen> {
        if (!file.isFile) {
            return Result.failure(LookFailed("there is no file at ${file.name}"))
        }
        val extension = file.extension.lowercase()
        if (extension !in EXTENSIONS) {
            val named = if (extension.isEmpty()) "a file with no extension" else "a .$extension"
            return Result.failure(
                LookFailed(
                    "look takes ${EXTENSIONS.sorted().joinToString(", ")}, and ${file.name} is $named",
                ),
            )
        }
        return try {
            // The dimensions first, which costs no bitmap memory — the same two-pass decode the
            // screen already does, for the same reason: a 4000×3000 photo is 48 MB decoded, and
            // knowing the size lets the second pass sample instead of allocate.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0) {
                return Result.failure(
                    LookFailed("${file.name} could not be read as a picture"),
                )
            }

            val (wantWidth, wantHeight) = ContextBudget.fitted(width, height)
            val bitmap = decodeFitted(file, width, height, wantWidth, wantHeight)
                ?: return Result.failure(
                    LookFailed("${file.name} could not be read as a picture"),
                )

            try {
                Result.success(Seen(dataUrl(bitmap), bitmap.width, bitmap.height))
            } finally {
                bitmap.recycle()
            }
        } catch (e: OutOfMemoryError) {
            // Deliberately caught, like the screen's decoder: a picture too big to hold is a picture
            // this app declines to send, not a reason to lose the turn.
            Result.failure(LookFailed("${file.name} is too large to open on this phone"))
        } catch (e: Exception) {
            Result.failure(LookFailed("${file.name} could not be read as a picture"))
        }
    }

    /**
     * Decode [file] at roughly [wantWidth]×[wantHeight].
     *
     * **`inSampleSize` is a power of two and `BitmapFactory` ignores anything else**, so it can only
     * halve repeatedly; the exact size is then reached with a scale. Doing it in this order means a
     * 4000×3000 photo is never fully decoded just to be shrunk — which is the difference between
     * 48 MB and 3 MB of bitmap while the picture is in hand.
     */
    private fun decodeFitted(
        file: File,
        width: Int,
        height: Int,
        wantWidth: Int,
        wantHeight: Int,
    ): Bitmap? {
        var sample = 1
        while (width / (sample * 2) >= wantWidth && height / (sample * 2) >= wantHeight) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(file.path, options) ?: return null
        if (decoded.width == wantWidth && decoded.height == wantHeight) return decoded
        // **The sampled decode is the source of the scale, not the original**, so this is a small
        // bitmap being made slightly smaller rather than a large one being thrown away.
        val scaled = Bitmap.createScaledBitmap(decoded, wantWidth, wantHeight, true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    /**
     * A bitmap as the `data:` URL the chat contract takes for an image.
     *
     * **PNG, always, whatever came in.** A JPEG re-encoded as PNG is larger on the wire, and it is
     * still the right trade: the provider's charge does not depend on the bytes, a screenshot is
     * already a PNG, and one output format is one thing to be wrong rather than four.
     */
    private fun dataUrl(bitmap: Bitmap): String {
        val bytes = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes)
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes.toByteArray())
    }

    /**
     * A refusal with a sentence the model can act on.
     *
     * **An exception rather than a sealed result type**, because the caller's job is to collect
     * whichever of several pictures failed into one sentence — and `Result` already carries the
     * failure and the message together. The alternative was a second result type wrapping the first.
     */
    class LookFailed(val said: String) : Exception(said)
}
