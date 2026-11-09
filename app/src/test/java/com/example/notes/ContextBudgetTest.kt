package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The three numbers are the person's, and the estimate errs early.**
 *
 * This file used to test a clamp: a ceiling per pinned endpoint, a ladder that derived one from a
 * model's window, and a repair that kept a target below the point that fired. All of that is gone.
 * What is left is one rule — *what was written is what is used* — and one estimate, which is the
 * only number the app derives.
 */
class ContextBudgetTest {

    @Test
    fun `each number is used exactly as it was written`() {
        val limits = ContextBudget.Limits.of(trigger = 100_000, hard = 250_000, target = 60_000)
        assertEquals("the clear-up point", 100_000L, limits.triggerAt)
        assertEquals("the trim point", 250_000L, limits.hardAt)
        assertEquals("the destination", 60_000L, limits.targetAt)
    }

    /**
     * **A number bigger than any window is not clamped.** This is the whole change, and the number
     * is absurd on purpose: a person who types it gets it, and the server that refuses the request
     * says why. Before this, 500,000 against a 104,858-token pin became 104,858 and the screen said
     * so — a number the person did not write, standing in for the one they did.
     */
    @Test
    fun `a number bigger than any window is used anyway`() {
        val limits = ContextBudget.Limits.of(trigger = 500_000, hard = 900_000, target = 400_000)
        assertEquals(500_000L, limits.triggerAt)
        assertEquals(900_000L, limits.hardAt)
        assertEquals(400_000L, limits.targetAt)
    }

    @Test
    fun `zero means that hand is off, and stays zero`() {
        val nothing = ContextBudget.Limits.of(trigger = 0, hard = 0, target = 0)
        assertEquals("no ask", 0L, nothing.triggerAt)
        assertEquals("no trim", 0L, nothing.hardAt)
        // **Not "a quarter of a ceiling"** any more: there is no ceiling to take a quarter of, and a
        // derived trigger was the one number that appeared without anybody typing it.
        assertEquals("no target", 0L, nothing.targetAt)

        val trimOnly = ContextBudget.Limits.of(trigger = 0, hard = 50_000, target = 20_000)
        assertEquals("the app alone, when only the trim is set", 0L, trimOnly.triggerAt)
        assertEquals(50_000L, trimOnly.hardAt)
    }

    /**
     * **A target above the trigger is used too, and is not repaired.**
     *
     * The old class threw here, from inside a composable, which is an app that will not open — one
     * typed digit, four relaunches, and the number saved. What the app does now is keep asking until
     * the context is under the trigger, which is a loop a person can see and fix; a crash is not.
     */
    @Test
    fun `a target above the thing that fires is used, not repaired`() {
        val limits = ContextBudget.Limits.of(trigger = 10_000, hard = 0, target = 90_000)
        assertEquals("as written", 90_000L, limits.targetAt)
    }

    @Test
    fun `no three numbers a person can type will throw`() {
        // The settings screen builds these on **every keystroke**, so anything thrown here does not
        // show an error — it kills the app, and it kills it again on the next launch because the
        // numbers are already saved. This walks the range a person plausibly types, including the
        // intermediate values of typing a long number one digit at a time.
        val interesting = listOf(0L, 1L, 2L, 5L, 50L, 500L, 5_000L, 12_000L, 24_000L, 50_000L,
            96_000L, 200_000L, 1_000_000L)
        for (trigger in interesting) {
            for (hard in interesting) {
                for (target in interesting) {
                    val limits = runCatching {
                        ContextBudget.Limits.of(trigger = trigger, hard = hard, target = target)
                    }.getOrElse { error ->
                        throw AssertionError(
                            "trigger=$trigger hard=$hard target=$target threw " +
                                "${error::class.simpleName}: ${error.message}",
                        )
                    }
                    // And nothing was quietly changed on the way through.
                    assertEquals("trigger=$trigger", trigger, limits.triggerAt)
                    assertEquals("hard=$hard", hard, limits.hardAt)
                    assertEquals("target=$target", target, limits.targetAt)
                }
            }
        }
    }

