package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * `model_conf.json`: the rules that make it a file a person can trust.
 *
 * The refusals matter more than the successes here. The file is edited by forms now, but it is
 * still a file on disk that can be damaged, hand-edited, or written by a version that came
 * before this one — and the app's promise is that a config it cannot read is **refused**, not
 * replaced with defaults, because a silent fallback means an agent using a model nobody chose.
 */
class ModelConfigTest {

    private lateinit var account: File

    @Before
    fun makeSpace() {
        account = File(System.getProperty("java.io.tmpdir"), "modelconf-${System.nanoTime()}")
        account.mkdirs()
    }

    /**
     * A file with keys this build does not know: a `provider` word and a list of endpoints.
     *
     * Kept as the fixture for the rule that matters: they are **not read and not refused**. A hand-edited
     * file may carry anything, and a config the app cannot open is worse than one with a word in it that
     * nothing routes by.
     */
    private fun shipped(): String = """
        {
          "version": 1,
          "active": { "model": "deepseek/deepseek-v4.1-flash-1017",
                      "provider": "deepinfra/fp8",
                      "effort": "high" },
          "models": [
            { "id": "deepseek/deepseek-v4.1-flash-1017",
              "providers": ["deepseek", "deepinfra/fp8", "deepinfra/fp8/flex"] }
          ]
        }
    """.trimIndent()

    @Test
    fun `the shipped config reads and is the one the app writes`() {
        val parsed = ModelConfigFile.parse(shipped()).getOrThrow()

        assertEquals("the model", ModelConfigFile.DEFAULT_MODEL, parsed.active.model)
        assertEquals("effort", "high", parsed.active.effort)
        // **The tag list and the provider word are neither read nor refused.** The address is the
        // upstream, and a word nothing routes by is not worth refusing a file over. So the whole parsed
        // model is compared with the three fields that exist: anything the reader had kept would show up
        // as a difference here rather than as a silent extra.
        assertEquals(
            "nothing but the model, the effort and the address survives the read",
            ActiveModel(ModelConfigFile.DEFAULT_MODEL, "high", OPENROUTER_BASE),
            parsed.active,
        )
    }

    @Test
    fun `what the app writes is what it can read`() {
        // A round trip, because a writer and a reader that disagree is the failure that only
        // shows up on somebody else's phone: the file is written by the forms and read by the
        // agent, so both directions are the same file.
        val written = ModelConfigFile.default()
        ModelConfigFile.write(account, written)

        val read = ModelConfigFile.load(account).getOrThrow()

        assertEquals("the same choice", written.active, read.active)
        // **And nothing else**, which is the other half of a round trip: what the writer puts in the
        // file is what the reader takes out, so a field the build no longer has cannot come back
        // through the file.
        assertFalse("the file holds no model blocks", ModelConfigFile.text(read).contains("embedding"))
    }

    /**
     * **The connection's address survives a round trip, and its absence means the shipped one.**
     *
     * The field is what makes the model layer host-agnostic (`PLAN-credentials` C2): a base URL *is*
     * the upstream now, so losing it in a write would silently send a person's requests back to
     * OpenRouter. And a file written before the field existed must keep meaning what it meant — the
     * default — rather than "no address at all", which would be a relative URL.
     */
    @Test
    fun `the connection's address is stored when it is not the shipped one`() {
        val own = ModelConfigFile.default().copy(
            active = ModelConfigFile.default().active.copy(baseUrl = "http://192.168.1.9:8000/v1"),
        )
        ModelConfigFile.write(account, own)
        assertEquals(
            "round trip",
            "http://192.168.1.9:8000/v1",
            ModelConfigFile.load(account).getOrThrow().active.baseUrl,
        )

        // **The shipped one is not written**, so an OpenRouter account's file does not sprout an
        // address it never chose — and the absence keeps meaning "the default".
        ModelConfigFile.write(account, ModelConfigFile.default())
        assertFalse(
            "no address in the file",
            ModelConfigFile.file(account).readText().contains("baseUrl"),
        )
        assertEquals(
            "and reading it answers the default",
            OPENROUTER_BASE,
            ModelConfigFile.load(account).getOrThrow().active.baseUrl,
        )
    }

