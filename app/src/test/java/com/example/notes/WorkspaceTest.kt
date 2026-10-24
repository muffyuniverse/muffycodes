package com.example.notes

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The tree rules of §2 and §3: what may be seen, what may be touched, how a file
 * is classified, and the moves across the worktree boundary.
 */
class WorkspaceTest {

    private lateinit var root: File
    private lateinit var workspace: Workspace

    @Before
    fun makeSpace() {
        root = File(System.getProperty("java.io.tmpdir"), "workspace-${System.nanoTime()}")
        workspace = Workspace(File(root, "muffyuniverse"))
        workspace.accountDir.mkdirs()
        workspace.projectsDir.mkdirs()
    }

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun hiddenThingsAreNeverListed() {
        val dir = workspace.accountDir
        File(dir, "visible.txt").writeText("hi")
        File(dir, ".gitignore").writeText("x")
        File(dir, ".muffy").mkdirs()
        val names = workspace.list(dir).map { it.name }
        assertEquals(listOf("projects", "visible.txt"), names)
    }

    @Test
    fun foldersComeBeforeFiles() {
        val dir = workspace.accountDir
        File(dir, "zebra.txt").writeText("")
        File(dir, "Alpha").mkdirs()
        val names = workspace.list(dir).map { it.name }
        assertEquals(listOf("Alpha", "projects", "zebra.txt"), names)
    }

    @Test
    fun aPictureIsKnownByItsExtension() {
        val dir = workspace.accountDir
        assertEquals(EntryKind.IMAGE, workspace.kindOf(File(dir, "photo.PNG")))
        assertEquals(EntryKind.TEXT, workspace.kindOf(File(dir, "notes.txt")))
        assertEquals(EntryKind.TEXT, workspace.kindOf(File(dir, "README.md")))
        assertEquals(EntryKind.FOLDER, workspace.kindOf(File(dir, "somewhere").apply { mkdirs() }))
    }

    @Test
    fun aNameWithNoExtensionGetsTxt() {
        assertEquals("notes.txt", Workspace.withDefaultExtension("notes"))
        assertEquals("notes.md", Workspace.withDefaultExtension("notes.md"))
        assertEquals("photo.png", Workspace.withDefaultExtension("photo.png"))
    }

    @Test
    fun aNameStartingWithADotIsRefused() {
        assertTrue(workspace.nameProblem(".ssh") != null)
        assertTrue(workspace.nameProblem("a/b") != null)
        assertTrue(workspace.nameProblem("  ") != null)
        assertNull(workspace.nameProblem("Shopping list.txt"))
    }

    @Test
    fun theStructuralFoldersCannotBeTouched() {
        val projects = workspace.projectsDir
        assertTrue(workspace.isStructural(projects))
        val project = File(projects, "Shopping").apply { mkdirs() }
        val repo = File(project, Workspace.REPO).apply { mkdirs() }
        assertTrue(workspace.isStructural(repo))
        assertFalse(workspace.isStructural(project))
        assertTrue(workspace.delete(projects).isFailure)
        assertTrue(workspace.rename(projects, "other").isFailure)
        assertTrue(projects.isDirectory)
    }

    @Test
    fun aFolderCannotBeMovedInsideItself() {
        val outer = File(workspace.accountDir, "outer").apply { mkdirs() }
        val inner = File(outer, "inner").apply { mkdirs() }
        assertTrue(workspace.move(outer, inner).isFailure)
        assertTrue(workspace.move(outer, outer).isFailure)
        assertTrue(outer.isDirectory)
    }

    @Test
    fun aFileCanBeMovedBesideTheWorktreeAndBackIn() {
        val project = workspace.createProjectFolders("Shopping").getOrThrow()
        val repo = File(project, Workspace.REPO)
        val file = File(repo, "Milk.txt").apply { writeText("milk") }

        // Out of the worktree: it becomes local, and GitHub stops hearing about it.
        assertTrue(workspace.move(file, project).isSuccess)
        assertTrue(File(project, "Milk.txt").isFile)
        assertFalse(File(repo, "Milk.txt").exists())

        // And back in.
        assertTrue(workspace.move(File(project, "Milk.txt"), repo).isSuccess)
        assertTrue(File(repo, "Milk.txt").isFile)
    }

