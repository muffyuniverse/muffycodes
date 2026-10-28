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
    val buckets = remember(rows, anchor) { RequestStats.buckets(rows, anchor) }
    // **Whether the log reaches back this far**, which is not the same as "nothing happened" in
    // it. The month column is the widest span, so it is the one that answers whether any of the
    // three can: if the log covers the month, it covers the day and hour inside it too.
    val box = remember(anchor) { RequestStats.container(RequestStats.Span.MONTH, anchor) }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        // **The container's name, then whether the log reaches it.** Two facts,
                        // because they answer two different questions: "what am I looking at" and
                        // "is an empty column here because nothing happened, or because nothing was
                        // recorded yet". The old caption answered neither - it said how many days
                        // the whole log held, which for a container in the past was a negative
                        // number.
                        // **The instant, then whether the log reaches back to it.** Three
                        // numbers, because three is how many ways the same moment is counted -
                        // and the sentence that makes the three columns readable: they all stop
                        // here, so the later part of the hour, day and month is not in them.
                        "up to " + StatsFormat.instant(anchor) + when {
                            rows.isEmpty() -> " · no log yet"
                            covered -> ""
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
                // **One table, three columns.** The rows are the metrics the operator named, in
                // the order they were named: counts and the two kinds' tokens with their shares,
                // then the cost and the two derived rates.
                item {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // The headings: one per span, and the narrowest first, so the columns read
                        // 12:00 | 25 Oct | Oct 2026 - the same instant seen three ways.
                        MetricRow(
                            name = "",
                            cells = buckets.map { it.label },
                            subs = buckets.map { it.sub },
                            header = true,
                        )
                        MetricRow("requests", buckets.map { StatsFormat.count(it.total.requests) })
                        MetricRow(
                            "input",
                            buckets.map { StatsFormat.tokens(it.total.promptTokens) },
                        )
                        MetricRow(
                            "  · cached",
                            buckets.map {
                                StatsFormat.absoluteAndShare(
                                    StatsFormat.tokens(it.total.cachedTokens),
                                    it.total.cachedShare,
                                )
                            },
                            muted = true,
                        )
                        MetricRow(
                            "output",
                            buckets.map { StatsFormat.tokens(it.total.completionTokens) },
                        )
                        MetricRow(
                            "  · thinking",
                            buckets.map {
                                StatsFormat.absoluteAndShare(
                                    StatsFormat.tokens(it.total.reasoningTokens),
                                    it.total.thinkingShare,
                                )
                            },
                            muted = true,
                        )
                        MetricRow(
                            "cost $",
                            buckets.map {
                                StatsFormat.cost(it.total.cost.takeIf { _ -> it.total.costReported > 0 })
                            },
                        )
                        MetricRow("per request", buckets.map { StatsFormat.perRequest(it.total.perRequest) })
                        MetricRow("per M in", buckets.map { StatsFormat.perMillion(it.total.perMillionInput) })
                        MetricRow("per M out", buckets.map { StatsFormat.perMillion(it.total.perMillionOutput) })
                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    }
                }
                // **Answers that stopped, only when there were any.** They are billed but report no
                // totals, so they cannot be folded into the rows above without claiming a cost of
                // zero for them; they get their own block, over the same three columns.
                if (buckets.any { it.stalls.requests > 0 }) {
                    item {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "Answers that stopped  ·  " +
                                    StatsFormat.count(buckets.fold(0) { n, b -> n + b.stalls.requests }) +
                                    " requests",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                            )
                            MetricRow("", buckets.map { it.label }, header = true)
                            MetricRow("requests", buckets.map { StatsFormat.count(it.stalls.requests) })
                            MetricRow("written", buckets.map { StatsFormat.tokens(it.stalls.saidChars) })
                            MetricRow("thought", buckets.map { StatsFormat.tokens(it.stalls.thinkingChars) })
                            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                        }
                    }
                }
                item {
                    Text(
                        // The one thing a reader has to know to read the shares correctly, said
                        // once rather than in every row.
                        "The columns overlap: the hour is part of the day, and the day part of the " +
                            "month. Cached is part of input, not a column beside it. A dash is a " +
                            "number that does not exist — a span with no requests has no average, " +
                            "and that is not the same as zero. A stopped answer was billed but " +
                            "never reported its totals, so its cost cannot be shown.",
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

/** How wide the metric names are, leaving the rest for the three time columns. */
private val GUTTER_DP = 100

/**
 * One metric, as a name and one cell per bucket.
 *
 * **The name gutter is a fixed width and the three cells share the rest.** There is no sideways
 * scroll any more: the columns are one hour, one day and one month, so there are exactly three and
 * they fit a phone. The transposed shape survives from when the columns were a rolling window of
 * thirty, which had to scroll.
 *
 * The gutter is 100dp because the longest label is `per request` at eleven characters in bodySmall;
 * the three cells then share 411 - 24 - 100 = 287dp, about 95dp each, which holds `24.0k (91.7%)`.
 */
@Composable
private fun MetricRow(
    name: String,
    cells: List<String>,
    /** Drawn under each cell, one line each. Only the heading row uses it. */
    subs: List<String> = emptyList(),
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
        Row(modifier = Modifier.weight(1f)) {
            for ((i, cell) in cells.withIndex()) {
                Column(modifier = Modifier.weight(1f)) {
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
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // **The second line of a heading**, saying where its window starts. Only the
                    // heading has one; an empty string keeps the row heights equal.
                    if (header && i < subs.size && subs[i].isNotEmpty()) {
                        Text(
                            subs[i],
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}
