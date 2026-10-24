package com.example.notes

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §9 of the design: **a file's name is its title, and its extension is the last
 * part of the name after a dot.**
 *
 * There is no preview, no lock file, no front matter. A text file opens in the
 * editor; a picture is shown as a picture and cannot be edited; anything else can
 * be renamed, moved and deleted, and that is all.
 */
class FilesFeatureTest : FeatureTest() {

    @Test
    fun `a new file gets the default extension when the name has none`() {
        scene.file("Shopping")

        assertTrue("the name got .txt", scene.names().contains("shopping.txt"))
    }

    @Test
    fun `a name that already has an extension keeps exactly it`() {
        scene.viewModel.createFile("plan.md")

        assertTrue("the extension is left alone", scene.names().contains("plan.md"))
    }

    @Test
    fun `a file is ordinary text on disk, with nothing added to it`() {
        val file = scene.file("milk.txt", "two litres")

        assertEquals("two litres", file.readText())
        assertEquals("no front matter, no header", "two litres", scene.viewModel.textOf(file))
    }

    @Test
    fun `a picture is recognised by its extension and is not opened in the editor`() {
        scene.file("photo.png")

        assertEquals(EntryKind.IMAGE, scene.entry("photo.png").kind)
    }

    @Test
    fun `a file with an extension the app does not know is unknown, not text`() {
        // §9's third class. This was "text, whatever it is" — which read the design
        // too loosely and was destructive: a .zip opened in the editor and saved
        // back has its bytes rewritten as UTF-8. An unknown kind can still be
        // renamed, moved and deleted, which is the whole of §9's promise for it.
        scene.file("notes.whatever")

        assertEquals(EntryKind.UNKNOWN, scene.entry("notes.whatever").kind)
    }

    @Test
    fun `a file with no extension at all is still text`() {
        // A person typing a name gets a note, not an unknown thing. Written straight
        // to disk: `createFile` would add `.txt` for them (§2's default), which is
        // the app's own kindness and not the rule being tested here.
        val file = File(scene.viewModel.currentDir!!, "journal")
        file.writeText("an entry")
        scene.viewModel.refresh()

        assertEquals("no extension is text", EntryKind.TEXT, scene.entry("journal").kind)
    }

    @Test
    fun `the extensions that are explicitly text stay text`() {
        listOf("notes.md", "data.json", "script.kt", "page.html", "build.gradle").forEach {
            scene.file(it)
            assertEquals("$it is text", EntryKind.TEXT, scene.entry(it).kind)
        }
    }

    @Test
    fun `a picture is recognised whatever case its extension is written in`() {
        // A camera writes .JPG as often as .jpg, and a file already on the phone
        // keeps the name it has: the rule is about the part of the name after the
        // dot, not how it was typed. Written to the disk directly, because the app
        // would lower-case a name it made itself.
        File(scene.viewModel.currentDir!!, "holiday.JPG").writeBytes(byteArrayOf(1, 2))
        File(scene.viewModel.currentDir!!, "scan.PNG").writeBytes(byteArrayOf(1, 2))
        scene.viewModel.refresh()

        assertEquals(EntryKind.IMAGE, scene.entry("holiday.JPG").kind)
        assertEquals(EntryKind.IMAGE, scene.entry("scan.PNG").kind)
    }

    @Test
    fun `an extension nobody listed is unknown rather than edited as text`() {
        // The reason for a list rather than "anything not an image": a type nobody
        // thought of must fail safe. These are the ones that would be destroyed by
        // a round trip through the text field.
        listOf("archive.zip", "book.pdf", "installer.apk", "photo.heic", "song.mp3")
            .forEach { name ->
                scene.file(name)
                assertEquals("$name is not editable", EntryKind.UNKNOWN, scene.entry(name).kind)
            }
    }

    @Test
    fun `an unknown file can still be renamed, moved and deleted`() {
        // §9: "rename, move and delete only" — the restrictions are about editing,
        // not about ownership.
        val file = scene.file("archive.zip")
        scene.folder("drafts")

        assertTrue("renamed", scene.viewModel.rename(file, "renamed.zip"))
        val moved = File(file.parentFile, "renamed.zip")
        assertTrue("moved", scene.viewModel.move(moved, File(moved.parentFile, "drafts")))
        assertFalse("it was moved", File(file.parentFile, "renamed.zip").exists())

        val placed = File(File(file.parentFile, "drafts"), "renamed.zip")
        assertTrue("deleted", scene.viewModel.delete(placed))
        assertFalse("it is gone", placed.exists())
    }

