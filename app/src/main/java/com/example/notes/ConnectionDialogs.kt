package com.example.notes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * **The two windows a connection is set in**, one per kind — reached from a row's own ⋮.
 *
 * Two windows rather than one with a switch, because the two kinds are *independent acts*: the plan
 * keeps them apart on purpose ("a git token can push; a model key can spend money"), and a single
 * window with a toggle would have made one act out of two decisions with different blast radii.
 *
 * ### What these windows do not do
 *
 * - **They do not show a key that is stored.** A window that printed one would be a screenshot away
 *   from a leak; an empty key box therefore means "keep the one that is here", and the supporting
 *   sentence says whether one is.
 * - **They do not scroll a Column.** A scrollable `Column` inside an `AlertDialog` never reports idle
 *   under Robolectric — measured, and the reason `AddSecretDialog` has the same note — so the fields
 *   are few enough to fit and the *body* field alone is height-capped.
 * - **They do not decide anything.** Every refusal is [ConnectionDraft.problemSaid] and every send is
 *   the view model's, so a test of the rules needs no screen and the window cannot drift from them.
 */
@Composable
fun LlmConnectionDialog(
    /** What the scope would use if nothing were typed — the hint under each field. */
    inForce: ConnectionDraft,
    /** Where this row is, in the app's own words. */
    where: String,
    onDismiss: () -> Unit,
    onSave: (ConnectionDraft) -> Unit,
    onCheck: (ConnectionDraft, (String) -> Unit) -> Unit,
) {
    var address by remember { mutableStateOf(inForce.address) }
    var secret by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(inForce.model) }
    var effort by remember { mutableStateOf(inForce.effort) }
    var shapeUrl by remember { mutableStateOf(inForce.shapeUrl) }
    var shapeBody by remember { mutableStateOf(inForce.shapeBody) }
    // **Off unless ticked, and it starts off even when the row already has it on**, because a window
    // that opened with a permission already ticked would re-grant it on every save — silently, and
    // after somebody had turned it off somewhere else. The hint says whether it is on now.
    var nameable by remember { mutableStateOf(inForce.nameable) }
    var editingShape by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var checkSaid by remember { mutableStateOf<String?>(null) }

    val draft = ConnectionDraft(
        kind = ConnKind.LLM,
        address = address,
        secret = secret,
        model = model,
        effort = effort,
        shapeUrl = shapeUrl,
        shapeBody = shapeBody,
        nameable = nameable,
    )
    val problem = draft.problemSaid()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("The model connection — $where") },
        text = {
            Column {
                OutlinedTextField(
                    value = address,
                    onValueChange = {
                        address = it
                        checkSaid = null
                    },
                    label = { Text("Address") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    supportingText = { Text("The base URL a request goes to, like https://openrouter.ai/api/v1.") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = secret,
                    onValueChange = {
                        secret = it
                        checkSaid = null
                    },
                    label = { Text("Key") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next,
                    ),
                    supportingText = {
                        Text(
                            // **Never filled in, and it says why.** The person has to be able to tell
                            // "there is one here" from "there is not", because the two mean different
                            // things about an empty box.
                            if (inForce.address.isEmpty()) {
                                "Kept for this connection. Leave it empty to send no key — a server " +
                                    "on this phone wants none."
                            } else {
                                "Leave it empty to keep the key that is here, or to send none."
                            },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    supportingText = { Text("The name this server serves it under — not another server's name for it.") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = effort,
                    onValueChange = { effort = it },
                    label = { Text("Effort") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    supportingText = { Text("none, low, medium, high or max. Blank means the server's own default.") },
                    modifier = Modifier.fillMaxWidth(),
                )
                // **The grant, and it is a grant rather than a setting.** Off, this key is exactly as
                // invisible to the agent as it is today; on, `auth: llm` resolves to it for this
                // server only. The sentence says both halves, because "allow" alone would read as
                // "allow anywhere".
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = nameable, onCheckedChange = { nameable = it })
                    Text(
                        "Let the agent spend this on its own requests to this host",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                // **The shape, behind one button.** It is the advanced half — most servers answer the
                // app's own preset — and a window that showed a JSON body beside four fields would
                // make a person think they had to have one.
                TextButton(onClick = { editingShape = true }) {
                    Text(
                        if (shapeUrl.isBlank() && shapeBody.isBlank()) {
                            "Request shape: the app's own (tap to change)"
                        } else {
                            "Request shape: $shapeUrl (tap to change)"
                        },
                    )
                }
                if (problem != null) {
                    Text(
                        problem,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                checkSaid?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    enabled = !checking && problem == null,
                    onClick = {
                        checking = true
                        checkSaid = null
                        onCheck(draft) { said ->
                            checking = false
                            checkSaid = said
                        }
                    },
                ) { Text(if (checking) "Checking…" else "Check") }
                TextButton(enabled = problem == null, onClick = { onSave(draft) }) { Text("Save") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )

    if (editingShape) {
        ShapeDialog(
            kind = ShapeKind.CHAT,
            url = shapeUrl.ifBlank { Shape.CHAT.url },
            body = shapeBody.ifBlank { Shape.CHAT.body.toString() },
            onDismiss = { editingShape = false },
            onUsePreset = {
                shapeUrl = ""
                shapeBody = ""
                editingShape = false
            },
            onResult = { url, text ->
                shapeUrl = url
                shapeBody = text
                editingShape = false
            },
        )
    }
}

/**
 * **The git window**: the host a token is for, the token, and the name a commit is made as.
 *
 * **The address field asks for a host, and the sentence says why**: the remote's address is git's own
 * fact (`remote.origin.url`), so storing it here would put a second copy on the phone — and the copy
 * is the one that goes stale when the address is changed.
 */
@Composable
fun GitConnectionDialog(
    inForce: ConnectionDraft,
    where: String,
    onDismiss: () -> Unit,
    onSave: (ConnectionDraft) -> Unit,
    onForget: () -> Unit,
    onCheck: (ConnectionDraft, (String) -> Unit) -> Unit,
) {
    var address by remember { mutableStateOf(inForce.address) }
    var secret by remember { mutableStateOf("") }
    var user by remember { mutableStateOf(inForce.user) }
    var nameable by remember { mutableStateOf(inForce.nameable) }
    var checking by remember { mutableStateOf(false) }
    var checkSaid by remember { mutableStateOf<String?>(null) }

    val draft = ConnectionDraft(
        kind = ConnKind.GIT,
        address = address,
        secret = secret,
        user = user,
        nameable = nameable,
    )
    val problem = draft.problemSaid()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("The git credential — $where") },
        text = {
            Column {
                OutlinedTextField(
                    value = address,
                    onValueChange = {
                        address = it
                        checkSaid = null
                    },
                    label = { Text("Host") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Next,
                    ),
                    supportingText = { Text("The host the token is for, like gitlab.com. The project's own address stays the remote's.") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = secret,
                    onValueChange = {
                        secret = it
                        checkSaid = null
                    },
                    label = { Text("Token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next,
                    ),
                    supportingText = { Text("Never shown again, never written into a file, and never sent anywhere but this host.") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text("Name commits are made as") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    supportingText = { Text("What the host wants beside the token: oauth2 on GitLab, blank on GitHub.") },
                    modifier = Modifier.fillMaxWidth(),
                )
                // **The grant, per kind.** A person may let the agent ask a model server what models
                // exist without letting it push as them, which is why this checkmark is separate from
                // the model window's — and why neither defaults to on.
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Checkbox(checked = nameable, onCheckedChange = { nameable = it })
                    Text(
                        "Let the agent spend this on its own requests to this host",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (problem != null) {
                    Text(
                        problem,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                checkSaid?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(
                    enabled = !checking && problem == null,
                    onClick = {
                        checking = true
                        checkSaid = null
                        onCheck(draft) { said ->
                            checking = false
                            checkSaid = said
                        }
                    },
                ) { Text(if (checking) "Checking…" else "Check") }
                TextButton(enabled = problem == null, onClick = { onSave(draft) }) { Text("Save") }
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = onForget) {
                    Text("Forget", color = MaterialTheme.colorScheme.error)
                }
            }
        },
    )
}

/**
 * **The request, edited as text** — a path and a body, with the app's own preset one tap away.
 *
 * The one rule that matters here is not in this file: [Shape.problem] refuses a path with a scheme, and
 * says so, because a shape that could name an address could send the key somewhere nobody chose. The
 * body is text until it is saved, so a half-typed template is not an error while it is being typed —
 * [ConnectionDraft.shapeProblem] is what refuses it, and it refuses at Save.
 */
@Composable
fun ShapeDialog(
    kind: ShapeKind,
    url: String,
    body: String,
    onDismiss: () -> Unit,
    onUsePreset: () -> Unit,
    onResult: (String, String) -> Unit,
) {
    var path by remember { mutableStateOf(url) }
    var text by remember { mutableStateOf(body) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("The request shape") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("Path") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    supportingText = { Text("A path on the address above, like chat/completions. Never a URL.") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Body") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    supportingText = {
                        Text(
                            // **The vocabulary, listed**, because a person writing a template needs to
                            // know what the app can fill — and the refusal names them too, so a typo is
                            // answered with the same list.
                            "Fill with " + kind.names.sorted().joinToString(", ") { "{{" + it + "}}" } +
                                ". A blank value removes its key.",
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 220.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onResult(path.trim(), text) }) { Text("Use this") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = onUsePreset) { Text("App's own") }
            }
        },
    )
}
