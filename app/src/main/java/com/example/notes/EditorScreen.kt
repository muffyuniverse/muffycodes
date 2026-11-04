package com.example.notes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SingleChoiceSegmentedButtonRowScope
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The editor: the file's name in the top row, and its text below.
 *
 * ```text
 * [<-]  milk.txt                        [history] [report]   <- the file, and its acts
 *
 * [ View | Edit | Diff ]                            Save    <- what it is, and commit
 * ```
 *
 * **The top row belongs to the file; the row under it belongs to the act.** The name is the one
 * thing the screen is about, so nothing presses on it: the two icons at the right are the acts that
 * need no word, and the modes and Save share the line below.
 *
 * This was three rows. The modes were alone on one and History and Save on the next, because three
 * modes plus two acts did not fit across a phone. The operator's correction is what untied it:
 * "remove the in the cell checkmark symbol on selection (color is a good enough marker), also remove
 * the black outline. then put it on the same row with save button. and put history button on the top
 * bar right corner to the left of bug report icon". Dropping the tick from each cell and the outline
 * from the group is what freed the width; moving History up is what freed the rest.
 *
 * **Nothing here scrolls.** A scrolling row makes an overflow look like an absence: the
 * button is there, a person simply cannot see it, and a primary button they have to hunt
 * for is a button that looks missing. The rows are sized to fit, and the picture of this
 * screen is what says whether they do.
 *
 * **Back discards.** Saving is something a person does, so the arrow really does
 * leave without keeping what was typed; the message says so when something was
 * dropped.
 *
 * **The name is editable where it stands**, and it is committed on "done" and on
 * leaving the field, never on a keystroke: the naming rule would otherwise rewrite
 * the name under the finger as it was typed.
 *
 * **A locked file opens in View and cannot leave it.** The lock lives in `.muffy`,
 * so it changes nothing in the file and makes no commit — which is why unlocking is
 * also free.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    viewModel: AppViewModel,
    file: java.io.File,
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    onReport: () -> Unit = {},
) {
    /**
     * **A page that must be read to the end**, so it has no Back and no Exit.
     *
     * **Read from the view model, not taken as a parameter**, because the rule is the app's and not
     * the caller's: onboarding is a state the whole app is in, and a screen that had to be *told*
     * about it could be told wrong. No caller ever passed one — the parameter was written with a
     * default and then never used, which is a second way to state the same fact and therefore a
     * second thing to keep in step.
     *
     * The same page opened later from the browser is an ordinary document and may be left, which is
     * exactly why this is not a check on the path.
     */
    val mustFinishReading = viewModel.onboarding
    val kind = viewModel.workspace?.kindOf(file) ?: EntryKind.TEXT
    val locked = viewModel.isLocked(file)
    // **The agent's record is the app's, and this screen says so rather than showing a Save that
    // will not work.** The two log files are what the conversation was and what the model sees; a
    // person saving over either one leaves an agent whose memory is not evidence of anything, so
    // the app refuses — the same rule that stops the record's `agent/` folder being renamed. What the
    // person keeps is Reset (the working context) and deleting the project, which takes the record
    // with it.
    val record = viewModel.isRecord(file)
    val readOnly = locked || record
    // Keyed on the *session*, not on the file: renaming the file you are writing
    // changes its path, and resetting the text on that would throw away everything
    // typed so far. Opening another file starts a new session and resets both.
    val session = viewModel.editorSession
    var text by remember(session) { mutableStateOf(viewModel.textOf(file)) }
    // What is on disk, so that Save can say whether there is anything to save.
    var saved by remember(session) { mutableStateOf(text) }
    var name by remember(session) { mutableStateOf(file.name) }
    /**
     * **View, Edit, or Diff** — three modes, and View is where it starts.
     *
     * The report asked for exactly this, and the reason View is the default is the reason the old
     * switch was wrong: a person opening a file usually wants to *read* it, and an editor that
     * starts in Edit puts a cursor in the text and a keyboard on the screen before anybody has said
     * they want to change anything. A locked or kept file opens in View and cannot leave it.
     *
     * **Except a file opened from "changes to send"**, which starts in Diff. The operator: "files
     * from this list when clicked should open in diff mode." A person who tapped a row of a list
     * called "Changes to send" has already said what they want to see; making them press Diff
     * afterwards is asking the same question twice.
     */
    var mode by remember(session) {
        mutableStateOf(
            if (viewModel.editorOpening == AppViewModel.Opening.UNSENT && !readOnly) Mode.DIFF
            else Mode.VIEW,
        )
    }
    // Kept as a name because the rest of this function asks "is the text being written" in a dozen
    // places, and a boolean read off the mode is clearer at each of them than the comparison is.
    val writing = mode == Mode.EDIT

    /**
     * **One place a mode is entered, so one place records it.**
     *
     * A mode is an act, not a redraw: "I opened the diff and it showed nothing" and "I never got
     * into Diff" look the same on a screen with no log. The name is the mode's own lowercase word,
     * so the trace reads `EDITOR VIEW milk.txt` rather than a sentence about a button.
     */
    /**
     * Writes the text if it differs from what is on disk, and does nothing otherwise.
     *
     * **"Only if changed" is the whole point, and it is not an optimisation.** Every save is a git
     * commit — `saveEditor` says so: "every save is its own version, and sync is then only the
     * push". Without this guard, opening a file to read it, pressing Edit, and pressing View again
     * would add a commit that records nothing, and a person doing that ten times would have ten
     * empty versions to scroll past in the history.
     */
    fun autosaveIfChanged() {
        if (readOnly || kind != EntryKind.TEXT) return
        if (text == saved) return
        viewModel.saveEditor(text)
        saved = text
    }

    /**
     * What a link in the document does.
     *
     * **`app:` is refused outside the system project.** A locked page is written by the app and
     * cannot be edited, so its links are the app's own. A *cloned* page is ordinary user text, and a
     * cloned page carrying `app:delete-account` would be a document that can act — the door this
     * feature must not open.
     */
    /**
     * **Records how far the person has got, as they get there.**
     *
     * Keyed on the session, so it runs once per page rather than on every recomposition. The record
     * only moves forward — [AppViewModel.onboardingReached] does the comparison — so swiping back to
     * re-read a page does not lose the ground that was gained.
     */
    LaunchedEffect(session, viewModel.onboarding) {
        val at = viewModel.onboardingIndexOf(file)
        if (at >= 0) viewModel.onboardingReached(at)
    }

    val onLink: (String) -> Unit = { address -> viewModel.followLink(address, file) }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current

    /**
     * What has been drawn on this picture, **while this screen is open and no longer.**
     *
     * D4: undo is session-only, and the saved versions are git's job — so the stroke list dies with
     * the screen. A stack of snapshots that survived a restart would be a second document beside the
     * PNG, and two documents that can disagree is the thing this codebase spends its time avoiding.
     */
    var strokes by remember(session) { mutableStateOf<List<Drawing.Stroke>>(emptyList()) }
    /**
     * **The tool in force, or null for none.**
     *
     * The operator: "so that when you touch a selected tool it gets unselected" — and the reason that
     * matters is the other half of the same report, "you cant move or zoom drawing while any of the
     * drawing tools (pen/line/box) is selected". No tool means the canvas is for looking at, so one
     * finger pans and two pinch; a tool means one finger draws and two still pinch, because pinching to
     * see what you are drawing is not the same act as moving the paper under a pen.
     */
    var tool by remember(session) { mutableStateOf<Drawing.Tool?>(Drawing.Tool.PEN) }
    var inkColour by remember(session) { mutableStateOf(Drawing.COLOURS.first()) }
    var inkWidth by remember(session) { mutableStateOf(Drawing.DEFAULT_WIDTH) }
    /**
     * The picture as it was when this screen opened, decoded once.
     *
     * `Picture` decodes its own copy for display; this one is what a save flattens the strokes onto.
     * Decoding twice is the price of not letting a save depend on what the screen happens to be
     * showing — and a save that drew on the screen's own bitmap would corrupt the preview.
     */
    /**
     * The picture as it was when this screen opened, in **three states rather than two**.
     *
     * **`null` used to mean both "still decoding" and "cannot be read"**, and the canvas said "this
     * picture could not be opened" for the first frames of every picture — the same conflation
     * `PictureState` exists to avoid, which I reintroduced here and the tour picture caught. Loading
     * and unreadable are different answers and each has its own thing to say.
     */
    var decoded by remember(session) { mutableStateOf<PictureOpen>(PictureOpen.Loading) }
    LaunchedEffect(file.path, kind) {
        if (kind == EntryKind.IMAGE) {
            decoded = PictureOpen.Loading
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    android.graphics.BitmapFactory.decodeFile(file.path)
                } catch (e: Throwable) {
                    null
                }
            }
            decoded = if (bitmap == null) PictureOpen.Unreadable else PictureOpen.Ready(bitmap)
        }
    }

    /**
     * Whether the person has drawn something that is not on disk yet.
     *
     * **The one way the canvas can lose work**, and the reason the guard below exists: a stroke list is
     * session-only by design (D4), so leaving Draw throws it away — silently, unless something asks.
     */
    val unsavedStrokes = strokes.isNotEmpty()

    /**
     * One place a mode is entered, so one place records it **and one place can refuse it.**
     *
     * **Leaving `Draw` with unsaved strokes asks first.** Every other mode switch is free — text lives
     * in a field and Save is still there, Diff only reads — but a stroke list is held in memory and
     * dies with the screen, so a stray tap on `View` would throw away a drawing with no undo. The
     * prompt is the same `ConfirmDialog` every other destructive act uses, so the question looks like
     * the app rather than like a special case.
     */
    var pendingMode by remember { mutableStateOf<Mode?>(null) }
    val choose: (Mode) -> Unit = { next ->
        if (mode == Mode.DRAW && next != Mode.DRAW && unsavedStrokes) {
            // Asked, not done. The mode changes in the dialog's confirm, so backing out leaves the
            // person exactly where they were — still in Draw, with the drawing intact.
            pendingMode = next
        } else {
            Trace.tap(
                // **Literal names, not a name built from the enum.** The vocabulary check refuses a
                // computed tap name, and it is right to: a name it cannot read is a name nobody can
                // grep for, and the list of what a person can press is the point of having a vocabulary.
                when (next) {
                    Mode.VIEW -> "editor view"
                    Mode.EDIT -> "editor edit"
                    Mode.DIFF -> "editor diff"
                    Mode.DRAW -> "editor draw"
                },
                file.name,
            )
            mode = next
        }
    }

    // The file can be renamed from elsewhere — a move, or the browser's rename — and
    // the field must show the name it has. Only while it is not being typed in,
    // because overwriting a half-typed name is the same bug in the other direction.
    LaunchedEffect(file.path) {
        if (!focused) name = file.name
    }

    // A version put back from the history changes the file underneath the editor,
    // which stays open. Pick it up — but only when there is nothing unsaved to
    // lose, because losing what somebody typed is never the lesser evil.
    LaunchedEffect(viewModel.historyGeneration, session) {
        val onDisk = viewModel.textOf(file)
        if (text == saved && onDisk != text) {
            text = onDisk
            saved = onDisk
        }
    }

    /**
     * Renames the file to what is in the field, if that is a different name.
     *
     * Idempotent, and that is not a detail: pressing "done" on the keyboard moves
     * focus, and moving focus calls this again. The second call used to compare the
     * *cleaned* name against the name of the file from before the rename, decide it
     * had work to do, fail because that file no longer existed — and put the old
     * name back in the field. Which is exactly what "the title lags one rename
     * behind" looks like from the outside.
     */
    fun commitName() {
        val live = viewModel.editingFile ?: file
        val wanted = name.trim()
        if (wanted.isEmpty() || wanted == live.name) {
            name = live.name
            return
        }
        // The answer is the file, not "yes": the name to show is the one the app
        // actually wrote, which is not always the one that was typed.
        val moved = viewModel.renameTo(live, wanted)
        name = moved?.name ?: live.name
    }

    /**
     * Leaves the editor, keeping anything typed.
     *
     * **It used to drop what was unsaved, and say so** — "Left without saving". That was honest
     * while Save was a button: the person had been offered a save and had not taken it. With Save
     * gone, leaving is the last moment the app can tell that the writing was meant, and a warning
     * about a loss the person did not choose would be the app blaming them for its own timing.
     *
     * The reset is still available, and it is the one way to deliberately throw writing away.
     */
    fun leave() {
        autosaveIfChanged()
        focus.clearFocus()
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    // **No way back off an onboarding page until the account is connected.**
                    // The operator's rule: *"can you just not render <- back button for
                    // onboarding/ files until github and openrouter are signed in?"* Onboarding is
                    // the sequence that *does* the signing in, so a door out of it is a door out of
                    // the thing that has to happen first — and it is the same reason [closeEditor]
                    // refuses. Hidden rather than disabled: a greyed arrow on a page with no other
                    // chrome is a button that says "you may not", and there is nothing to say.
                    //
                    // **Only on onboarding pages.** An ordinary file opened while signed out is a
                    // file a person may have opened to read, and trapping them in it would be the
                    // rule applied to the wrong thing.
                    if (!mustFinishReading) {
                        // Back, and it means back: what was typed and not saved goes.
                        IconButton(onClick = { leave() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back without saving",
                            )
                        }
                    }
                },
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (locked) {
                            Icon(
                                Icons.Filled.Lock,
                                contentDescription = "Locked, so it cannot be edited",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                        // The name, editable where it stands — but only while the
                        // file can be written. A named anything on a locked file is
                        // a rename, and a locked file is one this app does not touch.
                        BasicTextField(
                            value = name,
                            onValueChange = { if (!readOnly) name = it },
                            singleLine = true,
                            readOnly = readOnly,
                            textStyle = MaterialTheme.typography.titleMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = {
                                commitName()
                                focus.clearFocus()
                            }),
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { state ->
                                    focused = state.isFocused
                                    if (!state.isFocused) commitName()
                                },
                        )
                    }
                },
                // **History is an act, and its place is the top bar** - the operator's own
                // correction: "put history button on the top bar right corner to the left of bug
                // report icon". It used to be a word on the row below, which cost the row a third
                // of its width for a button pressed far less often than the modes.
                actions = {
                    // **No History on a file git does not keep.** The operator's report: "maybe logs
                    // are not versioned but editor still shows history button when you open them and
                    // that is confusing". The exclusion was working - `agent/`, `.muffy/` and
                    // `requests/` are in the project's `.git/info/exclude`, so no log has ever been
                    // committed - and the *button* was the lie: pressing it on a log opens a screen
                    // that can only say it has no versions, which reads as a broken feature rather
                    // than as a file that is deliberately not versioned.
                    //
                    // **A locked file has no history either, and for the same reason.** The
                    // operator's rule: *"dont render history icon in system locked files as well -
                    // they never have history"*. It is true of all four kinds, and each is kept out
                    // of git by a different rule — which is why the test is `readOnly` rather than a
                    // second list of paths to keep in step:
                    //
                    // | locked because | kept out by |
                    // |---|---|
                    // | an onboarding page | the system project has no `.git` at all |
                    // | the agent's record | `APP_IGNORES` |
                    // | a request log | `APP_IGNORES` |
                    // | a secret (`.env`, `id_rsa`, `*.pem`) | `SECRET_PATTERNS` |
                    //
                    // **`readOnly`, and not `locked`.** The two differ by the agent's record, and a
                    // record is the one locked file a person opens on purpose — so the rule that
                    // hides this button has to be the one the editor already uses to mean "this
                    // cannot be written", or the record would keep a button that lies.
                    //
                    // Everything else in the project keeps it, including files the person has not
                    // saved yet.
                    if (!readOnly) {
                        HistoryButton {
                            // The name is committed first: a version list is a list of this
                            // file's versions, and the file may just have been given another
                            // name.
                            commitName()
                            onOpenHistory()
                        }
                    }
                    // **Photo turns this page into a picture**, so a person can circle a paragraph
                    // and say "this goes there" — the correction that cannot be said in words.
                    //
                    // **An icon, not a word**, because it sits beside the report icon and a word
                    // there took a third of the bar from the file's own name.
                    //
                    // **Absent where the picture could not be written.** A photograph is written
                    // *beside* the page it came from, so a locked folder has nowhere to put it:
                    // `Workspace.writeBytes` refuses, and the button would be an act that always
                    // fails. The operator's words: *"make sure that it cant be triggered from a
                    // system locked folder (otherwise it will clash with readonly lock when saving
                    // the photo)"*. The record is the same case — a picture beside a log is a file
                    // the app will not make.
                    if (kind == EntryKind.TEXT && !readOnly) {
                        PhotoButton {
                            focus.clearFocus()
                            viewModel.photograph(text)
                        }
                    }
                    ReportButton(onReport)
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // **One row: the modes on the left, Save on the right.** It was two - modes alone,
            // then History and Save - because three modes and two acts did not fit on a phone. Moving
            // History to the top bar is what bought the row back, which is the operator's point:
            // "put it on the same row with save button".
            // **An onboarding page has no row at all.** Every control on it would be disabled —
            // Edit, Diff, Reset — so the row would be three greyed words and one live one that is
            // already selected. The operator's rule: *"instead of greying out just dont render the
            // row at all, because it will just have view active on it which is default anyway — but
            // that is only for system locked files (such as onboarding/)"*.
            //
            // **`onboarding/`, and not every locked file.** The agent's record is locked too, and
            // its Diff is *not* a disabled control: reading a file's history is not writing it, so
            // the record's row keeps a live Diff beside a disabled Edit. Hiding the row there would
            // take away something the record never forbade — and the sentence above the text would
            // be explaining controls that are not on screen.
            val page = viewModel.onboardingIndexOf(file) >= 0
            if (!page) Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // An unreadable file has no modes; Save is then the only thing on the row and
                // belongs at its end, where it is on every other file.
                if (kind == EntryKind.UNKNOWN) Spacer(Modifier.weight(1f))
                // **An image gets its own two modes**, and `Draw` is what makes a picture something
                // a person can change rather than only look at. The text modes are not offered here
                // because an image has no text to edit and no diff to read — a picture's history is
                // its versions, which the History button already shows.
                if (kind == EntryKind.IMAGE) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                        ModeSegment("View", Mode.VIEW, selected = mode) { choose(it) }
                        ModeSegment("Draw", Mode.DRAW, selected = mode, enabled = !readOnly) {
                            choose(it)
                        }
                    }
                }
                if (kind == EntryKind.TEXT) {
                    // **Three modes, named, and one of them is selected.** A single chip that said
                    // "Edit" or "View" was a switch: it told a person what pressing it would do, not
                    // where they were, and there was nowhere to put a third thing. Report 29 asked
                    // for separate buttons, and a segmented control is that with the "exactly one of
                    // these" rule carried in its shape.
                    // **`weight(1f)` on the group, not a spacer beside it.** A weighted child is
                    // measured *after* the ones without weight, so Save is laid out at the width its
                    // own text needs and the modes take what is left. The first attempt put a
                    // `weight(1f)` spacer between them instead, which reserved every remaining pixel
                    // and left Save thirty wide - the three-mode row rendered as "S a v e" stacked
                    // one letter per line. The picture is what caught it; no test would have.
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                        ModeSegment("View", Mode.VIEW, selected = mode) {
                            // **Leaving Edit saves, if anything changed.** This is the moment the
                            // person says they are done writing, and it is the only signal the app
                            // has — so the save happens here rather than behind a button they might
                            // forget. Guarded on a real difference: entering Edit to read something
                            // and leaving again must not write a commit for nothing.
                            focus.clearFocus()
                            commitName()
                            autosaveIfChanged()
                            // **The mode is set as well as the surface opened.** Leaving it out
                            // was a bug worth naming: the segment reads `selected = mode`, so a
                            // person who pressed View from Edit would have opened the page and
                            // found the Edit segment still highlighted behind it.
                            mode = Mode.VIEW
                        }
                        ModeSegment("Edit", Mode.EDIT, selected = mode, enabled = !readOnly) {
                            choose(it)
                        }
                        // **Diff is offered on any file with a history**, which is every file in a
                        // project: the picker's default is HEAD against the last synced commit, and
                        // a file with nothing between them says so rather than hiding the mode.
                        // **Diff is offered on any file with a history**, which is every file in a
                        // project — the picker's default is HEAD against the last synced commit, and
                        // a file with nothing between them says so rather than hiding the mode.
                        //
                        // **Except a file git does not keep**, which is the same set the History
                        // button is hidden for: an onboarding page, the agent's record, a request
                        // log, a secret. Their Diff could only ever say "nothing to compare", for
                        // ever, because there is nothing to compare — so the mode is disabled for
                        // the same reason the button is absent.
                        ModeSegment("Diff", Mode.DIFF, selected = mode, enabled = !readOnly) {
                            focus.clearFocus()
                            commitName()
                            choose(it)
                        }
                    }
                }

                // A fixed gap rather than a spacer: the weight above is already the flexible one,
                // and two flexible children would each take half of what Save does not need.
                Spacer(Modifier.width(8.dp))

                Button(
                    onClick = {
                        if (kind == EntryKind.IMAGE) {
                            // **The strokes are baked into the picture, and the list is cleared.**
                            // Clearing matters: what is now on disk includes them, so keeping them
                            // would draw every mark twice on the next save — the same bug in the
                            // other direction from losing them.
                            (decoded as? PictureOpen.Ready)?.let { open ->
                                if (strokes.isNotEmpty()) {
                                    val flattened = Drawing.flatten(open.bitmap, strokes)
                                    viewModel.savePicture(flattened)
                                    strokes = emptyList()
                                    // **The base becomes what was just written.** It used to stay the
                                    // picture from *before* the strokes, so View — which re-reads the
                                    // file — showed them and Draw, which draws `decoded` plus the
                                    // stroke list it had just emptied, did not. The operator's report:
                                    // "see why changes after saving are visible in view mode, but
                                    // become invisible in draw mode".
                                    //
                                    // Decoding the bytes the app just encoded is the one way to be
                                    // sure the two screens agree: it is the same image View will read
                                    // back off disk, not a second guess at it.
                                    decoded = android.graphics.BitmapFactory
                                        .decodeByteArray(flattened, 0, flattened.size)
                                        ?.let { PictureOpen.Ready(it) }
                                        ?: decoded
                                }
                            }
                        } else {
                            // **Reset, not Save.** The text is put back to what is on disk — the
                            // version the person last agreed to — and nothing is written. Saving is
                            // no longer an act a person performs: it happens when they leave Edit,
                            // which is the moment the app can tell they meant it.
                            //
                            // The operator's argument, which this follows: a Save button is friction
                            // that exists because the app could not decide when a save was intended.
                            // Leaving Edit *is* that decision.
                            Trace.tap("editor reset", file.name)
                            text = saved
                            focus.clearFocus()
                        }
                    },
                    enabled = when (kind) {
                        // **Reset is worth pressing only when there is something to undo**, which
                        // is exactly when the text differs from what is on disk.
                        EntryKind.TEXT -> !readOnly && text != saved
                        // An image has nothing to reset until something has been drawn on it.
                        EntryKind.IMAGE -> !readOnly && strokes.isNotEmpty() && decoded is PictureOpen.Ready
                        else -> false
                    },
                ) { Text(if (kind == EntryKind.IMAGE) "Save" else "Reset") }
            }
            // **No rule either**, because a divider is the line under a row and there is no row.
            if (!page) HorizontalDivider()

            // No sync row here. The editor is a page of writing, not a place where
            // projects are managed: a line of account, project and "3 changed" above
            // the text was the app talking over the thing being written.
            val content = PaddingValues(top = 0.dp)
            when (kind) {
                EntryKind.IMAGE -> if (mode == Mode.DRAW) {
                    DrawMode(
                        picture = decoded,
                        strokes = strokes,
                        tool = tool,
                        colour = inkColour,
                        width = inkWidth,
                        onTool = { tool = it },
                        onColour = { inkColour = it },
                        onWidth = { inkWidth = it },
                        onUndo = { strokes = Drawing.undo(strokes) },
                        onAdd = { strokes = strokes + it },
                        onErase = { index ->
                            // **A stroke is removed whole.** An eraser that painted white would
                            // destroy the picture underneath, and a person rubbing out a wrong arrow
                            // over a screenshot would lose the screenshot.
                            strokes = strokes.filterIndexed { i, _ -> i != index }
                        },
                    )
                } else {
                    // **View is the paged reader, even for a picture.** It used to be a bare
                    // `Picture`, which is why the operator could not swipe out of one: "why can't I
                    // swipe from its view mode to other files?" The swipe is a property of reading,
                    // not of text — and a page that is a drawing is one a person flips past like any
                    // other.
                    Reader(
                        viewModel = viewModel,
                        file = file,
                        text = "",
                        onLink = onLink,
                        kind = EntryKind.IMAGE,
                        onStep = { steps ->
                            val beside = viewModel.fileBeside(file, steps)
                            if (beside != null) {
                                Trace.tap("swipe file", beside.name)
                                autosaveIfChanged()
                                viewModel.openEditor(beside)
                            } else {
                                Trace.note(
                                    "swipe file",
                                    if (steps > 0) "past the last" else "before the first",
                                )
                            }
                        },
                    )
                }
                EntryKind.UNKNOWN -> CannotEdit(file = file, padding = content)

                // **Diff, before the text**, because it is a different view of the file rather
                // than a different way of editing it. It draws what changed between two commits,
                // and the picker above it is where the two come from.
                else -> if (mode == Mode.DIFF) {
                    DiffMode(viewModel = viewModel, path = file.path)
                } else if (writing && !readOnly) {
                    TextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxSize(),
                        textStyle = MaterialTheme.typography.bodyLarge,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                        ),
                        placeholder = { Text("Start writing…") },
                    )
                } else {
                    // **Why this cannot be written, before the text it applies to.** A Save that is
                    // greyed with no sentence beside it reads as a broken screen; the sentence says
                    // whose file it is and which acts remain.
                    if (record) {
                        Text(
                            "The agent's record. The app keeps this file — it is what the " +
                                "conversation was, and what the model is sent — so it is read here " +
                                "and never saved over. Reset clears the working context; deleting " +
                                "the project takes the record with it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    // **Reading is one surface, and it is where the swipe lives.** This used to be
                    // a plainer reader beside a fullscreen one — the same document drawn twice, with
                    // the swipe on only one of them, which is the drift that having two readers
                    // always ends in.
                    Reader(
                        viewModel = viewModel,
                        file = file,
                        text = text,
                        onLink = onLink,
                        onStep = { steps ->
                            val beside = viewModel.fileBeside(file, steps)
                            // **A swipe past the end bounces, and that is the whole rule.**
                            // `fileBeside` returns null there on purpose — "wrapping would make a
                            // folder feel circular" — and onboarding used to make that same null
                            // mean "finish", which is one gesture with two meanings and a special
                            // case bolted onto a rule that says the opposite.
                            //
                            // **Onboarding does not end by swiping any more.** It ends when both
                            // connections are made (`closeOnboardingIfConnected`), which is the
                            // thing it exists to do; a person who read every page without connecting
                            // uses the last page's own way out.
                            if (beside != null) {
                                Trace.tap("swipe file", beside.name)
                                // **Saved first, and only if it changed.** A swipe out of Edit
                                // would otherwise be the one way to lose writing, since leaving is
                                // a save everywhere else.
                                autosaveIfChanged()
                                viewModel.openEditor(beside)
                            } else {
                                // **The bounce is the answer at either end**, so nothing is done
                                // and nothing is said: a message per swipe would be the app
                                // explaining a wall the person can feel.
                                Trace.note(
                                    "swipe file",
                                    if (steps > 0) "past the last" else "before the first",
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    // **The question, drawn outside the layout** so it can appear over whatever mode is showing. The
    // answer is what changes the mode: dismissing leaves the person in Draw with the drawing intact,
    // which is the whole point of asking rather than warning after the fact.
    val leaving = pendingMode
    if (leaving != null) {
        ConfirmDialog(
            title = "Leave the drawing?",
            text = "What you have drawn is not saved yet. Leaving Draw discards it, and there is no " +
                "undo once the canvas is closed.",
            confirm = "Discard",
            destructive = true,
            onDismiss = { pendingMode = null },
            onConfirm = {
                pendingMode = null
                strokes = emptyList()
                Trace.tap(
                    when (leaving) {
                        Mode.VIEW -> "editor view"
                        Mode.EDIT -> "editor edit"
                        Mode.DIFF -> "editor diff"
                        Mode.DRAW -> "editor draw"
                    },
                    file.name,
                )
                mode = leaving
            },
        )
    }
}

/** True for a file whose text is meant to be read as Markdown. */
internal fun isMarkdown(file: java.io.File): Boolean =
    file.extension.lowercase() in setOf("md", "markdown")

/**
 * A picture, decoded away from the main thread.
 *
 * Two things this does deliberately.
 *
 * **It runs off the main thread.** Decoding a photo takes long enough to drop
 * frames, and the editor is also where typing happens.
 *
 * **It downsamples before it allocates.** A modern phone photo is 12 to 50
 * megapixels; decoded at full size into a bitmap that is 4 bytes per pixel, a
 * 50MP photo needs 200MB and Android kills the app for it. The file only ever
 * fills a screen of a few hundred by a few thousand pixels, so the decode is
 * told to sample down to about what will be visible — by powers of two, which is
 * the only thing [BitmapFactory] can do cheaply — and never to allocate more than
 * [MAX_BITMAP_PIXELS].
 *
 * [MAX_BITMAP_PIXELS] is a ceiling on the *decoded* bitmap, not on the file. A
 * 10,000 × 10,000 PNG on disk is a few hundred kilobytes and 400MB in memory.
 */
internal object Pictures {

    /** No bitmap bigger than this is ever allocated: 16MP at 4 bytes is 64MB. */
    const val MAX_BITMAP_PIXELS = 16_000_000

    /** About how many pixels the screen can show, so larger is wasted work. */
    private const val TARGET_PIXELS = 4_000_000

    /**
     * Decodes [file], or returns null if it is not a picture this phone can read.
     *
     * Null is a real answer — an `.svg`, a truncated download, or a format the
     * device has no decoder for — and the screen says so rather than showing an
     * empty frame that looks like a bug.
     */
    suspend fun decode(file: java.io.File): ImageBitmap? = withContext(Dispatchers.IO) {
        try {
            // First pass: the dimensions only, which costs no bitmap memory.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0) return@withContext null

            val sample = sampleSizeFor(width, height)
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                // The screen is not going to show more than 8 bits per channel.
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val bitmap = BitmapFactory.decodeFile(file.path, options) ?: return@withContext null
            bitmap.asImageBitmap()
        } catch (e: OutOfMemoryError) {
            // Deliberately caught: a picture that cannot fit is a picture this app
            // declines to show, not a reason to lose the user's work.
            null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * The power-of-two downsample that keeps the bitmap under both ceilings.
     *
     * Divided down from 1 rather than multiplied up from 2, so the answer is always
     * a power of two — [BitmapFactory] ignores anything else — and starting at 1
     * keeps small pictures at full size.
     */
    fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        var pixels = width.toLong() * height.toLong()
        while (pixels > TARGET_PIXELS && sample < 64) {
            sample *= 2
            pixels /= 4
        }
        // The TARGET loop almost always lands under the hard ceiling; this is the
        // guard for the case where it cannot, e.g. an enormous sample cap.
        while (sample < 64 && (width.toLong() / sample) * (height.toLong() / sample) > MAX_BITMAP_PIXELS) {
            sample *= 2
        }
        return sample
    }
}

/** What the picture screen is showing: the picture, or why it cannot. */
private sealed interface PictureState {
    data object Loading : PictureState
    data class Ready(val image: ImageBitmap) : PictureState
    data object Unreadable : PictureState
}

/**
 * The picture with a pen over it, and the four tools that make one.
 *
 * **The tool row is above the canvas and belongs to it**, rather than living in the app's top bar:
 * these are choices about the drawing, and a person reaches for them with the same hand that is
 * drawing. The mode row already says *that* this is Draw; this row says what a finger will do.
 *
 * **No palette beyond four colours and three widths.** A colour picker and a width slider are a
 * settings screen inside a drawing screen, and every tool added is a mode inside the canvas — which
 * is the complexity the file model exists to avoid.
 */
/**
 * **A page, and the pages either side of it, so a swipe moves rather than redraws.**
 *
 * The old reader decided at the *end* of a gesture — `if (travelled > a third of the width) onStep(1)`
 * — so nothing followed the finger and the next page appeared only after it had been read off disk
 * and the whole editor had been re-keyed. That is a page *replaced*, not a page *turned*, and it is
 * the difference the operator asked about: "is it possible to render screen you are swiping to and
 * make a fluent moving transition and not just a redrawn screen?"
 *
 * **It is, and the cost is one page each side.** The neighbours are read when a page settles, so by
 * the time a finger moves the page it is dragging toward is already text in memory. Two extra reads
 * per page, once — against a gesture that would otherwise have nothing to show.
 *
 * **A picture is decoded ahead too**, because a neighbour that slid in as a grey box and filled in
 * afterwards is the redraw this exists to avoid. That is the one real cost: two bitmaps alive at
 * once. `BitmapFactory` gives us no cheap way to hold "a picture" without its pixels, and the
 * alternative — a placeholder — is worse than the memory.
 */
@Composable
private fun Reader(
    viewModel: AppViewModel,
    file: java.io.File,
    text: String,
    onLink: (String) -> Unit,
    onStep: (Int) -> Unit,
    /** **What kind of page this is**, so a picture is drawn rather than shown as its name. */
    kind: EntryKind = EntryKind.TEXT,
) {
    // **The page that will be dragged in from each side**, read ahead of the gesture.
    var previous by remember(file.path) { mutableStateOf<Neighbour?>(null) }
    var next by remember(file.path) { mutableStateOf<Neighbour?>(null) }
    LaunchedEffect(file.path) {
        previous = null
        next = null
        // Off the main thread, so opening a page is not waiting on the ones beside it.
        val found = withContext(Dispatchers.IO) {
            viewModel.fileBeside(file, -1)?.let { read(it, viewModel.workspace?.kindOf(it)) } to
                viewModel.fileBeside(file, 1)?.let { read(it, viewModel.workspace?.kindOf(it)) }
        }
        previous = found.first
        next = found.second
    }

    // **The offset the finger has dragged**, in pixels. Positive is the page moving right, which is
    // a move to the *previous* page.
    //
    // **A plain `Float` during the drag, not an `Animatable`.** This is the fix for the gesture that
    // felt like it had to be overpowered: the drag used to `snapTo` an `Animatable` from inside a
    // coroutine, so the page only moved once the main thread got around to running that coroutine —
    // a frame or two behind the finger, and losing deltas in between. Worse, the `coerceIn` below
    // capped the *offset* at a third of the width, so the page stopped dead at exactly the bar and
    // the rest of the gesture did nothing. The finger had to push past a wall to cross it.
    //
    // Now the offset is written synchronously on every delta, so the page is under the finger the
    // whole way, and the *release* decides. `Animatable` is created only when settling, which is the
    // one time an animation is wanted.
    var offset by remember(file.path) { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    var width by remember { mutableStateOf(1f) }

    /**
     * **A drag that follows the finger, and settles on release.**
     *
     * `draggable` rather than `detectHorizontalDragGestures`: the detector reports a total only when
     * the gesture ends, which is precisely what made the old swipe a redraw. This one reports every
     * pixel, so the page can move with the hand.
     *
     * **The page tracks the finger all the way.** Nothing is clamped while dragging — a person who
     * pulls half a screen gets half a screen of travel — and the decision about whether that was far
     * enough is made on release. The only exception is an end, where there is nowhere to go and the
     * movement is resisted so the wall is felt rather than explained.
     *
     * **Release decides on distance OR speed.** A short flick should turn the page: a person reading
     * on a phone does not drag a third of the screen, they flick. Distance alone made a flick look
     * like the gesture had been ignored.
     */
    val dragging = Modifier.draggable(
        orientation = Orientation.Horizontal,
        state = rememberDraggableState { delta ->
            // **Synchronous, so the page is under the finger.** A `scope.launch` here would put the
            // move behind the frame being drawn — which is half of why the gesture felt laggy.
            offset = Swipe.offsetFor(offset, delta, previous != null, next != null)
        },
        onDragStopped = { velocity ->
            scope.launch {
                val travelled = offset
                // **The decision is `Swipe.steps`, where it can be tested.** Distance or speed, and
                // the arithmetic is not a thing to bury in a gesture lambda twice.
                // **The ends are passed in**, so a flick with nowhere to go answers 0 and bounces
                // rather than animating the page off the screen and finding no file to replace it.
                val steps = Swipe.steps(travelled, width, velocity, previous != null, next != null)
                // **The animation drives the offset the page is actually drawn from.**
                //
                // This was the "first half is animated, then it teleports" bug: the settle animated
                // an `Animatable` and copied its value into `offset` *after* the animation finished,
                // so nothing moved for 180ms and then everything moved at once. A value being
                // animated is not a value being drawn unless the draw reads it — and `offset` is
                // what the three `Modifier.offset` calls read.
                //
                // `animate` writes through the lambda on every frame, so the page follows it.
                val target = if (steps == 0) 0f else if (steps > 0) -width else width
                animate(
                    initialValue = travelled,
                    targetValue = target,
                    animationSpec = tween(180),
                ) { value, _ -> offset = value }
                // **The step happens after the page is off the screen**, not before: the new page is
                // already drawn beside this one, so what the eye follows is one continuous movement
                // rather than a cut.
                if (steps != 0) onStep(steps)
            }
        },
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .then(dragging),
    ) {
        // **Drawn back to front: the neighbours first, then the page on top.** The neighbours sit at
        // their own offsets so that as this page moves, the one arriving is already underneath it.
        previous?.let { page ->
            PagedPage(
                page = page,
                onLink = onLink,
                modifier = Modifier.offset { IntOffset((offset - width).roundToInt(), 0) },
            )
        }
        next?.let { page ->
            PagedPage(
                page = page,
                onLink = onLink,
                modifier = Modifier.offset { IntOffset((offset + width).roundToInt(), 0) },
            )
        }
        PagedPage(
            // **The page being read, with its own kind**, so a picture is drawn rather than named.
            page = Neighbour(file, text, isMarkdown(file), kind),
            onLink = onLink,
            // **The two connections, marked on the page that offers them.** Only there: a tick
            // beside "Connect GitHub" on a page about writing files would be an answer to a
            // question that page did not ask.
            // **Keyed by the address the link already carries**, so the mark lands on the line
            // whose link it describes — and a page that has no such line is simply unaffected.
            //
            // Only on the page that offers the connections: a tick beside "Connect GitHub" on a
            // page about writing files would be an answer to a question that page did not ask.
            marks = if (viewModel.onboardingIndexOf(file) >= 0 &&
                file.name == Onboarding.pages().last().name
            ) {
                mapOf(
                    "app:connect-github" to viewModel.signedIn,
                    "app:connect-openrouter" to viewModel.hasModelKey,
                )
            } else {
                emptyMap()
            },
            modifier = Modifier.offset { IntOffset(offset.roundToInt(), 0) },
        )
    }
}

/** One page's content: what it is, and how to draw it. */
private data class Neighbour(
    val file: java.io.File,
    val text: String,
    val markdown: Boolean,
    val kind: EntryKind = EntryKind.TEXT,
) { val image: Boolean get() = kind == EntryKind.IMAGE }

/**
 * **Reads one page so it is ready before it is needed.**
 *
 * A picture is decoded here, and the result is null when it cannot be read — the page then shows the
 * same "could not be opened" sentence the editor's own picture view does, rather than an empty frame
 * that would look like a page still loading for ever.
 */
private fun read(file: java.io.File, kind: EntryKind?): Neighbour? = try {
    // **Text is read; a picture is named.** A neighbour that is an image has no text to show, and
    // `PagedPage` draws it by its name — the alternative, decoding it here, is two bitmaps alive at
    // once on a phone for a page nobody may swipe to. What the swipe needs is *something in the
    // frame to move*, and a name is that.
    //
    // **And this is why the check is here rather than in a catch.** It used to be `file.readText()`
    // unconditionally, so a PNG's bytes were read as text and drawn as characters for the moment
    // before the real bitmap arrived. The operator saw exactly that: "why drawing when you swipe into
    // it from another file is briefly shown as a set of characters only then it renders into the
    // actual drawing?" The catch below never fired, because reading a PNG as text *succeeds* — it
    // returns convincing nonsense.
    if (kind == EntryKind.IMAGE) {
        Neighbour(file, "", markdown = false, kind = EntryKind.IMAGE)
    } else {
        Neighbour(file, file.readText(), isMarkdown(file), kind ?: EntryKind.TEXT)
    }
} catch (t: Throwable) {
    // **A file that cannot be read as text.** The page still exists, so the swipe can move to it; it
    // says what it is rather than showing an empty frame.
    Neighbour(file, "", isMarkdown(file))
}

/** One page, drawn at the size a page is drawn: the whole screen, its own scroll, selectable. */
@Composable
private fun PagedPage(
    page: Neighbour,
    onLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * **Whether each connection is made**, drawn as a mark beside the two links that offer them.
     *
     * Null on every page but the one that has links to mark. The operator's rule: *"add a visual
     * checkmark to the onboarding screen page across links, so that it is visual whether the keys are
     * set on not"*.
     *
     * **It is drawn here rather than written into the page's text**, because the page is a file —
     * versioned, editable, and read by the agent — and a mark baked into it would be a claim that
     * goes stale the moment somebody connects. The text says what the links do; this says whether
     * they are done, and only one of those changes.
     */
    marks: Map<String, Boolean> = emptyMap(),
) {
    val scrolling = modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 16.dp)
    // **Selectable, because View is where a person copies from.** Edit mode is not: a selection
    // there fights the text field's own cursor, and the field already supports selecting.
    Column(modifier = scrolling) {
        SelectionContainer {
            if (page.markdown) {
                MarkdownBody(
                    text = page.text,
                    baseDir = page.file.parentFile,
                    onLink = onLink,
                    marks = marks,
                )
            } else if (page.text.isNotEmpty()) {
                Text(
                    text = page.text,
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else if (page.image) {
                // **A picture, drawn.** It used to be only its name here, on the reasoning that
                // decoding two bitmaps at once is expensive — and that was the wrong trade: the
                // operator's report was "why drawing when you swipe into it from another file is
                // briefly shown as a set of characters only then it renders into the actual
                // drawing?", and the characters were the PNG's bytes read as text. A neighbour that
                // slides in as its own file name is the redraw the paged reader exists to avoid.
                Picture(file = page.file, padding = PaddingValues(0.dp))
            } else {
                // **A file with no text and no picture**, which is a page the swipe can still move
                // to. It says what it is rather than showing an empty frame.
                Text(
                    text = page.file.name,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun DrawMode(
    picture: PictureOpen,
    strokes: List<Drawing.Stroke>,
    tool: Drawing.Tool?,
    colour: Int,
    width: Float,
    onTool: (Drawing.Tool?) -> Unit,
    onColour: (Int) -> Unit,
    onWidth: (Float) -> Unit,
    onUndo: () -> Unit,
    onAdd: (Drawing.Stroke) -> Unit,
    onErase: (Int) -> Unit,
) {
    // **Each state says which one it is.** Saying "could not be opened" while a picture is still
    // opening is a screen telling the person their file is broken when it is not.
    when (picture) {
        PictureOpen.Loading -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return
        }
        PictureOpen.Unreadable -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "This picture could not be opened, so it cannot be drawn on.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return
        }
        is PictureOpen.Ready -> Unit
    }
    val bitmap = (picture as PictureOpen.Ready).bitmap
    val shown = remember(bitmap) { bitmap.asImageBitmap() }
    /**
     * **What the size field holds**, kept separate from [width] rather than derived from it.
     *
     * A field whose text was recomputed from the width would fight the person typing "1" on the way to
     * "12": the value clamps and re-renders between keystrokes. So the text is what they typed, the
     * width is what the drawing uses, and the two are joined by [Drawing.widthOf] — which is also where
     * the refusal lives, so a field holding something unusable leaves the width where it was.
     */
    var typed by remember { mutableStateOf(width.toInt().toString()) }

    Column(modifier = Modifier.fillMaxSize()) {
        // **The tools are icons, and the eraser is one of them.** The operator: "turn erase into icon
        // and put one color picker button on the same row after it." A row of words beside a canvas is
        // a row a person has to read; a pen, a line, a box and an eraser are four shapes that can be
        // recognised without reading.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            for (one in Drawing.Tool.entries) {
                // **Tapping the chosen tool takes it off.** That is the only way into "no tool", which
                // is the look-and-move mode: one finger pans, two pinch. A separate "Hand" tool would
                // be a fifth thing to learn, and the operator asked for the toggle instead.
                //
                // **Five things share this row** — four tools, the colour, the size field — so the
                // glyphs are 20dp and the chips are as narrow as the padding allows.
                FilterChip(
                    selected = tool == one,
                    onClick = {
                        Trace.tap("draw tool", one.name.lowercase())
                        onTool(if (tool == one) null else one)
                    },
                    label = { ToolGlyph(one) },
                    // **Narrow, because four of them and a colour button share the row.** The default
                    // chip padding is sized for a word; a 20dp glyph needs about half of it.
                    modifier = Modifier.height(36.dp),
                )
            }
            Spacer(Modifier.width(4.dp))
            // **One colour button, not four swatches**, and the button shows the colour in force:
            // the operator's "make color picker an icon that shows selected color".
            ColourButton(colour = colour, onColour = { Trace.tap("draw colour", Drawing.hex(it)); onColour(it) })
            Spacer(Modifier.width(4.dp))
            // **The size is a field and nothing else.** The operator: "px size is just a number field
            // that is typed from the keyboard (1-64 px)." The three width buttons that used to sit
            // beside it were three numbers picked by me, in the way of the one number the person
            // wanted to type; the range is said in the field's own label instead.
            OutlinedTextField(
                value = typed,
                onValueChange = { text ->
                    // **Only digits**, so the field cannot hold something it would then have to
                    // refuse. The range is enforced by [Drawing.widthOf], which clamps — a person
                    // typing 900 meant "as wide as it goes", and a field that silently ignored them
                    // would be the app typing on their behalf.
                    val clean = text.filter { it.isDigit() }.take(2)
                    typed = clean
                    Drawing.widthOf(clean)?.let { onWidth(it) }
                },
                label = { Text("px", style = MaterialTheme.typography.labelSmall) },
                placeholder = { Text("1-64", style = MaterialTheme.typography.labelSmall) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(72.dp).height(48.dp),
                textStyle = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { Trace.tap("draw undo"); onUndo() }, enabled = strokes.isNotEmpty()) {
                Text("Undo")
            }
        }
        DrawingSurface(
            picture = shown,
            strokes = strokes,
            tool = tool,
            colour = colour,
            width = width,
            modifier = Modifier.fillMaxSize(),
            onAdd = onAdd,
            onErase = onErase,
        )
    }
}

/**
 * The picture itself (§9: "images → shown as the picture, not editable").
 *
 * Three states, and each says which one it is: decoding, the picture, or a plain
 * explanation that this file could not be read. Silently showing nothing would be
 * indistinguishable from a bug.
 */
@Composable
private fun Picture(file: java.io.File, padding: androidx.compose.foundation.layout.PaddingValues) {
    // `null` is a real answer ("this cannot be read"), so it cannot also mean
    // "still decoding" — that conflation showed "could not be read" for the first
    // frame of every picture. A wrapper with three states keeps them apart.
    val state by produceState<PictureState>(initialValue = PictureState.Loading, file) {
        val decoded = Pictures.decode(file)
        value = if (decoded == null) PictureState.Unreadable else PictureState.Ready(decoded)
    }

    when (val current = state) {
        is PictureState.Ready -> Box(
            modifier = Modifier.fillMaxSize().padding(padding).padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = current.image,
                contentDescription = file.name,
                modifier = Modifier.fillMaxWidth(),
                contentScale = ContentScale.Fit,
            )
        }

        PictureState.Loading -> Box(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }

        PictureState.Unreadable -> Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "This picture could not be read on this phone.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                "The file is still here, and can be renamed, moved, viewed in history " +
                    "and deleted. It is not edited here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A file the app cannot show (§9: "anything else → named as unknown; rename, move
 * and delete only").
 *
 * The temptation is to open it in the text field anyway, because most files are
 * text and it would feel more capable. That is the one thing not to do: a `.zip`
 * or a `.pdf` opened as text and saved back has its bytes rewritten, and the user
 * who only wanted to look has lost the file. Saying "not here" costs nothing.
 */
@Composable
private fun CannotEdit(file: java.io.File, padding: androidx.compose.foundation.layout.PaddingValues) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "This file is not text, so it is not opened here.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            "It can still be renamed, moved, viewed in history and deleted. " +
                "Its name ends in ." + file.extension + ", which this app does not edit.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * **The three things a person can do with a file**, as a mode rather than a switch.
 *
 * A single chip reading "Edit" or "View" said what pressing it would do; three named chips say
 * where you are. That difference is the whole of report 29's third request, and it is not
 * cosmetic: with a switch there was nowhere to put a third mode, so Diff had no home.
 */
private enum class Mode { VIEW, EDIT, DIFF, DRAW }

/**
 * What the editor knows about an image file: still opening, open, or not a picture this phone can read.
 *
 * **Three states, because two cannot tell the truth.** With a nullable bitmap, "still decoding" and
 * "cannot be read" are the same value — so a screen says "this picture could not be opened" for the
 * first frames of every picture it opens successfully. The tour picture caught exactly that.
 */
private sealed interface PictureOpen {
    data object Loading : PictureOpen
    data class Ready(val bitmap: android.graphics.Bitmap) : PictureOpen
    data object Unreadable : PictureOpen
}

/**
 * One of the three, selected when it is the mode in force.
 *
 * **`index` is what the segmented control needs and what the caller should not have to count**, so
 * it is derived from the mode itself: the enum's order is the row's order, which is the order a
 * person reads them in — View, then Edit, then Diff, from reading towards changing.
 */
@Composable
private fun SingleChoiceSegmentedButtonRowScope.ModeSegment(
    label: String,
    mode: Mode,
    selected: Mode,
    enabled: Boolean = true,
    onClick: (Mode) -> Unit,
) {
    SegmentedButton(
        selected = selected == mode,
        enabled = enabled,
        onClick = { onClick(mode) },
        shape = SegmentedButtonDefaults.itemShape(index = mode.ordinal, count = Mode.entries.size),
        // **No tick in the cell and no outline round it**, the operator's own words: "remove the in
        // the cell checkmark symbol on selection (color is a good enough marker), also remove the
        // black outline". Material draws both by default - `SegmentedButtonDefaults.Icon` is a
        // checkmark on the active cell, and the group is bordered - and at three words wide the
        // tick took the room the label needed.
        //
        // The colour is what is left, and it is enough: the selected cell is filled with the
        // primary container and the others are transparent, which reads at a glance and survives a
        // theme change because both come from the theme.
        icon = {},
        border = BorderStroke(0.dp, Color.Transparent),
    ) { Text(label) }
}

/**
 * **Diff: two commits, and the file between them.**
 *
 * **The default pair is the newest version and the one before it.** It used to be `HEAD` against the
 * last synced commit, on the reasoning that a person opening a diff here wants to know "what am I
 * about to send" - and that is a real question with a hole in it: a file that has never been pushed
 * has no synced version, so the older end was null and the screen said "there is no version of this
 * file at one of those commits" about a file with a dozen commits behind it.
 *
 * The operator's report: *"diff isnt getting selected even when there is history to compare, fix it.
 * and set it so that by default it shows newest version vs previous one"*. Newest-against-previous
 * always has both ends whenever there are two commits, which is exactly when the mode means anything.
 *
 * Both ends are replaceable from the file's own history, so comparing two older versions - or two
 * versions that straddle the last sync - is the same act with two other ids.
 *
 * **Nothing is invented when an end is missing.** A file with one commit has no previous version, and
 * a file added since the last sync has no version at the older end; both are said plainly, because a
 * diff against nothing drawn as a normal diff would show every line as an addition and look like a
 * real comparison.
 */
@Composable
private fun DiffMode(viewModel: AppViewModel, path: String) {
    // **The view model owns the pair, and the screen only names it.** This used to be local state
    // seeded from the view model, which read the pair in force when the composable was first drawn:
    // the second file opened in a session got the first file's two commits, and the guard meant to
    // fix that could never fire because the local value was already set. The operator saw it as
    // "inside diff editor mode from and to versions dont react to touches".
    val from = viewModel.diffFrom
    val to = viewModel.diffTo
    LaunchedEffect(path) { viewModel.openDiff(path) }

    // **Keyed on the two ids, so a pick is redrawn.** A file at a revision is a repository read, and
    // a screen that re-read it on every recomposition would be doing that work for a frame; the key
    // is what makes it once per pair rather than once per draw.
    val before = remember(path, from) { viewModel.fileAtRevision(path, from) }
    val after = remember(path, to) { viewModel.fileAtRevision(path, to) }
    val choices = viewModel.diffChoices

    Column(modifier = Modifier.fillMaxSize()) {
        // **The picker, and it says which end each is.** "From" and "to" rather than "a" and "b":
        // a diff reads forwards, and the two ids a person picks are the older and the newer.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Picker("From", choices, from) { viewModel.chooseDiffFrom(it) }
            Picker("To", choices, to) { viewModel.chooseDiffTo(it) }
        }
        HorizontalDivider()
        when {
            choices.isEmpty() -> Text(
                "This file has no history yet, so there is nothing to compare.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp),
            )
            // **A file with no older version, and why**, which is a different sentence depending on
            // how the person got here. From the changes list, the missing end is the last sync — the
            // file was added since. From the Diff button, it is the version before this one, which
            // means the file has a single commit. Saying "new since the last sync" in the second
            // case would be a true-sounding sentence about something the person did not ask.
            before == null && after != null -> Column {
                Text(
                    if (from == null) {
                        "This file is new since the last sync, so every line is an addition."
                    } else {
                        "There is no earlier version of this file, so every line is an addition."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(8.dp),
                )
                DiffPreview(after.lines().map { GitService.DiffLine(GitService.DiffLine.Kind.ADDED, it) })
            }
            before != null && after != null -> DiffPreview(GitService.diffLines(before, after))
            // **The one case left is an end that names a commit the file is not in**, which happens
            // when a person picks an older commit from before the file was added. Named as such.
            else -> Text(
                "The file is not in one of those two versions. Pick another above.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

/**
 * One end of the comparison: a menu of the file's own commits.
 *
 * **Disabled when there is nothing to choose**, rather than a button that opens an empty menu. The
 * operator's second report on this screen was "inside diff editor mode from and to versions dont
 * react to touches" — and one of the reasons a control does not react is that there is nothing
 * behind it. A disabled button says "not yet"; an empty menu says "the app is broken".
 */
@Composable
private fun Picker(label: String, choices: List<GitService.CommitInfo>, selected: String?, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, enabled = choices.isNotEmpty()) {
            val shown = choices.firstOrNull { it.id == selected }?.shortId ?: "none"
            Text("$label: $shown")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (commit in choices) {
                DropdownMenuItem(
                    text = { Text("${commit.shortId} · ${commit.message.lineSequence().first()}") },
                    onClick = {
                        open = false
                        onPick(commit.id)
                        Trace.tap("diff commit", commit.shortId)
                    },
                )
            }
        }
    }
}

/**
 * **The four tools as shapes**, drawn rather than imported.
 *
 * `Edit`, `ShowChart` and the rest of the set this needs live in material-icons-extended, and this app
 * does not carry that for four glyphs — the same reason [LinkedGlyph] and [UnsyncGlyph] are drawn.
 *
 * Each is the *mark it makes*: a pen is a nib, a line is a diagonal, a box is a frame, and an eraser is
 * a tilted block with the corner it rubs with. A person recognises those faster than they read "Erase",
 * which is the whole point of the operator's request.
 */
@Composable
private fun ToolGlyph(tool: Drawing.Tool) {
    val tint = MaterialTheme.colorScheme.onSurface
    // **Both colours read outside the `Canvas`, because a `DrawScope` is not a composable.** Asking
    // `MaterialTheme` inside it is a compile error — and a compile error is the good version of that
    // mistake; the bad one would be a hard-coded colour that ignores the theme.
    val gap = MaterialTheme.colorScheme.surface
    val label = when (tool) {
        Drawing.Tool.PEN -> "Pen"
        Drawing.Tool.LINE -> "Line"
        Drawing.Tool.BOX -> "Box"
        Drawing.Tool.ERASER -> "Erase"
    }
    Canvas(modifier = Modifier.size(20.dp).semantics { contentDescription = label }) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.12f
        when (tool) {
            // A nib: a filled triangle with a slit, which is what a pen tip looks like from above.
            Drawing.Tool.PEN -> {
                val tip = Path().apply {
                    moveTo(w * 0.5f, h * 0.94f)
                    lineTo(w * 0.14f, h * 0.30f)
                    lineTo(w * 0.86f, h * 0.30f)
                    close()
                }
                drawPath(tip, tint)
                drawLine(
                    gap,
                    Offset(w * 0.5f, h * 0.30f),
                    Offset(w * 0.5f, h * 0.74f),
                    stroke * 0.5f,
                )
            }
            Drawing.Tool.LINE -> drawLine(
                tint,
                Offset(w * 0.14f, h * 0.86f),
                Offset(w * 0.86f, h * 0.14f),
                stroke,
                StrokeCap.Round,
            )
            Drawing.Tool.BOX -> drawRect(
                tint,
                topLeft = Offset(w * 0.14f, h * 0.22f),
                size = androidx.compose.ui.geometry.Size(w * 0.72f, h * 0.56f),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            // **A tilted block with a flat end**, which is how an eraser is drawn everywhere: the
            // flat end is the part that rubs, and the tilt is what tells it from a rectangle.
            Drawing.Tool.ERASER -> {
                val body = Path().apply {
                    moveTo(w * 0.20f, h * 0.76f)
                    lineTo(w * 0.62f, h * 0.22f)
                    lineTo(w * 0.88f, h * 0.42f)
                    lineTo(w * 0.46f, h * 0.94f)
                    close()
                }
                drawPath(body, tint)
                drawLine(
                    gap,
                    Offset(w * 0.34f, h * 0.62f),
                    Offset(w * 0.60f, h * 0.80f),
                    stroke * 0.7f,
                )
            }
        }
    }
}

/**
 * **One button showing the colour in force, which opens the wheel.**
 *
 * The operator: "put one color picker button on the same row after it. And make it a color wheel popup
 * window - look if there is a premade material for that."
 *
 * **There is not one, and this is why.** Compose Material 3 has no colour picker: `MaterialColors` is a
 * palette of *roles* (primary, surface) chosen by a design system, not a wheel a person points at. The
 * nearest thing is the deprecated `androidx.compose.material` `ColorPicker`, which never existed. So the
 * swatch is the button and the wheel is the platform's own picker, which is the same wheel every other
 * app on the person's phone shows.
 */
@Composable
private fun ColourButton(colour: Int, onColour: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    // **The button *is* the swatch**: a filled circle in the colour in force, ringed so white is
    // visible against a white toolbar. That is the operator's "make color picker an icon that shows
    // selected color" — the colour is not described, it is shown.
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(colour))
            .border(2.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .clickable { open = true }
            .semantics { contentDescription = "Colour" },
    )
    if (open) {
        ColourWindow(
            current = colour,
            onPick = { open = false; onColour(it) },
            onDismiss = { open = false },
        )
    }
}

/**
 * **The swatch window: every colour, then Select.**
 *
 * The operator: "when you press it a color selection window pops up with several basic color swatches
 * with select button (black, white + basic colors)." So there are two states and one button — a tap
 * *picks* a swatch (the ring moves), and **Select** closes the window with it. Picking and closing on
 * one tap would make a mis-tap a drawing decision with no way to see it first; this way the chosen
 * swatch is visible, named, before anything changes.
 *
 * **It is a `Dialog` and not a `Popup`**, because a `Popup` is dismissed by a tap anywhere outside it,
 * which is the same gesture as "changed my mind" and would make the Select button decorative.
 */
@Composable
private fun ColourWindow(current: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    var chosen by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Colour") },
        text = {
            Column {
                // **Two rows of five, which is the grid these ten fall into naturally**: dark and
                // light at the far left, then the six hues, then brown and grey. A single row of ten
                // 36dp swatches would be 400dp wide, wider than the dialog.
                for (row in Drawing.COLOURS.chunked(5)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (one in row) {
                            Swatch(
                                colour = one,
                                selected = chosen == one,
                                onClick = { chosen = one },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    // **Named, because ten colours are not self-describing.** The operator asked for
                    // black, white and "basic colors"; the name is how a person checks they picked the
                    // one they meant before pressing Select.
                    Drawing.nameOf(chosen),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onPick(chosen) }) { Text("Select") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun Swatch(colour: Int, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            // **The selection is a ring outside the colour, not a tick inside it.** A tick drawn in a
            // fixed colour disappears on white and on black — which are two of the ten.
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
            )
            .padding(4.dp)
            .clip(CircleShape)
            .background(Color(colour))
            .clickable(onClick = onClick),
    )
}
