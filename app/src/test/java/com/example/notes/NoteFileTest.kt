package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The on-disk format of a single file of the app: the small header followed by
 * the body.
 */
class NoteFileTest {

    @Test
    fun `a file round-trips`() {
        val note = Note(
            id = "abc123",
            title = "Shopping list",
            body = "Milk\nBread",
            pinned = true,
            updatedAt = 1750000000000L,
        )

        val decoded = NoteFile.decode(NoteFile.encode(note))!!

        assertEquals("abc123", decoded.id)
        assertEquals("Shopping list", decoded.title)
        assertEquals("Milk\nBread", decoded.body)
        assertTrue(decoded.pinned)
        assertEquals(1750000000000L, decoded.updatedAt)
    }

    @Test
    fun `an empty body survives the round trip`() {
        val note = Note(id = "x", title = "Only a title", body = "")
        val decoded = NoteFile.decode(NoteFile.encode(note))!!

        assertEquals("Only a title", decoded.title)
        assertEquals("", decoded.body)
    }

    @Test
    fun `a body that looks like a header is kept as it is`() {
        val body = "---\nid: not-really\ntitle: not really\n---\nmore text"
        val note = Note(id = "x", title = "Tricky", body = body)

        val decoded = NoteFile.decode(NoteFile.encode(note))!!

        assertEquals(body, decoded.body)
        assertEquals("Tricky", decoded.title)
    }

    @Test
    fun `a title with a newline does not break the header`() {
        val note = Note(id = "x", title = "Line one\nLine two", body = "b")

        val decoded = NoteFile.decode(NoteFile.encode(note))!!

        assertEquals("Line one Line two", decoded.title)
    }

    @Test
    fun `text that is not a file of the app is rejected`() {
        assertNull(NoteFile.decode("just some text"))
        assertNull(NoteFile.decode("{}"))
    }

    @Test
    fun `the id of the file name is used when the header has none`() {
        val text = "---\ntitle: No id here\n---\nbody"

        val decoded = NoteFile.decode(text, fallbackId = "from-file-name")!!

        assertEquals("from-file-name", decoded.id)
    }

    @Test
    fun `the file name of a note is derived from its id`() {
        assertEquals("abc123.md", NoteFile.fileNameFor("abc123"))
        assertTrue(NoteFile.isNoteFile("abc123.md"))
        assertFalse(NoteFile.isNoteFile("name"))
        assertFalse(NoteFile.isNoteFile(".hidden.md"))
    }
}
