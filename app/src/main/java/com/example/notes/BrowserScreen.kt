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
    /** Exports the project's newest artifact, or the project's own files when it has none. */
    onRowExport: (String) -> Unit = {},
    onRowRename: (String) -> Unit,
    onRowDelete: (String) -> Unit,
    /** Local -> synced. Asks for a licence first, because publishing is a decision. */
    onRowPublish: (String) -> Unit = {},
    /** Synced -> local. Asks first, because the repository stays on GitHub. */
    onRowKeepLocal: (String) -> Unit = {},
    onCreateFile: () -> Unit,
    onCreateFolder: () -> Unit,
    onUploadFiles: () -> Unit,
    onUploadFolder: () -> Unit,
    onRestoreDeleted: () -> Unit,
    onMoveSelected: () -> Unit,
    onCopySelected: () -> Unit,
    onDeleteSelected: () -> Unit,
    onReport: () -> Unit = {},
    /**
     * Opens the cost table.
     *
     * **In the projects screen's bar, and only there.** The question it answers — "what has the
     * model cost me" — is about the whole account rather than one folder, so it belongs where the
     * account's own list is and not inside a project where the answer would be a subset.
     */
    onOpenStats: () -> Unit = {},
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
                    if (projects) {
                        // **Only at the account root, where the bar is about the account.**
                        // Inside a project the same button would answer a narrower question
                        // than the one it asks, which is worse than not offering it.
                        // **A drawn chart, not `Info`.** `Info` said "about this app"; the screen
                        // says what this account has spent. `QueryStats` and `BarChart` are in
                        // material-icons-extended, which this app does not carry for one glyph, so
                        // the icon is drawn - the same answer `ReportButton` and `ChatButton` use.
                        StatsButton(onClick = { adding = false; onOpenStats() })
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

                // **One line, and only when a key on the phone is missing from CI.** The person has
                // the material, the build cannot see it, and their next release would be unsigned —
                // which they would otherwise discover from a failed install. The row asks; the
                // press sends. See `AppViewModel.checkSigning`.
                SigningRow(viewModel, onSend = { project -> viewModel.sendSigningKey(project) })

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
                    ProjectList(
                        viewModel, onRowChat, onRowSync, onRowRename, onRowDelete,
                        onRowPublish, onRowKeepLocal, onRowExport,
                    )
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
                        // **A row, not a preview.** Report 29 asked for "a list of changed files
                        // that I can enter": what a person wants from the list is the shape of the
                        // change — which file, how much of it — and the diff itself is one tap
                        // away in the editor, where it has the room and the commit picker.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.openChangedFile(file.path) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    file.path,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                // **The count of changed lines, which is the one fact that says
                                // how big the change is** without making a person read it. A file
                                // with no preview (a picture, a file too large) says why instead.
                                val lines = file.lines
                                Text(
                                    text = if (lines == null) {
                                        file.said ?: "no preview"
                                    } else {
                                        val added = lines.count { it.kind == GitService.DiffLine.Kind.ADDED }
                                        val removed = lines.count { it.kind == GitService.DiffLine.Kind.REMOVED }
                                        "+$added −$removed"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Text(
                                "›",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
internal fun DiffPreview(lines: List<GitService.DiffLine>) {
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
            // **The count is on the button, not beside it.** There used to be a caption reading
            // "1 to send" and a button reading "See changes" — two things saying one thing, and the
            // caption was the half a person could not press. Report 30 asked for them to be one:
            // "see <n> changes" *is* a button. With nothing to send there is no count and no button,
            // because there is no answer to give.
            if (onSeeChanges != null && changes > 0) {
                TextButton(
                    onClick = onSeeChanges,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                ) { Text(if (changes == 1) "See 1 change" else "See $changes changes") }
            } else if (changes == 0) {
                // **"up to date" stays**, because it is the answer to a question a person asks by
                // looking at the header — and it is not a button, because there is nothing behind it.
                Text(
                    text = "up to date",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
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

/**
 * The signing-key row: one sentence and one button, or nothing at all.
 *
 * **It draws from the view model's state rather than holding its own**, for the same reason every
 * other window in this screen does: the answer arrives on the worker after the screen was built, and
 * a value Compose cannot see does not redraw.
 */
@Composable
private fun SigningRow(viewModel: AppViewModel, onSend: (String) -> Unit) {
    val said = viewModel.signingSaid ?: return
    val project = viewModel.path.firstOrNull() ?: return
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = said,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onSend(project) }) { Text("Send") }
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
    /** Local -> synced: give it a repository, which asks for a licence first. */
    onPublish: (String) -> Unit,
    /** Synced -> local: take the remote away, keeping every version here. */
    onKeepLocal: (String) -> Unit,
    /** Puts a file of the project into Downloads, through the same function the agent's tool uses. */
    onExport: (String) -> Unit,
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
                            // **The two directions of one cycle, and only the one that applies.**
                            // A local project has no remote, so Sync could only say no - the rule a
                            // locked file's Rename already follows - and what it CAN do is get one.
                            // A synced project is offered the way back. Report: "local projects
                            // should have add to github option ... make sure that the state change
                            // cycle (local<>synced) is complete and without errors".
                            if (viewModel.isLocalProject(name)) {
                                DropdownMenuItem(
                                    text = { Text("Put on GitHub…") },
                                    onClick = {
                                        menu = false
                                        onPublish(name)
                                    },
                                )
                            } else {
                                DropdownMenuItem(
                                    text = { Text("Sync") },
                                    onClick = {
                                        menu = false
                                        onSync(name)
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Keep local only…") },
                                    onClick = {
                                        menu = false
                                        onKeepLocal(name)
                                    },
                                )
                            }
                            // **The same function the agent's `downloads` tool calls.** Two
                            // implementations of "copy this to Downloads" drift in the ways that are
                            // hardest to notice, and a person and an agent exporting the same file
                            // should find them identical.
                            DropdownMenuItem(
                                text = { Text("Export to Downloads") },
                                onClick = {
                                    menu = false
                                    onExport(name)
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
    // **Read here, outside the item lambda, so the list redraws when it changes.** A row that asked
    // the file on every draw did not: the lock was written, the answer changed, and Compose had
    // nothing observable to recompose for.
    val lockedPaths = viewModel.lockedPaths
    // **The unsync set is read here for the same reason.** Both marks are answers about state the
    // view model holds; reading one from state and the other from the file was the whole bug.
    val unsyncedPaths = viewModel.unsyncedPaths
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
                // **One answer, from state, not from the file.** `Workspace.hold` is the single
                // place that says who is holding a row - the person, the app, or nobody - so this
                // row has one branch instead of one per mechanism.
                //
                // `lockedPaths` is passed in rather than read: asking `.muffy/locked.json` on every
                // call meant Compose had no reason to redraw the row when the lock changed, so a
                // person had to leave the screen and come back. Reported from the live log: "lock
                // button changes show only after reentering the screen".
                //
                // A file inside a locked folder is read-only but nobody locked *it*, and a folder of
                // twenty lock icons would read as twenty things the person did - `hold` keeps that
                // rule because it asks about this path only.
                hold = ws?.hold(entry.file, lockedPaths) ?: Workspace.Hold.NONE,
                // **From state, like the lock beside it.** `ws.unsync(file)` re-read the flags
                // file on every call, which Compose cannot see - so a tap that changed the file
                // changed nothing the row was watching and the icon waited for some other redraw.
                // Reported twice: "sync connect icon doesnt update visually after tapping on it".
                unsync = ws?.unsync(entry.file, unsyncedPaths) ?: Workspace.Unsync.NONE,
                selected = viewModel.isSelected(entry.file),
                choosing = viewModel.selected.isNotEmpty(),
                // A hidden folder and the record's `agent/` are the app's
                // own folders: they can be opened, and nothing else. Renaming,
                // moving or deleting one is how the account's tree stops being the
                // tree the app thinks it is.
                // **A different question from a lock, and still its own parameter.** `system` is
                // "the app owns this row", which already means no menu and no long-press. A held row
                // is *shown*, can be opened and read, and carries a lock the person did not set.
                // Conflating them would either hide the row again (what report 28 asked to stop) or
                // offer a toggle that does nothing.
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
    selected: Boolean,
    choosing: Boolean,
    system: Boolean,
    /**
     * Who is holding this row, from [Workspace.hold].
     *
     * **One parameter where there were two.** `locked` and `systemLocked` were the person's lock and
     * the app's, drawn by separate branches in this function - which meant every rule about locks
     * had to be written twice and could disagree with itself. A `Hold` is the single answer, and the
     * branch below is the single place that decides what each answer looks like.
     */
    hold: Workspace.Hold = Workspace.Hold.NONE,
    /**
     * Who is keeping this row out of the repository, from [Workspace.unsync].
     *
     * **It had no state at all before this.** The button was drawn the same way whatever the truth
     * was, so a file that was already ignored still offered "Stop sending", and one the app keeps
     * out offered a tap that could not work. The operator's rule - grey unset, black set by the
     * person, red set by the app - is what makes the three cases visible.
     */
    unsync: Workspace.Unsync = Workspace.Unsync.NONE,
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
        if (hold == Workspace.Hold.APP) {
            // **A mark, not a toggle.** The app set this one, and the operator's rule is that what
            // the app sets cannot be unset - so it is drawn in red and it is not clickable. A toggle
            // that refuses to toggle teaches people that buttons in this list sometimes do nothing.
            Icon(
                Icons.Filled.Lock,
                contentDescription = "${entry.name} is kept by the app and cannot be edited",
                tint = APP_HOLD,
                modifier = Modifier.padding(horizontal = 12.dp).size(20.dp),
            )
            // **The second mark, and it was missing.** The operator, looking at `agent/`:
            // "it shows, but why only red lock and not also red disconnect?"
            //
            // The disconnect mark lived *inside* the text-file branch below, so the one row where
            // both states are set by the app - `agent/` and the two logs in it - drew the lock and
            // then left the row. Two facts, one of them invisible: the app keeps the file from being
            // edited *and* keeps it off GitHub, and the person could see only the first.
            //
            // It is a sibling of the lock rather than part of it because they answer different
            // questions, and a row can have one without the other.
            if (unsync == Workspace.Unsync.APP) {
                UnsyncGlyph(
                    tint = APP_HOLD,
                    description = "${entry.name} is kept out of the repository by the app",
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        } else if (!choosing && !system && entry.kind != EntryKind.UNKNOWN) {
            // **A folder carries the marks too, and it is the row where they matter most.**
            // Reported: "why folders made by agents dont have lock and sync icons?" and, earlier,
            // "why logs dont inherit red lock and unsync icons?".
            //
            // This gate was `entry.kind == EntryKind.TEXT`, so the two marks existed only on text
            // files — while `Workspace.hold` and `Workspace.unsync` have always understood a folder
            // path and everything beneath it. The state was being computed for folders and then not
            // drawn, so a person who locked `drafts/` saw the lock on no row at all and could not
            // tell whether the lock had taken.
            //
            // **`UNKNOWN` stays out**, because a binary the app cannot open has no lock to offer in
            // a menu that would not let you edit it anyway.
            //
            // The lock sits to the left of the menu, and only where it can mean
            // something: a file this app would otherwise let you write.
            IconButton(onClick = { actions.onToggleLock(entry) }) {
                // **Two shapes, not one shape in two colours.** The operator's report: "dont use
                // just one icon for two states for lock and sync ... so that different states have
                // not just different colors but icons".
                //
                // Colour alone was the rule here for a while, on the reasoning that grey-off and
                // black-on are a glance. They are not: they fail in sunlight, they fail for a person
                // who cannot tell the hues apart, and on a row that also carries a sync mark they
                // read as two shades of the same statement. A shackle that is lifted is not a shade
                // of a shackle that is shut.
                //
                // The tint stays, because it carries the third state the shape cannot: red is the
                // app's own lock, which cannot be lifted.
                val set = hold == Workspace.Hold.USER
                val tint = if (set) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.outline
                val said = if (set) "${entry.name} is locked; tap to unlock"
                else "Lock ${entry.name}, so it can only be viewed"
                if (set) {
                    Icon(Icons.Filled.Lock, contentDescription = said, tint = tint)
                } else {
                    OpenLockGlyph(tint = tint, description = said)
                }
            }
            // **Ignoring has its own button, beside the lock, because it is that kind of act.**
            // Report 26: it lived in the three-dot menu, which is where the app puts things a person
            // does not often need. Ignoring a file is the opposite - it is what somebody reaches for
            // when they realise a key is in the repository, and a thing you reach for in a hurry
            // should not be behind a menu. It sits with Lock for the same reason: both are "keep
            // this one here".
            // **Three states, the same rule as the lock.** Grey when nothing is set, black when the
            // person set it, red when the app did - and the app's cannot be lifted, so its button is
            // a mark rather than a control. A file the app keeps out of the repository is one the
            // person cannot choose to send, and a tap that silently does nothing is the defect this
            // rule exists to remove.
            // **`APP` cannot reach here.** A row the app holds takes the branch above, which draws
            // both marks and returns; the only states left for a text file are "the person set it"
            // and "nobody did", and both of those are the toggle below.
            IconButton(onClick = { actions.onIgnore(entry) }) {
                // **A broken link, not a cross.** Asked for twice: "unsync row button hasnt been
                // changed to a disconnect icon from its current cross, as I asked". The cross read
                // as "close" or "remove", which is a different act from "this is not being sent",
                // and beside a padlock it was a second ambiguous mark.
                // **A whole chain when it is being sent, a broken one when it is not.** Same rule
                // as the padlock above, and the operator asked for both in one report.
                //
                // The unset state is the *normal* one - most files are sent - so it gets the
                // ordinary mark, and the broken chain is the exception a person is looking for.
                val stopped = unsync == Workspace.Unsync.USER
                val tint = if (stopped) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.outline
                val said = if (stopped) "${entry.name} is not sent to GitHub"
                else "Stop sending ${entry.name} to GitHub"
                if (stopped) {
                    UnsyncGlyph(tint = tint, description = said)
                } else {
                    LinkedGlyph(tint = tint, description = said)
                }
            }
        }
        // **No menu on a file the app keeps, and the menu stays on one the person locked.**
        // The operator's ruling, twice: "3dots are hidden only on system locked branches", and
        // earlier "system locks restrict both users and agents, and user locks restrict agents (not
        // users themselves!)".
        //
        // This gated on `hold == NONE`, so a folder the person had locked hid the menu from the
        // person who locked it - the app taking away a lever from its own operator. Report 10 asked
        // for the menu to go and it was filed while looking at `agent/`, which is an *app* lock, so
        // it never meant the person's.
        //
        // Every item in the menu still works on a person-locked row, because `Workspace` asks
        // `isLocked` (the app's locks) and `isAgentLocked` (both) as different questions - so the
        // menu is not a lie there, and hiding it was.
        if (!choosing && !system && hold != Workspace.Hold.APP) Box {
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
                // **Offered on a person-locked file, because it works.** The person may rename what
                // they locked - their lock is an instruction to the agent, not a cage for them.
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
                // **Copy is offered everywhere it is offered at all**, and it is the one act a lock
                // was never about: reading a file and making another copy changes nothing here.
                DropdownMenuItem(
                    text = { Text("Copy…") },
                    onClick = {
                        menu = false
                        actions.onCopy(entry)
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
    // **A local project has no strip at all**, and the operator was right that hiding only the
    // button was the wrong half: "local projects shoudnt show sync row - hide it for them".
    //
    // Every part of the row is a claim about a remote. `changes` is `unsentPaths`, which returns
    // empty when there is no `origin` — so the count would be zero and the strip would say **"up to
    // date" for ever**, which is a statement about a GitHub repository that does not exist. A row
    // that can only be wrong should not be drawn.
    //
    // **And the strip was the only door to the changes window, which is fine, because that window
    // would be empty.** `openChanges` reads `unsentPaths` too — so for a local project it opens a
    // window with nothing in it and a caption claiming no changes. Nothing is lost by removing the
    // door to a room that cannot have anything in it.
    //
    // **The act that gives a local project all of this back is publishing it.** The ⋮ menu offers
    // "Put on GitHub…", and once there is a remote the strip returns with a real count and a real
    // Sync. That is the right order: the row describes a relationship with a remote, so it exists
    // once there is one.
    if (viewModel.isLocalProject(project)) return
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

/**
 * The colour of a lock the app set.
 *
 * **Red, and not a theme slot**, because the operator's rule is that the three states are told
 * apart by colour: grey when nothing is set, black when the person set it, red when the app did.
 * The first two map onto slots this theme already has (`outline` and `onSurface`); there is no
 * "error" slot that is legible as a *lock* rather than as a failure, so this is stated once here
 * rather than guessed at each call site.
 *
 * Red is also the right word for it: a file the app holds is one you cannot change, and the app
 * does not take that decision lightly - see [Workspace.Hold.APP].
 */
private val APP_HOLD = Color(0xFFB3261E)
