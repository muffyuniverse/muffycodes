package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules of the folder tree, which hold the whole screen together: what a
 * folder contains, how a path is built, what may be moved where, and where the
 * back button goes.
 *
 * The tree used here is the same for every test because it is only ever read,
 * so no test can disturb another and none of them has to build a tree of its
 * own. It is deliberately three levels deep, because that is where the
 * interesting mistakes are: a rule that works one level down and breaks two.
 *
 *         work                    personal
 *        /      \                    |
 *   meetings   (none)            (none)
 *      |
 *   minutes
 */
class FolderTreeTest {

    private val tree = listOf(
        Folder("work", "Work", parentId = null),
        Folder("meetings", "Meetings", parentId = "work"),
        Folder("minutes", "Minutes", parentId = "meetings"),
        Folder("personal", "Personal", parentId = null),
    )

    // ---- what a folder contains -------------------------------------------

    @Test
    fun `a folder and everything below it is found, however deep`() {
        // Given a tree three levels deep
        // When everything below the top folder is asked for
        val found = FolderTree.descendants(tree, "work")

        // Then the folder itself and every level under it is there, which is
        // what makes deleting a folder take its contents with it
        assertEquals(listOf("work", "meetings", "minutes"), found)
    }

    @Test
    fun `a folder with nothing below it is only itself`() {
        // Given a folder at the bottom of the tree
        // When everything below it is asked for
        // Then it is only itself, so deleting it does not reach sideways
        assertEquals(listOf("personal"), FolderTree.descendants(tree, "personal"))
    }

    @Test
    fun `a folder that does not exist contains nothing`() {
        // Given an id that is not in the tree
        // When everything below it is asked for
        // Then nothing is found rather than everything
        assertEquals(emptyList<String>(), FolderTree.descendants(tree, "no-such-folder"))
    }

    @Test
    fun `only the folders directly inside one are its children`() {
        // Given the tree
        // When the children of the top level and of one folder are asked for
        val atTop = FolderTree.childrenOf(tree, null).map { it.id }
        val insideWork = FolderTree.childrenOf(tree, "work").map { it.id }

        // Then the grandchildren are not children, so a folder is shown once
        // and not at every level between it and the top
        assertEquals(listOf("work", "personal"), atTop)
        assertEquals(listOf("meetings"), insideWork)
    }

    // ---- is one folder inside another -------------------------------------

    @Test
    fun `a folder is inside itself, and inside everything above it`() {
        // Given a folder three levels down
        // When it is compared with itself and with the folders above it
        // Then it counts as inside both, which is what stops a folder being
        // moved somewhere it would disappear into itself
        assertTrue(FolderTree.isAncestorOrSelf(tree, "work", "work"))
        assertTrue(FolderTree.isAncestorOrSelf(tree, "work", "minutes"))
        assertTrue(FolderTree.isAncestorOrSelf(tree, "meetings", "minutes"))

        // And a folder is not inside one on another branch, or below it
        assertFalse(FolderTree.isAncestorOrSelf(tree, "work", "personal"))
        assertFalse(FolderTree.isAncestorOrSelf(tree, "minutes", "work"))
    }

    @Test
    fun `what may be moved into excludes the folder itself and everything below`() {
        // Given a folder with children and grandchildren
        // When the places it could be moved to are worked out
        val targets = FolderTree.validMoveTargets(tree, "work").map { it.id }

        // Then neither it nor anything below it is offered, and the folders
        // elsewhere are, which is the whole point of the check
        assertFalse("a folder must not move into itself", targets.contains("work"))
        assertFalse("or into its child", targets.contains("meetings"))
        assertFalse("or into its grandchildren", targets.contains("minutes"))
        assertTrue("but into an unrelated folder", targets.contains("personal"))
    }

    // ---- a path, and names that clash -------------------------------------

    @Test
    fun `a path runs from the top down to the folder`() {
        // Given a folder three levels down
        // When its path is built
        val path = FolderTree.path(tree, "minutes").map { it.id }

        // Then it is the chain from the top, which is what the breadcrumb shows
        assertEquals(listOf("work", "meetings", "minutes"), path)

        // And the top level itself has no path, rather than a path of nothing
        assertEquals(emptyList<Folder>(), FolderTree.path(tree, null))
    }

    @Test
    fun `a name clash is only a clash among folders in the same place`() {
        // Given a folder called "work" at the top level, and one called
        // "meetings" inside it
        // When the same names are looked for in different places
        // Then a clash is found where there really is one, and not where the
        // folders sit at different levels, because two folders may share a name
        // in different places
        assertTrue("a second 'work' at the top clashes", FolderTree.hasSiblingNamed(tree, null, "work"))
        assertTrue("the clash ignores capital letters", FolderTree.hasSiblingNamed(tree, null, "WORK"))
        assertTrue("'meetings' clashes inside work", FolderTree.hasSiblingNamed(tree, "work", "meetings"))

        assertFalse(
            "'meetings' is inside work, so it is no clash at the top",
            FolderTree.hasSiblingNamed(tree, null, "meetings"),
        )
        assertFalse(
            "a folder renamed to the name it already has is no clash",
            FolderTree.hasSiblingNamed(tree, null, "work", exceptId = "work"),
        )
    }

    // ---- the back button ---------------------------------------------------
    // These four exist because of a real fault: the back button did nothing at
    // the top level of the tree, so the app could not be left from there.

    @Test
    fun `back from a folder at the top returns to the top level`() {
        // Given a folder with no parent
        // When back is pressed
        val target = FolderTree.backTarget(tree, "work")

        // Then it goes to the top level, rather than staying where it is
        assertTrue("back must leave the folder", target is FolderTree.BackTarget.GoTo)
        assertNull((target as FolderTree.BackTarget.GoTo).folderId)
    }

    @Test
    fun `back from a folder inside another returns to its parent`() {
        // Given a folder with a parent
        // When back is pressed
        val target = FolderTree.backTarget(tree, "meetings")

        // Then it goes one level up, and not all the way out
        assertTrue(target is FolderTree.BackTarget.GoTo)
        assertEquals("work", (target as FolderTree.BackTarget.GoTo).folderId)
    }

    @Test
    fun `back from the top level has nowhere to go`() {
        // Given that the top level is already shown
        // When back is pressed
        // Then there is nothing to do, which is what lets the app close instead
        assertTrue(FolderTree.backTarget(tree, null) is FolderTree.BackTarget.AtTopLevel)
    }

    @Test
    fun `back from a folder that no longer exists goes to the top level`() {
        // Given a folder that was deleted while it was open
        // When back is pressed
        // Then it goes somewhere real rather than nowhere, so deleting the open
        // folder cannot strand the screen
        assertTrue(FolderTree.backTarget(tree, "deleted-a-moment-ago") is FolderTree.BackTarget.AtTopLevel)
    }
}
