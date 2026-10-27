package com.example.notes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** What the browser asks the app to do, so the screen stays free of state. */
class BrowserActions(
    val onOpen: (Entry) -> Unit,
    val onRename: (Entry) -> Unit,
    val onMove: (Entry) -> Unit,
    val onCopy: (Entry) -> Unit,
    val onDelete: (Entry) -> Unit,
    val onTogglePin: (Entry) -> Unit,
    val onToggleLock: (Entry) -> Unit,
    val onHistory: (Entry) -> Unit,
    val onSelect: (Entry) -> Unit,
    /**
     * Adds this row to the project's `.gitignore`, so it stops being synced.
     *
     * **A row action, because the file is where the thought happens.** Someone looking at
     * `build/` or `secrets.env` in the list is someone who has just decided it should not go to
     * GitHub, and sending them to open `.gitignore` by hand — find it, type the path, spell it
     * right — is how a mistake becomes a commit.
     */
    val onIgnore: (Entry) -> Unit = {},
)

/**
 * The folder the app is showing: the account's root, or any folder inside a project.
 *
 * **The account's root is the Projects screen** (§6): one row per project, each with its unsynced
 * count, a `⋮` menu and the add button. There is no `projects/` level to walk into. Anywhere else
 * it is an ordinary folder listing (§3): what you made, and nothing the app made.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BrowserScreen(
    viewModel: AppViewModel,
    username: String?,
    onOpenSettings: () -> Unit,
    actions: BrowserActions,
    onAddProject: () -> Unit,
    onRowChat: (String) -> Unit,
    onRowSync: (String) -> Unit,
    onRowRename: (String) -> Unit,
    onRowDelete: (String) -> Unit,
    onCreateFile: () -> Unit,
    onCreateFolder: () -> Unit,
    onUploadFiles: () -> Unit,
    onUploadFolder: () -> Unit,
    onRestoreDeleted: () -> Unit,
    onMoveSelected: () -> Unit,
    onCopySelected: () -> Unit,
    onDeleteSelected: () -> Unit,
    onReport: () -> Unit = {},
) {
    val projects = viewModel.atProjects
    val here = viewModel.path
    // **The add menu belongs to the folder, so it closes when the folder does.**
    // Held here rather than inside the button, because the thing that has to close
    // it is a tap somewhere else on the screen.
    var adding by remember(here) { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    // Nothing at the root. The three-lined button that used to be
                    // here opened a window of the account, its switch list and its
                    // buttons — every one of which Settings already has, so it was
                    // two places to keep in step and the wrong one to find first.
                    if (viewModel.canGoUp) {
                        IconButton(onClick = { viewModel.up() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up")
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
                            here.isEmpty() -> "your projects"
                            here.size == 1 -> "project"
                            else -> here.dropLast(1).joinToString(" › ")
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
                    // **First in the row, on every screen, always in the same place.**
                    // The moment a person wants it is the moment something has just gone
                    // wrong, and hunting for it then is the worst possible time.
                    ReportButton(onReport)
                    if (viewModel.currentProject != null) {
                        // **The chat is where the agent is, so it is where the project is.** It
                        // used to be reachable only from the overflow menu of a project's row on
                        // the *list* — so a person already inside the project, looking at its
                        // files, had no way to reach the agent working on them. Reported from the
                        // live log: "where is chat icon within the project?"
                        ChatButton(
                            onClick = { adding = false; viewModel.currentProject?.let(onRowChat) },
                        )
                        // Search is scoped to the project (§7): it exists because a
                        // project's tree nests and what you remember is a name.
                        IconButton(onClick = { viewModel.showSearch = !viewModel.showSearch }) {
                            Icon(Icons.Filled.Search, contentDescription = "Search this project")
                        }
                    }
                    IconButton(onClick = {
                        // An outside action runs *after* the add menu has closed, so
                        // the menu is never left standing over the next screen.
                        adding = false
                        onOpenSettings()
                    }) {
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
                // **The account root *is* the list of projects**, so the button that makes one
                // lives here — there is no `projects/` row to open first.
                FloatingActionButton(onClick = onAddProject) {
                    Icon(Icons.Filled.Add, contentDescription = "New project or pull one in")
                }
            } else {
                // A folder gets the two things a folder gets (§3).
                Column(horizontalAlignment = Alignment.End) {
                    if (adding) {
                        // **The menu has a surface, because it is over the list.** These
                        // were five bare buttons drawn on top of the rows, with nothing
                        // behind them: file names showed between and under them, and a menu
                        // that cannot be read apart from the thing it covers is not a menu.
                        // Reported from the live log: "it will be good if options for +
                        // button will have their backgrounds — currently they overlap with
                        // text from file rows".
                        //
                        // **Hugging the widest label, not the screen.** The first fix put
                        // `fillMaxWidth` on every item, so the panel ran the whole width and
                        // read as a stray band across the list. `IntrinsicSize.Max` makes it
                        // exactly as wide as its widest row plus the little that the padding
                        // adds — and since the column is aligned to the end, it sits against
                        // the add button. Reported from the live log: "background for +
                        // button options is ugly, align to the right and make it the width
                        // just a bit wider than the widest word".
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            tonalElevation = 2.dp,
                            shadowElevation = 4.dp,
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .width(IntrinsicSize.Max)
                                .padding(bottom = 12.dp),
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
                                // Putting something in from Downloads sits beside making
                                // something, because to a person they are the same
                                // question: "how does a file get here?"
                                TextButton(onClick = { adding = false; onUploadFolder() }) {
                                    Text("Upload a folder…")
                                }
                                TextButton(onClick = { adding = false; onUploadFiles() }) {
                                    Text("Upload files…")
                                }
                                TextButton(onClick = { adding = false; onCreateFolder() }) {
                                    Text("New folder")
                                }
                                TextButton(onClick = { adding = false; onCreateFile() }) {
                                    Text("New file")
                                }
                                // The one way back for a deleted file: its row is gone,
                                // so there is nothing left to open a history on.
                                TextButton(onClick = { adding = false; onRestoreDeleted() }) {
                                    Text("Restore deleted…")
                                }
                            }
                        }
                    }
                    FloatingActionButton(onClick = { adding = !adding }) {
                        Icon(
                            Icons.Filled.Add,
                            contentDescription = if (adding) "Close the add menu" else "Add",
                        )
                    }
                }
            }
        },
    ) { padding ->
        // **Drawn from the view model's state, not from this screen's.** The editor covers the
        // browser, and a window whose state lived here would be gone when it came back.
        ChangesWindow(viewModel)
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(modifier = Modifier.fillMaxSize()) {
                ProjectSlimRow(viewModel, onRowSync)

                // What can be done with what is chosen. It appears only when
                // something is chosen, because it is a mode: a long press on a row
                // enters it, and the cross leaves it.
                if (viewModel.selected.isNotEmpty()) {
                    SelectionBar(
                        count = viewModel.selected.size,
                        onMove = onMoveSelected,
                        onCopy = onCopySelected,
                        onDelete = onDeleteSelected,
                        onClear = { viewModel.clearSelection() },
                    )
                }

                if (viewModel.showSearch) {
                    OutlinedTextField(
                        value = viewModel.query,
                        onValueChange = { viewModel.query = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        placeholder = { Text("Search in ${viewModel.currentProject.orEmpty()}") },
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
                    ProjectList(viewModel, onRowChat, onRowSync, onRowRename, onRowDelete)
                } else {
                    EntryList(viewModel, actions)
                }
            }
            // **The strip above the listing: how many agents are on, and how many need you.**
            // The design asks for it as a permanent bar, and it is the same state the
            // notification reads — once inside the app, once outside it, so the two cannot
            // disagree. Silent when no agent exists, because a strip saying "0 sessions" is a
            // row of the screen spent on nothing.
            val strip = AgentSession.strip(viewModel.agentSessions)
            if (strip.isNotEmpty()) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Text(
                        strip,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                }
            }

            // **A tap outside the add menu closes it and does nothing else.** Without
            // this the menu stayed open while the tap went through to whatever was under
            // it — a row opened, a folder was entered, and the menu was still there over
            // the next screen, so the outside action happened *and* the menu had to be
            // dismissed afterwards. An invisible pane over everything but the button: the
            // first tap belongs to the menu, the second does what it looks like it does.
            if (adding) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { adding = false },
                )
            }
        }
    }
}

/**
 * Account, project, the unsynced count and the sync button, in one slim row.
 *
 * Public because §7 says **every** screen while a project is open carries it. It is
 * one function rather than one per screen so the row cannot drift apart — the
 * changed count on the editor and the count in the browser are the same number
 * from the same call.
 */
