package com.example.notes

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The expander, tested where it can be wrong.**
 *
 * Brace expansion is a grammar, and a grammar is the kind of code that looks right and is wrong on the
 * fourth case. Every form bash has is here with its expected order, because **order is part of the
 * meaning**: a model that wrote `a{1,2}b{3,4}` expects four paths in a particular sequence, and a
 * different sequence would be a different program that happens to produce the same set.
 */
class ArgsTest {

    private fun expand(text: String): List<String> =
        Braces.expand(text).getOrElse { throw AssertionError("refused: ${it.message}") }

    private fun refused(text: String): String =
        Braces.expand(text).exceptionOrNull()?.message ?: throw AssertionError("`$text` was accepted")

    // ---- the three forms ----------------------------------------------------

    @Test
    fun `a comma list is several paths, in the order they were written`() {
        assertEquals(
            listOf("notes/milk.txt", "notes/bread.txt"),
            expand("notes/{milk,bread}.txt"),
        )
    }

    @Test
    fun `a numeric range counts, and keeps the width it was written with`() {
        // **The width matters**: `{01..03}` is how a person names three files that sort, and
        // `1,2,3` would address three different files.
        assertEquals(listOf("v1", "v2", "v3"), expand("v{1..3}"))
        assertEquals(listOf("v01", "v02", "v03"), expand("v{01..03}"))
        assertEquals(listOf("v3", "v2", "v1"), expand("v{3..1}"))
    }

    @Test
    fun `a range may step`() {
        assertEquals(listOf("v1", "v3", "v5"), expand("v{1..5..2}"))
    }

    @Test
    fun `a letter range counts too`() {
        assertEquals(listOf("a", "b", "c"), expand("{a..c}"))
    }

    @Test
    fun `two groups multiply, leftmost outermost`() {
        // **The order bash produces**, which is what a model writing this expects.
        assertEquals(
            listOf("a1b3", "a1b4", "a2b3", "a2b4"),
            expand("a{1,2}b{3,4}"),
        )
    }

    @Test
    fun `groups nest`() {
        assertEquals(
            listOf("x-a-1", "x-a-2", "x-b-1", "x-b-2"),
            expand("x-{a,b}-{1,2}"),
        )
        assertEquals(
            listOf("x1", "x2", "x3"),
            expand("x{1,{2,3}}"),
        )
    }

    // ---- what is not a group ------------------------------------------------

    @Test
    fun `a group with no comma and no range is literal, because bash says so`() {
        // **`{milk}` is a filename to bash**, not a group of one, and a file genuinely named that
        // must stay reachable. This is the case that a naive implementation gets wrong.
        assertEquals(listOf("{milk}.txt"), expand("{milk}.txt"))
    }

    @Test
    fun `a path with no brace is itself`() {
        assertEquals(listOf("notes/milk.txt"), expand("notes/milk.txt"))
    }

    @Test
    fun `a comma outside braces is not a separator`() {
        assertEquals(listOf("a,b.txt"), expand("a,b.txt"))
    }

    // ---- refusals -----------------------------------------------------------

    @Test
    fun `an unbalanced brace is refused rather than taken literally`() {
        // **A path with a stray brace is far more likely a mistake than a filename**, and taking it
        // literally would make the mistake silent: the command would act on a path nobody meant.
        assertTrue(refused("notes/{milk.txt").contains("does not match"))
        assertTrue(refused("notes/milk}.txt").contains("does not match"))
    }

    @Test
    fun `an expansion larger than the cap is refused, and says how many it wanted`() {
        // **`{1..1000000}` is four characters** and would build a million paths before the first was
        // used. The cap is what makes a brace expression a shorthand rather than a way to run out of
        // memory.
        val said = refused("v{1..100000}")
        assertTrue("names the bound: $said", said.contains("more than"))
    }

    // ---- sources ------------------------------------------------------------

    private fun one(raw: Any?, isPath: Boolean = false): Args.Expanded = Args.expand(
        raw = raw,
        isPath = isPath,
        read = { path -> if (path == "notes.md") "the file's text" else null },
        row = { id -> if (id == 17) "the row's body" else null },
        call = { tool, _ -> Result.success("the $tool result") },
    )

