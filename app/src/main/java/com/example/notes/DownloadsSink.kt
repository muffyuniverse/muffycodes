package com.example.notes

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File

/**
 * The log's home on a phone: one file in Downloads, appended to as things happen.
 *
 * ## Why Downloads
 *
 * The app keeps everything else in a folder no other app can see, which is right for
 * notes and useless for a log whose entire purpose is to be read from outside. Downloads
 * is the one place a person can already reach with the file manager they have, a cable,
 * or a chat app.
 *
 * ## Why no permission
 *
 * On Android 10 and later an app may add files to Downloads through `MediaStore` without
 * asking for anything: it is writing *its own* file into a shared collection. This app
 * asks for exactly one permission — the network — and a debugging aid is not worth a
 * second. Below Android 10 it falls back to the app's own external folder and says where,
 * because an answer that names nothing is how "restore deleted did nothing" felt.
 *
 * ## Why append
 *
 * Because the file is evidence. Rewriting it on a timer meant an event seen a moment late
 * had already been overwritten by the next write — which is the failure this whole
 * rewrite exists to end. A line, once written, stays written; the only thing that ever
 * removes lines is the trim inside [Trace.FileSink], and that leaves a marker where it
 * happened.
 */
class DownloadsSink(context: Context) : Trace.Sink {

    private val app: Context = context.applicationContext

    /** The one file. Found once, then kept. */
    private var uri: Uri? = null

    /** Where it landed, in words a person can find. */
    private var path: String? = null

    override fun append(lines: List<String>) {
        if (lines.isEmpty()) return
        val text = lines.joinToString("") { it + "\n" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val found = if (uri != null) uri else find()?.first
            if (found != null) {
                if (write(found, text, first = false)) {
                    uri = found
                    path = "Download/$FOLDER/${name(found)}"
                    return
                }
                // The entry went away (the person deleted the folder), or the row is one
                // whose file was never made. Either way, stop trusting it and make a real
                // one rather than writing nowhere for the rest of the session.
                uri = null
            }
            val made = createThenWrite(text)
            if (made != null) {
                uri = made
                path = "Download/$FOLDER/${name(made)}"
                return
            }
        }
        intoPrivate(text)
    }

