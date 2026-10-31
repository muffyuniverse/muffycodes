package com.example.notes

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Putting a file from a project where the phone can open it.
 *
 * ## Why this is one function and not two
 *
 * A person reaches this from a project's ⋮ menu; the agent reaches it from a `downloads` call. **They
 * must be the same code**, because two implementations of "copy this to Downloads" drift in exactly the
 * ways that are hard to notice: one cleans the name and the other does not, one refuses a folder and the
 * other copies its contents, one overwrites and the other makes `name (1)`. A person who downloaded a
 * file and an agent that downloaded the same file should be able to compare notes and find them
 * identical.
 *
 * ## Why no permission
 *
 * `MediaStore.Downloads` is the modern way to write into Downloads, and it needs **no storage
 * permission on Android 10 and later** — the app inserts a row and writes the bytes the row points at.
 * That is the same mechanism [DownloadsSink] already uses for the trace, which is why this file looks
 * like it.
 *
 * ## What it will not do
 *
 * **It does not export a folder.** A project is a tree, and copying one into Downloads produces a
 * hundred files with names that were unique inside the project and are not unique in a flat folder. A
 * folder export is a zip, and a zip is a different act with its own button — offering it here as
 * "export" would be a promise the format does not keep.
 */
object Export {

    /** Where exports land, inside Downloads. */
    const val FOLDER = "MuffyCodes"

    /** What an export did, in words a screen or a tool can show. */
    data class Done(val name: String, val bytes: Long, val folder: String) {
        fun said(): String = "Saved $name ($bytes bytes) to Downloads/$folder"
    }

    /**
     * Copies [file] into Downloads/MuffyCodes.
     *
     * **The name is the file's own**, and a name that already exists is left to the store, which makes
     * `name (1)`. Overwriting a file a person downloaded yesterday because today's has the same name is
     * a loss they did not ask for and cannot undo; a second copy is visible and deletable.
     */
    fun copyToDownloads(context: Context, file: File): Result<Done> {
        if (!file.isFile) {
            return Result.failure(IllegalStateException("There is no file at ${file.name}"))
        }
        val bytes = try {
            file.readBytes()
        } catch (e: Throwable) {
            return Result.failure(
                IllegalStateException("${file.name} could not be read: ${e.message}"),
            )
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // **Before Android 10 the store cannot be written without a permission this app does not
            // ask for**, and asking for storage access to export one file is a worse trade than saying
            // where the file already is. The project folder is readable by any file manager.
            return Result.failure(
                IllegalStateException(
                    "This phone is too old to write to Downloads without a permission the app does " +
                        "not ask for. The file is at ${file.absolutePath}.",
                ),
            )
        }
        val uri = try {
            insert(context, file)
        } catch (e: Throwable) {
            null
        } ?: return Result.failure(
            IllegalStateException("Downloads could not be written to on this phone"),
        )
        return try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: return Result.failure(IllegalStateException("Downloads gave no stream to write to"))
            finish(context, uri)
            Result.success(Done(file.name, bytes.size.toLong(), FOLDER))
        } catch (e: Throwable) {
            // **A half-written row is worse than none**: it appears in Downloads and will not open.
            try {
                context.contentResolver.delete(uri, null, null)
            } catch (t: Throwable) {
                // Nothing left to clean up.
            }
            Result.failure(
                IllegalStateException("${file.name} could not be saved: ${e.message}"),
            )
        }
    }

    /**
     * The row, marked pending until the bytes are in.
     *
     * **`IS_PENDING` is what keeps a half-copied file out of other apps' sight.** Without it a file
     * manager can open the row between the insert and the write and find nothing there.
     */
    private fun insert(context: Context, file: File): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, mimeOf(file.name))
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    }

    private fun finish(context: Context, uri: Uri) {
        try {
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                null,
                null,
            )
        } catch (t: Throwable) {
            // Written but still pending: other apps cannot see it yet. A smaller problem than losing
            // the file, and the only one left to have.
        }
    }

    /**
     * A type from the name, because the store appends an extension for a type that disagrees.
     *
     * **`text/plain` for a file called `notes.md` makes the store rename it `notes.md.txt`** — the same
     * trap [DownloadsSink] records. `application/octet-stream` is the honest answer for anything this
     * app does not recognise, and the store then leaves the name alone.
     */
    fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "txt", "log" -> "text/plain"
        "md", "markdown" -> "text/markdown"
        "json" -> "application/json"
        "zip" -> "application/zip"
        "apk" -> "application/vnd.android.package-archive"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "pdf" -> "application/pdf"
        else -> "application/octet-stream"
    }
}
