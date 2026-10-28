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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
) {
    var axis by remember { mutableStateOf(RequestStats.Axis.DAY) }

    /**
     * **The one instant the whole screen is about**, and the only thing the picker sets.
     *
     * Every view is computed from this: pick 12:00 on 25 October 2026 and the hour axis shows that
     * day's twenty-four hours, the day axis shows October 2026, and the month axis shows all of
     * 2026 — three containers, one selection. That is why there is no per-axis picker here: the
     * axis says which *granularity of time* to read this instant at, so switching axis must not
     * change the instant, and picking a date must not change the axis.
     *
     * It starts at [now], which gives the default the operator asked for: the current hour, the
     * current day, the current month and the current year, all at once.
     */
    var anchor by remember { mutableStateOf(now) }
    var picking by remember { mutableStateOf(false) }
    // The dialog's own state, reset from `anchor` every time it is opened — a picker that kept its
    // last selection would open a month away from the table it describes.
    val picker = rememberDatePickerState(initialSelectedDateMillis = anchor)
    val pickedMs = picker.selectedDateMillis
    // The time of day, as three numbers the client side owns: Material's DatePicker picks a DATE,
    // and the hour axis needs the hour. Kept separately from the date so choosing a date never
    // silently resets 12:00 to midnight.
    val anchorHour = java.time.Instant.ofEpochMilli(anchor)
        .atZone(java.time.ZoneId.systemDefault()).toLocalTime()
    var pickedHour by remember { mutableStateOf(anchorHour.hour) }
    var pickedMinute by remember { mutableStateOf(anchorHour.minute) }

    // **Read when the screen opens, not while it is open.** The log is appended to on every
    // response, including the keepalives sent while nobody is looking, so a table that watched the
    // files would be re-reading them while the agent wrote to them — and would move under the
    // reader's eyes. What the report asked for was a table counted on opening, which is also the
    // only version whose numbers are stable enough to compare.
    val rows = remember(accountDir) { RequestStats.read(accountDir) }
    val box = remember(axis, anchor) { RequestStats.container(axis, anchor) }
    val buckets = remember(rows, axis, anchor) { RequestStats.buckets(rows, axis, anchor) }
    // **Whether the log reaches back this far**, which is not the same as "nothing happened".
    val covered = remember(rows, box) { RequestStats.covers(rows, box) }
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
                // **The axis, as three named modes.** Hour, day and month are the three spans a
                // person asks a cost question over, and a segmented control says which one is
                // showing without a label that would have to be read.
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    RequestStats.Axis.entries.forEachIndexed { i, option ->
                        SegmentedButton(
                            selected = axis == option,
                            onClick = {
                                Trace.tap("stats axis", option.label)
                                axis = option
                            },
                            shape = SegmentedButtonDefaults.itemShape(i, RequestStats.Axis.entries.size),
                        ) { Text(option.label) }
                    }
                }
            }

            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        // **The container's name, then whether the log reaches it.** Two facts,
                        // because they answer two different questions: "what am I looking at" and
                        // "is an empty column here because nothing happened, or because nothing was
                        // recorded yet". The old caption answered neither - it said how many days
                        // the whole log held, which for a container in the past was a negative
                        // number.
                        RequestStats.span(axis, anchor) + when {
                            rows.isEmpty() -> " · no log yet"
                            covered -> " · from the log"
                            oldest == null -> " · no log yet"
                            else -> " · the log starts " + StatsFormat.day(oldest)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    // **A picker rather than only "now"**, because most cost questions are about a
                    // time that has already gone: what a Tuesday cost, and whether last month was
                    // more expensive than this one.
                    //
                    // One button, one meaning: it sets the instant, and the axis decides how to
                    // read it. Labelling it per axis ("Which month") was wrong - it implied the
                    // picker asked a different question depending on which axis was showing, when
                    // the whole point is that one selection answers all three.
                    TextButton(onClick = {
                        Trace.tap("stats date", "open")
                        // The dialog opens on what the table is showing, not on wherever it was
                        // last left - it is a view of the anchor, not a place of its own.
                        picker.selectedDateMillis = anchor
                        pickedHour = anchorHour.hour
                        pickedMinute = anchorHour.minute
                        picking = true
                    }) {
                        Text("When")
                    }
                }
            }

            if (picking) {
                item {
                    // **Material's own calendar, plus the time of day.** The operator asked for a
                    // picker over "hour, day, month, year" and for a standard Android picker; the
                    // platform's picks a DATE, so the hour and minute are added underneath rather
                    // than reimplementing a calendar to get them. That combination is the smallest
                    // honest way to select an instant.
                    DatePickerDialog(
                        onDismissRequest = { picking = false },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    pickedMs?.let { utcMidnight ->
                                        // **The picker returns UTC midnight of the chosen day.** Used
                                        // as-is, a phone west of UTC would land on the previous local
                                        // day, so the UTC date it names is read first and then
                                        // rebuilt in the device's zone at the chosen time of day.
                                        //
                                        // `atZone(...).toInstant()` on a local date-time that does not
                                        // exist - the hour a zone springs forward - resolves forward
                                        // to the next real instant instead of throwing, which is why
                                        // this is safe for any hour the person can choose.
                                        val day = java.time.Instant.ofEpochMilli(utcMidnight)
                                            .atZone(java.time.ZoneOffset.UTC)
                                            .toLocalDate()
                                        val chosen = day.atTime(pickedHour, pickedMinute)
                                            .atZone(java.time.ZoneId.systemDefault())
                                            .toInstant()
                                            .toEpochMilli()
                                        Trace.tap("stats date", StatsFormat.day(chosen))
                                        anchor = chosen
                                    }
                                    picking = false
                                },
                            ) { Text("Show") }
                        },
                        dismissButton = {
                            TextButton(onClick = { picking = false }) { Text("Cancel") }
                        },
                    ) {
                        Column {
                            DatePicker(state = picker)
                            // The time of day. Two compact steppers rather than a clock face: a
                            // clock is a large control for a screen whose subject is a table, and
                            // the hour is the only part the hour axis reads.
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
                                Text(":", style = MaterialTheme.typography.bodyMedium)
                                TimeStepper(
                                    label = "minute",
                                    value = pickedMinute,
                                    range = 0..59,
                                    onChange = { pickedMinute = it },
                                )
                            }
                        }
                    }
                }
            }

            if (rows.isEmpty()) {
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
                // The two kinds, each as its own block of rows. A block is a title and its rows,
                // and the rows line up because every block is drawn by the same function.
                item { KindBlock("The agent", buckets) { it.turns } }
                item { KindBlock("The keepalive", buckets) { it.pings } }
                // **Only drawn when there were any**, because a permanent empty block would be a
                // standing warning about something that usually has not happened. When it is drawn
                // it is the one line on the screen whose cost is unknown, and that is the point.
                if (buckets.any { it.stalls.requests > 0 }) {
                    item { KindBlock("Answers that stopped", buckets) { it.stalls } }
                }
                item {
                    Text(
                        // The one thing a reader has to know to read the shares correctly, said
                        // once rather than in every row.
                        "Cached is part of input, not a column beside it. A dash is a number that " +
                            "does not exist — a span with no requests has no average, and that is " +
                            "not the same as zero. A stopped answer was billed but never reported " +
                            "its totals, so its cost cannot be shown.",
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
 * One kind's metrics, as rows, over the buckets the axis names.
 *
 * **A function of the block, so both kinds are drawn by the same code.** Two copies of this would
 * be two places for a column to go missing, and the rows of the two blocks would stop lining up the
 * moment one of them changed.
 */
@Composable
private fun KindBlock(
    title: String,
    buckets: List<RequestStats.Bucket>,
    of: (RequestStats.Bucket) -> RequestStats.Stats,
) {
    // The buckets, summed, so the header can say what the block comes to. A block with no requests
    // at all is still drawn: "the keepalive cost nothing" is an answer, and an absent block would
    // leave a person wondering whether the feature was working.
    val total = buckets.map(of).fold(RequestStats.Stats()) { a, b -> a + b }
    // **One scroll for the whole block, and it starts at the OLDEST column.**
    //
    // The scroll is hoisted here because a state remembered inside each row is a separate scroll
    // per row: dragging one row would move its numbers off the column labels every other row is
    // still showing, and the table would quietly stop being a table.
    //
    // It starts at the LEFT now, and that reverses an earlier decision. When the columns were a
    // rolling window ending at "now", the newest column was the question and the table opened at
    // the right end. The columns are a container's contents now - the days of a month, the months
    // of a year - and a container is read the way a calendar is: from its beginning. Opening
    // October at 31 October would be opening a month at its last day.
    //
    // `scrollTo` and not `animateScrollTo` because an animated anchor is not an anchor: a picture
    // once caught it mid-flight, three columns short, and read as a table of nothing.
    val scroll = rememberScrollState()
    LaunchedEffect(scroll.maxValue) { scroll.scrollTo(0) }

    Text(
        title + "  ·  " + StatsFormat.count(total.requests) + " requests",
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        // **The time labels, once, at the top of the block.** Without them the columns are
        // numbers with no position in time - and they are the row that has to scroll with the
        // values below, which is why it is drawn by the same function rather than beside it.
        MetricRow(
            name = "",
            cells = buckets.map { it.label },
            header = true,
            scroll = scroll,
        )
        MetricRow("requests", buckets.map { StatsFormat.count(of(it).requests) }, scroll = scroll)
        // **Input, cached and output, each as one cell: the absolute and its share.**
        //
        // `cached` is a subset of `input`, so it gets its own row rather than being added to it -
        // but its cell carries both numbers for the same reason `input`'s does: "3.2k (91.7%)" says
        // how much and how much of the whole in one reading. The first version drew the share on a
        // separate muted line under the absolute, which made it look like a different measurement.
        MetricRow(
            name = "input",
            cells = buckets.map { StatsFormat.tokens(of(it).promptTokens) },
            scroll = scroll,
        )
        MetricRow(
            name = "  · cached",
            cells = buckets.map {
                StatsFormat.absoluteAndShare(
                    StatsFormat.tokens(of(it).cachedTokens),
                    of(it).cachedShare,
                )
            },
            scroll = scroll,
            muted = true,
        )
        MetricRow("output", buckets.map { StatsFormat.tokens(of(it).completionTokens) }, scroll = scroll)
        MetricRow(
            name = "  · thinking",
            cells = buckets.map {
                StatsFormat.absoluteAndShare(
                    StatsFormat.tokens(of(it).reasoningTokens),
                    of(it).thinkingShare,
                )
            },
            scroll = scroll,
            muted = true,
        )
        MetricRow("cost $", buckets.map { StatsFormat.cost(of(it).cost.takeIf { _ -> of(it).costReported > 0 }) }, scroll = scroll)
        // **Drawn only when the block has characters to show**, which is only ever the stall
        // block. It is the one number a stall has: how much had arrived before the connection died,
        // which is the closest thing to the amount that was billed.
        if (total.saidChars > 0 || total.thinkingChars > 0) {
            MetricRow("  · written", buckets.map { StatsFormat.tokens(of(it).saidChars) }, scroll = scroll, muted = true)
            MetricRow("  · thought", buckets.map { StatsFormat.tokens(of(it).thinkingChars) }, scroll = scroll, muted = true)
        }
        MetricRow("per request", buckets.map { StatsFormat.perRequest(of(it).perRequest) }, scroll = scroll)
        MetricRow("per M in", buckets.map { StatsFormat.perMillion(of(it).perMillionInput) }, scroll = scroll)
        MetricRow("per M out", buckets.map { StatsFormat.perMillion(of(it).perMillionOutput) }, scroll = scroll)
        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
    }
}

/**
 * One number the person steps up and down: `hour` or `minute`.
 *
 * **A stepper rather than a text field**, because the value is a bounded integer and typing "7" into
 * an hour field has to answer what happens at 7 versus 07. Two buttons that wrap cannot produce an
 * invalid time, so the confirm button never has to explain itself.
 */
@Composable
private fun TimeStepper(
    label: String,
    value: Int,
    range: IntRange,
    onChange: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = {
                onChange(if (value <= range.first) range.last else value - 1)
                Trace.tap("stats time", "$label-")
            },
        ) { Text("\u2212") }
        Text(
            String.format(java.util.Locale.UK, "%02d", value),
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
        )
        TextButton(
            onClick = {
                onChange(if (value >= range.last) range.first else value + 1)
                Trace.tap("stats time", "$label+")
            },
        ) { Text("+") }
    }
}

/**
 * How wide one column is.
 *
 * **Widened from 64 for the one-cell shares.** A cell now holds `24.0k (91.7%)`, which is thirteen
 * characters; at 64dp a monospace bodySmall fits about nine, so the share would have been ellipsed
 * in exactly the row it was added for. 104dp fits the longest realistic cell - `328.4k (100.0%)` -
 * and costs width: 104dp instead of 64 means three columns visible on a 411dp phone rather than
 * four and a half.
 *
 * The name gutter narrows from 96dp to 88dp, and the three rate rows were renamed rather than
 * given the room: `per request $` is thirteen characters and does not fit a narrow gutter, while
 * `per req` says the same thing in two fewer. The labels are the cheapest thing on the screen to
 * shorten - a column is not.
 */
private val CELL_DP = 104
private val GUTTER_DP = 88

/**
 * One metric, as a name and one cell per bucket.
 *
 * **The name column is a fixed width and the cells scroll together**, which is the whole reason the
 * table is transposed: at a hundred buckets the cells are wider than the screen, and a scroll that
 * took the names with it would leave a row of numbers with nothing to say what they are.
 */
@Composable
private fun MetricRow(
    name: String,
    cells: List<String>,
    scroll: androidx.compose.foundation.ScrollState,
    header: Boolean = false,
    muted: Boolean = false,
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            name,
            style = if (header) {
                MaterialTheme.typography.labelSmall
            } else {
                MaterialTheme.typography.bodySmall
            },
            color = if (muted) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
            modifier = Modifier.width(GUTTER_DP.dp),
        )
        Row(modifier = Modifier.horizontalScroll(scroll)) {
            for (cell in cells) {
                Text(
                    cell,
                    style = if (header) {
                        MaterialTheme.typography.labelSmall
                    } else {
                        MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    },
                    color = if (header) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.width(CELL_DP.dp),
                )
            }
        }
    }
}
