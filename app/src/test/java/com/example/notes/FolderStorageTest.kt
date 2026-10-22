package com.example.notes

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The layout on disk: one directory per folder, one file per note.
 *
 * The layout matters beyond tidiness. The folders are flat, so that a folder is
 * never a directory inside another folder's repository; and each note is a file
 * of its own, so that a note's history is the history of that note.
 *
 * Every test works in a directory of its own and removes it afterwards, so
 * nothing is shared and no test can leave anything for another to trip over.
 */
class FolderStorageTest {

    private val made = mutableListOf<File>()

    private fun storage(): Pair<File, FolderStorage> {
        val dir = File(System.getProperty("java.io.tmpdir"), "storage-${System.nanoTime()}")
        dir.mkdirs()
        made.add(dir)
        return dir to FolderStorage(dir)
    }

    @After
    fun removeWhatWasMade() {
        made.forEach { it.deleteRecursively() }
    }

    @Test
    fun `a note with no folder is written and read back at the top`() {
        // Given a note that belongs to no folder
        val (_, store) = storage()
        val note = Note(id = "n1", title = "Hello", body = "World")

        // When the tree is saved and read again
        store.save(emptyList(), listOf(note))
        val loaded = store.load()

        // Then the note is there, whole, and still belongs to no folder
        assertEquals(1, loaded.notes.size)
        val read = loaded.notes.first()
        assertEquals("Hello", read.title)
        assertEquals("World", read.body)
        assertNull(read.folderId)
    }

    @Test
    fun `each folder is a directory holding its own name`() {
        // Given a folder
        val (dir, store) = storage()

        // When the tree is saved
        store.save(listOf(Folder(id = "f1", name = "Work", parentId = null)), emptyList())

        // Then it is a directory named after its id, holding its name, so the
        // name can be changed without moving anything
        assertTrue(at(dir, "f1").isDirectory)
        assertTrue(at(dir, "f1", "name").isFile)
        assertEquals("Work", at(dir, "f1", "name").readText())
    }

    @Test
    fun `a note is written into the directory of its own folder`() {
        // Given one note inside a folder and one that belongs to no folder
        val (dir, store) = storage()
        val folder = Folder(id = "f1", name = "Work", parentId = null)
        val inside = Note(id = "n1", title = "Inside", body = "b", folderId = "f1")
        val atTop = Note(id = "n2", title = "At the top", body = "b", folderId = null)

        // When the tree is saved
        store.save(listOf(folder), listOf(inside, atTop))

        // Then each file sits in the right directory, and reading it back gives
        // each note its folder again
        assertTrue(at(dir, "f1", "n1.md").isFile)
        assertTrue(at(dir, "__root__", "n2.md").isFile)

        val loaded = store.load()
        assertEquals("f1", loaded.notes.first { it.id == "n1" }.folderId)
        assertNull(loaded.notes.first { it.id == "n2" }.folderId)
    }

    @Test
    fun `the parent of a folder is remembered`() {
        // Given a folder inside another folder
        val (_, store) = storage()
        val parent = Folder(id = "p1", name = "Parent", parentId = null)
        val child = Folder(id = "c1", name = "Child", parentId = "p1")

        // When the tree is saved and read again
        store.save(listOf(parent, child), emptyList())
        val loaded = store.load()

        // Then the shape of the tree survives, which is the whole of the
        // nesting: it is written down, not implied by where the files sit
        assertEquals(2, loaded.folders.size)
        assertNull(loaded.folders.first { it.id == "p1" }.parentId)
        assertEquals("p1", loaded.folders.first { it.id == "c1" }.parentId)
    }

    @Test
    fun `a folder is never a directory inside another folder`() {
        // Given a child folder
        val (dir, store) = storage()
        val parent = Folder(id = "p1", name = "Parent", parentId = null)
        val child = Folder(id = "c1", name = "Child", parentId = "p1")

        // When the tree is saved
        store.save(listOf(parent, child), emptyList())

        // Then both sit side by side at the top, because a directory inside
        // another would turn the inner folder into a link of the outer
        // repository rather than a folder of its own
        assertTrue(at(dir, "c1").isDirectory)
        assertFalse(
            "a folder must not live inside another folder's directory",
            at(dir, "p1", "c1").exists(),
        )
    }

    @Test
    fun `a note that is no longer in the tree is taken off the disk`() {
        // Given a note that was saved
        val (dir, store) = storage()
        store.save(emptyList(), listOf(Note(id = "n1", title = "Hello", body = "b")))
        assertTrue(at(dir, "__root__", "n1.md").isFile)

        // When it is deleted from the tree and the tree is saved
        store.save(emptyList(), emptyList())

        // Then its file is gone, so a deleted note does not come back the next
        // time the app is opened
        assertFalse(at(dir, "__root__", "n1.md").exists())
    }

    @Test
    fun `a folder that is no longer in the tree loses its directory`() {
        // Given a saved folder
        val (dir, store) = storage()
        store.save(listOf(Folder(id = "f1", name = "Work", parentId = null)), emptyList())
        assertTrue(at(dir, "f1").isDirectory)

        // When it is deleted and the tree is saved
        store.save(emptyList(), emptyList())

        // Then its whole directory is gone, rather than being left behind as an
        // empty folder that would be read back as a real one
        assertFalse(at(dir, "f1").exists())
    }

    @Test
    fun `one unreadable file does not cost the other files`() {
        // Given a folder holding one good note and one file that cannot be read
        val (dir, store) = storage()
        store.save(emptyList(), listOf(Note(id = "good", title = "Good", body = "kept")))
        at(dir, "__root__", "broken.md").writeText("this is not a file of the app at all")

        // When the tree is read
        val loaded = store.load()

        // Then the good note is still there and the broken file is skipped,
        // because one damaged file must never take the whole app down with it
        assertEquals(listOf("good"), loaded.notes.map { it.id })
    }
}

/** A file below [base], built from its path parts. */
private fun at(base: File, vararg parts: String): File =
    parts.fold(base) { dir, part -> File(dir, part) }