    @Test
    fun `a literal is itself`() {
        assertEquals("milk.txt", (one("milk.txt") as Args.Expanded.One).value)
    }

    @Test
    fun `a row value is that row's body, not its call`() {
        // **The answer, not the question.** A `{"row":7}` that answered the call would be a way to
        // re-run work; what a model wants when it names a row is what came back.
        val value = (one(JSONObject().put("row", 17)) as Args.Expanded.One).value
        assertEquals("the row's body", value)
    }

    @Test
    fun `a row that is gone is refused, never empty`() {
        // **The rule that makes `forget` safe to use.** A deleted row answering "" would read as
        // "replace this with nothing" — a silent act on a write, which is the worst place to be
        // silent.
        val failed = one(JSONObject().put("row", 99)) as Args.Expanded.Failed
        assertTrue("names the row: ${failed.why}", failed.why.contains("99"))
        assertTrue("and says rows are removed: ${failed.why}", failed.why.contains("forget"))
    }

    @Test
    fun `a file value is that file's text`() {
        val value = (one(JSONObject().put("file", "notes.md")) as Args.Expanded.One).value
        assertEquals("the file's text", value)
    }

    @Test
    fun `a missing file is refused`() {
        val failed = one(JSONObject().put("file", "nope.md")) as Args.Expanded.Failed
        assertTrue(failed.why.contains("nope.md"))
    }

    @Test
    fun `a tool value is that call's result`() {
        val value = (one(JSONObject().put("tool", "read").put("args", JSONObject())) as Args.Expanded.One).value
        assertEquals("the read result", value)
    }

    @Test
    fun `a source naming a tool that is not read is refused`() {
        // **A source supplies text; a command that changes something is a call of its own.** Running
        // `delete` as a side effect of assembling an argument is how a call does something the model
        // did not ask for — and it would happen before the command that asked was even reached.
        val failed = one(JSONObject().put("tool", "delete").put("args", JSONObject())) as Args.Expanded.Failed
        assertTrue("names it: ${failed.why}", failed.why.contains("`delete`"))
        assertTrue("and what is allowed: ${failed.why}", failed.why.contains("`read`"))
    }

    @Test
    fun `a source with two keys is refused, because no order is predictable`() {
        val failed = one(JSONObject().put("row", 1).put("file", "x")) as Args.Expanded.Failed
        assertTrue(failed.why.contains("has one key"))
    }

    @Test
    fun `a source naming neither row nor file nor tool is refused, and names what there is`() {
        val failed = one(JSONObject().put("video", "x")) as Args.Expanded.Failed
        // **The refusal names what is allowed**, because `video` was never a source key — it is a
        // key the check did not recognise, and listing the three is the answer that helps.
        assertTrue(failed.why.contains("`row`"))
        assertTrue(failed.why.contains("`tool`"))
    }

    // ---- the two halves together -------------------------------------------

    @Test
    fun `braces apply to a path key and not to a document`() {
        // **The rule that keeps a document a document.** A note containing `{a,b}` is a note about
        // braces; expanding it would rewrite the person's text into two different files.
        assertEquals(
            2,
            (Args.expand("n/{a,b}.md", isPath = true, read = { null }, row = { null }, call = { _, _ -> Result.success("") })
                as Args.Expanded.Many).values.size,
        )
        assertEquals(
            "n/{a,b}.md",
            (Args.expand("n/{a,b}.md", isPath = false, read = { null }, row = { null }, call = { _, _ -> Result.success("") })
                as Args.Expanded.One).value,
        )
    }

    @Test
    fun `a value that is both expands the braces first, then reads each`() {
        // **The order the two halves compose in**, and the reason they are one feature: `{file}` is
        // resolved per path, so two paths give two file contents.
        val resolved = Args.resolve(
            JSONObject().put("path", JSONObject().put("file", "notes.md")),
            pathKeys = setOf("path"),
            read = { "text of $it" },
            row = { null },
            call = { _, _ -> Result.success("") },
        ).getOrThrow()
        assertEquals("text of notes.md", resolved.getString("path"))
    }
}
