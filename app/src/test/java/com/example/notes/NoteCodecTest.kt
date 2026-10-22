package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the persistence codec. These run without a device or emulator.
 */
class NoteCodecTest {

    @Test
    fun `round trip preserves folders and notes`() {
        val folders = listOf(
            Folder("work", "Work", parentId = null),
            Folder("meetings", "Meetings", parentId = "work"),
        )
        val notes = listOf(
            Note("a", "Shopping", "milk\neggs", pinned = true, updatedAt = 1000L, folderId = "work"),
            Note("b", "", "no title", pinned = false, updatedAt = 2000L, folderId = null),
        )

        val decoded = NoteCodec.decode(NoteCodec.encode(folders, notes))

        assertEquals(folders, decoded.folders)
        assertEquals(notes, decoded.notes)
    }

    @Test
    fun `empty document encodes to an empty structure`() {
        val text = NoteCodec.encode(emptyList(), emptyList())

        val decoded = NoteCodec.decode(text)

        assertEquals(emptyList<Folder>(), decoded.folders)
        assertEquals(emptyList<Note>(), decoded.notes)
    }

    @Test
    fun `a version 1 file is migrated into the new format`() {
        // Version 1 was a bare array of notes.
        val legacy = """
            [{"id":"a","title":"Old note","body":"text","pinned":true,"updatedAt":1234}]
        """.trimIndent()

        val decoded = NoteCodec.decode(legacy)

        assertEquals(emptyList<Folder>(), decoded.folders)
        assertEquals(1, decoded.notes.size)
        assertEquals("Old note", decoded.notes[0].title)
        assertTrue(decoded.notes[0].pinned)
        assertNull(decoded.notes[0].folderId)
        assertEquals(1234L, decoded.notes[0].updatedAt)
    }

    @Test
    fun `a migrated file can be written back in the new format`() {
        val legacy = """[{"id":"a","title":"Old","body":"b"}]"""

        val decoded = NoteCodec.decode(legacy)
        val reencoded = NoteCodec.encode(decoded.folders, decoded.notes)
        val again = NoteCodec.decode(reencoded)

        assertEquals(1, again.notes.size)
        assertEquals("Old", again.notes[0].title)
    }

    @Test
    fun `decode skips entries without an id`() {
        val json = """
            {"version":2,
             "folders":[{"id":"f1","name":"Work"},{"name":"no id"}],
             "notes":[{"id":"keep","title":"T","body":"B","pinned":true,"updatedAt":5},
                      {"title":"no id","body":"dropped"},
                      "not an object"]}
        """.trimIndent()

        val decoded = NoteCodec.decode(json)

        assertEquals(1, decoded.folders.size)
        assertEquals("f1", decoded.folders[0].id)
        assertEquals(1, decoded.notes.size)
        assertEquals("keep", decoded.notes[0].id)
    }

    @Test
    fun `decode of blank text yields nothing`() {
        assertEquals(emptyList<Note>(), NoteCodec.decode("").notes)
        assertEquals(emptyList<Note>(), NoteCodec.decode("   ").notes)
        assertEquals(emptyList<Folder>(), NoteCodec.decode("").folders)
    }

    @Test
    fun `a note keeps its folder when encoded and decoded`() {
        val note = Note("n", "t", "b", folderId = "work")

        val decoded = NoteCodec.decode(NoteCodec.encode(emptyList(), listOf(note)))

        assertEquals("work", decoded.notes[0].folderId)
    }

    @Test
    fun `preview uses the first non blank line and truncates it`() {
        val long = "x".repeat(200)
        val note = Note("id", "t", "\n\n  ${long}  \nsecond line")

        val preview = note.preview()

        assertTrue(preview.endsWith("…"))
        assertTrue(preview.length <= 121)
    }

    @Test
    fun `preview is empty for a blank body`() {
        assertEquals("", Note("id", "t", "   \n  \n").preview())
    }
}
