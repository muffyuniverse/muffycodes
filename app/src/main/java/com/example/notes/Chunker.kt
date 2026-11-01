package com.example.notes

/**
 * **One splitter, used by the index and by the scanner.**
 *
 * The operator's instruction for the scanner was *"we can chunk the content and remove chunks that have
 * prompt injections"*, and the index needs chunks for the same reason — a vector of a whole file is a
 * vector of nothing in particular. **The two must agree**: a chunk the scanner passed is a chunk the
 * index embeds, and a chunk the scanner cut is a chunk no vector was ever made for. Two splitters would
 * drift on the first edge case and the drift would be invisible — the index would simply hold a vector
 * for text the scanner refused.
 *
 * ## What a chunk is
 *
 * A run of whole lines, up to a character budget, **cut where the text already breaks.** Lines are the
 * unit because they are what a person wrote and what a diff changes; a character count that split a
 * line in half would make every chunk boundary a place a reader has to guess.
 *
 * ## Why overlapping
 *
 * A paragraph that says "the timeout is 90 seconds" and the heading above it that says "retries" are
 * one idea in two chunks if the cut falls between them. The overlap carries the tail of one chunk into
 * the head of the next, so a query about retries can reach the number. It costs a little storage and
 * it is the difference between an index that finds the sentence and one that finds the heading.
 *
 * ## A chunk knows where it is
 *
 * [Chunk.start] and [Chunk.end] are **line numbers, both included, starting at 1** — the same
 * convention `read` and `edit` use, so a search hit can be read with no translation.
 */
object Chunker {

    /**
     * How many characters one chunk holds, at most.
     *
     * **About a paragraph and a half.** An embedding model's cost is per token and its quality drops
     * when a chunk holds several unrelated things, so a chunk should be one idea. Eight hundred
     * characters is roughly 200 tokens — the size the cost table in the design was computed at — and
     * small enough that a file of ten thousand lines does not become one vector.
     */
    const val CHARS: Int = 800

    /**
     * How many characters of the previous chunk are repeated at the start of the next.
     *
     * **A fifth of a chunk**, which is enough to carry a heading into the paragraph that needs it and
     * small enough that the repetition is not itself a cost. The overlap is taken from whole lines, so
     * it never starts mid-word.
     */
    const val OVERLAP: Int = 160

    /** One piece of a file: what it says, and which lines it came from. */
    data class Chunk(
        /** The text, exactly as it appears in the file — no trimming, no joining. */
        val text: String,
        /** First line, 1-based and included. */
        val start: Int,
        /** Last line, 1-based and included. */
        val end: Int,
    ) {
        /**
         * **The content address, and the reason history is affordable.**
         *
         * A version's chunk and the current file's chunk with the same text have the same hash, so
         * the vector is stored **once** and referenced twice. A file saved ten times with one
         * paragraph edited stores ten changed chunks and one copy of everything else — *"that is what
         * makes history affordable: it is not 10× the storage, it is the deltas."*
         */
        val hash: String get() = hashOf(text)
    }

    /**
     * A stable content hash of [text].
     *
     * **SHA-256, in hex, over the UTF-8 bytes.** Not `String.hashCode()`, which is 32 bits and
     * collides: two different paragraphs sharing a vector is a search result that reads text nobody
     * wrote, and it would be found years later. A cryptographic hash makes a collision something a
     * person does not have to think about.
     */
    fun hashOf(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Splits [text] into chunks of whole lines.
     *
     * **An empty file has no chunks**, rather than one empty chunk — a vector of nothing matches
     * every query a little and none of them well, and it would sit in every result list forever.
     *
     * **A single line longer than [CHARS] is its own chunk**, cut at the budget. That is the one place
     * a line is broken, and it is unavoidable: a minified file is one line of a hundred thousand
     * characters, and refusing to chunk it would mean refusing to index it.
     */
    fun split(text: String): List<Chunk> {
        if (text.isBlank()) return emptyList()
        val lines = text.split("\n")
        val out = mutableListOf<Chunk>()
        var at = 0
        while (at < lines.size) {
            // **Where this chunk ends.** Whole lines while they fit, and never zero lines — a line
            // longer than the budget still has to be taken, or the loop would not advance.
            var end = at
            var held = 0
            while (end < lines.size) {
                val next = lines[end].length + 1
                if (end > at && held + next > CHARS) break
                held += next
                end++
                if (held >= CHARS) break
            }
            if (end == at) end = at + 1
            val body = lines.subList(at, end).joinToString("\n")
            if (body.isNotBlank()) {
                out.addAll(cutLong(body, at + 1))
            }
            if (end >= lines.size) break
            // **The overlap, taken in whole lines from the tail of what was just emitted.** The next
            // chunk starts that many lines back, so a heading and the paragraph under it share a
            // chunk rather than being split between two.
            var back = end
            var kept = 0
            while (back > at + 1 && kept < OVERLAP) {
                back--
                kept += lines[back].length + 1
            }
            // **Always forward.** An overlap that reached back to `at` would make no progress and
            // loop forever on a file of very short lines.
            at = if (back > at) back else end
        }
        return out
    }

    /**
     * A chunk whose text is longer than [CHARS], cut at the budget.
     *
     * **The only place a line is broken**, and it exists for one shape: a minified file, a long JSON
     * blob, a base64 payload. Refusing to index those would be refusing to index the files a search
     * is most useful for.
     *
     * Each piece keeps the **same line number**, because they are all one line — a search hit says
     * "line 1" and `read` shows the whole line, which is what a person wants.
     */
    private fun cutLong(body: String, line: Int): List<Chunk> {
        if (body.length <= CHARS) return listOf(Chunk(body, line, line))
        val out = mutableListOf<Chunk>()
        var from = 0
        while (from < body.length) {
            val to = (from + CHARS).coerceAtMost(body.length)
            out.add(Chunk(body.substring(from, to), line, line))
            // **The same overlap**, so a cut inside a long line does not lose the join either.
            val next = (to - OVERLAP).coerceAtLeast(from + 1)
            from = next
        }
        return out
    }
}
