package com.example.notes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.selection.SelectionContainer
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
import java.io.File

/** What the browser asks the app to do, so the screen stays free of state. */
class BrowserActions(
    val onOpen: (Entry) -> Unit,
    val onRename: (Entry) -> Unit,
    val onMove: (Entry) -> Unit,
    val onCopy: (Entry) -> Unit,
    /**
     * **The same act as [onCopy], under the name the row gives it.**
     *
     * A file the app keeps offers "Clone…" rather than "Copy…", because what the person is doing is
     * taking a copy *out* of a read-only place — and the trace should say what the button said. It
     * defaults to [onCopy] so a caller that does not care about the distinction needs no change.
     */
    val onClone: (Entry) -> Unit = onCopy,
    /**
     * **Copy this file out to the phone's Downloads** — report 23: *"I should be able to clone/move
     * to the downloads - add this as a choice"*.
     *
     * It is a *copy*, and named as one, because nothing is removed from the project. It sits beside
     * Rename and Copy rather than beside Export, which is the project's own menu item and means "the
     * newest CI artifact"; this one is about the file the person is looking at.
     *
     * **Offered on an app-held row too**, because a copy is a read: the lock is about changing what
     * is here, and taking a copy out changes nothing — the same reasoning [onClone] records.
     */
    val onDownload: (Entry) -> Unit = {},
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
 * **The account's root is the Projects screen** (§6): one row per project, each with its unsent
 * count, a `⋮` menu and the add button. There is no `projects/` level to walk into. Anywhere else
 * it is an ordinary folder listing (§3): what you made, and nothing the app made.
 */
/**
 * **The branches of a project** (§13d).
 *
 * The operator's shape: *"when you tap a project you are shown branch screen where you need to select a
 * branch (and main branch exists by default even for newly created projects)."*
 *
 * **`main` is always there**, because `ProjectGit.init` sets `setInitialBranch("main")` — so a project
 * made a second ago has one row and there is no empty state to design. That also means the screen never
 * has to say "make your first branch", which would be a screen a person only ever sees once and which
 * every test would have to fake.
 *
 * **A branch is a place an agent can work without touching anybody else's work.** That is why this is
 * a screen rather than a dropdown: entering one is choosing what the agent may see, and a control that
 * changes what an agent can do should be a row you pressed, not a value that drifted.
 */
@Composable
fun BranchesScreen(
    project: String,
    branches: List<String>,
    /** The branch with work not on `main`, so the row can say so before it is entered. */
    unmerged: Set<String> = emptySet(),
    onEnter: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onBack: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("‹ Projects")
            }
            Text(
                project,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { Trace.tap("add branch"); onAdd() }) { Text("+ Branch") }
        }
        HorizontalDivider()
        Text(
            "Each branch is its own working copy, so an agent in one cannot disturb another. " +
                "main is what a project is made of; every other branch merges into it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            for (branch in branches) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            Trace.tap("enter branch", branch)
                            onEnter(branch)
                        }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(branch, style = MaterialTheme.typography.bodyLarge)
                        if (branch in unmerged) {
                            // **Said before the row is entered**, because it is the fact that decides
                            // whether the work can be merged and whether deleting the branch would
                            // lose anything.
                            Text(
                                "has work that is not on main",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // **main cannot be removed**, because it is the branch a project is made of —
                    // and offering a button that always refuses is worse than not offering it.
                    if (branch != "main") {
                        TextButton(onClick = { onRemove(branch) }) { Text("Remove") }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

/**
 * **The main menu: the accounts on this phone** (§13c.2).
 *
 * The operator's shape: *"main menu is about projects listed on the app, not just one account. There
 * should be no selected account, but a list of added accounts... user can simply enter or exit folders
 * of different accounts and there see their projects... and the + button on accounts screen allows
 * adding and removing accounts."*
 *
 * **Why a list rather than one flat list of every project:** the agent runs one account at a time
 * (`AgentRunner` holds one `Workspace`), and a flat list would need N runners and N WebViews. Entering
 * an account keeps that true while still letting a person move between them freely — the thing that
 * was actually missing was navigation, not concurrency.
 *
 * **Each row says how many projects it holds**, read from the folder rather than stored, so the number
 * cannot disagree with what entering it shows.
 */
/**
 * How many projects an account row says it has.
 *
 * **A function rather than an expression inside the row**, because it is a sentence a person reads and
 * the only way to test it without a painted screen is to call it. The alternative — asserting "1
 * project" in a Compose test — is ambiguous the moment the phone holds two accounts, and the
 * ambiguity is about the *number of rows*, not about the sentence.
 *
 * **Zero is reachable**: an account whose only project was deleted says "0 projects", which is honest,
 * and it is not the same as a row that failed to count.
 */
fun projectCountText(count: Int): String = when (count) {
    1 -> "1 project"
    else -> "$count projects"
}

@Composable
fun AccountsScreen(
    viewModel: AppViewModel,
    onEnter: (String) -> Unit,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val accounts = viewModel.accountsWithProjects
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Accounts",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            // **The `+` lives here, not in Settings.** The operator moved it: accounts are what this
            // screen is about, so adding one belongs on it, and Settings keeps only what applies to
            // the app as a whole.
            TextButton(onClick = { Trace.tap("add account"); onAdd() }) { Text("+ Add") }
            TextButton(onClick = onSettings) { Text("Settings") }
        }
        HorizontalDivider()
        if (accounts.isEmpty()) {
            EmptyState(
                title = "No accounts yet",
                text = "An account is a name, a host and a key. Its projects live in a folder of " +
                    "its own, and the agent works in one account at a time — so you can move " +
                    "between them freely without their agents interfering.",
            )
        } else {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                for ((account, projects) in accounts) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                Trace.tap("enter account")
                                onEnter(account.login)
                            }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(account.login, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                projectCountText(projects),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        // **Removal is offered, not hidden behind a long press.** A row a person can
                        // add to but never remove from is a row they work around by reinstalling.
                        TextButton(onClick = { onRemove(account.login) }) { Text("Remove") }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BrowserScreen(
    viewModel: AppViewModel,
    username: String?,
    onOpenSettings: () -> Unit,
    actions: BrowserActions,
    onAddProject: () -> Unit,
    onRowChat: (String) -> Unit,
    /** Exports the project's newest artifact, or the project's own files when it has none. */
    /**
     * Opens the window that publishes a release of [project] (§13b.7).
     *
     * **A parameter because the window is the app's.** Every other menu item here already reaches the
     * app through one of these callbacks; the release button on the strip is the same act as the menu
     * items beside it, so it takes the same road rather than reaching into a dialog of its own.
     */
    onRelease: (String) -> Unit = {},
    onRowRename: (String) -> Unit,
    onRowDelete: (String) -> Unit,
    /** Synced -> local. Asks first, because the repository stays where it is. */
    onRowKeepLocal: (String) -> Unit = {},
    onCreateFile: () -> Unit,
    onCreateFolder: () -> Unit,
    /** A blank picture, which needs a size as well as a name. */
    onCreateImage: () -> Unit,
    onUploadFiles: () -> Unit,
    onUploadFolder: () -> Unit,
    onRestoreDeleted: () -> Unit,
    onMoveSelected: () -> Unit,
    onCopySelected: () -> Unit,
    onDeleteSelected: () -> Unit,
    onReport: () -> Unit = {},
    /** Opens the picker for the open project's type. One word, in the bar, tap to change. */
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
                        // **The second line is where you are**, because there is no longer a
                        // project type to name. It used to be the type, which was a control as well
                        // as a fact — and with the types gone the honest second line is the path,
                        // which is the thing a person is inside of and can always be shown.
                        // **The branch is named when there is one**, because two trees of one
                        // project hold different files and the header said only the project's name —
                        // so a person editing `feature`'s copy had nothing on screen telling them so.
                        // It goes on the second line with the path rather than in the title, because
                        // the title is the folder's name and a branch is not a folder.
                        val small = when {
                            here.isEmpty() -> "your projects"
                            viewModel.activeBranch != AgentSession.MAIN ->
                                (here.dropLast(1) + "on " + viewModel.activeBranch)
                                    .filter { it.isNotEmpty() }
                                    .joinToString(" › ")
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
                            // **The branch the person is in**, which is a different conversation from
                            // the project's own (§13d) — see [AppViewModel.openChatHere].
                            onClick = { adding = false; viewModel.currentProject?.let(viewModel::openChatHere) },
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
            } else if (Workspace.isSystemPath(viewModel.path.joinToString("/"))) {
                // **Nothing can be added to the system project**, so the button is not offered.
                // The operator: "+ button should be hidden in system project." It is the app's own
                // folder — a person reads it and clones from it, and every write there comes from
                // the app. A button whose only possible outcome is a refusal is worse than no
                // button: it teaches the person the app is broken rather than that the folder is
                // the app's.
                Spacer(Modifier.size(0.dp))
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
                                // **A picture is a file, so it is made here** rather than in a
                                // separate place: to a person "how does a file get here?" has the same
                                // answer whichever kind it is, and the size is the only difference.
                                TextButton(onClick = { adding = false; onCreateImage() }) {
                                    Text("New image")
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
                // **The release door, one level up.** The row draws the button when there is nothing
                // unsent; what the button opens is the app's window, which the screen does not own.
                ProjectSlimRow(
                    viewModel,
                    // **Offered only where a release can honestly be named** — see
                    // `AppViewModel.canPublish`, which holds the rule rather than this call site.
                    onRelease = viewModel.currentProject
                        ?.takeIf { viewModel.canPublish }
                        ?.let { project -> { onRelease(project) } },
                )

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
                    // **Above the list, and inside this branch rather than inside [ProjectList]** -
                    // that function returns early when there are no projects, so a card placed
                    // there would vanish for exactly the person who has just started and has the
                    // most to learn about what the agent costs.
                    HeartbeatCard(viewModel.heartbeat)
                    ProjectList(
                        viewModel, onRowChat, onRowRename, onRowDelete,
                        onRowKeepLocal,
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
 * Account, project and the count of unsent work, in one slim row.
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
                    "Nothing is waiting to be sent. Everything here is at the project's address.",

                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 460.dp)) {
                    items(files) { file ->
                        // **A row, not a preview.** Report 29 asked for "a list of changed files
                        // that I can enter": what a person wants from the list is the shape of the
                        // change — which file, how much of it — and the diff itself is one tap
                        // away in the editor, where it has the room and the commit picker.
                        //
                        // **The tap opens the file in Diff, not in View.** The operator, 2026-11-04:
                        // "files from this list when clicked should open in diff mode." A person
                        // who tapped a row of a list called "Changes to send" has already said what
                        // they want to see, and the pair it starts on is HEAD against the last
                        // commit GitHub has — the same set of changes the count beside the row is
                        // counting.
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
    /** Offered only where a screen can open the window: the browser, which is the project. */
    onSeeChanges: (() -> Unit)? = null,
    /**
     * Offered when there is nothing left to send, because **that is the moment a release makes
     * sense**: a version published from a tree with unsent work would name a commit that is not
     * what is on GitHub.
     *
     * The operator chose a button here rather than a menu item, on the reasoning that a person
     * looking at "up to date" is exactly the person about to publish.
     */
    onRelease: (() -> Unit)? = null,
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
                // **And "Publish…" sits beside it**, because "nothing unsent" is the state in which a
                // release is safe to make: a version published from a tree with unsent work would
                // name a commit GitHub does not have. This is where Sync used to be, and a release is
                // a different question from a sync — sync asks "send what is here", and a release
                // names a version, which only a person decides.
                if (onRelease != null) {
                    TextButton(
                        onClick = onRelease,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) { Text("Publish…") }
                }
            }
            // **No Sync button, and no button where it was.** The operator moved the decision to
            // the agent: "we can remove this functionality and transfer it to agent so that it can
            // determine when to push". A person pressing Sync would be a second writer deciding the
            // same question, and the two could disagree about which commit is being sent.
            //
            // **The count stays**, because it is information rather than an action: it is how a
            // person learns that work exists which GitHub does not have. What it cannot do is say
            // when the agent will send it — nothing can, which is the trade the operator chose.
            if (busy) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
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
    onRename: (String) -> Unit,
    onDelete: (String) -> Unit,
    /** Synced -> local: take the remote away, keeping every version here. */
    onKeepLocal: (String) -> Unit,
    /** Puts a file of the project into Downloads, through the same function the agent's tool uses. */
) {
    val names = viewModel.projectNames
    // **The system folder is a row here, and it is not a project** (§13f). It lives at the account root,
    // so a person must be able to reach it from the root; but it has no repository, no remote and no
    // menu, and calling it a project is exactly the "dangling row" the operator reported. So it is drawn
    // last, as a folder, and tapping it opens the folder.
    val system = viewModel.workspace?.let { File(it.accountDir, Workspace.SYSTEM_PROJECT) }
        ?.takeIf { it.isDirectory }
    if (names.isEmpty() && system == null) {
        EmptyState(
            title = "No projects yet",
            text = "The add button brings in a repository from any address that speaks git, " +
                "or makes one that stays on this phone."
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // **The explanation survives the system row.** It used to be the whole screen or nothing,
        // because the list was the projects; now a new account has a row in it — the app's own folder —
        // so "empty" would be a screen showing one row and saying nothing about why. The words come
        // first, above the row they are about.
        if (names.isEmpty()) {
            item {
                Text(
                    text = "No projects yet. The add button brings in a repository from any " +
                        "address that speaks git, or makes one that stays on this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }
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
                            // **A project's first screen is its branches** (§13d). `openBranches`
                            // rather than `open`, so the tap names a project rather than a folder —
                            // and the file screen is reached by choosing a branch, one tap deeper.
                            .combinedClickable(onClick = { viewModel.openBranches(name) }),
                    ) {
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                        // **How much is unsent, on one line.** It used to lead with the project's
                        // type; with types gone the count is the whole of it, which is the one fact
                        // this screen exists to show at a glance.
                        val sending = if (viewModel.isLocalProject(name)) {
                            null
                        } else {
                            viewModel.changesOf(name).let { if (it > 0) "$it to send" else "up to date" }
                        }
                        // **And what will never be sent.** An ignored path is not a change and not
                        // unsent: it is a file on the phone that stays on the phone until somebody
                        // tracks it. Without this a project holding a 40 MB video and one note says
                        // "up to date", which is true of the note and a lie about the project.
                        val held = viewModel.ignoredOf(name).let {
                            if (it > 0) "$it not sent" else null
                        }
                        val second = listOfNotNull(sending, held).joinToString(" · ")
                        if (second.isNotEmpty()) {
                            Text(
                                text = second,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // **The system project has no menu**, and this is the operator's list of why:
                    // "no 3dot menue (no rename, delete, put on github etc)". Every item here is
                    // about a repository the person owns — Chat, Sync, Publish, Keep local,
                    // Rename, Delete, Export — and the system project is none of those: its name
                    // is fixed, its contents are the app's, and it has no remote to sync with or
                    // publish to. The row still opens, which is how a person reads it.
                    if (!Workspace.isSystem(name)) Box {
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
                            // **The address, which is where a project is** — and the whole of what
                            // publishing and renaming became. A local project given one is published;
                            // a project whose address names something else is renamed after it. There
                            // is no separate "put on GitHub" because there is no repository to make
                            // here: that is the host's own page, and this window takes its result.
                            DropdownMenuItem(
                                text = { Text("Set the address…") },
                                onClick = {
                                    menu = false
                                    onRename(name)
                                },
                            )
                            // **The way back, for a project that has one.** A local project has no
                            // remote, so this could only say no — the rule a locked file's Rename
                            // already follows. What it cannot do is delete the repository: that is
                            // the host's page too, and the window says so.
                            if (!viewModel.isLocalProject(name)) {
                                // **No "Sync" here either.** The row lost its button and the menu
                                // would have been the same act with a different door — the
                                // operator moved this decision to the agent, and a second door
                                // would make the removal look like a layout change.
                                DropdownMenuItem(
                                    text = { Text("Go local…") },
                                    onClick = {
                                        menu = false
                                        onKeepLocal(name)
                                    },
                                )
                            }

                            // **No "Export to Downloads" here any more.** It exported the newest CI
                            // artifact, and an artifact is a run's output — a vendor's idea that
                            // `ls-remote` cannot ask about. A file of the project's is copied out
                            // from the file's own menu, which is where the person is looking at it.
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
        // **Last, and as a folder.** No menu, no change count, no "up to date": the only things a person
        // can do here are read it and clone out of it, and every write is the app's. It opens through
        // [AppViewModel.open] with the path spelled out, which is the same door the browser uses for any
        // folder - and it is the door onboarding already sends a first-time reader through.
        if (system != null) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.open(listOf(Workspace.SYSTEM_PROJECT)) }
                        .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(Workspace.SYSTEM_PROJECT, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "the app's own folder — read me, prompts, templates",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
    // **The ignore set is read here for the same reason.** Both marks are answers about state the
    // view model holds; reading one from state and the other from a call was the whole bug.
    // **Ignored is asked of git, per path, and cached for the frame.** It is the same question
    // `git status` answers and the same one that decides what is committed — so the row cannot
    // disagree with what will actually be sent.
    val ignoredPaths = viewModel.ignoredPaths
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
                // **From state, like the lock beside it.** Asking git per row would walk the tree
                // file on every call, which Compose cannot see - so a tap that changed the file
                // changed nothing the row was watching and the icon waited for some other redraw.
                // Reported twice: "sync connect icon doesnt update visually after tapping on it".
                ignored = ignoredPaths[entry.file.absolutePath] ?: false,
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
     * Whether git ignores this row — the same question `git status` answers.
     *
     * **A plain tracked/untracked switch**, which is what the operator asked for: "gitignore should
     * be just classic gitignore, not as sync currently implemented but a simple tracked/untracked
     * switch". It used to be a third mechanism — a tracked file kept out of a derived tree — and the
     * row said "not sent" because that was true of a thing that no longer exists.
     */
    ignored: Boolean = false,
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
            // **Size and path, and the path is the point.** The operator asked for "a small size
            // and path caption ... make it selectable so that operator can copy the path".
            //
            // **Selectable, and only this line is.** `SelectionContainer` wraps the caption rather
            // than the row: a selectable row would eat the tap that opens a file and the long press
            // that chooses it, which are the two gestures the list exists for. Wrapping the smallest
            // thing that needs selecting is what lets both live in one row.
            //
            // **The path is absolute.** A project-relative one is shorter and useless here: the
            // person is copying it to paste somewhere else on the phone, and `/data/user/0/...` is
            // what every other tool on that phone understands.
            if (entry.caption().isNotEmpty()) {
                SelectionContainer {
                    Text(
                        text = entry.caption(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
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
            if (hold == Workspace.Hold.APP) {
                UnsyncGlyph(
                    tint = APP_HOLD,
                    description = "${entry.name} is kept out of the repository by the app",
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        } else if (!choosing && !system) {
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
            // **`UNKNOWN` used to be excluded, and that became the one row that mattered most.**
            // The gate read `kind != UNKNOWN`, on the reasoning that a binary the app cannot open
            // has no lock to offer — which is true of the *lock*. But the ignore switch is the one
            // control a binary needs: a `.pdf` or a `.zip` arrives ignored by default, and the row
            // that would let the person send it was the row that drew no switch at all. A file
            // waiting behind a door with no handle is the silent no-op this app keeps removing.
            //
            // So the row is drawn for every kind now. The lock is meaningless on a binary and
            // harmless; the ignore switch is the whole point.
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
                val stopped = ignored
                val tint = if (stopped) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.outline
                // **"Ignored" is the word now**, because that is the mechanism: the path is in
                // `.gitignore` and out of the index. "Not sent" described the derived tree, which
                // no longer exists — and a person who wants to know why a file is missing from
                // GitHub is looking for the word git itself uses.
                val said = if (stopped) "${entry.name} is ignored: it is not on GitHub"
                else "Ignore ${entry.name}, so it is not on GitHub"
                if (stopped) {
                    UnsyncGlyph(tint = tint, description = said)
                } else {
                    LinkedGlyph(tint = tint, description = said)
                }
            }
        }
        // **The menu is on every row; what is in it depends on who holds the file.**
        //
        // The operator's rulings, in order: "3dots are hidden only on system locked branches";
        // "system locks restrict both users and agents, and user locks restrict agents (not users
        // themselves!)"; and then the correction that produced this shape — "add 3dot icon for
        // system locked files but leave only clone option there for them".
        //
        // So an app-held row gets a menu with **one** item. It used to get no menu at all, which
        // was right while the only alternative was a menu of things the lock would refuse; the
        // clone is the exception, because a copy is not a write to this file. A person-locked row
        // keeps the whole menu, because every item in it works there: `Workspace` asks `isLocked`
        // (the app's locks) and `isAgentLocked` (both) as different questions, so the person's lock
        // never stopped the person.
        if (!choosing && !system) Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                // **A system-locked row gets one item, and it is the one act the lock was never
                // about.** The operator: "add 3dot icon for system locked files but leave only
                // clone option there for them." Reading a file and making a copy changes nothing
                // here — the copy lands in a project of the person's own, where it is ordinary text
                // they may edit. Rename, Move, Delete, Pin and the sync toggle are all about a file
                // this app keeps, so none of them is offered.
                if (hold == Workspace.Hold.APP) {
                    DropdownMenuItem(
                        text = { Text("Clone…") },
                        onClick = {
                            menu = false
                            actions.onClone(entry)
                        },
                    )
                    // **And the copy out**, which is the other act a lock was never about: it reads
                    // the file and writes somewhere else entirely.
                    DropdownMenuItem(
                        text = { Text("Copy to Downloads") },
                        onClick = {
                            menu = false
                            actions.onDownload(entry)
                        },
                    )
                } else {
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
                // **The same act the `upload` tool does**, through the same `Export` call, so a
                // person's copy and an agent's cannot drift apart.
                DropdownMenuItem(
                    text = { Text("Copy to Downloads") },
                    onClick = {
                        menu = false
                        actions.onDownload(entry)
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
fun ProjectSlimRow(viewModel: AppViewModel, onRelease: (() -> Unit)? = null) {
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
        // **The tree on screen**, which on a branch is not the project's (see `AppViewModel.changesHere`).
        changes = viewModel.changesHere,
        busy = viewModel.busy,
        onSeeChanges = { viewModel.openChanges(project) },
        onRelease = onRelease,
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
