package com.example.notes

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The editor: the file's name in the top row, and its text below.
 *
 * ```text
 * [<-]  milk.txt                     <- the top row, and nothing else
 *
 * History   [ Edit | View ]   [ Save ]   <- the second row, and it scrolls
 * ```
 *
 * **Two rows, because the top row belongs to the file and the second to what can
 * be done with it.** Four buttons wedged beside the name left the name — the one
 * thing the screen is about — a few characters wide.
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
    val kind = viewModel.workspace?.kindOf(file) ?: EntryKind.TEXT
    val locked = viewModel.isLocked(file)
    // Keyed on the *session*, not on the file: renaming the file you are writing
    // changes its path, and resetting the text on that would throw away everything
    // typed so far. Opening another file starts a new session and resets both.
    val session = viewModel.editorSession
    var text by remember(session) { mutableStateOf(viewModel.textOf(file)) }
    // What is on disk, so that Save can say whether there is anything to save.
    var saved by remember(session) { mutableStateOf(text) }
    var name by remember(session) { mutableStateOf(file.name) }
    // A locked file is read-only, so it opens in View. Every other text file opens
    // ready to be written, which is what opening a note is for.
    var writing by remember(session) { mutableStateOf(!locked) }
    var focused by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current

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

    /** Leaves the editor, dropping anything not saved. */
    fun leave() {
        // Whether it is showing or writing: text that was typed and not saved is
        // text that is about to be lost, and the person should be told.
        val dropped = kind == EntryKind.TEXT && text != saved
        focus.clearFocus()
        onBack()
        if (dropped) viewModel.message = "Left without saving"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    // Back, and it means back: what was typed and not saved goes.
                    IconButton(onClick = { leave() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back without saving",
                        )
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
                            onValueChange = { if (!locked) name = it },
                            singleLine = true,
                            readOnly = locked,
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
                actions = { ReportButton(onReport) },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // The second row. It scrolls sideways so that it can carry what it
            // needs to without squeezing the text into a column four buttons wide —
            // and because a phone in a large font has no room for all of it.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = {
                    // The name is committed first: a version list is a list of this
                    // file's versions, and the file may just have been given another
                    // name.
                    commitName()
                    onOpenHistory()
                }) { Text("History") }

                // View | Edit. One switch rather than two buttons, and it says which
                // of the two you are in — "View" a person can read, "Editing" a
                // person has to work out.
                if (kind == EntryKind.TEXT) {
                    FilterChip(
                        selected = writing,
                        enabled = !locked,
                        onClick = {
                            if (writing) {
                                // Leaving the editor's way of writing does not throw
                                // anything away: what was typed is still on the
                                // screen, and Save is still there.
                                focus.clearFocus()
                                commitName()
                            }
                            writing = !writing
                        },
                        label = { Text(if (writing) "Edit" else "View") },
                    )
                }

                Button(
                    onClick = {
                        // Saved before the name is committed: the text belongs to this
                        // file as it is now, and a rename afterwards carries the file —
                        // with its text — to the new name.
                        viewModel.saveEditor(text)
                        saved = text
                        commitName()
                        focus.clearFocus()
                    },
                    enabled = kind == EntryKind.TEXT && !locked && text != saved,
                ) { Text("Save") }
            }
            HorizontalDivider()

            // No sync row here. The editor is a page of writing, not a place where
            // projects are managed: a line of account, project and "3 changed" above
            // the text was the app talking over the thing being written.
            val content = PaddingValues(top = 0.dp)
            when (kind) {
                EntryKind.IMAGE -> Picture(file = file, padding = content)
                EntryKind.UNKNOWN -> CannotEdit(file = file, padding = content)

                else -> if (writing && !locked) {
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
                    // Reading. A `.md` file is shown as the thing it describes,
                    // because the markdown is not what its author wanted read; every
                    // other text is shown exactly as it is.
                    val scrolling = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                    if (isMarkdown(file)) {
                        // The document's own folder, so a picture written beside the
                        // note is the picture that appears.
                        MarkdownBody(
                            text = text,
                            modifier = scrolling,
                            baseDir = file.parentFile,
                        )
                    } else {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = scrolling,
                        )
                    }
                }
            }
        }
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
