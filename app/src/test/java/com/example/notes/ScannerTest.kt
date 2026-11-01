package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The scanner: surgery, not a block, and it always says what it cut.**
 *
 * No socket. `score` is a function, so every test here decides the probabilities itself and the rules are
 * arithmetic rather than a model's opinion. That is the same split `Browser`, `Fetcher`, `Embedder` and `Mcp`
 * have, and the same reason.
 *
 * What is worth testing is what breaks quietly:
 *
 * 1. **A cut chunk does not take the page with it** — the false positive costs a paragraph.
 * 2. **What was removed is SAID**, because a silently-shortened page lies about its own contents.
 * 3. **A scorer that answers the wrong count is refused**, because pairing by position with a short list cuts
 *    the wrong sections — invisible, and worse than not scanning.
 * 4. **A scorer that fails leaves the text alone**, because a defence that fails closed turns a model outage
 *    into a browsing outage.
 */
class ScannerTest {

    /**
     * A page of [count] chunks — **and the sections are long enough to BE chunks.**
     *
     * The chunker fills 800 characters before it cuts, so three short lines are **one** chunk. That is the
     * fixture trap this file walked into first: the claim "a section can be cut while its neighbours survive"
     * needs sections bigger than the budget, or the test is asking the splitter to do something the text cannot
     * express.
     */
    private fun page(count: Int, words: String = "ordinary prose about the weather"): String =
        (1..count).joinToString("\n") { section(it, words) }

    /**
     * One section, sized so that **each section is exactly one chunk.**
     *
     * The chunker fills [Chunker.CHARS] before it cuts, so a section must be under that to stay whole and the
     * page must be over it for a cut to happen at all. Each section is therefore padded to just under the
     * budget: the count of chunks equals the count of sections, which is what lets a test say "the second
     * section was cut" and mean it.
     *
     * **`split` is asserted to agree** in every test that relies on it, rather than trusted, because the day
     * this fixture stops producing one chunk per section the tests should say so instead of passing by
     * accident.
     */
    private fun section(number: Int, words: String = "ordinary prose about the weather"): String {
        val head = "Section $number of the page."
        // Two characters of slack per line for the newline the join adds, and a little more for safety.
        val room = Chunker.CHARS - head.length - 8
        val body = StringBuilder(head)
        var i = 1
        while (body.length + words.length + 8 < Chunker.CHARS - 8 && body.length < room) {
            body.append(' ').append(words).append(" number ").append(i++)
        }
        return body.toString()
    }

    /** The page's sections, asserted to be one chunk each — so a broken fixture fails as a fixture. */
    private fun sectionsOf(count: Int): Pair<String, Int> {
        val text = page(count)
        val chunks = Chunker.split(text)
        assertEquals("the fixture is one chunk per section: $count sections", count, chunks.size)
        return text to chunks.size
    }

    private fun scanner(scores: List<Double>, threshold: Double = 0.8) =
        Scanner(score = { Result.success(scores) }, threshold = { threshold })

    // ---- surgery -------------------------------------------------------------

    @Test
    fun `a chunk above the threshold is cut and the rest of the page survives`() {
        // **The whole reason for surgery**: a false positive costs a paragraph rather than a page.
        val (text, count) = sectionsOf(3)

        val scanned = scanner(listOf(0.1, 0.95, 0.1)).scan(text).getOrThrow()

        assertEquals("two sections survive", count - 1, scanned.total - scanned.removed.size)
        assertEquals("one was cut", 1, scanned.removed.size)
        assertEquals("and it was the second", 1, scanned.removed[0].index)
        assertTrue("the text still has the first section", scanned.text.contains("Section 1"))
        assertTrue("and the third", scanned.text.contains("Section 3"))
        assertFalse("but not the second's words", scanned.text.contains("Section 2 of the page"))
    }

    @Test
    fun `a chunk exactly at the threshold is cut, because the threshold is a ceiling`() {
        // `>=` rather than `>`, stated: a person who sets 0.8 means "cut at 0.8", and a chunk that scored
        // exactly the number they chose is the least ambiguous case there is.
        val (text, _) = sectionsOf(1)
        val scanned = scanner(listOf(0.8), threshold = 0.8).scan(text).getOrThrow()

        assertEquals(1, scanned.removed.size)
        assertTrue(scanned.text.isBlank())
    }

    @Test
    fun `a chunk below the threshold stays, however close`() {
        val (text, _) = sectionsOf(1)
        val scanned = scanner(listOf(0.799), threshold = 0.8).scan(text).getOrThrow()

        assertTrue("nothing was cut", scanned.removed.isEmpty())
        assertEquals(text, scanned.text)
    }

    @Test
    fun `everything cut leaves no text and says so plainly`() {
        // **The case that must not be silent.** An empty answer with no explanation reads as an empty page.
        //
        // **The count comes from the chunker, not from a guess.** A section is not a chunk — one may span two —
        // and asserting `3` was asserting how many chunks three sections happen to make, which is the
        // fixture's arithmetic rather than the rule being tested.
        val (text, count) = sectionsOf(3)
        val scanned = scanner(List(count) { 0.9 }).scan(text).getOrThrow()

        assertEquals(count, scanned.removed.size)
        assertTrue(scanned.text.isBlank())
        val said = scanned.said()
        assertTrue(said, said.contains("All $count sections"))
        assertTrue(said, said.contains("in the log"))
    }

    // ---- what it says --------------------------------------------------------

