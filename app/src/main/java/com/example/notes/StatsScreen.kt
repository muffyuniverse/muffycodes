package com.example.notes

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * What the model cost, summed from the request logs.
 *
 * **The shape of the table is the decision this screen is.** A log row is a response; a person's
 * question is "what did an hour cost, and was it the agent or the keepalive". So time runs across
 * the top and metrics run down the side — the opposite of a log table, and for two reasons:
 *
 * - **On a phone, a wide table is a table you cannot read.** Seven metric columns do not fit in
 *   320dp, and scrolling sideways to reach the cost column takes the row's own name off the screen.
 *   With metrics as rows, every number sits beside the words that say what it is.
 * - **Time across is the shape of a question about time.** "The cost went up after nine" is a
 *   left-to-right statement, and a table whose columns are hours says it without being read twice.
 *
 * **Turns and pings are separate rows, and that is not decoration.** The keepalive fires every four
 * minutes while the agent waits, so on an idle day it is roughly 360 responses and about ninety per
 * cent of the spend — while producing almost no output. A table that summed the two would be a
 * table about the keepalive that looked like a table about the agent, and every ratio on it (cost
 * per request especially) would describe a blend of two things that cost an order of magnitude
 * apart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    accountDir: java.io.File,
    onBack: () -> Unit,
    now: Long = System.currentTimeMillis(),
    /**
     * The device's clock, **read when a preset is tapped** - not when the screen opened.
     *
     * A parameter rather than a direct `System.currentTimeMillis()` so a test can pin it: the
     * default is the real clock, which is what the app wants, and a test passes a function that
     * returns its own fixture instant. Without this every screen test would assert against whatever
     * day CI happens to run on.
     */
    clockNow: () -> Long = { System.currentTimeMillis() },
    /** Reads the history off the main thread. Null in tests, which open a temp directory directly. */
    readRows: (((RequestDb.History) -> Unit) -> Unit)? = null,
    /**
     * Opens the report window.
     *
     * **Here because this is a screen a person reads figures off**, and a figure that looks wrong is
     * exactly when somebody wants to say so - with the interval they were looking at already in the
     * log behind them. Every other top bar in the app carries the button, so its absence here was a
     * gap rather than a decision. Report: "add debug icon to stats menu as well".
     */
    onReport: () -> Unit = {},
) {
    /**
     * **The interval, as two picks.** The operator's revision: *"we need 2 pickers for starting and
     * ending, and just 1 result column for the selected interval with per hour granularity. and no
     * checkboxes."*
     *
     * **Two pickers, because the question is an interval.** Every earlier design here tried to make
     * one instant carry the meaning - five nested spans, checkboxes to choose among them, an axis
     * control - and each needed a rule for where a column started and stopped. Two pickers remove
     * the question: the person states the interval, which is the thing they actually know.
     *
     * The default is **clock "1 day"**, autoselected: today from midnight, ending at the moment the
     * screen opened. See the preset rows below.
     */
    // **`now` is read ONCE, here.** It is a default parameter - `System.currentTimeMillis()` - so it
    // is a *new value on every recomposition*, and keying anything on it would reset the interval
    // every time the screen redrew. `remember(Unit)` freezes the opening instant for the life of this
    // screen instance; leaving and re-entering composes a new one, which is "every new opening".
    val openedAt = remember { now }

    // **The history itself, opened once and closed on the way out.** A `History` holds a database
    // handle rather than a list of rows, because the pickers move: a total per draw from a list
    // would have to re-scan every row, and a total per draw from SQL is one indexed range query.
    var history by remember(accountDir) { mutableStateOf<RequestDb.History?>(null) }
    var loaded by remember(accountDir) { mutableStateOf(readRows == null) }
    LaunchedEffect(accountDir, readRows) {
        if (readRows != null) {
            readRows { found ->
                history = found
                loaded = true
            }
        }
    }

    /**
     * **Which preset is chosen, and from which row.**
     *
     * Null means "neither" - the pickers were used, so the interval is hand-set and no chip is lit.
     * At most one of the two is non-null at a time, which is what "only one selectable across both
     * rows" means in the type rather than in a rule somebody has to remember.
     */
    var clockPick by remember { mutableStateOf<RequestStats.Preset?>(RequestStats.Preset.DAY_1) }
    var rollingPick by remember { mutableStateOf<RequestStats.Preset?>(null) }

    /**
     * **The default, computed before the first frame rather than after it.**
     *
     * Clock "1 day": today from midnight, ending at the opening moment. Done here rather than only
     * in the `LaunchedEffect` below because an effect runs *after* composition - so the first frame
     * would draw a zero-length interval, and anything reading the screen before the effect settled
     * (a test, a screenshot, a person with slow eyes) would see 00:00 to 00:00 and the wrong figures.
     */
    val defaultSpan = remember(openedAt) {
        RequestStats.Preset.DAY_1.clockAt(openedAt)
    }

    /** What the pickers say, and what the table is showing. Seeded from [defaultSpan]. */
    var from by remember { mutableStateOf(defaultSpan.fromMs) }
    var to by remember { mutableStateOf(defaultSpan.toMs) }
    var generated by remember { mutableStateOf(defaultSpan) }

    /** True when the picks have moved since the last Generate, so the button can say so. */
    val pending = remember(from, to, generated) {
        RequestStats.pending(from, to, generated)
    }

    /**
     * Applies a preset **from the device's clock, now**, and regenerates.
     *
     * The operator's correction: *"preset the moment it is chosen should take current time from
     * device and recalculate"* - a chip reads the clock when it is tapped, not when the screen
     * opened. A screen left open for an hour and then asked for "1 hour" would otherwise answer
     * about the hour before it was opened.
     *
     * **Tapping a preset is itself a request for figures**, which is why it does not wait for the
     * Generate button - that button exists only for the case a preset cannot cover, an interval set
     * by hand.
     *
     * **Setting one row clears the other in the same assignment**, so there is no state in which two
     * chips are lit and therefore no rule for what two lit chips would mean.
     */
    fun applyPreset(preset: RequestStats.Preset, clock: Boolean) {
        // **The clock at the moment of the tap**, which is the operator's rule: a screen left open
        // for an hour then asked for "1 hour" must answer about the last hour, not the hour before
        // it was opened.
        val at = clockNow()
        val span = if (clock) preset.clockAt(at, oldest = history?.oldest)
        else preset.intervalAt(at, oldest = history?.oldest)
        if (clock) { clockPick = preset; rollingPick = null }
        else { rollingPick = preset; clockPick = null }
        from = span.fromMs
        to = span.toMs
        generated = span
    }

    /** Which end's **date** is being picked: null when neither, `true` for From, `false` for To. */
    var editingDate by remember { mutableStateOf<Boolean?>(null) }
    /** Which end's **time** is being picked. Never both at once. */
    var editingTimeOf by remember { mutableStateOf<Boolean?>(null) }

    /** The instant at one end, so the two dialogs read the same value without duplicating it. */
    fun valueOf(start: Boolean) = if (start) from else to
    fun dateOf(start: Boolean): java.time.LocalDate = java.time.Instant.ofEpochMilli(valueOf(start))
        .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    fun timeOf(start: Boolean): java.time.LocalTime = java.time.Instant.ofEpochMilli(valueOf(start))
        .atZone(java.time.ZoneId.systemDefault()).toLocalTime()

    /**
     * Rebuilds one end from a date and a time, **keeping the half the person did not touch** - the
     * two dialogs are independent, so picking a date must not reset the time and vice versa.
     *
     * **A hand-set interval is not any preset**, so both rows deselect. A lit chip beside figures
     * for a different interval is the one thing this screen exists to prevent.
     */
    fun setEnd(start: Boolean?, day: java.time.LocalDate, time: java.time.LocalTime) {
        if (start == null) return
        val at = day.atTime(time).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (start) from = at else to = at
        clockPick = null
        rollingPick = null
    }

    // **The dial's own state, rebuilt for whichever end is being edited.**
    //
    // `rememberTimePickerState` takes no key, so on its own it keeps the FIRST seeding for the life
    // of the screen - open To after From and the dial would show From's time, and confirming would
    // silently copy it onto To. `key` is what discards and rebuilds the state per end.
    val timeState = key(editingTimeOf) {
        val t = java.time.Instant.ofEpochMilli(editingTimeOf?.let { valueOf(it) } ?: to)
            .atZone(java.time.ZoneId.systemDefault()).toLocalTime()
        rememberTimePickerState(initialHour = t.hour, initialMinute = t.minute, is24Hour = true)
    }
    // **The calendar's state, keyed the same way**, because it keeps its own selection too - seeding
    // the constructor alone would show the first date picked for the rest of the session.
    val picker = key(editingDate) {
        rememberDatePickerState(
            initialSelectedDateMillis = editingDate?.let { valueOf(it) } ?: to,
        )
    }

    // **The screen reads the history and never closes it.** Whoever opens a handle closes it, and
    // this screen does not open one - [readRows] does. An earlier version closed it in a
    // `DisposableEffect`, which was wrong twice over:
    //
    //  * `readRows` is a lambda, so its identity changes on every re-composition. That re-runs
    //    `LaunchedEffect`, which opens a *new* handle, and the effect's `onDispose` then ran against
    //    a handle the screen had already replaced - the next query hit "attempt to re-open an
    //    already-closed object", thrown from a line with no visible connection to the cause.
    //  * A screen is not the right owner anyway. It can be re-composed, previewed, or drawn twice in
    //    one test; the view model that hands out the handle knows when it is finished with it.
    //
    // The rule is the ordinary one: **the thing that opens a resource closes it.**
    // **One figure, over the interval that was last generated.** No list, no spans, no filtering:
    // the checkbox row is gone because there is one column.
    //
    // **`generated`, not `from`/`to`.** Moving a picker must not move the numbers - that is what
    // the Generate button is for, and without it a person comparing two intervals could never be
    // sure which one they were reading while they set up the second.
    val span = generated
    val totals = remember(history, span) {
        history?.totals(span.fromMs, span.toMs) ?: RequestStats.Totals()
    }
    // **Whether the history reaches this far**, which is not the same as "nothing happened". The two
    // read identically in an empty table and mean different things.
    val oldest = remember(history) { history?.oldest }
    val covered = remember(span, oldest) { oldest != null && span.fromMs >= oldest }
    // **The period immediately before it**, for the comparison column. The operator's definition:
    // the same length, ending where the current interval starts - so a hand-set interval gets a
    // baseline too, which is the whole reason it is computed from the span rather than from a preset.
    //
    // **It is not shown when the history does not reach it.** A previous column of dashes beside a
    // real current column reads as "traffic collapsed", when the truth is that the log begins after
    // the previous period did. `oldest` decides that, and the column is dropped entirely.
    val previousSpan = remember(span) { RequestStats.previousOf(span) }
    val previousTotals = remember(history, previousSpan) {
        history?.totals(previousSpan.fromMs, previousSpan.toMs) ?: RequestStats.Totals()
    }
    val showPrevious = remember(previousSpan, oldest) {
        oldest != null && previousSpan.fromMs >= oldest
    }


    // **One read, two uses.** The totals are zero in two very different situations - nothing was
    // asked, or nothing was recorded yet - and the caption has to tell them apart.
    //
    // **`totals` is a key, not just `history`.** The history handle arrives before the totals are
    // computed for a given interval, so keying on the handle alone would freeze the first interval's
    // answer and leave the caption claiming "no log yet" after a picker moved onto real data.
    val anyAtAll = remember(history, totals) {
        val h = history
        h != null && (h.oldest != null || totals.total.requests > 0)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                // **"Account statistics", not "What it cost".** The screen reports tokens, requests
                // and time as well as money, so a title about cost described one of its rows. The
                // name is also what the button that opens it is called, which is what a person
                // carries between the two screens.
                title = { Text("Account statistics", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = { ReportButton(onReport) },
            )
        },
    ) { padding ->
        // **Selectable, because figures are what a person quotes.** The screen reports what an
        // account spent; the reason somebody opens it is usually to tell someone else, and retyping
        // a number from a screen is how it gets typed wrong. Around the list rather than per item:
        // the rows are few and all of them are composed, so a selection can span the table.
        SelectionContainer {
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                // **Two ends, one line each, each split into a date and a time.** Start above end,
                // because that is the order the interval is read in.
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    IntervalRow(
                        label = "From",
                        day = dateOf(true),
                        time = timeOf(true),
                        onOpenDate = {
                            Trace.tap("stats from", "date")
                            editingDate = true
                        },
                        onOpenTime = {
                            Trace.tap("stats from", "time")
                            editingTimeOf = true
                        },
                    )
                    IntervalRow(
                        label = "To",
                        day = dateOf(false),
                        time = timeOf(false),
                        onOpenDate = {
                            Trace.tap("stats to", "date")
                            editingDate = false
                        },
                        onOpenTime = {
                            Trace.tap("stats to", "time")
                            editingTimeOf = false
                        },
                    )

                    // **Two rows of one-tap intervals, and exactly one is ever chosen.** The upper row
                    // starts on the grid - zero minutes, zero hour, zero day of the month - so "1 day"
                    // there means today from midnight. The lower row does not snap: "1 day" is the
                    // last twenty-four hours. Both end at the moment the chip was tapped, which is
                    // what makes them comparable rather than merely similar.
                    PresetRow(
                        label = "Clock",
                        selected = clockPick,
                        onPick = { applyPreset(it, clock = true) },
                    )
                    PresetRow(
                        label = "Rolling",
                        selected = rollingPick,
                        onPick = { applyPreset(it, clock = false) },
                    )
                    Text(
                        // **What the interval is, and whether the log reaches it.** Two facts,
                        // because they answer different questions: "what am I looking at" and "is
                        // an empty table because nothing happened, or because nothing was recorded
                        // yet". The second is the one that saves a person from thinking the screen
                        // is broken.
                        StatsFormat.span(span.minutes) + " · " + when {
                            // **Not "no log yet" while the read is still running.** Those are two
                            // different facts and only one of them is about the log.
                            !loaded -> "reading"
                            // **Two different empty screens, told apart.** "Nothing was asked"
                            // and "nothing was recorded yet" look identical in a table of zeroes,
                            // and only the second means the screen is not showing you history.
                            !anyAtAll -> "no log yet"
                            covered -> "from the log"
                            oldest == null -> "no log yet"
                            else -> "the log starts " + StatsFormat.instant(oldest)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp, bottom = 0.dp),
                    )
                    if (showPrevious) {
                        // **The previous window, named in full.** Its rule is unusual enough that a
                        // person who knows this code asked about it: it is the same LENGTH, ending
                        // where the current interval starts - not the whole of yesterday. Without
                        // this line the reader has to infer the window from the arithmetic, and the
                        // natural guess ("all of yesterday") is the one that would report a 46% drop
                        // caused by the day not being over yet.
                        //
                        // **Both ends, because a length alone does not locate it.** "12 hours 52
                        // minutes" beside "12 hours 52 minutes" still leaves the reader guessing
                        // whether it is the period before this one or yesterday's same span.
                        Text(
                            "before: " + StatsFormat.instantMinute(previousSpan.fromMs) +
                                " to " + StatsFormat.instantMinute(previousSpan.toMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                    }
                    // **Generate, because the table is a query and not a live view.** The figures
                    // are computed on press rather than on every stepper tick: a minute stepper can
                    // move sixty times on the way to a time, and re-reading the history on each of
                    // those is work nobody asked for and numbers that move under a thumb. It also
                    // makes the interval you are reading unambiguous while you set up the next one.
                    //
                    // **Enabled only when the picks have actually changed**, so the button says
                    // whether pressing it would do anything. A permanently-enabled Generate is a
                    // control whose effect a person has to guess at.
                    Button(
                        onClick = {
                            Trace.tap("stats generate", StatsFormat.instantMinute(to))
                            generated = RequestStats.interval(from, to)
                        },
                        enabled = pending,
                        modifier = Modifier.padding(bottom = 8.dp),
                    ) {
                        Text(if (pending) "Generate" else "Generated")
                    }
                }
            }

            // **Two dialogs, one job each.** A calendar is not a time picker: squeezing them into one
            // window is what forced the time into the calendar's title row, which was only ever a
            // workaround for the row falling off the bottom of a short screen. The row already shows
            // the date and the time as two facts, so two buttons matches what the person is looking
            // at.
            if (editingDate != null) {
                item {
                    DatePickerDialog(
                        onDismissRequest = { editingDate = null },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    val which = editingDate
                                    picker.selectedDateMillis?.let { utcMidnight ->
                                        // **The picker returns UTC midnight of the chosen day.** Used
                                        // as-is, a phone west of UTC would land on the previous
                                        // local day, so the UTC date it names is read first and then
                                        // rebuilt in the device's zone with the time already chosen.
                                        val day = java.time.Instant.ofEpochMilli(utcMidnight)
                                            .atZone(java.time.ZoneOffset.UTC)
                                            .toLocalDate()
                                        if (which != null) setEnd(which, day, timeOf(which))
                                    }
                                    editingDate = null
                                },
                            ) { Text("Show") }
                        },
                        dismissButton = {
                            TextButton(onClick = { editingDate = null }) { Text("Cancel") }
                        },
                    ) {
                        DatePicker(
                            state = picker,
                            // **The headline goes, because it repeats the navigator.** "October
                            // 28, 2026" is the same fact as the highlighted 28 and the "October
                            // 2026" heading directly beneath it.
                            headline = null,
                        )
                    }
                }
            }

            if (editingTimeOf != null) {
                item {
                    // **Material's own dial.** The operator asked for "a dial where you can choose
                    // hours and minutes", which is exactly `TimePicker`: drag the hour hand, then
                    // the minute hand. It needs a whole window, which is what the previous design
                    // could not give it - two attempts to put time controls *under* a calendar ended
                    // with them below the bottom of the dialog.
                    TimeDialog(
                        onDismiss = { editingTimeOf = null },
                        onConfirm = {
                            val which = editingTimeOf
                            val t = timeState
                            // **The date is kept, only the time changes**, so opening the dial and
                            // confirming cannot silently move the day.
                            if (which != null) setEnd(which, dateOf(which), java.time.LocalTime.of(t.hour, t.minute))
                            editingTimeOf = null
                        },
                    ) {
                        TimePicker(state = timeState)
                    }
                }
            }

            if (loaded && !anyAtAll) {
                item {
                    // **An empty table is a sentence, not a grid of zeroes.** A person opening this
                    // before the agent has run should be told why it is empty rather than shown a
                    // screen of dashes that reads like a fault.
                    Text(
                        "Nothing has been asked of the model yet, so there is nothing to count. " +
                            "Each response is written down as it happens, in the system project's " +
                            "requests folder - one row in a database, so a year of history costs " +
                            "a sum per hour rather than a re-reading of every response.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            } else {
                // **Three columns: previous, current, and how they differ.** The operator's
                // revision: *"metrics, prev interval, current interval, relative change"*. A figure
                // on its own says nothing about whether it is good - "3h 14m" is only informative
                // beside what the period before it cost.
                //
                // **The previous column disappears when the log does not reach it**, because a
                // column of dashes beside a real one reads as "traffic collapsed" when the truth is
                // that the history simply begins later.
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // **One heading, because the caption above is not one.** The columns have
                        // two different values now, so "which is which" is a real question and the
                        // answer has to be on screen. The headings are right-aligned to match the
                        // figures beneath them.
                        ColumnHeading(showPrevious = showPrevious, previousSpan = previousSpan)
                        val t = totals.total
                        val p = previousTotals.total
                        CompareRow(
                            "requests",
                            previous = StatsFormat.count(p.requests).takeIf { showPrevious },
                            current = StatsFormat.count(t.requests),
                            change = StatsFormat.change(
                                RequestStats.change(p.requests.toDouble(), t.requests.toDouble())
                                    .takeIf { showPrevious },
                            ),
                        )
                        CompareRow(
                            "input",
                            previous = StatsFormat.tokens(p.promptTokens).takeIf { showPrevious },
                            current = StatsFormat.tokens(t.promptTokens),
                            change = StatsFormat.change(
                                RequestStats.change(p.promptTokens.toDouble(), t.promptTokens.toDouble())
                                    .takeIf { showPrevious },
                            ),
                        )
                        // **The shares carry no change column**, deliberately. A cache share moving
                        // from 95.6% to 95.9% is +0.3 percentage points, which is not a relative
                        // change and would read as +0.3% - a number that is both wrong and
                        // reassuring. The absolute beside it already moved.
                        CompareRow(
                            "  · cached",
                            previous = StatsFormat.absoluteAndShare(
                                StatsFormat.tokens(p.cachedTokens), p.cachedShare,
                            ).takeIf { showPrevious },
                            current = StatsFormat.absoluteAndShare(
                                StatsFormat.tokens(t.cachedTokens), t.cachedShare,
                            ),
                            change = StatsFormat.NONE,
                            muted = true,
                        )
                        CompareRow(
                            "output",
                            previous = StatsFormat.tokens(p.completionTokens).takeIf { showPrevious },
                            current = StatsFormat.tokens(t.completionTokens),
                            change = StatsFormat.change(
                                RequestStats.change(
                                    p.completionTokens.toDouble(), t.completionTokens.toDouble(),
                                ).takeIf { showPrevious },
                            ),
                        )
                        CompareRow(
                            "  · thinking",
                            previous = StatsFormat.absoluteAndShare(
                                StatsFormat.tokens(p.reasoningTokens), p.thinkingShare,
                            ).takeIf { showPrevious },
                            current = StatsFormat.absoluteAndShare(
                                StatsFormat.tokens(t.reasoningTokens), t.thinkingShare,
                            ),
                            change = StatsFormat.NONE,
                            muted = true,
                        )
                        CompareRow(
                            "cost $",
                            previous = StatsFormat.cost(
                                p.cost.takeIf { p.costReported > 0 },
                            ).takeIf { showPrevious },
                            current = StatsFormat.cost(t.cost.takeIf { t.costReported > 0 }),
                            change = StatsFormat.change(
                                RequestStats.change(
                                    p.cost.takeIf { p.costReported > 0 },
                                    t.cost.takeIf { t.costReported > 0 },
                                ).takeIf { showPrevious },
                            ),
                        )
                        CompareRow(
                            "per request",
                            previous = StatsFormat.perRequest(p.perRequest).takeIf { showPrevious },
                            current = StatsFormat.perRequest(t.perRequest),
                            change = StatsFormat.change(
                                RequestStats.change(p.perRequest, t.perRequest)
                                    .takeIf { showPrevious },
                            ),
                        )
                        // **The two "per M" rows are gone, on the operator's instruction.** They
                        // were derived from what was charged, so they answered "what did this
                        // interval cost per million tokens" - a number nobody acts on, and a
                        // restatement of the rates the provider already lists. The rates that
                        // matter are the *current* endpoint's, and they belong in the agent block
                        // where the endpoint is chosen. Report: "remove this cost rows, provider
                        // already lists the prices, better show in agent block in settings what are
                        // the charge rates for selected endpoint: input, output, cache".
                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    }
                }
                // **Answers that stopped, only when there were any.** They are billed but report no
                // totals, so they cannot be folded into the rows above without claiming a cost of
                // zero for them; they get their own short block.
                if (totals.stalls.requests > 0) {
                    item {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                // **Singular when it is one.** "1 requests" is the kind of thing a
                                // screenshot catches and an assertion does not: the number was
                                // right, the sentence around it was wrong.
                                "Answers that stopped  ·  " +
                                    StatsFormat.count(totals.stalls.requests) +
                                    if (totals.stalls.requests == 1) " request" else " requests",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                            )
                            // **Characters, and it says so.** A stalled answer has no `usage`, so
                            // tokens are unknown and only arrived characters are known - which
                            // meant this block counted characters while the table above counts
                            // tokens, in the same format. See `StatsFormat.characters`.
                            MetricRow("written", StatsFormat.characters(totals.stalls.saidChars))
                            MetricRow("thought", StatsFormat.characters(totals.stalls.thinkingChars))
                            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                        }
                    }
                }
                item {
                    Text(
                        // The one thing a reader has to know to read the shares correctly, said
                        // once rather than in every row.
                        "Cached is part of input, not a column beside it. A dash is a number that " +
                            "does not exist — an interval with no requests has no average, and that " +
                            "is not the same as zero. A stopped answer was billed but never " +
                            "reported its totals, so its cost cannot be shown. An interval that " +
                            "reaches into a compacted hour counts that hour whole.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
        }
        }
    }
}


