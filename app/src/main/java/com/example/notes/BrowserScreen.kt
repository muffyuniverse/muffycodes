package com.example.notes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File

/** What the browser asks the app to do, so the screen stays free of state. */
class BrowserActions(
    val onOpen: (Entry) -> Unit,
    val onRename: (Entry) -> Unit,
    val onMove: (Entry) -> Unit,
    val onDelete: (Entry) -> Unit,
    val onTogglePin: (Entry) -> Unit,
    val onHistory: (Entry) -> Unit,
    val onMoveIntoRepository: (Entry) -> Unit,
    val onKeepOnlyOnPhone: (Entry) -> Unit,
)

/**
 * The folder the app is showing: the account's root, the projects list, or any
 * folder inside them.
 *
 * When the path is `projects`, this is the **Projects screen** (§6): one row per
 * project, each with its unsynced count, a `⋮` menu and the add button. Anywhere
 * else it is an ordinary folder listing (§3): what you made, and nothing the app
 * made.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BrowserScreen(
    viewModel: AppViewModel,
    username: String?,
    onOpenSettings: () -> Unit,
    onOpenAccount: () -> Unit,
    actions: BrowserActions,
    onAddProject: () -> Unit,
    onRowSync: (String) -> Unit,
    onRowRename: (String) -> Unit,
    onRowDelete: (String) -> Unit,
    onCreateFile: () -> Unit,
    onCreateFolder: () -> Unit,
) {
    val projects = viewModel.atProjects
    val here = viewModel.path

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    if (viewModel.canGoUp) {
                        IconButton(onClick = { viewModel.up() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up")
                        }
                    } else {
                        IconButton(onClick = onOpenAccount) {
                            Icon(Icons.Filled.Menu, contentDescription = "Account")
                        }
                    }
                },
                title = {
                    Column {
                        Text(
                            text = when {
                                here.isEmpty() -> username ?: "Muffy Codes"
                                else -> here.last()
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val small = when {
                            here.isEmpty() -> ""
                            here.size == 1 -> "the account's folders"
                            here.size == 2 -> "project"
                            else -> here.drop(1).dropLast(1).joinToString(" › ")
                        }
                        if (small.isNotEmpty()) {
                            Text(
                                text = small,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    if (viewModel.currentProject != null) {
                        // Search is scoped to the project (§7): it exists because a
                        // project's tree nests and what you remember is a name.
                        IconButton(onClick = { viewModel.showSearch = !viewModel.showSearch }) {
                            Icon(Icons.Filled.Search, contentDescription = "Search this project")
                        }
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            if (viewModel.workspace == null) {
                // Nothing can be made until there is an account to make it in.
                Spacer(Modifier.size(0.dp))
            } else if (projects) {
                FloatingActionButton(onClick = onAddProject) {
                    Icon(Icons.Filled.Add, contentDescription = "New project or pull one in")
                }
            } else {
                // A folder gets the two things a folder gets (§3).
                var adding by remember { mutableStateOf(false) }
                Column(horizontalAlignment = Alignment.End) {
                    if (adding) {
                        TextButton(onClick = { adding = false; onCreateFolder() }) {
                            Text("New folder")
                        }
                        TextButton(onClick = { adding = false; onCreateFile() }) {
                            Text("New file")
                        }
                    }
                    FloatingActionButton(onClick = { adding = !adding }) {
                        Icon(Icons.Filled.Add, contentDescription = "Add")
                    }
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // The slim row (§7): account, project, changes, and one button that
            // is always in the same place.
            val project = viewModel.currentProject
            if (project != null) {
                SlimRow(
                    account = username ?: "",
                    project = project,
                    changes = viewModel.changesOf(project),
                    busy = viewModel.busy,
                    onSync = { onRowSync(project) },
                )
                HorizontalDivider()
            }

            if (viewModel.showSearch) {
                OutlinedTextField(
                    value = viewModel.query,
                    onValueChange = { viewModel.query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    placeholder = { Text("Search in $project") },
                    singleLine = true,
                    trailingIcon = {
                        if (viewModel.isSearching) {
                            IconButton(onClick = { viewModel.query = "" }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                )
            }

            if (viewModel.workspace == null) {
                EmptyState(
                    title = "Sign in to begin",
                    text = "Your account is the root of everything here: its projects, " +
                        "their repositories, and the files you keep beside them.",
                )
            } else if (projects) {
                ProjectList(viewModel, onRowSync, onRowRename, onRowDelete)
            } else {
                EntryList(viewModel, actions)
            }
        }
    }
}

/** Account, project, the unsynced count and the sync button, in one slim row. */
@Composable
private fun SlimRow(
    account: String,
    project: String,
    changes: Int,
    busy: Boolean,
    onSync: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "$account · $project",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (changes > 0) "$changes changed" else "up to date",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = onSync) { Text("Sync") }
            }
        }
    }
}

