package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The on-disk shape of one file of the app: a small header, then the body.
 *
 * The cases worth checking are the ones where the body could be mistaken for
 * the header, or where a header field could not be written on one line. Those
 * are the shapes that would corrupt a file rather than merely look odd.
 */
class NoteFileTest {

    @Test
    fun `a note survives being written and read back`() {
        // Given a note with every field set
        val note = Note(
            id = "abc123",
            title = "Shopping list",
            body = "Milk\nBread",
            pinned = true,
            updatedAt = 1750000000000L,
        )

        // When it is written and read again
        val decoded = NoteFile.decode(NoteFile.encode(note))!!

        // Then every field comes back as it went in
        assertEquals("abc123", decoded.id)
        assertEquals("Shopping list", decoded.title)
        assertEquals("Milk\nBread", decoded.body)
        assertTrue(decoded.pinned)
        assertEquals(1750000000000L, decoded.updatedAt)
    }

    @Test
    fun `a note with an empty body keeps its title`() {
        // Given a note that has a title and nothing else
        val note = Note(id = "x", title = "Only a title", body = "")

        // When it is written and read again
        val decoded = NoteFile.decode(NoteFile.encode(note))!!

        // Then the title is not mistaken for the body, and the body stays empty
        // rather than becoming the title
        assertEquals("Only a title", decoded.title)
        assertEquals("", decoded.body)
    }

    @Test
    fun `a body that looks like a header is kept exactly as it is`() {
        // Given a body whose text imitates the header, which is the one thing
        // that could make a reader split the file in the wrong place
        val body = "---\nid: not-really\ntitle: not really\n---\nmore text"
        val note = Note(id = "x", title = "Tricky", body = body)

        // When it is written and read again
        val decoded = NoteFile.decode(NoteFile.encode(note))!!

        // Then the body is untouched and the real title is still the title
        assertEquals(body, decoded.body)
        assertEquals("Tricky", decoded.title)
    }

    @Test
    fun `a title with a newline is put on one line`() {
        // Given a title that spans two lines, which a header cannot hold
        val note = Note(id = "x", title = "Line one\nLine two", body = "b")

        // When it is written and read again
        val decoded = NoteFile.decode(NoteFile.encode(note))!!

        // Then the break is flattened rather than breaking the file open
        assertEquals("Line one Line two", decoded.title)
    }

    @Test
    fun `text that is not a file of the app is refused`() {
        // Given text that was written by something else
        val plainText = "just some text"
        val json = "{}"

        // When both are read as files of the app
        // Then both are refused, so a stranger's file is never half-read and
        // then rewritten
        assertNull(NoteFile.decode(plainText))
        assertNull(NoteFile.decode(json))
    }

    @Test
    fun `a file with no id in its header takes the id from its name`() {
        // Given a file whose header lost its id
        val text = "---\ntitle: No id here\n---\nbody"

        // When it is read and its name is passed in
        val decoded = NoteFile.decode(text, fallbackId = "from-file-name")!!

        // Then the name supplies the id, so the note still has an identity
        assertEquals("from-file-name", decoded.id)
    }

    @Test
    fun `the name of a file is its id, and only those names are read`() {
        // Given a note id
        // When the name for it is asked for
        val name = NoteFile.fileNameFor("abc123")

        // Then it is the id with the extension, and that name is recognised
        assertEquals("abc123.md", name)
        assertTrue(NoteFile.isNoteFile(name))

        // And the things that are not files of the app are not mistaken for
        // them: the file that holds a folder's name, and anything hidden
        assertFalse(NoteFile.isNoteFile("name"))
        assertFalse(NoteFile.isNoteFile(".hidden.md"))
    }
}
