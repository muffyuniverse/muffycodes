package com.example.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
 * Where the log is, said plainly — including when it is not where it promised.
 *
 * Read at composition rather than held in state: the answer changes when a write happens,
 * and a write is not a Compose event. Opening this screen re-reads it, which is when a
 * person looks.
 *
 * **There is no "off" branch any more.** The log is on in a debug build and off in a release one,
 * and a release build does not draw this block at all — so the only two answers left are "written
 * here" and "written there instead, because …".
 */
private fun traceWhere(): String {
    val where = Trace.where
    return when {
        where == null -> "Nothing has been written yet. One line per thing that happened."
        !where.contains(DownloadsSink.FOLDER) ->
            // **The reason, when the sink knows one.** "Downloads refused" was the whole story the
            // screen could tell, and it was the question that needed answering for a week: refused
            // *why*? A SecurityException and a full volume need different things done about them.
            "Writing to $where instead — " +
                ((Trace.sinkNow() as? DownloadsSink)?.refused ?: "Downloads refused") +
                ", so other apps cannot see it."
        else -> "Appended as it happens, at $where."
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onTestAccount: () -> Unit,
    /** Opens the trace: what the app was asked to do and what answered. */
    onOpenTrace: () -> Unit = {},
    /** The bug button, on every screen, always. */
    onReport: () -> Unit = {},
    /** Asks for the OpenRouter key: a screen that refuses screenshots, like the GitHub one. */
    onSetKey: () -> Unit = {},
    /**
     * Opens the vault: the list of named secrets, and the way to add one.
     *
     * **A door rather than a row of fields**, because a secret is written once and then never shown.
     * A screen that listed values would be the leak the store exists to prevent, so the list is a
     * separate place a person goes to deliberately.
     */
    onOpenSecrets: () -> Unit = {},
    /**
     * Opens the request forms — `shared/requests/chat/` in the browser.
     *
     * **A door to a folder rather than a list of rows**, because a request form is a file the person
     * and the agent both edit; the browser and the editor are the manager, and a window of fields here
     * would be a second way to write the same document.
     */
    onOpenTemplates: () -> Unit = {},
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
            // ---- the account is NOT here any more ----
            //
            // **The Add / Switch / Remove block lived here and is gone** (§13c.5 step 3). The main
            // menu is now the list of accounts and offers the same three acts — so two screens were
            // offering them, and the one reached by accident could switch the account out from under
            // the projects you were looking at.
            //
            // What is left is the one thing that was never account *management*: a debug build's test
            // account. It lived in the account window the three-lined button opened, and that window
            // is gone, so the capability came here rather than being quietly dropped with it. A
            // release build never sees this: `canMakeTestAccount` is false.
            if (viewModel.canMakeTestAccount) {
                TextButton(onClick = onTestAccount) { Text("Use the test account") }
            }

            // **No divider here, because the block below already opens with one.** This line was
            // here and the AGENT block drew its own heavier rule a few pixels under it, so the
            // screen showed two rules with a gap between them - which reads as a rendering fault
            // rather than as a section boundary. Visible in the settings scenario's picture. The
            // block's own rule is the one that means something: it is the heavier weight, and it is
            // what says where the agent's section starts.
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
            // **The block is drawn as a block: a rule above it, a rule below it.** The word AGENT
            // was already in the title and in no row, but nothing said where the block *ended* -
            // Keepalive, the record, reminders and the theme all followed at the same rhythm, so
            // "everything the agent needs" was a claim about the order rather than something the
            // screen showed. Reported from the live log: "block all agent related stuff together
            // and use if possible horisontal lines to delineate all what relates to agent
            // management".
            //
            // Two weights, and the difference is the meaning: the sections inside the block keep
            // the light divider, and the block itself is bounded by the heavier one. A screen where
            // every rule is the same weight has no blocks, only paragraphs.
            HorizontalDivider(
                modifier = Modifier.padding(top = 4.dp),
                color = MaterialTheme.colorScheme.outline,
            )
            // **"Chat agent", on the operator's instruction**: "just rename agent into Chat agent,
            // and Agent chat into Chat". The pair of names is the point: the block is the machine
            // that talks, and the block further down is how its conversation is drawn. Called
            // "AGENT" and "Agent chat", the second read as a part of the first.
            Text(
                "CHAT AGENT",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
            // **No sentence under the heading, on the operator's instruction**: "'one model, one
            // key...' row in agent block is to be removed". It described the block's scope to
            // somebody who can see the block, and the two heavier rules above and below already say
            // where it begins and ends. The word is the label; the rules are the extent.

            // **The minted key and the vault, together** — the operator's shape (§3.11.4): *"openrouter
            // oauth key minting should be an option in general settings in the same block with vault
            // secrets management button"*. They are the same kind of thing (a value the app uses
            // without ever writing it down), and minting is the vault's own verb with one step of
            // automation in front of it — so one block, two rows, no second mechanism.
            MintedKeyRow(viewModel = viewModel, onSetKey = onSetKey)
            RowDivider()

            SecretRow(viewModel = viewModel, onOpenSecrets = onOpenSecrets)
            RowDivider()

            // The model, the provider and the effort: [ModelSection] draws the three rows, each of
            // which opens the window that changes it — and that window is where models are added
            // and removed now, beside the list they change.
            ModelSection(viewModel = viewModel, onOpenTemplates = onOpenTemplates)
            // **The chat request is not configured here at all** — no address, no key, no shape, no
            // numbers. It is a file a worktree names (`Schema`), and the row above opens the folder.
            // This block used to hold a "Model connection" row for the general scope; that row existed
            // because the llm settings were fields with an inheritance chain, and a file needs neither.

            // ---- how big the working context may get ----
            // Two numbers, and they are different acts. The trigger is a message to the agent —
            // "you are over, clear up" — and the agent decides what to remove. The hard limit is
            // the app trimming, and it is off unless somebody asks for it: an app that cuts a
            // conversation on its own loses the thing the person was working on, and the agent is
            // the one supposed to be choosing what to forget.
            // **A heading with one sentence under it, because the numbers it used to hold left.**
            // Compaction is a property of a chat — which model, what effort, how much context — so
            // the three limits live in the request form beside the model. What stays here is the one
            // thing true of every count on this screen: they are estimates.
            Text("Context", style = MaterialTheme.typography.titleMedium)
            Text(
                "Token counts are approximate.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RowDivider()

            ClocksSection(settings)

            WebSection(settings)
            Text("Secrets the agent may use", style = MaterialTheme.typography.titleMedium)
            Text(
                // **The rule that makes everything else true**, and it is the app's own now rather
                // than a host's: a secret in the vault carries the host it may be sent to, so a
                // secret for one host is never attached to a request to another. That is what
                // replaced the environment-and-reviewers machinery — the app no longer holds a
                // workflow's key at all, so there is no runner to be careful about.
                "A secret is filed under the host it belongs to. When the agent reaches the network " +
                    "it must name the secret it is using, and the app refuses before sending if the " +
                    "host in the address is not the host on the secret — so a key for one server " +
                    "cannot travel to another, however the agent was talked into it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "A secret with no host travels nowhere: it is used on this phone, and that is all.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            KeepaliveSection(settings)
            RowDivider()

            LogAndTraceSection(settings, viewModel, onOpenTrace)

            ChatSection(settings)

            HistorySection(settings)

            // ---- back to what the app shipped with ----
            //
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            RunningSection(viewModel)
            BackgroundSection(exemptNow, onAskBattery, noticesNow, onAskNotices)

            // **The last thing on the screen, the operator's own correction**: "back to defaults
            // should be the last element on the settings list". It was above Running and Background,
            // which are things a person uses - so the one control that undoes every choice sat in
            // the middle of the choices rather than after them.
            //
            // **Asked before it happens**, because it is a single press that discards a screen of
            // preferences and there is no undo for one.
            ResetSettings(settings)
        }
    }

}

/**
 * **The two groups of clocks: work on this phone, and the wait for the model.**
 *
 * Two groups because they bound two different machines, and one heading each was not enough: three
 * timeouts in a row, in two units, with "wait" and "stop" used for both, is what made them read as one
 * subject. Everything is seconds, and each group is named for what it bounds.
 */
@Composable
private fun ClocksSection(settings: SettingsStore) {
    Text("Command execution timeouts", style = MaterialTheme.typography.titleMedium)
    Text(
        "How long work on this phone may take. Blank means no limit.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    LengthField(
        label = "Foreground command timeout (seconds)",
        value = settings.foregroundSeconds,
        placeholder = "no limit",
        onValueChange = { settings.keepForeground(it) },
    )
    Text(
        text = if (settings.foregroundSeconds <= 0) {
            "Now: the turn waits for every command to finish."
        } else {
            "Now: past ${settings.foregroundSeconds} s it carries on in the background and " +
                "its result arrives with a later turn."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    LengthField(
        label = "Background command timeout (seconds)",
        value = settings.backgroundSeconds,
        placeholder = "never",
        onValueChange = { settings.keepBackground(it) },
    )
    Text(
        text = if (settings.backgroundSeconds <= 0) {
            "Now: a background command runs until it finishes."
        } else {
            "Now: past ${settings.backgroundSeconds} s it is stopped for good, and the file " +
                "it was writing says so."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    RowDivider()

    Text("Model response timeouts", style = MaterialTheme.typography.titleMedium)
    Text(
        "How long the app waits for the model. Blank means the default.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // **An idle timeout in the strict sense**, and the sentence under it says the one thing a person
    // needs to know about that: it restarts on every packet, so a slow answer is safe and a dead one
    // is not.
    LengthField(
        label = "Response timeout (seconds)",
        value = settings.responseSeconds,
        placeholder = "${SettingsStore.DEFAULT_RESPONSE_SECONDS}",
        onValueChange = { settings.keepResponse(it) },
    )
    Text(
        text = "Now: ${settings.responseSeconds} s of silence ends the request - before the " +
            "first packet or between any two. A steady answer is never cut off.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
    )
    // **A different number from the two above**: they bound a single request, and this bounds how long
    // a *failure* is retried before the agent stops and asks for help. A dropped request is not a
    // stopped agent, so the window is how long the ladder may go on.
    LengthField(
        label = "Reconnect timeout (seconds)",
        value = settings.reconnectSeconds,
        placeholder = "${SettingsStore.DEFAULT_RECONNECT_SECONDS}",
        onValueChange = { settings.keepReconnect(it) },
    )
    Text(
        text = "Now: error responses are retried for ${settings.reconnectSeconds / 60} min " +
            "before the agent stops and asks for help. An account-level error such as an " +
            "empty balance is retried here, not on another provider.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
    )
    LengthField(
        label = "One result may carry (tokens)",
        value = settings.maxResult,
        placeholder = "6000",
        onValueChange = { settings.keepMaxResult(it) },
    )
    RowDivider()
}

/**
 * **How the conversation is drawn**, which is a reading preference and not a limit on the evidence.
 *
 * Its own block because it began inside the session-log section, and that made a display choice look
 * like a bound on what is kept. Nothing here changes what is stored or what the agent is sent.
 */
@Composable
private fun ChatSection(settings: SettingsStore) {
    Text("Chat", style = MaterialTheme.typography.titleMedium)
    Text(
        "How the conversation is drawn. Nothing here changes what is kept or what the " +
            "agent is sent.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // **In lines, because that is what a person reads in.** A cap expressed as a fraction of the
    // window would be a statement about the phone; a cap in lines is a statement about the message.
    LengthField(
        label = "A message may be this many lines (blank = no limit)",
        value = settings.bubbleLines,
        placeholder = "no limit",
        onValueChange = { settings.keepBubbleLines(it) },
    )

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
}

/**
 * **How much change history is kept, and how much of the request log.**
 *
 * Two fields and not one, because they are different files with different shapes: the history is
 * versions of the person's work, and the log is what the model cost. One number for both would make a
 * person choose between keeping their files and keeping their bill.
 */
@Composable
private fun HistorySection(settings: SettingsStore) {
    Text("Change history", style = MaterialTheme.typography.titleMedium)
    Text(
        "Every version of every file is kept, text and pictures alike. A picture is stored " +
            "once by its content, so an unchanged picture costs nothing the second time.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Text(
        "Request log",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 12.dp),
    )
    Text(
        "Every response the agent receives, with what it cost. Empty keeps all of them.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    LengthField(
        label = "Days kept",
        value = settings.requestDays,
        placeholder = "all of them",
        onValueChange = { settings.keepRequests(it) },
    )
}

/**
 * **How much of the record is kept, and the way to the account of what the app did.**
 *
 * The two are one block because they answer one question — "what is there to look at afterwards" —
 * and because the trace button belongs where a person who is already wondering why something
 * happened will find it.
 */
@Composable
private fun LogAndTraceSection(
    settings: SettingsStore,
    viewModel: AppViewModel,
    onOpenTrace: () -> Unit,
) {
    Text("Session log", style = MaterialTheme.typography.titleMedium)
    Text(
        "The most the session log may hold, in tokens. Over the limit, the oldest messages " +
            "are deleted until it is under it. Blank keeps everything.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    LengthField(
        label = "Keep the session log under (tokens, blank = everything)",
        value = settings.sessionTokens,
        placeholder = "everything",
        onValueChange = { settings.keepSession(it) },
    )
    RowDivider()

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
    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

    // **The report exists in every build, and writes a file in only one of them.** The ring is a
    // person's own account of what just happened and is worth having anywhere; the file is what
    // makes a report checkable, and only a debug build has it. The words say which is which,
    // because two different things called the same name is what confused the operator.
    TextButton(onClick = onOpenTrace) { Text("What the app did") }
    Text(
        if (BuildConfig.DEBUG) {
            "Every tap, what answered it, and anything that never got an answer. " +
                "This build also writes it to Downloads as it happens."
        } else {
            "Every tap, what answered it, and anything that never got an answer — this " +
                "session only. A release build keeps no file: share it now if you want it kept."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // **No switch.** A debug build writes, a release build does not, and there is nothing here to
    // decide. The switch cost a week: every session where it was off produced a paste that looked
    // healthy with no file behind it, and the person had to remember it was off.
    if (viewModel.settings.liveTrace) {
        // **The path is reported, not promised** — a write can fall back to the app's own folder,
        // and a line naming where the file really is turns "I cannot find the log" into a path.
        Text(
            traceWhere(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
}

/**
 * **What the app spends to keep a conversation warm, and how long the session exists at all.**
 *
 * Two ceilings, and they are separate on purpose. The first decides how long the app pays to keep the
 * context cached; the second decides when the agent switches itself off. A person may want an agent
 * reachable all day and pay nothing to keep it warm, so the second is deliberately **outside** the
 * `if (keepAlive)` block — a field inside it would make one switch mean two things.
 */
@Composable
private fun KeepaliveSection(settings: SettingsStore) {
    Text("Keepalive", style = MaterialTheme.typography.titleMedium)
    Row(
        // **The whole row is the switch.** The words are the explanation and the switch is the act,
        // and a person who tapped the words has said what they meant.
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

    LengthField(
        label = "Switch off after waiting (minutes, blank = never)",
        value = settings.waitMinutes,
        placeholder = "never",
        onValueChange = { settings.waitFor(it) },
    )
    Text(
        text = "Now: " + if (settings.waitMinutes <= 0) {
            "an idle agent waits until you speak or stop it."
        } else {
            "an agent that waits ${settings.waitMinutes} min switches itself off. " +
                "It says so in the strip, and nothing is lost: the record is on disk."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * **Where the agent may go, and how far.** Everything above this bounds how much or how long; these
 * say what it may reach — and the default is the public internet only.
 *
 * **The private-network switch is off by default, deliberately.** A page that steers the agent at
 * `192.168.1.1` has reached the router without either of them asking, so a home network is granted by
 * a person rather than assumed by the app.
 *
 * **The browser is the phone's own Chromium**, which costs nothing to ship and the Play Store updates
 * on its own. There is no switch for that: a phone without a WebView gets a sentence saying so.
 */
@Composable
private fun WebSection(settings: SettingsStore) {
    Text("The web", style = MaterialTheme.typography.titleMedium)
    Text(
        "The agent can read a web page and photograph it, using the browser this phone " +
            "already has. Its answers are saved under agent/browser/.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { settings.keepBrowsePrivate(!settings.browsePrivate) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Let it read this phone's network", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Off, it may read the public web and nothing else. On, it may also read " +
                    "this phone (127.0.0.1) and addresses on your home network — a NAS, a " +
                    "router, a server. Turn it on only if you want that.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = settings.browsePrivate,
            onCheckedChange = { settings.keepBrowsePrivate(it) },
        )
    }
    LengthField(
        label = "A page may take (seconds)",
        value = settings.browseSeconds,
        placeholder = "${SettingsStore.DEFAULT_BROWSE_SECONDS}",
        onValueChange = { settings.keepBrowseSeconds(it) },
    )
    Text(
        text = "Now: ${settings.browseSeconds} s per page. Past that the page is reported " +
            "as too slow rather than left hanging.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
    )
    // **The download cap sits with the other network limits** because it is the same kind of number:
    // a bound the person sets and the app enforces. It was a constant, and a cap nobody can change is
    // the tool's opinion rather than the person's.
    LengthField(
        label = "One download may be (MB)",
        value = settings.fetchMegabytes,
        placeholder = "${SettingsStore.DEFAULT_FETCH_MB}",
        onValueChange = { settings.keepFetchMegabytes(it) },
    )
    Text(
        text = "Now: ${settings.fetchMegabytes} MB per download, between " +
            "${SettingsStore.MIN_FETCH_MB} and ${SettingsStore.MAX_FETCH_MB}. Larger is " +
            "refused with the size named rather than cut short, so a half-file never " +
            "arrives looking whole.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { settings.keepHttp(!settings.httpAllowed) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Let it use the network", style = MaterialTheme.typography.bodyMedium)
            Text(
                "On, the agent can send a request to an address and keep the answer in " +
                    "agent/http/ — a dependency tarball, a release asset, an API call, " +
                    "anything a person gives it a link to. Off, the tool is not offered at " +
                    "all rather than offered and refused. Reading a page is a different " +
                    "tool, and is unaffected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = settings.httpAllowed,
            onCheckedChange = { settings.keepHttp(it) },
        )
    }
}

/**
 * **Every agent on this phone, and the two switches for all of them.**
 *
 * The box has a Stop per project and the strip says how many are on, but a person who wants them all
 * off should not have to open each project to do it. Both directions, because the waiting ceiling
 * stops sessions on its own: somebody coming back to an agent that switched itself off an hour ago
 * needs the way back on, and opening each project to type something is not that.
 */
@Composable
private fun RunningSection(viewModel: AppViewModel) {
    Text("Running", style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            AgentSession.strip(viewModel.agentSessions).ifEmpty { "Nothing is running." },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { viewModel.startAllAgents() },
            enabled = viewModel.agentSessions.any { !it.running },
        ) { Text("Start all") }
        TextButton(
            onClick = { viewModel.stopAllAgents() },
            enabled = viewModel.agentSessions.any { it.running },
        ) { Text("Stop all") }
    }
    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
}

/**
 * **The two things Android decides and the app cannot**: whether a poll may run with the screen off,
 * and whether a reminder can reach the tray.
 *
 * Both are one button, offered only while it is needed — a control that goes on asking after the
 * answer is yes teaches people to dismiss the dialog. Nothing here works around Doze; only the person
 * can exempt the app, and only they can grant the notification permission.
 */
@Composable
private fun BackgroundSection(
    exemptNow: Boolean,
    onAskBattery: () -> Unit,
    noticesNow: Boolean,
    onAskNotices: () -> Unit,
) {
    Text("Background", style = MaterialTheme.typography.titleMedium)
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
        if (!exemptNow) {
            TextButton(onClick = onAskBattery) { Text("Ask Android") }
        }
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

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

/**
 * **Every setting back to what the app shipped with** — the one act on this screen that discards
 * things, so it is the one that asks first.
 */
@Composable
private fun ResetSettings(settings: SettingsStore) {
    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
    Text("Back to defaults", style = MaterialTheme.typography.titleMedium)
    Text(
        "Every setting on this screen returns to what the app shipped with. Files, " +
            "projects and the record are not touched.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    var confirming by remember { mutableStateOf(false) }
    TextButton(onClick = { confirming = true }) {
        Text("Reset settings", color = MaterialTheme.colorScheme.error)
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Reset every setting?") },
            text = {
                Text(
                    "The themes, the limits, the timeouts and the agent's settings all go " +
                        "back to what the app shipped with. Nothing you have written is " +
                        "touched.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    settings.reset()
                }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            },
        )
    }
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
