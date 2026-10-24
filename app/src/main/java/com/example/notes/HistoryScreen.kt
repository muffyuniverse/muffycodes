package com.example.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

/**
 * The versions of one file, one at a time.
 *
 * This used to be a list with a Restore button on every row, and a version was
 * something you read a one-line preview of. What a person actually does with a
 * history is **step through it**: back a version, back another, forward again,
 * and stop where the file was right. So the screen is a viewer with two arrows,
 * and nothing else in the app carries state while it is open.
 *
 * **Both arrows move one version.** `<` is older, `>` is newer — the same way a
 * page is turned — and the count says where you are, so the newest version is
 * "version 5 of 5" even though it is stored first.
 *
 * **The newest version is what is on disk.** Every change is committed as it
 * happens ([AppViewModel.saveEditor] and friends), so there is no separate
 * "uncommitted" state to show as an extra step.
 *
 * **Restoring touches this file and nothing else.** The preview is read-only;
 * nothing is written until the button is pressed. That is the promise §4 makes
 * about folder organisation, applied to the commonest case of it.
 *
 * Which history answers is decided by where the file is, not by the screen: a
 * file inside a project's worktree is versioned by that project's repository, and
 * everything else — local files, files beside a worktree, folders — by the
 * account's own history (§4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: AppViewModel,
    title: String,
    onBack: () -> Unit,
) {
    // Recomputed when the file changes or something is restored, not on every frame:
    // `versions()` opens the repository and walks it.
    val versions = remember(viewModel.historyPath, viewModel.historyGeneration) {
        viewModel.versions()
    }
    var at by remember(viewModel.historyPath) { mutableStateOf(0) }
    val index = at.coerceIn(0, maxOf(versions.lastIndex, 0))
    val shown = versions.getOrNull(index)
    val kind = viewModel.historyKind
    val isFolder = viewModel.historyIsFolder

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("Done") }
                },
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (versions.isNotEmpty()) {
                            Text(
                                text = "version ${versions.size - index} of ${versions.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (versions.isEmpty() || shown == null) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("No earlier versions", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "A version appears here as soon as something changes.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Scaffold
            }

            val (commit, content) = shown

            // ---- the two arrows ---------------------------------------------
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    // An older version: stored later in the newest-first list.
                    enabled = index < versions.lastIndex,
                    onClick = { at = index + 1 },
                ) {
                    Text("<", style = MaterialTheme.typography.titleLarge)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = commit.message.lineSequence().first().ifBlank { "Changed" },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = DateFormat.getDateTimeInstance(
                            DateFormat.MEDIUM,
                            DateFormat.SHORT,
                        ).format(Date(commit.timestamp)) + "  ·  " + commit.shortId,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    enabled = index > 0,
                    onClick = { at = index - 1 },
                ) {
                    Text(">", style = MaterialTheme.typography.titleLarge)
                }
            }
            HorizontalDivider()

            // ---- what that version was ---------------------------------------
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                when {
                    isFolder -> Text(
                        text = content?.takeIf { it.isNotBlank() }
                            ?: "This version of the folder was empty.",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    // A picture or a file of an unknown kind has no text to show.
                    // Saying so is better than a screen of replacement characters
                    // that looks like corruption.
                    kind != EntryKind.TEXT -> Text(
                        text = "This is not a text file, so a version of it cannot be " +
                            "read here. It can still be put back.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    else -> Text(
                        text = content.orEmpty(),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                if (index == 0) {
                    Text(
                        "This is the current version.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    TextButton(onClick = { viewModel.restoreVersion(commit.id) }) {
                        Text(
                            if (isFolder) "Put this version of the folder back"
                            else "Restore this version",
                        )
                    }
                }
            }
        }
    }
}
