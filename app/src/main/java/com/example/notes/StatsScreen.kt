package com.example.notes

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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

    // **Where the table ends.** The axis says how wide a bucket is; this says which bucket is the
    // last one. It starts at now and moves when a person picks a time - which is the difference
    // between "what did today cost" and "what did that Tuesday cost", and only one of those can be
    // answered by a clock.
    var anchor by remember { mutableStateOf(now) }
    var picking by remember { mutableStateOf(false) }
    val picker = rememberDatePickerState(initialSelectedDateMillis = anchor)
    val pickedMs = picker.selectedDateMillis

    // **Read when the screen opens, not while it is open.** The log is appended to on every
    // response, including the keepalives sent while nobody is looking, so a table that watched the
    // files would be re-reading them while the agent wrote to them — and would move under the
    // reader's eyes. What the report asked for was a table counted on opening, which is also the
    // only version whose numbers are stable enough to compare.
    val rows = remember(accountDir) { RequestStats.read(accountDir) }
    val days = remember(rows, anchor) { RequestStats.days(rows, anchor) }
    val buckets = remember(rows, axis, anchor) { RequestStats.buckets(rows, axis, anchor) }

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
                        // The span, in the app's own words, so the table never has to be counted
                        // backwards to know what it covers.
                        RequestStats.span(axis, anchor) +
                            // A count with "(s)" after it is a placeholder that was never replaced.
                            " · " + when (days) {
                                0 -> "no log yet"
                                1 -> "1 day kept"
                                else -> "$days days kept"
                            },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    // **A picker rather than only "now"**, because most cost questions are about a
                    // time that has already gone: what a Tuesday cost, and whether last month was
                    // more expensive than this one.
                    TextButton(onClick = { Trace.tap("stats date", "open"); picking = true }) {
                        Text("When")
                    }
                }
            }

            if (picking) {
                item {
                    // **Material's own picker, in a dialog.** A hand-rolled calendar would be a
                    // second date implementation to keep correct, and the platform's already knows
                    // about leap years, month lengths and the first day of the week.
                    DatePickerDialog(
                        onDismissRequest = { picking = false },
                        confirmButton = {
                            TextButton(
                                onClick = {
                                    pickedMs?.let { utcMidnight ->
                                        // **The picker returns UTC midnight of the chosen day.** Used
                                        // as-is, a phone west of UTC would anchor on the previous
                                        // local day and the table would describe a day ago. The date
                                        // is converted: take the UTC date it names and put it at the
                                        // *end* of that day in the device's zone, so the chosen day
                                        // is the last bucket shown rather than the first one cut off.
                                        val day = java.time.Instant.ofEpochMilli(utcMidnight)
                                            .atZone(java.time.ZoneOffset.UTC)
                                            .toLocalDate()
                                        val chosen = day.atTime(23, 59, 59)
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
                        DatePicker(state = picker)
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
    // **One scroll for the whole block, and it starts at the newest column.**
    //
    // The scroll is hoisted here because a state remembered inside each row is a separate scroll
    // per row: dragging one row would move its numbers off the column labels every other row is
    // still showing, and the table would quietly stop being a table.
    //
    // It starts at the END because the newest bucket is on the right and the right is what a
    // person opening a cost screen is asking about. Anchored at zero, the thirty-day view opens on
    // a month of empty columns with today's numbers off-screen - which is exactly what the first
    // picture of this screen showed.
    val scroll = rememberScrollState()
    // **Anchored to a cell boundary, not to the raw end.** `maxValue` leaves whatever remainder the
    // viewport has, so the leftmost column is drawn cut in half - the first picture of this screen
    // showed its heading as ":t". Rounding the target down to a whole cell means the table always
    // shows whole columns, and the newest one is still on the right.
    val cellPx = with(androidx.compose.ui.platform.LocalDensity.current) { (CELL_DP.dp).toPx() }
    LaunchedEffect(buckets.size, scroll.maxValue) {
        val whole = scroll.maxValue - (scroll.maxValue % cellPx.toInt())
        scroll.animateScrollTo(whole)
    }

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
        MetricRow(
            name = "input",
            cells = buckets.map { StatsFormat.tokens(of(it).promptTokens) },
            scroll = scroll,
        )
        MetricRow(
            // The share, directly under the thing it is a share of, so the two read as one fact.
            name = "  · cached",
            cells = buckets.map { StatsFormat.share(of(it).cachedShare) },
            scroll = scroll,
            muted = true,
        )
        MetricRow("output", buckets.map { StatsFormat.tokens(of(it).completionTokens) }, scroll = scroll)
        MetricRow(
            name = "  · thinking",
            cells = buckets.map { StatsFormat.share(of(it).thinkingShare) },
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
        MetricRow("per request $", buckets.map { StatsFormat.perRequest(of(it).perRequest) }, scroll = scroll)
        MetricRow("per M in $", buckets.map { StatsFormat.perMillion(of(it).perMillionInput) }, scroll = scroll)
        MetricRow("per M out $", buckets.map { StatsFormat.perMillion(of(it).perMillionOutput) }, scroll = scroll)
        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
    }
}

/** How wide one column is. A named constant because the scroll anchors to a multiple of it. */
private val CELL_DP = 64

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
            modifier = Modifier.width(96.dp),
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
