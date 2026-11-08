package com.example.notes

import java.util.Locale
import kotlin.math.abs

/**
 * The four numbers worth glancing at, and the last two days of them.
 *
 * **This replaces the statistics screen.** That screen was a table you had to go and open, which is
 * the wrong shape for an app that runs an agent: the question "is it behaving" is asked constantly
 * and answered by looking, so the answer has to be where a person already is. A table answers
 * "what happened between 09:00 and 10:00 on Tuesday" - a question an agent now answers better, from
 * the same database, in prose.
 *
 * **Two windows, on purpose.** Each cell carries a number for the last sixty minutes and a graph of
 * the last forty-eight hours. They answer different questions: the number is "what is happening
 * now", the graph is "is that unusual". One window cannot answer both, and a card that showed only
 * the recent number would be a card nobody could read a trend from.
 */
object Heartbeat {

    /** Forty-eight points, one per hour. */
    const val HOURS = 48

    /** The recent window, in minutes. */
    const val RECENT_MINUTES = 60L

    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE

    /**
     * One measure, over the two windows.
     *
     * `points` has [HOURS] entries and **an entry may be null**, which means no request was recorded
     * in that hour. That is not the same as zero: a zero says "nothing was spent", a null says
     * "nothing was recorded", and a graph that drew the second as the first would show a flat line
     * through a period when the app was not running - or through the hours a retention cutoff
     * removed.
     */
    data class Measure(
        val id: Id,
        /** The last [RECENT_MINUTES], or null when there was no request in it. */
        val recent: Double?,
        /** What [recent] is compared against. See [of] for why the two kinds differ. */
        val baseline: Double?,
        val points: List<Double?>,
    ) {
        /**
         * How far [recent] is from [baseline], as a fraction.
         *
         * **A baseline of zero has no percentage to be below.** For a rate, a zero baseline means
         * nothing was recorded over the period at all; for a total, it means nothing was spent. In
         * both cases the division has no answer and the badge says so.
         */
        val badge: Double? get() {
            val now = recent ?: return null
            val base = baseline ?: return null
            // **A zero baseline is a real measurement with no percentage.** `baseline` is `0.0` for
            // a total over a period that spent nothing, and null for a rate with no denominator -
            // two different facts, and only the badge treats them alike, because dividing by either
            // has no answer.
            if (base == 0.0) return null
            return (now - base) / base
        }

        /**
         * Whether [badge] should read as good news.
         *
         * **Meaningless for a [Id.neutral] measure**, which is drawn in the brand colour precisely
         * because the app declines to say whether its rise is good - so this returns null there and
         * the card never asks.
         */
        val better: Boolean? get() = if (id.neutral) null else badge?.let { it >= 0 }

    }

    /**
     * The four cells, in the order they are drawn.
     *
     * **The order is the order of the questions.** "How much work happened" first — the count of
     * requests, which is what a person checks first now that the fourth cell is not money — then how
     * well the cache is working, which is what the work *cost*, then how much came out, then how much
     * of that was answer rather than thinking.
     */
    enum class Id(
        val title: String,
        val additive: Boolean,
        /**
         * Whether this measure's badge is drawn in the brand's caramel instead of green or red.
         *
         * **Activity alone.** Deciding whether more requests is good is not the app's call — a busy
         * hour is more work and more spend, and which of those matters depends on what the person is
         * doing. So for the request count the badge states the comparison and stops: the colour marks
         * it as a comparison rather than judging it. The other three keep green above and red below,
         * because a cache hit rising and a share of output rising are plainly the better direction
         * and calling them bad would be wrong rather than modest.
         */
        val neutral: Boolean = false,
    ) {
        /**
         * **Requests, which replaced spend — and the replacement is the point.**
         *
         * A fourth measure existed here and it was money: `usage.cost`, summed per hour. That field
         * is OpenRouter's own, not the protocol's, so a server this app talks to has no obligation
         * to report it — and a spend cell showing `0.00$` for a busy hour on such a server reads as
         * "free", which is the one direction a record must not lie in. The operator's rule:
         * *if we can't get it through the protocol then we don't need it.*
         *
         * **A count of requests is the protocol's own fact**, it is what the other three measures are
         * shares of, and it is the number a person asks after spend: how much work happened. Neutral
         * like spend was, because "more requests" is not better or worse — it is activity.
         */
        REQUESTS("requests", additive = true, neutral = true),
        CACHE("cache hit", additive = false),
        OUTPUT("output", additive = true),
        SHARE("output share", additive = false),
    }

