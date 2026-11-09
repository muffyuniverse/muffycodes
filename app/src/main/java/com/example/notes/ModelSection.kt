package com.example.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

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
    val problem = viewModel.modelConfigProblem
    if (problem != null) {
        // Named with its line, because the file is the operator's and they are the one who can fix it.
        Text(
            text = "This file will not read, so the model here is the last that did. $problem",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

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