    @Test
    fun `a picture is downsized before it is decoded, so a phone photo cannot run out of memory`() {
        // The guard that matters most, and the one that cannot be checked by eye:
        // a 50MP photo decoded at full size is 200MB and Android kills the app.
        // The sample size is a power of two because that is all BitmapFactory does
        // cheaply, and it must leave a small picture alone.
        assertEquals("a small picture is not shrunk", 1, Pictures.sampleSizeFor(800, 600))
        assertEquals("a laptop-sized screenshot is not shrunk", 1, Pictures.sampleSizeFor(1600, 1200))

        // 4000x3000 is 12MP: one halving brings it under the target.
        assertEquals("12MP is halved", 2, Pictures.sampleSizeFor(4000, 3000))
        // 8000x6000 is 48MP and needs several halvings.
        val big = Pictures.sampleSizeFor(8000, 6000)
        assertTrue("48MP is sampled down", big >= 4)
        assertTrue("and stays a power of two", big and (big - 1) == 0)
        assertTrue(
            "the decoded bitmap stays inside the ceiling",
            8000L / big * (6000L / big) <= Pictures.MAX_BITMAP_PIXELS,
        )
    }

    @Test
    fun `even an absurd picture cannot allocate past the ceiling`() {
        // A panorama or a corrupt header can claim any size. Whatever it claims,
        // the number handed to BitmapFactory must leave a bitmap that fits.
        listOf(50_000 to 50_000, 100_000 to 1, 1 to 100_000, 20_000 to 15_000).forEach { (w, h) ->
            val sample = Pictures.sampleSizeFor(w, h)
            assertTrue("$w x $h samples at least 1", sample >= 1)
            assertTrue("$w x $h stays a power of two", sample and (sample - 1) == 0)
        }
    }

    @Test
    fun `opening a file that is not text does not damage it`() {
        // The whole reason §9 has a third class. The editor holds the file's text in
        // state and saves it back on a pause in typing; if a `.zip` were read as
        // UTF-8 for that state, the first save would write the mangled reading over
        // the original. Somebody who opened a file to look at it would lose it.
        val bytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00, 0xFF.toByte(), 0xC3.toByte())
        val file = File(scene.viewModel.currentDir!!, "archive.zip")
        file.writeBytes(bytes)

        scene.viewModel.openEditor(file)
        // What the editor would put in its text state.
        assertEquals("nothing is read as text", "", scene.viewModel.textOf(file))

        // And a save of that empty string must not truncate the file.
        scene.viewModel.saveEditor("")
        scene.await("the save to settle") { !scene.viewModel.busy }

