package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * The meaning of each number, and the precision of the ones the screen prints.
 *
 * **What is left here after SQL took the summing.** Adding up a year of rows belongs in the
 * database, and it moved there — the tests that used to check "which stored level speaks for this
 * unit" are gone with the pyramid they described, because separate tables cannot double-count in
 * the first place. What cannot move is the *meaning*: that a cache share is a part of input rather
 * than a column beside it, that a bucket with no requests has no average rather than a zero, and
 * that a share keeps three decimals because one cannot tell working from broken.
 *
 * The precision tests are here because the numbers this app produces are small enough that the
 * obvious format destroys them: a ping costs about $0.00002, which three decimals prints as
 * "0.000" — indistinguishable from free.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RequestStatsTest {

    @Test
    fun `cache is a share of input, because it is a part of it`() {
        // **The double-count this prevents.** `cached_tokens` is a subset of `prompt_tokens`. A
        // table with three absolute columns — input, output, cache — would report 7,276 tokens for
        // a 3,692-token request, and a person reading it would believe the prompt had doubled.
        val stats = RequestStats.Stats(
            requests = 1,
            promptTokens = 3691,
            cachedTokens = 3584,
            completionTokens = 1,
            reasoningTokens = 1,
            cost = 0.0000241,
            costReported = 1,
        )

        assertEquals("a share of input", 0.9710, stats.cachedShare!!, 0.0001)
        assertEquals("and of output", 1.0, stats.thinkingShare!!, 0.0001)
        // The two absolutes do not overlap: input and output are the whole request.
        assertEquals(3692, stats.promptTokens + stats.completionTokens)
    }

    @Test
    fun `a bucket with no requests has no averages, rather than zeroes`() {
        // **A dash and a zero are different answers.** "0.00000 per request" is what a free bucket
        // would look like, and a person reading it would conclude the requests were free rather
        // than that there were none.
        val empty = RequestStats.Stats()
        assertNull("no cache share", empty.cachedShare)
        assertNull("no thinking share", empty.thinkingShare)
        assertNull("no cost per request", empty.perRequest)
        assertNull("no rate per million", empty.perMillionInput)
        assertNull("no output rate", empty.perMillionOutput)
        assertEquals("and the screen says so", StatsFormat.NONE, StatsFormat.perRequest(empty.perRequest))
    }

    @Test
    fun `a share keeps three decimals, because one cannot tell working from not`() {
        // **Three decimals is the spec (§1a), and it is a real distinction rather than fussiness.**
        // A cache share is the number a person consults to decide whether caching is working. One
        // decimal collapses 97.05% and 97.149% into the same "97.1%", which is the whole difference
        // between "all but a handful" and "all but one request in two thousand".
        //
        // Pinned as strings because the FORMAT is the thing under test: the arithmetic was already
        // right, and the screen was throwing the digits away on the way out.
        assertEquals("three decimals", "97.100%", StatsFormat.share(0.971))
        assertEquals("a share that is nearly all of it", "99.999%", StatsFormat.share(0.99999))
        // **The case one decimal cannot express**: these two differ by a fifth of a percent and
        // must not print the same, because at 10k requests a day that is twenty requests.
        assertNotEquals("two close shares print differently", StatsFormat.share(0.9705), StatsFormat.share(0.97149))
        assertEquals("and each is exact", "97.050%", StatsFormat.share(0.9705))
        assertEquals("the low end keeps its zeros", "0.050%", StatsFormat.share(0.0005))
        assertEquals("a share of nothing", "0.000%", StatsFormat.share(0.0))
        assertEquals("a full share", "100.000%", StatsFormat.share(1.0))
        // **No share is not a share of zero.** A bucket with no input tokens has nothing to divide,
        // and printing "0.000%" would claim the cache missed when nothing was asked.
        assertEquals("absent, not zero", StatsFormat.NONE, StatsFormat.share(null))
    }

    @Test
    fun `a character count is never printed as if it were a token count`() {
        // **The bug this pins was on the screen, two blocks apart.** The stall block counts
        // CHARACTERS, because a stalled answer has no `usage` and arrived characters are the only
        // thing known. The table above it counts TOKENS. Both went through the same formatter, so
        // `output 2.4k` and `written 2.1k` looked like the same measurement - they differ by about
        // four on this project's own prose.
        assertEquals("plain", "0 chars", StatsFormat.characters(0))
        assertEquals("thousands", "2.1k chars", StatsFormat.characters(2_140))
        assertEquals("millions", "1.5M chars", StatsFormat.characters(1_500_000))
        // And a token count must NOT carry the word, or the fix would have moved the ambiguity
        // rather than removed it.
        assertFalse("no unit on tokens", StatsFormat.tokens(2_140).contains("chars"))
        assertEquals("tokens are bare", "2.1k", StatsFormat.tokens(2_140))
        assertNotEquals(
            "the two blocks cannot print the same string for different units",
            StatsFormat.tokens(2_140),
            StatsFormat.characters(2_140),
        )
    }

    @Test
    fun `the share rides beside its absolute in one cell`() {
        // The operator's one-cell rule: `24.0k (91.7%)`. Two rows would separate a part from the
        // whole it is a part of, which is the fact the row exists to state.
        assertEquals("absolute and share", "24.0k (91.700%)", StatsFormat.absoluteAndShare("24.0k", 0.917))
        assertEquals("absolute alone", "24.0k", StatsFormat.absoluteAndShare("24.0k", null))
        assertEquals("share alone", "91.700%", StatsFormat.absoluteAndShare(null, 0.917))
        assertEquals("neither", StatsFormat.NONE, StatsFormat.absoluteAndShare(null, null))
    }

    @Test
    fun `an end is a boundary, so 19 to 20 is one hour`() {
        // **The operator's example, and the bug it caught.** "if now is 19:23 then you can choose
        // interval from 19 to 20." From 19 to 20 is ONE hour: 20 is where it stops, not an hour to
        // be included.
        //
        // The first version treated both picks as hours to contain, so 19-to-20 covered 19:00 to
        // 19:59:59 *and* 20:00 to 20:59:59 - two hours - and 19-to-22 was four. Worse, the To
        // button rendered `19:00` while the figures covered the whole of that hour.
        val zone = ZoneId.systemDefault()
        val day = LocalDate.parse("2026-10-25")
        fun at(h: Int) = day.atTime(h, 0).atZone(zone).toInstant().toEpochMilli()
        fun span(a: Int, b: Int) = RequestStats.interval(at(a), at(b))

        assertEquals("19 to 20 is one hour", 1L, span(19, 20).hours)
        assertEquals("and it starts at 19:00", at(19), span(19, 20).fromMs)
        assertEquals("and stops at the 20:00 boundary", at(20), span(19, 20).toMs)
        assertEquals("19 to 22 is three hours, not four", 3L, span(19, 22).hours)
        assertEquals("0 to 1 is one hour", 1L, span(0, 1).hours)

        // **A pick that is not on the hour snaps outward.** 19:23 as a start is floored to 19:00 so
        // the first 23 minutes are not dropped; 19:23 as an end is rounded up to the 20:00 boundary
        // so the last hour is not cut short. Covering at least what was asked for is the safe
        // direction: a total that quietly excluded the end of a chosen hour would look like lost
        // requests.
        val partial = RequestStats.interval(
            day.atTime(19, 23).atZone(zone).toInstant().toEpochMilli(),
            day.atTime(21, 47).atZone(zone).toInstant().toEpochMilli(),
        )
        assertEquals("19:23 to 21:47 covers 19:00 to 22:00", at(22), partial.toMs)
        assertEquals("which is three whole hours", 3L, partial.hours)
        assertEquals("starting at 19:00", at(19), partial.fromMs)

        // **Order does not matter.** A person moves whichever picker is nearest their thumb.
        assertEquals("left to right", span(19, 20), span(20, 19))
        assertEquals("the earlier pick is the start", at(19), span(20, 19).fromMs)
    }

    @Test
    fun `one instant is the one hour it falls in`() {
        // What "the current hour" means once the table is an interval: the hour containing now,
        // whose end is the next boundary. At 19:23 that is 19:00 to 20:00 - one hour, not a
        // 23-minute sliver and not two hours.
        val zone = ZoneId.systemDefault()
        val now = LocalDate.parse("2026-10-25").atTime(19, 23)
            .atZone(zone).toInstant().toEpochMilli()
        val span = RequestStats.interval(now, now)

        assertEquals("one hour", 1L, span.hours)
        assertEquals(
            "from 19:00",
            LocalDate.parse("2026-10-25").atTime(19, 0).atZone(zone).toInstant().toEpochMilli(),
            span.fromMs,
        )
        assertEquals(
            "to the 20:00 boundary",
            LocalDate.parse("2026-10-25").atTime(20, 0).atZone(zone).toInstant().toEpochMilli(),
            span.toMs,
        )
    }

    @Test
    fun `the same instant is the same request in every zone`() {
        // **The property the single instant column exists for**, now expressed on an interval rather
        // than on a column label. A log written in Berlin and read in Los Angeles must place every
        // row by its instant: the zone changes the TEXT of the two picker buttons and nothing else.
        //
        // The earlier version of this test compared the hour column's label between zones, which
        // only existed while the table drew one column per span. With a stated interval there is no
        // label to compare, so the claim is stronger and simpler - the arithmetic has no zone in it
        // at all.
        val instant = LocalDate.parse("2026-10-28").atTime(13, 14)
            .atZone(java.time.ZoneId.of("Europe/Berlin"))
            .toInstant()
            .toEpochMilli()
        val span = RequestStats.interval(instant - 3_600_000L, instant + 3_600_000L)

        // One hour either side, so the instant is inside whatever the zone says about it.
        assertEquals("the interval is built from instants alone", 3L, span.hours)
        assertTrue("and contains the instant", span.fromMs <= instant && instant < span.toMs)

        // The clock hour still differs between the zones - that is what makes the two clients
        // disagree about the LABEL and agree about the numbers.
        fun hourIn(zone: java.time.ZoneId): String =
            java.time.format.DateTimeFormatter.ofPattern("HH:00")
                .format(java.time.Instant.ofEpochMilli(instant).atZone(zone))
        assertNotEquals(
            "the two zones say a different clock hour",
            hourIn(java.time.ZoneId.of("Europe/Berlin")),
            hourIn(java.time.ZoneId.of("America/Los_Angeles")),
        )
    }

    @Test
    fun `the picker labels a whole hour, because the table cannot count part of one`() {
        // **`instantHour`, not `instant`.** The table's granularity is the hour, so a picker button
        // reading "19:23" would claim a precision the figures do not have - and an end shown as
        // 19:23 beside a total that runs to 19:59 is actively misleading about what was counted.
        val zone = java.time.ZoneId.of("UTC")
        val at = LocalDate.parse("2026-10-25").atTime(19, 23).atZone(zone).toInstant().toEpochMilli()
        assertEquals("the hour, to the minute it starts", "25 Oct 2026, 19:00", StatsFormat.instantHour(at, zone))
        assertNotEquals("and not the instant", StatsFormat.instant(at, zone), StatsFormat.instantHour(at, zone))
    }
}
