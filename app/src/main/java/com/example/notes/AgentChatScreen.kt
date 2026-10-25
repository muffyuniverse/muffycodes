package com.example.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
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
import androidx.compose.ui.unit.dp

/**
 * The box: one conversation with the agent, and the line to speak in.
 *
 * ```text
 * [<-]  shopping
 *        ‹  3 of 6  ›                    »
 *        operator  rename milk.txt to dairy.txt
 *        ──────────────────────────────────────
 *        tree      0 · GitHub/ GitHub/milk.txt
 *        edit      0 · renamed, 1 commit
 *        asked     The README still links to milk.txt. Fix that too?
 *        ──────────────────────────────────────
 *        idle since 14:02 · 37 checks
 *        ──────────────────────────────────────
 *        [ Write to the agent ]      [ Send ]
 * ```
 *
 * **Everything drawn here comes from `agent/session.log`.** The navigator counts turns of *this*
 * conversation, not of the session: an earlier one is read in the file, which is the evidence and
 * is meant to be read. That is a deliberate limit rather than a gap — a box that could walk back
 * would be a second, worse way to read a log.
 *
 * The turn is drawn one line per thing that happened, with the result's first line beside the
 * tool that produced it; tapping the turn adds the arguments and the whole result. The
 * composition — what counts as a turn, what a line says, how forty idle polls become one line —
 * is [AgentChat]'s, so it is tested without a screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentChatScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
) {
    val project = viewModel.chatProject ?: return
    val session = viewModel.sessionOf(project)
    // Read the generation so a fresh read of an identical-looking conversation still redraws;
    // it is also the key that follows a new turn's arrival.
    val generation = viewModel.chatGeneration
    val box = viewModel.chatBox
    val turns = box?.turns.orEmpty()
    val index = viewModel.chatTurn.coerceIn(0, (turns.size - 1).coerceAtLeast(0))
    val turn = turns.getOrNull(index)
    var draft by remember(project) { mutableStateOf("") }
    var expanded by remember(index, generation) { mutableStateOf(false) }
    val scroll = rememberScrollState()

    // A new turn is the thing being waited for, so the box follows it to the bottom.
    LaunchedEffect(index, generation) {
        scroll.animateScrollTo(scroll.maxValue)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                title = { Column {
                    Text(project)
                    Text(
                        // **The state, in the words the notification uses.** One vocabulary for
                        // the box, the strip and the tray, so "why is it not answering" has one
                        // answer wherever a person looks.
                        text = session?.label(System.currentTimeMillis()) ?: "off",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (session?.needsYou == true) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                } },
                actions = {
                    if (viewModel.chatWorking) {
                        Text(
                            "working",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                    // **Reset clears the working context and keeps the evidence.** No dialog:
                    // nothing is lost — the session log is the record of what happened — and the
                    // one thing a person needs to know afterwards is said in the sentence under
                    // the screen.
                    TextButton(
                        onClick = { viewModel.resetAgent(project) },
                        enabled = !viewModel.chatWorking,
                    ) {
                        Text("Reset")
                    }
                    // **One control, and its label is what it will do.** An agent is on or off;
                    // a switch would need a name for the on state that is not a button's.
                    TextButton(
                        onClick = {
                            if (session?.running == true) {
                                viewModel.stopAgent(project)
                            } else {
                                viewModel.startAgent(project)
                            }
                        },
                    ) {
                        Text(if (session?.running == true) "Stop the agent" else "Start the agent")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Room for the keyboard: the field a person just tapped is the one thing
                // that must never be behind it.
                .imePadding(),
        ) {
            if (turns.isNotEmpty()) {
                Navigator(
                    index = index,
                    last = turns.lastIndex,
                    onPrevious = { viewModel.chatTurnBy(-1) },
                    onNext = { viewModel.chatTurnBy(1) },
                    onLatest = { viewModel.chatLatestTurn() },
                )
                HorizontalDivider()
            }

            // **At most three quarters of the window, and scrolls beyond that.** The
            // conversation is not the whole screen: the line a person speaks in stays visible
            // and reachable, which is also what keeps it above the keyboard.
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxHeight * 0.75f)
                        .verticalScroll(scroll)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (box == null) {
                        Text(
                            "Nothing said yet. Write a request below and the agent will work " +
                                "on this project.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Line("operator", box.told)
                        HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                        if (turn != null) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        expanded = !expanded
                                        Trace.tap(
                                            "turn",
                                            if (expanded) "opened ${index + 1}" else "closed ${index + 1}",
                                        )
                                    },
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                for (line in turn.lines) {
                                    Line(
                                        caption = line.caption,
                                        body = line.body,
                                        failed = line.failed,
                                        size = line.bytes,
                                    )
                                    if (expanded && line.detail.isNotBlank()) {
                                        Text(
                                            line.detail,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(start = 88.dp),
                                        )
                                    }
                                }
                            }
                        }
                        if (box.idle != null) {
                            Text(
                                box.idle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                TextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Write to the agent") },
                    maxLines = 4,
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val said = draft
                        draft = ""
                        viewModel.sendChat(said)
                    },
                    enabled = draft.isNotBlank() && !viewModel.chatWorking,
                ) {
                    Text("Send")
                }
            }
        }
    }
}

/** `‹ 3 of 6 ›` and `»`: the turns of this conversation, and nothing older. */
@Composable
private fun Navigator(
    index: Int,
    last: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onLatest: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onPrevious, enabled = index > 0) { Text("‹") }
        Text(
            "${index + 1} of ${last + 1}",
            style = MaterialTheme.typography.labelLarge,
        )
        TextButton(onClick = onNext, enabled = index < last) { Text("›") }
        Spacer(Modifier.weight(1f))
        // At the last turn it is disabled, like the other two at their ends: `»` means "the
        // newest turn of this conversation" and there is nowhere further to go — an earlier
        // conversation is read in the record, not walked to here.
        TextButton(onClick = onLatest, enabled = index < last) { Text("»") }
    }
}

/** One line: the caption in a narrow column, the body beside it. */
@Composable
private fun Line(caption: String, body: String, failed: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            caption,
            style = MaterialTheme.typography.labelMedium,
            color = if (failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.width(80.dp),
        )
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = if (failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
        )
    }
}
