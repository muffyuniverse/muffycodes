package com.example.notes

/**
 * A folder. Folders form a tree: [parentId] points at the containing folder, and
 * a null [parentId] means the folder sits at the root.
 */
data class Folder(
    val id: String,
    val name: String,
    val parentId: String? = null,
) {
    /** The name shown in the UI when it has no name of its own. */
    fun displayName(): String = name.ifBlank { "Untitled folder" }
}
