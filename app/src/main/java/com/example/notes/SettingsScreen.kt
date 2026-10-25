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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.ImeAction

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
) {
    val settings = viewModel.settings
    val account = viewModel.account
    var showingSwitch by remember { mutableStateOf(false) }

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
            // Said in three clauses, because it is one number doing one job:
            // every change is committed on this phone — into a project's own repo
            // when the file is in one, and into the local repo behind the account
            // for everything else — and this is how many of those are kept.
            Text("Change history", style = MaterialTheme.typography.titleMedium)
            Text(
                // "repo" was the folder's old name, and a concept most people do not
                // hold; the sentence now says what the number governs in plain words.
                "How many changes are kept in each project, and in the local history " +
                    "that holds everything outside one. Empty keeps all of them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LengthField(
                label = "Changes kept",
                value = settings.historyLength,
                onValueChange = { settings.keepHistory(it) },
            )
            Spacer(Modifier.height(24.dp))

            // ---- the agent's model ----
            //
            // Three rows and a file. The file is the store, so these are editors of it
            // rather than a second copy of the same three values: editing the file by hand
            // and then opening this screen shows what the file says, which is the only way
            // the two can never disagree.
            ModelSection(viewModel = viewModel, onSetKey = onSetKey)
            Spacer(Modifier.height(24.dp))
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
 * A number of versions to keep; an empty field means no limit.
 *
 * **Enter means done.** The number is applied as it is typed — there is no Save — so the
 * only thing pressing Enter can still do is put the keyboard away. Leaving the cursor
 * blinking in a field the person has finished with says the opposite: that something is
 * still waiting for them.
 */
@Composable
private fun LengthField(label: String, value: Int, onValueChange: (Int) -> Unit) {
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
        placeholder = { Text("all of it") },
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
