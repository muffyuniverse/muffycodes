package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The reader that makes `.jsonc` possible, and the one trap it exists for.**
 *
 * Every test here is a way a stripping pass written as a `replace` would fail. The first two are the
 * measured reason this is a scanner: `https://` contains `//`, so a reader that does not track string
 * state deletes the address and leaves a template that parses and points nowhere — a file that looks
 * like it has a typo and does not.
 *
 * The rest are the states a hand-written file reaches: a comment after a value, a block comment across
 * lines, an escaped quote before a slash, a string that ends in a backslash, and a block comment that
 * never closes (the only thing the scanner itself refuses, because it is the only one a parser cannot
 * describe).
 */
class JsoncTest {

    @Test
    fun `a url keeps its slashes, because they are inside a string`() {
        val text = """{ "address": "https://openrouter.ai/api/v1" }"""
        val root = Jsonc.parse(text).getOrThrow()
        assertEquals("https://openrouter.ai/api/v1", root.getString("address"))
    }

    @Test
    fun `a doubly-slashed path inside a string is not a comment either`() {
        // **The nastier form**: `//` that is not part of a scheme. A reader that special-cased
        // `https://` would pass the test above and fail this one.
        val text = """{ "url": "a//b//c" }"""
        assertEquals("a//b//c", Jsonc.parse(text).getOrThrow().getString("url"))
    }

    @Test
    fun `both comment forms are removed, and the document still parses`() {
        val text = """
            // the whole line is a comment
            {
              "address": "https://openrouter.ai/api/v1", // after a value
              /* a block
                 across lines */
              "url": "chat/completions"
            }
        """.trimIndent()
        val root = Jsonc.parse(text).getOrThrow()
        assertEquals("https://openrouter.ai/api/v1", root.getString("address"))
        assertEquals("chat/completions", root.getString("url"))
    }

    @Test
    fun `an escaped quote does not end the string, so a slash after it is still text`() {
        val text = """{ "a": "he said \"//\"", "b": 1 }"""
        val root = Jsonc.parse(text).getOrThrow()
        assertEquals("""he said "//"""", root.getString("a"))
        assertEquals(1, root.getInt("b"))
    }

    @Test
    fun `a string ending in a backslash ends where it should`() {
        // `"a\\"` is a string holding one backslash; the `//` after it starts a comment.
        val text = """{ "a": "a\\" // trailing
        , "b": 2 }"""
        val root = Jsonc.parse(text).getOrThrow()
        assertEquals("""a\""", root.getString("a"))
        assertEquals(2, root.getInt("b"))
    }

    @Test
    fun `an unterminated block comment is refused, naming the line it opened on`() {
        val text = """
            {
              "a": 1,
              /* this never closes
              "b": 2
        """.trimIndent()
        val said = Jsonc.strip(text).exceptionOrNull()?.message.orEmpty()
        assertTrue("it names the line: $said", said.contains("line 3"))
        assertTrue("it names the form: $said", said.contains("never closed"))
    }

    @Test
    fun `a comment becomes whitespace, so a later error still reports its own line`() {
        // **The reason comments are not deleted.** A parse error after a three-line block comment must
        // report line 5, not line 2 — which is what removing the comment would do.
        val text = """
            /* one
               two
               three */
            {
              "a": 1
            }
        """.trimIndent()
        val stripped = Jsonc.strip(text).getOrThrow()
        assertEquals("the length is unchanged", text.length, stripped.length)
        assertEquals(
            "the newlines inside the comment are kept",
            5,
            stripped.count { it == '\n' },
        )
    }

    @Test
    fun `a broken document after a comment is still the parser's to refuse`() {
        val text = """
            { // a comment
              "a": 1,
              "b":
            }
        """.trimIndent()
        val said = Jsonc.parse(text).exceptionOrNull()?.message.orEmpty()
        assertTrue("it says where: $said", said.contains("line "))
        assertTrue("it is a sentence, not an empty string", said.length > 8)
    }

    @Test
    fun `an empty document is refused rather than becoming an empty object`() {
        // `JSONObject("")` throws, and it must keep throwing: a template with no address is not a
        // template, and a reader that answered `{}` would move the failure to the first turn.
        assertTrue(Jsonc.parse("").isFailure)
        assertTrue(Jsonc.parse("// only a comment\n").isFailure)
    }
}
