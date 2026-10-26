package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two limits, and the one thing §2.3 says they must never do.
 *
 * The design's numbers are facts about the pinned endpoints — `cloudflare` allows a 943,718-token
 * reply out of a 1,048,576 window, so its input ceiling is a tenth of `deepinfra/fp8`'s. The
 * ceiling therefore belongs to the **pin**, and the tests here are mostly about the boundary
 * between "the number somebody typed" and "the number the agent is held to".
 */
class ContextBudgetTest {

    // ---- the ceiling belongs to the pin -------------------------------------

    @Test
    fun `the five measured pins have the ceilings the design measured`() {
        assertEquals(917_504L, ContextBudget.ceiling("deepinfra/fp8"))
        assertEquals(917_504L, ContextBudget.ceiling("deepinfra/fp8/flex"))
        assertEquals(786_432L, ContextBudget.ceiling("decart/fp4"))
        assertEquals(655_360L, ContextBudget.ceiling("deepseek"))
        assertEquals(104_858L, ContextBudget.ceiling("cloudflare"))
    }

    @Test
    fun `a measured pin is not second-guessed by live numbers`() {
        // The table is what was measured; the API reports `max_prompt_tokens` as null for every
        // endpoint of this model, so a live number that disagrees is the one to distrust.
        assertEquals(
            104_858L,
            ContextBudget.ceiling("cloudflare", contextLength = 1_000_000, maxOutput = 1_000),
        )
    }

    @Test
    fun `a pin nobody measured falls back through the numbers that are known`() {
        assertEquals(
            "the pin's own window minus the reply it promises",
            800_000L,
            ContextBudget.ceiling("someone/new", contextLength = 1_000_000, maxOutput = 200_000),
        )
        assertEquals(
            "three quarters of a window when no reply size is known",
            750_000L,
            ContextBudget.ceiling("someone/new", contextLength = 1_000_000),
        )
        assertEquals(
            "and a floor when nothing at all is known",
            ContextBudget.UNKNOWN_CEILING,
            ContextBudget.ceiling("someone/new"),
        )
    }

    @Test
    fun `a base slug is not a tag, so it is not in the table`() {
        // `deepseek` is the tag. A bare `deepinfra` routes among that provider's endpoints, so it
        // has no single ceiling — and answering with one would be the app inventing a pin.
        assertEquals(ContextBudget.UNKNOWN_CEILING, ContextBudget.ceiling("deepinfra"))
    }

    @Test
    fun `a pin the table does not name is held to the model's window, not to the floor`() {
        // **The bug this exists for.** The runner used to ask for the ceiling with the pin alone, so
        // every endpoint the table did not name got the 96,000 floor — and a person whose model holds
        // a million tokens watched it trimmed at 96k. The window travels with the choice now, and this
        // is the arithmetic that says the floor is no longer reached when it is known.
        assertEquals(
            "an unmeasured pin with a model window of a million",
            800_000L,
            ContextBudget.ceiling("someone/new", contextLength = 1_000_000, maxOutput = 200_000),
        )
        assertTrue(
            "which is not the floor: the whole point",
            ContextBudget.ceiling("someone/new", contextLength = 1_000_000, maxOutput = 200_000) >
                ContextBudget.UNKNOWN_CEILING,
        )
        assertEquals(
            "and the floor is still what a model nobody knows gets",
            ContextBudget.UNKNOWN_CEILING,
            ContextBudget.ceiling("someone/new", contextLength = null, maxOutput = null),
        )
    }

    // ---- one result's own limit ----------------------------------------------

    @Test
    fun `a result may not be bigger than the input window`() {
        // Characters against tokens, and the file's own four bytes a token. A result bigger than the
        // window cannot be sent at all, so an ask past it is not a bigger allowance.
        val limit = ContextBudget.resultLimit(asked = 40_000, contextLength = 8_000)

        assertEquals("the window, in characters", 32_000L, limit.ceiling)
        assertEquals("so that is what is in force", 32_000L, limit.inForce)
        assertEquals("while the ask is kept as typed", 40_000L, limit.asked)
        assertTrue("and the row can say it was clamped", limit.clamped)
    }