    /**
     * All four measures over the period ending at [now].
     *
     * **The points are sliding windows, not clock hours.** The operator was explicit: *"they are not
     * clock hours but intervals computed from minutes, so that last entry shows real 60 min"*. A
     * clock-hour graph would draw the hour still in progress, so at 10:20 the newest bar would hold
     * twenty minutes while the number above it said sixty - two windows with the same label and
     * different lengths. Here every point is exactly sixty minutes, and the newest point *is* the
     * number.
     *
     * **`now` is taken down to the minute** so the window edges land on bucket edges. Otherwise a
     * window starting at 09:23:45 would be summed from buckets beginning 09:24:00, and every point
     * would be quietly shifted. "The last sixty minutes" then means the last sixty whole ones, which
     * is a thing that can be checked.
     *
     * **The two kinds of baseline are different on purpose.** Spend and output are totals, so the
     * comparison is against the *average hour* - the total divided by [HOURS]. Cache share and
     * output share are rates, and averaging percentages would weight a quiet hour with one request
     * the same as a busy hour with a thousand; those compare against the ratio of the period's sums,
     * which is what "the cache hit rate over two days" actually means.
     */
    fun of(db: RequestDb.History, now: Long): List<Measure> {
        // **The window ends at the NEXT minute boundary, not the last one.** The minute in progress
        // has no closed bucket, so a window ending at the floor would drop everything since the
        // minute began - up to fifty-nine seconds of the newest work, which is the one request a
        // person opens the card to watch. Ending on the boundary that is still to come includes it,
        // and keeps every window exactly sixty minutes.
        val at = ceilToMinute(now)
        val start = at - HOURS * HOUR

        // **One read, then summed in memory.** Asking for forty-eight windows would be forty-eight
        // queries, and each would round outward to whole buckets - see [RequestDb.minutes].
        //
        // **Read to `at`**, the boundary after now, so the part-minute's raw rows are included.
        val buckets = db.minutes(start, at)

        fun sum(from: Long, to: Long): RequestStats.Stats {
            var out = RequestStats.Stats()
            var b = from - Math.floorMod(from, MINUTE)
            while (b < to) {
                val bucket = buckets[b]
                if (bucket != null) out += bucket
                b += MINUTE
            }
            return out
        }

        // The windows partition the period exactly, so the last one is the recent window.
        val windows = (0 until HOURS).map { i ->
            sum(start + i * HOUR, start + (i + 1) * HOUR)
        }
        val whole = windows.fold(RequestStats.Stats()) { a, b -> a + b }

        fun measure(id: Id, points: List<Double?>, whole: Double?, recent: Double?) =
            Measure(
                id = id,
                recent = recent,
                // **Totals are compared per hour; rates are not divided at all.** Dividing a rate
                // by forty-eight would turn a 90% cache share into 1.9%, and the badge would then
                // report a 4700% improvement on any normal hour.
                baseline = if (id.additive) whole?.div(HOURS.toDouble()) else whole,
                points = points,
            )

        return listOf(
            measure(
                Id.REQUESTS,
                windows.map { w -> w.requests.toDouble().takeIf { w.requests > 0 } },
                whole = whole.requests.toDouble(),
                recent = windows.last().let { w -> w.requests.toDouble().takeIf { w.requests > 0 } },
            ),
            measure(
                Id.CACHE,
                windows.map { it.cachedShare },
                whole = whole.cachedShare,
                recent = windows.last().cachedShare,
            ),
            measure(
                Id.OUTPUT,
                windows.map { w -> w.completionTokens.toDouble().takeIf { w.requests > 0 } },
                // **Completion, not completion plus reasoning.** "Output tokens" is what the model
                // answered; the thinking is a separate cost the fourth cell is about. Adding them
                // here would make the third cell and the fourth disagree about the same number.
                whole = whole.completionTokens.toDouble(),
                recent = windows.last().let { w -> w.completionTokens.toDouble().takeIf { w.requests > 0 } },
            ),
            measure(
                Id.SHARE,
                windows.map { it.outputShare },
                whole = whole.outputShare,
                recent = windows.last().outputShare,
            ),
        )
    }

