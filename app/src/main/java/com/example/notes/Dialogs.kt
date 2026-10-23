package com.example.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/** Asks for one line of text. */
@Composable
fun PromptDialog(
    title: String,
    label: String,
    initial: String,
    confirm: String = "OK",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = {
                    onConfirm(text.trim())
                    onDismiss()
                },
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Asks a yes-or-no question. */
@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirm: String,
    destructive: Boolean = false,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm()
                    onDismiss()
                },
            ) {
                Text(
                    confirm,
                    color = if (destructive) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One option of a dialog that offers a choice. */
data class Choice(val label: String, val detail: String = "", val action: () -> Unit)

/** Offers a few clearly different ways forward, each with its own explanation. */
@Composable
fun ChoiceDialog(
    title: String,
    text: String,
    choices: List<Choice>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                choices.forEach { choice ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                choice.action()
                                onDismiss()
                            }
                            .padding(vertical = 8.dp),
                    ) {
                        Text(choice.label, style = MaterialTheme.typography.bodyLarge)
                        if (choice.detail.isNotEmpty()) {
                            Text(
                                choice.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The two outcomes of Remove, side by side (§5): keeping the projects, or
 * deleting them from this phone as well.
 */
@Composable
fun RemoveAccountDialog(
    login: String,
    onDismiss: () -> Unit,
    onRemove: (deleteCopies: Boolean) -> Unit,
) {
    var deleteCopies by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove $login from this phone?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RadioLine(
                    selected = !deleteCopies,
                    label = "Keep its projects here",
                    detail = "Signing in again brings them back",
                    onClick = { deleteCopies = false },
                )
                RadioLine(
                    selected = deleteCopies,
                    label = "Delete its projects from this phone too",
                    detail = "The repositories stay on GitHub, and Add brings them back",
                    onClick = { deleteCopies = true },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onRemove(deleteCopies)
                    onDismiss()
                },
            ) {
                Text(
                    "Remove",
                    color = if (deleteCopies) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The two outcomes of deleting a project (§6), in one dialog. */
@Composable
fun DeleteProjectDialog(
    project: String,
    onDismiss: () -> Unit,
    onDelete: (everywhere: Boolean) -> Unit,
) {
    var everywhere by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $project?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RadioLine(
                    selected = !everywhere,
                    label = "From this phone only",
                    detail = "The repository stays on GitHub, and the add button brings it back",
                    onClick = { everywhere = false },
                )
                RadioLine(
                    selected = everywhere,
                    label = "Everywhere",
                    detail = "The copy here and the repository on GitHub. Cannot be undone",
                    onClick = { everywhere = true },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDelete(everywhere)
                    onDismiss()
                },
            ) {
                Text(
                    "Delete",
                    color = if (everywhere) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RadioLine(
    selected: Boolean,
    label: String,
    detail: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The add window (§6): "New project…" always first, then the account's
 * repositories, newest-changed first, with the ones already here marked.
 */
@Composable
fun AddProjectDialog(
    repositories: List<Repo>,
    alreadyHere: Set<String>,
    loading: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onNewProject: () -> Unit,
    onPick: (Repo) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a project") },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onNewProject)
                        .padding(vertical = 12.dp),
                ) {
                    Text("＋ New project…", style = MaterialTheme.typography.bodyLarge)
                }
                HorizontalDivider()
                when {
                    loading -> Text(
                        "Asking GitHub…",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                    error != null -> Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                    repositories.isEmpty() -> Text(
                        "No repositories on this account yet",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                    else -> LazyColumn {
                        items(repositories) { repo ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(repo) }
                                    .padding(vertical = 10.dp),
                            ) {
                                Text(
                                    repo.name + if (repo.name in alreadyHere) "  ·  on this phone" else "",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                if (repo.updatedAt.isNotBlank()) {
                                    Text(
                                        "last changed ${repo.updatedAt.take(10)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** The create dialog: a name and a licence (§4.1). */
@Composable
fun NewProjectDialog(
    licences: List<Licence>,
    onDismiss: () -> Unit,
    onCreate: (name: String, licence: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var licence by remember { mutableStateOf("gpl-3.0") }
    var showLicences by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showLicences = !showLicences },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Licence: ${licence.ifBlank { "none" }}")
                }
                if (showLicences && licences.isNotEmpty()) {
                    Column(modifier = Modifier.heightIn(max = 200.dp)) {
                        LazyColumn {
                            items(licences) { item ->
                                Text(
                                    text = item.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            licence = item.key
                                            showLicences = false
                                        }
                                        .padding(vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onCreate(name.trim(), licence)
                    onDismiss()
                },
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Signing in with a key (§5). The key is never shown back. */
@Composable
fun SignInDialog(
    onDismiss: () -> Unit,
    onSignIn: (login: String, token: String, name: String, email: String) -> Unit,
) {
    var token by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sign in to GitHub") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Personal access key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Commit name (optional)") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Commit email (optional)") },
                    singleLine = true,
                )
                Text(
                    "The key needs the repo and delete_repo scopes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = token.isNotBlank(),
                onClick = {
                    onSignIn("", token.trim(), name.trim(), email.trim())
                    onDismiss()
                },
            ) { Text("Sign in") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Picks the folder a thing should be moved into. */
@Composable
fun MoveDialog(
    title: String,
    folders: List<Pair<String, java.io.File>>,
    onDismiss: () -> Unit,
    onPick: (java.io.File) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp)) {
                if (folders.isEmpty()) {
                    Text("There is no other folder to move into")
                } else {
                    LazyColumn {
                        items(folders) { (label, dir) ->
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(dir) }
                                    .padding(vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
