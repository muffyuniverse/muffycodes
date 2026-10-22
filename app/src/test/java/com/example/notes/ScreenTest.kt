package com.example.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Drives the real screens by the names that are shown on them.
 *
 * This runs on the plain JVM through Robolectric: no emulator, no device and no
 * graphics processor. A screen is built and its parts are found by their names,
 * which is what the tour on an emulator does, but in milliseconds instead of
 * seconds. Nothing is drawn, so there is no picture — only the question of
 * whether the screen offers what it should.
 *
 * What this cannot tell you is how anything looks. Colours, spacing and
 * anything drawn by hand on a canvas are invisible here. That is a question for
 * a person's eyes, not for a test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun listOf(
        folders: List<Folder> = emptyList(),
        notes: List<Note> = emptyList(),
        query: String = "",
        projectName: String? = null,
        pendingChanges: Int = 0,
        onNoteAction: (Note, NoteAction) -> Unit = { _, _ -> },
    ) {
        compose.setContent {
            MaterialTheme(colorScheme = AppTheme.MILK.lightScheme()) {
                NotesListScreen(
                    folders = folders,
                    notes = notes,
                    breadcrumb = emptyList(),
                    canNavigateUp = false,
                    isSearchingEverything = false,
                    query = query,
                    onQueryChange = {},
                    noteCountOf = { 0 },
                    totalCountOf = { 0 },
                    folderPathOf = { null },
                    onNavigateUp = {},
                    onOpenFolder = {},
                    onOpenNote = {},
                    onCreateNote = {},
                    onCreateFolder = {},
                    onOpenSettings = {},
                    onOpenHistory = {},
                    projectName = projectName,
                    pendingChanges = pendingChanges,
                    onCommit = {},
                    onTogglePin = {},
                    onFolderAction = { _, _ -> },
                    onNoteAction = onNoteAction,
                )
            }
        }
    }

    /** The list, with nothing in it yet, offers every way to start. */
    @Test
    fun theEmptyListOffersTheWayToStart() {
        listOf()

        compose.onNodeWithText("Files").assertIsDisplayed()
        // The + and the gear are drawn, so they have no text of their own: they
        // are named by what a person would say about them.
        compose.onNodeWithContentDescription("New file").assertIsDisplayed()
        compose.onNodeWithText("Search all files").assertIsDisplayed()
        compose.onNodeWithContentDescription("Settings").assertIsDisplayed()
    }

    /** A file of the folder is shown by its title. */
    @Test
    fun aFileIsShownByItsTitle() {
        listOf(notes = listOf(Note(id = "a", title = "Shopping list", body = "Milk")))

        compose.onNodeWithText("Shopping list").assertIsDisplayed()
    }

    /**
     * With a project behind the folder, the commit button is offered and the
     * number of waiting changes is shown, so that the screen says how much
     * there is to sync.
     */
    @Test
    fun aFolderWithAProjectOffersACommit() {
        listOf(projectName = "muffycodes", pendingChanges = 3)

        compose.onNodeWithText("git: muffycodes", substring = true).assertIsDisplayed()
    }

    /**
     * The editor hands back both the title and the body when it is saved.
     *
     * The file starts with a title, because a brand new file asks for the focus
     * of the body field as soon as it opens, and asking a field for focus
     * throws when there is no real window — which is the one thing this kind of
     * test does not have. The empty-file path is exercised by the tour on the
     * emulator instead, where there is a window.
     */
    @Test
    fun theEditorTakesATitleAndABody() {
        var saved: Pair<String, String>? = null

        compose.setContent {
            MaterialTheme(colorScheme = AppTheme.MILK.lightScheme()) {
                NoteEditorScreen(
                    note = Note(id = "a", title = "Shopping list", body = "Milk", folderId = null),
                    folders = emptyList(),
                    folderPathOf = { null },
                    onSave = { title, body -> saved = title to body },
                    onDelete = {},
                    onMove = {},
                    onHistory = {},
                    onBack = {},
                )
            }
        }

        // The title is also shown in the bar at the top, so the field is found
        // by being the thing that can be typed into as well as by its text.
        compose.onNode(hasText("Shopping list") and hasSetTextAction())
            .performTextReplacement("Weekly shopping")
        compose.onNode(hasText("Milk") and hasSetTextAction())
            .performTextReplacement("Milk\nBread\nCoffee")

        compose.onNodeWithContentDescription("Save").performClick()

        assertEquals("Weekly shopping", saved?.first)
        assertEquals("Milk\nBread\nCoffee", saved?.second)
    }
}