/**
 * One end of the interval, as **two buttons**: `From   28 Oct 2026   14:31`.
 *
 * **The date and the time are separate taps because they are separate questions.** A calendar
 * answers one and a dial answers the other, and neither fits inside the other's window - which is
 * exactly why the previous design squeezed the time into the calendar's title row.
 *
 * **Two buttons rather than one**, so a person who only wants to change the hour does not have to
 * walk through a calendar to do it.
 */
@Composable
private fun IntervalRow(
    label: String,
    day: java.time.LocalDate,
    time: java.time.LocalTime,
    onOpenDate: () -> Unit,
    onOpenTime: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp),
        )
        // **A fixed pattern, because the scenario pictures are compared across runs.** A localized
        // date would make the screenshots depend on the JVM's default locale, which CI and a phone do
        // not always share.
        TextButton(onClick = onOpenDate) {
            Text(day.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy")))
        }
        TextButton(onClick = onOpenTime) {
            // **To the minute**, and for `To` this is a boundary: 20:00 means the interval stops
            // there, and 19:23 means it stops at 19:23.
            Text(time.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")))
        }
    }
}

/**
 * One row of preset intervals, scrolled sideways.
 *
 * **Horizontally scrollable because there are nine of them** and a phone shows four. Scrolling
 * rather than wrapping: a wrapped row changes height as the selection moves, which in a `LazyColumn`
 * makes the table below jump under the thumb.
 *
 * **One selection across both rows**, enforced by `applyPreset` clearing the other row's pick - two
 * lit chips would name two intervals and the figures can only be one.
 */
