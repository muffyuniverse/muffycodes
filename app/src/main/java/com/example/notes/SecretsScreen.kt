package com.example.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * The vault's door: **the list of secrets this account has, and the way to add or remove one.**
 *
 * **Why this screen had to exist.** `Credentials.setSecret` was encrypted, tested, and called by
 * nothing in the app — a tested function with no caller, the same defect the morning audit found in
 * `createdByOperator` and `CiSetup`. The `http` tool's `auth:` names a secret, so without a door the
 * first secret could never exist and the feature would be untestable in practice as well as unusable.
 *
 * **Names are shown and values are never shown.** Not even once after typing, and not in a masked
 * field — the value is written into the store and the screen keeps nothing. A person who forgets a
 * key replaces it; there is no reading it back, which is what makes the store worth having.
 *
 * **The host is asked for, because a secret is not usable without one.** The vault binds each value
 * to a host and refuses any other, so the host is part of what the secret *is* rather than a setting
 * beside it. Asking for it here is the only way that rule is true of every secret.
 *
 * **One vault, three scopes.** The app's own keys (the GitHub token, the model key) live in a
 * separate namespace that `auth:` cannot name at all. Everything here is either **all projects** or
 * **one project**, and the row says which — because "why can this project see that token" is a
 * question a person has to be able to answer by looking.
 */
@Composable
fun SecretsScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
) {
    // Read once per visit and after every change, because the list is the thing being edited.
    var names by remember { mutableStateOf(viewModel.secretNames()) }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<String?>(null) }
    // **A refusal is shown, not dropped.** `saveSecret` returns the sentence to show and this screen
    // used to discard it — so a secret the phone refused to keep looked exactly like one it kept,
    // and the person found out when a request failed. A failure has to say what happened.
    var said by remember { mutableStateOf<String?>(null) }
    // **The projects a secret may be scoped to**, which is also the list the selector offers. Read
    // once: a project cannot be made from inside this screen, so the list cannot go stale while it
    // is open.
    val projects = remember { viewModel.scopeProjects() }

    Column(modifier = Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Secrets", style = MaterialTheme.typography.titleMedium)
            Column(modifier = Modifier.weight(1f)) {}
            TextButton(onClick = { adding = true }) { Text("Add") }
        }
        Text(
            "A secret is a value the app can use without ever writing it down: a token for an API, " +
                "a key that opens an encrypted log. Each one is stored for one host and is refused " +
                "for every other, so naming a secret cannot send it somewhere it was not meant to go.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        said?.let { problem ->
            Text(
                problem,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        if (names.isEmpty()) {
            Text(
                "No secrets on this phone yet. The agent's `http` tool and an MCP article both " +
                    "name one of these rather than holding a token of their own.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (name in names) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.bodyMedium)
                        // **The host is shown, because it is the half of the rule a person has to
                        // be able to check.** A secret whose host was mistyped looks identical in
                        // every other respect, and would refuse the request it was made for.
                        // **The scope, beside the host.** "all projects" and a project's name are
                        // the two answers, and which one it is decides who can read the value — so
                        // it belongs on the row, not behind a tap.
                        Text(
                            viewModel.secretHost(name).orEmpty().ifEmpty { "used by this phone only" } +
                                " · " + scopeLabel(viewModel.scopeOf(name)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { removing = name }) { Text("Remove") }
                }
            }
        }
        Row(modifier = Modifier.padding(top = 16.dp)) {
            TextButton(onClick = onBack) { Text("Back") }
        }
    }

    if (adding) {
        AddSecretDialog(
            projects = projects,
            onDismiss = { adding = false },
            onSave = { name, value, host, scope ->
                // **The scope decides which store method is called**, not a nullable argument: general
                // is not "a project called null", and passing it as one would store a general secret
                // under the account scope.
                val problem = when (scope) {
                    SecretScope.General -> viewModel.saveGeneralSecret(name, value, host)
                    else -> viewModel.saveSecret(name, value, host, scope.projectArg)
                }
                said = problem
                names = viewModel.secretNames()
                adding = false
            },
        )
    }
    removing?.let { name ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove $name?") },
            text = {
                Text(
                    "The value is deleted from this phone. Anything that names it will stop " +
                        "working until a new one is added.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.forgetSecret(name)
                        names = viewModel.secretNames()
                        removing = null
                    },
                ) {
                    // **"Forget", not "Remove".** The row behind the window already has a Remove,
                    // so two buttons reading the same word in one frame is a test that has to
                    // count them — and a person who cannot tell which one they are about to press.
                    Text("Forget")
                }
            },
            dismissButton = {
                TextButton(onClick = { removing = null }) { Text("Keep") }
            },
        )
    }
}

/**
 * The window that makes one secret.
 *
 * **Three fields and every one of them required.** A name with no host is a secret that could go
 * anywhere, which is the failure this whole mechanism exists to prevent, so the save button refuses
 * until the host is there and says why rather than staying quietly greyed out.
 */
