package com.example.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One value the agent is built from: which model, which provider, how hard.
 *
 * Three rows rather than one form, because they are three different questions and the third only
 * exists because of the first: the effort ladder belongs to the model and the price to the
 * provider, so the choices are made in that order.
 */
@Composable
fun ModelSection(viewModel: AppViewModel) {
    val config = viewModel.modelConfig
    val problem = viewModel.modelConfigProblem
    var picking by remember { mutableStateOf<String?>(null) }

    if (problem != null) {
        // Named with its line, because the file is the operator's and they are the one who can
        // fix it. The choice in force is still shown below — "your file is wrong" and "here is
        // what the agent would use" are two different facts and both are true.
        Text(
            text = "This file will not read, so the model below is the last one that did. " +
                problem,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }

    SettingRow(
        label = "Model",
        value = config.active.model,
        onTap = { picking = MODEL },
    )
    SettingRow(
        label = "Provider",
        value = config.active.provider,
        onTap = { picking = PROVIDER },
    )
    SettingRow(
        label = "Effort",
        value = config.active.effort,
        onTap = { picking = EFFORT },
    )

    // **Only Reset is here.** Add and Remove belong to the list they change, so they live in the
    // window that opens when the model (or the provider) is pressed — reported from the live log:
    // "move add and remove model buttons into the window that opens after pressing the model name.
    // Add the same to provider. right in settings leave only reset model configuration button".
    // Reset stays because it is the opposite of a choice: it puts back the config the app ships,
    // which is not something to hunt for inside the list of things to pick.
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { viewModel.resetModelConfig() }) { Text("Reset") }
    }

    // **What context the pin actually allows, named here because this is where the model is
    // chosen.** The number under the Context fields is the floor when nothing has been read yet,
    // and a floor that looks like a limit is how "96k" got reported as a cap on a million-token
    // model. This row says which of the two it is, and pressing it re-reads the catalogue.
    val info = viewModel.modelInfo
    val ceiling = ContextBudget.ceiling(
        pin = viewModel.modelConfig.active.provider,
        contextLength = info?.contextLength,
        maxOutput = info?.maxOutput,
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "Context: ${ContextBudget.short(ceiling)}" +
                (info?.contextLength?.let { " of ${ContextBudget.short(it.toLong())}" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = { viewModel.readCatalogue(viewModel.modelConfig.active.model, force = true) },
        ) { Text("Read") }
    }

    if (picking != null) {
        ModelPicker(
            viewModel = viewModel,
            what = picking!!,
            onDismiss = { picking = null },
        )
    }
}

private const val MODEL = "model"
private const val PROVIDER = "provider"
private const val EFFORT = "effort"

