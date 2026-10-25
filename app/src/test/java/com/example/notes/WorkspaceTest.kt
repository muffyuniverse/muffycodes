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

    // ---- the agent's record is kept, not editable -----------------------------

    /** One row of the record, in the columns the app writes. */
    private val FIRST_ROW = "14:02:11.410    1 TOLD    in  operator  task\n"

    private fun record(): File {
        val dir = File(workspace.projectsDir, "shopping")
        val agent = File(dir, AgentLog.DIR)
        agent.mkdirs()
        File(dir, "project_prompt.txt").writeText("how to work")
        return File(agent, AgentLog.SESSION).apply { writeText(FIRST_ROW) }
    }

    @Test
    fun `the record is one file, and a file of that name elsewhere is not it`() {
        val session = record()
        val prompt = File(session.parentFile.parentFile, "project_prompt.txt")
        // A record written by an older build: its second file is still the app's, so a person
        // cannot edit it while the update folds it in.
        val legacy = File(session.parentFile, AgentLog.CONTEXT).apply { writeText(FIRST_ROW) }

        assertTrue("the record", workspace.isRecord(session))
        assertTrue("and an old build's context, until it is folded in", workspace.isRecord(legacy))
        assertFalse("the project's own instructions are the person's", workspace.isRecord(prompt))
        assertFalse(
            "a file with the record's name somewhere else is not the record",
            run {
                val elsewhere = File(workspace.projectsDir, "elsewhere")
                elsewhere.mkdirs()
                workspace.isRecord(File(elsewhere, AgentLog.SESSION).apply { writeText("x") })
            },
        )
    }

    @Test
    fun `nobody writes the record through this class, and there is no second door`() {
        // **The refusal is the app's, not the system's** — a file manager can edit these files, and
        // nothing here pretends otherwise. What it stops is the app itself offering a Save that
        // destroys the agent's memory. There used to be a `writeRecord` door for the agent's
        // clear-up; compaction is now a list of ids applied by AgentLog itself, so the door was
        // removed rather than left open for the next caller who thought of a reason.
        val context = record()
        val text = FIRST_ROW.replace("task", "something else")

        assertFalse("the person's funnel refuses", workspace.write(context, text))
        assertEquals("and the file is untouched", FIRST_ROW, context.readText())
        assertFalse("and there is no other door", workspace.write(context, text))
    }

    @Test
    fun `the record cannot be renamed or deleted one file at a time`() {
        // Deleting the project still takes it, which is the lever a person keeps; a single log file
        // is not theirs to remove, or the record ends at the first convenient moment.
        val context = record()
        val deleted = workspace.delete(context)
        assertTrue("delete is refused: $deleted", deleted.isFailure)
        assertTrue("and it is still there", context.isFile)

        val renamed = workspace.rename(context, "notes.log")
        assertTrue("rename is refused: $renamed", renamed.isFailure)
        assertTrue("and it still has its name", context.isFile)
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

    /**
     * The lock, at the funnel rather than at the screens.
     *
     * Every test here goes through `Workspace` and never through the view model, because
     * that is the claim: a lock holds for **any** caller. It used to hold only for the
     * screens that remembered to ask, so the editor refused and a restore did not — and a
     * lock that some writers honour is a habit, not a lock.
     */
    private fun lock(vararg paths: String) {
        workspace.setLocked(paths.toSet())
    }

    @Test
    fun aLockedFileIsNotWrittenByTheFunnel() {
        val file = File(workspace.accountDir, "milk.txt")
        workspace.write(file, "two litres")
        lock("milk.txt")

        assertFalse("the write is refused", workspace.write(file, "three litres"))
        assertEquals("and the text did not change", "two litres", file.readText())
    }

    @Test
    fun aLockOnAFolderCoversWhatIsInsideIt() {
        // The bug this is for: locking a folder protected the folder and nothing in it, so
        // the files a person had just frozen were all still editable.
        val folder = File(workspace.accountDir, "drafts")
        folder.mkdirs()
        val inside = File(folder, "milk.txt")
        workspace.write(inside, "two litres")
        lock("drafts")

        assertTrue("the file inside is locked", workspace.isLocked(inside))
        assertFalse("so it is not written", workspace.write(inside, "three litres"))
        assertEquals("and keeps its text", "two litres", inside.readText())
        assertTrue("nor deleted", workspace.delete(inside).isFailure)
        assertTrue("and it is still there", inside.isFile)
        assertFalse(
            "nothing new can be made inside it either",
            workspace.createFile(folder, "butter").isSuccess,
        )
    }

    @Test
    fun aLockOnAFileDoesNotLockTheOneBesideItWithALongerName() {
        // A prefix comparison without its separator: locking `milk.txt` would also lock
        // `milk.txt.bak`, which is the kind of bug that reads as "the app is random".
        val file = File(workspace.accountDir, "milk.txt")
        val neighbour = File(workspace.accountDir, "milk.txt.bak")
        workspace.write(file, "a")
        workspace.write(neighbour, "b")
        lock("milk.txt")

        assertTrue("the file is locked", workspace.isLocked(file))
        assertFalse("the one with the longer name is not", workspace.isLocked(neighbour))
        assertTrue("and it can still be written", workspace.write(neighbour, "c"))
    }

    @Test
    fun copyingALockedFileOutIsAllowedBecauseCopyingDoesNotChangeIt() {
        // A lock is "read only", not "keep away": a copy is a read of the source, and the
        // source is untouched. Writing *into* a locked folder is the change that is refused.
        val folder = File(workspace.accountDir, "drafts")
        folder.mkdirs()
        val original = File(folder, "milk.txt")
        workspace.write(original, "two litres")
        val elsewhere = File(workspace.accountDir, "inbox")
        elsewhere.mkdirs()
        lock("drafts/milk.txt")

        val copy = workspace.copy(original, elsewhere)

        assertTrue("the copy is made", copy.isSuccess)
        assertEquals("with the same text", "two litres", copy.getOrThrow().readText())

        lock("inbox")
        assertTrue(
            "but copying into a locked folder is refused",
            workspace.copy(original, elsewhere).isFailure,
        )
    }

    @Test
    fun theLockIsRememberedAcrossAWorkspaceThatHasNotReadItYet() {
        // The flags live in `.muffy`, so a second Workspace over the same account sees the
        // lock without being told: the cache is a cache, not the truth.
        val file = File(workspace.accountDir, "milk.txt")
        workspace.write(file, "two litres")
        lock("milk.txt")

        val other = Workspace(workspace.accountDir)

        assertTrue("the lock is read from disk", other.isLocked(file))
        assertFalse("and it refuses a write too", other.write(file, "three litres"))
    }
}
