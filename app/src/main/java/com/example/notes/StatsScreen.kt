package com.example.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    /** Reads the log off the main thread. Null in tests, which read a temp directory directly. */
    readRows: (((List<RequestStats.Row>) -> Unit) -> Unit)? = null,
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
    var from by remember { mutableStateOf(dayStart(now)) }
    // **The next hour boundary, not the current instant and not the current hour.** At 19:28 the
    // default end is 20:00, because an end is where the interval STOPS - so "from midnight to 20:00"
    // covers the hours 00 through 19. Storing the last millisecond of 19:00-20:00 here would label
    // the button 19:00 while the figures covered the whole of that hour, and the two would disagree.
    var to by remember { mutableStateOf(RequestStats.hourFloor(now) + 3_600_000L) }
    /** Which picker is open: null when neither is, `true` for the start, `false` for the end. */
    var editingStart by remember { mutableStateOf<Boolean?>(null) }

    // The dialog's own state, reset from the pick it is editing every time it opens - a picker that
    // kept its last selection would open a day away from the interval it describes.
    val pickingValue = if (editingStart == true) from else to
    val picker = rememberDatePickerState(initialSelectedDateMillis = pickingValue)
    val editingHour = java.time.Instant.ofEpochMilli(pickingValue)
        .atZone(java.time.ZoneId.systemDefault()).toLocalTime()
    // **Hour only, deliberately.** The table's granularity is the hour (§1 of the plan), so a
    // minute is not a thing a person can choose: offering one would let somebody pick 19:23 for a
    // table that can only count whole hours, and the figure they got back would silently be a
    // different period from the one they asked for.
    var pickedHour by remember { mutableStateOf(editingHour.hour) }

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
    var rows by remember(accountDir) { mutableStateOf<List<RequestStats.Row>>(emptyList()) }
    var loaded by remember(accountDir) { mutableStateOf(readRows == null) }
    LaunchedEffect(accountDir, readRows) {
        if (readRows != null) {
            readRows { found ->
                rows = found
                loaded = true
            }
        }
    }
    // **One figure, over the interval the two pickers name.** No list, no spans, no filtering: the
    // checkbox row is gone because there is one column, and the whole "which level speaks for this
    // unit" question is answered once per draw rather than once per column.
    val span = remember(from, to) { RequestStats.interval(from, to) }
    val totals = remember(rows, span) { RequestStats.totals(rows, span) }
    // **Whether the log reaches this far**, which is not the same as "nothing happened". The two
    // read identically in an empty table and mean different things.
    val covered = remember(rows, span) { RequestStats.covers(rows, span) }
    val oldest = remember(rows) { RequestStats.oldest(rows) }

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
                            pickedHour = java.time.Instant.ofEpochMilli(from)
                                .atZone(java.time.ZoneId.systemDefault()).hour
                            editingStart = true
                        },
                    )
                    IntervalRow(
                        label = "To",
                        at = to,
                        onOpen = {
                            Trace.tap("stats to", "open")
                            picker.selectedDateMillis = to
                            pickedHour = java.time.Instant.ofEpochMilli(to)
                                .atZone(java.time.ZoneId.systemDefault()).hour
                            editingStart = false
                        },
                    )
                    Text(
                        // **What the interval is, and whether the log reaches it.** Two facts,
                        // because they answer different questions: "what am I looking at" and "is
                        // an empty table because nothing happened, or because nothing was recorded
                        // yet". The second is the one that saves a person from thinking the screen
                        // is broken.
                        StatsFormat.hours(span.hours) + " · " + when {
                            // **Not "no log yet" while the read is still running.** Those are two
                            // different facts and only one of them is about the log.
                            !loaded -> "reading"
                            rows.isEmpty() -> "no log yet"
                            covered -> "from the log"
                            oldest == null -> "no log yet"
                            else -> "the log starts " + StatsFormat.instant(oldest)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
                    )
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
                                        // **On the hour exactly.** No minute can be chosen, and
                                        // `from`/`to` are snapped again by `interval()` below:
                                        // a start is floored to its hour and an end is taken to
                                        // the end of its hour, so the interval always covers whole
                                        // hours and never half of one.
                                        val chosen = day.atTime(pickedHour, 0)
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
                        // **The calendar is shrunk, and the body scrolls.**
                        //
                        // Found by looking at the picture, not by a test: on a 320x470 screen the
                        // default `DatePicker` filled the dialog and the hour stepper sat *below the
                        // bottom of the screen* - so the one control this session added could not be
                        // reached at all, and every assertion about it still passed because the node
                        // exists whether or not a person can see it.
                        //
                        // The title and the headline are the two lines Material lets a caller drop
                        // ("Select date", "October 28, 2026"), and they are the two that repeat what
                        // the month navigator directly beneath them already says. Dropping them is
                        // ~120dp, which is the hour row. The scroll is the backstop: a font scale or
                        // a shorter phone must not be able to hide a control again.
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            DatePicker(state = picker, title = null, headline = null)
                            // **The hour, and nothing finer.** One stepper rather than a clock face:
                            // a clock is a large control for a screen whose subject is a table, and
                            // it would offer minutes the table cannot count.
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
                                Spacer(Modifier.width(8.dp))
                                Text(":00", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }

            if (rows.isEmpty() && loaded) {
                item {
                    // **An empty table is a sentence, not a grid of zeroes.** A person opening this
                    // before the agent has run should be told why it is empty rather than shown a
                    // screen of dashes that reads like a fault.
                    Text(
                        "Nothing has been asked of the model yet, so there is nothing to count. " +
                            "Each response is written down as it happens, in the system project's " +
                            "requests folder.",
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
                        MetricRow(
                            name = "",
                            // The single column's heading: what the interval is, in hours, so the
                            // figure has a scale rather than being a number with no extent.
                            cells = listOf(StatsFormat.hours(span.hours)),
                            header = true,
                        )
                        MetricRow("requests", listOf(StatsFormat.count(totals.total.requests)))
                        MetricRow("input", listOf(StatsFormat.tokens(totals.total.promptTokens)))
                        MetricRow(
                            "  · cached",
                            listOf(
                                StatsFormat.absoluteAndShare(
                                    StatsFormat.tokens(totals.total.cachedTokens),
                                    totals.total.cachedShare,
                                ),
                            ),
                            muted = true,
                        )
                        MetricRow("output", listOf(StatsFormat.tokens(totals.total.completionTokens)))
                        MetricRow(
                            "  · thinking",
                            listOf(
                                StatsFormat.absoluteAndShare(
                                    StatsFormat.tokens(totals.total.reasoningTokens),
                                    totals.total.thinkingShare,
                                ),
                            ),
                            muted = true,
                        )
                        MetricRow(
                            "cost $",
                            listOf(
                                StatsFormat.cost(
                                    totals.total.cost.takeIf { _ -> totals.total.costReported > 0 },
                                ),
                            ),
                        )
                        MetricRow("per request", listOf(StatsFormat.perRequest(totals.total.perRequest)))
                        MetricRow("per M in", listOf(StatsFormat.perMillion(totals.total.perMillionInput)))
                        MetricRow("per M out", listOf(StatsFormat.perMillion(totals.total.perMillionOutput)))
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
                                "Answers that stopped  ·  " +
                                    StatsFormat.count(totals.stalls.requests) + " requests",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                            )
                            MetricRow("written", listOf(StatsFormat.tokens(totals.stalls.saidChars)))
                            MetricRow("thought", listOf(StatsFormat.tokens(totals.stalls.thinkingChars)))
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
            Text(StatsFormat.instantHour(at))
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
private fun MetricRow(
    name: String,
    cells: List<String>,
    header: Boolean = false,
    muted: Boolean = false,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(
            name,
            style = if (header) {
                MaterialTheme.typography.labelSmall
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = if (muted) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            cells.firstOrNull().orEmpty(),
            style = if (header) {
                MaterialTheme.typography.labelSmall
            } else {
                MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
            },
            color = if (header) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            textAlign = TextAlign.End,
            maxLines = 1,
        )
    }
}


