package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A name is checked, not rewritten.
 *
 * It used to be rewritten: lowercased, with every run of anything else turned into one underscore.
 * **Measured, and the rule was never real** — GitHub created files named `zz:a.txt`, `zz?a.txt`,
 * `zz a.txt` and `CON` and answered `201 Created` to every one. So the app was renaming the person's
 * file to satisfy nothing.
 *
 * What is checked now is what actually breaks a clone: the characters Microsoft lists for Windows,
 * control characters, a trailing space or full stop, the reserved device names, and a length no
 * filesystem would store. A name that fails is **refused with a sentence**, because a name is the
 * person's and a quietly different one is a file they did not ask for.
 */
class NamingFeatureTest : FeatureTest() {

    /**
     * The only thing in the folder that a person put there.
     *
     * The account's root is the projects list, and it holds nothing the app made, so asking
     * for the only entry would count it too.
     */
    /**
     * The one thing the test made.
     *
     * **`system` is always there**, because the account root holds the project that keeps the
     * account's settings. So "the one name" is the one that is not the system project — asking for
     * `.single()` over the whole list would be asking a question whose answer changed when the
     * system project arrived.
     */
    private fun made(): String =
        scene.names().single { !Workspace.isSystemLocked(it) && it != Workspace.SYSTEM_PROJECT }

    @Test
    fun `a name typed with capitals and spaces is kept as typed`() {
        // **This test used to assert the opposite**, and the change is the point: there is no
        // rewrite to report, so there is no "(was ...)" sentence to check either.
        assertTrue("made", scene.viewModel.createFile("My Shopping List"))
        val written = made()

        assertEquals("My Shopping List.txt", written)
        assertTrue("and the file is there", File(scene.viewModel.currentDir!!, written).isFile)
    }

    @Test
    fun `a folder keeps the name it was given, brackets and all`() {
        assertTrue("made", scene.viewModel.createFolder("Old Letters (2024)"))

        assertEquals("Old Letters (2024)", made())
    }

    @Test
    fun `a name of symbols is a name, and only a blank one falls back`() {
        // **The old rule turned `!!!` into `folder`**, which is a name nobody typed. Brackets and
        // exclamation marks are legal everywhere git goes, so the only name that has nothing to
        // keep is a blank one.
        assertTrue("made", scene.viewModel.createFolder("!!!"))

        assertEquals("!!!", made())
    }

    @Test
    fun `a rename obeys the rule, and keeps the extension it was not given`() {
        val file = scene.file("milk.txt", "two litres")

        assertTrue("renamed", scene.viewModel.rename(file, "Shopping List"))

        val written = made()
        assertEquals("Shopping List.txt", written)
        assertTrue("the text came with it", File(file.parentFile, written).readText() == "two litres")
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

        val made = File(file.parentFile, "My Photo.png")
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
        // **`quiet.PNG`, not `quiet.png`.** The rename keeps the extension the file already had,
        // and that extension is upper case because the file is. Lowercasing it was the rewriter, and
        // the rewriter is gone — a rename that changed a picture's extension case for no reason is
        // the kind of quiet correction this change removes.
        assertTrue("and it is there", File(file.parentFile, "quiet.PNG").isFile)
    }

    @Test
    fun `a dot at the start is refused, not silently rewritten`() {
        // A name the app owns is one starting with a dot (§3). Letting a typed name start with one
        // would hide a person's own file from every screen — and now that names are not rewritten,
        // the answer is a refusal rather than a quiet `secret.txt`.
        assertFalse("refused", scene.viewModel.createFile(".secret"))
        assertNotNull("and the message says why", scene.viewModel.message)
    }

    @Test
    fun `a name too long to be a path is refused, not cut short`() {
        // **Cutting a name is the same mistake as rewriting it**: the person asked for a name and
        // would get a different one. A length that cannot be stored is a refusal with a sentence.
        val long = "a".repeat(200)
        assertFalse("refused", scene.viewModel.createFolder(long))
        assertTrue(
            "and the message names the limit: ${scene.viewModel.message}",
            scene.viewModel.message.orEmpty().contains("longer than"),
        )
    }

    @Test
    fun `a name keeps a word from another script`() {
        // A Cyrillic title stays exactly as typed. Turning it into `untitled`, or lowercasing it,
        // would lose the only part of the name that says what the file is.
        assertTrue("made", scene.viewModel.createFile("Транспортная логистика"))
        assertEquals("Транспортная логистика.txt", made())
    }