@Composable
private fun PresetRow(
    label: String,
    selected: RequestStats.Preset?,
    onPick: (RequestStats.Preset) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp),
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // **The order the operator listed**, with `total` last in both rows because it is the
            // widest question rather than another length.
            for (preset in RequestStats.Preset.entries) {
                // **`FilterChip`, not a `Button`.** A chip is Material's control for "one of these",
                // and its selected state is drawn rather than described - a plain button would read
                // as "do this" instead of "this is chosen".
                //
                // **Each chip says which row it belongs to**, because "15 min" is a control in both
                // rows and the two do different things: one starts at the hour, the other starts
                // fifteen minutes ago. A screen reader announcing "15 min, button" twice leaves a
                // person no way to choose between them, and a test cannot tell them apart either -
                // which is how this was found.
                FilterChip(
                    selected = preset == selected,
                    onClick = { onPick(preset) },
                    label = { Text(preset.label, maxLines = 1) },
                    modifier = Modifier.semantics {
                        contentDescription = "$label ${preset.label}"
                    },
                )
            }
        }
    }
}

/**
 * A dialog holding Material's dial, **because Material does not ship one.**
 *
 * `DatePickerDialog` exists; a time equivalent does not, so this wraps `TimePicker` in a `Dialog`
 * with the same two buttons. Kept here rather than in a shared file because it exists for one screen
 * and one reason - the dial needs a whole window, which is the thing the previous design could not
 * give it.
 */
