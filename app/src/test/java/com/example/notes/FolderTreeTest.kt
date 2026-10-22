package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the folder tree rules. These run without a device or emulator.
 */
class FolderTreeTest {

    private val folders = listOf(
        Folder("work", "Work", parentId = null),
        Folder("meetings", "Meetings", parentId = "work"),
        Folder("minutes", "Minutes", parentId = "meetings"),
        Folder("personal", "Personal", parentId = null),
    )

    @Test
    fun `descendants include the folder itself and every level below it`() {
        assertEquals(
            listOf("work", "meetings", "minutes"),
            FolderTree.descendants(folders, "work"),
        )
    }

    @Test
    fun `descendants of a leaf is just the leaf`() {
        assertEquals(listOf("personal"), FolderTree.descendants(folders, "personal"))
    }

    @Test
    fun `descendants of an unknown folder is empty`() {
        assertEquals(emptyList<String>(), FolderTree.descendants(folders, "nope"))
    }

    @Test
    fun `isAncestorOrSelf detects ancestors`() {
        assertTrue(FolderTree.isAncestorOrSelf(folders, "work", "minutes"))
        assertTrue(FolderTree.isAncestorOrSelf(folders, "work", "work"))
        assertFalse(FolderTree.isAncestorOrSelf(folders, "work", "personal"))
        assertFalse(FolderTree.isAncestorOrSelf(folders, "minutes", "work"))
    }

    @Test
    fun `path builds the chain from the root`() {
        assertEquals(
            listOf("work", "meetings", "minutes"),
            FolderTree.path(folders, "minutes").map { it.id },
        )
        assertEquals(emptyList<Folder>(), FolderTree.path(folders, null))
    }

    @Test
    fun `childrenOf lists direct children only`() {
        assertEquals(listOf("work", "personal"), FolderTree.childrenOf(folders, null).map { it.id })
        assertEquals(listOf("meetings"), FolderTree.childrenOf(folders, "work").map { it.id })
    }

    @Test
    fun `valid move targets exclude the folder and its descendants`() {
        val targets = FolderTree.validMoveTargets(folders, "work").map { it.id }

        assertFalse(targets.contains("work"))
        assertFalse(targets.contains("meetings"))
        assertFalse(targets.contains("minutes"))
        assertTrue(targets.contains("personal"))
    }

    @Test
    fun `sibling name clash is detected but only among siblings`() {
        // "work" is a root folder, and a root folder named "work" already exists.
        assertTrue(FolderTree.hasSiblingNamed(folders, null, "work"))
        assertTrue(FolderTree.hasSiblingNamed(folders, null, "WORK"))
        // A clash with a folder's own name is ignored when that folder is excluded.
        assertFalse(FolderTree.hasSiblingNamed(folders, null, "work", exceptId = "work"))
        // "meetings" is a child of "work", so it is not a sibling of the root folders.
        assertFalse(FolderTree.hasSiblingNamed(folders, null, "meetings"))
        assertTrue(FolderTree.hasSiblingNamed(folders, "work", "meetings"))
    }

    // ---- back navigation --------------------------------------------------

    @Test
    fun `back from a top level folder goes to the top level`() {
        // Regression: "work" sits at the top level, so it has no parent. Back must
        // still leave it and return to the top level rather than doing nothing.
        val target = FolderTree.backTarget(folders, "work")

        assertTrue(target is FolderTree.BackTarget.GoTo)
        assertNull((target as FolderTree.BackTarget.GoTo).folderId)
    }

    @Test
    fun `back from a nested folder goes to its parent`() {
        val target = FolderTree.backTarget(folders, "meetings")

        assertTrue(target is FolderTree.BackTarget.GoTo)
        assertEquals("work", (target as FolderTree.BackTarget.GoTo).folderId)
    }

    @Test
    fun `back from the top level has nowhere to go`() {
        assertTrue(FolderTree.backTarget(folders, null) is FolderTree.BackTarget.AtTopLevel)
    }

    @Test
    fun `back from an unknown folder falls back to the top level`() {
        assertTrue(FolderTree.backTarget(folders, "ghost") is FolderTree.BackTarget.AtTopLevel)
    }
}
