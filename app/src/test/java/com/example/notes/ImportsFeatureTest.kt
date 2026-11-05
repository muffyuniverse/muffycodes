package com.example.notes

import android.net.Uri
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Bringing things in from the phone (§3: a file gets here from Downloads).
 *
 * What matters here is not the picker — that is Android's — but what the app does
 * with what the picker hands it. Two rules, and both are the difference between an
 * import and a corruption:
 *
 * 1. **Bytes are copied, never decoded.** The commonest thing to import is a
 *    picture or a PDF; reading one as text on the way in is how it arrives mangled.
 * 2. **A name already taken is numbered, never overwritten.** Importing the same
 *    file twice must add a copy, because a second import is never a way to lose the
 *    first.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class ImportsFeatureTest {

    private val context = RuntimeEnvironment.getApplication()

    /** A content URI that answers with exactly these bytes. */
    private fun uriWith(path: String, bytes: ByteArray): Uri {
        val uri = Uri.parse("content://test/$path")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        return uri
    }

    @Test
    fun `a file arrives byte for byte, not decoded`() {
        // Deliberately not valid UTF-8: PNG magic, a NUL, and a lone continuation
        // byte. Anything that ran this through a text reader would not give it back.
        val bytes = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x00, 0x00, 0xFF.toByte(), 0xC3.toByte(),
        )
        val dir = File(context.cacheDir, "import-${System.nanoTime()}").apply { mkdirs() }
        val uri = uriWith("photo.png", bytes)

        val result = Imports.filesInto(context, listOf(uri), dir)

        assertEquals("one file arrived", 1, result.getOrThrow().copied)
        val arrived = File(dir, "photo.png")
        assertTrue("it is there", arrived.isFile)
        assertArrayEquals("and it is exactly what was sent", bytes, arrived.readBytes())
    }

    @Test
    fun `importing the same name twice adds a copy instead of replacing it`() {
        // Two different documents that happen to be called the same thing — which is
        // what exporting "note.txt" from two places looks like. Distinct URIs, one
        // name: exactly the case the numbering exists for.
        val dir = File(context.cacheDir, "import2-${System.nanoTime()}").apply { mkdirs() }
        val first = uriWith("one/note.txt", "the first".toByteArray())
        val second = uriWith("two/note.txt", "the second".toByteArray())

        Imports.filesInto(context, listOf(first), dir)
        Imports.filesInto(context, listOf(second), dir)

        assertEquals("the first copy is untouched", "the first", File(dir, "note.txt").readText())
        assertEquals(
            "the second was numbered",
            "the second",
            File(dir, "note 2.txt").readText(),
        )
    }

    @Test
    fun `a name that is already taken is numbered rather than overwritten`() {
        val dir = File(context.cacheDir, "names-${System.nanoTime()}").apply { mkdirs() }
        File(dir, "photo.png").writeText("one")
        File(dir, "photo 2.png").writeText("two")

        val third = Imports.freeName(dir, "photo.png")

        assertEquals("photo 3.png", third.name)
        assertFalse("and it is not a name that exists", third.exists())
    }

    @Test
    fun `a name with no extension is numbered without inventing one`() {
        val dir = File(context.cacheDir, "names2-${System.nanoTime()}").apply { mkdirs() }
        File(dir, "journal").writeText("one")

        // A name with no extension is numbered with no invented extension either, and
        // with the one separator every other free name uses.
        assertEquals("journal 2", Imports.freeName(dir, "journal").name)
    }

    @Test
    fun `an import that could write nothing at all is a failure, not a success`() {
        // The honest case a test can build: a target that is not there. The point of
        // the assertion is the *shape* of the answer — a person must never be told
        // "imported" when nothing arrived, because the next thing they do is look
        // for a file that is not there.
        val missing = File(context.cacheDir, "nowhere-${System.nanoTime()}/deeper")
        val uri = uriWith("thing.txt", "x".toByteArray())

        val result = Imports.filesInto(context, listOf(uri), missing)

        assertTrue("it failed", result.isFailure)
        assertFalse("and nothing was made", missing.exists())
    }

    @Test
    fun `a name from the picker arrives exactly as it was called`() {
        // **The names a phone's Downloads really holds are kept.** This used to assert
        // `holo_and_lawrence_kraft_spice_and_wolf.jpeg` — the rewriter lowercased and joined it.
        // Measured against a real repository, GitHub accepts spaces, commas and capitals, so the
        // rename was for a rule that did not exist, and the report that explained it was explaining
        // an act the app should not have taken.
        val dir = File(context.cacheDir, "shape-${System.nanoTime()}").apply { mkdirs() }
        val uri = uriWith("Holo and Lawrence, Kraft - Spice and Wolf.jpeg", "x".toByteArray())

        val result = Imports.filesInto(context, listOf(uri), dir)
        val report = result.getOrThrow()

        val made = dir.listFiles()!!.single().name
        assertEquals("Holo and Lawrence, Kraft - Spice and Wolf.jpeg", made)
        assertEquals("one file", 1, report.copied)
        assertEquals("and nothing was renamed", emptyList<Pair<String, String>>(), report.renamed)
        assertTrue("the message carries the name", report.said().contains(made))
    }

    @Test
    fun `a name of symbols arrives under that name`() {
        // **`!!!.png` is a legal file name everywhere git goes**, so it is kept. The app used to
        // replace it with `imported.png` because its rule demanded letters — and then reported the
        // change, which is the app apologising for an act it should not have taken.
        val dir = File(context.cacheDir, "shape2-${System.nanoTime()}").apply { mkdirs() }
        val uri = uriWith("!!!.png", "x".toByteArray())

        val report = Imports.filesInto(context, listOf(uri), dir).getOrThrow()

        assertEquals("!!!.png", dir.listFiles()!!.single().name)
        assertEquals("and nothing was renamed", emptyList<Pair<String, String>>(), report.renamed)
    }

    @Test
    fun `a name Windows cannot check out is replaced, and the report says so`() {
        // **The one place a name is still changed**, and it is reported: an import has no dialog to
        // refuse into, so a name that cannot be checked out becomes `imported`. The report is what
        // keeps it honest — a file that arrives under a name nobody expected reads as a file that
        // did not arrive.
        val dir = File(context.cacheDir, "shape4-${System.nanoTime()}").apply { mkdirs() }
        val uri = uriWith("a:b.png", "x".toByteArray())

        val report = Imports.filesInto(context, listOf(uri), dir).getOrThrow()

        assertEquals("imported.png", dir.listFiles()!!.single().name)
        assertEquals(
            "and it says what it called it",
            listOf("a:b.png" to "imported.png"),
            report.renamed,
        )
    }

    @Test
    fun `a name that is only a symbol and a number keeps the number`() {
        // **`(1)` is a name, not punctuation to remove.** It is how a phone writes the second copy
        // of a picture, and the number is the only part that says which one. The app used to strip
        // it to `1.png`; a name is now kept unless it cannot be checked out on Windows.
        val dir = File(context.cacheDir, "shape3-${System.nanoTime()}").apply { mkdirs() }
        val uri = uriWith("(1).png", "x".toByteArray())

        Imports.filesInto(context, listOf(uri), dir)

        assertEquals("(1).png", dir.listFiles()!!.single().name)
    }

    @Test
    fun `a name with several dots is numbered before its last extension`() {
        // `archive.tar.gz` is a gzip of a tar: numbering it as `archive 2.tar.gz`
        // would rename the thing rather than the copy, so the number goes before the
        // final extension only.
        //
        // **The name is now kept, so the collision is against the name as it arrives** — the file
        // already there has to be spelled the way the importer spells it, or the two never collide
        // and the numbering is never exercised.
        val dir = File(context.cacheDir, "names3-${System.nanoTime()}").apply { mkdirs() }
        File(dir, "archive.tar.gz").writeText("one")

        assertEquals("archive.tar 2.gz", Imports.freeName(dir, "archive.tar.gz").name)
    }
}
