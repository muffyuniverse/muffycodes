package com.example.notes

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
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
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
    //
    // **The first jump is a jump, not a scroll.** The operator's report: "when I enter the chat
    // screen I should see the bottom (on entering the chat my view already should be positioned
    // there, not scrolled before my eyes)". `animateScrollToItem` on entry animated the whole
    // conversation past the person - a long chat visibly slid from the top to the bottom, which
    // reads as the app doing something to the screen rather than as arriving at the end of it.
    //
    // `scrollToItem` on the first pass puts it there before it is drawn; every later message still
    // animates, because *that* motion is information: something arrived below where you are looking.
    var firstPass by remember { mutableStateOf(true) }
    /**
     * **Whether the person is looking at the end of the conversation**, which is a position rather
     * than a preference.
     *
     * The operator's rule: "screen should be at the bottom of the message list on chat menu opening.
     * and it should scroll down when I start writing when at the bottom. but when I am not at the
     * bottom (for example when I scrolled up to see an earlier message) screen shouldn't move. the
     * difference is positional."
     *
     * So there is no "follow" switch and no timer: the list follows what arrives **when it was
     * already at the end**, and leaves the view alone when it was not. A person reading an earlier
     * message has said where they want to be, and an app that drags them away from it is an app
     * that loses their place on every reply.
     *
     * The last visible index is compared with the last index rather than with a pixel offset: a
     * page's height varies, so "within a screenful of the end" is a claim about rows, not distances.
     */
    val atBottom by remember {
        derivedStateOf {
            val info = list.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            last == null || (last.index >= info.totalItemsCount - 1)
        }
    }
    LaunchedEffect(box?.messages?.size, generation) {
        val last = (box?.messages?.size ?: 0) - 1
        if (last < 0) return@LaunchedEffect
        if (firstPass) {
            // **The opening jump is a jump.** On entry the list must already be at the end, not
            // visibly slide there.
            firstPass = false
            list.scrollToItem(last)
        } else if (atBottom) {
            // **Smooth, because the motion is information** - something arrived below where you
            // were looking - and because the operator asked for it: "scrolling to fit the keyboard
            // below the last message looks too snappy, can you make it smooth and without lag?"
            list.animateScrollToItem(last)
        }
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
                        //
                        // **"idle · waiting", not "off", when there is no session yet.** The
                        // operator's report: "agent is 'off' but it responds when user sends it a
                        // message. Make it so that a new message from user turns the agent 'on'".
                        // Sending always did turn it on - `sendChat` resumes a stopped session and
                        // `speak` starts a fresh one - so the behaviour was right and the *word*
                        // was the lie: "off" reads as a switch that will not work, and then it
                        // works, and the person has learned not to trust the header.
                        //
                        // A project with no session is waiting for one, which is what it says now.
                        text = session?.label(System.currentTimeMillis()) ?: "idle · waiting",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (session?.needsYou == true) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                } },
                actions = {
                    // **Nothing about the reply in the top bar.** The operator's report: "it
                    // shouldnt be in the top bar at all, agent's messages should be in their own
                    // bubble, not inside the top bar of the screen".
                    //
                    // A line was drawn here - elapsed seconds, characters, calls, tokens - and it
                    // grew as the reply did, taking width from the title. Worse, it was the *only*
                    // sign of what the agent was saying: a person watched a counter climb in the
                    // corner and then the whole reply appeared at once, which reads as the app
                    // squeezing a message into the title bar. The words are a bubble now, where the
                    // finished ones go, and the bar says nothing while it works.
                    //
                    // The measurements are not lost - `chatLive` is still written, and the trace
                    // carries them. They are simply not the top bar's business.
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
        // **The keyboard is a size change, and the list has to follow it.** The operator's report:
        // "when keyboard pops up it covers messages, I want the screen to move down so that messages
        // are above the keyboard. and when keyboard is down, screen should readjust back".
        //
        // `imePadding` shrinks the column so the composer is never behind the keyboard - that part
        // already worked. What it does not do is move the *view*: the list keeps the scroll offset
        // it had, the last message slides below the new bottom edge, and the person is left looking
        // at the middle of the conversation they were at the end of.
        //
        // So the height of the keyboard is watched, and when it changes the list goes back to the
        // last message. Reading the inset rather than "is the keyboard open" also covers the height
        // changing while it is open - a suggestion strip appearing, a different keyboard - which is
        // the same complaint one step later.
        val keyboard = WindowInsets.ime.getBottom(LocalDensity.current)
        LaunchedEffect(keyboard) {
            val last = (box?.messages?.size ?: 0) - 1
            if (last >= 0) list.animateScrollToItem(last)
        }
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
                        // **The reply being written, as a bubble where it will end up.**
                        // The operator's report: "agent's messages should be in their own bubble,
                        // not inside the top bar of the screen". A finished reply is a row in the
                        // record and arrives here as a message; while it is being written there is
                        // no row yet, so it is drawn from `chatWriting` and disappears the instant
                        // the row arrives - which is why it is the same bubble shape rather than
                        // something special.
                        //
                        // **No pager, no caption, no footer**, because none of those exist until the
                        // turn ends: a page count for a reply that is still growing would change
                        // under the person's eyes.
                        if (viewModel.chatWorking) {
                            item(key = "writing") {
                                WritingBubble(
                                    words = viewModel.chatWriting,
                                    pages = viewModel.chatPages.values.sortedBy { it.id },
                                )
                            }
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

            // **No divider, and no box around the field.** The operator's report: "I want write to
            // the agent box be the color of the background - no visible edges".
            //
            // There were three edges doing the same job: a `HorizontalDivider` above the field, the
            // field's own filled container, and its indicator line. Three lines saying "the
            // conversation ends here" on a screen where the conversation ending is already obvious
            // from the messages stopping. They now go, and the field is the page's own background -
            // so the composer reads as the place you keep writing rather than as a panel bolted to
            // the bottom of a transcript.
            //
            // **The cursor and the placeholder are the affordance.** That is the whole visible
            // difference between an empty field and a label, and it is how every chat app the person
            // has used already behaves.
            //
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
                    .padding(horizontal = 4.dp),
                placeholder = { Text("Write to the agent") },
                maxLines = 4,
                // **The page's colour, and no indicator.** `containerColor` alone leaves the
                // Material underline, which is the edge the report is about.
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                ),
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    // **Below the status bar, not behind it.** The operator's report: "on message
                    // fullscreen view top bar buttons are too high, they are partly covered by
                    // system bar. move them a little down".
                    //
                    // This screen draws its own row instead of a `TopAppBar`, and a bare
                    // `padding(16.dp)` counts from the window edge - which on a device with a
                    // status bar or a camera cutout is *under* them. The Scaffold on the screen
                    // behind it handles this; this overlay covers that Scaffold, so it has to ask
                    // for the same inset itself.
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // **A page's caption is a tool name, and this is not a tool.** The operator's
                    // report: "and what is this 'create' text at fullscreen view?" - "create" is
                    // the agent's `create` tool, which is what the page IS, and beside a Close
                    // button on a full-screen view it reads as a verb the person could press.
                    //
                    // The caption stays, because knowing which call produced the text is the point
                    // of the page. It is no longer alone: the row now says whose page it is, so
                    // "create" cannot be mistaken for an action.
                    // **"Agent · agent" was the page saying its own name twice.** Reported: "why in
                    // fullscreen at the top left corner agent is repeated 2 times: 'agent agent'?"
                    // The caption of an ordinary reply is literally `agent`, so the prefix and the
                    // caption were the same word. The prefix is only worth drawing when it adds
                    // something: a page whose caption is the tool that made it reads "Agent · read",
                    // which says who and what; a page whose caption is already "agent" says it once.
                    val caption = shown?.caption ?: "message"
                    Text(
                        if (caption.equals("agent", ignoreCase = true)) "Agent" else "Agent · $caption",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    if (pages.size > 1) {
                        // **The same arrows as the bubble's, because this is the screen where they
                        // are hardest to hit.** Reported: "in full screen mode paging arrows are
                        // still tiny". The bubble's pager was given real chevrons with a 48dp touch
                        // target and this one kept `Text("‹")` — a guillemet in the body font, which
                        // is punctuation at whatever size the font decides, not a control.
                        PagerArrow(
                            pointingLeft = true,
                            enabled = at > 0,
                            description = "Previous page",
                            onClick = { if (at > 0) at-- },
                        )
                        Text(
                            "${at + 1}/${pages.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 2.dp),
                        )
                        PagerArrow(
                            pointingLeft = false,
                            enabled = at < pages.size - 1,
                            description = "Next page",
                            onClick = { if (at < pages.size - 1) at++ },
                        )
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
 * **The agent's reply while it is still being written.**
 *
 * The same surface, ink and shape as a finished agent message, because it becomes one - a person
 * should not see the text jump from one style to another at the moment the turn ends. What it does
 * not have is any of the furniture that needs a finished row: no caption (there is no tool name
 * yet), no pager (a page count for a growing reply would change under your eyes), no footer (no id,
 * no cost).
 *
 * **A caret at the end**, because the difference between "still writing" and "stopped mid-sentence"
 * is the only thing the shape alone cannot say.
 */
@Composable
private fun WritingBubble(words: String, pages: List<Agent.Live>) {
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentWidth(Alignment.Start)
            .widthIn(max = 340.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // **The calls, in the order they were made, each filling in its own result.** The operator's
        // design: a page is a command *and* its result, so the command is drawn as soon as the model
        // asks and the answer replaces "working" when the tool returns. Without the first half a
        // twenty-second `create` showed nothing at all until it finished.
        for (live in pages) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    live.tool,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (live.failed) MaterialTheme.colorScheme.error else ink,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "#${live.id}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            Text(
                // **The answer when there is one, and "working" when there is not.** A page that
                // showed nothing while a tool ran would be indistinguishable from a page with an
                // empty answer.
                text = live.answer?.let { AgentChat.summaryOf(it) } ?: "working…",
                style = MaterialTheme.typography.bodySmall,
                color = if (live.failed) MaterialTheme.colorScheme.error else ink,
            )
        }
        // **The words, once per reply.** Blank until the first reply finishes, which is why this
        // only draws when there is something to say.
        if (words.isNotBlank()) {
            Text(
                text = words,
                style = MaterialTheme.typography.bodyMedium,
                color = ink,
            )
        }
    }
}

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
            // **The height animation belongs to the agent's bubble, not the person's.**
            //
            // A person's message never changes height: it is one `Text`, it has no pages, and it
            // does not grow while the agent works. The animation was therefore doing nothing
            // visible - but it is a layout modifier that re-lays out on every pass, and the
            // operator's report is "text selection is broken for user messages". That modifier is
            // the one structural difference between their bubble and the agent's, which selects
            // fine.
            //
            // **Said plainly: I could not reproduce this in the test suite.** Text selection is a
            // platform long-press gesture and Robolectric does not perform it, so this is a change
            // to the one difference I can see rather than a verified fix. If selection is still
            // broken on the next build, the cause is elsewhere and I will need the trace.
            .then(if (mine) Modifier else Modifier.animateContentSize())
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
                PageBody(
                    page = page,
                    cap = cap,
                    ink = ink,
                    expanded = false,
                    onExpand = { onFullscreen(at) },
                )
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
private fun PageBody(
    page: AgentChat.Page,
    cap: Int,
    ink: Color,
    expanded: Boolean,
    /**
     * Opens this message fullscreen, or null where there is nowhere to expand to.
     *
     * **The sentence used to be a claim the person could not act on.** Reported: "expand to read -
     * I want this phrase at the bottom of messages to be clickable (and of selectable color) so that
     * it also expands the message into fullscreen (same as the icon of magnifying glass in the top
     * right corner)". It said "expand to read" and tapping it did nothing, which is worse than not
     * saying it: the words promised a control and the screen had none.
     *
     * Null rather than an empty lambda, because in fullscreen the page is already whole — there the
     * sentence cannot appear at all (`cap = 0`), and a caller that passed a lambda anyway would be
     * offering to open what is already open.
     */
    onExpand: (() -> Unit)? = null,
) {
    val lines = page.detail.ifBlank { page.body }.lines()
    val cut = cap > 0 && !expanded && lines.size > cap
    val shown = if (cut) lines.take(cap).joinToString("\n") else lines.joinToString("\n")
    val hidden = if (cut) lines.size - cap else 0
    // **The sentence is drawn by the caller when it is a control.** A cut page in the list gets a
    // tappable line instead of a trailing phrase inside the selectable text, so the offer to expand
    // is one target rather than a run of characters a long press can select.
    val body = if (cut && onExpand == null) shown + "\n… $hidden more lines — expand to read" else shown
    if (page.prose) {
        MarkdownBody(text = body, modifier = Modifier.fillMaxWidth())
    } else {
        Text(
            body,
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
    if (cut && onExpand != null) {
        // **A real control, in the accent colour.** It is the same act as the magnifier in the top
        // right corner, so it is the same colour as the things a person can press, and it is big
        // enough to hit without aiming.
        TextButton(
            onClick = onExpand,
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
        ) {
            Text(
                "… $hidden more lines — expand to read",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
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
    val clock = LogFormat.clock(page.stamp)
    if (clock.isNotBlank()) parts.add(clock)
    if (message.thinking > 0) parts.add("thinking ${ContextBudget.short(message.thinking)}")
    if (message.output > 0) parts.add("output ${ContextBudget.short(message.output)}")
    return parts.joinToString(" · ")
}
