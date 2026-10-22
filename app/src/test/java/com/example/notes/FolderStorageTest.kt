package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The on-disk layout: one directory per folder, one file per note, and the
 * bindings between folders and git projects.
 */
class FolderStorageTest {

    private fun tempDir(name: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "$name-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }

    @Test
    fun `a note at the top level is written and read back`() {
        val base = tempDir("root-note")
        val storage = FolderStorage(base)

        val note = Note(id = "n1", title = "Hello", body = "World")
        storage.save(emptyList(), listOf(note))

        val loaded = storage.load()
        assertEquals(1, loaded.notes.size)
        assertEquals("Hello", loaded.notes.first().title)
        assertEquals("World", loaded.notes.first().body)
        assertNull(loaded.notes.first().folderId)
    }

    @Test
    fun `each folder gets its own directory`() {
        val base = tempDir("dirs")
        val storage = FolderStorage(base)

        val folder = Folder(id = "f1", name = "Work", parentId = null)
        storage.save(listOf(folder), emptyList())

        assertTrue(at(base, "f1").isDirectory)
        assertTrue(at(base, "f1", "name").isFile)
        assertEquals("Work", at(base, "f1", "name").readText())
    }

    @Test
    fun `the notes of a folder are kept in the folder's own directory`() {
        val base = tempDir("folder-note")
        val storage = FolderStorage(base)

        val folder = Folder(id = "f1", name = "Work", parentId = null)
        val inside = Note(id = "n1", title = "Inside", body = "b", folderId = "f1")
        val outside = Note(id = "n2", title = "Outside", body = "b", folderId = null)

        storage.save(listOf(folder), listOf(inside, outside))

        // Each note is a file of its own, named after its id.
        assertTrue(at(base, "f1", "n1.md").isFile)
        assertTrue(at(base, "__root__", "n2.md").isFile)

        val loaded = storage.load()
        assertEquals(2, loaded.notes.size)
        assertEquals("f1", loaded.notes.first { it.id == "n1" }.folderId)
        assertNull(loaded.notes.first { it.id == "n2" }.folderId)
    }

    @Test
    fun `the parent of a folder is remembered`() {
        val base = tempDir("parent")
        val storage = FolderStorage(base)

        val parent = Folder(id = "p1", name = "Parent", parentId = null)
        val child = Folder(id = "c1", name = "Child", parentId = "p1")
        storage.save(listOf(parent, child), emptyList())

        val loaded = storage.load()
        assertEquals(2, loaded.folders.size)
        assertNull(loaded.folders.first { it.id == "p1" }.parentId)
        assertEquals("p1", loaded.folders.first { it.id == "c1" }.parentId)
    }

    @Test
    fun `a note that is gone is removed from the disk`() {
        val base = tempDir("delete")
        val storage = FolderStorage(base)

        val note = Note(id = "n1", title = "Hello", body = "b")
        storage.save(emptyList(), listOf(note))
        assertTrue(at(base, "__root__", "n1.md").isFile)

        storage.save(emptyList(), emptyList())
        assertFalse(at(base, "__root__", "n1.md").exists())
    }

    @Test
    fun `the directory of a deleted folder is removed`() {
        val base = tempDir("delete-folder")
        val storage = FolderStorage(base)

        val folder = Folder(id = "f1", name = "Work", parentId = null)
        storage.save(listOf(folder), emptyList())
        assertTrue(at(base, "f1").isDirectory)

        storage.save(emptyList(), emptyList())
        assertFalse(at(base, "f1").exists())
    }

    @Test
    fun `the folders are flat so that no folder is inside another's repository`() {
        val base = tempDir("flat")
        val storage = FolderStorage(base)

        val parent = Folder(id = "p1", name = "Parent", parentId = null)
        val child = Folder(id = "c1", name = "Child", parentId = "p1")
        storage.save(listOf(parent, child), emptyList())

        // The child is a sibling of the parent, not a directory inside it.
        assertTrue(at(base, "c1").isDirectory)
        assertFalse(at(base, "p1", "c1").exists())
    }

    @Test
    fun `the bindings between folders and projects round-trip`() {
        val store = ProjectStore(
            mapOf(
                "f1" to GitProject("muffy/notes", "https://github.com/muffy/notes.git"),
                "f2" to GitProject("muffy/other", "https://github.com/muffy/other.git"),
            )
        )

        val decoded = ProjectStore.decode(store.encode())

        assertEquals(2, decoded.byFolder.size)
        assertEquals("muffy/notes", decoded.projectFor("f1")?.name)
        assertEquals("muffy/other", decoded.projectFor("f2")?.name)
        assertNull(decoded.projectFor("f3"))
    }

    @Test
    fun `a folder can be detached from its project`() {
        val store = ProjectStore(mapOf("f1" to GitProject("a", "b")))

        val detached = store.with("f1", null)

        assertNull(detached.projectFor("f1"))
        assertTrue(store.projectFor("f1") != null)
    }
}

/** A file below [base], built from its path parts. */
private fun at(base: File, vararg parts: String): File =
    parts.fold(base) { dir, part -> File(dir, part) }