    override fun read(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val target = uri ?: find()?.first
            if (target != null) {
                uri = target
                return read(target)
            }
        }
        return privateFile()?.takeIf { it.isFile }?.let {
            try {
                it.readText()
            } catch (t: Throwable) {
                null
            }
        }
    }

    override fun where(): String? = path ?: privateFile()?.absolutePath

    override fun clear() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val target = uri ?: find()?.first
            if (target != null) {
                try {
                    // A null stream is a clear that did not happen; the private copy
                    // below is emptied either way, so the ring still starts clean.
                    app.contentResolver.openOutputStream(target, "wt")?.close()
                } catch (t: Throwable) {
                    // Falls through to the private copy, which may also be the home.
                }
            }
        }
        try {
            privateFile()?.writeText("")
        } catch (t: Throwable) {
            // Nothing to do.
        }
    }

    /**
     * Adds text to an existing file, and never loses what was there.
     *
     * **"wa" needs a file that already exists.** On a `MediaStore` row that has just been
     * inserted and never written, append can throw — and when it did, the folder appeared
     * in Downloads and the log did not: a session's evidence went to the private fallback
     * while the app reported the other path. So an append that fails is not the end of it:
     * the text already in the file is read, the new text is put after it, and the whole
     * thing is written. Slower, and only on the path that was already broken.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun write(target: Uri, text: String, first: Boolean): Boolean {
        if (!first && put(target, text, "wa")) return true
        // Either the first write, or the append did not happen: put the whole file back,
        // old text and new. Only reached on a path that has already failed once.
        return put(target, (read(target) ?: "") + text, "w")
    }

    /**
     * Writes [text] to [target] in [mode], and **says no when there is no stream**.
     *
     * This is the bug that made a folder appear in Downloads and stay empty. The old
     * version read `openOutputStream(target, "wa")?.use { it.write(...) }` and then
     * `return true`: when the resolver answered `null` — which it does for a `MediaStore`
     * row that has no file behind it yet — the `?.` skipped the write, nothing was thrown,
     * and the function reported success. The caller then believed the line was safe and
     * did not fall back to anywhere. A null stream is a failure, and it has to be said
     * out loud to be handled.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun put(target: Uri, text: String, mode: String): Boolean = try {
        val stream = app.contentResolver.openOutputStream(target, mode)
        if (stream == null) {
            false
        } else {
            stream.use { it.write(text.toByteArray()) }
            true
        }
    } catch (t: Throwable) {
        false
    }

    /** Whatever is already in the file, or null when it cannot be read. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun read(target: Uri): String? = try {
        app.contentResolver.openInputStream(target)?.use { it.readBytes().toString(Charsets.UTF_8) }
    } catch (t: Throwable) {
        null
    }

    /**
     * Makes the file and puts the first lines in it.
     *
     * `IS_PENDING` stays set until those lines are written, and only then is cleared: the
     * point of pending is that no other app can read a half-made file, and clearing it
     * before the file existed was the other half of the same bug — a visible row for a
     * file nobody had created yet.
     *
     * A failure deletes the row rather than leaving it: a phantom entry is worse than no
     * entry, because [find] would keep choosing it.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun createThenWrite(text: String): Uri? {
        val made = try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, NAME)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } catch (t: Throwable) {
            null
        } ?: return null

        if (!write(made, text, first = true)) {
            try {
                app.contentResolver.delete(made, null, null)
            } catch (t: Throwable) {
                // Nothing to clean up.
            }
            return null
        }
        try {
            app.contentResolver.update(
                made,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
        } catch (t: Throwable) {
            // Written but still marked pending: other apps cannot see it yet, which is a
            // smaller problem than a log that is not being written at all.
        }
        return made
    }

    /**
     * The log this phone already has, with the name it is really stored under.
     *
     * **The name is not what was asked for, and assuming it was cost a session's log.**
     * A `MediaStore` row inserted as `trace.log` with a text MIME type comes back as
     * `trace.log.txt`: the store appends an extension for what the type says the file is.
     * Searching for the exact name therefore never found the file this app had made, so
     * every launch inserted a fresh row — `trace.log.txt`, `trace.log (1).txt`,
     * `trace.log (2).txt` — and the one-log promise, and the monitoring that depended on
     * it, quietly became one file per launch.
     *
     * So the search is by prefix, and the newest row wins. That also adopts a file made by
     * an older build, and a file a person renamed within the folder keeps being found.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun find(): Pair<Uri, String>? = try {
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.RELATIVE_PATH,
        )
        app.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?",
            arrayOf("$NAME%"),
            // Newest first: `_ID DESC` is insertion order, which is what "the one that has
            // been appended to lately" means here. Sorting in the query rather than in
            // memory is what makes the newest row the one the cursor sees first.
            "${MediaStore.Downloads._ID} DESC",
        )?.use { cursor ->
            var found: Pair<Uri, String>? = null
            while (cursor.moveToNext() && found == null) {
                val folder = cursor.getString(2).orEmpty().trimEnd('/')
                if (folder.endsWith(FOLDER)) {
                    found = android.content.ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        cursor.getLong(0),
                    ) to cursor.getString(1).orEmpty()
                }
            }
            found
        }
    } catch (t: Throwable) {
        null
    }

    /** The display name behind [target], asked of the store rather than assumed. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun name(target: Uri): String = try {
        app.contentResolver.query(target, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else NAME }
            ?.takeIf { it.isNotBlank() } ?: NAME
    } catch (t: Throwable) {
        NAME
    }

/** Before Android 10, or when Downloads cannot be written at all. */
    private fun intoPrivate(text: String) {
        val file = privateFile() ?: return
        try {
            file.parentFile?.mkdirs()
            file.appendText(text)
        } catch (t: Throwable) {
            // Nowhere to write. The screen still shows the ring, which is why the ring
            // exists as well as the file.
        }
    }

    private fun privateFile(): File? = try {
        app.getExternalFilesDir("trace")?.let { File(it, NAME) }
    } catch (t: Throwable) {
        null
    }

    companion object {
        /** The folder inside Downloads. */
        const val FOLDER = "MuffyCodes log"

        /** The file. One, always, appended to. */
        const val NAME = "trace.log"
    }
}
