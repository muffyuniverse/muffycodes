package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * **`{{…}}` and the request document** — the two halves of one idea, so one test class.
 *
 * The operator's design (2026-11-09): *"an http command is just a tool http with header and body
 * arguments or even better if as one complete argument, that can be written directly or take a path to
 * a file, and all {{}} paths and secrets inside that argument get expanded, recursively."*
 *
 * The tests are grouped by the sentence they defend:
 *
 * - **a document is read the way a person writes it** — the verb may be left out, a comment above it
 *   is a comment, and a body is not trimmed;
 * - **two things may be written as a name**, and both are expanded where they were written;
 * - **recursion ends**, because a file that names itself would otherwise run until the phone ran out
 *   of memory;
 * - **a secret cannot choose where it goes**, which is the whole reason the vault binds a value to a
 *   host.
 */
class ExpandTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val seen = mutableMapOf<String, String>()

    /** A [Expand.Names] whose answers the test writes, so a substitution can be read on its own. */
    private fun names() = Expand.Names { one -> seen[one.whole] }

    private fun expand(text: String, host: String = "example.com") =
        Expand.of(text, host, names())

    /** The mention [whole] is, so a resolver can be asked one without a document around it. */
    private fun mention(whole: String) = Expand.mentions(whole).single()

    // ---- the document ------------------------------------------------------

    @Test
    fun `an address alone is a GET`() {
        val one = HttpRequest.parse("https://api.example.com/user").getOrThrow()

        assertEquals("GET", one.method)
        assertEquals("https://api.example.com/user", one.url)
        assertTrue("no headers", one.headers.isEmpty())
        assertEquals("no body", null, one.body)
    }

    @Test
    fun `the first line names the method and the address`() {
        val one = HttpRequest.parse("PATCH https://api.example.com/x").getOrThrow()

        assertEquals("PATCH", one.method)
        assertEquals("https://api.example.com/x", one.url)
    }

    @Test
    fun `a lower-case method is the method`() {
        assertEquals("DELETE", HttpRequest.parse("delete https://api.example.com/x").getOrThrow().method)
    }

    @Test
    fun `headers are the lines between the address and the blank one`() {
        val one = HttpRequest.parse(
            "GET https://api.example.com/x\n" +
                "Accept: application/json\n" +
                "X-GitHub-Api-Version: 2022-11-28",
        ).getOrThrow()

        assertEquals("application/json", one.headers["Accept"])
        assertEquals("2022-11-28", one.headers["X-GitHub-Api-Version"])
    }

    @Test
    fun `a comment above the request is not part of it`() {
        val one = HttpRequest.parse(
            "# a note\n// and another\n\nGET https://api.example.com/x",
        ).getOrThrow()

        assertEquals("GET", one.method)
        assertTrue(one.headers.isEmpty())
    }

    @Test
    fun `the body is everything after the blank line, exactly`() {
        val one = HttpRequest.parse("POST https://api.example.com/x\n\n  spaced  \n\n").getOrThrow()

        assertEquals("  spaced  \n\n", String(one.body!!, Charsets.UTF_8))
    }

    @Test
    fun `a colon in a header's value is part of the value`() {
        val one = HttpRequest.parse("GET https://api.example.com/x\nReferer: https://a.example/b").getOrThrow()

        assertEquals("https://a.example/b", one.headers["Referer"])
    }

    @Test
    fun `carriage returns are not headers`() {
        val one = HttpRequest.parse("POST https://api.example.com/x\r\nAccept: text/plain\r\n\r\nbody").getOrThrow()

        assertEquals("text/plain", one.headers["Accept"])
        assertEquals("body", String(one.body!!, Charsets.UTF_8))
    }

    // ---- the refusals a document can produce -------------------------------

    @Test
    fun `an empty document is refused and shows the shape`() {
        val said = HttpRequest.parse("").exceptionOrNull()?.message.orEmpty()

        assertTrue(said, said.contains("no address"))
        assertTrue("it shows the shape: $said", said.contains("GET https://"))
    }

    @Test
    fun `a comment and nothing else is refused, because it has no address`() {
        val said = HttpRequest.parse("# nothing here").exceptionOrNull()?.message.orEmpty()

        assertTrue(said, said.contains("no address"))
    }

    @Test
    fun `an unknown verb is refused with the ones that work`() {
        val said = HttpRequest.parse("TRACE https://api.example.com/x").exceptionOrNull()?.message.orEmpty()

        assertTrue(said, said.contains("PATCH"))
        assertTrue("and names the one it was given: $said", said.contains("TRACE"))
    }

    @Test
    fun `a line that is not a header is refused and quoted`() {
        val said = HttpRequest.parse("POST https://api.example.com/x\nnonsense\n\n{}")
            .exceptionOrNull()?.message.orEmpty()

        assertTrue("it quotes the line: $said", said.contains("nonsense"))
        assertTrue("and says what a header is: $said", said.contains("colon"))
    }

    @Test
    fun `a GET with a body is refused, and says which verb carries one`() {
        val said = HttpRequest.parse("GET https://api.example.com/x\n\nhello")
            .exceptionOrNull()?.message.orEmpty()

        assertTrue(said, said.contains("POST"))
    }

    @Test
    fun `a secret header may not be written by hand`() {
        val said = HttpRequest.parse("GET https://api.example.com/x\nAuthorization: Bearer typed")
            .exceptionOrNull()?.message.orEmpty()

        assertTrue(said, said.contains("{{secret:"))
    }

    @Test
    fun `a secret header may hold a placeholder behind a scheme word`() {
        // **The scheme is not secret.** `Bearer` is in every request ever written, and a header that
        // held nothing but the placeholder would be one an API could not read.
        val one = HttpRequest.parse("GET https://api.example.com/x\nAuthorization: Bearer {{secret:gh}}")
            .getOrThrow()

        assertEquals("Bearer {{secret:gh}}", one.headers["Authorization"])
    }

    @Test
    fun `a secret header with a placeholder and something else is refused`() {
        // The value is `Bearer {{secret:gh}} and also ghp_typed`, which is a typed token with a
        // placeholder beside it — the exact shape a careless edit produces.
        val said = HttpRequest.parse(
            "GET https://api.example.com/x\nAuthorization: Bearer {{secret:gh}} and also ghp_typed",
        ).exceptionOrNull()?.message.orEmpty()

        assertTrue(said, said.contains("{{secret:"))
    }

    // ---- expansion ---------------------------------------------------------

    @Test
    fun `a file name is replaced by what the resolver answers`() {
        seen["{{file:notes/a.md}}"] = "two litres"

        assertEquals("two litres", expand("{{file:notes/a.md}}").getOrThrow())
    }

    @Test
    fun `a name in the middle of a sentence is replaced in place`() {
        seen["{{secret:key}}"] = "abc"

        assertEquals("Bearer abc!", expand("Bearer {{secret:key}}!").getOrThrow())
    }

    @Test
    fun `two names in one text are both replaced`() {
        seen["{{file:a}}"] = "one"
        seen["{{file:b}}"] = "two"

        assertEquals("one and two", expand("{{file:a}} and {{file:b}}").getOrThrow())
    }

    @Test
    fun `text with no names is returned unchanged`() {
        assertEquals("nothing to do", expand("nothing to do").getOrThrow())
    }

    @Test
    fun `a name inside a named value is expanded too`() {
        // **The recursion the operator asked for**: a file cut from a template that names a secret.
        seen["{{file:outer}}"] = "the key is {{secret:inner}}"
        seen["{{secret:inner}}"] = "abc"

        assertEquals("the key is abc", expand("{{file:outer}}").getOrThrow())
    }

    @Test
    fun `a secret's own value is not expanded again`() {
        // **A vault value is data, not a template.** Running it back through the expander would let
        // four characters inside somebody's password be read as a placeholder.
        seen["{{secret:key}}"] = "pa{{file:notes.md}}ss"
        seen["{{file:notes.md}}"] = "LEAKED"

        assertEquals("pa{{file:notes.md}}ss", expand("{{secret:key}}").getOrThrow())
    }

    @Test
    fun `a file that names itself is refused rather than recursing`() {
        seen["{{file:loop}}"] = "again {{file:loop}}"

        val said = expand("{{file:loop}}").exceptionOrNull()?.message.orEmpty()

        assertTrue("it says the document names itself: $said", said.contains("itself"))
    }

    @Test
    fun `a chain longer than the limit is refused`() {
        // Each name is new, so the loop guard never fires; the depth limit is what stops it.
        for (i in 0 until Expand.DEPTH + 2) {
            seen["{{file:f$i}}"] = "{{file:f${i + 1}}}"
        }
        seen["{{file:f${Expand.DEPTH + 2}}}"] = "end"

        val said = expand("{{file:f0}}").exceptionOrNull()?.message.orEmpty()

        assertTrue("it says it expands too many times: $said", said.contains("expands more than"))
    }

    @Test
    fun `a name that answers nothing is refused and named`() {
        val said = expand("{{file:missing.md}}").exceptionOrNull()?.message.orEmpty()

        assertTrue("it names the file: $said", said.contains("missing.md"))
        assertTrue("and what it is for: $said", said.contains("no file"))
    }

    @Test
    fun `a bare name is refused, because it belongs to the request shape`() {
        val said = expand("{{messages}}").exceptionOrNull()?.message.orEmpty()

        assertTrue("it says what to write instead: $said", said.contains("{{file:"))
        assertTrue("and names the one it was given: $said", said.contains("{{messages}}"))
    }

    @Test
    fun `a secret is refused when the request has no host`() {
        seen["{{secret:key}}"] = "abc"

        val said = expand("{{secret:key}}", host = "").exceptionOrNull()?.message.orEmpty()

        assertTrue("it says a secret needs a host: $said", said.contains("host"))
    }

    @Test
    fun `an unclosed brace is left alone, because a document may explain the syntax`() {
        assertEquals("write {{like this", expand("write {{like this").getOrThrow())
    }

    @Test
    fun `a mention with no name in it is refused`() {
        val said = expand("{{file:}}").exceptionOrNull()?.message.orEmpty()

        assertTrue(said, said.contains("no name"))
    }

    // ---- what a document mentions, without expanding anything --------------

    @Test
    fun `every name in a text is listed, left to right`() {
        val found = Expand.namesIn("{{file:a}} then {{secret:b}} and {{file:c}}")

        assertEquals(listOf("a", "b", "c"), found)
    }

    @Test
    fun `a bare name is listed as itself`() {
        assertEquals(listOf("messages"), Expand.namesIn("{{messages}}"))
    }

    // ---- a request, expanded ----------------------------------------------

    @Test
    fun `the address, the headers and the body are all expanded`() {
        seen["{{file:host}}"] = "api.example.com"
        seen["{{file:accept}}"] = "application/json"
        seen["{{file:title}}"] = "Milk"

        val one = HttpRequest.parse(
            "POST https://{{file:host}}/issues\nAccept: {{file:accept}}\n\n{\"title\":\"{{file:title}}\"}",
        ).getOrThrow().expand("api.example.com", names()).getOrThrow()

        assertEquals("https://api.example.com/issues", one.url)
        assertEquals("application/json", one.headers["Accept"])
        assertEquals("""{"title":"Milk"}""", String(one.body!!, Charsets.UTF_8))
    }

    @Test
    fun `a binary body is sent as it is, not run through the text expander`() {
        // A picture being uploaded contains a NUL, and a text expander over it would corrupt it — or
        // refuse the document for containing `{{` by accident.
        val picture = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 0, 0, 0)
        val one = HttpRequest(method = "POST", url = "https://example.com/x", headers = emptyMap(), body = picture)

        val out = one.expand("example.com", names()).getOrThrow()

        assertTrue("the bytes are untouched", picture.contentEquals(out.body))
    }

    @Test
    fun `a document whose address expands to nothing is refused`() {
        val said = HttpRequest.parse("GET https://{{file:missing}}/x").getOrThrow()
            .expand("", names()).exceptionOrNull()?.message.orEmpty()

        assertTrue("it names the file: $said", said.contains("missing"))
    }

    // ---- the resolver over a folder and a vault ---------------------------

    @Test
    fun `a file is read from inside the project`() {
        File(temp.root, "notes.md").writeText("two litres")

        val names = Expand.names(temp.root, "example.com", credentials = null, project = null, branch = null)

        assertEquals("two litres", names.read(mention("{{file:notes.md}}")))
    }

    @Test
    fun `a path that climbs out of the project answers nothing`() {
        File(temp.root.parentFile, "outside.md").writeText("not yours")

        val names = Expand.names(temp.root, "example.com", credentials = null, project = null, branch = null)

        assertEquals(null, names.read(mention("{{file:../outside.md}}")))
    }

    @Test
    fun `a file that is not there answers nothing`() {
        val names = Expand.names(temp.root, "example.com", credentials = null, project = null, branch = null)

        assertEquals(null, names.read(mention("{{file:absent.md}}")))
    }

    @Test
    fun `a secret is answered only for its own host`() {
        val vault = MemoryCredentials().apply {
            activeLogin = "someone"
            setSecret("github", "ghp_one", "api.github.com")
        }
        val names = Expand.names(temp.root, "api.github.com", vault, project = null, branch = null)
        val elsewhere = Expand.names(temp.root, "evil.example", vault, project = null, branch = null)

        assertEquals("ghp_one", names.read(mention("{{secret:github}}")))
        assertEquals("and not for another host", null, elsewhere.read(mention("{{secret:github}}")))
    }
}