@Composable
fun AddSecretDialog(
    /** Every project, for the scope selector. Empty when there are none yet. */
    projects: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onSave: (name: String, value: String, host: String, scope: SecretScope) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    // **The account scope is the default**, because it is the narrowest that does not name a project:
    // a secret is presumed to belong to the person who typed it.
    var scope by remember { mutableStateOf<SecretScope>(SecretScope.Account) }
    val problem = secretProblem(name, value, host)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a secret") },
        text = {
            // **No scroll here, deliberately.** Three fields fit, and a scrollable Column inside an
            // AlertDialog never reports idle in a Robolectric test — the scroll state animates while
            // the dialog settles, so a test that types into it waits for a quiescence that does not
            // come. The list behind the dialog scrolls, because that one grows.
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    supportingText = {
                        Text("What the app calls it — an article or a tool names this, never the value.")
                    },
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Value") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Next,
                    ),
                    supportingText = { Text("Never shown again, and never written into a file.") },
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Host") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done,
                    ),
                    supportingText = {
                        Text("Where it may be used, like api.github.com. Nowhere else, ever.")
                    },
                )
                // **The scope, as a button rather than a dropdown.** A dropdown inside an
                // AlertDialog is a second popup over the first, which is both fiddly to drive and
                // awkward to read; a button that cycles through "all projects" and each project is
                // one line, always visible, and says its current answer.
                if (projects.isNotEmpty()) {
                    TextButton(
                        onClick = { scope = nextScope(scope, projects) },
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        Text(
                            "Scope: " + scopeLabel(scope) + " (tap to change)",
                        )
                    }
                }
                if (problem != null) {
                    Text(
                        problem,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = problem == null,
                onClick = { onSave(name.trim(), value, host.trim(), scope) },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * **Which of the four scopes a secret lives in** (§13c.4).
 *
 * This is a type rather than a `String?` because **three scopes cannot be told apart by null**: the
 * old signature used null for "all projects" (the account), and adding general would have needed a
 * second sentinel — a magic project name, or an empty string — which is exactly the kind of
 * indistinguishable-in-code mistake the vault's host rule exists to prevent.
 *
 * **The account scope is still the default**, because it is the narrowest scope that does not name a
 * project: a secret is presumed to belong to the person who typed it.
 */
sealed interface SecretScope {
    /** Every project on **this account**. The default. */
    data object Account : SecretScope

    /** Every project, **whatever the account** — the shared service key. */
    data object General : SecretScope

    /** One project only. */
    data class Project(val name: String) : SecretScope

    /**
     * The `project` argument the store takes: null for account, a name for a project.
     *
     * **General is null too**, and that is not a loss: [CredentialStore] stores general under its own
     * key prefix, so the store is told which method to call by the caller, not by this value.
     */
    val projectArg: String?
        get() = (this as? Project)?.name
}

/** How a scope reads on a row: the fact, not the null. */
fun projectScopeLabel(project: String?): String =
    project?.let { "only $it" } ?: "all projects"

/**
 * How a [SecretScope] reads on a row and in the selector.
 *
 * **Each of the three is a different sentence**, because they are different facts and a person
 * deciding where a key should live has to be able to tell them apart at a glance.
 */
fun scopeLabel(scope: SecretScope): String = when (scope) {
    SecretScope.Account -> "this account"
    SecretScope.General -> "all projects, every account"
    is SecretScope.Project -> "only ${scope.name}"
}

/** The scope after [current] when the selector is tapped: account, general, then each project. */
fun nextScope(current: SecretScope, projects: List<String>): SecretScope {
    val order = listOf<SecretScope>(SecretScope.Account, SecretScope.General) +
        projects.map { SecretScope.Project(it) }
    // **A project deleted while the dialog was open** starts from the beginning rather than keeping a
    // scope that no longer exists.
    val at = order.indexOfFirst { it == current }
    return order[((at + 1).takeIf { at >= 0 } ?: 0) % order.size]
}

/**
 * Why this secret cannot be saved yet, or null when it can.
 *
 * **A sentence rather than a disabled button**, because a button that will not press and says
 * nothing is the failure this app keeps finding: the person cannot tell whether the app is broken or
 * waiting for them. Each answer names the field that is missing.
 */
fun secretProblem(name: String, value: String, host: String): String? = when {
    name.isBlank() -> "Give it a name, so something can ask for it."
    // **A name is a key in a store, not a path**, but a name with a slash in it would collide with
    // another name once encoded, so it is refused where the person can still change it.
    name.contains('/') -> "A name cannot contain a slash. Try a dash instead."
    value.isEmpty() -> "Paste the value. It is stored encrypted and is never shown again."
    host.isBlank() -> "Say which host may use it. A secret with no host could go anywhere."
    // **A URL in the host field is the mistake to catch**, because it looks right and would never
    // match: the vault compares hosts, and `https://api.github.com/x` is not one.
    host.contains("://") || host.contains('/') ->
        "Just the host, without the scheme or a path — api.github.com, not https://api.github.com."
    else -> null
}