    @Test
    fun theProjectOfAPathIsFound() {
        val project = workspace.createProjectFolders("Shopping").getOrThrow()
        val repo = File(project, Workspace.REPO)
        val file = File(repo, "Milk.txt")
        assertEquals("Shopping", workspace.projectOf(file))
        assertEquals("Shopping", workspace.projectAt(listOf(Workspace.PROJECTS, "Shopping", Workspace.REPO)))
        assertNull(workspace.projectAt(listOf("projects")))
        assertNull(workspace.projectAt(emptyList()))
    }

    @Test
    fun projectsAreListedByName() {
        workspace.createProjectFolders("beta")
        workspace.createProjectFolders("Alpha")
        assertEquals(listOf("Alpha", "beta"), workspace.projectNames())
    }

    @Test
    fun pinsAreKeptInTheAppStateAndSurviveARestart() {
        val file = File(workspace.accountDir, "ideas.txt").apply { writeText("x") }
        val path = workspace.pathString(workspace.relative(file)!!)
        workspace.setPins(setOf(path))

        val readBack = Workspace(workspace.accountDir)
        assertTrue(readBack.isPinned(file))
        assertFalse(readBack.isPinned(File(workspace.accountDir, "other.txt")))
    }

    @Test
    fun writingReplacesAFileWhole() {
        val file = File(workspace.accountDir, "note.txt")
        workspace.write(file, "one")
        workspace.write(file, "two")
        assertEquals("two", file.readText())
        assertNotNull(workspace.relative(file))
    }

    @Test
    fun aNameIsTakenAndSaysSo() {
        workspace.createFile(workspace.accountDir, "note.txt", "a")
        val second = workspace.createFile(workspace.accountDir, "note.txt", "b")
        assertTrue(second.isFailure)
        assertTrue(second.exceptionOrNull()?.message.orEmpty().contains("already"))
    }

    @Test
    fun anOldRepoFolderBecomesGitHubAndKeepsItsFiles() {
        // An installation from before the rename: `projects/testrepo/repo/`, with a file
        // and a lock on it stored by the old path.
        val worktree = File(workspace.projectsDir, "testrepo/repo")
        worktree.mkdirs()
        File(worktree, "milk.txt").writeText("two litres")
        File(worktree, ".git").mkdirs()
        workspace.setLocked(setOf("projects/testrepo/repo/milk.txt"))

        workspace.ensure()

        val renamed = File(workspace.projectsDir, "testrepo/${Workspace.REPO}")
        assertFalse("the old folder is gone", worktree.exists())
        assertTrue("the new one is there", renamed.isDirectory)
        assertEquals("with its files", "two litres", File(renamed, "milk.txt").readText())
        assertTrue("and its repository", File(renamed, ".git").isDirectory)
        assertEquals(
            "and the lock followed the file, rather than silently lifting",
            setOf("projects/testrepo/${Workspace.REPO}/milk.txt"),
            workspace.locked(),
        )
    }

    @Test
    fun aProjectWithBothFoldersIsLeftAlone() {
        // A half-finished migration, or a person's own `repo` folder beside the worktree:
        // nothing is renamed over, because the destination already exists.
        File(workspace.projectsDir, "testrepo/${Workspace.REPO}").mkdirs()
        val old = File(workspace.projectsDir, "testrepo/repo")
        old.mkdirs()
        File(old, "keep.txt").writeText("mine")

        workspace.ensure()

        assertTrue("the old folder stays where it is", old.isDirectory)
        assertEquals("and so does its file", "mine", File(old, "keep.txt").readText())
    }

    @Test
    fun readingAMissingFileIsEmptyRatherThanACrash() {
        assertEquals("", workspace.read(File(workspace.accountDir, "gone.txt")))
    }
}
