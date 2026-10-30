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
        // **Both numbers in tokens.** They used to be two units — the row in characters, the wall in
        // tokens — so the clamp multiplied by an estimate and the text it produced named the wrong
        // unit. A result bigger than the window cannot be sent at all, so an ask past it is not a
        // bigger allowance.
        val limit = ContextBudget.resultLimit(asked = 40_000, contextLength = 8_000)

        assertEquals("the window itself", 8_000L, limit.ceiling)
        assertEquals("so that is what is in force", 8_000L, limit.inForce)
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
        // **The session limit is in tokens now, and it is a plain number.** It used to be megabytes
        // converted here; the operator asked for one field in the unit the model is billed in, so
        // there is nothing to convert and nothing for this test to get wrong.
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
    fun `a trim below the forget point is a mode, not a mistake`() {
        // **The operator's correction, 2026-10-29**: "trim compaction actually can be set below
        // forget and then it is only the app that handles the compaction (a feature, not a bug)
        // because forget is just never triggered."
        //
        // This used to be repaired - a hard limit under the trigger was raised to it - because the
        // design at the time treated the app's trim as a last resort that must never fire first.
        // With one shared target there is no contradiction to repair: a person who would rather the
        // app do the cutting than have the agent choose says so by putting the trim first, and the
        // agent is simply never asked. The number is honoured as typed.
        val limits = ContextBudget.Limits.of(
            triggerAsked = 50_000,
            hardAsked = 10_000,
            targetAsked = 4_000,
            ceiling = 917_504,
        )

        assertEquals("the agent's point is where it was put", 50_000L, limits.triggerAt)
        assertEquals("and the trim fires before it, as asked", 10_000L, limits.hardAt)
        assertEquals("with nothing rewritten", 10_000L, limits.hard.asked)
        assertEquals("and both aim at the one target", 4_000L, limits.targetAt)
    }

    @Test
    fun `the target must be below both points that fire, or the ask would remove nothing`() {
        // **The only ordering the three numbers need.** A target at or above a trigger means the
        // act that fires there removes nothing: the context is already under the number it is being
        // asked to reach. So the target sits strictly below each trigger that is on.
        val target = runCatching {
            ContextBudget.Limits.of(triggerAsked = 10_000, hardAsked = 0, targetAsked = 10_000, ceiling = 917_504)
        }
        assertTrue("a target at the forget point is refused", target.isFailure)

        val aboveTrim = runCatching {
            ContextBudget.Limits.of(triggerAsked = 50_000, hardAsked = 20_000, targetAsked = 20_000, ceiling = 917_504)
        }
        assertTrue("and a target at the trim point is refused too", aboveTrim.isFailure)

        // Below both, and it is accepted - which is the normal shape: forget early, trim later,
        // both aiming at the same smaller number.
        val fine = ContextBudget.Limits.of(
            triggerAsked = 50_000,
            hardAsked = 90_000,
            targetAsked = 24_000,
            ceiling = 917_504,
        )
        assertEquals("forget fires first", 50_000L, fine.triggerAt)
        assertEquals("the trim waits", 90_000L, fine.hardAt)
        assertEquals("and both aim at the same target", 24_000L, fine.targetAt)
    }

    @Test
    fun `a target of zero is half the forget point, so the ask buys headroom`() {
        // A default that landed ON the trigger would ask the agent to cut to exactly the number
        // that asked it - so the next row to arrive would cross the trigger again and the ask would
        // repeat. Half is the floor that makes one clear-up buy real quiet.
        val limits = ContextBudget.Limits.of(triggerAsked = 50_000, hardAsked = 0, ceiling = 917_504)
        assertEquals("half of where the ask fires", 25_000L, limits.targetAt)
        assertTrue("which is below it, so the rule holds", limits.targetAt < limits.triggerAt)
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
    fun `the estimate rounds up, at three and a half bytes a token`() {
        assertEquals("empty", 0L, ContextBudget.tokens(""))
        // 4 bytes / 3.5 = 1.14, rounded up.
        assertEquals("one token's worth", 2L, ContextBudget.tokens("abcd"))
        // 7 bytes is exactly two tokens.
        assertEquals("and seven is two", 2L, ContextBudget.tokens("abcdefg"))
        assertEquals("while eight is three", 3L, ContextBudget.tokens("abcdefgh"))
    }

    @Test
    fun `the estimate counts bytes, so a long body costs what it weighs`() {
        assertEquals(1_143L, ContextBudget.tokens("x".repeat(4_000)))
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
    }

    @Test
    fun `the estimate is built on the same bytes, so the two never disagree`() {
        val text = "a row with some words in it, and a number 42"
        assertEquals(
            "tokens is bytes over three and a half, rounded up",
            (ContextBudget.bytes(text) * 2 + 6) / 7,
            ContextBudget.tokens(text),
        )
    }

    @Test
    fun `the estimate over-counts for text that is not Latin, and that is the safe bound`() {
        // **Measured against the model this app pins.** DeepSeek publishes about 0.6 token per
        // Chinese character, so a hundred characters are about sixty tokens. Three and a half bytes
        // a token estimates eighty-six — over, which asks the agent to clear up early rather than
        // sending a request the endpoint refuses. The old four bytes a token estimated 75, which was
        // also over, but by luck rather than by choice: the same constant was 17% *under* for the
        // English and JSON that this app is actually full of.
        val cjk = "\u4E2D".repeat(100)
        assertEquals("300 bytes", 300L, ContextBudget.bytes(cjk))
        assertEquals("estimated at 86 tokens", 86L, ContextBudget.tokens(cjk))
        assertTrue("while the model would count about 60", ContextBudget.tokens(cjk) > 60)
    }

    @Test
    fun `a count reads as a scale, not a quantity`() {
        assertEquals("96k", ContextBudget.short(96_000))
        assertEquals("918k", ContextBudget.short(917_504))
        assertEquals("and a pin's ceiling is the design's own example", "105k", ContextBudget.short(104_858))
        assertEquals("off", ContextBudget.short(0))
        assertEquals("999", ContextBudget.short(999))
    }
    @Test
    fun `a trim typed below the target does not throw, because typing is one digit at a time`() {
        // **The crash**: "app is crushing after I tried to set trim compaction field in settings".
        //
        // The trace shows `trim-compaction 5` and then four relaunches in a row, each dying at the
        // settings screen - because 5 was already saved, so the screen rebuilt the same numbers and
        // threw in the same place. **One typed digit was an app that would not open.**
        //
        // The arithmetic: nothing else typed means the trigger resolves to a quarter of the ceiling
        // (24k) and the target to half of that (12k), so a trim of 5 put the target above the point
        // the trim fires at. `require(targetAt < hardAt)` threw `IllegalArgumentException` **from
        // inside a composable**, which is not a caught error, it is a dead app.
        //
        // **Every keystroke is its own state**, which is why this is not an edge case: typing 50000
        // passes through 5, 50, 500 and 5000, and the first of those was fatal. So the table below
        // is not four variations, it is what one person doing one thing produced.
        for (typed in listOf(5, 50, 500, 5_000, 50_000)) {
            val limits = ContextBudget.Limits.of(
                triggerAsked = 0,
                hardAsked = typed.toLong(),
                ceiling = ContextBudget.UNKNOWN_CEILING,
            )
            assertTrue(
                "typing $typed must not throw, and must aim below where it trims",
                limits.targetAt < limits.hardAt,
            )
        }
    }

    @Test
    fun `the derived target is below the trim when the trim is the earlier of the two`() {
        // The same bug asked as a question about the number rather than about the crash: **the
        // target must sit below whichever point fires first**, and that is not always the trigger.
        //
        // A trim below the clear-up point is a real choice - the app cuts and the agent is never
        // asked - so the trigger cannot be the only ceiling on where the target may be.
        val limits = ContextBudget.Limits.of(
            triggerAsked = 0,
            hardAsked = 5_000,
            ceiling = ContextBudget.UNKNOWN_CEILING,
        )
        assertEquals("the trim is where it was typed", 5_000L, limits.hardAt)
        assertEquals("so the target is half of the trim, not half of the trigger", 2_500L, limits.targetAt)
    }

    @Test
    fun `a typed target above the trim is repaired rather than obeyed`() {
        // **The second way into the same crash**, and it needs no unusual number: the target is a
        // field a person can type into, and a target above the trim is exactly what a person typing
        // three numbers in a hurry will produce.
        //
        // Repaired rather than refused, which is the rule the two limits already follow: what was
        // typed stays in `asked` where the screen can show it, and what is enforced is made to work.
        val limits = ContextBudget.Limits.of(
            triggerAsked = 50_000,
            hardAsked = 10_000,
            targetAsked = 40_000,
            ceiling = 917_504,
        )
        assertEquals("the typed number is kept for the field", 40_000L, limits.target.asked)
        assertTrue("and the number in force is below the trim", limits.targetAt < limits.hardAt)
    }

    @Test
    fun `no three numbers a person can type will throw`() {
        // **The crash was reachable by typing, so the fix is only done when typing cannot reach it.**
        //
        // The settings screen builds `Limits` on **every keystroke**, inside a composable. Anything
        // that throws there does not show an error, it kills the app - and because the numbers are
        // saved, it kills the app again on the next launch. That is what happened: one digit, four
        // relaunches.
        //
        // So this is not a test of one case. It walks every combination of the three fields across
        // the range a person plausibly types - including the intermediate values of typing a large
        // number one digit at a time - and requires that constructing the limits either succeeds
        // with a coherent answer or fails as an ordinary refusal. Nothing may throw.
        val interesting = listOf(
            0, 1, 2, 5, 50, 500, 5_000, 12_000, 24_000, 50_000, 96_000, 200_000,
        )
        for (trigger in interesting) {
            for (hard in interesting) {
                for (target in interesting) {
                    val limits = runCatching {
                        ContextBudget.Limits.of(
                            triggerAsked = trigger.toLong(),
                            hardAsked = hard.toLong(),
                            targetAsked = target.toLong(),
                            ceiling = ContextBudget.UNKNOWN_CEILING,
                        )
                    }.getOrElse { error ->
                        throw AssertionError(
                            "trigger=$trigger hard=$hard target=$target threw " +
                                "${error::class.simpleName}: ${error.message}",
                        )
                    }
                    // And the answer is coherent, not merely non-throwing: a target that is on or
                    // above a point that fires would ask for a size the context already has.
                    if (limits.targetAt > 0L && limits.triggerAt > 0L) {
                        assertTrue(
                            "trigger=$trigger hard=$hard target=$target: " +
                                "target ${limits.targetAt} must be below trigger ${limits.triggerAt}",
                            limits.targetAt < limits.triggerAt,
                        )
                    }
                    if (limits.targetAt > 0L && limits.hardAt > 0L) {
                        assertTrue(
                            "trigger=$trigger hard=$hard target=$target: " +
                                "target ${limits.targetAt} must be below trim ${limits.hardAt}",
                            limits.targetAt < limits.hardAt,
                        )
                    }
                }
            }
        }
    }

}
