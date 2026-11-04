package com.example.notes

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File

/**
 * The phone's Downloads folder, as the three acts a tool needs.
 *
 * ## Why this is an interface and not a `Context` in [AgentTools]
 *
 * The same reason [Browser] and [Background] are injected: a tool set built in a test must not need
 * a phone. `MediaStore` needs a `Context`, and the whole point of the test suite here is that a rule
 * can be checked without one. So the rules — what a name may be, how big a file may be, where a
 * download lands — live in [AgentTools] and are tested against a fake; only [MediaStoreDownloads] is
 * untested, and it is the thin part.
 *
 * ## The three acts, and why each exists
 *
 * - **`list`** answers what is there. The operator's reason is not tidiness: *"this way agent inside
 *   muffycodes can read logs from some other debug application and fix bugs"* — an agent that cannot
 *   see the folder cannot find the log another app wrote.
 * - **`read`** copies one file out, **into the project**, because everything an agent reads has to
 *   be somewhere it may read. A download is never read in place.
 * - **`write`** is the old `downloads` command: one project file out to the phone.
 *
 * **There is no delete.** Removing a file a person put there is their act, not an agent's; the tool
 * set has no verb for it on purpose.
 */
interface PhoneDownloads {

    /** One file as the listing answers it: enough to choose, never the contents. */
    data class Item(val name: String, val bytes: Long, val at: Long)

    /** Everything in Downloads, newest first. */
    fun list(): Result<List<Item>>

    /**
     * Copies [name] from Downloads to [into], answering how many bytes landed.
     *
     * **A copy, not a move.** The file stays in Downloads, because the person may still want it and
     * an agent that consumed a download would be an agent that deleted something.
     */
    fun read(name: String, into: File): Result<Long>

    /** Copies [file] into Downloads, answering where it landed. */
    fun write(file: File): Result<Export.Done>

    /** Nothing, for a tool set that was built without a decision about Downloads. */
    object NONE : PhoneDownloads {
        private fun said(what: String) = Result.failure<Nothing>(
            IllegalStateException(
                "This app was not given a way to reach Downloads, so $what is not possible here.",
            ),
        )

        override fun list(): Result<List<Item>> = said("listing what is in Downloads")

        override fun read(name: String, into: File): Result<Long> = said("copying out of Downloads")

        override fun write(file: File): Result<Export.Done> = said("putting a file in Downloads")
    }
}

/**
 * [PhoneDownloads] over `MediaStore`, for Android 10 and later.
 *
 * ## Why `MediaStore` and not a path
 *
 * Since Android 10 an app cannot write to `Download/` as a plain directory without a permission
 * this app does not ask for. `MediaStore` is the supported route, and it is also what makes the
 * listing honest: a row exists because an app registered a file, which is a stronger statement than
 * "a file is at this path".
 *
 * ## The fact that would otherwise be a mystery
 *
 * **A file written to `Download/` by raw path may have no `MediaStore` row**, and so will not appear
 * in [list] — the store learns about files when it is told, and a shell or an old app may never have
 * told it. [AgentTools] says this in the tool's own description rather than leaving an agent to
 * conclude a file does not exist. There is no repair here: a scan of every file on the phone is a
 * permission the app does not have.
 */
class MediaStoreDownloads(context: Context) : PhoneDownloads {

    private val app: Context = context.applicationContext

    override fun list(): Result<List<PhoneDownloads.Item>> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return Result.failure(
                IllegalStateException(
                    "This phone is too old to read Downloads without a permission the app does not " +
                        "ask for.",
                ),
            )
        }
        return try {
            Result.success(query())
        } catch (e: Throwable) {
            Result.failure(IllegalStateException("Downloads could not be read: " + explain(e)))
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun query(): List<PhoneDownloads.Item> {
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.SIZE,
            MediaStore.Downloads.DATE_MODIFIED,
        )
        val found = mutableListOf<PhoneDownloads.Item>()
        // **Newest first, and off the store's own clock.** `DATE_MODIFIED` seconds since the epoch,
        // sorted by the database rather than here, so a thousand files are a cursor and not a list
        // the app sorts while the person waits.
        app.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Downloads.DATE_MODIFIED} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(1)?.trim().orEmpty()
                if (name.isEmpty()) continue
                found.add(
                    PhoneDownloads.Item(
                        name = name,
                        bytes = cursor.getLong(2),
                        // The store reports seconds; the app speaks milliseconds everywhere else, so
                        // the conversion happens here — once, at the edge — rather than in a caller
                        // that would have to remember it.
                        at = cursor.getLong(3) * 1000,
                    ),
                )
            }
        }
        return found
    }

    override fun read(name: String, into: File): Result<Long> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return Result.failure(
                IllegalStateException("This phone is too old to read Downloads."),
            )
        }
        val uri = try {
            findBy(name)
        } catch (e: Throwable) {
            return Result.failure(
                IllegalStateException("Downloads could not be read: " + explain(e)),
            )
        } ?: return Result.failure(IllegalStateException("There is no file called \"$name\" in Downloads."))
        return try {
            into.parentFile?.mkdirs()
            val copied = app.contentResolver.openInputStream(uri)?.use { input ->
                into.outputStream().use { output -> input.copyTo(output) }
            } ?: return Result.failure(
                IllegalStateException("\"$name\" is in Downloads but could not be opened."),
            )
            Result.success(copied)
        } catch (e: Throwable) {
            // **A half-written file is removed**, because a truncated copy is one an agent reads as
            // the whole thing — the same rule `fetch` follows for a download that is too big.
            try {
                into.delete()
            } catch (t: Throwable) {
                // Nothing to clean up.
            }
            Result.failure(IllegalStateException("\"$name\" could not be copied: " + explain(e)))
        }
    }

    /** The row whose display name is exactly [name], or null. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun findBy(name: String): Uri? = app.contentResolver.query(
        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
        arrayOf(MediaStore.Downloads._ID),
        "${MediaStore.Downloads.DISPLAY_NAME} = ?",
        arrayOf(name),
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            ContentUris.withAppendedId(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                cursor.getLong(0),
            )
        } else {
            null
        }
    }

    override fun write(file: File): Result<Export.Done> = Export.copyToDownloads(app, file)

    private fun explain(e: Throwable): String =
        e.message ?: e.javaClass.simpleName
}
