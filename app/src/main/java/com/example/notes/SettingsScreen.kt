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
    onEditIdentity: () -> Unit,
) {
    val settings = viewModel.settings
    val account = viewModel.account

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
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onSignIn) { Text("Add") }
                if (account != null) {
                    TextButton(onClick = onEditIdentity) { Text("Identity") }
                    TextButton(onClick = onRemoveAccount) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            if (viewModel.accountList.size > 1) {
                Text("Switch to", style = MaterialTheme.typography.labelMedium)
                viewModel.accountList
                    .filter { it.login != account?.login }
                    .forEach { other ->
                        TextButton(onClick = { viewModel.switchTo(other.login) }) {
                            Text(other.login)
                        }
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

            // ---- how much history is kept ----
            Text("History kept", style = MaterialTheme.typography.titleMedium)
            Text(
                "How many versions are kept. An empty field keeps everything. " +
                    "The length is applied at the next change.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LengthField(
                label = "This phone's history",
                value = settings.appHistoryLength,
                onValueChange = { settings.setAppHistoryLength(it) },
            )
            LengthField(
                label = "Each project's history",
                value = settings.projectHistoryLength,
                onValueChange = { settings.setProjectHistoryLength(it) },
            )
            Spacer(Modifier.height(24.dp))
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