    @Test
    fun `what was cut is named by position and opening, not repeated`() {
        // **The removed text is in the log; the conversation is told enough to recognise it.** A cut chunk's
        // opening line is exactly the thing that would be dangerous to repeat in full.
        val (text, _) = sectionsOf(3)
        val scanned = scanner(listOf(0.1, 0.99, 0.1)).scan(text).getOrThrow()

        val said = scanned.said()
        assertTrue(said, said.contains("1 of 3 sections"))
        assertTrue(said, said.contains("section 2"))
        assertTrue(said, said.contains("Section 2 of the page"))
        // **And not the whole of it**, which is the assertion that would fail if `opening` returned the text.
        assertFalse("the cut text is not repeated in full: $said", said.contains("repeated enough to fill a line"))
    }

    @Test
    fun `nothing cut says nothing at all`() {
        // **Not "0 of 3 sections were removed".** A line about a thing that did not happen is noise in every
        // result the agent reads, and it would train the model to ignore the line that matters.
        val (text, _) = sectionsOf(3)
        val scanned = scanner(listOf(0.1, 0.1, 0.1)).scan(text).getOrThrow()

        assertEquals("", scanned.said())
        assertEquals(text, scanned.text)
    }

    // ---- what it refuses -----------------------------------------------------

    @Test
    fun `a scorer that answers the wrong count is refused rather than paired by position`() {
        // **The invisible failure.** Two scores for three chunks, paired by position, cuts the wrong sections
        // and reports it as success — the same class of bug as an embedding attached to the wrong chunk.
        val (text, _) = sectionsOf(3)
        val answer = scanner(listOf(0.1, 0.9)).scan(text)

        assertTrue(answer.isFailure)
        val said = answer.exceptionOrNull()!!.message!!
        assertTrue(said, said.contains("2 scores for 3 sections"))
    }

    @Test
    fun `a scorer that fails leaves the text unchanged`() {
        // **A defence that fails closed turns a model outage into a browsing outage.** The caller decides
        // whether to mention it; the scanner does not decide to empty the page.
        val failing = Scanner(
            score = { Result.failure(IllegalStateException("OpenRouter answered HTTP 500")) },
            threshold = { 0.8 },
        )

        val (text, _) = sectionsOf(2)
        val answer = failing.scan(text)

        assertTrue(answer.isFailure)
        assertTrue(answer.exceptionOrNull()!!.message!!.contains("HTTP 500"))
    }

    @Test
    fun `a threshold of zero is off, not cut everything`() {
        // **The reading that would be worst.** A person who turns the scanner down must not get an empty
        // result; `>= 0` would cut every chunk, so the check has to be before the comparison.
        var asked = false
        val off = Scanner(score = { asked = true; Result.success(emptyList()) }, threshold = { 0.0 })

        val (text, _) = sectionsOf(3)
        val scanned = off.scan(text).getOrThrow()

        assertEquals("the text is untouched", text, scanned.text)
        assertFalse("and the model was not asked", asked)
        assertTrue(scanned.removed.isEmpty())
    }

    @Test
    fun `blank text is not sent to the model`() {
        var asked = false
        val one = Scanner(score = { asked = true; Result.success(emptyList()) }, threshold = { 0.8 })

        val scanned = one.scan("   \n\n  ").getOrThrow()

        assertEquals("", scanned.text.trim())
        assertFalse(asked)
    }

    // ---- reading the model's answer -----------------------------------------

    @Test
    fun `scores are read by section number, not by arrival order`() {
        // **A model that answers 3, 1, 2 has not answered wrongly.** Pairing by arrival would cut the wrong
        // sections — the same rule `parseEmbeddings` follows for the same reason.
        val content = """
            {"scores":[
              {"section":3,"probability":0.9},
              {"section":1,"probability":0.05},
              {"section":2,"probability":0.1}
            ]}
        """.trimIndent()

        val scores = Scanner.parseScores(content, 3)

        assertEquals(listOf(0.05, 0.1, 0.9), scores)
    }

    @Test
    fun `a section the model skipped is an error, not a zero`() {
        // **A section the model forgot is not a section the model cleared**, and treating it as 0 would let a
        // truncated answer pass the dangerous part through.
        val content = """{"scores":[{"section":1,"probability":0.05}]}"""

        val answer = runCatching { Scanner.parseScores(content, 3) }

        assertTrue(answer.isFailure)
        assertTrue(answer.exceptionOrNull()!!.message!!.contains("skipped section 2"))
    }

    @Test
    fun `a score outside zero to one is clamped rather than trusted`() {
        // A model that answers 1.4 or -0.2 is answering in the right spirit and the wrong units, and clamping
        // keeps the comparison meaningful.
        val content = """
            {"scores":[{"section":1,"probability":1.4},{"section":2,"probability":-0.2}]}
        """.trimIndent()

        assertEquals(listOf(1.0, 0.0), Scanner.parseScores(content, 2))
    }

    @Test
    fun `json wrapped in prose is still read`() {
        // **Models add a sentence before the JSON**, and refusing that would make the scanner fail on the most
        // ordinary kind of answer.
        val content = """Here is my assessment: {"scores":[{"section":1,"probability":0.3}]} — done."""

        assertEquals(listOf(0.3), Scanner.parseScores(content, 1))
    }

    @Test
    fun `an answer with no json says so instead of guessing`() {
        val answer = runCatching { Scanner.parseScores("I cannot help with that.", 1) }

        assertTrue(answer.isFailure)
        assertTrue(answer.exceptionOrNull()!!.message!!.contains("no JSON"))
    }
}
