package com.example.notes

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File

/**
 * Bringing a file or a folder in from the phone's Downloads.
 *
 * The picker is the system's, not the app's: Android hands back a `content://`
 * URI and the app reads it as a stream. That is deliberate — the app never asks
 * for storage permission, so it can only ever see what a person just pointed at,
 * and it can only read it, never write back to where it came from.
 *
 * Everything here copies bytes. A file is never opened as text on the way in,
 * because the commonest thing to import is a picture or a PDF and reading those
 * as UTF-8 is how they arrive corrupted.
 *
 * **Names are cleaned on the way in** ([Workspace.cleanName]). A document arrives
 * with whatever name its sender chose — `Holo and Lawrence, Kraft - Spice and
 * Wolf.jpeg` — and a name is a path component in two git repositories and in the
 * folder tree that *is* the account. Cleaning here rather than refusing later
 * means the import always works, and the report says which names changed so the
 * person is not left looking for a file called something else.
 *
 * **Nothing here runs on the UI thread.** A screenshot, a video or an installed
 * `.apk` is tens of megabytes, and copying one inside the picker's answer blocks
 * the window until Android kills the app for not responding — the file lands on
 * disk, and then the app closes, which looks like the import crashed it.
 */
object Imports {

    /** How many files one import will copy, so a runaway tree cannot fill the phone. */
    const val MAX_FILES = 5_000

    /** How deep a picked folder is followed. */
    private const val MAX_DEPTH = 32

    /**
     * What an import did: how many files arrived, and which names were changed.
     *
     * The renames are kept because a name that changes silently is the reason a
     * person reports that their file did not import.
     */
    data class Report(val copied: Int, val renamed: List<Pair<String, String>> = emptyList()) {

        /** One sentence for the screen. */
        fun said(): String {
            val count = when (copied) {
                0 -> "Nothing was imported"
                1 -> "Imported 1 file"
                else -> "Imported $copied files"
            }
            if (renamed.isEmpty()) return count
            val shown = renamed.take(2).joinToString("; ") { (from, to) -> "\"$from\" → $to" }
            val rest = if (renamed.size > 2) " and ${renamed.size - 2} more" else ""
            return "$count — named to fit: $shown$rest"
        }
    }

    /**
     * Copies [uris] into [into], keeping their names (cleaned).
     *
     * A name already in the folder is kept as it is and the new one is numbered —
     * `photo.png` becomes `photo_2.png` — so importing twice never destroys the
     * first copy.
     */
    fun filesInto(context: Context, uris: List<Uri>, into: File): Result<Report> = try {
        if (!into.isDirectory) throw IllegalStateException("That folder is not there any more")
        var copied = 0
        val renamed = mutableListOf<Pair<String, String>>()
        val failures = mutableListOf<String>()
        uris.forEach { uri ->
            val asked = try {
                displayName(context, uri) ?: "imported file"
            } catch (e: Throwable) {
                "imported file"
            }
            try {
                // The folder is asked for again before every file, not once at the
                // start: a copy that takes a while can outlive the folder it is
                // copying into if the same account is being changed on another
                // screen, and the failure for that is a bare "open failed: ENOENT"
                // that names neither the file nor the reason.
                into.mkdirs()
                if (!into.isDirectory) {
                    throw IllegalStateException("The folder is no longer there")
                }
                val target = freeName(into, asked)
                if (target.name != asked.trim()) renamed += asked.trim() to target.name
                val input = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException(
                        "\"$asked\" is no longer available to the app. It may have been " +
                            "moved or deleted since it was picked.",
                    )
                input.use { source ->
                    target.outputStream().use { output -> source.copyTo(output) }
                }
                copied++
            } catch (e: Throwable) {
                // **Which file, and why.** Android's own words for a document that
                // has gone are "open failed: ENOENT (No such file or directory)",
                // which names no file and reads like a bug in this app. A failure is
                // reported with the name that was being imported, and does not stop
                // the files after it.
                failures += "\"$asked\": ${e.message ?: "could not be read"}"
            }
        }
        if (copied == 0 && failures.isNotEmpty()) {
            Result.failure(IllegalStateException(failures.first()))
        } else {
            Result.success(Report(copied, renamed))
        }
    } catch (e: Throwable) {
        Result.failure(e)
    }

