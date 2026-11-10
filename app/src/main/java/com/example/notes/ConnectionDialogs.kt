package com.example.notes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
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
 * **The git credential a scope holds** — the host it is for, the token, and the name commits are made
 * as.
 *
 * **This is the whole window now** (2026-11-10). It used to sit beside `LlmConnectionDialog`, which
 * stored a model, an effort, three numbers and a request shape per scope; every one of those belongs to
 * a request form, so the model half went with `ConnKind.LLM` and this is what the vault has left to
 * keep.
 *
 * Two rules the drawing follows:
 *
 * - **It does not scroll a Column.** A scrollable `Column` inside an `AlertDialog` never reports idle
 *   under Robolectric — measured, and the reason `AddSecretDialog` has the same note — so the fields
 *   are few enough to fit.
 * - **It does not decide anything.** Every refusal is [ConnectionDraft.problemSaid] and every send is
 *   the view model's, so a test of the rules needs no screen and the window cannot drift from them.
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
                ProblemLine(problem)
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
