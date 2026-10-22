package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The record of which folder is which git project.
 *
 * It is a plain value that is written to a file and read back, so the cases
 * worth checking are the ones that decide whether a folder keeps its project
 * across a restart: that a binding survives, and that removing one really
 * removes it without touching the others.
 */
class ProjectStoreTest {

    @Test
    fun `the bindings between folders and projects survive being written and read`() {
        // Given two folders bound to two different projects
        val store = ProjectStore(
            mapOf(
                "f1" to GitProject("muffy/notes", "https://github.com/muffy/notes.git"),
                "f2" to GitProject("muffy/other", "https://github.com/muffy/other.git"),
            )
        )

        // When it is written and read back
        val decoded = ProjectStore.decode(store.encode())

        // Then both bindings are still there, each pointing at its own project,
        // which is what makes the app remember which folder is which repository
        assertEquals(2, decoded.byFolder.size)
        assertEquals("muffy/notes", decoded.projectFor("f1")?.name)
        assertEquals("muffy/other", decoded.projectFor("f2")?.name)

        // And a folder that was never bound has no project, rather than somebody
        // else's
        assertNull(decoded.projectFor("never-bound"))
    }

    @Test
    fun `detaching one folder leaves the others bound`() {
        // Given a folder bound to a project
        val store = ProjectStore(
            mapOf(
                "f1" to GitProject("a", "https://github.com/a/a.git"),
                "f2" to GitProject("b", "https://github.com/b/b.git"),
            )
        )

        // When one of them is detached
        val detached = store.with("f1", null)

        // Then only that one is free, and the original is untouched, because a
        // value that changed in place could not be handed to a screen to compare
        assertNull(detached.projectFor("f1"))
        assertEquals("b", detached.projectFor("f2")?.name)
        assertTrue("the change must make a new value", store.projectFor("f1") != null)
    }

    @Test
    fun `an empty store has no bindings`() {
        // Given a store with nothing in it

        // When it is written and read back
        val decoded = ProjectStore.decode(ProjectStore(emptyMap()).encode())

        // Then it is empty rather than broken, so a first run does not fail for
        // want of a file to read
        assertEquals(emptyMap<String, GitProject>(), decoded.byFolder)
        assertNull(decoded.projectFor("anything"))
    }
}
