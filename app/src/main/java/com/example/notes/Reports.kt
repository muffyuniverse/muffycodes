package com.example.notes

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where a problem report goes so that a person can hand it to somebody.
 *
 * ## Why Downloads, and not the app's own folder
 *
 * The app keeps everything it owns in a private folder that is invisible to every other
 * app on the phone — which is right for notes and useless for a report, whose entire
 * purpose is to leave. **Downloads** is the one place a person can already reach with
 * the file manager they have, plug into a computer, or attach to a message. So a report
 * is written there, in its own folder, named so that a set of them sorts in the order
 * they were made.
 *
 * ## Why no permission is asked for
 *
 * On Android 10 and later, an app may add files to Downloads through `MediaStore`
 * without any storage permission at all: it is writing *its own* file into a shared
 * collection, which the platform allows precisely because it needs no access to anybody
 * else's. This app asks for exactly one permission — the network — and a debugging
 * button is not worth a second one, so below Android 10 the report falls back to the
 * app's own external folder and says where it went instead of asking for anything.
 *
 * Nothing here throws: a report that could not be written must not take the app down
 * on the way to telling somebody that something else went wrong.
 */
object Reports {

    /** The folder in Downloads the reports collect in. */
    const val FOLDER = "MuffyCodes reports"

    /** What became of an attempt to save one. */
    data class Saved(
        /** Where it is, in words a person can act on, or null when it could not be. */
        val where: String?,
        /** True when it is in Downloads, which is the case that needs no explanation. */
        val shared: Boolean,
    ) {
        val ok: Boolean get() = where != null
    }

    /**
     * `report-2026-10-24-1653-picture-in-a-note.md` — sorts in the order made.
     *
     * [what] is "report" or "trace": a person writing down what broke and the app's
     * own record of what it did are different documents, and they should be told apart
     * in a folder listing before either is opened.
     */
    fun name(what: String = "report", at: Long = System.currentTimeMillis(), slug: String = ""): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date(at))
        val clean = slug.trim().lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .trim('-')
            .take(40)
            .replace(Regex("-+"), "-")
        return if (clean.isEmpty()) "$what-$stamp.md" else "$what-$stamp-$clean.md"
    }

    /**
     * Writes [text] into Downloads and answers with where it landed.
     *
     * [slug] is a word or two from the person's own sentence, so that a folder full of
     * reports can be told apart by name before any of them is opened.
     */
    fun save(
        context: Context,
        text: String,
        slug: String = "",
        what: String = "report",
        at: Long = System.currentTimeMillis(),
    ): Saved {
        val file = name(what, at, slug)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val stored = try {
                intoDownloads(context, file, text)
            } catch (t: Throwable) {
                null
            }
            // A failure here is not the end: the app's own external folder is still a
            // place the person can reach with a file manager, and a report that lands
            // somewhere is better than one that lands nowhere.
            if (stored != null) return Saved("Download/$FOLDER/$file", true)
        }
        return fromPrivate(context, file, text)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun intoDownloads(context: Context, name: String, text: String): String? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/markdown")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            // Not hidden and not pending: the file is complete the moment it is written,
            // and `IS_PENDING` would leave it invisible to a file manager until some
            // later app happened to finish it.
            put(MediaStore.Downloads.IS_PENDING, 0)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
            name
        } catch (t: Throwable) {
            try {
                resolver.delete(uri, null, null)
            } catch (ignored: Throwable) {
                // A half-written report is worse than none: deleting it failed, and
                // there is nothing else to try.
            }
            null
        }
    }

    /**
     * Before Android 10, Downloads is not writable without a permission, so the report
     * goes to the app's own external folder — still reachable over a cable — and the
     * answer says exactly where, because "somewhere" is not an answer.
     */
    private fun fromPrivate(context: Context, name: String, text: String): Saved {
        val dir = try {
            context.getExternalFilesDir("reports")
        } catch (t: Throwable) {
            null
        } ?: return Saved(null, false)
        return try {
            dir.mkdirs()
            val target = File(dir, name)
            target.writeText(text)
            Saved(target.absolutePath, false)
        } catch (t: Throwable) {
            Saved(null, false)
        }
    }

    /**
     * The live trace's one file, replaced rather than multiplied.
     *
     * A running log written every few seconds as `report-…` files would fill the folder
     * with hundreds of them in an afternoon and make the one that mattered impossible
     * to find. So the live trace is a single named file, overwritten in place: whatever
     * is in it is the most recent state of the app, which is exactly what it is for.
     */
    const val LIVE = "live-trace.md"

    /**
     * Writes the live trace, replacing the previous one if there is one.
     *
     * Replacing means finding the entry MediaStore already has and opening it for
     * truncating write; inserting again under the same name would leave the phone with
     * `live-trace.md` and `live-trace(1).md` and the loop reading last hour's file.
     * On anything that goes wrong it falls back to inserting, which at worst leaves one
     * duplicate rather than no trace at all.
     */
    fun saveLive(context: Context, text: String): Saved {
        val name = LIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val resolver = context.contentResolver
                val uri = findLive(context, name)
                if (uri != null) {
                    resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray()) }
                    return Saved("Download/$FOLDER/$name", true)
                }
                val added = intoDownloads(context, name, text)
                if (added != null) return Saved("Download/$FOLDER/$name", true)
            } catch (t: Throwable) {
                // Falls through to the app's own folder, which is still readable over a
                // cable.
            }
        }
        return fromPrivate(context, name, text)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun findLive(context: Context, name: String): android.net.Uri? {
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.RELATIVE_PATH,
        )
        val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ?"
        val args = arrayOf(name)
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            args,
            null,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val path = cursor.getString(2).orEmpty().trimEnd('/')
                if (path.endsWith(FOLDER)) {
                    return android.content.ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        id,
                    )
                }
            }
        }
        return null
    }

    /**
     * The file's own name as a slug: the first few words the person wrote.
     *
     * Trailing filler is dropped — `sync keeps failing on` describes nothing that `sync
     * keeps failing` does not, and a name ending in "on" reads like a sentence that was
     * cut off.
     */
    fun slugOf(note: String): String {
        val words = note.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.split(Regex("\\s+"))
            ?.filter { it.isNotBlank() }
            .orEmpty()
            .take(4)
            .toMutableList()
        val filler = setOf(
            "on", "in", "at", "to", "for", "and", "or", "the", "a", "an", "of", "is",
            "it", "its", "with", "when", "while", "my",
        )
        while (words.size > 1 && words.last().lowercase().trim('.', ',', '!', '?') in filler) {
            words.removeAt(words.lastIndex)
        }
        return words.joinToString("-")
    }
}
