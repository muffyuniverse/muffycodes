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
        assertEquals("the tag, not the name", "deepinfra/fp8", parsed.active.provider)
        assertEquals("effort", "high", parsed.active.effort)
        assertEquals(
            "and the tags keep their order, which is the person's",
            listOf("deepseek", "deepinfra/fp8", "deepinfra/fp8/flex"),
            parsed.providersOf(ModelConfigFile.DEFAULT_MODEL),
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
        assertEquals("and the same catalogue", written.models, read.models)
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
        val own = ModelConfigFile.default().copy(baseUrl = "http://192.168.1.9:8000/v1")
        ModelConfigFile.write(account, own)
        assertEquals(
            "round trip",
            "http://192.168.1.9:8000/v1",
            ModelConfigFile.load(account).getOrThrow().baseUrl,
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
            ModelConfigFile.load(account).getOrThrow().baseUrl,
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
    fun `a file that still carries a model's window is read, and the number is ignored`() {
        // **The keys are gone from what the app writes**, because nothing clamps to them any more
        // (see `ContextBudget`). A file the previous build wrote still has them, and the honest
        // treatment is to read the rest of it and drop the two: a migration for a number nobody uses
        // would be work spent on a value that has no reader.
        val withWindow = ModelConfigFile.default().let { config ->
            config.copy(active = config.active)
        }
        ModelConfigFile.write(account, withWindow)
        val written = ModelConfigFile.file(account).readText()
        assertFalse("the app writes no window", written.contains("contextLength"))
        assertFalse("nor a reply reserve", written.contains("maxOutput"))

        // And a file that has them — this one was written by hand — still loads.
        ModelConfigFile.file(account).writeText(
            written.replace("\"effort\"", "\"contextLength\": 1048576, \"maxOutput\": 393216, \"effort\""),
        )
        val old = ModelConfigFile.load(account).getOrThrow()

        assertEquals("the model it names", withWindow.active.model, old.active.model)
        assertEquals("and the effort", withWindow.active.effort, old.active.effort)
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
    fun `a provider that is not one of the models is refused`() {
        // The check that keeps the file honest: an active provider that the model does not
        // list would be a pin to an endpoint the person had removed.
        val mistake = """
            { "version": 1,
              "active": { "model": "m", "provider": "deepinfra", "effort": "high" },
              "models": [ { "id": "m", "providers": ["deepinfra/fp8"] } ] }
        """.trimIndent()

        val error = ModelConfigFile.parse(mistake).exceptionOrNull()?.message.orEmpty()

        assertTrue("it names the field: $error", error.contains("active.provider"))
    }

    @Test
    fun `a provider tag that ends in a slash is refused`() {
        // `deepinfra/` looks like a tag and is a prefix: it would match every endpoint of the
        // provider, which is exactly the autorouting the pin exists to prevent.
        val mistake = """
            { "version": 1,
              "active": { "model": "m", "provider": "deepinfra/", "effort": "high" },
              "models": [ { "id": "m", "providers": ["deepinfra/"] } ] }
        """.trimIndent()

        val error = ModelConfigFile.parse(mistake).exceptionOrNull()?.message.orEmpty()

        assertTrue("it explains why: $error", error.contains("not a prefix"))
    }

    @Test
    fun `an active model that is not in the catalogue is refused`() {
        // The catalogue is the allow-list; the choice has to be inside it.
        val mistake = """
            { "version": 1,
              "active": { "model": "gone/model", "provider": "p", "effort": "high" },
              "models": [ { "id": "m", "providers": ["p"] } ] }
        """.trimIndent()

        val error = ModelConfigFile.parse(mistake).exceptionOrNull()?.message.orEmpty()

        assertTrue("it says which: $error", error.contains("is not in models"))
    }

    @Test
    fun `a model with no providers is refused`() {
        val mistake = """
            { "version": 1,
              "active": { "model": "m", "provider": "p", "effort": "high" },
              "models": [ { "id": "m", "providers": [] } ] }
        """.trimIndent()

        val error = ModelConfigFile.parse(mistake).exceptionOrNull()?.message.orEmpty()

        assertTrue("a model is pinned to something: $error", error.contains("providers is empty"))
    }

    @Test
    fun `a version from the future is refused rather than read wrongly`() {
        val future = """{ "version": 9, "active": {}, "models": [] }"""

        val error = ModelConfigFile.parse(future).exceptionOrNull()?.message.orEmpty()

        assertTrue("it says what it can read: $error", error.contains("version 9"))
    }

    @Test
    fun `a repeated provider tag is kept once`() {
        // The forms can produce it (tick, untick, tick), and two identical rows in the picker
        // would read as two endpoints at the same price.
        val doubled = """
            { "version": 1,
              "active": { "model": "m", "provider": "p", "effort": "high" },
              "models": [ { "id": "m", "providers": ["p", "p", "q"] } ] }
        """.trimIndent()

        val parsed = ModelConfigFile.parse(doubled).getOrThrow()

        assertEquals(listOf("p", "q"), parsed.providersOf("m"))
    }

    // ---- the effort ladder ---------------------------------------------------

    @Test
    fun `effort moves down to what the model supports`() {
        // The operator's rule: if the model does not declare the chosen effort, it moves down to
        // one it does, and the operator can raise it again. `max` is absent from this ladder —
        // which is the case the rule is for, since the ladder is the model's, not the app's.
        val ladder = listOf("high", "low", "none")

        assertEquals("max is not offered, so the next one down", "high",
            ModelConfigFile.effortFor(ladder, "max"))
        assertEquals("high is offered", "high", ModelConfigFile.effortFor(ladder, "high"))
        assertEquals("and a rung that is between two of them", "low",
            ModelConfigFile.effortFor(ladder, "medium"))
    }

    @Test
    fun `effort moves up when there is nothing below`() {
        // "Moves down" has no down when the model's ladder starts above what was asked: a model
        // offering only `high` and `max` has to answer `low` with `high`, and refusing would be
        // the app overruling a choice it was handed.
        assertEquals("high", ModelConfigFile.effortFor(listOf("max", "high"), "low"))
        // `medium` is between `high` and `max`, and neither is below it, so it lands on the
        // lowest the model has — the same rule, and the opposite answer to what "down" suggests.
        assertEquals("high", ModelConfigFile.effortFor(listOf("max", "high"), "medium"))
    }

    @Test
    fun `a model that declares no ladder sends none`() {
        // Sending an effort a model never declared is a 400 from one provider and a silently
        // ignored parameter from another, and the second is worse.
        assertNull(ModelConfigFile.effortFor(emptyList(), "high"))
    }

    @Test
    fun `an effort name this app has never seen is still placed below the known ones`() {
        // A model may add a name tomorrow; the app must place it rather than throw. An unknown
        // rung ranks below every known one, so a request for it falls to the lowest effort the
        // model does declare — which is the safe direction, since nothing is sent that the model
        // has not said it understands.
        assertEquals("unknown ranks below `none`", -1, ModelConfigFile.effortRank("some_new_thing"))
        assertTrue(ModelConfigFile.effortRank("some_new_thing") < ModelConfigFile.effortRank("none"))
        assertEquals("low", ModelConfigFile.effortFor(listOf("high", "low"), "some_new_thing"))
    }

    @Test
    fun `none is a rung of the ladder, not an absence`() {
        // `none` is a real rung, not "unset": sliding to the left end of the effort control is
        // "no reasoning at all", which is a materially different agent and has to sort there.
        assertTrue(ModelConfigFile.effortRank("none") < ModelConfigFile.effortRank("low"))
        assertTrue(ModelConfigFile.effortRank("low") < ModelConfigFile.effortRank("high"))
        assertTrue(ModelConfigFile.effortRank("high") < ModelConfigFile.effortRank("max"))
    }

    @Test
    fun `the volume for the agent is named in one place`() {
        // §1.1: it sits in the account's folder beside the config, so a person browsing an
        // account can see what their agent is. Nothing else needs the name, so nothing else
        // spells it.
        assertNotNull(ModelConfigFile.file(account))
        assertEquals("model_conf.json", ModelConfigFile.file(account).name)
    }

    // ---- the two models that are not the chat model -------------------------

    @Test
    fun `an embedding choice keeps its dimension, and a file without one keeps no dimension`() {
        // **The dimension is a request parameter, so it is stored with the choice**: the index built
        // from it has no catalogue to ask. A file that names no dimension is not an error; it is a
        // config from before this app could embed, and the picker says "not chosen" rather than
        // inventing a size.
        val config = ModelConfigFile.default().copy(
            embedding = PickedModel(
                model = "qwen/qwen3-embedding-8b",
                provider = "deepinfra",
                dimensions = 1024,
                providers = listOf("deepinfra", "together"),
            ),
        )
        val read = ModelConfigFile.parse(ModelConfigFile.text(config)).getOrThrow()

        assertEquals("the model", "qwen/qwen3-embedding-8b", read.embedding?.model)
        assertEquals("its provider", "deepinfra", read.embedding?.provider)
        assertEquals("its size", 1024, read.embedding?.dimensions)
        assertEquals(
            "and the endpoints it may use",
            listOf("deepinfra", "together"),
            read.embedding?.providers,
        )
    }

    @Test
    fun `a config with no embedding model says so instead of guessing one`() {
        // **`null` is the honest answer.** A default slug picked on the person's behalf would be a
        // model choice they never made and cannot see — and search would then spend money on a model
        // nobody chose. The row says "not chosen", and the feature is off.
        val read = ModelConfigFile.parse(ModelConfigFile.text(ModelConfigFile.default()))
            .getOrThrow()

        assertNull("no embedding model", read.embedding)
        assertNull("and no rerank model", read.rerank)
    }

    @Test
    fun `a half-written embedding block is refused by name`() {
        // A block that is there and incomplete is a file someone edited wrongly, and the refusal has
        // to say which key — `embedding.provider` is the sentence that lets them fix it.
        val text = """
            {"version":1,
             "active":{"model":"deepseek/deepseek-v4.1-flash-1017","provider":"deepseek","effort":"high"},
             "models":[{"id":"deepseek/deepseek-v4.1-flash-1017","providers":["deepseek"]}],
             "embedding":{"model":"qwen/qwen3-embedding-8b"}}
        """.trimIndent()

        val read = ModelConfigFile.parse(text)

        assertTrue("it is refused", read.isFailure)
        assertTrue(
            "and names the key: ${read.exceptionOrNull()?.message}",
            read.exceptionOrNull()?.message.orEmpty().contains("embedding.provider"),
        )
    }

    @Test
    fun `a dimension that is not a positive number is refused`() {
        // **A typo, not a size.** Zero or negative cannot be a vector length, and silently treating
        // it as "not set" would build an index at whatever the model defaulted to — an index the
        // person believes is 1024 and is not.
        val text = """
            {"version":1,
             "active":{"model":"deepseek/deepseek-v4.1-flash-1017","provider":"deepseek","effort":"high"},
             "models":[{"id":"deepseek/deepseek-v4.1-flash-1017","providers":["deepseek"]}],
             "embedding":{"model":"qwen/qwen3-embedding-8b","provider":"deepinfra","dimensions":0}}
        """.trimIndent()

        val read = ModelConfigFile.parse(text)

        assertTrue("it is refused", read.isFailure)
        assertTrue(
            "and says what is wrong: ${read.exceptionOrNull()?.message}",
            read.exceptionOrNull()?.message.orEmpty().contains("dimensions"),
        )
    }

    @Test
    fun `a rerank choice has no dimension and is not given one`() {
        // **The three jobs are not the same shape**, and a rerank model has no third value. Writing
        // one anyway would put a number in the file that nothing reads and a later reader would try
        // to honour.
        val config = ModelConfigFile.default().copy(
            rerank = PickedModel(
                model = "voyageai/rerank-2.5-lite",
                provider = "voyageai",
                providers = listOf("voyageai"),
            ),
        )
        val read = ModelConfigFile.parse(ModelConfigFile.text(config)).getOrThrow()

        assertEquals("the model", "voyageai/rerank-2.5-lite", read.rerank?.model)
        assertNull("and no dimension", read.rerank?.dimensions)
    }
}
