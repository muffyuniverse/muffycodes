package com.example.notes

import android.net.Uri
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
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

        assertEquals("one file arrived", 1, result.getOrNull())
        val arrived = File(dir, "photo.png")
        assertTrue("it is there", arrived.isFile)
        assertArrayEquals("and it is exactly what was sent", bytes, arrived.readBytes())
    }

    @Test
    fun `importing the same name twice adds a copy instead of replacing it`() {
        val dir = File(context.cacheDir, "import2-${System.nanoTime()}").apply { mkdirs() }
        val first = uriWith("note.txt", "the first".toByteArray())
        val second = uriWith("note.txt", "the second".toByteArray())

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

        assertEquals("journal 2", Imports.freeName(dir, "journal").name)
    }

    @Test
    fun `a file that cannot be read is reported rather than silently skipped`() {
        // Nothing registered for this URI, so the provider hands back no stream —
        // which is what a deleted document looks like from here.
        val dir = File(context.cacheDir, "import3-${System.nanoTime()}").apply { mkdirs() }
        val missing = Uri.parse("content://test/gone.png")

        val result = Imports.filesInto(context, listOf(missing), dir)

        assertTrue("it failed", result.isFailure)
        assertTrue(
            "and said which file",
            result.exceptionOrNull()?.message?.contains("gone") == true,
        )
    }

    @Test
    fun `a folder that is no longer there is not written into`() {
        val gone = File(context.cacheDir, "gone-${System.nanoTime()}")
        val uri = uriWith("thing.txt", "x".toByteArray())

        val result = Imports.filesInto(context, listOf(uri), gone)

        assertTrue("it failed", result.isFailure)
        assertFalse("and nothing was made", gone.exists())
    }
}