@Composable
private fun TimeDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    content: @Composable () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                content()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = onConfirm) { Text("Show") }
                }
            }
        }
    }
}

/**
 * The three column headings, right-aligned over the figures they name.
 *
 * **A heading is needed now and was not before.** With one column the caption above the table was
 * already its heading - "15 hours · from the log" said what the figures were. Two different values
 * per row make "which is which" a real question, and a table where a person has to guess the answer
 * is a table that will be misread.
 *
 * **No heading over the change column**, because `+18.2%` is unambiguous once the two columns beside
 * it are named.
 */
@Composable
private fun ColumnHeading(showPrevious: Boolean, previousSpan: RequestStats.Interval) {
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        // The name column, empty here so the headings line up with the figures below.
        Spacer(modifier = Modifier.weight(1f))
        if (showPrevious) {
            Text(
                // **"before", not "previous".** The word alone does not say *what* the period is,
                // and the honest answer is unusual enough to be worth stating: it is the same
                // length, ending where this one starts. Not the whole of yesterday - comparing
                // twelve hours against twenty-four would report a 46% drop caused by nothing.
                "before",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.width(PREVIOUS_WIDTH),
            )
        }
        Text(
            "now",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(CURRENT_WIDTH),
        )
        Text(
            "",
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(CHANGE_WIDTH),
        )
    }
}

