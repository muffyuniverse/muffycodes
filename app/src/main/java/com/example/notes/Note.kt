package com.example.notes

/**
 * A single note. [id] is stable across renames so the edit screen can track it.
 * [pinned] notes are kept at the top of the list. [folderId] is the folder the note
 * lives in, or null for a note at the root ("unfiled").
 */
data class Note(
    val id: String,
    val title: String,
    val body: String,
    val pinned: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
    val folderId: String? = null,
) {
    /** First non-blank line of the body, used as the list preview. */
    fun preview(): String {
        val line = body.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        return if (line.length > 120) line.take(120) + "…" else line
    }
}

// A comment, so the suite runs again without touching configuration.
