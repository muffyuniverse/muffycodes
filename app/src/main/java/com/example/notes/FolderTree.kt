package com.example.notes

/**
 * Pure helpers for the folder tree. No Android dependencies, so every rule here
 * (descendant lookup, path building, cycle prevention) is unit-testable.
 */
object FolderTree {

    /**
     * All descendants of [folderId], deepest last, including [folderId] itself.
     * Returns an empty list if the folder does not exist.
     */
    fun descendants(folders: List<Folder>, folderId: String): List<String> {
        if (folders.none { it.id == folderId }) return emptyList()
        val childrenOf = folders.groupBy { it.parentId }
        val result = mutableListOf<String>()
        val queue = ArrayDeque<String>()
        queue.addLast(folderId)
        val seen = mutableSetOf<String>()
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            if (!seen.add(id)) continue
            result.add(id)
            childrenOf[id].orEmpty().forEach { child ->
                if (child.id !in seen) queue.addLast(child.id)
            }
        }
        return result
    }

    /** True if [candidateAncestorId] is [folderId] itself or one of its ancestors. */
    fun isAncestorOrSelf(folders: List<Folder>, candidateAncestorId: String, folderId: String): Boolean {
        var current: String? = folderId
        val byId = folders.associateBy { it.id }
        val guard = mutableSetOf<String>()
        while (current != null) {
            if (current == candidateAncestorId) return true
            if (!guard.add(current)) return false // defensive: broken parent cycle
            current = byId[current]?.parentId
        }
        return false
    }

    /**
     * The chain of folders from the root down to [folderId], inclusive.
     * Empty when the folder does not exist.
     */
    fun path(folders: List<Folder>, folderId: String?): List<Folder> {
        if (folderId == null) return emptyList()
        val byId = folders.associateBy { it.id }
        val chain = mutableListOf<Folder>()
        val guard = mutableSetOf<String>()
        var current: String? = folderId
        while (current != null) {
            if (!guard.add(current)) break
            val folder = byId[current] ?: break
            chain.add(0, folder)
            current = folder.parentId
        }
        return chain
    }

    /** The parent folder of [folderId], or null when it is at the root or unknown. */
    fun parentOf(folders: List<Folder>, folderId: String?): Folder? {
        if (folderId == null) return null
        return folders.firstOrNull { it.id == folderId }?.parentId?.let { pid ->
            folders.firstOrNull { it.id == pid }
        }
    }

    /** Direct children of [folderId] (null means the root). */
    fun childrenOf(folders: List<Folder>, folderId: String?): List<Folder> =
        folders.filter { it.parentId == folderId }

    /**
     * All folders that may legally receive [folderId] as a parent: everything
     * except the folder itself and its own descendants (which would create a cycle).
     */
    fun validMoveTargets(folders: List<Folder>, folderId: String): List<Folder> {
        val forbidden = descendants(folders, folderId).toSet()
        return folders.filter { it.id !in forbidden }
    }

    /** True if [name] is already used by a sibling of [parentId] (ignoring case). */
    fun hasSiblingNamed(folders: List<Folder>, parentId: String?, name: String, exceptId: String? = null): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        return folders.any {
            it.id != exceptId && it.parentId == parentId && it.name.equals(trimmed, ignoreCase = true)
        }
    }

    /**
     * Where a back gesture should take the user from [currentFolderId].
     *
     * The important case is a folder that sits at the top level: it has no parent,
     * but back must still leave it and return to the top level, rather than doing
     * nothing. [GoTo] with a null id means "go back to the top level".
     */
    fun backTarget(folders: List<Folder>, currentFolderId: String?): BackTarget {
        if (currentFolderId == null) return BackTarget.AtTopLevel
        val folder = folders.firstOrNull { it.id == currentFolderId }
            ?: return BackTarget.AtTopLevel
        return BackTarget.GoTo(folder.parentId)
    }

    /** The outcome of a back gesture in the folder tree. */
    sealed interface BackTarget {
        /** Already at the top level; there is nowhere to go back to. */
        data object AtTopLevel : BackTarget

        /** Go to [folderId], where null means the top level. */
        data class GoTo(val folderId: String?) : BackTarget
    }
}

// A note that changes nothing, to check the test selector.