/**
 * One metric across three columns: **previous, current, and the relative change.**
 *
 * **A null previous renders a dash, not a blank.** A blank cell reads as "nothing was counted",
 * while a dash says "there is no such figure" - the same distinction the averages already make.
 */
@Composable
private fun CompareRow(
    name: String,
    previous: String?,
    current: String,
    change: String,
    muted: Boolean = false,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (previous != null) {
            Text(
                previous,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.width(PREVIOUS_WIDTH),
            )
        }
        Text(
            current,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(CURRENT_WIDTH),
        )
        Text(
            change,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(CHANGE_WIDTH),
        )
    }
}

/**
 * One metric, as a name and its number.
 *
 * **One cell, not a row of them.** There is a single column now, so the transposed table and its
 * shared horizontal scroll are gone with the fifth column: this is a label on the left and a figure
 * on the right, which is what a table of one column always was.
 */
@Composable
private fun MetricRow(name: String, cell: String, muted: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            name,
            style = MaterialTheme.typography.bodyMedium,
            color = if (muted) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            cell,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            color = if (muted) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
        )
    }
}



/**
 * The three figure columns, in dp.
 *
 * **Fixed rather than weighted**, so the columns line up between rows even when one cell is a dash
 * and another is `1.35393` - a weighted row would put each row's decimal point in a different place,
 * which is the one thing a table of numbers must never do.
 *
 * The change column is narrower than the others because `+18.2%` is never longer than `1.35393`
 * beside a token count, and on a 360dp phone every dp of the name column is worth having.
 */
private val PREVIOUS_WIDTH = 76.dp
private val CURRENT_WIDTH = 84.dp
private val CHANGE_WIDTH = 64.dp