    /** The first minute boundary strictly after [ms], so an aligned instant stays put. */
    private fun ceilToMinute(ms: Long): Long =
        if (ms % MINUTE == 0L) ms else ms - Math.floorMod(ms, MINUTE) + MINUTE

    /** The share of all tokens in the request that came out of it. */
    private val RequestStats.Stats.outputShare: Double?
        get() {
            // **The operator's definition, verbatim**: `completion / (prompt + completion)`. Not
            // over reasoning, and not over cached - cached is already part of prompt, so adding it
            // would count the same tokens twice.
            val total = promptTokens + completionTokens
            return if (total == 0L) null else completionTokens.toDouble() / total
        }

    // ---- drawing ----------------------------------------------------------------------------

    /**
     * A number with its magnitude in letters: `999`, `9.99K`, `99.9K`, `999K`, `9.9M`, `99.9B`.
     *
     * **Three significant figures at every scale**, which is the operator's own list: the step from
     * `9.99K` to `99.9K` is one digit of precision, and keeping three digits means a number never
     * appears to jump in accuracy as it crosses a boundary. Below a thousand it is the integer,
     * because `1.00K` for a thousand tokens reads as a rounder number than it is.
     */
    fun compact(value: Double): String {
        val v = if (value.isFinite()) value else return "—"
        val sign = if (v < 0) "-" else ""
        val n = abs(v)
        return sign + when {
            n < 1_000 -> String.format(Locale.UK, "%.0f", n)
            n < 10_000 -> String.format(Locale.UK, "%.2fK", n / 1_000)
            n < 100_000 -> String.format(Locale.UK, "%.1fK", n / 1_000)
            n < 1_000_000 -> String.format(Locale.UK, "%.0fK", n / 1_000)
            n < 10_000_000 -> String.format(Locale.UK, "%.2fM", n / 1_000_000)
            n < 100_000_000 -> String.format(Locale.UK, "%.1fM", n / 1_000_000)
            n < 1_000_000_000 -> String.format(Locale.UK, "%.0fM", n / 1_000_000)
            n < 10_000_000_000 -> String.format(Locale.UK, "%.2fB", n / 1_000_000_000)
            n < 100_000_000_000 -> String.format(Locale.UK, "%.1fB", n / 1_000_000_000)
            else -> String.format(Locale.UK, "%.0fB", n / 1_000_000_000)
        }
    }

    /** The recent value, in the shape its cell shows it. */
    fun format(id: Id, value: Double?): String = when {
        value == null -> "—"
        // **A count, not a price.** `%.2f$` used to be here, and the `$` was a currency; a request
        // count is a whole number and a unit-less one, because "12.00 requests" is a figure nobody
        // writes.
        id == Id.REQUESTS -> String.format(Locale.UK, "%.0f", value)
        id == Id.OUTPUT -> compact(value)
        // **Two decimals, matching the statistics screen's own cache share.** 97.10% and 97.05% are
        // different facts about how well the cache is working, and one decimal hides the difference.
        else -> String.format(Locale.UK, "%.2f%%", value * 100)
    }

    /**
     * The badge: how far the recent value is from the period's, as a percentage.
     *
     * **Capped at `>+99.99%` and `<-100%` rather than printing a huge number**, because beyond that
     * the exact figure is not a fact anybody acts on - a value twenty times its baseline is "much
     * more", and `+1900.00%` invites arithmetic that means nothing. The lower cap is `-100%` because
     * that is the floor of the scale: nothing can fall further than all of it.
     */
    fun badge(change: Double?): String {
        if (change == null) return "—"
        val pct = change * 100
        return when {
            pct > 99.99 -> ">+99.99%"
            pct <= -100.0 -> "-100%"
            pct >= 0 -> String.format(Locale.UK, "+%.2f%%", pct)
            else -> String.format(Locale.UK, "%.2f%%", pct)
        }
    }
}
