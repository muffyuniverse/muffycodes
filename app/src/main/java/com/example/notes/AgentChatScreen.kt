package com.example.notes

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
    /**
     * The report window.
     *
     * **Defaulted to nothing so the screen still works where there is no reporter** - the tests and
     * the scenarios build it directly, and neither has a dialog to open. The app passes the same
     * `openReport` the browser does, so a report from either screen reaches the same place.
     */
    onReport: () -> Unit = {},
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
    var page by remember(generation) { mutableStateOf<Fullscreen?>(null) }
    /**
     * The list, and **where it starts: at the bottom.**
     *
     * "Pinned to the bottom" is the opening position, not a lock - once the person scrolls, the list
     * stays where they put it. A `LazyColumn` is what makes that affordable: a `Column` inside
     * `verticalScroll` composes every message, so a long session gets slower the longer it runs and
     * "scroll to the bottom" has to measure all of it first.
     */
    val list = rememberLazyListState()

    /**
     * **The page each message is showing**, by message id.
     *
     * A page index per message rather than one shared cursor, because a person turns to page 3 of
     * one result and then reads the next message from its beginning - a single cursor would carry
     * page 3 into a message that may not have three pages.
     */
    var showing by remember(generation) { mutableStateOf<Map<Int, Int>>(emptyMap()) }

    /**
     * **Which messages the person has turned back in**, and so which must not be dragged forward.
     *
     * A message follows its newest page until somebody pages back through it. After that it is
     * theirs: a new page arriving must not pull the ground out from under the page they are reading.
     * A tap on `›` that returns to the last page releases it again, so following resumes without
     * needing a third gesture.
     */
    var held by remember(generation) { mutableStateOf<Set<Int>>(emptySet()) }

    /**
     * **Every message sits on its last page, and follows it as pages arrive.**
     *
     * Two rules, and they are one: on opening, everything is on its last page, and while the agent
     * works, the message being written advances on its own - so a person can watch it work without
     * turning pages by hand. A message they have turned back in stops following (§[held]), and the
     * `›` that brings it to the end starts it following again.
     */
    LaunchedEffect(box?.messages?.map { it.id to it.pages.size }, generation) {
        val messages = box?.messages ?: return@LaunchedEffect
        val next = mutableMapOf<Int, Int>()
        for (message in messages) {
            val last = (message.pages.size - 1).coerceAtLeast(0)
            // Held messages keep the page the person chose, clamped to what still exists: a page
            // cannot be removed from under them, but the message may have grown since.
            next[message.id] = if (message.id in held) {
                (showing[message.id] ?: last).coerceIn(0, last)
            } else {
                last
            }
        }
        if (next != showing) showing = next
    }

    // A new message is the thing being waited for, so the list follows it to the bottom.
    LaunchedEffect(box?.messages?.size, generation) {
        val last = (box?.messages?.size ?: 0) - 1
        if (last >= 0) list.animateScrollToItem(last)
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
                    // **The report button, which this screen never had.** The operator asked for it
                    // twice - "bug report icon still isnt added to agent chat as I instructed in an
                    // older report from yesterday" - and it is the screen where a report is most
                    // likely: a wrong answer, a loop, a refusal. Same drawn glyph as the browser's,
                    // in the same corner, so the act looks the same wherever it is offered.
                    ReportButton(onReport = onReport)
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

            // **The conversation fills everything above the composer**, which is the operator's
            // correction: "there is strange white space above write input box and it partially
            // covers messages making the lower ones hard to read. remove it, last message should
            // snap to the user input message box, no gaps".
            //
            // It used to be capped at three quarters of the window with the intent that the
            // composer stay reachable. That is what `weight(1f)` already guarantees - the composer is
            // a sibling that is measured first - so the cap only reserved a band of empty screen
            // between the last message and the box, and on a short conversation the band was most of
            // the screen.
            // **A plain `Box`, not `BoxWithConstraints`.** The constraints were read when this
            // block was capped at three quarters of the window; `weight(1f)` does the whole job now,
            // so the scope is unused - and lint is right that a subcomposition nobody reads is a
            // cost paid for nothing.
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (box == null) {
                    Text(
                        "Nothing said yet. Write a request below and the agent will work " +
                            "on this project.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                } else {
                    LazyColumn(
                        state = list,
                        modifier = Modifier
                            .fillMaxSize(),
                        // **Bottom padding only, so the last message sits on the divider.** The
                        // vertical padding is what made the gap look like part of the message.
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        // **Every message, oldest first, alternating by side.** That is the whole
                        // shape: what the person asked, what the agent did about it, what the person
                        // asked next. The list lasts as long as the session.
                        items(box.messages, key = { it.id }) { message ->
                            MessageRow(
                                message = message,
                                at = showing[message.id] ?: 0,
                                cap = viewModel.settings.bubbleLines,
                                onTurn = { delta ->
                                    val pages = message.pages.size
                                    if (pages > 1) {
                                        val now = showing[message.id] ?: (pages - 1)
                                        val next = (now + delta).coerceIn(0, pages - 1)
                                        showing = showing + (message.id to next)
                                        // **Leaving the last page holds the message; coming back to
                                        // it releases it.** That is what makes "follow the newest
                                        // page" and "read the one I chose" one rule rather than two:
                                        // the person's own act decides which, and there is no
                                        // separate switch to find.
                                        held = if (next == pages - 1) held - message.id
                                        else held + message.id
                                        Trace.tap("message page", "#${message.id} ${next + 1}/$pages")
                                    }
                                },
                                onFullscreen = { index ->
                                    page = Fullscreen(message, index)
                                    Trace.tap("message fullscreen", "#${message.id}")
                                },
                            )
                        }
                        if (box.idle != null) {
                            item {
                                Text(
                                    box.idle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 4.dp),
                                )
                            }
                        }
                    }
                }
            }

            HorizontalDivider()
            // **Enter sends, and there is no Send button** - the operator's correction: "input box
            // should not have send button, it should send the message on enter".
            //
            // The button was a second way to do the same thing, and on a phone it cost a third of
            // the row's width - which is the width the message being written needs. A keyboard
            // already has a return key, and on a multi-line field it says what it will do.
            val send = {
                val said = draft
                if (said.isNotBlank()) {
                    draft = ""
                    viewModel.sendChat(said)
                }
            }
            TextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                placeholder = { Text("Write to the agent") },
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
            )
        }
    }

    // **The page view, over everything.** Not a new screen: the same pages, one screen at a time,
    // with a way back. It is drawn last so it covers the list and the composer both - a person
    // reading a long result is not about to type.
    //
    // **Uncapped, and that is the whole reason it exists.** In the list a page is cut at the height
    // cap; here it is read in full, scrolling, and the pages of that one message can be swapped.
    page?.let { open ->
        val pages = open.message.pages
        var at by remember(open.message.id) { mutableStateOf(open.at.coerceIn(0, (pages.size - 1).coerceAtLeast(0))) }
        val shown = pages.getOrNull(at)
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        shown?.caption ?: "message",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (pages.size > 1) {
                        TextButton(
                            onClick = { if (at > 0) at-- },
                            enabled = at > 0,
                        ) { Text("‹") }
                        Text(
                            "${at + 1}/${pages.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = { if (at < pages.size - 1) at++ },
                            enabled = at < pages.size - 1,
                        ) { Text("›") }
                    }
                    TextButton(onClick = {
                        page = null
                        Trace.tap("close page", "#${open.message.id}")
                    }) { Text("Close") }
                }
                if (shown != null) {
                    Text(
                        footer(open.message, shown),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                // **A fresh scroll per page**, so turning the page starts at its beginning rather
                // than wherever the last one was scrolled to.
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (shown != null) {
                        PageBody(page = shown, cap = 0, ink = MaterialTheme.colorScheme.onSurface, expanded = true)
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
/** What the fullscreen view is showing: one message, at one of its pages. */
private data class Fullscreen(val message: AgentChat.Message, val at: Int)

/**
 * **One message.** Two sides, drawn differently, because they are different things.
 *
 * The operator's is a **plain row**: their words, right-aligned on a tinted surface, with no pages,
 * no caption and no footer. It has no tool calls and no turns, so every piece of the agent's
 * furniture would be a frame around one sentence. Report: "user messages are less complicated -
 * they dont have multiple toolcalls and turns, - they are just a plain message row".
 *
 * The agent's is a **container of pages** on a surface of its own colour: the call and its result
 * for the page being shown, a pager when there is more than one, the closing words as the last
 * page, and a footer saying which row it was and what the turn cost.
 */
@Composable
/**
 * One arrow of the pager: an icon in a button a finger can actually hit.
 *
 * **40dp rather than the 28x24 the guillemets got.** The Material minimum for a touch target is 48dp,
 * and the row is already tight against a caption and a fullscreen icon - so 40dp is the compromise:
 * visibly bigger than it was, and the icon inside it is 24dp rather than a font's idea of a guillemet.
 * `IconButton`'s own minimum interaction size is kept, so the *touch* area is the full 48 even though
 * the drawn box is 40.
 *
 * [description] is what a screen reader says, and it is why these are icons rather than text: "‹" is
 * announced as "single left-pointing angle quotation mark", which is a fact about typography rather
 * than about the message behind the button.
 */
@Composable
private fun PagerArrow(
    pointingLeft: Boolean,
    enabled: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
        Icon(
            imageVector = if (pointingLeft) {
                Icons.AutoMirrored.Filled.KeyboardArrowLeft
            } else {
                Icons.AutoMirrored.Filled.KeyboardArrowRight
            },
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
    }
}

private fun MessageRow(
    message: AgentChat.Message,
    at: Int,
    cap: Int,
    onTurn: (Int) -> Unit,
    onFullscreen: (Int) -> Unit,
) {
    val mine = message.side == AgentChat.Side.OPERATOR
    // **Two colours, and they are surfaces rather than text colours.** The sides have to be told
    // apart at a glance down a list without either one shouting: the operator's takes the theme's
    // secondary container, the agent's the surface variant, and both carry the theme's own ink.
    val surface = if (mine) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val ink = if (mine) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // **The side decides the edge it hugs.** A right-aligned operator row and a
            // left-aligned agent row is the one convention every chat shares, and it says who is
            // speaking before a single word is read.
            .wrapContentWidth(if (mine) Alignment.End else Alignment.Start)
            .widthIn(max = 340.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(surface)
            .animateContentSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (mine) {
            // **Selectable**, so a person can copy what they asked - or copy their own words out to
            // ask something similar again. The operator asked for the app's text to be copyable.
            SelectionContainer {
                Text(
                    message.body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ink,
                    textDecoration = if (message.forgotten) TextDecoration.LineThrough else null,
                )
            }
            return@Column
        }
        val page = message.pages.getOrNull(at) ?: message.pages.firstOrNull() ?: return@Column
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                page.caption,
                style = MaterialTheme.typography.labelMedium,
                color = if (page.failed) MaterialTheme.colorScheme.error else ink,
                modifier = Modifier.weight(1f),
            )
            // **The pager, at the top of the bubble** - the operator's own correction: "arrows to
            // page through the message should be at the top of the message bubble". It sat below the
            // body, so its position depended on how tall the page happened to be: on a long result
            // the arrows were a screenful away from the caption they belong with, and paging moved
            // the thing you were aiming at. At the top they do not move at all, which is the same
            // reason the fullscreen icon is up here.
            if (message.pages.size > 1) {
                // **Real chevrons, and bigger.** The operator's request: "select < and > arrows from
                // proper icons and make them a little bigger so that they are easy to press".
                //
                // They were `Text("‹")` - a typographic single guillemet in the body font. Two
                // things were wrong with that. It is *punctuation*, so it sat on the baseline at
                // whatever size the font decided and did not look like a control; and at 28x24 with
                // 6dp of padding the actual tappable area was well under the 48dp a finger needs.
                //
                // `KeyboardArrowLeft`/`Right` are in material-icons-core, which this app already
                // carries - the *extended* set is the one it does not, and that is why the other
                // glyphs here are drawn. These two are not: they are in the set we have.
                //
                // **`AutoMirrored`**, because a pager is directional: in a right-to-left language the
                // "next" arrow has to point the other way, and the mirrored variant is what does
                // that. The app already uses it for the up-arrow.
                PagerArrow(
                    pointingLeft = true,
                    enabled = at > 0,
                    description = "Previous page",
                    onClick = { onTurn(-1) },
                )
                Text(
                    "${at + 1}/${message.pages.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ink,
                    modifier = Modifier.padding(horizontal = 2.dp),
                )
                PagerArrow(
                    pointingLeft = false,
                    enabled = at < message.pages.size - 1,
                    description = "Next page",
                    onClick = { onTurn(1) },
                )
            }
            // **The fullscreen icon is in the message's TOP-RIGHT corner**, which is where the
            // operator asked for it and where it stays put: the pager sits here too, and a control
            // anchored to the top cannot move when the page below it changes height.
            IconButton(
                onClick = { onFullscreen(at) },
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = "Read this message fullscreen",
                    tint = ink,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        // **The page is selectable, and so is the header.** A result is the thing a person copies
        // out of a chat - a path, a line of a file, an error - which is why this is here rather
        // than only on the operator's own words.
        SelectionContainer {
            Column {
                PageBody(page = page, cap = cap, ink = ink, expanded = false)
            }
        }
        Text(
            footer(message, page),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

/**
 * One page's text, **cut at the cap and told so.**
 *
 * The sentence is the point: a message that simply stopped would be indistinguishable from one that
 * finished, so the last visible line says how many lines are hidden and that expanding shows them.
 * The count is the **active page's**, because that page is the message's height - swapping pages
 * changes both, which is correct, since the sentence is a statement about what is on screen.
 */
@Composable
private fun PageBody(page: AgentChat.Page, cap: Int, ink: Color, expanded: Boolean) {
    val lines = page.detail.ifBlank { page.body }.lines()
    val cut = cap > 0 && !expanded && lines.size > cap
    val shown = if (cut) lines.take(cap).joinToString("\n") else lines.joinToString("\n")
    val continuing = if (cut) "\n… ${lines.size - cap} more lines — expand to read" else ""
    if (page.prose) {
        MarkdownBody(text = shown + continuing, modifier = Modifier.fillMaxWidth())
    } else {
        Text(
            shown + continuing,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (expanded) FontFamily.Monospace else null,
            // **Struck through, not hidden.** A page the agent forgot is still read here: that is
            // what makes the forgetting auditable rather than something the app asks you to trust.
            textDecoration = if (page.forgotten) TextDecoration.LineThrough else null,
            color = when {
                page.failed -> MaterialTheme.colorScheme.error
                page.forgotten -> ink
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

/**
 * What a message's footer says: **id · time · thinking · output.**
 *
 * **The id first, because it is the handle.** A person reads this footer to find the row in
 * `session.log`, and a handle after a date and a time is one you have to hunt for. Report: "make id
 * first in the row".
 *
 * **The time, and no date.** The date is the conversation's; a list where every row repeats the same
 * date is a column of noise, and the day is already on the screen above the list. Report: "on
 * message footer, I dont need full datetime, only hour:minute".
 *
 * A part that is not known is left out rather than filled with a zero: a zero is a measurement and
 * "not recorded" is not the same fact.
 */
private fun footer(message: AgentChat.Message, page: AgentChat.Page): String {
    val parts = mutableListOf<String>()
    if (page.id > 0) parts.add("#${page.id}")
    // **In the context, or forgotten.** The record's own `ctx` column says which: `in` means the
    // model is sent this row, `out` means the agent took it out with `forget`. Report: "message
    // footer should show if a row is in context (simple check of id against context log ids), so
    // that the displayed state is memorized or forgotten".
    //
    // **The word, and not only the strike-through.** A line through the text is a convention a
    // reader has to already know; the footer says it plainly, and it says it beside the id, which is
    // the handle the state belongs to.
    //
    // A page with no id is a row the app has not written yet, so it has no context state to report -
    // and saying "forgotten" about a row that does not exist would be a claim about nothing.
    if (page.id > 0) parts.add(if (page.forgotten) "forgotten" else "in context")
    val clock = LogFormat.time(page.stamp)
    if (clock.isNotBlank()) parts.add(clock)
    if (message.thinking > 0) parts.add("thinking ${ContextBudget.short(message.thinking)}")
    if (message.output > 0) parts.add("output ${ContextBudget.short(message.output)}")
    return parts.joinToString(" · ")
}