    @Test
    fun `a result limit on a model nobody measured is the number the operator typed`() {
        val limit = ContextBudget.resultLimit(asked = 40_000, contextLength = null)

        assertEquals("nothing to clamp against", 40_000L, limit.inForce)
        assertFalse("so the row says nothing", limit.clamped)
    }

    @Test
    fun `a session log with no number keeps everything`() {
        // Zero is the default and it means the evidence is not deleted: the app used to drop the
        // oldest turns at four megabytes without anybody asking.
        assertEquals("blank keeps it all", 0L, ContextBudget.sessionBytes(0))
        assertEquals("a number is megabytes", 8L * 1024 * 1024, ContextBudget.sessionBytes(8))
    }

    // ---- the clamp must not eat the number ----------------------------------

    @Test
    fun `a big number on a small pin is clamped in force and kept as asked`() {
        // §2.3's trap: a clamp that writes back means looking at a small model once destroys the
        // number you set for a big one.
        val limit = ContextBudget.Limit(asked = 200_000L, ceiling = 104_858L)

        assertEquals("held to the ceiling", 104_858L, limit.inForce)
        assertEquals("and the number you typed is still 200,000", 200_000L, limit.asked)
        assertTrue("which the row says out loud", limit.clamped)
        assertEquals("and the row says which is which", true, limit.said().contains("asked · "))
        assertEquals("naming the ceiling it was clamped against", true, limit.said().contains("allows 105k"))
    }

    @Test
    fun `the same number is in force again when the pin grows`() {
        val asked = 200_000L
        val small = ContextBudget.Limit(asked, ceiling = ContextBudget.ceiling("cloudflare"))
        val big = ContextBudget.Limit(asked, ceiling = ContextBudget.ceiling("deepinfra/fp8"))

        assertEquals("clamped on the small pin", 104_858L, small.inForce)
        assertEquals("untouched on the big one", 200_000L, big.inForce)
        assertEquals("because the number never changed", asked, big.asked)
    }

    @Test
    fun `a number under the ceiling is not called clamped`() {
        val limit = ContextBudget.Limit(asked = 50_000L, ceiling = 917_504L)
        assertEquals(50_000L, limit.inForce)
        assertFalse(limit.clamped)
        assertEquals(
            "one number, and the room it has",
            "50k (this pin allows 918k)",
            limit.said(),
        )
    }

    // ---- the hard limit is the reserved one --------------------------------

    @Test
    fun `a hard limit under the trigger is raised to it`() {
        // v1: "hard limit is reserved option and much higher than trigger". Below the trigger it is
        // not the last resort, it is the normal path — the app would trim before the agent was ever
        // asked, which is the contradiction the section is about.
        val limits = ContextBudget.Limits.of(triggerAsked = 50_000, hardAsked = 10_000, ceiling = 917_504)

        assertEquals("the agent is asked first", 50_000L, limits.triggerAt)
        assertEquals("and the app's trim waits for it", 50_000L, limits.hardAt)
        assertEquals("with what was typed still on record", 10_000L, limits.hard.asked)
        assertTrue("which the row can say it raised", limits.hardAt > limits.hard.inForce)
    }

    @Test
    fun `a hard limit above the endpoint's window is no limit at all`() {
        // "below max context length for selected model": above it the request is refused before
        // anything is trimmed, so the number is clamped by the same ceiling the trigger uses.
        val limits = ContextBudget.Limits.of(
            triggerAsked = 50_000,
            hardAsked = 900_000,
            ceiling = ContextBudget.ceiling("cloudflare"),
        )

        assertEquals("held to what the pin takes", 104_858L, limits.hardAt)
        assertTrue("and the row can say it was clamped", limits.hard.clamped)
        assertEquals("while the typed number survives", 900_000L, limits.hard.asked)
    }

    // ---- what zero means, and it means two different things ------------------

    @Test
    fun `a trigger of zero follows the pin instead of switching the warning off`() {
        val limits = ContextBudget.Limits.of(triggerAsked = 0, hardAsked = 0, ceiling = 400_000)
        assertEquals("a quarter of what the pin allows", 100_000L, limits.triggerAt)
        assertEquals("and the hard limit is off", 0L, limits.hardAt)
    }

