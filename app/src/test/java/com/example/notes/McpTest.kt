package com.example.notes

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **What an MCP article says, and what the app will not let it say.**
 *
 * No socket anywhere in this file. The parser is a pure function of an article's text, and the transport is
 * the one part that is not tested here at all — which is the same split `Fetcher` and `Embedder` have, and
 * the same reason: a feature that could only be checked by reaching a server is a feature checked by hand.
 */
class McpTest {

    private fun article(
        url: String? = "https://mcp.example.com/mcp",
        auth: String? = null,
        token: String? = null,
        extra: String = "",
    ): String = buildString {
        appendLine("# Linear")
        appendLine()
        appendLine("Issues and projects. Read-only is enough for most work.")
        appendLine()
        appendLine("```mcp")
        if (url != null) appendLine("url: $url")
        if (auth != null) appendLine("auth: $auth")
        if (token != null) appendLine("token: $token")
        if (extra.isNotEmpty()) appendLine(extra)
        appendLine("```")
    }

    // ---- the fence -----------------------------------------------------------

    @Test
    fun `the fenced block is what the app reads, and the prose around it is not`() {
        // **The article is for two readers.** A person reads the prose, the app reads the fence, and the
        // fence must not be found in the prose — a line saying `url: ...` in a paragraph is a sentence.
        val text = """
            # Linear

            Connect to `https://mcp.linear.app/mcp` for issues.

            ```mcp
            url: https://mcp.linear.app/mcp
            auth: bearer
            token: linear
            ```
        """.trimIndent()

        val server = McpServer.parse(text, "linear", "system/mcp/linear.md").getOrThrow()

        assertEquals("https://mcp.linear.app/mcp", server.url)
        assertEquals(McpServer.AUTH_BEARER, server.auth)
        assertEquals("linear", server.tokenName)
    }

    @Test
    fun `an article with no fence says so, and names the file`() {
        // **A refusal that names the file and the missing thing** — "that server is not configured" would
        // leave a person hunting through a folder for which article is wrong.
        val answer = McpServer.parse("# Linear\n\nNo fence here.\n", "linear", "system/mcp/linear.md")

        assertTrue(answer.isFailure)
        val said = answer.exceptionOrNull()!!.message!!
        assertTrue(said, said.contains("system/mcp/linear.md"))
        assertTrue(said, said.contains("```mcp"))
    }

    @Test
    fun `a fence with no url says which key is missing`() {
        val answer = McpServer.parse(article(url = null), "linear", "system/mcp/linear.md")

        assertTrue(answer.isFailure)
        assertTrue(answer.exceptionOrNull()!!.message!!.contains("`url:`"))
    }

    @Test
    fun `auth defaults to none, so an article that says nothing about it is public`() {
        // **A server that needs nothing is the ordinary case for a local one**, and requiring
        // `auth: none` would be asking a person to write a line that means "I did what you expect".
        val server = McpServer.parse(article(auth = null), "local", "mcp/local.md").getOrThrow()

        assertEquals(McpServer.AUTH_NONE, server.auth)
        assertNull(server.tokenName)
    }

    @Test
    fun `a quoted url is unquoted, because a URL contains a colon`() {
        // **The colon is why the value is trimmed of quotes.** `url: "https://x/mcp"` is what a person
        // writes when they are thinking about YAML, and the naive `indexOf(':')` split would leave the
        // quotes on — which is a URL that 404s for no visible reason.
        val server = McpServer.parse(
            article(url = "\"https://mcp.example.com/mcp\""),
            "x",
            "mcp/x.md",
        ).getOrThrow()

        assertEquals("https://mcp.example.com/mcp", server.url)
    }

    @Test
    fun `keys the app does not know are kept rather than refused`() {
        // **Forward compatibility in one line.** An article written for a later version of this class
        // should still load, and an unknown key should not make it a broken server.
        val server = McpServer.parse(
            article(extra = "read: true"),
            "linear",
            "mcp/linear.md",
        ).getOrThrow()

        assertEquals("true", server.extra["read"])
        assertEquals("https://mcp.example.com/mcp", server.url)
    }

    @Test
    fun `an unterminated fence still answers, because the article may be mid-edit`() {
        // A person typing into the editor has an unterminated fence for as long as they are typing. The
        // article they saved a second ago should work.
        val text = "# X\n\n```mcp\nurl: https://mcp.example.com/mcp\n"

        val server = McpServer.parse(text, "x", "mcp/x.md").getOrThrow()

        assertEquals("https://mcp.example.com/mcp", server.url)
    }

    @Test
    fun `only the first fence is a server`() {
        // **A second fence is an example, not a second server.** A person pasting two connection examples
        // into one article means the one at the top.
        val text = """
            # X

            ```mcp
            url: https://first.example.com/mcp
            ```

            Or, for a local one:

            ```mcp
            url: http://localhost:3000/mcp
            ```
        """.trimIndent()

        val server = McpServer.parse(text, "x", "mcp/x.md").getOrThrow()

        assertEquals("https://first.example.com/mcp", server.url)
    }

