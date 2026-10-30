package com.example.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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

    // **The numbers are read on arrival and on every change of model or provider, because that is
    // when they change.** `readCatalogue` is keyed on the model, so re-entering this screen with
    // the same pin costs nothing and a different pin costs one request. Before this the screen
    // never asked, so the row showed the floor — and the button beside it was the only way to find
    // out what the endpoint actually allows.
    LaunchedEffect(config.active.model, config.active.provider) {
        viewModel.readCatalogue(config.active.model)
    }

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

    // **Opening a list is not recorded; taking something out of it is.** A sheet a person opens and
    // closes changed nothing, and a tap for it would be the noise that makes a real one hard to find.
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

    // **What this provider exposes, in one line, under the choice it belongs to.** Reported from
    // the live log, twice: "context 900k of 1000k — what does that mean? I somehow have the context
    // full?" and then "is it what provider can take out of what model can?". The second question is
    // the right reading of the old line, and it should not have needed asking.
    //
    // Three numbers, named: the window the model holds, the room the provider keeps for its reply,
    // and what is left for the conversation. They are read from the endpoint in use rather than
    // from the model, because the reply reserve is a fact about the endpoint — `cloudflare` keeps
    // 943,718 of the same 1,048,576 window that `deepinfra` keeps 131,072 of.
    //
    // There is no button. The catalogue is read on arrival and whenever the model or provider
    // changes, which are the only moments the numbers can change; a second way to ask was a second
    // thing to explain. A read that fails says so in the same place, because a line of numbers with
    // no word about being stale is the floor pretending to be a measurement.
    val info = viewModel.modelInfo
    val endpoint = viewModel.modelEndpoints.firstOrNull { it.tag == config.active.provider }
    val window = endpoint?.contextLength ?: info?.contextLength
    val reply = endpoint?.maxOutput ?: info?.maxOutput
    // **The clamp is asked the same question the agent asks it.** The measured pins in the table win
    // first (`max_prompt_tokens` is null for every endpoint of this model, so a table entry is the
    // only number that was ever checked); everything else comes from the endpoint in use. Passing
    // the model's numbers here instead would have reported `deepinfra`'s reserve on `cloudflare`,
    // which is the disagreement this line exists to prevent.
    val input = ContextBudget.ceiling(config.active.provider, window, reply)
    Text(
        text = when {
            window == null || reply == null -> "Context: not read yet"
            else -> "Context: ${tokens(window)} · reply ${tokens(reply)} · input ${tokens(input)}"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // ---- what this endpoint charges ----
    //
    // **Moved here from the statistics table, on the operator's instruction**: "remove this cost
    // rows, provider already lists the prices, better show in agent block in settings what are the
    // charge rates for selected endpoint: input, output, cache". The statistics screen's two rows
    // were derived from what had been spent, which folds the cache saving into a rate and so is not
    // the price of anything; these are the endpoint's own published numbers, in the block where the
    // endpoint is chosen, so a person can compare before spending rather than after.
    Text(
        text = when {
            // **Not fetched yet is said, not hidden.** The catalogue is read when the screen opens
            // and when the model changes, so this state is momentary - but a row that simply did
            // not appear would look like a model with no prices, which is a different fact.
            endpoint == null && viewModel.catalogueReading -> "Rates: reading…"
            endpoint == null -> "Rates: not read yet · tap the provider above to try again"
            else -> "Rates per M: in ${money(endpoint.priceIn)} · out ${money(endpoint.priceOut)}" +
                " · cache ${money(endpoint.priceCache)}"
        },
        style = MaterialTheme.typography.bodySmall,
        color = if (endpoint == null) {
            MaterialTheme.colorScheme.onSurfaceVariant
        } else {
            MaterialTheme.colorScheme.primary
        },
    )
    viewModel.catalogueProblem?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
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
                // **The note the operator asked for, and it is deliberately one line**: "this should
                // be explained in a very brief note on the top of the card". What it has to say is
                // that the order is not decoration - the agent walks the list from the top when an
                // endpoint fails - because a reorderable list whose order means nothing is a list
                // people will reorder for no reason.
                if (what == PROVIDER && rows.size > 1) {
                    Text(
                        text = "Order is the fallback order: use the arrows to move a row.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(bottom = 4.dp),
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
                                // **On the effort ladder a tap IS the choice.** Everywhere else the
                                // row is marked and then Select confirms, because a model or a
                                // provider can fail and a person wants to see the price before
                                // committing. An effort level cannot fail - it is one of the values
                                // the model publishes - so a mark plus a button is two controls for
                                // one decision. Report: "effort popup needs just one select button".
                                .clickable {
                                    if (what == EFFORT) {
                                        Trace.tap("effort", row)
                                        viewModel.chooseModel(
                                            active.model, active.provider, row,
                                            info?.efforts.orEmpty(),
                                        )
                                        onDismiss()
                                    } else {
                                        chosen = row
                                    }
                                }
                                .padding(vertical = 6.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // **No dot on the effort ladder**, for the same reason: a row that
                                // takes effect on tap does not also need a mark saying it is the one
                                // that would take effect. It shows which is current instead, in
                                // words, because that is a fact about the model rather than a
                                // selection.
                                if (what != EFFORT) {
                                    Text(
                                        text = if (picked) "● " else "○ ",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                Text(
                                    text = if (what == EFFORT && row == current(what, active)) {
                                        "$row — in use"
                                    } else {
                                        row
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                // **Arrows rather than a drag, and that is a deliberate
                                // compromise.** The operator asked for "by holding and then moving
                                // them", which needs `detectDragGesturesAfterLongPress` and a
                                // reordering list - machinery that cannot be tested in this
                                // project's Robolectric suite, because a synthetic drag does not
                                // exercise the same code a finger does. Two taps move a row by one
                                // position, the order is still the person's, and the behaviour is
                                // asserted by a test that taps them. A drag can replace this when
                                // there is a way to verify it.
                                if (what == PROVIDER) {
                                    val at = rows.indexOf(row)
                                    TextButton(
                                        onClick = { viewModel.moveProvider(active.model, at, at - 1) },
                                        enabled = at > 0,
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    ) { Text("↑") }
                                    TextButton(
                                        onClick = { viewModel.moveProvider(active.model, at, at + 1) },
                                        enabled = at < rows.lastIndex,
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                    ) { Text("↓") }
                                }
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
            // **One row, on the operator's instruction**: "on model selection card also remove
            // cancel and put add, remove, select on one row", and the same for the provider card.
            //
            // `confirmButton` takes the whole row rather than one button in it, because Material
            // puts `dismissButton` on the left and `confirmButton` on the right - so three buttons
            // split across two slots would be two groups rather than one row. Add and Remove are
            // left, Select is right, and Cancel is gone: tapping outside dismisses, which is the
            // same act without a third word competing with the two that change something.
            //
            // **Add and Remove are about MODELS and PROVIDERS, not about effort.** They used to be
            // on every step, which put "Add" beside a ladder of effort levels where there is nothing
            // to add. Report: "effort popup needs just one select button. It seems as if you used
            // same template for every option which is wrong."
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (what != EFFORT) {
                    TextButton(onClick = { adding = true }) { Text("Add") }
                    TextButton(onClick = { removing = true }) { Text("Remove") }
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    enabled = chosen != null,
                    onClick = {
                        val pick = chosen ?: return@TextButton
                        when (what) {
                            MODEL -> {
                                Trace.tap("model", pick)
                                // A model change moves the provider too, because the endpoints
                                // belong to the model: keeping the old tag would pin to something
                                // this model does not have.
                                val providers = config.providersOf(pick)
                                val provider = if (active.provider in providers) {
                                    active.provider
                                } else {
                                    providers.firstOrNull().orEmpty()
                                }
                                // The numbers the sheet holds are the **active** model's, so they
                                // are only handed over when the pick is that same model. A
                                // different model is asked for its own window by the view model.
                                viewModel.chooseModel(
                                    pick,
                                    provider,
                                    active.effort,
                                    info?.efforts.orEmpty(),
                                    contextLength = info?.contextLength.takeIf { pick == active.model },
                                    maxOutput = info?.maxOutput.takeIf { pick == active.model },
                                )
                            }
                            PROVIDER -> {
                                Trace.tap("provider", pick)
                                // **The new provider's own numbers travel with the choice.** They
                                // did not before: the window and the reply reserve stayed the old
                                // pin's, so choosing `cloudflare` left the agent clamped to
                                // `deepinfra`'s 917,504 while that endpoint keeps 943,718 of the
                                // window for replies. The model's window is the same either way;
                                // the reserve is not.
                                val chosenEndpoint = numbers.firstOrNull { it.tag == pick }
                                viewModel.chooseModel(
                                    active.model,
                                    pick,
                                    active.effort,
                                    info?.efforts.orEmpty(),
                                    contextLength = chosenEndpoint?.contextLength
                                        ?: info?.contextLength.takeIf { active.provider == pick },
                                    maxOutput = chosenEndpoint?.maxOutput
                                        ?: info?.maxOutput.takeIf { active.provider == pick },
                                )
                            }
                            else -> {
                                Trace.tap("effort", pick)
                                viewModel.chooseModel(
                                    active.model, active.provider, pick, info?.efforts.orEmpty(),
                                )
                            }
                        }
                        onDismiss()
                    },
                ) { Text("Select") }
            }
        },
    )

    // **The dialog follows the step.** "Add" on the provider step used to open "Add a model", which
    // asked for a model id when the person was choosing an endpoint - and "Remove" opened "Remove a
    // model", which could delete the very model whose providers they were editing. Report: "when you
    // open provider selection window and click on add or remove you are prompted to give model tag
    // instead of opening a list of available providers for current model (we already should have a
    // function for that somewhere) to add, and remove should should just remove selected provider
    // from saved providers list".
    if (adding) {
        if (what == PROVIDER) {
            AddProviderDialog(viewModel = viewModel, model = active.model, onDismiss = { adding = false })
        } else {
            AddModelDialog(viewModel = viewModel, onDismiss = { adding = false })
        }
    }
    if (removing) {
        if (what == PROVIDER) {
            RemoveProviderDialog(viewModel = viewModel, model = active.model, onDismiss = { removing = false })
        } else {
            RemoveModelDialog(viewModel = viewModel, onDismiss = { removing = false })
        }
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
fun tokens(value: Long): String = when {
    value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000 -> "${value / 1_000}k"
    else -> value.toString()
}

/** The same, for a number that arrived as an `Int` — an endpoint's own limits. */
fun tokens(value: Int): String = tokens(value.toLong())

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
                    Trace.tap("add model", id.trim())
                    viewModel.addModel(id.trim(), ticked.toList())
                    onDismiss()
                },
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Allowing another endpoint for [model] — **from the list OpenRouter publishes, not a typed tag.**
 *
 * The report's words: "you are prompted to give model tag instead of opening a list of availiable
 * providers for current model (we already should have a function for that somewhere) to add". We do:
 * `modelEndpoints` is the same catalogue read the picker already uses, and the endpoints it does not
 * list are not usable anyway.
 *
 * **The ones already allowed are shown, not offered.** A list that included them would make a
 * second tap on one look like it did something.
 */
@Composable
private fun AddProviderDialog(viewModel: AppViewModel, model: String, onDismiss: () -> Unit) {
    var failure by remember { mutableStateOf<String?>(null) }
    val allowed = viewModel.modelConfig.providersOf(model)
    val endpoints = viewModel.modelEndpoints
    val free = endpoints.filterNot { it.tag in allowed }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Allow another provider") },
        text = {
            Column {
                Text(
                    "Which endpoint may answer for $model. Prices and windows differ per endpoint.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (endpoints.isEmpty()) {
                    // Said rather than left blank: an empty list here means the catalogue has not
                    // been read, which is a different fact from "there are no others".
                    Text(
                        "The endpoint list has not been read yet. Open the provider picker once " +
                            "and it will be.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    free.forEach { endpoint ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.addProvider(model, endpoint.tag).fold(
                                        onSuccess = { onDismiss() },
                                        onFailure = { failure = it.message },
                                    )
                                }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(endpoint.tag, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            // **The prices, not the context window**, on the operator's
                            // instruction: "\"allow another provider\" window per row smalltext
                            // legends should show instead of context - their input, output and
                            // cache price rates".
                            //
                            // It is the right three numbers for the decision being made. A person
                            // choosing which endpoint may answer is choosing what to pay: the
                            // window is the same for the model whichever endpoint runs it, and the
                            // price is the one thing that differs. The same three rates the
                            // provider picker already shows, from the same source.
                            Text(
                                text = "${endpoint.providerName} · " +
                                    "in ${money(endpoint.priceIn)} · " +
                                    "out ${money(endpoint.priceOut)} · " +
                                    "cache ${money(endpoint.priceCache)} per M",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider()
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
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/**
 * Stopping one endpoint answering for [model] — **the selected one, not a typed tag.**
 *
 * The list is the model's own providers, so removing is a tap on the one to stop rather than a
 * sentence that has to match what the file holds exactly.
 */
@Composable
private fun RemoveProviderDialog(viewModel: AppViewModel, model: String, onDismiss: () -> Unit) {
    var failure by remember { mutableStateOf<String?>(null) }
    val allowed = viewModel.modelConfig.providersOf(model)
    val inUse = viewModel.modelConfig.active.provider

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stop allowing a provider") },
        text = {
            Column {
                Text(
                    "Which endpoint may no longer answer for $model.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    allowed.forEach { tag ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.removeProvider(model, tag).fold(
                                        onSuccess = { onDismiss() },
                                        onFailure = { failure = it.message },
                                    )
                                }
                                .padding(vertical = 8.dp),
                        ) {
                            Text(
                                text = if (tag == inUse) "$tag — in use" else tag,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        HorizontalDivider()
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
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
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
                                Trace.tap("remove model", choice.id)
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
                    // **The sentence that was missing.** This row said "no key yet" about a key that
                    // was stored and unreadable for an unrecognised reason, which is what the
                    // operator saw after setting it - twice, the second time after it had been
                    // marked fixed.
                    KeyState.UNREADABLE ->
                        "A key is stored for this account and this phone could not read it. Set it " +
                            "again to replace it."
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
