package com.example.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * The versions of one file, one at a time.
 *
 * This has been a list with a Restore button on every row, a viewer with two arrows,
 * and a list again. It is now **a page per version**: one version fills the screen,
 * a swipe turns to the next, and the one row above it carries the day, the time,
 * its name in git and the two arrows at the corners. That is what stepping through
 * a history is — the thing itself, whole — without a list of rows that all say the
 * same sentence.
 *
 * The messages were the app narrating itself ("Edited milk.txt"), the same sentence
 * on every version; the one message worth the width is the note typed when a
 * project was pushed, and that is the only one shown.
 *
 * **The newest version is what is on disk.** Every change is committed as it
 * happens ([AppViewModel.saveEditor] and friends), so there is no separate
 * "uncommitted" state to show as an extra step.
 *
 * **Selecting touches this file and nothing else.** The preview is read-only;
 * nothing is written until Select is pressed — and Select is also the way back to
 * the editor, because a person who has found the version they want has finished
 * with the history. That is the promise §4 makes about folder organisation,
 * applied to the commonest case of it.
 *
 * Which history answers is decided by where the file is, not by the screen: a
 * file inside a project's worktree is versioned by that project's repository, and
 * everything else — local files, files beside a worktree, folders — by the
 * account's own history (§4).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: AppViewModel,
    title: String,
    onBack: () -> Unit,
    onSelect: () -> Unit = onBack,
    onReport: () -> Unit = {},
) {
    // Asked for on the worker, so the repository walk never happens in a frame — and,
    // more to the point, so it happens **after** the save that was just made has been
    // committed. Both run on the app's one worker, in the order they were asked for,
    // so the newest version here is the one that was just saved rather than the one
    // before it.
    var versions by remember(viewModel.historyPath) {
        mutableStateOf<List<Pair<GitService.CommitInfo, String?>>>(emptyList())
    }
    var loading by remember(viewModel.historyPath) { mutableStateOf(true) }
    LaunchedEffect(viewModel.historyPath, viewModel.historyGeneration) {
        loading = true
        viewModel.requestVersions { found ->
            versions = found
            loading = false
        }
    }
    // The page *is* the chosen version. There is no second copy of that fact:
    // every way of turning the page — a swipe, an arrow — moves this one.
    val pager = rememberPagerState(pageCount = { versions.size })
    val scope = rememberCoroutineScope()
    // A restored version is a new version at the top, so the page that was open is
    // no longer the same version; the newest is what the screen goes back to.
    LaunchedEffect(viewModel.historyGeneration) { pager.scrollToPage(0) }
    val index = pager.currentPage.coerceIn(0, maxOf(versions.lastIndex, 0))
    val shown = versions.getOrNull(index)
    val kind = viewModel.historyKind
    val isFolder = viewModel.historyIsFolder

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    // Back leaves the version alone. Selecting is the other act, and
                    // it is on the other side of the name so the two cannot be
                    // mistaken for each other.
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back without selecting",
                        )
                    }
                },
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (versions.isNotEmpty()) {
                            Text(
                                text = "${index + 1} of ${versions.size} · newest first",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    // One button, doing both halves of the same act: the version
                    // shown becomes the file, and the file is what is on the screen
                    // afterwards. A "Done" that only left, plus a Restore at the
                    // bottom, made a person press one and then wonder whether they
                    // had meant the other.
                    if (shown != null && index > 0) {
                        Button(onClick = {
                            viewModel.restoreVersion(shown.first.id)
                            onSelect()
                        }) { Text("Select") }
                    } else if (versions.isNotEmpty()) {
                        Text(
                            "Newest",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 16.dp),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (versions.isEmpty() || shown == null) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        if (loading) "Reading the history…" else "No earlier versions",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (!loading) {
                        Text(
                            "A version appears here as soon as something changes.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                return@Scaffold
            }

            // ---- one version at a time ---------------------------------------
            //
            // **A swipe, and two arrows at the corners.** One version fills the
            // screen; `<` is the one before it and `>` the one after, which is the
            // gesture a person already makes on a phone. The list this replaced
            // showed every version at once, which read like a file manager rather
            // than like turning back a page.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // `<` is the page before this one, which in a newest-first history
                // is a *newer* version — and `>` is the page after, which is older.
                // That is the direction a swipe goes, so the arrows and the gesture
                // agree instead of being two mental models of the same list.
                TextButton(
                    enabled = index > 0,
                    onClick = {
                        scope.launch { pager.animateScrollToPage(index - 1) }
                    },
                ) {
                    Text("<", style = MaterialTheme.typography.titleLarge)
                }
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = DateFormat.getDateTimeInstance(
                            DateFormat.MEDIUM,
                            DateFormat.SHORT,
                        ).format(Date(shown.first.timestamp)),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val note = shown.first.message.lineSequence().first().trim()
                    Text(
                        text = shown.first.shortId + if (note.isNotEmpty() && !appVoice(note)) {
                            "  ·  $note"
                        } else {
                            ""
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(
                    enabled = index < versions.lastIndex,
                    onClick = {
                        scope.launch { pager.animateScrollToPage(index + 1) }
                    },
                ) {
                    Text(">", style = MaterialTheme.typography.titleLarge)
                }
            }
            HorizontalDivider()

            // ---- what that version was ---------------------------------------
            //
            // The page is the version, so turning it is turning the history. The
            // pager is bound to `pager.currentPage` and to nothing else: an effect
            // that wrote the page back from state it also read is how a screen ends
            // up fighting the finger that is swiping it.
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { page ->
                val version = versions.getOrNull(page)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    when {
                        version == null -> Unit

                        isFolder -> Text(
                            text = version.second?.takeIf { it.isNotBlank() }
                                ?: "This version of the folder was empty.",
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        // A picture or a file of an unknown kind has no text to
                        // show. Saying so is better than a screen of replacement
                        // characters that looks like corruption.
                        kind != EntryKind.TEXT -> Text(
                            text = "This is not a text file, so a version of it cannot be " +
                                "read here. It can still be put back.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        else -> Text(
                            text = version.second.orEmpty(),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }

        }
    }
}

/**
 * True when a commit message is the app narrating a change rather than a person
 * explaining one.
 *
 * Every change the app makes is committed with a sentence it wrote — "Edited
 * milk.txt", "Moved a into b/", "Restored an older version". In a list of versions
 * of one file those repeat, and they are the same sentence for every row. The one
 * message that is not the app's is the note typed when a project was pushed, so
 * that is the one worth the width.
 */
internal fun appVoice(message: String): Boolean {
    val verbs = listOf(
        "Edited ", "Created ", "Deleted ", "Renamed ", "Moved ", "Imported ",
        "Restored ", "Brought ", "Synced ",
    )
    return verbs.any { message.startsWith(it) }
}