    @Test
    fun `a hard limit of zero is never, not a default size`() {
        val limits = ContextBudget.Limits.of(triggerAsked = 10_000, hardAsked = 0, ceiling = 400_000)
        assertEquals("nothing is trimmed", 0L, limits.hardAt)
        assertEquals("but the warning still happens", 10_000L, limits.triggerAt)
    }

    @Test
    fun `zero stays zero when it is not a share`() {
        // The ceiling of nothing is not a ceiling; a trigger of a quarter of zero must not become
        // "always over" or "never warn", it must stay off.
        val limits = ContextBudget.Limits.of(triggerAsked = 0, hardAsked = 0, ceiling = 0)
        assertEquals(0L, limits.triggerAt)
    }

    // ---- the estimate, and which way it errs --------------------------------

    @Test
    fun `the estimate rounds up, because early is the safe direction`() {
        assertEquals("empty", 0L, ContextBudget.tokens(""))
        assertEquals("one token's worth", 1L, ContextBudget.tokens("abcd"))
        assertEquals("and not 1.25 of them", 2L, ContextBudget.tokens("abcde"))
    }

    @Test
    fun `the estimate counts bytes, so a long body costs what it weighs`() {
        assertEquals(1_000L, ContextBudget.tokens("x".repeat(4_000)))
        assertEquals(
            "and two texts add up rather than being measured separately",
            ContextBudget.tokens("abcd") + ContextBudget.tokens("efgh"),
            ContextBudget.tokens("abcd", "efgh"),
        )
    }

    // ---- bytes are the fact, tokens are the estimate -----------------------

    @Test
    fun `bytes are exact, and are what a person is shown`() {
        // The one number this file can be sure of. A token needs the model's vocabulary, which this
        // app may not have for the model it is pointed at — so tokens stay an estimate and bytes
        // are the measurement.
        assertEquals("ascii", 4L, ContextBudget.bytes("abcd"))
        assertEquals("utf-8, not code units: an emoji is four bytes", 4L, ContextBudget.bytes("\uD83D\uDE00"))
        assertEquals("and a CJK character is three", 3L, ContextBudget.bytes("\u4E2D"))
        assertEquals("a label a person reads", "412 B", ContextBudget.sizeLabel(412))
        assertEquals("thousands", "9.3k", ContextBudget.sizeLabel(9_300))
        assertEquals("without a pointless zero", "9k", ContextBudget.sizeLabel(9_000))
        assertEquals("millions", "1.2M", ContextBudget.sizeLabel(1_200_000))
    }

    @Test
    fun `the estimate is built on the same bytes, so the two never disagree`() {
        val text = "a row with some words in it, and a number 42"
        assertEquals(
            "tokens is bytes over four, rounded up",
            (ContextBudget.bytes(text) + 3) / 4,
            ContextBudget.tokens(text),
        )
    }

    @Test
    fun `the estimate under-counts for text that is not Latin, and that is the known bound`() {
        // Honest about the direction: four bytes a token is right for English and code, and wrong
        // for CJK where a character is a token or less. The app may therefore trim late for a
        // Chinese context rather than early — the opposite of the claim the Latin case supports.
        val cjk = "\u4E2D".repeat(100)
        assertEquals("300 bytes", 300L, ContextBudget.bytes(cjk))
        assertEquals("estimated at 75 tokens", 75L, ContextBudget.tokens(cjk))
        assertTrue("while a real tokenizer would count ~100", ContextBudget.tokens(cjk) < 100)
    }

    @Test
    fun `a count reads as a scale, not a quantity`() {
        assertEquals("96k", ContextBudget.short(96_000))
        assertEquals("918k", ContextBudget.short(917_504))
        assertEquals("and a pin's ceiling is the design's own example", "105k", ContextBudget.short(104_858))
        assertEquals("off", ContextBudget.short(0))
        assertEquals("999", ContextBudget.short(999))
    }
}
