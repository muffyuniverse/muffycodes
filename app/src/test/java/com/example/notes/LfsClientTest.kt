package com.example.notes

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer

/**
 * **The two rules the LFS client exists to obey**, plus the endpoint arithmetic that decides whether
 * it talks to the right place at all.
 *
 * Everything here runs against a local [HttpServer], because the behaviour under test is *what this
 * client sends*, not what GitHub does with it. The private-repository case cannot be tested offline —
 * that is stated in the plan and is why it stays a manual gate.
 */
class LfsClientTest {

    private lateinit var server: HttpServer
    private var port = 0

    /** What the last request looked like, so a header can be asserted rather than inferred. */
    private var lastPath: String = ""
    private var lastAuth: String? = null
    private var lastMethod: String = ""

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        port = server.address.port
        server.createContext("/") { exchange -> handle(exchange) }
        server.start()
    }

    @After
    fun stop() {
        server.stop(0)
    }

    @Test
    fun `the batch call carries the token, and the transfer call does not`() {
        // **The whole reason this class exists.** JGit's client sends no `Authorization` header over
        // HTTPS, so a private repository answers 401 — and, for a download, does so silently. Here
        // the batch call must carry it and the pre-signed transfer must not: its query string is the
        // credential, and adding ours would break the signature.
        val endpoint = "http://127.0.0.1:$port/owner/repo.git/info/lfs"
        respondWith(
            """{"objects":[{"oid":"$OID","size":4,"actions":{"upload":""" +
                """{"href":"http://127.0.0.1:$port/store/object","header":{"X-Signed":"yes"}}}}]}""",
        )

        val batch = LfsClient.batch(endpoint, "secret-token", "upload", listOf(OID to 4L)).getOrThrow()

        assertEquals("the batch path", "/owner/repo.git/info/lfs/objects/batch", lastPath)
        assertEquals("and it carried the token", "Basic " + encoded("x-access-token:secret-token"), lastAuth)
        val action = batch.actions[OID]!!
        assertEquals("the transfer URL came from the answer", "http://127.0.0.1:$port/store/object", action.href)
        assertEquals("with its own header", "yes", action.headers["X-Signed"])
    }

    @Test
    fun `a refused token is named, not passed through as a number`() {
        // **"HTTP 401" is not actionable; "the token was refused" is.** The person is told to sign in
        // again rather than left to guess whether their network is broken.
        val endpoint = "http://127.0.0.1:$port/o/r.git/info/lfs"
        respondWith("""{"message":"Requires authentication"}""", code = 401)

        val failure = LfsClient.batch(endpoint, "stale", "download", listOf(OID to 4L))

        assertTrue("it failed", failure.isFailure)
        val said = failure.exceptionOrNull()!!.message!!
        assertTrue("and it says the token was refused: $said", said.contains("refused the token"))
        assertTrue("with the code, so it can be looked up: $said", said.contains("401"))
    }

    @Test
    fun `one object the server will not give does not hide the others`() {
        // **Four pictures can arrive and one be too large.** A whole-call failure would throw away
        // the four, so a per-object error is reported as one object's outcome and the rest proceed.
        val endpoint = "http://127.0.0.1:$port/o/r.git/info/lfs"
        respondWith(
            """{"objects":[
                 {"oid":"$OID","size":4,"actions":{"download":{"href":"http://127.0.0.1:$port/a"}}},
                 {"oid":"$OTHER","size":4,"error":{"code":404,"message":"Object does not exist"}}
               ]}""",
        )

        val batch = LfsClient.batch(endpoint, "t", "download", listOf(OID to 4L, OTHER to 4L)).getOrThrow()

        assertEquals("the good one is here", listOf(OID), batch.missing)
        assertEquals("the bad one is named", 1, batch.failed.size)
        assertEquals("with its oid", OTHER, batch.failed.single().oid)
        assertTrue("and the server's reason", batch.failed.single().why!!.contains("does not exist"))
    }

    @Test
    fun `a download that arrives short is refused rather than stored`() {
        // **A redirect body is a valid HTTP 200.** Storing it would hand the screen a truncated
        // picture, so the size is checked and a short object leaves nothing behind — not even a
        // partial file the smudge would later treat as present.
        val action = LfsClient.Action("http://127.0.0.1:$port/short", emptyMap())
        val target = File(temporaryFolder(), "ab/cd/$OID")

        val failure = LfsClient.download(action, target, expectedSize = 100)

        assertTrue("it failed", failure.isFailure)
        assertTrue(
            "and says the picture was incomplete: ${failure.exceptionOrNull()!!.message}",
            failure.exceptionOrNull()!!.message!!.contains("incomplete"),
        )
        assertFalse("the object was not written", target.exists())
        assertFalse("and no partial file was left", File(target.parentFile, target.name + ".part").exists())
    }

    @Test
    fun `a download of the right size is stored where the smudge will look`() {
        // The positive half: the bytes land at the target, and the temporary file is gone.
        val action = LfsClient.Action("http://127.0.0.1:$port/ok", emptyMap())
        val target = File(temporaryFolder(), "ab/cd/$OID")

        LfsClient.download(action, target, expectedSize = 4).getOrThrow()

        assertTrue("it is stored", target.isFile)
        assertEquals("with the right bytes", "PNG!", String(target.readBytes()))
        assertFalse("and nothing is left over", File(target.parentFile, target.name + ".part").exists())
    }

    @Test
    fun `the endpoint is the remote with the LFS suffix, and nothing else`() {
        // **Parsed, not spliced.** A remote may carry a port, a missing `.git`, or a trailing slash,
        // and each of those would break a `replace(".git", ...)`.
        assertEquals(
            "https://github.com/owner/repo.git/info/lfs",
            LfsClient.endpointOf("https://github.com/owner/repo.git"),
        )
        assertEquals(
            "a remote without .git still works",
            "https://github.com/owner/repo/info/lfs",
            LfsClient.endpointOf("https://github.com/owner/repo"),
        )
        assertEquals(
            "a trailing slash is not doubled",
            "https://github.com/owner/repo/info/lfs",
            LfsClient.endpointOf("https://github.com/owner/repo/"),
        )
        assertNull("an ssh remote has no HTTP endpoint", LfsClient.endpointOf("git@github.com:owner/repo.git"))
        assertNull("and neither does nonsense", LfsClient.endpointOf("not a url"))
    }

    // ---- the local server -------------------------------------------------------

    private var nextCode = 200
    private var nextBody = ""

    private fun respondWith(body: String, code: Int = 200) {
        nextBody = body
        nextCode = code
    }

    private fun handle(exchange: HttpExchange) {
        lastPath = exchange.requestURI.path
        lastMethod = exchange.requestMethod
        lastAuth = exchange.requestHeaders.getFirst("Authorization")
        // Read the request so the client is not left writing into a closed socket.
        exchange.requestBody.use { it.readBytes() }
        val body = when {
            lastPath.endsWith("/short") -> "PNG"
            lastPath.endsWith("/ok") -> "PNG!"
            else -> nextBody
        }
        val bytes = body.toByteArray()
        val code = if (lastPath.endsWith("/short") || lastPath.endsWith("/ok")) 200 else nextCode
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun encoded(pair: String): String =
        android.util.Base64.encodeToString(pair.toByteArray(), android.util.Base64.NO_WRAP)

    private fun temporaryFolder(): File =
        Files.createTempDirectory("lfsclient").toFile().also { it.deleteOnExit() }

    private companion object {
        /** A 64-character lowercase hex string, which is what an LFS oid is. */
        const val OID = "b3ed289de20e6efad71c81b5309110e9eab76310f7a0863be68619783c64553a"
        const val OTHER = "0000000000000000000000000000000000000000000000000000000000000000"
    }
}
