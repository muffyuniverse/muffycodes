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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
    var axis by remember { mutableStateOf(RequestStats.Axis.DAY) }

    // **Read when the screen opens, not while it is open.** The log is appended to on every
    // response, including the keepalives sent while nobody is looking, so a table that watched the
    // files would be re-reading them while the agent wrote to them — and would move under the
    // reader's eyes. What the report asked for was a table counted on opening, which is also the
    // only version whose numbers are stable enough to compare.
    val rows = remember(accountDir) { RequestStats.read(accountDir) }
    val days = remember(accountDir) { RequestStats.days(accountDir) }
    val buckets = remember(rows, axis) { RequestStats.buckets(rows, axis, now) }

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
                Text(
                    // The span, in the app's own words, so the table never has to be counted
                    // backwards to know what it covers.
                    RequestStats.span(axis, now) +
                        " · " + (if (days == 0) "no log yet" else "$days day(s) kept"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
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
                item { KindBlock("The agent", buckets, now) { it.turns } }
                item { KindBlock("The keepalive", buckets, now) { it.pings } }
                item {
                    Text(
                        // The one thing a reader has to know to read the shares correctly, said
                        // once rather than in every row.
                        "Cached is part of input, not a column beside it. A dash is a number that " +
                            "does not exist — a span with no requests has no average, and that is " +
                            "not the same as zero.",
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
    now: Long,
    of: (RequestStats.Bucket) -> RequestStats.Stats,
) {
    // The buckets, summed, so the header can say what the block comes to. A block with no requests
    // at all is still drawn: "the keepalive cost nothing" is an answer, and an absent block would
    // leave a person wondering whether the feature was working.
    val total = buckets.map(of).fold(RequestStats.Stats()) { a, b -> a + b }
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
        )
        MetricRow("requests", buckets.map { StatsFormat.count(of(it).requests) })
        MetricRow(
            name = "input",
            cells = buckets.map { StatsFormat.tokens(of(it).promptTokens) },
        )
        MetricRow(
            // The share, directly under the thing it is a share of, so the two read as one fact.
            name = "  · cached",
            cells = buckets.map { StatsFormat.share(of(it).cachedShare) },
            muted = true,
        )
        MetricRow("output", buckets.map { StatsFormat.tokens(of(it).completionTokens) })
        MetricRow(
            name = "  · thinking",
            cells = buckets.map { StatsFormat.share(of(it).thinkingShare) },
            muted = true,
        )
        MetricRow("cost $", buckets.map { StatsFormat.cost(of(it).cost.takeIf { _ -> of(it).costReported > 0 }) })
        MetricRow("per request $", buckets.map { StatsFormat.perRequest(of(it).perRequest) })
        MetricRow("per M in $", buckets.map { StatsFormat.perMillion(of(it).perMillionInput) })
        MetricRow("per M out $", buckets.map { StatsFormat.perMillion(of(it).perMillionOutput) })
        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
    }
}

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
        Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
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
                    modifier = Modifier.width(64.dp),
                )
            }
        }
    }
}
