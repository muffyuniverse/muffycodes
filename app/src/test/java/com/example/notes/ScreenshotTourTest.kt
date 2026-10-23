package com.example.notes

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Draws the screens and saves them as pictures, on the plain JVM.
 *
 * This is the tour that ships with every build, so it has to be quick and it
 * has to run anywhere — which is why it is Robolectric and not an emulator.
 * There is no device and no waiting; a screen is built, drawn to a bitmap and
 * written to a file in milliseconds.
 *
 * What these pictures are for is the question "does the feature work, and is
 * the screen I expected actually there". What they are NOT for is "does it look
 * right": a JVM draws its own fonts and spacing, and the truth of appearance is
 * the operator's eyes on a real install. The emulator tour, run separately on a
 * release, is where that truth lives. Read these as evidence that the screens
 * render and the features are present — not as the final word on how they look.
 *
 * Rendering is real here (GraphicsMode.NATIVE), so the pixels are genuine
 * Compose output rather than a shadow's placeholder. That is the one thing that
 * makes a picture worth saving at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTourTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * Pictures are only worth making when the run is recording. The ordinary
     * test run reaches this class too, and it must do nothing: an assumption
     * that fails here marks the tour skipped, so the suite that decides whether
     * the code is right is never slowed down or given images to carry.
     */
    @Before
    fun onlyMakePicturesWhenRecording() {
        Assume.assumeTrue(System.getProperty("roborazzi.test.record") == "true")
    }

    /**
     * Draws whatever is on the screen and saves it under build/outputs/roborazzi,
     * where the workflow picks the pictures up. The directory is made here
     * rather than trusted to exist, because a test run starts with nothing.
     */
    private fun shot(name: String) {
        val dir = File("build/outputs/roborazzi")
        dir.mkdirs()
        compose.onRoot().captureRoboImage(File(dir, name).path)
    }

    private fun showList(
        folders: List<Folder> = emptyList(),
        notes: List<Note> = emptyList(),
        projectName: String? = null,
        pendingChanges: Int = 0,
    ) {
        compose.setContent {
            MaterialTheme(colorScheme = AppTheme.MILK.lightScheme()) {
                NotesListScreen(
                    folders = folders,
                    notes = notes,
                    breadcrumb = emptyList(),
                    canNavigateUp = false,
                    isSearchingEverything = false,
                    query = "",
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
                    onNoteAction = { _, _ -> },
                )
            }
        }
    }

    /** A brand new app: every way to start is offered. */
    @Test
    fun theEmptyScreen() {
        showList()
        shot("tour-01-list-empty.png")
    }

    /**
     * A folder holding a project and some files: the git subtitle and the count
     * of waiting changes are the feature this picture is meant to show.
     */
    @Test
    fun listWithAProjectAndFiles() {
        showList(
            folders = listOf(Folder(id = "f1", name = "Work", parentId = null)),
            notes = listOf(
                Note(id = "a", title = "Shopping list", body = "Milk"),
                Note(id = "b", title = "Ideas", body = "A notes app"),
            ),
            projectName = "muffycodes",
            pendingChanges = 3,
        )
        shot("tour-02-list-project.png")
    }

    /** An open file, with its title and body. */
    @Test
    fun editingANote() {
        compose.setContent {
            MaterialTheme(colorScheme = AppTheme.MILK.lightScheme()) {
                NoteEditorScreen(
                    note = Note(id = "a", title = "Shopping list", body = "Milk", folderId = null),
                    folders = emptyList(),
                    folderPathOf = { null },
                    onSave = { _, _ -> },
                    onDelete = {},
                    onMove = {},
                    onHistory = {},
                    onBack = {},
                )
            }
        }
        shot("tour-03-editor.png")
    }
}