    /**
     * Copies a whole folder tree into [into].
     *
     * Android exposes a picked folder as a *tree* URI: its children are found by
     * asking the documents provider, one level at a time, because a folder on a
     * phone is not a path — it may be inside a zip, a cloud drive, or a provider
     * that has no filesystem at all. So this walks the tree rather than listing a
     * directory.
     */
    fun folderInto(context: Context, tree: Uri, into: File): Result<Report> = try {
        into.mkdirs()
        if (!into.isDirectory) throw IllegalStateException("The folder is no longer there")
        val asked = displayName(context, tree) ?: "imported folder"
        val target = freeName(into, asked)
        val renamed = mutableListOf<Pair<String, String>>()
        if (target.name != asked.trim()) renamed += asked.trim() to target.name
        if (!target.mkdirs()) throw IllegalStateException("Could not make \"${target.name}\"")
        val copied = copyTree(context, tree, DocumentsContract.getTreeDocumentId(tree), target, renamed, 0)
        Result.success(Report(copied, renamed))
    } catch (e: Throwable) {
        Result.failure(e)
    }

    /** Copies the children of one document id into [target], and their children. */
    private fun copyTree(
        context: Context,
        tree: Uri,
        documentId: String,
        target: File,
        renamed: MutableList<Pair<String, String>>,
        depth: Int,
    ): Int {
        if (depth > MAX_DEPTH) return 0
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        var copied = 0
        context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (copied >= MAX_FILES) break
                val id = cursor.getString(0) ?: continue
                val asked = cursor.getString(1)?.trim().orEmpty().ifBlank { continue }
                val mime = cursor.getString(2).orEmpty()
                // A name from outside is cleaned before it is used, so a provider
                // answering `../accounts` or `.muffy` cannot write outside this
                // folder or into the app's own bookkeeping.
                val child = freeName(target, asked)
                if (child.name != asked) renamed += asked to child.name
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    if (!child.mkdirs()) continue
                    if (child.listFiles()?.isEmpty() != false) File(child, Workspace.KEEPER).writeText("")
                    copied += copyTree(context, tree, id, child, renamed, depth + 1)
                } else {
                    val source = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    runCatching {
                        context.contentResolver.openInputStream(source)?.use { input ->
                            child.outputStream().use { output -> input.copyTo(output) }
                        } ?: return@runCatching
                        copied++
                    }
                }
            }
        }
        return copied
    }

    /** The name a document provider gives a thing, or null when it will not say. */
    private fun displayName(context: Context, uri: Uri): String? {
        // A tree URI has no display name of its own through OpenableColumns on
        // every provider, so its last path segment is the fallback.
        if (DocumentsContract.isTreeUri(uri)) {
            val fromDocument = runCatching {
                DocumentsContract.getDocumentId(uri)
            }.getOrNull()?.substringAfterLast('/')?.substringAfterLast(':')
            return fromDocument?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment?.substringAfterLast('/')
        }
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return uri.lastPathSegment
    }

    /**
     * A name not already taken in [dir], in the app's one name shape.
     *
     * **The numbering is `Workspace.freeName`'s, not a second copy of the idea.** This
     * method used to number with an underscore — `photo_2.png` — while a copy numbered
     * with a space and a count — `photo 2.png`. Two answers to "what is this one called
     * then?" is how a person ends up with `photo_2.png` and `photo 2.png` and no way to
     * tell which is which. One rule, in one place, and importing the same thing twice
     * still adds a copy instead of replacing what was there.
     */
    fun freeName(dir: File, name: String): File {
        val clean = Workspace.cleanName(name, "imported")
        return File(dir, Workspace.freeName(dir, clean))
    }
}
