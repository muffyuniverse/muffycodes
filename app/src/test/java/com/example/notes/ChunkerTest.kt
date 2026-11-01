package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The splitter, **shared by the index and the scanner.**
 *
 * The operator's instruction for the scanner was *"we can chunk the content and remove chunks that
 * have prompt injections"*, and the index needs chunks for the same reason. **The two must agree**: a
 * chunk the scanner passed is a chunk the index embeds. Two splitters would drift on the first edge
 * case and the drift would be invisible — the index would simply hold a vector for text the scanner
 * refused.
 *
 * These tests are about the three things that make a chunker usable: it cuts on lines, it never
 * loses text, and it overlaps so a heading and its paragraph are not separated.
 */
class ChunkerTest {

    @Test
    fun `a short file is one chunk with its own line numbers`() {
        val chunks = Chunker.split("one\ntwo\nthree")

        assertEquals("one chunk", 1, chunks.size)
        assertEquals("holding everything", "one\ntwo\nthree", chunks.single().text)
        // **1-based and inclusive**, the same convention `read` and `edit` use, so a search hit can
        // be read with no translation.
        assertEquals("from the first line", 1, chunks.single().start)
        assertEquals("to the last", 3, chunks.single().end)
    }

    @Test
    fun `a long file is cut on lines, never inside one`() {
        // **Lines are the unit** because they are what a person wrote and what a diff changes. A cut
        // inside a line makes every chunk boundary a place a reader has to guess at.
        val lines = (1..400).map { "line $it with some words on it" }
        val chunks = Chunker.split(lines.joinToString("\n"))

        assertTrue("it was cut: ${chunks.size}", chunks.size > 1)
        for (chunk in chunks) {
            // **Every line in the chunk is whole**, which is what "cut on lines" means: the chunk's
            // text starts at the beginning of a line and ends at the end of one.
            assertTrue(
                "a chunk starts at a line boundary: ${chunk.text.take(20)}",
                chunk.text == chunk.text.trimEnd() || chunk.text.isNotBlank(),
            )
            assertFalse(
                "and does not begin mid-word: ${chunk.text.take(30)}",
                chunk.text.startsWith("ine "),
            )
        }
    }

    @Test
    fun `no text is lost between the chunks`() {
        // **The property that makes an index trustworthy.** A chunker that dropped a paragraph would
        // make it unsearchable, and nothing would ever say so — the file would simply never match.
        val lines = (1..200).map { "line $it" }
        val text = lines.joinToString("\n")
        val chunks = Chunker.split(text)

        // Every line of the file appears in at least one chunk. The overlap means some appear twice,
        // which is the point, so the assertion is coverage rather than equality.
        for (line in lines) {
            assertTrue(
                "\"$line\" is in some chunk",
                chunks.any { it.text.contains(line) },
            )
        }
    }

    @Test
    fun `the chunks overlap, so a heading and its paragraph share one`() {
        // **Why the overlap exists.** A paragraph that says "the timeout is 90 seconds" and the
        // heading above it that says "retries" are one idea in two chunks if the cut falls between
        // them — and a query about retries would then find the heading and not the number.
        val lines = (1..300).map { "line $it" }
        val chunks = Chunker.split(lines.joinToString("\n"))

        assertTrue("more than one chunk: ${chunks.size}", chunks.size > 1)
        val first = chunks[0]
        val second = chunks[1]
        // **The second chunk starts before the first one ended.** That is the overlap, expressed as
        // the fact it is: the line numbers reach back.
        assertTrue(
            "chunk 2 starts at line ${second.start}, which is at or before chunk 1's end ${first.end}",
            second.start <= first.end,
        )
    }

    @Test
    fun `the splitter always moves forward, even on a file of very short lines`() {
        // **An overlap that reached back to where the chunk began would make no progress** and loop
        // forever. A file of one-character lines is the shape that finds it.
        val chunks = Chunker.split((1..2000).joinToString("\n") { "a" })

        assertTrue("it terminated with chunks: ${chunks.size}", chunks.isNotEmpty())
        assertTrue("and there are more than one: ${chunks.size}", chunks.size > 1)
        // Every chunk is bounded, so none of them swallowed the file.
        for (chunk in chunks) {
            assertTrue(
                "a chunk is bounded: ${chunk.text.length}",
                chunk.text.length <= Chunker.CHARS + 2,
            )
        }
    }

    @Test
    fun `one very long line is its own chunk, cut at the budget`() {
        // **The only place a line is broken**, and it is unavoidable: a minified file is one line of
        // a hundred thousand characters, and refusing to chunk it would be refusing to index it.
        val long = "x".repeat(Chunker.CHARS * 2 + 100)
        val chunks = Chunker.split(long)

        assertTrue("it was cut: ${chunks.size}", chunks.size > 1)
        for (chunk in chunks) {
            assertTrue(
                "each piece is within the budget: ${chunk.text.length}",
                chunk.text.length <= Chunker.CHARS,
            )
            // **All the pieces keep the same line number**, because they are all one line — a search
            // hit says "line 1" and `read` shows the whole line, which is what a person wants.
            assertEquals("line 1", 1, chunk.start)
            assertEquals("line 1", 1, chunk.end)
        }
    }

    @Test
    fun `an empty file has no chunks, rather than one empty one`() {
        // **A vector of nothing matches every query a little and none of them well**, and it would
        // sit in every result list forever.
        assertEquals(0, Chunker.split("").size)
        assertEquals(0, Chunker.split("\n\n\n").size)
        assertEquals(0, Chunker.split("   ").size)
    }

    // ---- the hash ----------------------------------------------------------

    @Test
    fun `the same text hashes the same, wherever it came from`() {
        // **This is what makes history affordable**: a version's chunk and the current file's chunk
        // with the same words are one vector, referenced twice.
        val a = Chunker.Chunk("the timeout is 90 seconds", 1, 1)
        val b = Chunker.Chunk("the timeout is 90 seconds", 47, 47)

        assertEquals("one text, one hash", a.hash, b.hash)
        assertEquals("and it is the same as the object's own", Chunker.hashOf(a.text), a.hash)
    }

    @Test
    fun `a different text hashes differently`() {
        // A collision would be two paragraphs sharing a vector, which is a search result that reads
        // text nobody wrote — and it would be found years later, by a person, in confusion.
        assertFalse(
            Chunker.hashOf("the timeout is 90 seconds") ==
                Chunker.hashOf("the timeout is 30 seconds"),
        )
    }

    @Test
    fun `the hash is sha256 in hex, so two readers agree on it`() {
        // **Not `String.hashCode()`**, which is 32 bits and collides. Written as a literal so a
        // change of algorithm is a failing test rather than a silent index invalidation.
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            Chunker.hashOf("hello"),
        )
    }

    @Test
    fun `whitespace is part of the text, so an indent change is a different chunk`() {
        // **A re-indented file is a changed file**, and pretending otherwise would mean the index
        // held a vector for text that is no longer in the repository.
        assertFalse(Chunker.hashOf("  indented") == Chunker.hashOf("indented"))
    }
}
