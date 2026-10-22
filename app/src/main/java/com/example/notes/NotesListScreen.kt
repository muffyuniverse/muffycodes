package com.example.notes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

/**
 * The list screen. It shows the sub-folders of the current location, then the
 * notes inside it. The back arrow and the breadcrumb move up the tree.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesListScreen(
    folders: List<Folder>,
    notes: List<Note>,
    breadcrumb: List<Folder>,
    canNavigateUp: Boolean,
    isSearchingEverything: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    noteCountOf: (String) -> Int,
    totalCountOf: (String) -> Int,
    folderPathOf: (Note) -> String?,
    onNavigateUp: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onOpenNote: (Note) -> Unit,
    onCreateNote: () -> Unit,
    onCreateFolder: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
    /** The name of the git project of this folder, or null. */
    projectName: String?,
    /** How many changes are waiting to be committed. */
    pendingChanges: Int,
    onCommit: () -> Unit,
    onTogglePin: (String) -> Unit,
    onFolderAction: (Folder, FolderAction) -> Unit,
    onNoteAction: (Note, NoteAction) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = breadcrumb.lastOrNull()?.displayName() ?: "Files",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // The breadcrumb, or the name of the git project, in small
                        // type so it does not get in the way.
                        val path = if (breadcrumb.size > 1) {
                            breadcrumb.dropLast(1).joinToString(" > ") { it.displayName() }
                        } else {
                            null
                        }
                        val subtitle = listOfNotNull(
                            path,
                            projectName?.let { "git: $it" },
                        ).joinToString("  •  ")
                        if (subtitle.isNotEmpty()) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (canNavigateUp) {
                        IconButton(onClick = onNavigateUp) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up one level")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        HistoryIcon(
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onCreateFolder) {
                        FolderIcon(
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                    // The commit button. It shows how many changes are waiting.
                    IconButton(onClick = onCommit, enabled = pendingChanges > 0) {
                        BadgedBox(
                            badge = {
                                if (pendingChanges > 0) {
                                    Text(
                                        text = pendingChanges.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onError,
                                    )
                                }
                            },
                        ) {
                            CommitIcon(
                                modifier = Modifier.size(22.dp),
                                tint = if (pendingChanges > 0) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreateNote) {
                Icon(Icons.Filled.Add, contentDescription = "New file")
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                singleLine = true,
                placeholder = { Text("Search all files") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search")
                        }
                    }
                },
            )

            Box(modifier = Modifier.fillMaxSize()) {
                if (folders.isEmpty() && notes.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Nothing here yet.\nTap + to write a file.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(folders, key = { "folder-${it.id}" }) { folder ->
                            FolderRow(
                                folder = folder,
                                noteCount = totalCountOf(folder.id),
                                onOpen = { onOpenFolder(folder.id) },
                                onAction = { onFolderAction(folder, it) },
                            )
                        }

                        if (folders.isNotEmpty() && notes.isNotEmpty()) {
                            item(key = "divider") {
                                Text(
                                    text = if (isSearchingEverything) "Search results" else "Files",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
                                )
                            }
                        }

                        items(notes, key = { "note-${it.id}" }) { note ->
                            NoteRow(
                                note = note,
                                folderPath = folderPathOf(note),
                                onOpen = { onOpenNote(note) },
                                onTogglePin = { onTogglePin(note.id) },
                                onAction = { onNoteAction(note, it) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** The actions available on a folder row. */
enum class FolderAction { Rename, Move, Delete }

/** The actions available on a note row. */
enum class NoteAction { Rename, Move, Delete, History }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderRow(
    folder: Folder,
    noteCount: Int,
    onOpen: () -> Unit,
    onAction: (FolderAction) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FolderIcon(
                modifier = Modifier.size(30.dp),
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp),
            ) {
                Text(
                    text = folder.displayName(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (noteCount > 0) {
                    Text(
                        text = if (noteCount == 1) "1 file" else "$noteCount files",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Text(
                        text = "⋮",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        onClick = {
                            menuOpen = false
                            onAction(FolderAction.Rename)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Move to…") },
                        onClick = {
                            menuOpen = false
                            onAction(FolderAction.Move)
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (noteCount > 0) {
                                    "Delete folder and $noteCount file(s)"
                                } else {
                                    "Delete folder"
                                },
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onAction(FolderAction.Delete)
                        },
                    )
                }
            }
        }
    }
}

/**
 * A folder glyph drawn on a canvas. The Material icon set ships no folder icon in
 * the core artifact, and drawing it keeps the app free of the large
 * icons-extended dependency.
 */
@Composable
fun FolderIcon(modifier: Modifier = Modifier, tint: Color) {    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val tabWidth = w * 0.45f
        val stroke = (minOf(w, h) * 0.08f).coerceAtLeast(1.5f)

        val path = Path().apply {
            moveTo(w * 0.08f, h * 0.86f)
            lineTo(w * 0.08f, h * 0.22f)
            lineTo(tabWidth, h * 0.22f)
            lineTo(w * 0.34f, h * 0.40f)
            lineTo(w * 0.92f, h * 0.40f)
            lineTo(w * 0.92f, h * 0.86f)
            close()
        }

        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = stroke, join = StrokeJoin.Round, cap = StrokeCap.Round),
        )
    }
}

/**
 * A git commit glyph: a node with a branch, drawn on a canvas for the same
 * reason as the folder icon.
 */
@Composable
fun CommitIcon(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = (minOf(w, h) * 0.09f).coerceAtLeast(1.5f)
        val radius = minOf(w, h) * 0.13f

        // The branch: a vertical line with a short side line.
        drawLine(
            color = tint,
            start = androidx.compose.ui.geometry.Offset(w * 0.30f, h * 0.18f),
            end = androidx.compose.ui.geometry.Offset(w * 0.30f, h * 0.82f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = androidx.compose.ui.geometry.Offset(w * 0.30f, h * 0.52f),
            end = androidx.compose.ui.geometry.Offset(w * 0.74f, h * 0.52f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )

        // The three nodes of the commit.
        listOf(
            androidx.compose.ui.geometry.Offset(w * 0.30f, h * 0.18f),
            androidx.compose.ui.geometry.Offset(w * 0.30f, h * 0.82f),
            androidx.compose.ui.geometry.Offset(w * 0.74f, h * 0.52f),
        ).forEach { center ->
            drawCircle(color = tint, radius = radius, center = center)
        }
    }
}

/**
 * A history glyph: a circle with a clock hand, drawn on a canvas.
 */
@Composable
fun HistoryIcon(modifier: Modifier = Modifier, tint: Color) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = (minOf(w, h) * 0.09f).coerceAtLeast(1.5f)
        val center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.5f)
        val radius = minOf(w, h) * 0.38f

        drawCircle(
            color = tint,
            radius = radius,
            center = center,
            style = Stroke(width = stroke),
        )
        // The hands: one back to twelve, one to the right.
        drawLine(
            color = tint,
            start = center,
            end = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.28f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = center,
            end = androidx.compose.ui.geometry.Offset(w * 0.68f, h * 0.58f),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteRow(
    note: Note,
    folderPath: String?,
    onOpen: () -> Unit,
    onTogglePin: () -> Unit,
    onAction: (NoteAction) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = note.title.ifBlank { "Untitled file" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onTogglePin, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (note.pinned) Icons.Filled.Star else Icons.Outlined.Star,
                        contentDescription = if (note.pinned) "Unpin" else "Pin",
                        tint = if (note.pinned) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(18.dp),
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                        Text(
                            text = "⋮",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = {
                                menuOpen = false
                                onAction(NoteAction.Rename)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Version history") },
                            onClick = {
                                menuOpen = false
                                onAction(NoteAction.History)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Move to…") },
                            onClick = {
                                menuOpen = false
                                onAction(NoteAction.Move)
                            },
                        )
                        DropdownMenuItem(
                            text = {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            },
                            onClick = {
                                menuOpen = false
                                onAction(NoteAction.Delete)
                            },
                        )
                    }
                }
            }

            if (note.preview().isNotEmpty()) {
                Text(
                    text = note.preview(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatTimestamp(note.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (folderPath != null) {
                    Text(
                        text = "  •  $folderPath",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

private fun formatTimestamp(millis: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
