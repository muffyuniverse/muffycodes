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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
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
 *        idle since #412 · 37 checks
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
    var draft by remember(project) { mutableStateOf("") }
    /**
     * **Which bubble is open, by the row's id** — 0 for none.
     *
     * Not a position in the list: a position moves when anything before it is forgotten, and the
     * whole point of the footer's id is that the record's handle is stable. Keyed to the generation
     * as well, so a re-read of the record closes whatever was open rather than leaving a bubble
     * expanded that may no longer be there.
     */
    var opened by remember(generation) { mutableStateOf(0) }
    /**
     * The bubble being read fullscreen, or null.
     *
     * **A page view rather than a bigger bubble.** A long result read in the list is a tall box
     * inside a taller list, which is two scrolls fighting; fullscreen shows the same text one screen
     * at a time, so there is one scroll and it is the one a person expects.
     */
    var page by remember(generation) { mutableStateOf<AgentChat.Bubble?>(null) }
    val scroll = rememberScrollState()

    // A new bubble is the thing being waited for, so the box follows it to the bottom.
    LaunchedEffect(box?.bubbles?.size, generation) {
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
                    // **One line, ellipsised.** Without this a long project name wrapped to two or
                    // three lines in the bar and squeezed the actions beside it - "groceries" broke
                    // across two lines in the chat scenario's picture and the buttons sat against
                    // it. A title bar is a fixed height; the title gives way, not the controls.
                    Text(
                        project,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
                        // **Elapsed and produced, and never a percentage.** There is no denominator:
                        // nobody knows how long an answer will be until it has finished, so a bar
                        // would imply a proportion the app cannot know. What it can say is how long
                        // this has been going on and how much has arrived, and both are measured.
                        //
                        // **It is a description, not a progress bar**, and it is written in the
                        // same place the word "working" used to be - one line, in the corner, where
                        // a person glancing at the phone finds it and a person reading the answer
                        // is not distracted by it.
                        Text(
                            viewModel.chatLive.ifBlank { "working" },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .testTag("chat-live"),
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
            // **No navigator.** It was there because the box drew one turn at a time, and a turn
            // could be a dozen lines; a conversation is a list of bubbles and a list scrolls. The
            // navigator was the thing that made a person press ‹ to see the call that produced the
            // answer they were reading, which is the opposite of what a transcript should ask of
            // them.

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
                        // **Every bubble, oldest first, and the list lasts as long as the
                        // session.** That is the whole shape: what the person asked, what the agent
                        // did about it, and what it finally said.
                        for (bubble in box.bubbles) {
                            Bubble(
                                bubble = bubble,
                                expanded = opened == bubble.id,
                                cap = viewModel.settings.bubbleLines,
                                onOpen = {
                                    // **Fullscreen is a page view, not a bigger bubble.** The same
                                    // text, one screen at a time, so a long result is read by
                                    // turning pages rather than by scrolling a tall box inside a
                                    // taller list.
                                    page = bubble
                                    Trace.tap("bubble page", "#${bubble.id}")
                                },
                                onTap = {
                                    // **One open at a time, keyed by the row's id.** A set of open
                                    // bubbles would let a person expand six and lose the thread; and
                                    // an id is what the record uses, so the state survives a redraw
                                    // that reorders nothing but re-reads everything.
                                    opened = if (opened == bubble.id) 0 else bubble.id
                                    Trace.tap("bubble", "#${bubble.id}")
                                },
                            )
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

    // **The page view, over everything.** Not a new screen: the same bubble, one screen at a time,
    // with a way back. It is drawn last so it covers the list and the composer both - a person
    // reading a long result is not about to type.
    page?.let { open ->
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        open.caption,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        page = null
                        Trace.tap("close page", "#${open.id}")
                    }) { Text("Close") }
                }
                Text(
                    footer(open),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (open.prose) {
                        MarkdownBody(text = open.detail)
                    } else {
                        Text(
                            open.detail.ifBlank { open.body },
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One bubble: what happened, and **a footer saying which row it was and when.**
 *
 * **The token column is gone.** It was there for the clear-up, and the clear-up is a sentence in the
 * context rather than a number beside every line — so the space it took now carries the two facts a
 * person actually needs to find the row in `session.log`: its id, and the time.
 *
 * The footer is **id · time · thinking · output**, in that order: the handle first because that is
 * what a person copies, then when, then what the model spent. Thinking and output are shown only
 * when the row knows them, which it does for a streamed turn and does not for anything older — a
 * zero there would be a claim, and the absence of a number is the honest answer.
 *
 * **The time is a time, not a date.** The date belongs to the conversation; a list where every
 * bubble repeats it is a column of noise.
 */
@Composable
private fun Bubble(
    bubble: AgentChat.Bubble,
    expanded: Boolean,
    cap: Int,
    onOpen: () -> Unit,
    onTap: () -> Unit,
) {
    // **Cut at the cap and say so.** The last line is the app telling the truth about the message
    // rather than the message looking shorter than it is - a bubble that simply stopped would be
    // indistinguishable from one that had finished.
    val lines = bubble.body.lines()
    val cut = cap > 0 && !expanded && lines.size > cap
    val shown = if (cut) lines.take(cap).joinToString("\n") else bubble.body
    val continuing = if (cut) "\n… ${lines.size - cap} more lines — tap to read" else ""
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                bubble.caption,
                style = MaterialTheme.typography.labelMedium,
                color = if (bubble.failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.width(80.dp),
            )
            // **The agent's own words are markdown; everything else is a line.** A result is one
            // sentence about what a tool did, and rendering it as prose would invite a person to
            // read structure into it that is not there.
            if (bubble.prose) {
                MarkdownBody(
                    text = shown + continuing,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Text(
                    shown + continuing,
                    style = MaterialTheme.typography.bodyMedium,
                    // **Struck through, not hidden.** A row the agent forgot is still read here:
                    // that is what makes the forgetting auditable rather than something the app
                    // asks you to trust.
                    textDecoration = if (bubble.forgotten) TextDecoration.LineThrough else null,
                    color = when {
                        bubble.failed -> MaterialTheme.colorScheme.error
                        bubble.forgotten -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Text(
            footer(bubble),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(start = 80.dp),
        )
        if (expanded && bubble.detail.isNotBlank()) {
            Text(
                bubble.detail,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 80.dp),
            )
        }
    }
}

/**
 * What a bubble's footer says: **date · time · id · thinking · output.**
 *
 * The report asked for "time, date and id of the message", and the date is not decoration: a
 * conversation that crosses midnight has two rows reading `23:59` and `00:01`, and a footer
 * without the date cannot tell them apart. It is drawn first because that is the order a person
 * reads a moment in — day, then clock — and the id comes after because it is a handle rather than
 * a time.
 *
 * A part that is not known is left out rather than filled with a zero: a zero is a measurement and
 * "not recorded" is not the same fact. The id is always known and the time is known for every row
 * that was written, so in practice the absent parts are the date on an old row and the two token
 * counts, which only a streamed turn can report.
 */
private fun footer(bubble: AgentChat.Bubble): String {
    // **The id first, because it is the handle.** A person reads this footer to find the row in
    // `session.log` - that is what the id is for - and a handle after a date and a time is one you
    // have to hunt for. Report: "make id first in the row".
    val parts = mutableListOf<String>()
    if (bubble.id > 0) parts.add("#${bubble.id}")
    // **The time, and no date.** The date is the conversation's; a list where every row repeats the
    // same date is a column of noise, and the day is already on the screen above the list. Report:
    // "on message footer, I dont need full datetime, only hour:minute".
    val clock = LogFormat.time(bubble.stamp)
    if (clock.isNotBlank()) parts.add(clock)
    if (bubble.thinking > 0) parts.add("thinking ${ContextBudget.short(bubble.thinking)}")
    if (bubble.output > 0) parts.add("output ${ContextBudget.short(bubble.output)}")
    return parts.joinToString(" · ")
}
