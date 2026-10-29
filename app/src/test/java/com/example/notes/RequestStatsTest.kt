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
    fun `an end is a boundary, so 19 to 20 is sixty minutes`() {
        // **The operator's example, now at minute granularity.** "if now is 19:23 then you can
        // choose interval from 19 to 20" - from 19:00 to 20:00 is SIXTY minutes, because 20:00 is
        // where it stops rather than a minute to be included.
        //
        // An earlier version treated both picks as hours to contain, so 19-to-20 covered 19:00 to
        // 19:59:59 *and* 20:00 to 20:59:59 - two hours - and 19-to-22 was four.
        val zone = ZoneId.systemDefault()
        val day = LocalDate.parse("2026-10-25")
        fun at(h: Int, m: Int = 0) =
            day.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

        val hour = RequestStats.interval(at(19), at(20))
        assertEquals("19 to 20 is sixty minutes", 60L, hour.minutes)
        assertEquals("and it starts at 19:00", at(19), hour.fromMs)
        assertEquals("and stops at the 20:00 boundary", at(20), hour.toMs)

        // **The granularity is the minute now**, so a fifteen-minute window is an ordinary ask.
        val quarter = RequestStats.interval(at(19, 15), at(19, 30))
        assertEquals("19:15 to 19:30 is fifteen minutes", 15L, quarter.minutes)
        assertEquals("starting where it was asked to", at(19, 15), quarter.fromMs)
        assertEquals("and ending at the 19:30 boundary", at(19, 30), quarter.toMs)

        // **A pick with seconds snaps outward.** 19:23:40 as a start is floored to 19:23 so the
        // first forty seconds are not dropped; as an end it rounds up to 19:24 so the last minute
        // is not cut short. Covering at least what was asked for is the safe direction: a total
        // that quietly excluded the end of a chosen minute would look like lost requests.
        val partial = RequestStats.interval(
            day.atTime(19, 23, 40).atZone(zone).toInstant().toEpochMilli(),
            day.atTime(21, 47, 20).atZone(zone).toInstant().toEpochMilli(),
        )
        assertEquals("19:23:40 to 21:47:20 covers 19:23 to 21:48", at(21, 48), partial.toMs)
        assertEquals("starting at 19:23", at(19, 23), partial.fromMs)
        assertEquals("which is 145 minutes", 145L, partial.minutes)

        // **Order does not matter.** A person moves whichever picker is nearest their thumb.
        assertEquals("left to right", hour, RequestStats.interval(at(20), at(19)))
        assertEquals("the earlier pick is the start", at(19), RequestStats.interval(at(20), at(19)).fromMs)

        // **Both picks on the same minute is an empty interval, not a negative one.**
        val empty = RequestStats.interval(at(19, 30), at(19, 30))
        // **An empty interval, not a negative one.** Two picks on the same minute is the natural
        // way to ask for nothing, and the answer must be zero rather than a backwards span.
        assertEquals("no minutes", 0L, empty.minutes)
        assertEquals("and it does not run backwards", empty.fromMs, empty.toMs)
    }

    @Test
    fun `an interval reads as days, hours and minutes`() {
        // **The operator's format: `123 days 15 hours 51 minutes`.** That is how a duration is read
        // out loud, and it keeps the figure exact at every size - `871 minutes` is unreadable,
        // `14 hours` hides the half hour that is the point, and `14.52 hours` is not how anybody
        // says it.
        assertEquals("the operator's own example", "123 days 15 hours 51 minutes", StatsFormat.span(123 * 1440 + 15 * 60 + 51))
        assertEquals("minutes only", "15 minutes", StatsFormat.span(15))
        assertEquals("one minute", "1 minute", StatsFormat.span(1))
        assertEquals("an empty interval still says something", "0 minutes", StatsFormat.span(0))
        assertEquals("an exact hour", "1 hour", StatsFormat.span(60))
        assertEquals("hours and minutes", "1 hour 30 minutes", StatsFormat.span(90))
        assertEquals("a long day", "14 hours 31 minutes", StatsFormat.span(871))
        assertEquals("an exact day", "1 day", StatsFormat.span(1440))
        assertEquals("a day and an hour", "1 day 1 hour", StatsFormat.span(1500))
        // **A zero unit is dropped rather than padded.** `1 day 5 minutes` says what happened;
        // `1 day 0 hours 5 minutes` makes a reader check a number that carries nothing.
        assertEquals("no hours to show", "1 day 5 minutes", StatsFormat.span(1445))
        assertEquals("two days", "2 days", StatsFormat.span(2 * 1440))
        assertEquals("all three", "2 days 3 hours 17 minutes", StatsFormat.span(2 * 1440 + 197))
    }

    @Test
    fun `a rolling preset reaches back from the opening moment`() {
        // **The operator's definition**: rolling is "just rolling" - now minus the length, with
        // nothing snapped. 09:16 minus an hour is 08:16, not 09:00.
        val zone = ZoneId.of("UTC")
        val now = LocalDate.parse("2026-10-28").atTime(9, 16).atZone(zone).toInstant().toEpochMilli()
        fun start(p: RequestStats.Preset) = p.intervalAt(now, zone).fromMs
        fun at(h: Int, m: Int = 0, day: Int = 28) =
            LocalDate.parse("2026-10-${day.toString().padStart(2, '0')}")
                .atTime(h, m).atZone(zone).toInstant().toEpochMilli()

        assertEquals("15 min", at(9, 1), start(RequestStats.Preset.MINUTES_15))
        assertEquals("1 hour", at(8, 16), start(RequestStats.Preset.HOUR_1))
        assertEquals("6 hours", at(3, 16), start(RequestStats.Preset.HOURS_6))
        assertEquals("1 day", at(9, 16, day = 27), start(RequestStats.Preset.DAY_1))
        assertEquals("1 week", at(9, 16, day = 21), start(RequestStats.Preset.WEEK_1))
        // **Every one ends at the opening moment**, which is what makes the two families comparable.
        for (p in listOf(RequestStats.Preset.MINUTES_15, RequestStats.Preset.YEAR_1)) {
            assertEquals("${p.label} ends now", now, p.intervalAt(now, zone).toMs)
        }
    }

    @Test
    fun `a clock preset snaps its start to the grid`() {
        // **The operator's distinction**: clock starts at zero minutes, zero hour, zero day of the
        // month, zero month of the year - whichever boundary the length reaches. The end is still
        // the opening moment, so a clock interval is always at least as wide as the rolling one.
        val zone = ZoneId.of("UTC")
        val now = LocalDate.parse("2026-10-28").atTime(9, 16).atZone(zone).toInstant().toEpochMilli()
        fun start(p: RequestStats.Preset) = p.clockAt(now, zone).fromMs
        fun at(h: Int, m: Int = 0, day: Int = 28, month: Int = 10, year: Int = 2026) =
            LocalDate.of(year, month, day).atTime(h, m).atZone(zone).toInstant().toEpochMilli()

        // **Under an hour, the grid is the hour** - which is why 15 min and 6 hours both start at
        // 09:00: their length does not reach a coarser boundary, so the hour is where they begin.
        assertEquals("15 min starts at the hour", at(9), start(RequestStats.Preset.MINUTES_15))
        assertEquals("1 hour too", at(9), start(RequestStats.Preset.HOUR_1))
        assertEquals("6 hours too", at(9), start(RequestStats.Preset.HOURS_6))
        assertEquals("1 day starts at midnight", at(0), start(RequestStats.Preset.DAY_1))
        assertEquals("1 week too", at(0), start(RequestStats.Preset.WEEK_1))
        assertEquals("1 month starts on the 1st", at(0, day = 1), start(RequestStats.Preset.MONTH_1))
        assertEquals("1 year starts on 1 January", at(0, day = 1, month = 1), start(RequestStats.Preset.YEAR_1))

        // **A clock interval is never narrower than its rolling twin**, because it starts earlier.
        for (p in RequestStats.Preset.entries.filter { it != RequestStats.Preset.TOTAL }) {
            assertTrue(
                "${p.label}: clock starts at or before rolling",
                p.clockAt(now, zone).fromMs <= p.intervalAt(now, zone).fromMs,
            )
        }
    }

    @Test
    fun `a quarter on the clock starts on a quarter boundary, not the first of the month`() {
        // **The case that separates "months" from "quarters".** On 28 October a one-month clock
        // interval starts on 1 October, which is right; a one-quarter one that also started on
        // 1 October would be a month, not a quarter. It starts on 1 October only because October IS
        // a quarter boundary - so the test uses a month that is not.
        val zone = ZoneId.of("UTC")
        val november = LocalDate.parse("2026-11-15").atTime(9, 16).atZone(zone).toInstant().toEpochMilli()
        val quarterStart = RequestStats.Preset.QUARTER_1.clockAt(november, zone).fromMs
        assertEquals(
            "a quarter beginning in November starts in October",
            LocalDate.parse("2026-10-01").atStartOfDay(zone).toInstant().toEpochMilli(),
            quarterStart,
        )
        val december = LocalDate.parse("2026-12-15").atTime(9, 16).atZone(zone).toInstant().toEpochMilli()
        assertEquals(
            "and December is still the same quarter",
            LocalDate.parse("2026-10-01").atStartOfDay(zone).toInstant().toEpochMilli(),
            RequestStats.Preset.QUARTER_1.clockAt(december, zone).fromMs,
        )
    }

    @Test
    fun `a month back from the thirty-first clamps rather than overflowing`() {
        // **31 October minus one month is 30 September**, not 31 September and not 1 October. Java's
        // calendar arithmetic clamps, which is the answer a person means; a fixed 30-day subtraction
        // would give 1 October and be wrong by a day in the other direction.
        val zone = ZoneId.of("UTC")
        val at = LocalDate.parse("2026-10-31").atTime(9, 16).atZone(zone).toInstant().toEpochMilli()
        assertEquals(
            "clamped to the last day of September",
            LocalDate.parse("2026-09-30").atTime(9, 16).atZone(zone).toInstant().toEpochMilli(),
            RequestStats.Preset.MONTH_1.intervalAt(at, zone).fromMs,
        )
    }

    @Test
    fun `total reaches back to the oldest thing the log holds`() {
        // The one preset whose start cannot be derived from `now`, so it takes the history's own
        // beginning. With no history it is an empty interval rather than one stretching to 1970.
        val zone = ZoneId.of("UTC")
        val now = LocalDate.parse("2026-10-28").atTime(9, 16).atZone(zone).toInstant().toEpochMilli()
        val oldest = now - 400L * 86_400_000L
        assertEquals("from the first row", oldest, RequestStats.Preset.TOTAL.intervalAt(now, zone, oldest).fromMs)
        assertEquals("to the opening moment", now, RequestStats.Preset.TOTAL.intervalAt(now, zone, oldest).toMs)
        // **Clock and rolling agree on total**, because there is no grid to snap to.
        assertEquals(
            "no grid to snap for total",
            RequestStats.Preset.TOTAL.intervalAt(now, zone, oldest),
            RequestStats.Preset.TOTAL.clockAt(now, zone, oldest),
        )
        assertEquals("and with no history it is empty", now, RequestStats.Preset.TOTAL.intervalAt(now, zone, null).fromMs)
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
        assertEquals("the interval is built from instants alone", 120L, span.minutes)
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
    fun `the picker labels the minute it can now choose`() {
        // **The reverse of the rule that held while the finest summary was an hour.** Then, a
        // button reading "19:23" claimed a precision the figures did not have. Now the log keeps a
        // minute table, so 19:23 is exactly what the interval is - and showing 19:00 would hide the
        // minute the person just picked.
        val zone = java.time.ZoneId.of("UTC")
        val at = LocalDate.parse("2026-10-25").atTime(19, 23).atZone(zone).toInstant().toEpochMilli()
        assertEquals("the picked minute", "25 Oct 2026, 19:23", StatsFormat.instantMinute(at, zone))
        assertEquals("which is the instant itself", StatsFormat.instant(at, zone), StatsFormat.instantMinute(at, zone))
    }
}
