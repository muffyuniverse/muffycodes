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
 * that a share keeps the decimals the operator asked for, because a rounded share hides a change.
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
        assertEquals("and the screen says so", StatsFormat.NONE, StatsFormat.perRequest(empty.perRequest))
        // **The two "per M" rates used to be asserted here, and the rows they fed are gone** - the
        // operator asked for the endpoint's advertised rates in the agent block instead, because a
        // rate derived from what was spent folds the cache saving in and answers a question nobody
        // asks. The getters went with the rows rather than lingering as dead arithmetic.
    }

    @Test
    fun `a share keeps two decimals, because that is the precision that was asked for`() {
        // **Three decimals was the spec, and the operator replaced it**: "add cached % line as share
        // of total input with .00% precision". They are the one reading the screen, and two decimals
        // is what a share is normally quoted to.
        //
        // **What the third decimal was protecting is now said a different way.** The old comment
        // argued that one decimal collapses 97.05% and 97.149%, which is true and is why the answer
        // was not one decimal either. Two keeps the difference between "all but a handful" and "all
        // but one request in two thousand" visible for every share that is not within a hundredth of
        // a percent of another — and a table with a `before` column beside it shows a change as a
        // change rather than as a fourth digit.
        //
        // Pinned as strings because the FORMAT is the thing under test: the arithmetic was already
        // right, and the screen was throwing the digits away on the way out.
        assertEquals("two decimals", "97.10%", StatsFormat.share(0.971))
        assertEquals("a share that is nearly all of it", "100.00%", StatsFormat.share(0.99999))
        // **The case one decimal cannot express**: these differ by a fifth of a percent and must not
        // print the same, because at 10k requests a day that is twenty requests.
        assertNotEquals("two close shares print differently", StatsFormat.share(0.9705), StatsFormat.share(0.97149))
        assertEquals("and each is exact", "97.05%", StatsFormat.share(0.9705))
        assertEquals("the low end keeps its zeros", "0.05%", StatsFormat.share(0.0005))
        assertEquals("a share of nothing", "0.00%", StatsFormat.share(0.0))
        assertEquals("a full share", "100.00%", StatsFormat.share(1.0))
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
        assertEquals("absolute and share", "24.0k (91.70%)", StatsFormat.absoluteAndShare("24.0k", 0.917))
        assertEquals("absolute alone", "24.0k", StatsFormat.absoluteAndShare("24.0k", null))
        assertEquals("share alone", "91.70%", StatsFormat.absoluteAndShare(null, 0.917))
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
        // the opening moment, so a clock interval is the current cell: the part of its own length
        // which the calendar has delivered so far, and therefore inside the rolling interval.
        val zone = ZoneId.of("UTC")
        val now = LocalDate.parse("2026-10-28").atTime(9, 16).atZone(zone).toInstant().toEpochMilli()
        fun start(p: RequestStats.Preset) = p.clockAt(now, zone).fromMs
        fun at(h: Int, m: Int = 0, day: Int = 28, month: Int = 10, year: Int = 2026) =
            LocalDate.of(year, month, day).atTime(h, m).atZone(zone).toInstant().toEpochMilli()

        // **The grid is the preset's own length**, and the operator's report is what pinned it:
        // "clock 6 hours should set as from whole 6 hour bases (0, 6, 12, 18) so that if the to
        // time is 9:55 then from is 6:00 ... for 1 hour it should be beginning of the hour ... for
        // 15 min it should be 0, 15, 30 or 45".
        //
        // All three used to truncate to the HOUR, so a 09:55 clock gave 09:00 to every one of them:
        // "6 hours" covered fifty-five minutes, and "15 min" covered the whole hour. The three
        // chips named three intervals and two of them were wrong.
        // The fixture's clock is 09:16, so the 15-minute cell is the one that began at 09:15. The
        // operator's own example is "9:55 -> 9:45", which this asserts through the same rule rather
        // than by moving the clock: the cell boundary, not the example, is what the code decides.
        assertEquals("15 min starts at :15", at(9, 15), start(RequestStats.Preset.MINUTES_15))
        assertEquals("1 hour starts at the top of the hour", at(9), start(RequestStats.Preset.HOUR_1))
        assertEquals("6 hours starts at the 6-hour base", at(6), start(RequestStats.Preset.HOURS_6))
        assertEquals("1 day starts at midnight", at(0), start(RequestStats.Preset.DAY_1))
        // **A week starts on a Monday, not at today's midnight.** Truncating to DAYS gave "1 week"
        // exactly the same interval as "1 day", so the chip named a week and covered a few hours.
        // 28 Oct 2026 is a Wednesday, so the week began on Monday the 26th.
        assertEquals("1 week starts on Monday", at(0, day = 26), start(RequestStats.Preset.WEEK_1))
        assertEquals("1 month starts on the 1st", at(0, day = 1), start(RequestStats.Preset.MONTH_1))
        assertEquals("1 year starts on 1 January", at(0, day = 1, month = 1), start(RequestStats.Preset.YEAR_1))

        // **Which family is wider depends on how OLD the grid cell is, not on the preset's size.**
        // The clock start is the beginning of the cell; the rolling start is `now - length`. So the
        // clock reaches further back exactly when the cell has already been running longer than the
        // length names.
        //
        // **Every clock interval sits inside the rolling one, and none reaches into the future.**
        // The grid is the preset's own length - so the clock start is that length floored to the
        // calendar, which is always at or after `now - length`. "1 day" on this screen means *today
        // from midnight*, which at 09:16 is nine hours, not twenty-four.
        //
        // This replaced a two-branch comparison that asked whether "the cell is older than the
        // length", which was true for the coarse-grid durations and is now true for none of them.
        // Containment is the rule that survived the fix, so it is the rule asserted.
        for (p in RequestStats.Preset.entries.filter { it != RequestStats.Preset.TOTAL }) {
            val clock = p.clockAt(now, zone).fromMs
            val rolling = p.intervalAt(now, zone).fromMs
            assertTrue("${p.label}: the clock is inside the rolling interval", clock >= rolling)
            assertTrue("${p.label}: the clock does not start in the future", clock <= now)
        }
    }

    @Test
    fun `the clock grids are the lengths' own, at the operator's own times`() {
        // **The report's four worked examples, asserted as written.** "clock 6 hours should set as
        // from whole 6 hour bases (0, 6, 12, 18) so that if the to time is 9:55 then from is 6:00,
        // and for 14:30 it is 12:00. for 1 hour it should be beginning of the hour: 9:00 for 9:55.
        // for 15 min it should be 0, 15, 30 or 45, so that for 9:55 it is 9:45 (and the interval is
        // 10 min)".
        //
        // This is a second test rather than more lines in the one above because that one runs on a
        // 09:16 clock, where two of these four cases cannot be told apart from the old behaviour.
        val zone = ZoneId.of("UTC")
        fun at(h: Int, m: Int) =
            LocalDate.parse("2026-10-28").atTime(h, m).atZone(zone).toInstant().toEpochMilli()
        fun start(p: RequestStats.Preset, h: Int, m: Int) = p.clockAt(at(h, m), zone).fromMs

        assertEquals("six hours at 9:55 begins at 6:00", at(6, 0), start(RequestStats.Preset.HOURS_6, 9, 55))
        assertEquals("six hours at 14:30 begins at 12:00", at(12, 0), start(RequestStats.Preset.HOURS_6, 14, 30))
        assertEquals("one hour at 9:55 begins at 9:00", at(9, 0), start(RequestStats.Preset.HOUR_1, 9, 55))
        assertEquals("fifteen minutes at 9:55 begins at 9:45", at(9, 45), start(RequestStats.Preset.MINUTES_15, 9, 55))
        // "and the interval is 10 min" - the width that example produces, which is the thing the
        // person actually reads off the screen under the table.
        assertEquals(
            "and that interval is ten minutes wide",
            10 * 60_000L,
            at(9, 55) - start(RequestStats.Preset.MINUTES_15, 9, 55),
        )
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
    fun `the previous period is the same length, ending where this one starts`() {
        // **The operator's definition**: *"previous interval is just start of the current interval
        // minus length of the current interval."*
        val zone = ZoneId.of("UTC")
        fun ms(h: Int, m: Int = 0, day: Int = 28) =
            LocalDate.parse("2026-10-${day.toString().padStart(2, '0')}")
                .atTime(h, m).atZone(zone).toInstant().toEpochMilli()

        val hour = RequestStats.Interval(ms(10), ms(11))
        val before = RequestStats.previousOf(hour, zone)
        assertEquals("ends where this one starts", ms(10), before.toMs)
        assertEquals("and is the same length", 3_600_000L, before.toMs - before.fromMs)
        assertEquals("so it starts an hour earlier", ms(9), before.fromMs)

        // **A hand-set interval gets a baseline too**, which is the reason this is a function of the
        // interval rather than of a preset: 10:17 to 10:43 compares against 09:51 to 10:17.
        val odd = RequestStats.Interval(ms(10, 17), ms(10, 43))
        val oddBefore = RequestStats.previousOf(odd, zone)
        assertEquals("a hand-set interval still has a previous", ms(9, 51), oddBefore.fromMs)
        assertEquals("of exactly its length", odd.toMs - odd.fromMs, oddBefore.toMs - oddBefore.fromMs)

        // **An empty interval has no previous**, rather than a backwards one.
        val empty = RequestStats.Interval(ms(10), ms(10))
        assertEquals("empty", ms(10), RequestStats.previousOf(empty, zone).fromMs)
        assertEquals("and not negative", ms(10), RequestStats.previousOf(empty, zone).toMs)
    }

    @Test
    fun `the previous period of a day is a calendar day, not 86,400,000 milliseconds`() {
        // **The DST case, and the reason the subtraction is on the local timeline.** On 25 October
        // 2026 the UK clocks go back at 02:00, so that local day is 25 hours. Subtracting
        // 86,400,000 ms from the 26th's midnight would land at 00:00 on the 25th *local* only by
        // coincidence of the epoch - the point is that a fixed-milliseconds subtraction cannot
        // express "a day ago" in a zone whose days are not all 24 hours, and the previous period
        // would end an hour away from where the current one starts.
        val london = ZoneId.of("Europe/London")
        fun midnight(day: Int) =
            LocalDate.parse("2026-10-${day.toString().padStart(2, '0')}")
                .atStartOfDay(london).toInstant().toEpochMilli()

        // The fixture really is testing what it claims: the 25th is 25 hours long in this zone.
        assertEquals("the 25th is a 25-hour day here", 25L * 3_600_000L, midnight(26) - midnight(25))
        assertEquals("and the 26th is 24", 24L * 3_600_000L, midnight(27) - midnight(26))

        // **The current period is the 26th, which is 24 hours. Its previous is also 24 hours.**
        //
        // On this day that starts at 01:00 on the 25th rather than at its midnight, and the
        // assertion says so explicitly because it looks like a bug and is not. The alternative - the
        // previous CALENDAR day, 00:00 to 00:00 - would set 25 hours of traffic beside 24 and read
        // as a 4% rise that nothing caused. A comparison column exists to compare equal amounts of
        // time, and "minus the length" is what the operator asked for.
        val before = RequestStats.previousOf(RequestStats.Interval(midnight(26), midnight(27)), london)
        assertEquals("it ends where the current one starts", midnight(26), before.toMs)
        assertEquals(
            "and is exactly as long, which is what makes the comparison fair",
            midnight(27) - midnight(26),
            before.toMs - before.fromMs,
        )
        assertEquals(
            "so it starts at 01:00 on the 25th, not at midnight",
            LocalDate.parse("2026-10-25").atTime(1, 0).atZone(london).toInstant().toEpochMilli(),
            before.fromMs,
        )
    }

    @Test
    fun `the button is pending when the picks differ from what was generated`() {
        // **The Generate button's entire behaviour.** Pulled out of the screen because the
        // alternative is driving Material's calendar to learn whether a button is enabled, which
        // tests the harness rather than the decision.
        // **Minute-aligned instants, because `interval` snaps to whole minutes.** A fixture of
        // 1_000L and 2_000L both floor to the same minute, so the interval is 0-60_000 whatever the
        // picks are - and a test written on those numbers would be asserting about the snap rather
        // than about the decision.
        val min = 60_000L
        val generated = RequestStats.Interval(10 * min, 20 * min)

        assertEquals("unchanged", false, RequestStats.pending(10 * min, 20 * min, generated))
        assertEquals("the start moved", true, RequestStats.pending(9 * min, 20 * min, generated))
        assertEquals("the end moved", true, RequestStats.pending(10 * min, 21 * min, generated))
        // **Swapping the ends is NOT pending**, because `interval` sorts them: an interval named
        // backwards is the same span of time, and this screen has always treated it that way. Worth
        // pinning precisely because the naive guess is the opposite - a check done end-by-end would
        // call this a change and offer to regenerate identical figures.
        assertEquals("swapped is the same interval", false, RequestStats.pending(20 * min, 10 * min, generated))
        // **Returning to the generated interval clears it**, because the question is what the table
        // is showing, not whether anything was ever touched.
        assertEquals("moved and moved back", false, RequestStats.pending(10 * min, 20 * min, generated))
    }

    @Test
    fun `a change is a ratio, and has no answer without a baseline`() {
        // **The trap this guards.** One ping last period against twenty-two requests this period is
        // +79,600%, which is arithmetically true and reads as a catastrophe. A zero baseline has no
        // ratio at all, and neither does a figure only one side reported.
        assertEquals("a rise", 0.5, RequestStats.change(100.0, 150.0)!!, 1e-9)
        assertEquals("a fall", -0.25, RequestStats.change(100.0, 75.0)!!, 1e-9)
        assertEquals("no change", 0.0, RequestStats.change(100.0, 100.0)!!, 1e-9)
        // **A zero baseline is not "infinitely more".**
        assertNull("nothing to compare against", RequestStats.change(0.0, 22.0))
        // **Stopping entirely IS an answer**, and it is the one a person opens this table to find.
        assertEquals("it stopped", -1.0, RequestStats.change(22.0, 0.0)!!, 1e-9)
        // **An absent figure on either side has no change**, rather than a change against zero.
        assertNull("no previous figure", RequestStats.change(null, 22.0))
        assertNull("no current figure", RequestStats.change(22.0, null))
    }

    @Test
    fun `a change prints with one decimal and an explicit sign`() {
        // **One decimal, not three.** A change is a comparison of two numbers already rounded on
        // screen; claiming 18.243% invites arithmetic that cannot be trusted.
        assertEquals("a rise carries a plus", "+18.2%", StatsFormat.change(0.182))
        assertEquals("a fall carries its own minus", "-4.0%", StatsFormat.change(-0.04))
        assertEquals("it stopped", "-100.0%", StatsFormat.change(-1.0))
        assertEquals("no change", "0.0%", StatsFormat.change(0.0))
        // **No baseline prints a dash**, the same as every other absent figure.
        assertEquals("absent", StatsFormat.NONE, StatsFormat.change(null))
        // **Rounded before it is signed**, so a change of -0.04% is `0.0%` and not `-0.0%`.
        assertEquals("a negligible fall is not a negative zero", "0.0%", StatsFormat.change(-0.0001))
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
