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

            // **First, because nothing below works without it.** The key was at the bottom of the
            // model rows, so the screen asked a person to choose a model and a provider before
            // telling them the list they were choosing from could not be read at all.
            KeyRow(viewModel = viewModel, onSetKey = onSetKey)
            RowDivider()

            // **Under the key, because they are the same kind of thing** — a value the app uses
            // without ever writing it down — and because the agent needs both before its tools can
            // reach anything: one key talks to the model, the other talks to everything else.
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
            Text("Context", style = MaterialTheme.typography.titleMedium)
            // **One line, on the operator's instruction**: "on token calculation explanation just
            // write that 'token counts are approximate' - no need for lengthy paragraphs". The
            // paragraph explained the app's own arithmetic to a person who wanted to set a limit;
            // the one thing that changes how they read every number below is that the numbers are
            // estimates, and that is now the whole sentence.
            Text(
                "Token counts are approximate.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // **Three numbers: one destination, two triggers.** The operator's correction
            // (2026-10-29): "both forget and trim compactions should have same target but different
            // triggers (so that forget triggers much earlier)". The target is where the context
            // should END UP; the two triggers are where each hand starts working. One destination
            // means whichever acts, the context lands in the same place.
            //
            // **Forget first, trim second, and the target below them** - written in the order the
            // two hands act, and **not an order the app enforces any more**. The numbers are used
            // as they are typed; a target above a trigger asks for something the trigger already
            // passed, and the app keeps asking until the context is under the trigger. A trim set
            // BELOW the forget point is a mode rather than a mistake: the agent is never asked, so
            // the app does all the compacting, which is what somebody who would rather the app
            // decide is choosing.
            LengthField(
                label = "Compaction target",
                value = settings.targetTokens,
                placeholder = "60000",
                onValueChange = { settings.keepTarget(it) },
            )
            LengthField(
                label = "Forget compaction at",
                value = settings.triggerTokens,
                placeholder = "100000",
                onValueChange = { settings.keepTrigger(it) },
            )
            LengthField(
                label = "Trim compaction at",
                value = settings.hardTokens,
                placeholder = "never",
                onValueChange = { settings.keepHard(it) },
            )
            // **The line that resolved the three numbers is gone**, and so is the clamp it showed.
            // It existed to say "you asked for 500k and this pin allows 105k, so 105k is what is
            // used"; nothing is clamped now, so the three numbers above *are* the three numbers.
            // A line repeating them in a second unit would be decoration.
            // **Said out loud when the trim comes first**, because it is a legitimate choice that
            // looks like a mistake: with the trim below the forget point the agent is never asked,
            // so the app does all the compacting. Somebody who meant it should be told it is
            // working as intended; somebody who did not should see it immediately.
            // **Read as written**, because that is the only reading left: `hard <= trigger` is a
            // comparison of the person's two numbers, not of two numbers the app derived.
            val hardAt = settings.hardTokens.toLong()
            val triggerAt = settings.triggerTokens.toLong()
            if (hardAt > 0 && triggerAt > 0 && hardAt <= triggerAt) {
                Text(
                    "The app trims before the agent is ever asked, so the agent never compacts. " +
                        "That is what these numbers say; raise the trim to let the agent go first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // **The fate lines are gone, on the operator's instruction**: "settings context
            // block: remove what has happened to the context element and its fields - not needed".
            //
            // They answered a question the limits above do not raise: a person setting a limit
            // wants to know where it is, not the history of the last time it fired. What a
            // compaction did is in the record, with a timestamp, which is where a person checking
            // it will look anyway - the screen was offering a summary of a file they can read.
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
            // **Named for what it bounds, in the operator's words.** Report: "rename while this app
            // works on this phone into command execution timeouts. fields: foreground command
            // timout, background command timeout". The old heading described the phone rather than
            // the thing being limited, which is why it needed a paragraph to say what it meant.
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
                    // **This sentence used to say "nothing half-written is kept", and it stopped
                    // being true the moment a slow command was handed to the background.** The
                    // report that changed it: "when command takes more than the timeout, agent gets a
                    // result that tells that this command takes longer than the timeout allows and
                    // will be returned on one of the next turns". A setting screen that describes the
                    // old behaviour is worse than one that describes none.
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
                    // **This one really does stop**, and the difference from the row above is the
                    // whole reason both exist: a command that already left the conversation has
                    // nowhere further to be handed to, so past this it dies and says why.
                    "Now: past ${settings.backgroundSeconds} s it is stopped for good, and the file " +
                        "it was writing says so."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RowDivider()

            // ---- the other clock: the wait for the model ----
            // **Named for the thing that is slow, not for what the app is doing meanwhile.** Report:
            // "while it talks to the model rename to model response timeouts", and then the three
            // fields by name: "connection timeout, stalling timeout, reconnect timeout".
            Text("Model response timeouts", style = MaterialTheme.typography.titleMedium)
            Text(
                "How long the app waits for the model. Blank means the default.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // **Two fields, not three, because two clocks became one.** The operator merged the
            // connection wait and the stalling wait: "lets merge connection and stalling timeouts
            // into a single response timeout that tracks time between request and first response
            // packet" - and, asked what then bounds a model that dies mid-answer, "one number that
            // also restarts per packet".
            //
            // So this is an idle timeout in the strict sense, and the sentence under it says the one
            // thing a person needs to know about that: it restarts on every packet, so a slow answer
            // is safe and a dead one is not.
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
            // **The other half of the same question**, and it is a different number: the two above
            // bound a single request, and this bounds how long a *failure* is retried before the
            // agent stops and asks for help. A dropped request is not a stopped agent — the ladder
            // at one, two and four seconds exists so a tunnel does not end a session — so the
            // window is how long that is allowed to go on.
            LengthField(
                label = "Reconnect timeout (seconds)",
                value = settings.reconnectSeconds,
                placeholder = "${SettingsStore.DEFAULT_RECONNECT_SECONDS}",
                onValueChange = { settings.keepReconnect(it) },
            )
            // **Said to the point, on the operator's instruction**: "in reconnect 'now' description
            // write that it is about handling error responses - make this description to the point".
            // The old sentence explained what an idle agent is, which is a different subject; this
            // one says what the number is for.
            Text(
                text = "Now: error responses are retried for ${settings.reconnectSeconds / 60} min " +
                    "before the agent stops and asks for help. An account-level error such as an " +
                    "empty balance is retried here, not on another provider.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
            // **The sentence that used to be here is gone, and its going is the point.** It said a
            // dropped request and a model thinking hard look the same from here, because the
            // provider said nothing until the answer was complete. That was true of a non-streamed
            // request and it is not true now: the answer arrives as it is written, so the two are
            // different silences with a clock each, and leaving the paragraph behind would be the
            // screen apologising for a limitation the app no longer has.
            LengthField(
                label = "One result may carry (tokens)",
                value = settings.maxResult,
                placeholder = "6000",
                onValueChange = { settings.keepMaxResult(it) },
            )
            // **No clamp line any more.** A result bigger than the model's window used to be
            // reduced to that window and said so; the number is now used as written, and a server
            // that will not take it says so itself at the request that carries it.
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
            // ---- the browser, which is a capability rather than a bound ----
            //
            // **Its own section, because the switch is not a limit.** Everything above says how much
            // or how long; this says *where the agent may go*, and the default is the public
            // internet. A page that could steer the agent at `192.168.1.1` has reached the router
            // without either of them asking, so the private ranges are granted by a person rather
            // than assumed by the app.
            //
            // **The agent browses with the phone's own Chromium**, which costs nothing to ship and
            // is updated by the Play Store independently. There is no switch for that: a phone
            // without a WebView gets a sentence saying so, and one with it can read the web.
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
            // **The download cap, next to the other network limits** because it is the same kind of
            // number: a bound the person sets and the app enforces. It was a constant until the
            // operator asked for it to be theirs — "a hundred" refused archives that were genuinely
            // a dependency, and a cap nobody can change is the tool's opinion rather than the
            // person's.
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

            // **"Session log", not "The record".** The operator asked directly: "if 'the record'
            // block means session log, then call it so". It does, and `agent/session.log` is the
            // file's own name, so the label and the file now agree.
            Text("Session log", style = MaterialTheme.typography.titleMedium)
            // **Precise, on the operator's instruction**: "session log description is unreadable -
            // make it precise: defines max session log size in tokens, removes oldest messages
            // until session is under limit".
            //
            // The paragraph it replaces explained the *unit* and the difference from clearing up,
            // which is background rather than the thing the field does. What a person needs before
            // typing a number is the rule: what it bounds, and what it deletes.
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
            // **One rule, not two.** This block used to end with a `RowDivider()` and then the
            // section rule - a faint line, a gap, then a heavier one, which reads as a mistake
            // rather than as an ending. The section rule already ends the block, so the row rule
            // is gone. Report: "reminders and running blocks have 2 horizontal lines under them".
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // ---- what the app did ----
            //
            // Here rather than behind a long press, because the moment a person wants
            // it is the moment something did not work, and hunting for the screen that
            // explains it is the last thing they should have to do.
            // **The report exists in every build, and it writes a file in only one of them.**
            //
            // The operator's confusion, and it was earned: *"why then release version has trace
            // logging at all... confusing"*. A release build drew this button and a ring of events,
            // and wrote nothing anywhere — so pressing Report produced a paste with no file behind
            // it, on the build that cannot produce a file. Two different things called the same name.
            //
            // **Both stay, and the words say which is which.** The ring is a person's own account of
            // what just happened, and it is worth having on any build. The file is the thing that
            // makes a report *checkable*, and only a debug build has it.
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
            // **No switch.** The operator's question: *"why is there a switch? dev version is for
            // debugging - trace log should be on by default."* A debug build writes; a release build
            // does not; there is nothing here to decide.
            //
            // The switch cost a week. Every session where it was off produced a paste that looked
            // like a healthy session with no file behind it — and the person then had to *remember*
            // it was off, which is the thing nobody remembers.
            //
            // **The line stays, and it is the useful half.** It says where the log actually went,
            // and when it went somewhere other than promised, why. That sentence is what a person
            // needs; the switch was never what they needed.
            if (viewModel.settings.liveTrace) {
                // **The path is reported, not promised.** This used to read "at
                // Download/MuffyCodes log/trace.log" — a sentence true of the intention and false of
                // the phone, because a write can fall back to the app's own folder. A line naming
                // where the file really is turns "I cannot find the log" into a path.
                Text(
                    traceWhere(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // ---- the agent chat, as a block of its own ----
            //
            // **It has a heading and a rule of its own, on the operator's instruction**: "maximum
            // displayed message height somehow ended up in session log block but it need a new
            // separate agent chat block". It began in the session block, which is about how much of
            // the record is *kept* - and this bounds something else entirely: how much of one
            // message is *drawn*. Two different questions in one block made a reading preference
            // look like a limit on the evidence.
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            Text("Chat", style = MaterialTheme.typography.titleMedium)
            Text(
                "How the conversation is drawn. Nothing here changes what is kept or what the " +
                    "agent is sent.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // **In lines, because that is what a person reads in.** A cap expressed as a fraction of
            // the window would be a statement about the phone; a cap in lines is a statement about
            // the message. Zero means no cap, which is the honest default on a screen that scrolls.
            LengthField(
                label = "A message may be this many lines (blank = no limit)",
                value = settings.bubbleLines,
                placeholder = "no limit",
                onValueChange = { settings.keepBubbleLines(it) },
            )

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
            // **There is no history length, and the sentence says why rather than vanishing.**
            //
            // A setting that shortened the history existed because a phone cannot hold every version
            // of everything. That is still true of pictures, and it is answered by keeping exactly one
            // version of each rather than by a number a person has to choose — so the choice moved
            // from this screen into the file's kind. Text is unbounded because text compresses 120x,
            // and squashing a history is git's job rather than a note-taking app's.
            Text("Change history", style = MaterialTheme.typography.titleMedium)
            Text(
                "Every version of every file is kept, text and pictures alike. A picture is stored " +
                    "once by its content, so an unchanged picture costs nothing the second time.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // ---- how much of the request log is kept ----
            //
            // **Its own field, not part of the history limit.** They are different files with
            // different shapes: the history is versions of the person's work, and the log is what
            // the model cost. One number for both would make a person choose between keeping their
            // files and keeping their bill.
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

            // ---- back to what the app shipped with ----
            //
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
            // **One rule here too.** Same defect as Reminders, found in the same report.
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

            // **The last thing on the screen, the operator's own correction**: "back to defaults
            // should be the last element on the settings list". It was above Running and Background,
            // which are things a person uses - so the one control that undoes every choice sat in
            // the middle of the choices rather than after them.
            //
            // **Asked before it happens**, because it is a single press that discards a screen of
            // preferences and there is no undo for one.
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
            Text("Back to defaults", style = MaterialTheme.typography.titleMedium)
            Text(
                "Every setting on this screen returns to what the app shipped with. Files, " +
                    "projects and the record are not touched.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            var confirmingReset by remember { mutableStateOf(false) }
            TextButton(onClick = { confirmingReset = true }) {
                Text("Reset settings", color = MaterialTheme.colorScheme.error)
            }
            if (confirmingReset) {
                AlertDialog(
                    onDismissRequest = { confirmingReset = false },
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
                            confirmingReset = false
                            settings.reset()
                        }) { Text("Reset") }
                    },
                    dismissButton = {
                        TextButton(onClick = { confirmingReset = false }) { Text("Cancel") }
                    },
                )
            }
        }
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
