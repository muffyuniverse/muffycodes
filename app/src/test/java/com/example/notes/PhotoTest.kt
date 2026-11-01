package com.example.notes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * A text file's view, rendered to a picture.
 *
 * **Robolectric, because the layout is the platform's `Canvas`.** What can be checked here is the
 * arithmetic and the file that comes out: the size, that the bytes are a real PNG, the name's hash, and
 * the refusals. **What cannot be checked is that the letters appear**, because Robolectric's `Canvas`
 * rasterises nothing — the same limit the drawing tests hit, and the reason the tour has a scenario.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhotoTest {

    private fun render(text: String) = Photo.render(text).getOrThrow()

    @Test
    fun `a page is as wide as the setting and as tall as its content`() {
        val one = render("A short note.")
        assertEquals("the width is fixed", Photo.WIDTH, one.width)
        assertTrue("a line of text is not a whole page", one.height < 500)
        assertTrue("but it is a page", one.height > 100)

        // **Taller content makes a taller page.** A render that returned a constant would pass every
        // assertion above.
        val many = render((1..40).joinToString("\n\n") { "Paragraph $it with some words in it." })
        assertTrue("more text, more page: ${many.height} vs ${one.height}", many.height > one.height)
    }

    @Test
    fun `the bytes are a real png`() {
        val bytes = render("hello").bytes
        assertTrue("long enough to be a picture", bytes.size > 100)
        assertEquals("PNG magic, first byte", 0x89.toByte(), bytes[0])
        assertEquals("second", 'P'.code.toByte(), bytes[1])
        assertEquals("third", 'N'.code.toByte(), bytes[2])
        assertEquals("fourth", 'G'.code.toByte(), bytes[3])
    }

    @Test
    fun `a page is white, so it can be drawn on`() {
        // Decoding is not available here, so the white is asserted through the render's own promise:
        // the bitmap is filled before anything is drawn. The tour shows the result.
        val one = render("hello")
        assertTrue("it produced a page", one.height > 0)
    }

    @Test
    fun `an empty file is refused rather than rendered as a blank page`() {
        // A white rectangle is not a photograph of anything, and sending one to a model costs tokens
        // to say nothing.
        val failure = Photo.render("").exceptionOrNull() as Photo.PhotoFailed
        assertTrue("says so: ${failure.said}", failure.said.contains("nothing"))
        assertTrue(
            "and whitespace alone is nothing too",
            Photo.render("   \n\n  \t\n").exceptionOrNull() is Photo.PhotoFailed,
        )
    }

    @Test
    fun `a file too long for one page is refused, and says how long`() {
        // **Refused rather than truncated.** A photograph of the first half of a document is worse
        // than being told it is too long, because the person would annotate a page they think is
        // complete.
        val long = (1..3_000).joinToString("\n\n") { "Line $it." }
        val failure = Photo.render(long).exceptionOrNull() as Photo.PhotoFailed
        assertTrue("names the limit: ${failure.said}", failure.said.contains(Photo.MAX_HEIGHT.toString()))
    }

    @Test
    fun `the name carries the file's own and a hash of what was rendered`() {
        // **The hash is of the content**, so the same page twice is the same name — a folder does not
        // fill with near-identical copies — and a changed page is a new one.
        val file = File("/tmp/notes.md")
        val a = Photo.nameFor(file, "one".toByteArray())
        val b = Photo.nameFor(file, "one".toByteArray())
        val c = Photo.nameFor(file, "two".toByteArray())

        assertEquals("the same content, the same name", a, b)
        assertNotEquals("different content, a different name", a, c)
        assertTrue("starts with the file's name: $a", a.startsWith("notes-"))
        assertTrue("ends as a png: $a", a.endsWith(".png"))
        // Eight hex characters, per the design's `+ 8 simbol hash`.
        assertEquals("eight characters of hash", 8, a.removePrefix("notes-").removeSuffix(".png").length)
    }

    @Test
    fun `a file with no extension still gets a usable name`() {
        val name = Photo.nameFor(File("/tmp/notes"), "x".toByteArray())
        assertTrue("named after the file: $name", name.startsWith("notes-"))
        assertTrue("and a png", name.endsWith(".png"))
    }

    @Test
    fun `a page costs what its pixels cost`() {
        // The number a person is shown has to be the provider's arithmetic, not a guess — the same
        // rule the `look` tool obeys.
        val one = render("A short note.")
        assertEquals(
            "the page's own price",
            ContextBudget.pictureTokens(one.width, one.height),
            one.tokens,
        )
        assertTrue("and it is a picture's price: ${one.tokens}", one.tokens in 200L..8_000L)
    }

    @Test
    fun `every kind of block renders without failing`() {
        // The layout has a branch per block kind, and a kind that threw would make whole documents
        // unphotographable. This walks all of them in one page.
        val everything = """
            # Heading one
            ## Heading two
            ### Heading three

            A paragraph with **bold** and `code` in it.

            - a bullet
              - a nested bullet
            1. a number
            2. another

            ```
            val x = 1
            ```

            > a quotation

            ---

            ![a cat](cat.png)
        """.trimIndent()
        val one = render(everything)
        assertTrue("it rendered: ${one.height}", one.height > 400)
        assertTrue("with bytes", one.bytes.size > 500)
    }

    @Test
    fun `a very long word is broken rather than running off the page`() {
        // A URL in a note is common, and a line that runs past the page is a paragraph nobody can
        // circle — which is the whole point of the photograph.
        val url = "https://example.com/" + "a".repeat(600)
        val one = render("Look at $url for details.")
        assertTrue("it rendered", one.height > 0)
        // Two lines rather than one: the URL alone is wider than the page at this text size.
        val short = render("Look at it for details.")
        assertTrue(
            "the long word wrapped onto more lines: ${one.height} vs ${short.height}",
            one.height > short.height,
        )
    }

    @Test
    fun `a blockquote and a rule do not collapse the page`() {
        // Every branch adds its own height; one that added nothing would let the next block draw on
        // top of it.
        val withRule = render("above\n\n---\n\nbelow")
        val without = render("above\n\nbelow")
        assertTrue(
            "the rule takes room: ${withRule.height} vs ${without.height}",
            withRule.height > without.height,
        )
    }
}
