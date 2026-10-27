package com.example.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign

/**
 * Settings: the account, the appearance, and how much history is kept — and
 * nothing else (§10). Pull, Delete and Sync are not here: they live where the
 * projects are.
 */
/**
 * Where the log is, said plainly — including when it is not where the switch says.
 *
 * Read at composition rather than held in state: the answer changes when a write happens,
 * and a write is not a Compose event. Opening this screen re-reads it, which is when a
 * person looks.
 */
@Composable
private fun traceWhere(): String {
    val where = Trace.where
    return when {
        where == null -> "Nothing written yet. One line per thing that happened, appended."
        !where.contains(DownloadsSink.FOLDER) ->
            "Writing to $where instead — Downloads refused, so other apps cannot see it."
        else -> "Appended as it happens, at $where."
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onRemoveAccount: () -> Unit,
    onTestAccount: () -> Unit,
    /** Opens the trace: what the app was asked to do and what answered. */
    onOpenTrace: () -> Unit = {},
    /** The bug button, on every screen, always. */
    onReport: () -> Unit = {},
    /** Asks for the OpenRouter key: a screen that refuses screenshots, like the GitHub one. */
    onSetKey: () -> Unit = {},
    /**
     * Whether Android will let an agent poll while the screen is off — **the system's fact, passed
     * in** rather than read here.
     *
     * The same rule as the clock: a system fact a screen depends on is a parameter, so a test states
     * the phone it is testing instead of arranging a shadow to impersonate one. The default reads the
     * phone, because that is what the app does.
     */
    exempt: (Context) -> Boolean = { Battery.exempt(it) },
    /** Changes when the person comes back from the system's screen, so the fact is read again. */
    batteryAsked: Int = 0,
    /** Opens the system's exemption screen. MainActivity owns the launcher, as it does the pickers. */
    onAskBattery: () -> Unit = {},
    /**
     * Whether a notification posted now would be seen — **the system's fact, passed in**, like the
     * battery exemption above and for the same reason: a screen that reads the phone itself cannot
     * be tested against a stated one.
     */
    notified: (Context) -> Boolean = { Notices.granted(it) },
    /** Changes when the person comes back from the permission dialog, so the fact is read again. */
    noticesAsked: Int = 0,
    /** Shows the permission dialog. MainActivity owns the launcher, as it does the pickers'. */
    onAskNotices: () -> Unit = {},
) {
    val settings = viewModel.settings
    val account = viewModel.account
    var showingSwitch by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // Read once per visit to the system's screen — not per frame, because the check is an IPC into
    // the power service, and not once for ever, because the answer can change while the app is
    // paused behind that screen.
    val exemptNow = remember(batteryAsked) { exempt(context) }
    val noticesNow = remember(noticesAsked) { notified(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Settings") },
                  actions = { ReportButton(onReport) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                // Room for the keyboard. Without this the history field sat under it:
                // the screen could scroll, but nothing told it that the bottom of the
                // window had moved up to the top of the keyboard, so the field a
                // person had just tapped was the one thing they could not see.
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // ---- the account (§5) ----
            //
            // **One block, with its own tight spacing.** The screen's rhythm is six
            // density-independent pixels between things that are unrelated; the account's
            // name, its address and the three buttons that act on it are one thing, and
            // giving them the outer rhythm — on top of a button's own padding — left a gap
            // wide enough to look like two screens. Reported from the live log: "between
            // user account and buttons below it is too much space".
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Signed in as", style = MaterialTheme.typography.labelMedium)
                Text(
                    text = account?.login ?: "Nobody yet",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = account?.commitEmail ?: "Sign in to create projects",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Add, Switch, Remove — and nothing that reads back at you. The commit
                // name and email are no longer something to type: they come from the
                // account GitHub already knows, so the only reason to change them would
                // be to commit as somebody else, which this app has no use for.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The padding is the button's own; asking for less keeps the touch
                    // target (Material enforces forty dips) while removing the empty band
                    // above the label.
                    val tight = PaddingValues(
                        horizontal = 10.dp,
                        vertical = 4.dp,
                    )
                    TextButton(onClick = onSignIn, contentPadding = tight) { Text("Add") }
                    if (account != null) {
                        TextButton(
                            onClick = { showingSwitch = true },
                            contentPadding = tight,
                        ) { Text("Switch") }
                        TextButton(onClick = onRemoveAccount, contentPadding = tight) {
                            Text("Remove", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
            // A debug build can make a test account. It lived in the account window
            // that the three-lined button opened, and that window is gone — so the
            // capability came here rather than being quietly dropped with it. A
            // release build never sees this: `canMakeTestAccount` is false.
            if (viewModel.canMakeTestAccount) {
                TextButton(onClick = onTestAccount) { Text("Use the test account") }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // ---- everything the agent needs, in one place, on this screen once ----
            //
            // **A block, not six sections.** Every row below exists for the same machine: the key
            // it talks with, the model it talks to, how big its memory may get, how long it waits,
            // when it asks for help, and the two Android permissions that decide whether any of
            // that can happen while the phone is in a pocket. Reported from the live log as two
            // separate complaints that are one fix: "move agent block up, right below signed in as
            // block. and make openrouter key the first field of agent block", and "block all agent
            // related stuff together and use if possible horisontal lines to delineate all what
            // relates to agent management and remove agent from every field title except the title
            // of the block (and put it in center)".
            //
            // **Above the first field, not between two fields.** The block opened with a divider
            // of its own, which drew a line directly under the history row and left the word AGENT
            // on the far side of it from everything the word governs.
            //
            // The word is in the title and in no row: inside this block it is the only thing on the
            // screen, so repeating it in six labels says nothing six times.
            Text(
                "AGENT",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "One model, one key, and what it is allowed to use. Everything here is about the " +
                    "agent on this phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // **First, because nothing below works without it.** The key was at the bottom of the
            // model rows, so the screen asked a person to choose a model and a provider before
            // telling them the list they were choosing from could not be read at all.
            KeyRow(viewModel = viewModel, onSetKey = onSetKey)
            RowDivider()

            // The model, the provider and the effort: [ModelSection] draws the three rows, each of
            // which opens the window that changes it — and that window is where models are added
            // and removed now, beside the list they change.
            ModelSection(viewModel = viewModel)
            RowDivider()

            // ---- how big the working context may get ----
            // Two numbers, and they are different acts. The trigger is a message to the agent —
            // "you are over, clear up" — and the agent decides what to remove. The hard limit is
            // the app trimming, and it is off unless somebody asks for it: an app that cuts a
            // conversation on its own loses the thing the person was working on, and the agent is
            // the one supposed to be choosing what to forget.
            Text("Context", style = MaterialTheme.typography.titleMedium)
            Text(
                "In tokens, counted at about three and a half bytes each — the phone has no tokenizer, so " +
                    "these are the app's own numbers rather than a bill. A pin smaller than your " +
                    "number lowers it rather than changing it: the number you typed stays.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LengthField(
                label = "Clear up at",
                value = settings.triggerTokens,
                placeholder = "a quarter",
                onValueChange = { settings.keepTrigger(it) },
            )
            LengthField(
                label = "Trim it myself at",
                value = settings.hardTokens,
                placeholder = "never",
                onValueChange = { settings.keepHard(it) },
            )
            // **The pin's real ceiling**, read from what the catalogue said about the active
            // model — the same number the runner builds its limits from. This line used to pass
            // the floor on purpose ("no model is being named here"), and the result was a screen
            // that told a person their million-token model would be trimmed at 96k.
            val catalogue = viewModel.modelInfo
            val limit = ContextBudget.Limits.of(
                triggerAsked = settings.triggerTokens.toLong(),
                hardAsked = settings.hardTokens.toLong(),
                ceiling = ContextBudget.ceiling(
                    pin = viewModel.modelConfig.active.provider,
                    contextLength = catalogue?.contextLength,
                    maxOutput = catalogue?.maxOutput,
                ),
            )
            Text(
                "Clear up at ${ContextBudget.short(limit.triggerAt)} · " +
                    "trim at ${ContextBudget.short(limit.hardAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            RowDivider()

            // ---- the two clocks: work on this phone, and the wait for the model ----
            //
            // **Two groups, because they measure two different things and one label each was not
            // enough.** Reported from the live log: "how wait for the model is different from stop
            // command after? is it how long it takes to produce first response?" - which is what
            // three timeouts in a row, in two units, with "wait" and "stop" used for both, will
            // always produce. The answer is not a sentence in a description: it is that these numbers
            // bound different machines, and the screen has to say which is which before it says how
            // long.
            //
            // **Everything is seconds now.** The model wait was in minutes and the command limits in
            // seconds, eight lines apart, with nothing saying they were different scales - so "4" and
            // "180" looked like the same kind of number.
            Text("While the app works on this phone", style = MaterialTheme.typography.titleMedium)
            Text(
                "These bound work the app does itself: reading files, walking folders, talking to " +
                    "git. The model is not involved - it is waiting for the app. All in seconds; " +
                    "blank means no limit.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LengthField(
                label = "A command may hold the conversation for (seconds, blank = no limit)",
                value = settings.foregroundSeconds,
                placeholder = "no limit",
                onValueChange = { settings.keepForeground(it) },
            )
            Text(
                text = if (settings.foregroundSeconds <= 0) {
                    "Now: a command runs until it finishes, and the turn waits for it. A number " +
                        "here is a choice about how long a turn may sit still."
                } else {
                    // **This sentence used to say "nothing half-written is kept", and it stopped
                    // being true the moment a slow command was handed to the background.** The
                    // report that changed it: "when command takes more than the timeout, agent gets a
                    // result that tells that this command takes longer than the timeout allows and
                    // will be returned on one of the next turns". A setting screen that describes the
                    // old behaviour is worse than one that describes none.
                    "Now: past ${settings.foregroundSeconds} s the command stops holding the turn and " +
                        "carries on in the background. The agent is told the path its result will be " +
                        "written to, and the result arrives with the next batch."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LengthField(
                label = "Stop a background command after (seconds, blank = never)",
                value = settings.backgroundSeconds,
                placeholder = "never",
                onValueChange = { settings.keepBackground(it) },
            )
            Text(
                text = if (settings.backgroundSeconds <= 0) {
                    "Now: a background command runs until it finishes. Its result is a file, so " +
                        "nothing is waiting on it."
                } else {
                    // **This one really does stop**, and the difference from the row above is the
                    // whole reason both exist: a command that already left the conversation has
                    // nowhere further to be handed to, so past this it dies and says why.
                    "Now: past ${settings.backgroundSeconds} s a background command is stopped for " +
                        "good - it has already left the conversation, so there is nowhere to hand it " +
                        "to. The file it was writing says it stopped."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RowDivider()

            // ---- the other clock: the wait for the model ----
            Text("While it talks to the model", style = MaterialTheme.typography.titleMedium)
            Text(
                "This bounds one request to OpenRouter: from the bytes going out to the answer " +
                    "coming back. Nothing on this phone is running while it waits - the app is " +
                    "sitting still, and the model is the one working. In seconds; blank means the " +
                    "default.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LengthField(
                label = "Wait for an answer for (seconds)",
                value = settings.timeoutSeconds,
                placeholder = "${SettingsStore.DEFAULT_TIMEOUT_SECONDS}",
                onValueChange = { settings.keepTimeout(it) },
            )
            Text(
                text = "Now: past ${settings.timeoutSeconds} s the request is dropped and tried " +
                    "again on another provider. An answer longer than this is never cut: a result " +
                    "too big for the conversation is written whole to a file, and its path is the " +
                    "answer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            // **What this number cannot yet tell apart, said plainly.** The provider sends nothing
            // until the completion is finished, so a request that never arrived and a model that is
            // thinking hard are the same silence to this app - and they need opposite responses: a
            // dropped request should be retried at once, a diligent model should be waited for,
            // because retrying throws away work already paid for. One number cannot express two
            // things, so the screen says so instead of implying the number is enough.
            Text(
                "A dropped request and a model thinking hard look the same from here: the provider " +
                    "says nothing until the answer is complete. So this one number covers both.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
            LengthField(
                label = "One result may carry (tokens)",
                value = settings.maxResult,
                placeholder = "6000",
                onValueChange = { settings.keepMaxResult(it) },
            )
            // **The clamp says which number is in force.** A result bigger than the model's input
            // window cannot be sent at all, so a bigger ask is not a bigger allowance — and a row
            // that showed only the ask would be the same "96k" mistake in a second unit.
            val resultLimit = ContextBudget.resultLimit(
                settings.maxResult,
                viewModel.modelInfo?.contextLength,
            )
            if (resultLimit.clamped) {
                Text(
                    "This model's input window is ${resultLimit.ceiling} tokens, so that is " +
                        "what is used.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RowDivider()

            // ---- how much of the record is kept ----
            // **The other half of Context, and it is a different act.** Clearing up takes a row out
            // of the conversation and leaves it in the record; this is where the record itself stops,
            // and what it drops is dropped for good. Two numbers were reported as one confusion —
            // "where is max session log size? because context trim just switches row out of context,
            // and session log limit removes them altogether" — so they are named apart here.
            // ---- keepalive, which is about money rather than a bound ----
            //
            // **Its own section, because it is not a timeout.** The two groups above bound how long
            // something may take; this one decides whether the app spends requests at all. A waiting
            // conversation is re-sent so the provider's cache stays warm, and the person pays for
            // that in requests and battery, so the switch, the interval and the ceiling are theirs.
            //
            // **"Keepalive", because that is what it does.** The agent used to say `wait` and the app
            // came back to ask again; the name described the model's act and hid the person's bill.
            // Reported from the live log: "what is agent pace description? I totally dont understand
            // what those words mean".
            Text("Keepalive", style = MaterialTheme.typography.titleMedium)
            Row(
                // **The whole row is the switch.** The words are the explanation and the switch is
                // the act, and a person who tapped the words has said what they meant.
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { settings.keepWarm(!settings.keepAlive) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Keep the conversation warm", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "While it waits for you the app re-sends the same conversation with a " +
                            "one-token answer: nothing is written down, the agent does not act, and " +
                            "your next sentence costs a cache read instead of a fresh prompt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.keepAlive,
                    onCheckedChange = { settings.keepWarm(it) },
                )
            }
            if (settings.keepAlive) {
                LengthField(
                    label = "Warm it every (seconds)",
                    value = settings.pollSeconds,
                    placeholder = "240",
                    onValueChange = { settings.keepPoll(it) },
                )
                Text(
                    text = "Now: every ${settings.pollMs / 1000} s, and at most " +
                        "${SettingsStore.MAX_POLL_SECONDS} s — past that the cache has already gone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                LengthField(
                    label = "For at most (minutes, blank = until you speak)",
                    value = settings.warmMinutes,
                    placeholder = "until you speak",
                    onValueChange = { settings.keepWarmFor(it) },
                )
                Text(
                    text = "Now: " + if (settings.warmMinutes <= 0) {
                        "until you speak."
                    } else {
                        "for ${settings.warmMinutes} min. An hour is about where keeping it warm " +
                            "stops being cheaper than letting it go cold."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            RowDivider()

            Text("The record", style = MaterialTheme.typography.titleMedium)
            Text(
                "agent/session.log is the evidence: every event, in order. Empty keeps all of it. " +
                    "Given a number, the oldest turns go once it passes that size and a row says how " +
                    "many — what goes there is gone, while clearing up only takes a row out of the " +
                    "conversation and leaves it in the file.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LengthField(
                label = "Keep the record under (MB, blank = everything)",
                value = settings.sessionMb,
                placeholder = "everything",
                onValueChange = { settings.keepSession(it) },
            )
            RowDivider()

            // ---- how often a thing that needs a person says so ----
            // The poll is the agent working; this is the app asking somebody to look at it. It
            // stops the moment the thing is dealt with, which is why the number is an interval and
            // not a count: a reminder that arrives while the question is still the newest thing on
            // the screen is noise.
            Text("Reminders", style = MaterialTheme.typography.titleMedium)
            Text(
                "When it needs you — it asked something, or it stopped on its own — it says so, " +
                    "and repeats at this interval until you deal with it. Empty says it once.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LengthField(
                label = "Repeat every (minutes)",
                value = settings.remindMinutes,
                placeholder = "once",
                onValueChange = { settings.keepRemind(it) },
            )
            RowDivider()

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // ---- what the app did ----
            //
            // Here rather than behind a long press, because the moment a person wants
            // it is the moment something did not work, and hunting for the screen that
            // explains it is the last thing they should have to do.
            TextButton(onClick = onOpenTrace) { Text("What the app did") }
            Text(
                "Every tap, what answered it, and anything that never got an answer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // **Only a debug build shows this at all.** A release build has no business
            // writing a running log of a person's tapping into a folder other apps can
            // read, so the switch does not exist there — not disabled, absent.
            if (viewModel.settings.liveTraceAvailable) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Write the log to Downloads", style = MaterialTheme.typography.bodyMedium)
                        // **The path is reported, not promised.** This used to read "at
                        // Download/MuffyCodes log/trace.log" — a sentence true of the
                        // intention and false of the phone, because a write can fall back
                        // to the app's own folder. A line naming where the file really is
                        // turns "I cannot find the log" into a path.
                        Text(
                            traceWhere(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = viewModel.settings.liveTrace,
                        onCheckedChange = { viewModel.keepLog(it) },
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // ---- the appearance (§10) ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Theme", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                Text("Light", style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.width(6.dp))
                Switch(
                    checked = settings.useDark,
                    onCheckedChange = { settings.selectDarkVariant(it) },
                )
                Spacer(Modifier.width(6.dp))
                Text("Dark", style = MaterialTheme.typography.labelSmall)
            }
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(AppTheme.entries) { theme ->
                    Swatch(
                        theme = theme,
                        selected = theme == settings.theme,
                        onClick = { settings.selectTheme(theme) },
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // ---- how much change history is kept ----
            //
            // **One sentence, and it is now true.** The old one described a local history "that
            // holds everything outside one", and that repository is gone: every file on this phone
            // is inside a project, and every project has its own history. Keeping the sentence
            // would have been the app describing a repository that no longer exists.
            Text("Change history", style = MaterialTheme.typography.titleMedium)
            Text(
                "How many versions are kept in each project. Empty keeps all of them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LengthField(
                label = "Versions kept",
                value = settings.historyLength,
                onValueChange = { settings.keepHistory(it) },
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // ---- every one on this phone, and one switch ----
            // The box has a Stop per project, and the strip says how many are on — but a person
            // who wants them all off should not have to open each project to do it.
            Text("Running", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    AgentSession.strip(viewModel.agentSessions).ifEmpty { "Nothing is running." },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { viewModel.stopAllAgents() },
                    enabled = viewModel.agentSessions.any { it.running },
                ) { Text("Stop all") }
            }
            RowDivider()

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // ---- what Android does to it in a pocket ----
            // One button, because nothing in the app can work around Doze and only the person can
            // exempt it. Here rather than on launch: an app that opens with a permissions dialog
            // teaches people to dismiss permissions dialogs.
            Text("Background", style = MaterialTheme.typography.titleMedium)
            // **One sentence, saying what it is for.** The old one explained Doze, the absence of
            // a fallback provider and what a stopped poll means — three true things, and a person
            // deciding whether to press a button needs one.
            Text(
                "So it keeps working with the screen off.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (exemptNow) "Exempt · it can poll" else "Not exempt · polls may fail",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (exemptNow) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.weight(1f),
                )
                // Offered only while it is needed: a button that goes on asking after the answer
                // is yes teaches people to dismiss the dialog.
                if (!exemptNow) {
                    TextButton(onClick = onAskBattery) { Text("Ask Android") }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // ---- whether a reminder can reach the tray at all ----
            // The battery exemption decides whether a poll can go out; this decides whether the
            // answer can be seen. From Android 13 a declared permission that was never granted
            // means a notification posted and silently dropped — a session that looks healthy.
            Text(
                "So you know when it needs you, without opening the app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (noticesNow) "On · a reminder reaches you" else "Off · nothing is shown",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (noticesNow) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    modifier = Modifier.weight(1f),
                )
                if (!noticesNow) {
                    TextButton(onClick = onAskNotices) { Text("Allow") }
                }
            }
        }
    }

    if (showingSwitch) {
        SwitchAccountDialog(
            current = account?.login,
            others = viewModel.accountList.map { it.login }.filter { it != account?.login },
            onPick = { login ->
                viewModel.switchTo(login)
                showingSwitch = false
            },
            onDismiss = { showingSwitch = false },
        )
    }
}

/**
 * The accounts this phone holds, to move between.
 *
 * When there are none, it says so and names the button that makes one, rather
 * than showing an empty window that looks broken.
 */
@Composable
private fun SwitchAccountDialog(
    current: String?,
    others: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Switch account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (others.isEmpty()) {
                    Text("There are no other accounts on this phone yet.")
                    Text(
                        "Use Add to sign in to another one first.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    if (current != null) {
                        Text(
                            "Now: $current",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    others.forEach { login ->
                        TextButton(onClick = { onPick(login) }, modifier = Modifier.fillMaxWidth()) {
                            Text(login)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** One swatch of the theme row: tap to select, framed when selected. */
@Composable
private fun Swatch(theme: AppTheme, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(theme.swatch)
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                    shape = RoundedCornerShape(12.dp),
                )
                .clickable(onClick = onClick),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = theme.displayName,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The line between two rows of the same block.
 *
 * Written once rather than at eleven call sites, because a block whose rows are separated by
 * lines that are each a little different is a block that reads as a list of accidents. It is
 * short, faint and inset, so it separates without becoming a section break of its own.
 */
@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 10.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/**
 * A number of versions to keep; an empty field means no limit.
 *
 * **Enter means done.** The number is applied as it is typed — there is no Save — so the
 * only thing pressing Enter can still do is put the keyboard away. Leaving the cursor
 * blinking in a field the person has finished with says the opposite: that something is
 * still waiting for them.
 */
@Composable
private fun LengthField(
    label: String,
    value: Int,
    placeholder: String = "all of it",
    onValueChange: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(SettingsStore.showLength(value)) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            if (typed.all { it.isDigit() }) {
                text = typed
                onValueChange(SettingsStore.parseLength(typed))
            }
        },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(
            onDone = {
                focus.clearFocus()
                keyboard?.hide()
            },
        ),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}
