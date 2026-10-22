package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The single-file format that earlier versions of the app wrote.
 *
 * This is now only ever read, never written, because every file has its own
 * file on disk. It therefore exists for one reason: an upgrade must not lose
 * anything that was already saved. Only the shapes an old file could really
 * have are checked, and every one of them is a way to lose data if it is
 * handled wrongly.
 */
class NoteCodecTest {

    @Test
    fun `a document survives being written and read back`() {
        // Given a tree of folders and notes, including a note with no title
        val folders = listOf(
            Folder("work", "Work", parentId = null),
            Folder("meetings", "Meetings", parentId = "work"),
        )
        val notes = listOf(
            Note("a", "Shopping", "milk\neggs", pinned = true, updatedAt = 1000L, folderId = "work"),
            Note("b", "", "no title", pinned = false, updatedAt = 2000L, folderId = null),
        )

        // When the document is written and read again
        val decoded = NoteCodec.decode(NoteCodec.encode(folders, notes))

        // Then it is the same document, field for field
        assertEquals(folders, decoded.folders)
        assertEquals(notes, decoded.notes)
    }

    @Test
    fun `an empty document reads back as an empty document`() {
        // Given a document with nothing in it

        // When it is written and read again
        val decoded = NoteCodec.decode(NoteCodec.encode(emptyList(), emptyList()))

        // Then it is still empty rather than broken
        assertEquals(emptyList<Folder>(), decoded.folders)
        assertEquals(emptyList<Note>(), decoded.notes)
    }

    @Test
    fun `the oldest format, a bare list of notes, is read`() {
        // Given a file in the first format the app ever wrote: no version, no
        // folders, just an array of notes
        val oldest = """[{"id":"a","title":"Old note","body":"text","pinned":true,"updatedAt":1234}]"""

        // When it is read
        val decoded = NoteCodec.decode(oldest)

        // Then the note is kept whole, and belongs to no folder because the
        // oldest format had none
        assertEquals(1, decoded.notes.size)
        val note = decoded.notes[0]
        assertEquals("Old note", note.title)
        assertEquals("text", note.body)
        assertTrue(note.pinned)
        assertEquals(1234L, note.updatedAt)
        assertNull(note.folderId)
        assertEquals(emptyList<Folder>(), decoded.folders)
    }

    @Test
    fun `an entry without an id is dropped, and the rest are kept`() {
        // Given a file holding two good entries and three broken ones
        val json = """
            {"version":2,
             "folders":[{"id":"f1","name":"Work"},{"name":"no id"}],
             "notes":[{"id":"keep","title":"T","body":"B","pinned":true,"updatedAt":5},
                      {"title":"no id","body":"dropped"},
                      "not an object"]}
        """.trimIndent()

        // When it is read
        val decoded = NoteCodec.decode(json)

        // Then only the entries that can be identified survive, because an entry
        // without an id cannot be told apart from any other and would be
        // rewritten as a duplicate on the next save
        assertEquals(1, decoded.folders.size)
        assertEquals("f1", decoded.folders[0].id)
        assertEquals(1, decoded.notes.size)
        assertEquals("keep", decoded.notes[0].id)
    }

    @Test
    fun `blank text reads as an empty document rather than a failure`() {
        // Given a file that is empty, and one holding only spaces
        val empty = ""
        val blank = "   \n\t "

        // When both are read
        val fromEmpty = NoteCodec.decode(empty)
        val fromBlank = NoteCodec.decode(blank)

        // Then neither throws, and both are simply empty. A crash here would
        // lose every note on the phone for the sake of one blank file.
        assertEquals(emptyList<Note>(), fromEmpty.notes)
        assertEquals(emptyList<Note>(), fromBlank.notes)
        assertEquals(emptyList<Folder>(), fromEmpty.folders)
    }
}
