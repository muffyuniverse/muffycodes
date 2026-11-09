package com.example.notes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration

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

/**
 * Makes a picture: a name, and one of the sizes a phone's own pictures take.
 *
 * **The sizes are shown with their cost, because the cost is the reason to pick one.** A phone
 * screenshot is 1,105 tokens and a square is 765, and a person choosing a canvas has no other way to
 * know that the shape of the rectangle decides what it costs to show the agent. Showing the number
 * turns a preference into a decision.
 *
 * **The default is first in the list**, per the operator: the shape already in their hand rather than
 * the cheapest one.
 */
@Composable
fun NewImageDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, Look.Canvas) -> Unit,
) {
    var name by remember { mutableStateOf("drawing") }
    var chosen by remember { mutableStateOf(Look.DEFAULT_CANVAS) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New image") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                Spacer(Modifier.height(12.dp))
                for (canvas in Look.CANVASES) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { chosen = canvas }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = chosen == canvas,
                            onClick = { chosen = canvas },
                        )
                        Text(
                            text = "${canvas.label} · ${canvas.width}×${canvas.height}",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "about ${canvas.tokens} tokens",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onConfirm(name.trim(), chosen)
                    onDismiss()
                },
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * How a drawing is saved, when saving it destroys what was there.
 *
 * **A picture has one version.** There is no history to go back to, so "save" and "save a copy" are
 * genuinely different acts and the person has to choose which one they mean — the operator's words:
 * *"when we change a binary we should show a save screen with current path and name preset but with
 * ability for user to save the change as a different file — that will be our versioning option for
 * binaries"*.
 *
 * **The name is preset to the file's own**, so the ordinary act — save it where it is — is one press.
 * The other button is what makes a second version possible at all: a new name is a new file, with its
 * own single version, and the old one keeps its own.
 *
 * **The sentence about loss is on the window, not in a toast afterwards.** A person pressing Replace
 * is told, before they press, that the picture they have now will be gone.
 */
@Composable
fun SavePictureDialog(
    current: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    // The extension is kept out of the field: a person typing "page" means "page.png", and a name
    // typed without one would be saved as a picture the app then refuses to open.
    val stem = current.substringBeforeLast('.', current)
    val extension = current.substringAfterLast('.', "")
    var name by remember(current) { mutableStateOf(stem) }
    val asked = name.trim()
    // **The same name means replace; a different one means a copy.** One field, because that is how a
    // person thinks about it — the choice is the name, not a mode.
    val replacing = asked.equals(stem, ignoreCase = true)
    val target = if (extension.isEmpty()) asked else "$asked.$extension"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save this picture") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    isError = asked.isEmpty(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (replacing) {
                        "This replaces \"$current\". A picture keeps one version, so the one you " +
                            "have now will be gone."
                    } else {
                        "Saves as \"$target\", beside the picture you are editing. Both are kept, " +
                            "each with its own single version."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (replacing) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = asked.isNotEmpty(),
                onClick = {
                    onSave(target)
                    onDismiss()
                },
            ) { Text(if (replacing) "Replace" else "Save as a copy") }
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
                    detail = "The repositories stay where they are, and Add brings them back",
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

/**
 * **Deleting a project: one choice, and a sentence naming where the copy on the host still is.**
 *
 * There were two radio buttons: "from this phone only" and "everywhere". The second stopped being an
 * act on 2026-11-08, when `delete_repo` was dropped from the token's scopes — it was the one
 * irreversible capability in the app, and a repository is deleted on the host's own page, where the
 * confirmation is the host's. What was left was a choice between doing a thing and doing the same
 * thing with a longer sentence, which is a window asking a question it has already answered.
 *
 * **[address] is the project's own**, so the promise is exact rather than approximate: it said "on
 * GitHub" for every project, which was true only while every project had a GitHub repository.
 */
@Composable
fun DeleteProjectDialog(
    project: String,
    address: String?,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete $project?") },
        text = {
            Text(
                "$project is deleted from this phone, with every version it has not sent.\n\n" +
                    (address?.let {
                        "Its repository stays at $it — nothing here touches it, and deleting that " +
                            "is done on the host's own page. The add button brings a copy back."
                    } ?: "It has no address, so there is nowhere else a copy could be."),
            )
        },
        confirmButton = {
            TextButton(onClick = { onDelete(); onDismiss() }) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
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
 * **Adding a project: an address, or a name.**
 *
 * The operator's rule, and it is what makes the window universal: *"we can ask user to add remote urls
 * on his own via + button, and we just check with git ls-remote command that is part of the protocol."*
 *
 * **The list of an account's repositories is gone, and that is the point.** There is no git command
 * for it: `GET /user/repos` is GitHub's, GitLab uses `/api/v4/projects`, Gitea `/api/v1/user/repos`,
 * and they disagree about envelopes, clone fields, auth headers and paging. So the list was a vendor
 * feature wearing a feature's clothes, and an address field replaces it on every host at once.
 *
 * **The name is a prefill, not an answer**: the address may be `team/repo` and a slash cannot be a folder.
 */
@Composable
fun AddProjectDialog(
    onDismiss: () -> Unit,
    onNewProject: () -> Unit,
    onAddUrl: (url: String, name: String) -> Unit,
    /**
     * **Whether an address may be typed here at all** — false for the local account, which has no host
     * to reach one with (§3.9 of the credentials plan).
     *
     * The half is *missing* rather than disabled, and a sentence takes its place: a dialog that shows a
     * field and refuses what is typed into it spends the person's press to teach them the rule.
     */
    fromAddress: Boolean = true,
) {
    var address by remember { mutableStateOf("") }
    // **Follows the address until the person types a name of their own.** A prefill that overrides
    // what somebody typed is the app arguing with them; one that stops following the moment they edit
    // it is the app helping.
    var name by remember { mutableStateOf("") }
    var nameEdited by remember { mutableStateOf(false) }
    val suggested = Workspace.repoNameOf(address)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onNewProject)
                        .padding(vertical = 12.dp),
                ) {
                    Text("＋ New project…", style = MaterialTheme.typography.bodyLarge)
                }
                HorizontalDivider()
                Text(
                    if (fromAddress) "Or from an address" else "A project here stays on this phone",
                    style = MaterialTheme.typography.titleSmall,
                )
                if (!fromAddress) {
                    Text(
                        "Local has no host to reach an address with. Sign in, and a repository can " +
                            "arrive from anywhere — or move this project into an account and set its " +
                            "address there.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (fromAddress) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = {
                            address = it
                            if (!nameEdited) name = Workspace.repoNameOf(it)
                        },
                        label = { Text("Repository address") },
                        singleLine = true,
                        supportingText = {
                            Text(
                                "https://host/owner/repository.git — checked with ls-remote before " +
                                    "anything is cloned.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = {
                            name = it
                            nameEdited = it != suggested
                        },
                        label = { Text("Name on this phone") },
                        singleLine = true,
                    )
                    TextButton(
                        enabled = address.isNotBlank() && name.isNotBlank(),
                        onClick = { onAddUrl(address, name) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Add it") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * **Where a project is going** — the accounts on this phone, minus the one it is in.
 *
 * A list rather than a typed name, because an account is a folder and a folder is chosen by looking at
 * it. The two modes differ only in the promise, so the window says which one it is making: a move ends
 * the project here, a copy leaves it.
 */
@Composable
fun TransferProjectDialog(
    project: String,
    copy: Boolean,
    accounts: List<String>,
    onDismiss: () -> Unit,
    onGo: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (copy) "Copy \"$project\" to…" else "Move \"$project\" to…") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (copy) {
                        "The project stays here and a copy goes there: its files, its history and " +
                            "its branches. The copy has its own address, its own agent and its own " +
                            "templates."
                    } else {
                        "The project leaves this account and arrives in the one you pick: its files, " +
                            "its history and its branches. Its agent and its template choice do not " +
                            "travel — those belong to the account that spent them."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                for (login in accounts) {
                    TextButton(
                        onClick = { onGo(login) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(login) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * **What typing [address] will do to [project]'s name**, or null when there is nothing to say yet.
 *
 * A function rather than a branch inside the window, because this is the sentence that decides
 * whether the window is a feature or a surprise: typing an address renames a folder, and nothing in
 * a text field suggests it. The rule has three answers and only one of them is silent:
 *
 * - **the name changes** — the folder follows the address's last segment, and the window says so;
 * - **the name stays** — a repository that moved owner or host keeps its name, which is ordinary,
 *   and saying "renamed" would report something that did not happen;
 * - **nothing yet** — an empty address names nothing, and a window that guessed would be inventing.
 */
fun addressNameSaid(project: String, address: String): String? {
    val name = Workspace.repoNameOf(address)
    if (name.isEmpty() || name == "." || name == "..") return null
    return if (name == project) {
        "The name stays \"$name\" — only the address changes."
    } else {
        "The project is renamed to \"$name\", after the address. Rename it on the host first if " +
            "you meant to change the repository's name too."
    }
}

/**
 * **Setting a project's address** — the one project verb, and the whole of publishing and renaming.
 *
 * The field starts on the address the project has, because the ordinary use is *changing* it: a
 * repository that moved to another owner, another host, or another name. The name the project takes is
 * the address's last segment — so the sentence says that, rather than asking a second question whose
 * answer would have to agree with the first.
 *
 * **Nothing here creates anything.** A repository is made on the host's website, where the licence,
 * the visibility and the description ask their questions with real consequences; this window takes the
 * address that came out of that and makes the app agree with it.
 */
@Composable
fun SetAddressDialog(
    project: String,
    current: String?,
    onDismiss: () -> Unit,
    onSet: (url: String) -> Unit,
) {
    var address by remember { mutableStateOf(current.orEmpty()) }
    val name = Workspace.repoNameOf(address)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set the address of $project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Repository address") },
                    singleLine = true,
                    supportingText = {
                        Text(
                            "https://host/owner/repository.git — checked with ls-remote before " +
                                "anything changes.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                )
                // **The consequence, in the window that causes it**, read from the function that
                // states it so the rule can be tested without a window. Renaming a folder is not
                // obviously something that follows from typing an address, and the sentence that
                // says it is the difference between a feature and a surprise.
                addressNameSaid(project, address)?.let { said ->
                    Text(
                        said,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = address.isNotBlank(),
                onClick = {
                    onSet(address.trim())
                    onDismiss()
                },
            ) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * **Keeping a synced project on this phone only.**
 *
 * Asked because it cannot be undone from here and because the repository stays on GitHub: removing
 * the remote is a statement about this phone, not about the server, and a person who expected the
 * copy to disappear would be wrong in a way that matters. Deleting the repository is the other act.
 */
@Composable
fun KeepLocalDialog(
    project: String,
    address: String?,
    onDismiss: () -> Unit,
    onKeepLocal: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Keep $project only on this phone") },
        text = {
            Text(
                "Every version stays here and the project stops being sent anywhere.\n\n" +
                    // **The host named, and the address shown**, because this sentence is what
                    // decides whether a person believes a copy still exists somewhere else. It said
                    // "on GitHub" for every project, which was true only while every project had one.
                    (address?.let { "Its repository is still at $it, with everything already pushed " +
                        "to it. Nothing here deletes it — that is the host's own page." }
                        ?: "It had no address, so there is nowhere else it could be."),
            )
        },
        confirmButton = { TextButton(onClick = { onKeepLocal(); onDismiss() }) { Text("Keep local") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * **A name, and nothing else** — the project is made on this phone, with no repository behind it.
 *
 * The licence used to be asked here, because making a project meant making a repository on GitHub and
 * seeding it. A local project has nowhere to seed it, and a project that gains a remote later gets one
 * on the host's own page — where the licence question also has consequences a checkbox in this app
 * cannot: visibility, the repository's description, who can find it.
 */
@Composable
fun NewProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
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
                Text(
                    "It starts here, with its own history. An address can be added whenever you " +
                        "want it to live somewhere else as well.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onCreate(name.trim())
                    onDismiss()
                },
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * **Two ways in, and only one of them is GitHub's.**
 *
 * The operator's decision: the device flow stays, as *"a separate door option... a convenience on top of
 * the shared process"*. So this is a choice between two doors that end in the same save, and it says what
 * each one is for rather than making a person guess which is the "real" one.
 */
@Composable
fun AddAccountDialog(
    onDismiss: () -> Unit,
    onGitHub: () -> Unit,
    onAnotherHost: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add an account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "An account is a name, the host its key belongs to, and the key.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = onGitHub,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Sign in with GitHub", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "The name and the address are filled in for you, because GitHub says who a key " +
                        "belongs to.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                Button(
                    onClick = onAnotherHost,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Another host", style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    "Any host that speaks git: you say the host, and paste a key you made there.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * **The door for every host this app has no API for** — which is all of them but one.
 *
 * Four fields, and the sentence that keeps the dialog honest: nothing here can check a key. GitHub's
 * door asks `GET /user` who a key belongs to; there is no git command that answers "is this key valid"
 * without a repository to ask about, and no other host has an endpoint worth special-casing. So a bad
 * key is refused by the *first clone*, as a sentence naming the account, the host and which of
 * unauthorised / not-found / unreachable it was — rather than by a red line in a dialog that only
 * pretended to check.
 */
@Composable
fun AddHostAccountDialog(
    onDismiss: () -> Unit,
    onAdd: (name: String, host: String, gitUser: String, key: String) -> Unit,
    error: String? = null,
) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("gitlab.com") }
    var gitUser by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Another host") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Account name") },
                    singleLine = true,
                    supportingText = {
                        Text(
                            "The folder this account's projects live in.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Host") },
                    singleLine = true,
                    supportingText = {
                        Text(
                            "Just the name, like ${GITHUB_HOST} — a project's address goes on the project.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                )
                OutlinedTextField(
                    value = gitUser,
                    onValueChange = { gitUser = it },
                    label = { Text("Git username (optional)") },
                    singleLine = true,
                    supportingText = {
                        Text(
                            "Leave it empty unless the host wants one: GitLab wants `oauth2`.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                )
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Text(
                    "The key is tried the first time it is used. Nothing here can ask the host whether " +
                        "it is right; the first clone says so, and names what was refused.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (error != null) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(name, host, gitUser, key) },
                enabled = name.isNotBlank() && host.isNotBlank() && key.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun SignInDialog(
    onDismiss: () -> Unit,
    signingIn: Boolean = false,
    error: String? = null,
    /**
     * **The device flow, offered first**, because it is the one that needs nothing from the person
     * but a code and a tap on github.com. Null when there is no flow to offer — a build with no
     * OAuth App registered — and then the key field is the only thing on the screen, which is the
     * honest shape rather than a button that cannot work.
     */
    device: AppViewModel.Device? = null,
    onStartDevice: () -> Unit = {},
    /**
     * **Opens the address the code is typed at**, in a real browser.
     *
     * A callback rather than an `Intent` here, so this file stays drawable and testable: the screen
     * that owns the context is the thing that knows how to leave the app.
     */
    onOpenWhere: (String) -> Unit = {},
    onSignIn: (token: String) -> Unit,
) {
    var token by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sign in to GitHub") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // **The code, when there is one.** Big and monospaced, because it is read off the
                // screen and typed somewhere else — the one string in this app that is copied by
                // eye rather than by finger.
                // **Declared here, not inside the branch below.** It used to be read inside the
                // `if`, so `shown` did not exist further down where the failure sentence is drawn —
                // which is where a refused token's reason is shown. Kotlin says
                // "Unresolved reference 'failed' on receiver of type 'Any'", which is what an
                // out-of-scope smart cast looks like.
                val shown = device
                if (shown != null && shown.code.isNotEmpty()) {
                    // **Both the code and the address are selectable**, because the code is read off
                    // this screen and typed into another one — the operator's report was *"it gives
                    // the code but I cant copy it"*, which is the one string in the app that is
                    // copied by eye rather than by finger. Selecting is what makes copying possible.
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Type this code at the address below:",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                shown.code,
                                style = MaterialTheme.typography.headlineMedium,
                                fontFamily = FontFamily.Monospace,
                            )
                            // **The address is a link, not a caption.** It used to be a plain string
                            // glued into the sentence above, so it was neither tappable nor
                            // selectable — "it gives a link to open but it is not interactive".
                            Text(
                                shown.where,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                textDecoration = TextDecoration.Underline,
                                modifier = Modifier.clickable { onOpenWhere(shown.where) },
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        Text(
                            "Waiting for you to approve it…",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    // **Centred and larger, because it is the thing to press.** The operator:
                    // *"make link Sign in to GitHub centered and bigger - to grab attention"*. A
                    // `TextButton` at body size sat in the top-left of a dialog full of small grey
                    // text, which is exactly where the eye goes last — and this is the primary door,
                    // with the paste box as the backup underneath it.
                    //
                    // A `Button` rather than a bigger `TextButton`: the filled shape is what makes it
                    // read as the action rather than as one more line of text that happens to be
                    // tappable.
                    Button(
                        onClick = onStartDevice,
                        enabled = !signingIn,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    ) {
                        Text(
                            "Sign in to GitHub",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
                if (shown?.failed != null) {
                    Text(
                        shown.failed,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                HorizontalDivider()
                // **The backup, and it is a field rather than a link.** A person whose provider will
                // not cooperate, or who already has a token, should not have to find another screen:
                // the operator's rule is *"inside each link a manual key field as a backup signin
                // option"*.
                Text(
                    "Or paste a key",
                    style = MaterialTheme.typography.titleSmall,
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Personal access key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                // **Two things, and only two: which kind of key, and which scopes.**
                //
                // The operator's report: *"description text with requirements should be to the
                // point. It should say what type of token to supply and what access scopes it
                // needs."* It used to be four paragraphs of justification, which is a person being
                // made to read an argument when they wanted a checklist.
                //
                // `repo` covers `public_repo`, so one checkbox is enough for private repositories
                // too. A missing `workflow` is why this is a list rather than a sentence: that
                // token pushes every file except a workflow, and GitHub's refusal names neither.
                Text(
                    "A classic token, with these three scopes ticked:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // **Read from the rule that enforces them**, not typed here. Two lists would drift,
                // and the drift would be silent — the screen promising one thing while the check
                // demanded another.
                TokenScopes.REQUIRED.forEach { scope ->
                    Text(
                        "•  $scope — ${TokenScopes.why(scope)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "A fine-grained token works too, but its permissions cannot be checked here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // **Kept, and it survived the tightening on purpose.** Cutting the requirements down
                // to a checklist also cut this, and the scenario caught it: a person pasting a key has
                // one question the scopes do not answer — *whose name goes on the commits* — and the
                // answer is not obvious, because it is the key's owner and not this phone's user.
                Text(
                    "Your commits are written as the GitHub account this key belongs to.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // A sign-in is a network round trip, and the dialog used to close
                // the instant it was asked — so a refusal arrived at a screen that
                // was already gone, as a snackbar nobody was looking for, and the
                // whole thing read as "the button does nothing". The dialog stays
                // now, says what it is doing, and says why it failed.
                if (signingIn) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        Text(
                            "Checking the key with GitHub…",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (error != null) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = token.isNotBlank() && !signingIn,
                onClick = {
                    // Asked, not dismissed. The dialog closes when the answer is
                    // yes, and stays on a no so the reason can be read — and the key
                    // can be corrected rather than retyped.
                    onSignIn(token.trim())
                },
            ) { Text(if (signingIn) "Signing in…" else "Sign in") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !signingIn) { Text("Cancel") }
        },
    )
}

/**
 * Picks the folder a thing should go into — for a move or for a copy.
 *
 * One window for both, because they ask the identical question: *where?* The verb is the
 * only difference, and it is a parameter rather than a second copy of forty lines of
 * folder-walking. Two windows that asked the same question would drift: the move's would
 * learn that a folder cannot hold itself and the copy's would not.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaceDialog(
    title: String,
    /**
     * What the act is called, in the words the person reads: "Move" or "Copy".
     *
     * Every sentence in the window is built from it, so a copy can never be described
     * with the word "move" — which is the one way a person could be told the wrong thing
     * about whether their original is still there.
     */
    verb: String = "Move",
    /** The account's root, which is where the browser starts. */
    root: java.io.File,
    /** Folders that must not be offered: the things being moved, and their insides. */
    /** Things that must not be offered, and unchanged by either act. */
    forbidden: List<java.io.File>,
    /** The folder the browser starts in. */
    start: java.io.File = root,
    /**
     * Whether a folder may be *chosen*. A folder that may not be chosen can still be
     * looked inside — a project holds everything, and walking through it to reach a
     * folder inside is the whole point of a browser.
     */
    canChoose: (java.io.File) -> Boolean = { true },
    onDismiss: () -> Unit,
    onPick: (java.io.File) -> Unit,
) {
    // **One layer at a time, from the root.** A flat list of every folder in the
    // account answered "where can this go" with a wall of paths whose shape was
    // invisible — and the answer a person has is a *place*, reached by walking to it.
    var here by remember(start) { mutableStateOf(start) }
    var chosen by remember(start) { mutableStateOf<java.io.File?>(null) }
    var hint by remember(start) { mutableStateOf<String?>(null) }

    /**
     * The folders this one holds that may be moved into.
     *
     * **Hidden folders are not listed, and not walked into.** A name starting with `.`
     * is the app's own bookkeeping — `.muffy/` holds every flag, `.git/` is the clone's —
     * and both acts refuse them, so offering one could only end in a refusal after the
     * person had picked it. Worse, walking into `.muffy/` shows the app's flags as a list
     * of folders to file notes into. Reported from the live log: "move and clone allow to
     * choose hidden folders at the account root".
     */
    fun children(dir: java.io.File): List<java.io.File> =
        (dir.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
            .filter { !it.name.startsWith(".") }
            .filter { child -> !forbidden.any { it.name == child.name && it.parentFile == child.parentFile } }
            .filter { child -> forbidden.none { it.canonicalFile == child.canonicalFile } }
            .filter { child -> forbidden.none { f -> f.isDirectory && child.path.startsWith(f.path + java.io.File.separator) } }
            .sortedBy { it.name.lowercase() }

    val hereChildren = children(here)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.heightIn(max = 440.dp)) {
                // Where the browser is, and the way back up.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        enabled = here.canonicalFile != start.canonicalFile || here != start,
                        onClick = {
                            val parent = here.parentFile
                            if (parent != null) here = parent
                            hint = null
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up one folder")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (here == root) "The account" else here.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "Tap a folder to look inside it; press and hold to choose it.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider()

                if (hereChildren.isEmpty()) {
                    Text(
                        "There is no folder inside this one to ${verb.lowercase()} into.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    LazyColumn {
                        items(hereChildren) { child ->
                            val picked = chosen?.canonicalFile == child.canonicalFile
                            val deeper = children(child).isNotEmpty()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (picked) MaterialTheme.colorScheme.secondaryContainer
                                        else Color.Transparent,
                                    )
                                    .combinedClickable(
                                        onClick = {
                                            if (deeper) {
                                                here = child
                                                hint = null
                                            } else {
                                                // A folder with nothing inside cannot be
                                                // walked into, so a tap would look like
                                                // nothing happened. Say what to do instead.
                                                hint = "Press and hold \"${child.name}\" to ${verb.lowercase()} into it."
                                            }
                                        },
                                        onLongClick = {
                                            if (canChoose(child)) {
                                                chosen = child
                                                hint = null
                                            } else {
                                                hint = "\"${child.name}\" belongs to the app."
                                            }
                                        },
                                    )
                                    .padding(horizontal = 8.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = child.name + "/",
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (picked) {
                                    Text(
                                        "chosen",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }

                val said = hint
                if (said != null) {
                    Text(
                        said,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Text(
                    text = chosen?.let { "$verb into ${it.name}/" } ?: "Nothing chosen yet",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            // A button, not a tap on the row: choosing where something goes and
            // then moving it is two decisions, and a folder that was only looked
            // into must never be a folder that was moved into.
            TextButton(
                enabled = chosen != null,
                onClick = { chosen?.let(onPick) },
            ) { Text(verb) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * **Where a secret is kept, chosen by walking to it** — the Move window's shape, applied to a scope.
 *
 * The operator's instruction: *"I want scope selection to be done in the same way as moving is, a
 * window that allows to fall into account, then project and see what choices you have, and long press
 * selects."* So the shape is `PlaceDialog`'s, and it is the same for the same reason: a flat list of
 * every scope answered "where may this live" with a wall of names whose relation was invisible, and
 * the answer a person has is a *place*, reached by walking to it.
 *
 * **The first row of every level is the level itself** — "every project on this account" at the root,
 * "every branch of acme" inside acme. `PlaceDialog` has no such row because it never needs to choose
 * its own root; a scope does, and a row is the only way to say it without a second mechanism.
 *
 * ## The rules are functions, and the window draws them
 *
 * No test in this repository drives a dialog: an `AlertDialog`'s enter animation never reports idle
 * under Robolectric, so a test that waits on one hangs for sixty seconds and then exhausts the heap for
 * every test after it in the shard. So what a level shows, what a tap does, what a long press chooses,
 * and what the footer says are [scopeLoad], [scopeTap], [scopeLongPress] and [scopeChosenSaid] — four
 * functions of plain values, testable without a screen. The window below binds them to a list.
 */

/** Where the scope window is looking: the account's root, or inside one project. */
sealed interface ScopeHere {
    /** The root: the account, and every project it holds. */
    data object Root : ScopeHere

    /** Inside [project], where every branch of it is a place. */
    data class InProject(val project: String) : ScopeHere
}

/** One line the scope window draws. */
data class ScopeLine(
    /** The scope long-pressing this line chooses. Null for a line that is only a way in. */
    val chooses: SecretScope?,
    val label: String,
    /** True when a tap walks into it. */
    val walks: Boolean,
    /** What the line says under its name, when it has something to say. */
    val detail: String = "",
)

/**
 * **What one level of the walk shows**: the level itself first, then what is inside it.
 *
 * [branches] is asked per project and answers `main` first — a branch that has no working tree is not
 * a place a value can live, because `Worktrees.branches` is the list of trees, and a value scoped to a
 * branch that does not exist would be a row nothing can ever read.
 *
 * **A project with no branches at all still shows the project line**, so a scope can be chosen before
 * any branch exists — and the "nothing inside" sentence then says why, rather than leaving a person
 * looking at an empty window wondering whether the app is broken.
 */
fun scopeLines(
    here: ScopeHere,
    projects: List<String>,
    branches: (String) -> List<String>,
): List<ScopeLine> = when (here) {
    ScopeHere.Root -> listOf(
        ScopeLine(
            chooses = SecretScope.Account,
            label = "Every project on this account",
            walks = false,
            detail = "a secret kept here answers for every project below",
        ),
    ) + projects.sorted().map { project ->
        ScopeLine(
            chooses = SecretScope.Project(project),
            label = "$project/",
            walks = true,
            detail = "tap to see its branches · hold to keep it for the whole project",
        )
    }

    is ScopeHere.InProject -> {
        val found = branches(here.project)
        listOf(
            ScopeLine(
                chooses = SecretScope.Project(here.project),
                label = "Every branch of ${here.project}",
                walks = false,
                detail = if (found.size == 1) "this project has only main" else "${found.size} branches",
            ),
        ) + found.map { branch ->
            ScopeLine(
                chooses = SecretScope.Branch(here.project, branch),
                label = branch,
                walks = false,
                detail = "only ${here.project}/$branch",
            )
        }
    }
}

/**
 * What a long press on [line] chooses, or the sentence to say when it chooses nothing.
 *
 * **Every line in the window is choosable**, unlike the Move window's folders: a scope has no insides
 * that are not also scopes of their own. So this only ever has to answer with a scope, and the
 * `null` branch exists for the day a line stops being choosable — it must say so rather than do
 * nothing.
 */
fun scopeLongPress(line: ScopeLine): SecretScope? = line.chooses

/**
 * What a tap on [line] does: walk into it, or say what to do instead.
 *
 * **A tap that cannot walk is answered with the sentence rather than with silence** — the rule the
 * folder browser already follows, because a row that looks pressable and does nothing is the failure
 * this app keeps removing.
 */
fun scopeTap(line: ScopeLine): ScopeTap = when {
    line.walks && line.chooses is SecretScope.Project -> ScopeTap.Into(ScopeHere.InProject(line.chooses.name))
    line.walks -> ScopeTap.Into(ScopeHere.Root)
    else -> ScopeTap.Hint(
        "Press and hold \"${line.label.trimEnd('/')}\" to choose it.",
    )
}

/** What a tap did, so the window is a rendering of this and not a second source of truth. */
sealed interface ScopeTap {
    /** Walk into [here]. */
    data class Into(val here: ScopeHere) : ScopeTap

    /** Say [said], and change nothing. */
    data class Hint(val said: String) : ScopeTap
}

/**
 * The line under the list, saying what is chosen.
 *
 * **"Nothing chosen yet" rather than an empty space**, because the Save button depends on this and a
 * person has to be able to see why it will not press. It names the scope in the same words the row
 * will afterwards ([scopeLabel]), so the choice and the row cannot read as two different things.
 */
fun scopeChosenSaid(chosen: SecretScope?): String =
    chosen?.let { "Keeping it for " + scopeLabel(it) } ?: "Nothing chosen yet"

/**
 * The window itself. See the block above for why its rules are functions rather than tests here.
 */
@Composable
fun ScopeDialog(
    /** Every project the account holds, which is the root level's list. */
    projects: List<String>,
    /** The branches of one project, `main` first. */
    branches: (String) -> List<String>,
    onDismiss: () -> Unit,
    onPick: (SecretScope) -> Unit,
) {
    var here by remember { mutableStateOf<ScopeHere>(ScopeHere.Root) }
    var chosen by remember { mutableStateOf<SecretScope?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }

    // **Read once into a value, because `here` is a delegated property** — a `when` over it cannot
    // smart-cast, which the compiler says once and then fails the whole build for.
    val where = here
    val lines = scopeLines(where, projects, branches)
    val title = when (where) {
        ScopeHere.Root -> "The account"
        is ScopeHere.InProject -> where.project
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Where should it be kept?") },
        text = {
            Column(modifier = Modifier.heightIn(max = 440.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        enabled = here != ScopeHere.Root,
                        onClick = {
                            here = ScopeHere.Root
                            hint = null
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up one level")
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            // **The same sentence the Move window uses**, because it is the same act:
                            // tap to look, hold to choose.
                            text = "Tap a project to look inside it; press and hold to choose it.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider()
                if (lines.isEmpty()) {
                    Text(
                        "There is nothing here to choose yet. Make a project first.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    LazyColumn {
                        items(lines) { line ->
                            val picked = line.chooses != null && line.chooses == chosen
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (picked) MaterialTheme.colorScheme.secondaryContainer
                                        else Color.Transparent,
                                    )
                                    .combinedClickable(
                                        onClick = {
                                            when (val tap = scopeTap(line)) {
                                                is ScopeTap.Into -> {
                                                    here = tap.here
                                                    hint = null
                                                }
                                                is ScopeTap.Hint -> hint = tap.said
                                            }
                                        },
                                        onLongClick = {
                                            val scope = scopeLongPress(line)
                                            if (scope != null) {
                                                chosen = scope
                                                hint = null
                                            } else {
                                                hint = "\"${line.label}\" cannot be chosen."
                                            }
                                        },
                                    )
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = line.label,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (line.detail.isNotEmpty()) {
                                        Text(
                                            text = line.detail,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                if (picked) {
                                    Text(
                                        "chosen",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
                val said = hint
                if (said != null) {
                    Text(
                        said,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Text(
                    text = scopeChosenSaid(chosen),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = chosen != null,
                onClick = { chosen?.let(onPick) },
            ) { Text("Keep it there") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The bug button: one press, everywhere, from any screen.
 *
 * It is deliberately the smallest thing in each app bar and the same thing on every
 * screen, so that finding it never depends on where you are — the moment a person
 * wants it is the moment something has just gone wrong, and hunting for it then is
 * the worst possible time.
 *
 * It answers its own tap immediately ([onReport] is called by the screen that opened
 * it). A report button that sat in the trace as an unanswered intent would make every
 * report the app ever receives claim one extra broken sequence — its own.
 */
@Composable
fun ReportButton(onReport: () -> Unit) {
    // The colour is read out here rather than inside the drawing: a `Canvas` block is
    // not a composable context, and reading the theme in it is a compile error.
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    IconButton(onClick = onReport) {
        // **Drawn, not imported.** `Icons.Filled.BugReport` lives in
        // material-icons-extended — a dependency this app does not have and should not
        // take for one glyph, since the whole set ships in the APK. Six legs and a body
        // are the same drawing at every size, and they answer to the theme like any
        // other icon.
        Canvas(
            modifier = Modifier
                .size(22.dp)
                // The description lives on the drawing, because that is what a screen
                // reader announces for this button.
                .semantics { contentDescription = "Report a problem" },
        ) {
            val w = size.width
            val h = size.height
            val stroke = w * 0.075f
            // The body, and the head above it.
            drawOval(
                color = ink,
                topLeft = Offset(w * 0.28f, h * 0.34f),
                size = Size(w * 0.44f, h * 0.52f),
                style = Stroke(width = stroke),
            )
            drawCircle(
                color = ink,
                radius = w * 0.13f,
                center = Offset(w * 0.5f, h * 0.24f),
                style = Stroke(width = stroke),
            )
            // Three legs a side, angled out from the body.
            for (i in 0..2) {
                val y = h * (0.42f + i * 0.16f)
                drawLine(ink, Offset(w * 0.28f, y), Offset(w * 0.08f, y - h * 0.06f), stroke)
                drawLine(ink, Offset(w * 0.72f, y), Offset(w * 0.92f, y - h * 0.06f), stroke)
            }
        }
    }
}

/**
 * **Photograph this page**, as an icon rather than the word it used to be.
 *
 * The operator's report: *"make sure that "photo" is an icon button"*. It sits beside the report
 * icon, and a word there took a third of the top bar from the file's own name.
 *
 * **Drawn, not imported**, for the same reason as [ReportButton]: `Icons.Filled.PhotoCamera` lives in
 * material-icons-extended, and this app does not carry that whole set for one glyph.
 *
 * A camera is a body and a lens, and the body has the little raised shoulder that says "viewfinder"
 * without a second stroke.
 */
@Composable
fun PhotoButton(onPhoto: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    IconButton(onClick = onPhoto) {
        Canvas(
            modifier = Modifier
                .size(22.dp)
                .semantics { contentDescription = "Photograph this page" },
        ) {
            val w = size.width
            val h = size.height
            val stroke = w * 0.075f
            // The body, with the raised shoulder on the left of the top edge.
            drawRoundRect(
                color = ink,
                topLeft = Offset(w * 0.06f, h * 0.30f),
                size = Size(w * 0.88f, h * 0.56f),
                cornerRadius = CornerRadius(w * 0.10f, w * 0.10f),
                style = Stroke(width = stroke),
            )
            drawLine(ink, Offset(w * 0.22f, h * 0.30f), Offset(w * 0.30f, h * 0.18f), stroke)
            drawLine(ink, Offset(w * 0.30f, h * 0.18f), Offset(w * 0.46f, h * 0.18f), stroke)
            // The lens.
            drawCircle(
                color = ink,
                radius = w * 0.16f,
                center = Offset(w * 0.5f, h * 0.58f),
                style = Stroke(width = stroke),
            )
        }
    }
}

/**
 * **The other half of each pair: a whole chain, and an open padlock.**
 *
 * The operator's report: "dont use just one icon for two states for lock and sync. tell me if it is
 * possible to use open lock icon on unlock and connected icon on sync, so that different states have
 * not just different colors but icons".
 *
 * It is possible, and this is it. Until now both flags used one glyph each and told the states apart
 * by colour alone - grey off, black on, red when the app set it. Colour alone is a weak signal: it
 * fails in bright sun, it fails for a person who cannot distinguish the hues, and it fails for the
 * one state that matters most, because "the app is holding this" and "nobody is" differ only in
 * saturation at a glance down a list.
 *
 * **Drawn, not imported**, for the same reason as [UnsyncGlyph]: `LockOpen` and `Link` live in
 * material-icons-extended, and this app does not carry that whole set for two glyphs. Three strokes
 * each, and they read at 20dp.
 */
@Composable
fun LinkedGlyph(tint: Color, description: String, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp).semantics { contentDescription = description }) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.10f
        // **The same two halves as the broken link, joined.** That is the point: the pair differs by
        // the break and nothing else, so a person comparing two rows sees one difference rather than
        // two different drawings. The middle bar closes the gap the broken one cuts through.
        drawLine(tint, Offset(w * 0.06f, h * 0.32f), Offset(w * 0.30f, h * 0.32f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.06f, h * 0.32f), Offset(w * 0.06f, h * 0.68f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.06f, h * 0.68f), Offset(w * 0.30f, h * 0.68f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.70f, h * 0.32f), Offset(w * 0.94f, h * 0.32f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.94f, h * 0.32f), Offset(w * 0.94f, h * 0.68f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.94f, h * 0.68f), Offset(w * 0.70f, h * 0.68f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.30f, h * 0.50f), Offset(w * 0.70f, h * 0.50f), stroke, StrokeCap.Round)
    }
}

/**
 * **An open padlock: the shackle lifted clear of the body.**
 *
 * The closed padlock is `Icons.Filled.Lock`; this is its other position, drawn here so the pair is
 * the same family and the same weight. The shackle is one side only - that is what "open" looks like
 * on every padlock anyone has seen - and the body is the same rounded rectangle as the icon's.
 */
@Composable
fun OpenLockGlyph(tint: Color, description: String, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp).semantics { contentDescription = description }) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.10f
        // The body: a squat rounded rectangle in the lower half.
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.20f, h * 0.46f),
            size = Size(w * 0.60f, h * 0.44f),
            cornerRadius = CornerRadius(w * 0.08f),
            style = Stroke(width = stroke),
        )
        // **The shackle, lifted and hinged on one side only.** The right leg stops short of the
        // body, which is the whole difference between this and a closed lock.
        drawLine(tint, Offset(w * 0.50f, h * 0.46f), Offset(w * 0.50f, h * 0.24f), stroke, StrokeCap.Round)
        drawArc(
            color = tint,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.30f, h * 0.08f),
            size = Size(w * 0.40f, h * 0.32f),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
        drawLine(tint, Offset(w * 0.70f, h * 0.24f), Offset(w * 0.70f, h * 0.36f), stroke, StrokeCap.Round)
    }
}

/**
 * Not sent: two halves of a link with the middle missing, drawn rather than imported.
 *
 * **The operator asked for this twice and the cross stayed.** "unsync row button hasnt been changed
 * to a disconnect icon from its current cross, as I asked" - and the cross was `Icons.Filled.Clear`,
 * which reads as "take this out" or "close", not as "this is not being sent". A cross beside a lock
 * is also a cross that could be either act.
 *
 * Two open ends with a gap between them is the shape everybody already knows for a broken link, and
 * it is three strokes, so it needs no dependency. `Icons.Filled.LinkOff` lives in
 * material-icons-extended, which this app does not carry for one glyph.
 *
 * [tint] is passed in rather than read from the theme, because this glyph is drawn in three states
 * and the state is the caller's to decide: grey unset, black set by the person, red set by the app.
 */
@Composable
fun UnsyncGlyph(tint: Color, description: String, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(20.dp).semantics { contentDescription = description }) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.10f
        // The two halves, each a bracket opening towards the other, with a gap in the middle.
        drawLine(tint, Offset(w * 0.06f, h * 0.32f), Offset(w * 0.30f, h * 0.32f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.06f, h * 0.32f), Offset(w * 0.06f, h * 0.68f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.06f, h * 0.68f), Offset(w * 0.30f, h * 0.68f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.70f, h * 0.32f), Offset(w * 0.94f, h * 0.32f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.94f, h * 0.32f), Offset(w * 0.94f, h * 0.68f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.94f, h * 0.68f), Offset(w * 0.70f, h * 0.68f), stroke, StrokeCap.Round)
        // The break: a diagonal through the gap, which is the part that says *broken* rather than
        // merely "two things".
        drawLine(tint, Offset(w * 0.38f, h * 0.78f), Offset(w * 0.62f, h * 0.22f), stroke, StrokeCap.Round)
    }
}

/**
 * A file's versions: a clock with an arrow going back, drawn rather than imported.
 *
 * `Icons.Filled.History` is in material-icons-extended, which this app does not carry — the whole
 * set ships in the APK, and one glyph is not worth that. The operator asked for the history button
 * to move to the top bar beside the report button, which is an icon's place; a word there would
 * have pushed the name out of the title.
 *
 * **A circle, two hands and an arrow**, because that is what "what this used to say" looks like:
 * the clock is the time it has been through, and the arrow is which way the list goes.
 */
@Composable
fun HistoryButton(onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    IconButton(onClick = onClick) {
        Canvas(
            modifier = Modifier
                .size(22.dp)
                // On the drawing, because that is what a screen reader announces for the button.
                .semantics { contentDescription = "This file's history" },
        ) {
            val w = size.width
            val h = size.height
            val stroke = w * 0.085f
            // The face, open at the upper left so the arrow has somewhere to come from.
            drawArc(
                color = ink,
                startAngle = 45f,
                sweepAngle = 315f,
                useCenter = false,
                topLeft = Offset(w * 0.10f, h * 0.10f),
                size = Size(w * 0.80f, h * 0.80f),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            // Going back: an arrowhead at the open end, pointing anticlockwise.
            drawLine(
                ink,
                Offset(w * 0.10f, h * 0.10f),
                Offset(w * 0.10f, h * 0.36f),
                stroke,
                StrokeCap.Round,
            )
            drawLine(
                ink,
                Offset(w * 0.10f, h * 0.10f),
                Offset(w * 0.34f, h * 0.13f),
                stroke,
                StrokeCap.Round,
            )
            // The hands, both from the centre: one up, one to the right.
            val cx = w * 0.5f
            val cy = h * 0.5f
            drawLine(ink, Offset(cx, cy), Offset(cx, cy - h * 0.22f), stroke, StrokeCap.Round)
            drawLine(ink, Offset(cx, cy), Offset(cx + w * 0.18f, cy), stroke, StrokeCap.Round)
        }
    }
}

/**
 * The agent's door into a project: a speech bubble, drawn rather than imported.
 *
 * `Icons.Filled.Chat` is in material-icons-extended, which this app does not carry — the whole set
 * ships in the APK, and one glyph is not worth that. A rounded rectangle with a tail is the same
 * drawing at every size and answers to the theme like any other icon.
 */
@Composable
fun ChatButton(onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    IconButton(onClick = onClick) {
        Canvas(
            modifier = Modifier
                .size(22.dp)
                // On the drawing, because that is what a screen reader announces for the button.
                .semantics { contentDescription = "Chat with the agent" },
        ) {
            val w = size.width
            val h = size.height
            val stroke = w * 0.085f
            // The bubble: a rounded body with two square corners where the tail comes off, which is
            // why this is a path rather than `drawRoundRect`.
            val body = Path().apply {
                moveTo(w * 0.06f, h * 0.14f)
                lineTo(w * 0.94f, h * 0.14f)
                lineTo(w * 0.94f, h * 0.66f)
                lineTo(w * 0.40f, h * 0.66f)
                lineTo(w * 0.20f, h * 0.88f)
                lineTo(w * 0.20f, h * 0.66f)
                lineTo(w * 0.06f, h * 0.66f)
                close()
            }
            drawPath(body, color = ink, style = Stroke(width = stroke))
        }
    }
}


/**
 * Says what went wrong, as a line in the app's log.
 *
 * **Nothing is captured here.** The log has been appended to since the app opened, so
 * the moment the person writes their sentence the file already holds everything that led
 * to it — the taps, what answered them, what was refused. All the button adds is a
 * `REPORT` line with their words, which is why the file and the report are one document
 * rather than two that have to be kept in step.
 *
 * What the window *shows* is only so the person knows the app already noticed: if a tap
 * is sitting there unanswered, they will describe the right thing.
 */
@Composable
fun ReportDialog(
    waiting: List<Trace.Broken>,
    failures: List<String>,
    onDismiss: () -> Unit,
    onSend: (String) -> Unit,
) {
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Report a problem") },
        text = { ReportBody(waiting = waiting, failures = failures, note = note, onNote = { note = it }) },
        confirmButton = {
            TextButton(onClick = { onSend(note) }) { Text("Write it down") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * What the window says, without the window.
 *
 * Split out because a dialog is a platform window: mounting one in a test asks whether
 * Robolectric settles, which is a question about the harness, not about this app. The
 * part with a decision in it — what the app already noticed, in the app's own words, and
 * what the person types — is ordinary content and can be tested as such.
 */
@Composable
fun ReportBody(
    waiting: List<Trace.Broken>,
    failures: List<String>,
    note: String,
    onNote: (String) -> Unit,
) {
    val stuck = waiting.filter { it.waitedMs > Trace.staleMs }
    // **The body scrolls, because a dialog is a fixed-height window and this body is not fixed.**
    // With several stuck taps the last paragraph ran past the bottom edge and was cut mid-word -
    // visible in the report scenario's own picture, and unreadable in the one window where a person
    // is trying to describe a problem. A dialog that clips its own instructions is worse than a
    // shorter one: it looks complete.
    // **Selectable, because this is the window a person copies out of.** It lists the failures and
    // the taps that never answered, in the app's own words - and the next thing they do with that
    // text is paste it into a message about the problem.
    SelectionContainer {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            "What happened, and what you were trying to do?",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = note,
            onValueChange = onNote,
            label = { Text("What went wrong?") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

        if (stuck.isEmpty()) {
            Text(
                "Every tap so far was answered.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                "These have no answer, and are already in the log:",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
            )
            stuck.take(6).forEach {
                Text(
                    "· ${it.line}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        if (failures.isNotEmpty()) {
            Text(
                "And these were refused:",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp),
            )
            failures.take(4).forEach {
                Text(
                    "· $it",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        // **Shorter, because the window is.** The sentence used to run to three lines and was the
        // thing that got cut: the body's own cap could be reduced until it scrolled, but a body
        // whose last paragraph is always the one past the fold has not been fixed, only made
        // scrollable. What the paragraph has to carry is that the sentence joins the log - the
        // rest was explaining a screen the reader is already looking at.
        Text(
            "This joins the same log, after everything above it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
    }
}

/**
 * Whether answering a key save closes the window, and what to say when it does not.
 *
 * **The rule the report was about, in one function.** The dialog used to close on the press, so a
 * key that could not be read back was reported as kept: the refusal arrived as a snackbar at a
 * screen that was already gone. A save that has an answer closes; one that does not stays and says
 * why. It is a function so the rule is testable without drawing a dialog window — a text field
 * inside one never goes idle under Robolectric, which is a harness fact and not a reason to leave
 * the rule unpinned.
 *
 * @return null when the key was kept (and [closes] has been called), or the sentence to show.
 */
internal fun keySaveVerdict(why: String?, closes: () -> Unit): String? {
    if (why == null) closes()
    return why
}

/**
 * The screen the OpenRouter key is typed on — **and it is told whether the key took.**
 *
 * A save is a write and a read back, and the read back can fail on a phone that is doing nothing
 * wrong (a locked screen is enough). The dialog used to dismiss itself the instant the button was
 * pressed, so that refusal arrived at a screen that was already gone, as a snackbar nobody was
 * looking for — which reads exactly like a key that was accepted.
 *
 * The refusal is a **parameter**, the way the sign-in dialog's is: the screen that decides whether
 * the window is open also decides why it stayed, and this one only draws what it is told. A dialog
 * that kept its own copy could disagree with the screen about both.
 */
@Composable
fun ModelKeyDialog(
    onDismiss: () -> Unit,
    error: String? = null,
    onSave: (String) -> Unit,
    /**
     * Begins the OpenRouter sign-in and answers the address to open, or null when this build has no
     * way to open one.
     *
     * **Offered first, and the paste box kept.** Signing in mints a key the person never sees and
     * never has to copy, which is the easier path — but a key someone already has must still work,
     * and the sign-in can fail for reasons outside the app (a rate limit, no browser, an OpenRouter
     * outage). Two doors, one of them always open.
     */
    onSignIn: (() -> String?)? = null,
    /** Exchanges the pasted code, answering null when the key was kept. */
    onCode: ((String, (String?) -> Unit) -> Unit)? = null,
) {
    var key by remember { mutableStateOf("") }
    // **The sign-in is a second stage of the same window**, not a second dialog: a person who
    // pressed "Sign in" should not have to find where the app put them.
    var signIn by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var refused by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (signIn) "Sign in to OpenRouter" else "OpenRouter key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (signIn) {
                    Text(
                        "Open OpenRouter, sign in, and it will show you a code. Paste that code " +
                            "here and the app will keep the key OpenRouter makes for you — you " +
                            "never see or copy the key itself.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it; refused = null },
                        placeholder = { Text("the code OpenRouter showed") },
                        singleLine = true,
                    )
                    if (refused != null) {
                        Text(
                            refused!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        "A code can only be used once and expires after ten minutes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { onSignIn?.invoke() }) { Text("Open OpenRouter again") }
                } else {
                // **The sign-in is the primary door here too, and it comes first.**
                //
                // The operator's report: *"sign in to openrouter should be the primary option (same
                // way and structure as with github signin window). and direct key insert as a
                // backup"*. It was a `TextButton` below the paste field, so the backup came first and
                // the easier path — a key the person never sees or copies — was the thing at the
                // bottom in small type.
                if (onSignIn != null && onCode != null) {
                    Button(
                        onClick = {
                            signIn = true
                            refused = null
                            onSignIn.invoke()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    ) {
                        Text(
                            "Sign in to OpenRouter",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Text(
                        "You never see or copy the key: OpenRouter makes one and the app keeps it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider()
                    Text("Or paste a key", style = MaterialTheme.typography.titleSmall)
                }
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    // **A placeholder, not a label.** A label is drawn in the notch cut into the
                    // outline, so a label wider than the field runs out past the border - which is
                    // what "Key, starting sk-or-v1-" did in the key scenario's picture, a word
                    // sitting on the line. A placeholder is drawn inside the field and is clipped
                    // to it, so the same sentence cannot escape the box.
                    placeholder = { Text("starts with sk-or-v1-") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Text(
                    "It pays for the work. Kept encrypted on this phone, never written to the log.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (error != null) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                }
            }
        },
        confirmButton = {
            if (signIn) {
                TextButton(
                    enabled = code.isNotBlank() && !working && onCode != null,
                    onClick = {
                        working = true
                        refused = null
                        onCode!!.invoke(code.trim()) { said ->
                            working = false
                            if (said == null) onDismiss() else refused = said
                        }
                    },
                ) { Text(if (working) "Checking…" else "Use this code") }
            } else {
                TextButton(
                    enabled = key.isNotBlank(),
                    onClick = { onSave(key.trim()) },
                ) { Text("Keep it") }
            }
        },
        dismissButton = {
            TextButton(onClick = { if (signIn) signIn = false else onDismiss() }) {
                Text(if (signIn) "Back" else "Cancel")
            }
        },
    )
}

/**
 * **A release is a tag** — so the window asks for a version and a sentence, and nothing else.
 *
 * It used to ask whether to also build a dev copy and to dispatch a workflow that built and signed the
 * app. That was GitHub Actions wearing a feature's clothes: the protocol has one release, `git tag`,
 * and what a host builds from it belongs to the host.
 *
 * **The dev-copy checkbox is gone rather than hidden**, because there is nothing left for it to decide:
 * this app no longer produces a build of somebody else's project.
 */
@Composable
fun ReleaseDialog(
    project: String,
    onDismiss: () -> Unit,
    onRelease: (versionName: String, note: String) -> Unit,
) {
    var version by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val problem = Release.nameProblem(version.trim())
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tag a version of $project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "A tag is made at the top of ${Release.BRANCH} and sent to this project's " +
                        "address. Every host understands a tag; what it builds from one is its own " +
                        "business.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = version,
                    onValueChange = { version = it },
                    label = { Text("Version, for example 1.5") },
                    singleLine = true,
                    isError = version.isNotEmpty() && problem != null,
                    supportingText = {
                        // **Silent while empty.** "Say what version this is" under an untouched field
                        // is the app scolding somebody for not having typed yet.
                        if (version.isNotEmpty() && problem != null) Text(problem)
                    },
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("What changed (optional)") },
                    supportingText = {
                        Text(
                            "The tag carries this, so it is readable wherever the version is.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = problem == null && version.isNotBlank(),
                onClick = {
                    onRelease(version.trim(), note.trim())
                    onDismiss()
                },
            ) { Text("Tag it") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
