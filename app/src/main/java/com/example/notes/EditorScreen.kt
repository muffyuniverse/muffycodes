package com.example.notes

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The editor: the file's text, and nothing else.
 *
 * There is no title field and no preview (§8): the file's name **is** its title,
 * and every text file opens here however it is named. What is written is saved
 * as it is typed, and each save becomes a version in the history.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    viewModel: AppViewModel,
    file: java.io.File,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val kind = viewModel.workspace?.kindOf(file) ?: EntryKind.TEXT
    var text by remember(file) { mutableStateOf(viewModel.textOf(file)) }
    var menu by remember { mutableStateOf(false) }

    // Every pause in typing becomes a save, and every save becomes a version.
    //
    // Only for text. The state starts at `textOf(file)`, which reads the file as
    // UTF-8 — and for a picture or an unknown file that is already a lossy
    // interpretation. Saving compared against it would have written the mangled
    // reading straight back over the file, destroying exactly the file this screen
    // exists to protect. So the effect does nothing at all unless the file is text.
    LaunchedEffect(text, kind) {
        if (kind == EntryKind.TEXT && text != viewModel.textOf(file)) {
            delay(400)
            viewModel.saveEditor(text)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    // "Done", not an arrow. Leaving the editor is not going back:
                    // the writing is saved, and what a person has finished doing is
                    // done. An arrow said "undo", which is the one thing it never
                    // did.
                    TextButton(onClick = onBack) { Text("Done") }
                },
                title = {
                    Text(
                        file.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    TextButton(onClick = onOpenHistory) { Text("History") }
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Rename…") },
                            onClick = {
                                menu = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = {
                                menu = false
                                onDelete()
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // No sync row here. The editor is a page of writing, not a place where
            // projects are managed: a line of account, project and "3 changed" above
            // the text was the app talking over the thing being written.
            //
            // The outer Column has taken the top padding, so the content must not
            // add it again — doing so pushed the text field over the slim row.
            val content = PaddingValues(top = 0.dp)
            when (kind) {
                EntryKind.IMAGE -> Picture(file = file, padding = content)
                EntryKind.UNKNOWN -> CannotEdit(file = file, padding = content)

                else -> TextField(
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
            }
        }
    }
}

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