    @Test
    fun `a name the person typed is kept exactly as they typed it`() {
        // **The rewriter is gone, and this is why.** It lowercased and turned every run of anything
        // else into one underscore, so `My Notes (draft).txt` was stored as `my_notes_draft.txt`.
        // Measured against a real repository: GitHub created files named `zz:a.txt`, `zz?a.txt`,
        // `zz a.txt` and `CON` and answered `201 Created` to every one. The app was not protecting
        // the remote from anything; it was renaming the person's file for a rule that did not exist.
        assertEquals("My Notes (draft).txt", madeFileNamed("My Notes (draft).txt"))
        assertEquals("Shopping list.txt", madeFileNamed("Shopping list.txt"))
        assertEquals("PHOTO.JPEG", madeFileNamed("PHOTO.JPEG"))
        assertEquals("a  b__c.txt", madeFileNamed("a  b__c.txt"))
    }

    @Test
    fun `a name that cannot be checked out on Windows is refused, not corrected`() {
        // **A refusal rather than a rename**, because the name is the person's: "that name has a
        // colon in it" is a sentence they can act on, and `my_notes.txt` is a file they did not ask
        // for. The list is Microsoft's own — a repository holding one of these cannot be checked
        // out on Windows, and the failure arrives on somebody else's machine.
        for (bad in listOf("a:b.txt", "a?b.txt", "a*b.txt", "a\"b.txt", "a<b.txt", "a|b.txt")) {
            assertNotNull("$bad must be refused", Workspace.nameProblem(bad))
        }
        // **Trailing space and full stop**, which Microsoft lists separately from the characters.
        assertNotNull(Workspace.nameProblem("notes."))
        assertNotNull(Workspace.nameProblem("notes "))

        // **And the names that are fine stay fine.** A rule that refused everything would pass the
        // loop above and be useless.
        for (good in listOf("notes.txt", "Shopping list.txt", "a-b_c.txt", "v1.2.txt", "a#b.txt")) {
            assertNull("$good must be allowed", Workspace.nameProblem(good))
        }
    }

    @Test
    fun `a device name is refused, and its extension does not save it`() {
        // Microsoft: "avoid these names followed immediately by an extension; for example, NUL.txt
        // and NUL.tar.gz are both equivalent to NUL". So the test is on the stem.
        for (bad in listOf("CON", "CON.txt", "nul.tar.gz", "COM1", "LPT9.log", "aux.md")) {
            assertNotNull("$bad must be refused", Workspace.nameProblem(bad))
        }
        // **`COM56` is NOT reserved** — Microsoft says so explicitly — so a "starts with COM" rule
        // would refuse a name that works.
        assertNull(Workspace.nameProblem("COM56.txt"))
        assertNull(Workspace.nameProblem("CONSOLE.txt"))
        assertNull(Workspace.nameProblem("console.txt"))
    }

    @Test
    fun `the rule is one function, not a habit of the screens`() {
        // Asked directly, so that a screen that forgot to call it is a compile-time gap rather than
        // a name that silently breaks a clone.
        assertNotNull(Workspace.nameProblem("a:b"))
        assertNull(Workspace.nameProblem("fine.txt"))
        // Blank still needs a name, and the callers fall back rather than refuse.
        assertNotNull(Workspace.nameProblem(""))
        assertNotNull(Workspace.nameProblem("   "))
    }

    /**
     * Makes a file with [name] and answers the name it was actually given.
     *
     * **Asked of the entries, not of [made]**, because the point of these tests is precisely that
     * the app does not change the name — a helper that went through the same rule would agree with
     * itself whatever happened.
     */
    private fun madeFileNamed(name: String): String {
        scene.signedIn()
        scene.project("groceries")
        scene.open("groceries")
        // **The entries before and after**, so the answer is the one this call made rather than
        // "the only entry" — a project's folder holds more than the file being tested.
        val before = scene.viewModel.entries.map { it.name }.toSet()
        assertTrue("made", scene.viewModel.createFile(name))
        val made = scene.viewModel.entries.map { it.name }.single { it !in before }
        assertTrue("and it is on disk as $made", File(scene.viewModel.currentDir!!, made).isFile)
        return made
    }
}
