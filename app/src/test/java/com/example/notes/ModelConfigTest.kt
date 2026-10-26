package com.example.notes

import org.junit.Assert.assertEquals
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
    fun theShippedConfigReadsAndIsTheOneTheAppWrites() {
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
    fun whatTheAppWritesIsWhatItCanRead() {
        // A round trip, because a writer and a reader that disagree is the failure that only
        // shows up on somebody else's phone: the file is written by the forms and read by the
        // agent, so both directions are the same file.
        val written = ModelConfigFile.default()
        ModelConfigFile.write(account, written)

        val read = ModelConfigFile.load(account).getOrThrow()

        assertEquals("the same choice", written.active, read.active)
        assertEquals("and the same catalogue", written.models, read.models)
    }

    @Test
    fun `a model's own window is stored with the choice, and a file without one says nothing`() {
        // **The number two callers need and neither can look up.** The screen's turn and the
        // service's poll both build an agent hours apart, and neither has a catalogue; a window held
        // in memory would be a window the service never has. It travels in the same file as the
        // choice it belongs to.
        val withWindow = ModelConfigFile.default().let { config ->
            config.copy(active = config.active.copy(contextLength = 1_048_576, maxOutput = 393_216))
        }
        ModelConfigFile.write(account, withWindow)
        val read = ModelConfigFile.load(account).getOrThrow()

        assertEquals("the window", 1_048_576, read.active.contextLength)
        assertEquals("and the reply it promises", 393_216, read.active.maxOutput)
        assertEquals("round trip, whole", withWindow.active, read.active)

        // **Absent is null, not zero.** A file written by an older build has no such key, and a
        // window of zero tokens would be a claim the catalogue never made.
        ModelConfigFile.file(account).writeText(shipped())
        val old = ModelConfigFile.load(account).getOrThrow()
        assertNull("nothing is claimed about the window", old.active.contextLength)
        assertNull("nor about the reply", old.active.maxOutput)
    }

    @Test
    fun anAccountWithNoFileGetsTheShippedOne() {
        // A new account has no config, and that is not an error: the app makes one, set up to
        // the model the operator asked for by default.
        val config = ModelConfigFile.load(account).getOrThrow()

        assertEquals(ModelConfigFile.DEFAULT_MODEL, config.active.model)
        assertTrue("and it is on disk now", ModelConfigFile.file(account).isFile)
    }

    @Test
    fun aFileThatWillNotParseIsRefusedWithItsLine() {
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
    fun aProviderThatIsNotOneOfTheModelsIsRefused() {
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
    fun aProviderTagThatEndsInASlashIsRefused() {
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
    fun anActiveModelThatIsNotInTheCatalogueIsRefused() {
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
    fun aModelWithNoProvidersIsRefused() {
        val mistake = """
            { "version": 1,
              "active": { "model": "m", "provider": "p", "effort": "high" },
              "models": [ { "id": "m", "providers": [] } ] }
        """.trimIndent()

        val error = ModelConfigFile.parse(mistake).exceptionOrNull()?.message.orEmpty()

        assertTrue("a model is pinned to something: $error", error.contains("providers is empty"))
    }

    @Test
    fun aVersionFromTheFutureIsRefusedRatherThanReadWrongly() {
        val future = """{ "version": 9, "active": {}, "models": [] }"""

        val error = ModelConfigFile.parse(future).exceptionOrNull()?.message.orEmpty()

        assertTrue("it says what it can read: $error", error.contains("version 9"))
    }

    @Test
    fun aRepeatedProviderTagIsKeptOnce() {
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
    fun effortMovesDownToWhatTheModelSupports() {
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
    fun effortMovesUpWhenThereIsNothingBelow() {
        // "Moves down" has no down when the model's ladder starts above what was asked: a model
        // offering only `high` and `max` has to answer `low` with `high`, and refusing would be
        // the app overruling a choice it was handed.
        assertEquals("high", ModelConfigFile.effortFor(listOf("max", "high"), "low"))
        // `medium` is between `high` and `max`, and neither is below it, so it lands on the
        // lowest the model has — the same rule, and the opposite answer to what "down" suggests.
        assertEquals("high", ModelConfigFile.effortFor(listOf("max", "high"), "medium"))
    }

    @Test
    fun aModelThatDeclaresNoLadderSendsNone() {
        // Sending an effort a model never declared is a 400 from one provider and a silently
        // ignored parameter from another, and the second is worse.
        assertNull(ModelConfigFile.effortFor(emptyList(), "high"))
    }

    @Test
    fun anEffortNameThisAppHasNeverSeenIsStillPlacedBelowTheKnownOnes() {
        // A model may add a name tomorrow; the app must place it rather than throw. An unknown
        // rung ranks below every known one, so a request for it falls to the lowest effort the
        // model does declare — which is the safe direction, since nothing is sent that the model
        // has not said it understands.
        assertEquals("unknown ranks below `none`", -1, ModelConfigFile.effortRank("some_new_thing"))
        assertTrue(ModelConfigFile.effortRank("some_new_thing") < ModelConfigFile.effortRank("none"))
        assertEquals("low", ModelConfigFile.effortFor(listOf("high", "low"), "some_new_thing"))
    }

    @Test
    fun nonesPlaceOnTheLadder() {
        // `none` is a real rung, not "unset": sliding to the left end of the effort control is
        // "no reasoning at all", which is a materially different agent and has to sort there.
        assertTrue(ModelConfigFile.effortRank("none") < ModelConfigFile.effortRank("low"))
        assertTrue(ModelConfigFile.effortRank("low") < ModelConfigFile.effortRank("high"))
        assertTrue(ModelConfigFile.effortRank("high") < ModelConfigFile.effortRank("max"))
    }

    @Test
    fun theVolumeForTheAgentIsNamedInOnePlace() {
        // §1.1: it sits in the account's folder beside the config, so a person browsing an
        // account can see what their agent is. Nothing else needs the name, so nothing else
        // spells it.
        assertNotNull(ModelConfigFile.file(account))
        assertEquals("model_conf.json", ModelConfigFile.file(account).name)
    }
}