/** The Projects screen: a row each, with the count and the ⋮ menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectList(
    viewModel: AppViewModel,
    onSync: (String) -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val names = viewModel.projectNames
    if (names.isEmpty()) {
        EmptyState(
            title = "No projects yet",
            text = "The add button makes one on GitHub and pulls it in, " +
                "or brings in a repository you already have."
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(names) { name ->
            var menu by remember { mutableStateOf(false) }
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f)
                            .combinedClickable(onClick = {
                                viewModel.open(listOf(Workspace.PROJECTS, name))
                            }),
                    ) {
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = viewModel.changesOf(name).let {
                                if (it > 0) "$it changed" else "up to date"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Sync") },
                                onClick = {
                                    menu = false
                                    onSync(name)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Rename…") },
                                onClick = {
                                    menu = false
                                    onRename(name)
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text("Delete…", color = MaterialTheme.colorScheme.error)
                                },
                                onClick = {
                                    menu = false
                                    onDelete(name)
                                },
                            )
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

/** An ordinary folder: what you made, folders first, pinned on top. */
@Composable
private fun EntryList(viewModel: AppViewModel, actions: BrowserActions) {
    val entries = viewModel.visibleEntries
    val pinnedPaths = viewModel.pins
    if (entries.isEmpty()) {
        EmptyState(
            title = if (viewModel.isSearching) "Nothing matches" else "Nothing here yet",
            text = if (viewModel.isSearching) "Try another word."
            else "The add button makes a file or a folder.",
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(entries) { entry ->
            val ws = viewModel.workspace
            val path = ws?.relative(entry.file)?.let { ws.pathString(it) }
            val pinned = path != null && path in pinnedPaths
            EntryRow(
                entry = entry,
                pinned = pinned,
                inWorktree = viewModel.inWorktree,
                local = !viewModel.inWorktree && viewModel.currentProject != null,
                actions = actions,
                onOpen = {
                    if (entry.isFolder) viewModel.openChild(entry.name) else actions.onOpen(entry)
                },
            )
            HorizontalDivider()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    entry: Entry,
    pinned: Boolean,
    inWorktree: Boolean,
    local: Boolean,
    actions: BrowserActions,
    onOpen: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onOpen, onLongClick = { menu = true })
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (pinned) {
            Icon(
                Icons.Filled.Star,
                contentDescription = "Pinned",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name + if (entry.isFolder) "/" else "",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when (entry.kind) {
                    EntryKind.FOLDER -> "folder"
                    EntryKind.IMAGE -> "image"
                    EntryKind.TEXT -> "text"
                } + if (local) "  ·  local" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(if (pinned) "Unpin" else "Pin") },
                    onClick = {
                        menu = false
                        actions.onTogglePin(entry)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Rename…") },
                    onClick = {
                        menu = false
                        actions.onRename(entry)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Move…") },
                    onClick = {
                        menu = false
                        actions.onMove(entry)
                    },
                )
                if (inWorktree) {
                    DropdownMenuItem(
                        text = { Text("Keep only on this phone") },
                        onClick = {
                            menu = false
                            actions.onKeepOnlyOnPhone(entry)
                        },
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text("Move into the repository") },
                        onClick = {
                            menu = false
                            actions.onMoveIntoRepository(entry)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("History") },
                    onClick = {
                        menu = false
                        actions.onHistory(entry)
                    },
                )
                DropdownMenuItem(
                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        menu = false
                        actions.onDelete(entry)
                    },
                )
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.size(8.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/** The account sheet the top bar's menu opens (§5). */
@Composable
fun AccountSheet(
    account: Account?,
    stored: List<Account>,
    onDismiss: () -> Unit,
    onAdd: () -> Unit,
    onSwitch: (String) -> Unit,
    onRemove: () -> Unit,
    onEditIdentity: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(account?.login ?: "No account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (account != null) {
                    Text(
                        account.commitEmail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { onEditIdentity() }) { Text("Change commit name and email") }
                    HorizontalDivider()
                }
                if (stored.size > 1) {
                    Text("Switch to", style = MaterialTheme.typography.labelMedium)
                    stored.filter { it.login != account?.login }.forEach { other ->
                        TextButton(onClick = { onSwitch(other.login) }) { Text(other.login) }
                    }
                }
                Row {
                    TextButton(onClick = onAdd) { Text("Add") }
                    if (account != null) {
                        TextButton(onClick = onRemove) {
                            Text("Remove", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