/**
 * What the last push does not have, file by file — **the answer to "1 to send".**
 *
 * Three shapes in one list: a path, a preview of how it differs, and a touch that opens it in the
 * editor. Only text files get a preview — a picture's bytes rendered as lines are convincing
 * nonsense — and the reason is said beside the name instead.
 *
 * **The window belongs to the view model, not to this composable**, which is what makes the round
 * trip work: a person touches a file, the editor covers the screen, and closing the editor puts the
 * browser back with the window exactly as it was.
 */
@Composable
fun ChangesWindow(viewModel: AppViewModel) {
    val files = viewModel.changes ?: return
    AlertDialog(
        onDismissRequest = { viewModel.closeChanges() },
        title = { Text("Changes to send") },
        text = {
            if (files.isEmpty()) {
                Text(
                    "Nothing is waiting to be sent. Everything on this phone is on GitHub.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 460.dp)) {
                    items(files) { file ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.openChangedFile(file.path) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                        ) {
                            Text(
                                file.path,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val lines = file.lines
                            if (lines == null) {
                                Text(
                                    file.said ?: "no preview",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                DiffPreview(lines)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.closeChanges() }) { Text("Close") }
        },
    )
}

/**
 * A file's lines, coloured by what happened to them.
 *
 * **Additions are dark green, removals are the theme's error red, and everything else is the theme's
 * own text colour** — the operator's words. Unchanged lines are most of the answer: without them a
 * change has no place to be in.
 *
 * Past [CAP] lines the preview stops and says how many it did not show, because a screen is not a
 * place to read forty thousand lines and a window that tried would take a second to open.
 */
@Composable
private fun DiffPreview(lines: List<GitService.DiffLine>) {
    // **A dark theme needs a lighter green for the same job.** A dark green on a dark surface is the
    // one colour that reads as "nothing here", and this is the one line whose whole meaning is
    // "something was added".
    val dark = MaterialTheme.colorScheme.onSurface.luminance() > 0.5f
    val added = if (dark) Color(0xFF7FD68A) else Color(0xFF1B5E20)
    val removed = MaterialTheme.colorScheme.error
    val shown = lines.take(CAP)
    Column {
        shown.forEach { line ->
            Text(
                text = when (line.kind) {
                    GitService.DiffLine.Kind.ADDED -> "+ ${line.text}"
                    GitService.DiffLine.Kind.REMOVED -> "- ${line.text}"
                    GitService.DiffLine.Kind.SAME -> "  ${line.text}"
                },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = when (line.kind) {
                    GitService.DiffLine.Kind.ADDED -> added
                    GitService.DiffLine.Kind.REMOVED -> removed
                    GitService.DiffLine.Kind.SAME -> MaterialTheme.colorScheme.onSurface
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (lines.size > shown.size) {
            Text(
                "… and ${lines.size - shown.size} more lines. Touch the file to read it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** How much of one file's diff the window draws before it stops. */
private const val CAP = 60

@Composable
fun SlimRow(
    account: String,
    project: String,
    changes: Int,
    busy: Boolean,
    onSync: () -> Unit,
    /** Offered only where a screen can open the window: the browser, which is the project. */
    onSeeChanges: (() -> Unit)? = null,
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
                // "to send", not "changed": a saved file is committed at once and is only
                // waiting for the push, so calling it "changed" described git's state
                // rather than the person's.
                text = if (changes > 0) "$changes to send" else "up to date",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            // **To the left of Sync, and only when there is something to see.** "1 to send" is the
            // question this button answers; with nothing to send there is no answer and no button.
            if (onSeeChanges != null && changes > 0) {
                TextButton(
                    onClick = onSeeChanges,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) { Text("See changes") }
            }
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else if (changes > 0) {
                // **A button that would do nothing says so.** Sync with nothing changed is a
                // request with no answer, and a person pressing it cannot tell "already up to
                // date" from "it did not work".
                //
                // **And no number on it.** The count was on the button for a while, and it
                // read as a second, competing number beside the one in the row. Reported from
                // the live log: "and why sync button itself started to show a number? remove
                // it". The row already says how many; the button says what pressing it does.
                Button(
                    onClick = onSync,
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                ) { Text("Sync") }
            } else {
                TextButton(onClick = onSync, enabled = false) { Text("Sync") }
            }
        }
    }
}

/** The Projects screen: a row each, with the count and the ⋮ menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectList(
    viewModel: AppViewModel,
    onChat: (String) -> Unit,
    onSync: (String) -> Unit,
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    val names = viewModel.projectNames
    if (names.isEmpty()) {
        EmptyState(
            title = "No projects yet",
            text = "The add button makes one on GitHub, brings in a repository you already " +
                "have, or makes one that stays on this phone."
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
                            .combinedClickable(onClick = { viewModel.open(listOf(name)) }),
                    ) {
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                        // **A local project has no second line.** The row exists to answer "how
                        // much is unsent", and a project with no remote has no answer to give —
                        // "local · this phone only" said the absence twice and made the row look
                        // broken beside its neighbours. Reported from the live log: "still have
                        // "local" word under filenames, should we really keep it?" — no.
                        if (!viewModel.isLocalProject(name)) {
                            Text(
                                // The same words as the row inside the project: what GitHub does
                                // not have yet, which is the only reason a person opens this screen.
                                text = viewModel.changesOf(name).let {
                                    if (it > 0) "$it to send" else "up to date"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            // **The chat is a project's, not a file's.** It is the first thing
                            // in this menu that is not about the repository's contents, and it
                            // is on top because it is what a person comes here for once the
                            // agent is on.
                            DropdownMenuItem(
                                text = { Text("Chat") },
                                onClick = {
                                    menu = false
                                    onChat(name)
                                },
                            )
                            // **Not offered on a local project.** It has no remote, so the item
                            // could only say no — the rule a locked file's Rename already follows.
                            if (!viewModel.isLocalProject(name)) {
                                DropdownMenuItem(
                                    text = { Text("Sync") },
                                    onClick = {
                                        menu = false
                                        onSync(name)
                                    },
                                )
                            }
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
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            // Choosing is a mode, and a tap on the space between rows leaves it —
            // the same way a long press entered it. Tapping a *row* while choosing
            // still toggles that row.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { if (viewModel.selected.isNotEmpty()) viewModel.clearSelection() },
    ) {
        items(entries) { entry ->
            val ws = viewModel.workspace
            val path = ws?.relative(entry.file)?.let { ws.pathString(it) }
            val pinned = path != null && path in pinnedPaths
            EntryRow(
                entry = entry,
                pinned = pinned,
                // **The switch, read from state, not from the file.** `isLockedHere` asks
                // `.muffy/locked.json` every time it is called, so the row's icon did not change
                // when the lock changed — Compose had no reason to redraw it, and a person had to
                // leave the screen and come back. Reported from the live log: "lock button changes
                // show only after reentering the screen". `lockedPaths` is the same answer, held in
                // state, so the row redraws the moment the set changes.
                //
                // A file inside a locked folder is read-only but nobody locked *it*, and a folder
                // of twenty lock icons would read as twenty things the person did — hence
                // `isLockedHere`'s own rule rather than `isLocked`'s.
                locked = path != null && path in lockedPaths,
                selected = viewModel.isSelected(entry.file),
                choosing = viewModel.selected.isNotEmpty(),
                // A hidden folder and the record's `agent/` are the app's
                // own folders: they can be opened, and nothing else. Renaming,
                // moving or deleting one is how the account's tree stops being the
                // tree the app thinks it is.
                system = ws?.isStructural(entry.file) == true,
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
    locked: Boolean,
    selected: Boolean,
    choosing: Boolean,
    system: Boolean,
    actions: BrowserActions,
    onOpen: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val background = if (selected) MaterialTheme.colorScheme.secondaryContainer
    else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            // **A long press chooses; it does not open a menu.** A menu is a list of
            // things to do to one row; the thing people want with several rows is to
            // do the same thing to all of them, which needs a way to mark them that
            // does not cover the rows with a window.
            .combinedClickable(
                onClick = { if (choosing) actions.onSelect(entry) else onOpen() },
                onLongClick = { if (!system) actions.onSelect(entry) },
            )
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (choosing) {
            Checkbox(checked = selected, onCheckedChange = { actions.onSelect(entry) })
            Spacer(Modifier.width(4.dp))
        }
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
                // A trailing slash is the whole of "this is a folder": the name and
                // its extension already say what kind of file a file is, so the line
                // that used to spell it out underneath was saying it twice.
                text = entry.name + if (entry.isFolder) "/" else "",
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!choosing && !system && entry.kind == EntryKind.TEXT) {
            // The lock sits to the left of the menu, and only where it can mean
            // something: a file this app would otherwise let you write.
            IconButton(onClick = { actions.onToggleLock(entry) }) {
                // One glyph, two states, told apart by colour and by what a screen
                // reader says. The open padlock lives in the extended icon set, and
                // one icon is not worth a whole dependency.
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = if (locked) {
                        "${entry.name} is locked; tap to unlock"
                    } else {
                        "Lock ${entry.name}, so it can only be viewed"
                    },
                    tint = if (locked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline,
                )
            }
        }
        if (!choosing && !system) Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                // **Two things, because they are the two that need a menu.** Lock has its
                // own button on the row and History has one in the editor, so listing them
                // here made the menu a set of shortcuts to what is already on screen. Pin
                // is here because nothing else carries it; Move because it asks a question
                // a tap cannot.
                DropdownMenuItem(
                    text = { Text(if (pinned) "Unpin" else "Pin") },
                    onClick = {
                        menu = false
                        actions.onTogglePin(entry)
                    },
                )
                // **Not offered on a locked file.** Rename would be refused, so the item
                // could only disappoint — the same rule Delete already followed. The model
                // refuses it either way; hiding it is not the refusal.
                if (!locked) {
                    DropdownMenuItem(
                        text = { Text("Rename…") },
                        onClick = {
                            menu = false
                            actions.onRename(entry)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text("Move…") },
                    onClick = {
                        menu = false
                        actions.onMove(entry)
                    },
                )
                // **Copy stays on a locked file, and that is what a lock means.** It
                // says "do not change this"; reading it and making another copy
                // elsewhere changes nothing here, so refusing it would stretch the lock
                // over an act it was never about.
                DropdownMenuItem(
                    text = { Text("Copy…") },
                    onClick = {
                        menu = false
                        actions.onCopy(entry)
                    },
                )
                // **A folder too.** Ignoring a folder ignores everything below it, which is what a
                // person means when they say "not this one" about a build directory.
                DropdownMenuItem(
                    text = { Text("Add to .gitignore") },
                    onClick = {
                        menu = false
                        actions.onIgnore(entry)
                    },
                )
                if (!locked) {
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
}

/**
 * What is chosen, and what can be done with it.
 *
 * The buttons are words rather than icons because there are two of them and they
 * are dangerous ones: "Delete" on four rows at once is not a thing to make a
 * person guess at from a shape.
 */
@Composable
private fun SelectionBar(
    count: Int,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (count == 1) "1 chosen" else "$count chosen",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onMove) { Text("Move…") }
            TextButton(onClick = onCopy) { Text("Copy…") }
            TextButton(onClick = onDelete) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
            IconButton(onClick = onClear) {
                Icon(Icons.Filled.Clear, contentDescription = "Stop choosing")
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

/**
 * The slim row for a screen that is inside a project (§7).
 *
 * Reads the account, the project and the count from the ViewModel rather than
 * taking them as arguments, so the editor and the history screen show the same
 * numbers as the browser without passing three values through every call — and so
 * there is exactly one place that can be wrong.
 */
@Composable
fun ProjectSlimRow(viewModel: AppViewModel, onSync: (String) -> Unit) {
    // **Only inside the repo.** The row carries Sync, and Sync sends what is in
    // the worktree — offering it one level above, where nothing of the project is
    // on screen, raises a question the screen cannot answer.
    if (!viewModel.inWorktree) return
    val project = viewModel.currentProject ?: return
    SlimRow(
        account = viewModel.account?.login ?: "",
        project = project,
        changes = viewModel.changesOf(project),
        busy = viewModel.busy,
        onSync = { onSync(project) },
        onSeeChanges = { viewModel.openChanges(project) },
    )
    HorizontalDivider()
}
