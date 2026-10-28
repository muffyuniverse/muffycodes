package com.example.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * What the app did, on the app's own screen.
 *
 * The point of this screen is a question a person can ask and answer without a
 * terminal: *did the thing I just tapped reach anything?* So the broken sequences come
 * first — a tap, and how long it has waited for any answer at all — and the log comes
 * after it, newest first, in the order things happened.
 *
 * It is read-only except for two acts: share, which hands the report to another app so
 * it can leave the phone, and clear, which starts again. Everything else is here to be
 * looked at, because a screen that can change the app while explaining it is a screen
 * where "it worked a moment ago" becomes true.
 *
 * The report is passed in rather than read here, so this can be painted in a test with
 * no recorder and no file behind it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObservabilityScreen(
    report: Trace.Report,
    onBack: () -> Unit,
    onShare: () -> Unit,
    onClear: () -> Unit,
    onReport: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                // One line, ellipsised: a bar is a fixed height and the actions beside the title
                // must not be squeezed by a title that decided to wrap.
                title = { Text("What the app did", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    ReportButton(onReport)
                    if (report.taps > 0) {
                        // **No Save.** It is already on disk, appended as it happened;
                        // a Save button would offer to write a second, worse copy of
                        // something that is already there.
                        TextButton(onClick = onShare) { Text("Share") }
                        TextButton(onClick = onClear) { Text("Clear") }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Text(
                text = report.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = if (report.isHealthy) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )

            // **The broken ones first, and said as sentences.** "Save (milk.txt) — no
            // change and no message after 2410 ms" is the whole finding: which element,
            // and that nothing at all came back. A list of events would make a person
            // work that out from timestamps.
            val stuck = report.waiting.filter { it.waitedMs > Trace.staleMs }
            if (stuck.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    text = "No answer yet",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                )
                stuck.forEach { broken ->
                    Text(
                        text = "· ${broken.line}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }
            }

            if (report.failures.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
                Text(
                    text = "Refused",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                )
                report.failures.take(20).forEach { said ->
                    Text(
                        text = "· $said",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(top = 12.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Everything, newest first",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "${report.lines.size} lines",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (report.lines.isEmpty()) {
                Text(
                    text = "Tap anything and it appears here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(report.lines) { line ->
                        // Monospace, because the columns line up: time, how long the
                        // answer took, and what the event was. A proportional font
                        // turns a table into a paragraph.
                        Text(
                            text = line,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (line.contains(" FAIL ")) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
    }
}