/** A label, a value, and the fact that tapping it opens a choice. */
@Composable
private fun SettingRow(label: String, value: String, onTap: () -> Unit) {
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
 * The picker: a list of candidates with their numbers, and one button that takes one.
 *
 * **Select and Cancel rather than tap-to-choose.** A tap on a row while scrolling past it would
 * change the model, and the rows are dense with numbers a person is reading; so a tap only marks
 * a row and the button commits it.
 *
 * Every number is the endpoint's, and every number OpenRouter does not publish is a dash. On the
 * app's own default model that is most of the throughput column — a dash is the true answer, and
 * a zero would read as an outage.
 */
@Composable
private fun ModelPicker(
    viewModel: AppViewModel,
    what: String,
    onDismiss: () -> Unit,
) {
    val config = viewModel.modelConfig
    val active = config.active
    var chosen by remember { mutableStateOf<String?>(null) }
    // The two list acts, held by the picker rather than by the settings screen: the button that
    // opens them is in this window now, and closing one dialog must not close the other.
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    val numbers = viewModel.modelEndpoints
    val info = viewModel.modelInfo
    val failure = viewModel.catalogueProblem
    val reading = viewModel.catalogueReading

    // **The shapes, now; the numbers, when they arrive.** "Updated on entering the selection
    // menu" is what this is: one read per opening, on the app's worker, with no polling
    // anywhere. It is not inside the composition, because a sheet that fetches while it draws
    // freezes exactly when the network is slow — which is when it was opened.
    //
    // **The model list needs it as much as the other two**, and is the one that was left out. The
    // effort ladder is a fact about the model, so it came from here already; but the context
    // limit is too, and with no read the agent fell back to the floor — which is how a 1M-token
    // model came to be trimmed at 96k. Reported from the live log: "why there is 96k limit on
    // trim? it should be only capped by active model context size".
    LaunchedEffect(what, active.model) {
        viewModel.readCatalogue(active.model)
    }

    val allowed = config.providersOf(active.model)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when (what) {
                    MODEL -> "Model"
                    PROVIDER -> "Provider"
                    else -> "Effort"
                }
            )
        },
        text = {
            Column {
                if (failure != null) {
                    Text(
                        text = failure!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                val rows = when (what) {
                    MODEL -> config.models.map { it.id }
                    // From the config, not from the answer: these are the tags the person chose,
                    // and they are on the phone. A sheet that showed nothing until OpenRouter
                    // replied would be a sheet that could not be used offline.
                    PROVIDER -> allowed
                    else -> info?.efforts.orEmpty().ifEmpty { listOf(active.effort) }
                }
                if (rows.isEmpty() && failure == null) {
                    Text(
                        text = "Nothing to choose from yet.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (reading && what != MODEL) {
                    // Said in the list rather than instead of it: the rows are readable while
                    // the prices are still on their way.
                    Text(
                        text = "Reading OpenRouter…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // A plain scrolling Column rather than a lazy list: there are at most a few
                // rows, and a lazy list inside a dialog is measured against a height the
                // dialog does not bound — which is how a list ends up drawing nothing.
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    rows.forEach { row ->
                        val picked = chosen == row || (chosen == null && row == current(what, active))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { chosen = row }
                                .padding(vertical = 6.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (picked) "● " else "○ ",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = row,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            val detail = detailFor(what, row, info, numbers)
                            if (detail.isNotEmpty()) {
                                Text(
                                    text = detail,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = chosen != null,
                onClick = {
                    val pick = chosen ?: return@TextButton
                    when (what) {
                        MODEL -> {
                            // A model change moves the provider too, because the endpoints
                            // belong to the model: keeping the old tag would pin to something
                            // this model does not have.
                            val providers = config.providersOf(pick)
                            val provider = if (active.provider in providers) {
                                active.provider
                            } else {
                                providers.firstOrNull().orEmpty()
                            }
                            viewModel.chooseModel(pick, provider, active.effort, info?.efforts.orEmpty())
                        }
                        PROVIDER -> viewModel.chooseModel(
                            active.model, pick, active.effort, info?.efforts.orEmpty(),
                        )
                        else -> viewModel.chooseModel(
                            active.model, active.provider, pick, info?.efforts.orEmpty(),
                        )
                    }
                    onDismiss()
                },
            ) { Text("Select") }
        },
        dismissButton = {
            // **Add and Remove live here**, beside the list they change — on every step of the
            // choice, because a model is added with its endpoints and one is removed from the
            // model in use. Neither is on the settings screen any more.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { adding = true }) { Text("Add") }
                TextButton(onClick = { removing = true }) { Text("Remove") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )

    if (adding) {
        AddModelDialog(viewModel = viewModel, onDismiss = { adding = false })
    }
    if (removing) {
        RemoveModelDialog(viewModel = viewModel, onDismiss = { removing = false })
    }
}

private fun current(what: String, active: ActiveModel): String = when (what) {
    MODEL -> active.model
    PROVIDER -> active.provider
    else -> active.effort
}

/**
 * The line under a choice: what that model or endpoint is like.
 *
 * Written as a plain sentence of numbers rather than a table, because these are rows in a list
 * that has to stay readable on a phone.
 */
private fun detailFor(
    what: String,
    row: String,
    info: ModelInfo?,
    numbers: List<EndpointInfo>,
): String {
    if (what == MODEL) return ""
    if (what == EFFORT) {
        return if (row == info?.defaultEffort) "the model's own default" else ""
    }
    // No answer yet means no line under the tag — not a row of dashes, which would look like a
    // provider OpenRouter publishes nothing about rather than one it has not been asked about.
    val endpoint = numbers.firstOrNull { it.tag == row } ?: return ""
    val parts = ArrayList<String>(5)
    parts.add("in ${money(endpoint.priceIn)} · out ${money(endpoint.priceOut)} per M")
    parts.add("cache ${money(endpoint.priceCache)}")
    parts.add("${number(endpoint.tokensPerSecond)} tok/s")
    // Named first, because a nested template with a quote inside it is the exact shape
    // that fails the quote pass and then the compiler on a phone-only build.
    val limit = endpoint.maxOutput
    parts.add(if (limit == null) "max —" else "max ${tokens(limit)}")
    if (!endpoint.tools) parts.add("no tool calls")
    return parts.joinToString(" · ")
}

/** Dollars per million, at the precision that matters at these prices. */
fun money(value: Double?): String = when {
    value == null -> "—"
    value == 0.0 -> "free"
    value < 0.01 -> String.format(Locale.US, "$%.4f", value)
    else -> String.format(Locale.US, "$%.3f", value)
}

/** A statistic OpenRouter may not publish. */
fun number(value: Double?): String =
    if (value == null) "—" else String.format(Locale.US, "%.0f", value)

/** A token count, shortened, because 1,048,576 is a number a person has to count digits of. */
fun tokens(value: Int): String = when {
    value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000 -> "${value / 1_000}k"
    else -> value.toString()
}

/** The release date of a model, as a person reads a date. */
fun released(at: Long?): String = if (at == null) "—" else
    SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(at * 1000))

/**
 * Adding a model: type the id, fetch it, then tick the endpoints it may use.
 *
 * The fetch is the point. A form that only wrote a string into a file would let a typo through
 * and fail eight seconds into the agent's first turn with a 404; asking OpenRouter first turns
 * that into "OpenRouter does not have that model", at the moment the person is looking.
 */
@Composable
private fun AddModelDialog(viewModel: AppViewModel, onDismiss: () -> Unit) {
    var id by remember { mutableStateOf("") }
    var asked by remember { mutableStateOf(false) }
    var ticked by remember { mutableStateOf<Set<String>>(emptySet()) }
    val info = viewModel.previewInfo
    val numbers = viewModel.previewEndpoints
    val failure = viewModel.previewProblem
    val fetching = viewModel.previewReading

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a model") },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = id,
                    onValueChange = {
                        id = it
                        asked = false
                    },
                    label = { Text("Model id, as OpenRouter writes it") },
                    placeholder = { Text(ModelConfigFile.DEFAULT_MODEL) },
                    singleLine = true,
                )
                TextButton(
                    enabled = id.isNotBlank() && !fetching,
                    onClick = {
                        asked = true
                        // Two round trips, on the app's worker: a button that froze the window
                        // for them would look like a button that did nothing.
                        viewModel.readPreview(id)
                    },
                ) { Text(if (fetching) "Reading…" else "Fetch") }

                val found = info
                if (found != null && asked) {
                    Text(found.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "${found.inputModalities.joinToString("+")} -> " +
                            found.outputModalities.joinToString("+") +
                            " · ${found.contextLength?.let { tokens(it) } ?: "—"} context" +
                            " · released ${released(found.releasedAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(
                        modifier = Modifier
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        numbers.forEach { endpoint ->
                            val usable = endpoint.tools
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = usable) {
                                        ticked = if (endpoint.tag in ticked) {
                                            ticked - endpoint.tag
                                        } else {
                                            ticked + endpoint.tag
                                        }
                                    }
                                    .padding(vertical = 4.dp),
                            ) {
                                Text(if (endpoint.tag in ticked) "✓ " else "· ")
                                Column {
                                    Text(
                                        text = endpoint.tag,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (usable) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    )
                                    Text(
                                        text = "in ${money(endpoint.priceIn)} · " +
                                            "out ${money(endpoint.priceOut)} · " +
                                            "${number(endpoint.tokensPerSecond)} tok/s" +
                                            if (usable) "" else " · cannot call tools",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                if (failure != null) {
                    Text(
                        text = failure!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = info != null && ticked.isNotEmpty(),
                onClick = {
                    viewModel.addModel(id.trim(), ticked.toList())
                    onDismiss()
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Removing a model, which the active one refuses. */
@Composable
private fun RemoveModelDialog(viewModel: AppViewModel, onDismiss: () -> Unit) {
    var failure by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove a model") },
        text = {
            Column {
                viewModel.modelConfig.models.forEach { choice ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.removeModel(choice.id).fold(
                                    onSuccess = { onDismiss() },
                                    onFailure = { failure = it.message },
                                )
                            }
                            .padding(vertical = 8.dp),
                    ) {
                        Text(choice.id, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    HorizontalDivider()
                }
                if (failure != null) {
                    Text(
                        text = failure!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * The OpenRouter key: **the first field of the agent block**, because nothing else in it works
 * without one.
 *
 * It is here rather than inside [ModelSection] because of an order a person found confusing: the
 * three model rows were on the settings screen, and the key — the thing that makes them usable —
 * was at the bottom of them. Reported from the live log: "move agent block up, right below signed in
 * as block. and make openrouter key the first field of agent block".
 *
 * The state is read through a revision ([AppViewModel.modelKeySaved]) rather than the getter: a
 * plain property gives Compose no reason to re-run this block when a dialog writes a key, and the
 * row went on saying "No key yet" after the key had been kept, which looks exactly like a key that
 * did not take.
 */
@Composable
fun KeyRow(viewModel: AppViewModel, onSetKey: () -> Unit) {
    val saved = viewModel.modelKeySaved
    val keyState = remember(saved) { viewModel.modelKeyState }
    val hasKey = keyState == KeyState.READABLE
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = when (keyState) {
                    KeyState.READABLE -> "An OpenRouter key is kept on this phone for this account."
                    KeyState.LOCKED ->
                        "A key is kept for this account. This phone has to be unlocked before the " +
                            "model list or the agent can read it."
                    KeyState.NONE ->
                        "No OpenRouter key yet, so the model list cannot be read and the agent " +
                            "cannot talk."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (hasKey) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
        TextButton(onClick = onSetKey) {
            Text(if (keyState == KeyState.NONE) "Set key" else "Change key")
        }
    }
}
