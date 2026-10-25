package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §14 of the design: **one length, applied at the next change.**
 *
 * A project's history is the only history there is — the account level one is gone, because every
 * folder under the account is a project — so "which history" is no longer a question this file has
 * to answer.
 *
 * Shortening a history throws away the oldest end of it and nothing else. Not one
 * byte of the working copy changes, and nothing is pruned while the number is
 * being typed.
 */
class RetentionFeatureTest : FeatureTest() {

    /** The length of the open project's history — the one history a change goes into. */
    private fun historySize(): Int =
        (scene.viewModel.currentProject ?: scene.viewModel.projectNames.firstOrNull())
            ?.let { scene.repo(it).log(null).size }
            ?: 0

    @Test
    fun `an empty length keeps everything`() {
        assertEquals("zero means all of it", 0, scene.settings.historyLength)
    }

    @Test
    fun `an empty field is read as keep everything, and any number as that many`() {
        assertEquals(0, SettingsStore.parseLength(""))
        assertEquals(0, SettingsStore.parseLength("   "))
        assertEquals(7, SettingsStore.parseLength("7"))
        assertEquals(0, SettingsStore.parseLength("not a number"))
        assertEquals(0, SettingsStore.parseLength("-3"))
    }

    @Test
    fun `a length is shown as an empty field when it means everything`() {
        assertEquals("", SettingsStore.showLength(0))
        assertEquals("12", SettingsStore.showLength(12))
    }

    @Test
    fun `shortening a history happens at the next change, not when it is set`() {
        scene.project("Shopping")
        scene.open("Shopping")
        repeat(6) { scene.folder("folder_$it") }
        scene.await("six commits") { historySize() >= 6 }
        val before = historySize()

        scene.settings.keepHistory(3)

        assertEquals("nothing has happened yet", before, historySize())
    }

    @Test
    fun `after the next change the history keeps only the length it was given`() {
        scene.project("Shopping")
        scene.open("Shopping")
        repeat(6) { scene.folder("folder_$it") }
        scene.await("six commits") { historySize() >= 6 }
        scene.settings.keepHistory(3)

        scene.folder("the_last_one")
        scene.await("the history to be shortened") { historySize() <= 3 }

        assertEquals("three versions are kept", 3, historySize())
    }

    @Test
    fun `shortening the history does not touch a single file`() {
        scene.project("Shopping")
        scene.open("Shopping")
        repeat(5) { scene.folder("folder_$it") }
        scene.open("Shopping", "folder_2")
        val file = scene.file("milk.txt", "two litres")
        scene.open("Shopping")
        scene.await("the commits") { historySize() >= 5 }

        scene.settings.keepHistory(2)
        scene.folder("after_the_shortening")
        scene.await("the history to be shortened") { historySize() <= 2 }

        assertEquals("the file is exactly as it was", "two litres", file.readText())
        assertTrue("and it is still where it was", file.isFile)
    }

    @Test
    fun `a length longer than the history keeps the history it has`() {
        scene.project("Shopping")
        scene.open("Shopping")
        scene.folder("one")
        scene.await("a commit") { historySize() >= 1 }
        val before = historySize()

        scene.settings.keepHistory(50)
        scene.folder("two")
        scene.await("the next change") { historySize() > before }

        assertTrue("nothing was thrown away", historySize() >= before)
    }

    @Test
    fun `a project's history is shortened after the next sync`() {
        scene.project("Shopping")
        scene.open("Shopping")
        repeat(5) { round ->
            scene.worktreeFile("file-$round.txt", "round $round")
            scene.sync("Shopping")
        }
        val before = scene.remoteCommits("Shopping")
        assertTrue("there are several commits to shorten, found $before", before >= 5)

        scene.settings.keepHistory(2)
        scene.worktreeFile("last.txt", "the last round")
        scene.sync("Shopping")

        assertTrue(
            "the project keeps the length it was given\n${scene.diagnose()}",
            scene.remoteCommits("Shopping") <= 2,
        )
    }

    @Test
    fun `a project's files are untouched when its history is shortened`() {
        scene.project("Shopping")
        scene.open("Shopping")
        repeat(4) { round ->
            scene.worktreeFile("file-$round.txt", "round $round")
            scene.sync("Shopping")
        }
        scene.settings.keepHistory(1)
        scene.worktreeFile("last.txt", "kept")
        scene.sync("Shopping")

        assertEquals(
            "the newest file is still in the worktree",
            "kept",
            File(scene.workspace!!.repoOf("Shopping"), "last.txt").readText(),
        )
    }

    @Test
    fun `one number governs the phone and each project alike`() {
        // The field used to be two, and the difference between them was invisible:
        // somebody setting a limit is answering "how far back can I go?", which is
        // one question. So one number, and the last thing typed wins.
        scene.settings.keepHistory(4)
        assertEquals("the number that was set", 4, scene.settings.historyLength)

        scene.settings.keepHistory(9)
        assertEquals("and setting it again replaces it", 9, scene.settings.historyLength)
    }

    @Test
    fun `an older build's two lengths survive as the one number`() {
        // Every build before this one wrote two keys. Reading the larger of them is
        // deliberate: a person who had set either history longer meant to be able to
        // go back that far, and reading the smaller would delete versions they had
        // asked to keep — silently, on the first change after the update.
        val name = "legacy-${System.nanoTime()}"
        scene.app.getSharedPreferences(name, 0).edit()
            .putInt(SettingsStore.KEY_APP_HISTORY, 4)
            .putInt(SettingsStore.KEY_PROJECT_HISTORY, 9)
            .apply()

        val settings = SettingsStore(scene.app, name)

        assertEquals("the larger of the two is kept", 9, settings.historyLength)
    }

    @Test
    fun `a length set now is not overwritten by the old keys`() {
        // The migration must run once. If it kept reading the legacy keys, a person
        // who had 9 and set 2 would find 9 again on the next start.
        val name = "legacy2-${System.nanoTime()}"
        scene.app.getSharedPreferences(name, 0).edit()
            .putInt(SettingsStore.KEY_APP_HISTORY, 9)
            .putInt(SettingsStore.KEY_PROJECT_HISTORY, 9)
            .apply()

        SettingsStore(scene.app, name).keepHistory(2)
        val reopened = SettingsStore(scene.app, name)

        assertEquals("the number that was set", 2, reopened.historyLength)
    }

    @Test
    fun `a length survives the app being started again`() {
        scene.settings.keepHistory(5)

        assertEquals(5, scene.settings.historyLength)
    }
}
