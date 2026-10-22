package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a note is summarised in the list.
 *
 * The preview is the only part of a note a person sees before opening it, so the
 * cases that matter are the ones where it would say nothing useful: a body that
 * begins with blank lines, and a body long enough to run off the row.
 */
class NotePreviewTest {

    @Test
    fun `the preview is the first line that has anything on it`() {
        // Given a note whose body begins with blank lines and spaces
        val note = Note(id = "n", title = "t", body = "\n   \n\tthe real first line\nsecond")

        // When it is summarised
        val preview = note.preview()

        // Then the blank lines are skipped, and the spaces around it are gone
        assertEquals("the real first line", preview)
    }

    @Test
    fun `a long first line is cut short, with a mark to say so`() {
        // Given a note whose first line is far longer than a row can hold
        val note = Note(id = "n", title = "t", body = "x".repeat(200))

        // When it is summarised
        val preview = note.preview()

        // Then it is cut, and the cut is visible rather than silent, so a reader
        // knows there is more
        assertTrue("a cut preview must end with a mark", preview.endsWith("…"))
        assertTrue("the preview must stay short, was ${preview.length}", preview.length <= 121)
    }

    @Test
    fun `a body with nothing on it has no preview`() {
        // Given a note that is only whitespace
        val note = Note(id = "n", title = "t", body = "   \n\t\n  ")

        // When it is summarised
        assertTrue(
            "a blank body must give an empty preview, not a line of spaces",
            note.preview().isEmpty(),
        )
    }
}
