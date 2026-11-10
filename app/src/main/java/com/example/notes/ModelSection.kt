package com.example.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * **The model block, which is one row: where the request templates are.**
 *
 * A chat request is a file (`Schema`), and the pointer to it lives with the thing that runs it — the
 * worktree — so this screen holds only what is a property of *the app*: the folder, and how many
 * templates are in it.
 *
 * **What left, and when.** The chat model's rows (model, provider, effort) and the picker behind each
 * of them went first: they read OpenRouter's catalogue — `/models/{id}/endpoints` with its `provider`
 * tags, `supported_efforts`, `pricing` — and none of those paths exists on any other server, while a
 * request a person writes in a file needs no list to choose from.
 *
 * Then the **embedding and rerank rows** went, with the index they fed (2026-11-09). They were two
 * pointers into this same folder plus a dimensions cycle, and a question — *"rebuild every project's
 * index?"* — that only existed because a vector space cannot be renamed in place. A row whose whole
 * job is to answer a question the app no longer asks is worse than no row: it is a setting that
 * changes nothing.
 */
@Composable
fun ModelSection(
    viewModel: AppViewModel,
    /** Opens `.muffy/schemas` in the browser — the whole template manager, which already exists. */
    onOpenTemplates: () -> Unit = {},
) {
    // **Read per composition, not remembered.** The browser is where these files are written, and a
    // remembered count would keep saying "three" after the person added a fourth — the settings screen
    // recomposes on the way back from the browser, so a plain read is both fresher and smaller.
    val names = viewModel.templateNames()
    SettingRow(
        label = "Request templates",
        value = when (names.size) {
            0 -> "none yet — tap to add one"
            1 -> "1 template — tap to edit"
            else -> "${names.size} templates — tap to edit"
        },
        onTap = onOpenTemplates,
    )
}

/**
 * A label, a value, and the fact that tapping it opens a choice.
 *
 * **`internal` rather than private**, because the settings screen uses the same row for the model
 * connection: one shape for "this row opens a window" is what makes the screen read as a list of doors
 * rather than as a list with one odd row in it.
 */
@Composable
internal fun SettingRow(label: String, value: String, onTap: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * **The minted OpenRouter key, as a vault row** — and the button that mints another.
 *
 * The operator's shape (§3.11.4, 2026-11-10): *"openrouter oauth key minting should be an option in
 * general settings in the same block with vault secrets management button - because it should be used
 * to basically mint an openrouter key as a secret that then could be given an access scope."*
 *
 * So this sits **beside** [SecretRow] rather than above it as a separate mechanism: minting writes an
 * ordinary secret named `@openrouter`, the same kind the person could paste in by hand, and its scope
 * is chosen on the vault screen like any other row's. There used to be a `model_key_<login>` record
 * here as well — a second copy of the same key — which is exactly the duplication that made
 * `ConnKind.LLM` exist.
 *
 * **It says which name it wrote**, because the answer to "why does my chat still say it has no key"
 * is usually that the form names a different secret.
 */
@Composable
fun MintedKeyRow(viewModel: AppViewModel, onSetKey: () -> Unit) {
    // **Read through a revision**, not the getter: a plain property gives Compose no reason to re-run
    // this block when the dialog writes the row, and it went on saying "no key" after a key had been
    // minted — which looks exactly like a key that did not take.
    val saved = viewModel.secretSaved
    val hasKey = remember(saved) { viewModel.hasModelKey }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (hasKey) {
                    "A model key is kept as the secret \"@${Schemas.MINTED_KEY_NAME}\", which a " +
                        "request form names to use it."
                } else {
                    "No model key yet, so a form that names \"@${Schemas.MINTED_KEY_NAME}\" has " +
                        "nothing to spend and the agent cannot talk."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (hasKey) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
        TextButton(onClick = onSetKey) { Text(if (hasKey) "Mint another" else "Mint a key") }
    }
}

/**
 * The vault's row in settings: **how many secrets there are, and the way in.**
 *
 * **A count and not a list.** The names belong on the screen behind the button, where they can be
 * added to and removed; a settings row that listed them would either be a second place to read the
 * same thing or a place to read values, and one of those is a leak.
 *
 * **Says "no secrets" in the error colour**, for the same reason the key row does: a person whose
 * agent cannot reach anything should not have to guess whether that is the app's fault or their own
 * unfinished setup.
 */
@Composable
fun SecretRow(viewModel: AppViewModel, onOpenSecrets: () -> Unit) {
    val changed = viewModel.secretSaved
    // **Rows, not names**: a name held in two scopes is two values a person set up, and the count
    // beside the button has to match the list behind it.
    val count = remember(changed) { viewModel.secretRows().size }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = when (count) {
                    0 -> "No secrets on this phone, so an article or a tool that names one has " +
                        "nothing to use."
                    1 -> "One secret is kept on this phone, for the host it was added for."
                    else -> "$count secrets are kept on this phone, each for its own host."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (count == 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        TextButton(onClick = onOpenSecrets) { Text("Secrets") }
    }
}
