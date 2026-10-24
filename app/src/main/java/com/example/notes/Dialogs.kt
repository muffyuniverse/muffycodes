package com.example.notes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
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
    signingIn: Boolean = false,
    error: String? = null,
    onSignIn: (token: String) -> Unit,
) {
    var token by remember { mutableStateOf("") }
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
                Text(
                    "Your commits are written as the GitHub account this key " +
                        "belongs to: its name and its email, taken from GitHub.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "The key needs the repo and delete_repo scopes.",
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

/** Picks the folder a thing should be moved into. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MoveDialog(
    title: String,
    /** The account's root, which is where the browser starts. */
    root: java.io.File,
    /** Folders that must not be offered: the things being moved, and their insides. */
    forbidden: List<java.io.File>,
    /** The folder the browser starts in. */
    start: java.io.File = root,
    /**
     * Whether a folder may be *chosen*. A folder that may not be chosen can still be
     * looked inside — `projects/` holds every project's repository, and walking
     * through it to reach one is the whole point of a browser.
     */
    canChoose: (java.io.File) -> Boolean = { true },
    onDismiss: () -> Unit,
    onMove: (java.io.File) -> Unit,
) {
    // **One layer at a time, from the root.** A flat list of every folder in the
    // account answered "where can this go" with a wall of paths whose shape was
    // invisible — and the answer a person has is a *place*, reached by walking to it.
    var here by remember(start) { mutableStateOf(start) }
    var chosen by remember(start) { mutableStateOf<java.io.File?>(null) }
    var hint by remember(start) { mutableStateOf<String?>(null) }

    /** The folders this one holds that may be moved into. */
    fun children(dir: java.io.File): List<java.io.File> =
        (dir.listFiles() ?: emptyArray())
            .filter { it.isDirectory }
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
                        "There is no folder inside this one to move into.",
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
                                                hint = "Press and hold \"${child.name}\" to move into it."
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
                    text = chosen?.let { "Move into ${it.name}/" } ?: "Nothing chosen yet",
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
                onClick = { chosen?.let(onMove) },
            ) { Text("Move") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Asks what a sync was for.
 *
 * A project's history is the app narrating files — "Edited milk.txt" — which is the
 * right message for an undo step and the wrong one for the history on GitHub, where
 * the question is why a push happened. So the push asks, and what is typed becomes
 * the sync's own commit.
 */
@Composable
fun SyncDialog(
    project: String,
    changes: Int,
    onDismiss: () -> Unit,
    onSync: (String) -> Unit,
) {
    var note by remember(project) {
        mutableStateOf(if (changes > 0) "$changes change${if (changes == 1) "" else "s"}" else "")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sync $project") },
        text = {
            Column {
                Text(
                    if (changes > 0) {
                        "These changes will be committed, then pushed. Say what they " +
                            "were for, and it becomes the message GitHub shows."
                    } else {
                        "Nothing is waiting. Syncing only pushes what is already here."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("What changed?") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSync(note) }) { Text("Sync") }
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
    Column(modifier = Modifier.fillMaxWidth()) {
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
        Text(
            "Your sentence goes into the same log the app has been writing since it " +
                "opened, so the lines above it are what happened.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}
