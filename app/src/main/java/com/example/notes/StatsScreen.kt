package com.example.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
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
    /** Reads the history off the main thread. Null in tests, which open a temp directory directly. */
    readRows: (((RequestDb.History) -> Unit) -> Unit)? = null,
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
     * The default is the current day, midnight to now, because that is the interval a person
     * opening a cost screen most often wants and it is bounded.
     */
    // **The picks, and the interval that was actually generated from them.** Two pairs rather than
    // one, because there is a Generate button: what the pickers say and what the figures describe
    // are allowed to differ, and that difference is the whole point of the button - you can set up
    // an interval without the table moving under your hands on every stepper press.
    var from by remember { mutableStateOf(dayStart(now)) }
    // **The end of the minute now is in**, not the current instant and not the current minute: an
    // end is where the interval STOPS, so "from midnight to 14:31" covers the minute 14:30.
    //
    // `interval(now, now)` alone is NOT that answer at minute granularity: with `now` exactly on a
    // minute boundary it returns that boundary as the end, which is a zero-length interval - the
    // screen would open showing nothing on the one tick where the clock happens to be exact. And
    // `interval(now, now + 60_000)` overshoots to two minutes when `now` has seconds on it.
    var to by remember { mutableStateOf(RequestStats.endOfMinute(now)) }
    // **What the table is showing.** Starts equal to the picks, so the screen is useful before
    // anybody presses anything - a table that said "press Generate" on first open would be a
    // screen that does nothing when opened.
    var generated by remember { mutableStateOf(RequestStats.interval(from, to)) }
    /** True when the picks have moved since the last Generate, so the button can say so. */
    val pending = remember(from, to, generated) {
        RequestStats.interval(from, to) != generated
    }
    /** Which picker is open: null when neither is, `true` for the start, `false` for the end. */
    var editingStart by remember { mutableStateOf<Boolean?>(null) }

    // The dialog's own state, reset from the pick it is editing every time it opens - a picker that
    // kept its last selection would open a day away from the interval it describes.
    val pickingValue = if (editingStart == true) from else to
    val picker = rememberDatePickerState(initialSelectedDateMillis = pickingValue)
    val editingTime = java.time.Instant.ofEpochMilli(pickingValue)
        .atZone(java.time.ZoneId.systemDefault()).toLocalTime()
    // **Hour and minute, because the table can answer both.** The log keeps a minute table, so a
    // figure over "19:23 to 19:38" is a real answer rather than a rounding of one - which is what
    // made hour-only right while the finest summary was an hour.
    var pickedHour by remember { mutableStateOf(editingTime.hour) }
    var pickedMinute by remember { mutableStateOf(editingTime.minute) }

    // **Read when the screen opens, not while it is open.** The log is appended to on every
    // response, including the keepalives sent while nobody is looking, so a table that watched the
    // files would be re-reading them while the agent wrote to them — and would move under the
    // reader's eyes. What the report asked for was a table counted on opening, which is also the
    // only version whose numbers are stable enough to compare.
    //
    // **On the worker, never in composition.** This was a `remember { read(accountDir) }`, which
    // parses the whole log on the main thread before the first frame: fine for a few hundred rows,
    // a frozen window for a year of them. [readRows] hands the work to the view model's worker and
    // posts the result back; when it is null the caller has given us rows already (tests).
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
                title = { Text("What it cost", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                // **Two pickers, one line each.** Start above end, because that is the order the
                // interval is read in - and because a vertical pair fits "From 1 Oct 09:00" beside
                // "To 31 Oct 17:00" without the labels fighting for width on a phone.
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    IntervalRow(
                        label = "From",
                        at = from,
                        onOpen = {
                            Trace.tap("stats from", "open")
                            picker.selectedDateMillis = from
                            java.time.Instant.ofEpochMilli(from)
                                .atZone(java.time.ZoneId.systemDefault()).toLocalTime()
                                .let { pickedHour = it.hour; pickedMinute = it.minute }
                            editingStart = true
                        },
                    )
                    IntervalRow(
                        label = "To",
                        at = to,
                        onOpen = {
                            Trace.tap("stats to", "open")
                            picker.selectedDateMillis = to
                            java.time.Instant.ofEpochMilli(to)
                                .atZone(java.time.ZoneId.systemDefault()).toLocalTime()
                                .let { pickedHour = it.hour; pickedMinute = it.minute }
                            editingStart = false
                        },
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
                        modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
                    )
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

            if (editingStart != null) {
                item {
                    // **Material's own calendar, plus the time of day.** The operator asked for a
                    // standard Android picker; the platform's picks a DATE, so the hour and minute
                    // are added underneath rather than reimplementing a calendar to get them.
                    DatePickerDialog(
                        onDismissRequest = { editingStart = null },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    picker.selectedDateMillis?.let { utcMidnight ->
                                        // **The picker returns UTC midnight of the chosen day.** Used
                                        // as-is, a phone west of UTC would land on the previous local
                                        // day, so the UTC date it names is read first and then
                                        // rebuilt in the device's zone at the chosen time of day.
                                        //
                                        // `atZone(...).toInstant()` on a local date-time that does
                                        // not exist - the hour a zone springs forward - resolves
                                        // forward to the next real instant instead of throwing.
                                        val day = java.time.Instant.ofEpochMilli(utcMidnight)
                                            .atZone(java.time.ZoneOffset.UTC)
                                            .toLocalDate()
                                        // **On the minute exactly.** `from`/`to` are snapped again
                                        // by `interval()`: a start is floored to its minute and an
                                        // end is taken to the next boundary, so the interval always
                                        // covers whole minutes and never part of one.
                                        val chosen = day.atTime(pickedHour, pickedMinute)
                                            .atZone(java.time.ZoneId.systemDefault())
                                            .toInstant()
                                            .toEpochMilli()
                                        Trace.tap("stats interval", StatsFormat.instant(chosen))
                                        // **`interval()` puts them in order**, so dragging the
                                        // start past the end does not make the table vanish - it
                                        // swaps them, which is what the person meant.
                                        if (editingStart == true) from = chosen else to = chosen
                                    }
                                    editingStart = null
                                },
                            ) { Text("Show") }
                        },
                        dismissButton = {
                            TextButton(onClick = { editingStart = null }) { Text("Cancel") }
                        },
                    ) {
                        // **The hour is the calendar's own TITLE, and that is the whole fix.**
                        //
                        // Two attempts came before this one, and both were measured by looking at
                        // the picture rather than by a test:
                        //
                        //  1. The plain `DatePicker` filled a 320x470 dialog and the hour row sat
                        //     below the bottom of the screen - present in the tree, unreachable.
                        //  2. Dropping the `DatePicker`'s title and headline made room, and the
                        //     calendar simply grew into it. The hour row was still off the bottom.
                        //
                        // The lesson is that **a scrolling body cannot rescue a control the person
                        // has to find**: nothing on screen says there is one, and the part of a
                        // dialog a thumb reaches first is the top. So the hour is not a row under
                        // the calendar - it replaces the calendar's title, which Material pins above
                        // the grid and which therefore cannot be pushed off by anything.
                        //
                        // **`title` here is `DatePicker`'s, not the dialog's.** The dialog has no
                        // such parameter; the first version of this change passed it to
                        // `DatePickerDialog` and failed to compile, which is how the distinction was
                        // learned.
                        DatePicker(
                            state = picker,
                            title = {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 24.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text("At", style = MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.width(12.dp))
                                    TimeStepper(
                                        label = "hour",
                                        value = pickedHour,
                                        range = 0..23,
                                        onChange = { pickedHour = it },
                                    )
                                    // **The colon is the separator, not a fixed zero.** It was
                                    // `:00` while only the hour could be chosen; now that a minute
                                    // can be, a literal ":00" beside a stepper reading 37 would be
                                    // actively wrong.
                                    Text(":", style = MaterialTheme.typography.bodyMedium)
                                    Spacer(Modifier.width(4.dp))
                                    TimeStepper(
                                        label = "minute",
                                        value = pickedMinute,
                                        range = 0..59,
                                        onChange = { pickedMinute = it },
                                    )
                                }
                            },
                            // **The headline goes, because it repeats the navigator.** "October
                            // 28, 2026" is the same fact as the highlighted 28 and the "October
                            // 2026" heading directly beneath it, and on a short screen it is the
                            // difference between the calendar fitting and not.
                            headline = null,
                        )
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
                // **One column.** The operator's revision: "just 1 result column for the selected
                // interval with per hour granularity. and no checkboxes." The rows are the metrics
                // named in L28419, in the order they were named - the counts, the tokens with their
                // shares, then the cost and the two derived rates.
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // **No column heading, because the caption above already is one.** It
                        // reads "15 hours · from the log" and a header cell reading "15 hours"
                        // directly beneath it is the same fact twice - which a screenshot shows at
                        // once and no assertion would ever notice, since both are individually
                        // true.
                        MetricRow("requests", StatsFormat.count(totals.total.requests))
                        MetricRow("input", StatsFormat.tokens(totals.total.promptTokens))
                        MetricRow(
                            "  · cached",
                            StatsFormat.absoluteAndShare(
                                StatsFormat.tokens(totals.total.cachedTokens),
                                totals.total.cachedShare,
                            ),
                            muted = true,
                        )
                        MetricRow("output", StatsFormat.tokens(totals.total.completionTokens))
                        MetricRow(
                            "  · thinking",
                            StatsFormat.absoluteAndShare(
                                StatsFormat.tokens(totals.total.reasoningTokens),
                                totals.total.thinkingShare,
                            ),
                            muted = true,
                        )
                        MetricRow(
                            "cost $",
                            StatsFormat.cost(
                                totals.total.cost.takeIf { _ -> totals.total.costReported > 0 },
                            ),
                        )
                        MetricRow("per request", StatsFormat.perRequest(totals.total.perRequest))
                        MetricRow("per M in", StatsFormat.perMillion(totals.total.perMillionInput))
                        MetricRow("per M out", StatsFormat.perMillion(totals.total.perMillionOutput))
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

/**
 * Midnight at the start of the day that holds [at], in the device's zone.
 *
 * **`atStartOfDay(zone)` rather than `atTime(0, 0)`**: on the day a zone springs forward, midnight
 * does not exist, and asking for it by name lands somewhere else or throws.
 */
private fun dayStart(at: Long): Long = java.time.Instant.ofEpochMilli(at)
    .atZone(java.time.ZoneId.systemDefault())
    .toLocalDate()
    .atStartOfDay(java.time.ZoneId.systemDefault())
    .toInstant()
    .toEpochMilli()

/**
 * One end of the interval: `From  28 Oct 2026, 00:00`.
 *
 * **A button, not a field.** The date is picked from the platform's calendar and the time from two
 * steppers, so there is nothing to type and nothing to validate - a person who mistypes a date does
 * not exist here.
 */
@Composable
private fun IntervalRow(label: String, at: Long, onOpen: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(48.dp),
        )
        TextButton(onClick = onOpen) {
            // **To the hour**, and for `To` this is a boundary: 20:00 means the interval stops
            // there. Minutes would claim a precision the table does not have.
            Text(StatsFormat.instantMinute(at))
        }
    }
}

/**
 * One number a person sets, with a step either side: `− 12 +`.
 *
 * **Wrapping, not clamping.** At 59 the next minute is 0 and at 23 the next hour is 0, because that
 * is how a clock works and because a control that refuses the last press teaches nothing about which
 * press would have worked. It is also what lets 23:59 reach 00:00 without a special case.
 *
 * **Zero-padded and monospaced**, so the control does not change width as the number does; a button
 * that shifts sideways under the finger is one a person misses.
 */
@Composable
private fun TimeStepper(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    fun step(by: Int): Int = ((value + by - range.first).mod(range.last + 1 - range.first)) + range.first
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { Trace.tap("stats time", "$label-"); onChange(step(-1)) }) {
            Text("−")
        }
        Text(
            // **`Locale.UK`, not the device's.** A zero-padded two-digit number is diagnostic, and a
            // locale that substitutes other digits would make the two steppers disagree with each
            // other and with the column heading.
            String.format(java.util.Locale.UK, "%02d", value),
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
        )
        TextButton(onClick = { Trace.tap("stats time", "$label+"); onChange(step(1)) }) {
            Text("+")
        }
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