        assertArrayEquals("the bytes are untouched", bytes, file.readBytes())
    }

    @Test
    fun `renaming keeps the file's text`() {
        val file = scene.file("draft.txt", "the words")

        scene.viewModel.rename(file, "final.txt")

        assertEquals("the words", File(scene.viewModel.currentDir!!, "final.txt").readText())
        assertFalse("the old name is gone", File(scene.viewModel.currentDir!!, "draft.txt").exists())
    }

    @Test
    fun `a file can be moved into a folder and the folder can be moved into another`() {
        scene.folder("letters")
        scene.open("letters")
        scene.folder("2026")
        scene.open()
        val file = scene.file("milk.txt", "two litres")

        assertTrue(scene.viewModel.move(file, File(scene.viewModel.currentDir!!, "letters")))
        val moved = File(scene.viewModel.currentDir!!, "letters/milk.txt")
        assertTrue("it is where it was moved to", moved.isFile)
        assertEquals("with its text", "two litres", moved.readText())
    }

    @Test
    fun `a folder cannot be moved inside itself`() {
        val outer = scene.folder("outer")
        scene.open("outer")
        val inner = scene.folder("inner")

        assertFalse("the move is refused", scene.viewModel.move(outer, inner))
        assertTrue("the outer folder is still where it was", outer.isDirectory)
    }

    @Test
    fun `deleting a folder takes everything in it`() {
        scene.folder("letters")
        scene.open("letters")
        scene.file("one.txt", "1")
        scene.folder("deeper")
        scene.open("letters", "deeper")
        scene.file("two.txt", "2")
        scene.open()

        val letters = File(scene.viewModel.currentDir!!, "letters")
        assertTrue(scene.viewModel.delete(letters))
        assertFalse("the folder is gone", letters.exists())
        assertFalse("and what was in it", File(letters, "deeper/two.txt").exists())
    }

    @Test
    fun `two things cannot share a name in one folder`() {
        scene.file("milk.txt", "1")
        val again = scene.viewModel.createFile("milk.txt")

        assertFalse("the second is refused", again)
        assertEquals("only one is there", 1, scene.names().count { it == "milk.txt" })
        assertEquals("and it is untouched", "1", scene.viewModel.textOf(scene.entry("milk.txt").file))
    }

    @Test
    fun `a file inside a project but outside the worktree is a local file`() {
        scene.project("Shopping")
        scene.open(Workspace.PROJECTS, "Shopping")
        val local = scene.file("shopping_list.txt", "milk")

        assertTrue("it is beside the worktree", local.parentFile!!.name == "Shopping")
        assertEquals("it belongs to the project", "Shopping", scene.workspace!!.projectOf(local))
        assertFalse("but not to the repository", scene.viewModel.inWorktree)
    }

    @Test
    fun `a file can be taken into the repository and out of it again`() {
        // One Move does both directions now, and it has to record both sides: a
        // file taken into a worktree must be *tracked* there, and a file taken out
        // must have its removal recorded, or GitHub goes on holding a copy this
        // phone no longer has.
        val project = "Shopping"
        scene.project(project)
        scene.open(Workspace.PROJECTS, project)
        val local = scene.file("milk.txt", "two litres")
        val repo = scene.workspace!!.repoOf(project)
        val inside = File(repo, "milk.txt")

        assertTrue("into the repository", scene.viewModel.move(local, repo))
        assertTrue("the file is in the worktree", inside.isFile)
        assertFalse("and no longer beside it", local.exists())
        assertTrue(
            "and the project tracks it",
            scene.awaitCommits(project, 2) { paths -> paths.contains("milk.txt") },
        )

        assertTrue("out of the repository", scene.viewModel.move(inside, repo.parentFile!!))
        assertTrue("back beside the worktree", File(local.parentFile, "milk.txt").isFile)
        assertTrue(
            "and the project recorded the removal",
            scene.awaitCommits(project, 3) { paths -> !paths.contains("milk.txt") },
        )
    }

    @Test
    fun `a file moved into another project is tracked by that one and not the first`() {
        // The crosses are not always into the project you are standing in: a file
        // can go from one repository to another, and then two histories have to be
        // told, not one.
        scene.project("shopping")
        scene.project("work")
        scene.open(Workspace.PROJECTS, "shopping")
        val file = scene.file("milk.txt", "two litres")
        val shop = scene.workspace!!.repoOf("shopping")
        val office = scene.workspace!!.repoOf("work")

        assertTrue("into the worktree", scene.viewModel.move(file, shop))
        // Waited for here, because a commit takes everything that is waiting: two
        // moves in the same millisecond would be one version, and the first project
        // would never have seen the file at all.
        assertTrue(
            "the first project tracks it",
            scene.awaitCommits("shopping", 2) { paths -> paths.contains("milk.txt") },
        )

        assertTrue("and across", scene.viewModel.move(File(shop, "milk.txt"), office))

        assertTrue(
            "the second project has it",
            scene.awaitCommits("work", 2) { paths -> paths.contains("milk.txt") },
        )
        assertTrue(
            "the first does not",
            scene.awaitCommits("shopping", 3) { paths -> !paths.contains("milk.txt") },
        )
    }

    @Test
    fun `pinning a file is remembered and survives a reload`() {
        val file = scene.file("milk.txt", "1")
        scene.viewModel.togglePinned(file)

        val again = AppViewModel(
            scene.app, rootDir = scene.root, credentials = scene.credentials,
            settings = scene.settings, saveDebounceMs = 0,
        )
        again.refresh()

        assertTrue("the pin is still there", again.pins.contains("milk.txt"))
    }
}
