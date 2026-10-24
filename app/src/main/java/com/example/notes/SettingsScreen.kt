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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * Settings: the account, the appearance, and how much history is kept — and
 * nothing else (§10). Pull, Delete and Sync are not here: they live where the
 * projects are.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onRemoveAccount: () -> Unit,
    onTestAccount: () -> Unit,
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
                TextButton(onClick = onSignIn) { Text("Add") }
                if (account != null) {
                    TextButton(onClick = { showingSwitch = true }) { Text("Switch") }
                    TextButton(onClick = onRemoveAccount) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
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
                "How many changes are kept in each project's repo, and in the local " +
                    "one that holds everything else. Empty keeps all of them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LengthField(
                label = "Changes kept",
                value = settings.historyLength,
                onValueChange = { settings.keepHistory(it) },
            )
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

/** A number of versions to keep; an empty field means no limit. */
@Composable
private fun LengthField(label: String, value: Int, onValueChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(SettingsStore.showLength(value)) }
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
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}
