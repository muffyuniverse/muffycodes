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
 */
object Imports {

    /**
     * Copies [uris] into [into], keeping their names.
     *
     * Returns how many arrived. A name already in the folder is kept as it is and
     * the new one is numbered — `photo.png` becomes `photo 2.png` — so importing
     * twice never destroys the first copy.
     */
    fun filesInto(context: Context, uris: List<Uri>, into: File): Result<Int> = try {
        if (!into.isDirectory) throw IllegalStateException("That folder is not there any more")
        var copied = 0
        val failures = mutableListOf<String>()
        uris.forEach { uri ->
            try {
                val name = displayName(context, uri) ?: "imported file"
                val target = freeName(into, name)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: throw IllegalStateException("Could not read \"$name\"")
                copied++
            } catch (e: Exception) {
                failures += e.message ?: "one file could not be read"
            }
        }
        if (copied == 0 && failures.isNotEmpty()) {
            Result.failure(IllegalStateException(failures.first()))
        } else {
            Result.success(copied)
        }
    } catch (e: Exception) {
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
    fun folderInto(context: Context, tree: Uri, into: File): Result<Int> = try {
        val root = displayName(context, tree) ?: "imported folder"
        val target = freeName(into, root)
        if (!target.mkdirs()) throw IllegalStateException("Could not make \"${target.name}\"")
        val copied = copyTree(context, tree, DocumentsContract.getTreeDocumentId(tree), target)
        Result.success(copied)
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** Copies the children of one document id into [target], and their children. */
    private fun copyTree(context: Context, tree: Uri, documentId: String, target: File): Int {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        var copied = 0
        context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val name = cursor.getString(1) ?: continue
                val mime = cursor.getString(2).orEmpty()
                // A name from outside is not allowed to escape the folder it is
                // going into: a provider that answered "../accounts" would otherwise
                // write outside the tree.
                if (name.contains('/') || name.contains('\\') || name.startsWith(".")) continue
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                    val child = File(target, name)
                    if (!child.mkdirs()) continue
                    copied += copyTree(context, tree, id, child)
                } else {
                    val source = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    runCatching {
                        context.contentResolver.openInputStream(source)?.use { input ->
                            File(target, name).outputStream().use { output ->
                                input.copyTo(output)
                            }
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
     * A name not already taken in [dir].
     *
     * `photo.png`, then `photo 2.png`, then `photo 3.png`. The point is that
     * importing the same thing twice adds a copy instead of replacing what was
     * there — a second import is never a way to lose the first.
     */
    fun freeName(dir: File, name: String): File {
        val clean = name.trim().ifBlank { "imported" }
        val first = File(dir, clean)
        if (!first.exists()) return first
        val stem = clean.substringBeforeLast('.', clean)
        val extension = clean.substringAfterLast('.', "")
        val tail = if (extension.isEmpty()) "" else ".$extension"
        var n = 2
        while (true) {
            val candidate = File(dir, "$stem $n$tail")
            if (!candidate.exists()) return candidate
            n++
            if (n > 999) return File(dir, "$stem ${System.nanoTime()}$tail")
        }
    }
}
