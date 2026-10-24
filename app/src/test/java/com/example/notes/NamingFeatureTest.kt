package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * One shape for every name the app writes.
 *
 * A name here is not a label on a screen: it is a path component in two git
 * repositories and in the folder tree that *is* the account. So the promise is
 * concrete — lowercase letters, digits and single underscores — and every way a
 * name gets in (typing one, renaming, importing from the phone's picker) has to
 * obey it. The counterpart promise is that the app says so when it changes what
 * was asked for, because a file that arrives under a name nobody expected reads
 * as a file that did not arrive.
 */
class NamingFeatureTest : FeatureTest() {

    @Test
    fun `a name typed with capitals and spaces is written the one way`() {
        assertTrue("made", scene.viewModel.createFile("My Shopping List"))
        val made = scene.names().single()

        assertEquals("my_shopping_list.txt", made)
        assertTrue("and the file is there", File(scene.viewModel.currentDir!!, made).isFile)
    }

    @Test
    fun `the app says which name it used when it had to change one`() {
        scene.viewModel.createFile("My Shopping List")

        assertEquals(
            "the message names both",
            "Created my_shopping_list.txt (was \"My Shopping List.txt\")",
            scene.viewModel.message,
        )
    }

    @Test
    fun `a folder obeys the same rule as a file`() {
        assertTrue("made", scene.viewModel.createFolder("Old Letters (2024)"))

        assertEquals("old_letters_2024", scene.names().single())
    }

    @Test
    fun `a name made only of symbols still makes something`() {
        assertTrue("made", scene.viewModel.createFolder("!!!"))

        assertEquals("an empty name falls back rather than a row of underscores", "folder", scene.names().single())
    }

    @Test
    fun `a rename obeys the rule, and keeps the extension it was not given`() {
        val file = scene.file("milk.txt", "two litres")

        assertTrue("renamed", scene.viewModel.rename(file, "Shopping List"))

        val made = scene.names().single()
        assertEquals("shopping_list.txt", made)
        assertTrue("the text came with it", File(file.parentFile, made).readText() == "two litres")
    }

    @Test
    fun `a rename that keeps a picture a picture`() {
        // The extension is not decoration: §9 decides what may be edited from it, so
        // a rename that dropped `.png` would quietly turn a picture into "text" and
        // let the editor rewrite its bytes.
        val file = File(scene.viewModel.currentDir!!, "dot.png").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        scene.viewModel.refresh()

        assertTrue("renamed", scene.viewModel.rename(file, "My Photo"))

        val made = File(file.parentFile, "my_photo.png")
        assertTrue("it kept its extension", made.isFile)
        assertEquals("still a picture", EntryKind.IMAGE, scene.workspace!!.kindOf(made))
    }

    @Test
    fun `an upper-case extension is not a different kind of file`() {
        val file = File(scene.viewModel.currentDir!!, "SHOUTING.PNG").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }
        scene.viewModel.refresh()

        assertEquals("still a picture", EntryKind.IMAGE, scene.workspace!!.kindOf(file))
        assertTrue("renamed", scene.viewModel.rename(file, "quiet"))
        assertTrue("and now lowercase", File(file.parentFile, "quiet.png").isFile)
    }

    @Test
    fun `a dot at the start cannot make a name the app hides`() {
        // A name the app owns is one starting with a dot (§3). Letting a typed name
        // start with one would hide a person's own file from every screen.
        assertTrue("made", scene.viewModel.createFile(".secret"))

        assertEquals("secret.txt", scene.names().single())
        assertFalse("and it is not hidden", scene.workspace!!.isHidden(scene.names().single()))
    }

    @Test
    fun `a long name is cut short rather than refused`() {
        val long = "a".repeat(200)
        assertTrue("made", scene.viewModel.createFolder(long))

        val made = scene.names().single()
        assertEquals("cut to the app's limit", Workspace.MAX_STEM, made.length)
        assertTrue("and it was made", File(scene.viewModel.currentDir!!, made).isDirectory)
    }

    @Test
    fun `a name keeps a word from another script`() {
        // Cleaning is not transliteration. A Cyrillic title stays readable, lowercased
        // and joined by underscores; turning it into `untitled` would be losing the
        // only part of the name that says what the file is.
        assertTrue("made", scene.viewModel.createFile("Транспортная логистика"))
        assertEquals("транспортная_логистика.txt", scene.names().single())
    }

    @Test
    fun `the rule is one function, not a habit of the screens`() {
        // Asked directly, so that a screen that forgot to call it is a compile-time
        // gap rather than a name with a space in it.
        assertEquals("shopping_list.txt", Workspace.cleanName("Shopping list.txt"))
        assertEquals("a_b_c", Workspace.cleanName("a  b__c"))
        assertEquals("notes", Workspace.cleanName("  notes  "))
        assertEquals("photo.jpeg", Workspace.cleanName("PHOTO.JPEG"))
        assertEquals("archive_tar.gz", Workspace.cleanName("archive.tar.gz"))
        assertEquals("untitled", Workspace.cleanName("   "))
        assertEquals("untitled", Workspace.cleanName("///"))
        assertEquals("v1_2_readme.md", Workspace.cleanName("v1.2 README.md"))
    }
}
