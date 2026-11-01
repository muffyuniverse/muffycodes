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

    /** What one picture became: the data URL, and the size it was actually sent at. */
    data class Seen(val url: String, val width: Int, val height: Int) {
        /** What it costs, from the size it was sent at. */
        val tokens: Long get() = ContextBudget.pictureTokens(width, height)
    }

    /** Why a file could not be looked at, as a sentence for the model. */
    sealed interface Failed {
        val said: String
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

    /** A refusal with a sentence the model can act on. */
    class LookFailed(override val said: String) : Exception(said)
}