    /** **An address that is not an address is refused by name**, because it would be a relative URL. */
    @Test
    fun `a base url without a scheme is refused`() {
        val text = ModelConfigFile.text(ModelConfigFile.default())
            .replaceFirst("{", "{\n  \"baseUrl\": \"openrouter.ai/api/v1\",")
        val problem = ModelConfigFile.parse(text).exceptionOrNull()?.message.orEmpty()
        assertTrue("it names the field: $problem", problem.contains("baseUrl"))
        assertTrue("and the shape: $problem", problem.contains("https://"))
    }

    @Test
    fun `an account with no file gets the shipped one`() {
        // A new account has no config, and that is not an error: the app makes one, set up to
        // the model the operator asked for by default.
        val config = ModelConfigFile.load(account).getOrThrow()

        assertEquals(ModelConfigFile.DEFAULT_MODEL, config.active.model)
        assertTrue("and it is on disk now", ModelConfigFile.file(account).isFile)
    }

    @Test
    fun `a file that will not parse is refused with its line`() {
        // Not replaced with the defaults. A fallback would mean the agent quietly running on a
        // model the person did not choose, which is worse than a screen saying which line is
        // wrong — and `org.json` reports a character offset, which a person cannot use.
        val broken = """
            {
              "version": 1,
              "active": { "model": "x", "provider": "y", "effort": "high" },
              "models": [
            }
        """.trimIndent()

        val result = ModelConfigFile.parse(broken)

        val message = result.exceptionOrNull()?.message.orEmpty()
        // The list on the fourth line is closed by the wrong bracket on the fifth, so that is
        // the line to name. Asserting the number rather than just the word is what proves the
        // offset was converted: passing org.json's sentence through unchanged would mention a
        // line in brackets, not as the prefix this app puts on it.
        // A line prefix with the parser's own words after it. The number is allowed to be off by
        // one because it is counted from the offset `org.json` gives up at, and that offset is
        // the parser's choice, not this app's; what must be true is that a line was derived.
        val named = Regex("""^line (\d+): (\S.*)$""").find(message)
        assertTrue("it says which line: $message", named != null)
        val line = named!!.groupValues[1].toInt()
        assertTrue("and it is where the mistake is: $message", line in 4..6)
    }

    @Test
    fun `a version from the future is refused rather than read wrongly`() {
        val future = """{ "version": 9, "active": {}, "models": [] }"""

        val error = ModelConfigFile.parse(future).exceptionOrNull()?.message.orEmpty()

        assertTrue("it says what it can read: $error", error.contains("version 9"))
    }

    @Test
    fun `the volume for the agent is named in one place`() {
        // §1.1: it sits in the account's folder beside the config, so a person browsing an
        // account can see what their agent is. Nothing else needs the name, so nothing else
        // spells it.
        assertNotNull(ModelConfigFile.file(account))
        assertEquals("model_conf.json", ModelConfigFile.file(account).name)
    }

    // ---- the blocks the file no longer has ---------------------------------

    @Test
    fun `an old file with embedding, rerank and baseUrl still loads`() {
        // **The two model blocks are ignored, not refused.** The build before this one wrote them, and
        // refusing a person's own config over a field that no longer means anything would be a file the
        // app that wrote it can no longer open. `baseUrl` is different: it is still the chat model's
        // address when no connection names one, so it is read rather than dropped.
        val old = """
            {
              "version": 1,
              "active": { "model": "deepseek/deepseek-v4.1-flash-1017", "effort": "high" },
              "embedding": { "schema": "embedding.json", "dimensions": 1024 },
              "rerank": { "schema": "rerank.json" },
              "baseUrl": "https://openrouter.ai/api/v1"
            }
        """.trimIndent()

        val read = ModelConfigFile.parse(old).getOrThrow()

        assertEquals("the chat model still loads", "deepseek/deepseek-v4.1-flash-1017", read.active.model)
        assertEquals("and the app writes the file without them", 1, ModelConfigFile.text(read).split("embedding").size - 1)
    }
}