    // ---- the token is never in the article ----------------------------------

    @Test
    fun `the article holds a name and the store holds the token`() {
        // **The one failure mode that cannot be undone.** An article is in a project and a project syncs to
        // GitHub, so a token written into one is a leaked token in a repository. The article names a secret;
        // only the store can turn the name into a token.
        val credentials = MemoryCredentials()
        credentials.setSecret("linear", "lin_api_secret_value", "mcp.example.com")
        val server = McpServer.parse(
            article(auth = McpServer.AUTH_BEARER, token = "linear"),
            "linear",
            "mcp/linear.md",
        ).getOrThrow()

        assertEquals("Bearer lin_api_secret_value", server.authorization(credentials))
        // And the token is nowhere in what the article gave back, which is the assertion that matters:
        // a field holding it would be a field a log line could print.
        assertTrue(
            "the server's own fields hold no token: $server",
            !server.toString().contains("lin_api_secret_value"),
        )
    }

    @Test
    fun `a bearer article with no token name is refused and says what to add`() {
        val server = McpServer.parse(
            article(auth = McpServer.AUTH_BEARER, token = null),
            "linear",
            "mcp/linear.md",
        ).getOrThrow()

        val said = server.missing(MemoryCredentials())!!
        assertTrue(said, said.contains("`auth: bearer`"))
        assertTrue(said, said.contains("token: <name>"))
    }

    @Test
    fun `a token name the phone does not have is refused, and the article is not blamed`() {
        // **The distinction is worth the two sentences.** "The article is wrong" and "the secret is not on
        // this phone" have different fixes, and a refusal that conflates them sends the person to edit a
        // file that is already right.
        val server = McpServer.parse(
            article(auth = McpServer.AUTH_BEARER, token = "linear"),
            "linear",
            "mcp/linear.md",
        ).getOrThrow()

        val said = server.missing(MemoryCredentials())!!
        assertTrue(said, said.contains("`linear`"))
        assertTrue(said, said.contains("not on this phone"))
        assertTrue(said, said.contains("never written into the article"))
    }

    @Test
    fun `oauth is refused by name rather than silently doing nothing`() {
        // **A server asking for a scheme the app does not implement must say so.** The alternative is a
        // request with no `Authorization` header and a 401 that looks like a bad token.
        val server = McpServer.parse(
            article(auth = "oauth", token = null),
            "linear",
            "mcp/linear.md",
        ).getOrThrow()

        val said = server.missing(MemoryCredentials())!!
        assertTrue(said, said.contains("auth: oauth"))
        assertTrue(said, said.contains("OAuth"))
    }

    @Test
    fun `a public server needs nothing from the store`() {
        val server = McpServer.parse(article(auth = null), "local", "mcp/local.md").getOrThrow()

        assertNull(server.missing(MemoryCredentials()))
        assertNull(server.authorization(MemoryCredentials()))
    }

    // ---- what a result looks like -------------------------------------------

    @Test
    fun `a tool call's text parts are joined and its other parts are named`() {
        // **Not dropped in silence.** A result quietly missing an image is a result that lies about its own
        // contents — the same rule as a page whose sections were removed by the scanner.
        val answer = textOf(
            """
            {"content":[
              {"type":"text","text":"the first line"},
              {"type":"image","data":"…","mimeType":"image/png"},
              {"type":"text","text":"the second line"}
            ]}
            """.trimIndent(),
        )

        assertTrue(answer, answer.contains("the first line"))
        assertTrue(answer, answer.contains("the second line"))
        assertTrue(answer, answer.contains("image part, not text"))
    }

    @Test
    fun `a resource part contributes its text`() {
        val answer = textOf(
            """{"content":[{"type":"resource","resource":{"text":"the file's body"}}]}""",
        )

        assertEquals("the file's body", answer)
    }

    @Test
    fun `isError is carried, because a server can answer a failure as a result`() {
        // MCP puts a tool's failure in `result.isError` rather than in a JSON-RPC error, so a client that
        // only reads `error` would report a failed call as a successful one with odd text.
        val failed = answerOf("""{"content":[{"type":"text","text":"no such issue"}],"isError":true}""")

        assertTrue("the server said it failed", failed.failed)
        assertEquals("no such issue", failed.text)
    }

    /** The text a result of this shape comes out as, through the client's own reader. */
    private fun textOf(result: String): String = answerOf(result).text

    /** **The real reader**, the same function the transport calls — not a copy of its rules. */
    private fun answerOf(result: String): Mcp.Answer = Mcp.Answer.read(JSONObject(result))
}
