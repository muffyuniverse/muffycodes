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
            val existing = uri ?: find()
            if (existing != null) {
                if (appendTo(existing, text)) {
                    uri = existing
                    return
                }
                // The entry went away (the person deleted the folder). Fall through and
                // make a new one rather than writing nowhere for the rest of the session.
                uri = null
            }
            val made = create()
            if (made != null && appendTo(made, text)) {
                uri = made
                path = "Download/$FOLDER/$NAME"
                return
            }
        }
        intoPrivate(text)
    }

    override fun read(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val target = uri ?: find()
            if (target != null) {
                uri = target
                return try {
                    app.contentResolver.openInputStream(target)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    }
                } catch (t: Throwable) {
                    null
                }
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
            val target = uri ?: find()
            if (target != null) {
                try {
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

    /** Appended rather than rewritten: the mode is the whole of it. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun appendTo(target: Uri, text: String): Boolean = try {
        app.contentResolver.openOutputStream(target, "wa")?.use {
            it.write(text.toByteArray())
        }
        true
    } catch (t: Throwable) {
        false
    }

    /**
     * The first write makes the file, complete and visible.
     *
     * `IS_PENDING` is set and then cleared rather than left at 0 from the start: a file
     * another app reads halfway through its first line is worse than one that appears a
     * second later.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun create(): Uri? = try {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, NAME)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val made = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (made != null) {
            app.contentResolver.update(
                made,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
        }
        made
    } catch (t: Throwable) {
        null
    }

    /** The existing log, if this phone already has one from an earlier session. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun find(): Uri? = try {
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.RELATIVE_PATH,
        )
        app.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Downloads.DISPLAY_NAME} = ?",
            arrayOf(NAME),
            null,
        )?.use { cursor ->
            var found: Uri? = null
            while (cursor.moveToNext()) {
                val folder = cursor.getString(2).orEmpty().trimEnd('/')
                if (folder.endsWith(FOLDER)) {
                    found = android.content.ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        cursor.getLong(0),
                    )
                }
            }
            found
        }
    } catch (t: Throwable) {
        null
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
