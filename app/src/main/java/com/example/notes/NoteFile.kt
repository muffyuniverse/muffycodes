package com.example.notes

/**
 * Reads and writes a single file (note) on disk.
 *
 * Every file is its own document, so a git repository can track each one
 * separately and the version history of a file is the history of that file.
 * The format is a small header followed by the body:
 *
 * ```
 * ---
 * id: 8f1c...
 * title: Shopping list
 * pinned: false
 * updatedAt: 1750000000000
 * ---
 * Milk
 * Bread
 * ```
 *
 * The body is everything after the second `---` line, and may contain any
 * character at all.
 *
 * The old single-JSON format ([NoteCodec]) is still read, so files written by
 * earlier versions of the app are not lost.
 */
object NoteFile {

    /** The extension used for the files of the app. */
    const val EXTENSION = ".md"

    /** The file name for a note with this id. */
    fun fileNameFor(noteId: String): String = noteId + EXTENSION

    /** True when [fileName] looks like a file of the app. */
    fun isNoteFile(fileName: String): Boolean =
        fileName.endsWith(EXTENSION) && !fileName.startsWith(".")

    /** Turns a note into the text of its file. */
    fun encode(note: Note): String {
        val header = buildString {
            appendLine("---")
            appendLine("id: ${note.id}")
            appendLine("title: ${escape(note.title)}")
            appendLine("pinned: ${note.pinned}")
            appendLine("updatedAt: ${note.updatedAt}")
            append("---")
        }
        return header + "\n" + note.body
    }

    /**
     * Reads a file back. Returns null when the text is not a file of the app,
     * so a stray file in the folder does not break the app.
     */
    fun decode(text: String, fallbackId: String? = null): Note? {
        if (!text.startsWith("---")) return null
        val end = text.indexOf("\n---", 3)
        if (end < 0) return null

        val header = text.substring(3, end).trim()
        val body = text.substringAfter("\n---", "").removePrefix("\n")

        var id = fallbackId
        var title = ""
        var pinned = false
        var updatedAt = System.currentTimeMillis()

        header.lines().forEach { line ->
            val separator = line.indexOf(':')
            if (separator < 0) return@forEach
            val key = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            when (key) {
                "id" -> if (value.isNotEmpty()) id = value
                "title" -> title = unescape(value)
                "pinned" -> pinned = value.equals("true", ignoreCase = true)
                "updatedAt" -> updatedAt = value.toLongOrNull() ?: updatedAt
            }
        }

        val noteId = id ?: fallbackId ?: return null
        return Note(
            id = noteId,
            title = title,
            body = body,
            pinned = pinned,
            updatedAt = updatedAt,
            folderId = null,
        )
    }

    /**
     * Escapes the characters that would break a header line. A newline in a
     * title becomes a space, and a colon is left alone because the value is
     * read as everything after the first colon.
     */
    private fun escape(value: String): String =
        value.replace("\r\n", " ").replace("\n", " ").replace("\r", " ")

    /** Undoes the escaping done by [escape]. */
    private fun unescape(value: String): String = value
}