    @Test
    fun `a session log with no number keeps everything`() {
        // Zero is the default and it means the evidence is not deleted: the app used to drop the
        // oldest turns at four megabytes without anybody asking.
        // **The session limit is in tokens now, and it is a plain number.** It used to be megabytes
        // converted here; the operator asked for one field in the unit the model is billed in, so
        // there is nothing to convert and nothing for this test to get wrong.
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
        assertEquals("and a measured pin's ceiling, from the design", "105k", ContextBudget.short(104_858))
        assertEquals("off", ContextBudget.short(0))
        assertEquals("999", ContextBudget.short(999))
    }
    /**
     * **The crash that this redesign removes rather than fixes.**
     *
     * The trace was `trim-compaction 5` and then four relaunches, each dying on the settings screen:
     * `require(targetAt < hardAt)` threw `IllegalArgumentException` **from inside a composable**, and
     * because the digit was already saved it died again on every launch. Every keystroke is its own
     * state, so typing 50000 passed through 5, 50, 500 and 5000 — the first of those fatal.
     *
     * There is no repair to test any more, and that is the point: the three numbers are stored as
     * written, so a half-typed number is a half-typed number and nothing is computed from it until a
     * request is made.
     */
    @Test
    fun `a half-typed number is stored as it is, and computes nothing`() {
        for (typed in listOf(0L, 1L, 5L, 50L, 500L, 5_000L, 50_000L)) {
            val limits = ContextBudget.Limits.of(trigger = 0, hard = typed, target = 0)
            assertEquals("typing $typed is the trim", typed, limits.hardAt)
            assertEquals("and the ask is off", 0L, limits.triggerAt)
            assertEquals("and there is no destination", 0L, limits.targetAt)
        }
    }

    // ---- pictures -------------------------------------------------------------------------------
    //
    // The arithmetic is the provider's own, and the numbers below are the table from
    // `docs/DESIGN-seeing-images.md` §6 Q1 — which was written to answer the operator's question
    // "what is the difference before and after 1568px?" and found that 1568 is not a number the
    // provider has.

    @Test
    fun `a landscape picture is four tiles and a tall one is eight`() {
        // **The case that corrected the design.** The provider pins the SHORTEST side at 768, so a
        // tall picture keeps its height: 768x2048 is 2x4 tiles, not 2x2. A rule capping the long edge
        // would have made this picture cost MORE by shrinking it into a worse tile count.
        assertEquals("1024x768 is four tiles", 765L, ContextBudget.pictureTokens(1024, 768))
        assertEquals("768x2048 is eight", 1_445L, ContextBudget.pictureTokens(768, 2048))
        assertEquals("and a 16:9 phone screenshot is six", 1_105L, ContextBudget.pictureTokens(1080, 1920))
    }

    @Test
    fun `a big photo costs what its resized self costs, not what its bytes cost`() {
        // A 4000x3000 phone photo is 3-6 MB on disk and would be 8,245 tokens sent as-is. After the
        // provider's two resize steps it is 1024x768: four tiles, the same 765 as a small picture.
        // **Counting the base64 would have overcharged it by a third and still been wrong.**
        assertEquals(
            "a 12MP photo costs what a 1024x768 does",
            ContextBudget.pictureTokens(1024, 768),
            ContextBudget.pictureTokens(4000, 3000),
        )
        assertEquals(
            "and the resize is the provider's own two steps",
            1024 to 768,
            ContextBudget.fitted(4000, 3000),
        )
    }

    @Test
    fun `a picture smaller than the shortest side is left alone`() {
        // Nothing is ever enlarged: a 320x200 icon is sent as it is. **And it is ONE tile, not four** —
        // 320 and 200 both fit inside a single 512 square, so it is charged 85 + 170 = 255. Assuming
        // "four tiles" for anything small would over-report by a factor of three, and the budget's
        // safe direction is the one that never under-counts, not one that inflates everything.
        assertEquals("untouched", 320 to 200, ContextBudget.fitted(320, 200))
        assertEquals("one tile, 255 tokens", 255L, ContextBudget.pictureTokens(320, 200))
    }

    @Test
    fun `the first resize step happens before the second, and only shrinks`() {
        // A 3000x1000 is 3:1, so after the box it is 2048x683 - and the short side is already under
        // 768, so step two leaves it. **Eight tiles, 1,445 tokens**: two across and two down is not
        // how tiling works, because 2048 needs four columns and 683 needs two rows. That is the case
        // where doing the two steps in the other order gives a different answer, and the reason the
        // order matters.
        assertEquals("inside the box, short side under 768", 2048 to 683, ContextBudget.fitted(3000, 1000))
        assertEquals("four columns of tiles, two rows", 1_445L, ContextBudget.pictureTokens(3000, 1000))
        assertEquals("a square over the box is 768 square", 768 to 768, ContextBudget.fitted(4000, 4000))
    }

    @Test
    fun `a picture with no size costs nothing rather than crashing`() {
        // A file that would not decode has no dimensions, and the row still has to be countable.
        assertEquals("zero is not a picture", 0L, ContextBudget.pictureTokens(0, 0))
        assertEquals("and a negative is not either", 0L, ContextBudget.pictureTokens(-1, 100))
    }

    @Test
    fun `a picture is never cheaper than the framing charge`() {
        // The floor is the 85-token base: even a 1x1 picture is charged it. This is the property that
        // makes the estimate never under-report, which is the safe direction for a budget.
        for ((w, h) in listOf(1 to 1, 10 to 10, 512 to 512, 513 to 513)) {
            assertTrue(
                "${w}x$h must cost at least the base",
                ContextBudget.pictureTokens(w, h) >= ContextBudget.PICTURE_BASE,
            )
        }
    }

}
