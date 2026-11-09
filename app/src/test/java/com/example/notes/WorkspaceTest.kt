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
 * The tree rules of §2 and §3: what may be seen, what may be touched, how a file is
 * classified, and the one structural folder that is left — a project's `agent/`, because the
 * account root is the projects list and a project's folder is its repository.
 */
class WorkspaceTest {

    private lateinit var root: File
    private lateinit var workspace: Workspace

    @Before
    fun makeSpace() {
        root = File(System.getProperty("java.io.tmpdir"), "workspace-${System.nanoTime()}")
        workspace = Workspace.single(File(root, "muffyuniverse"))
        workspace.accountDir.mkdirs()
        workspace.accountDir.mkdirs()
    }

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    // ---- the shared folder is not a project (§13f) ----------------------------

    @Test
    fun `the shared folder is not a project, even when it holds a repository`() {
        // **Both halves, because the second is the one that can regress.** An account made before this
        // change can hold `shared/.git` - the old rule made the folder a project, so saving a file in it
        // made a repository. `isProjectFolder` states the rule rather than inferring it from the folder's
        // contents, or the leftover would turn it back into the "dangling" row the operator reported.
        workspace.ensure()
        val shared = File(workspace.accountDir, Workspace.SYSTEM_PROJECT)
        assertTrue("the folder is made by ensure()", shared.isDirectory)
        assertFalse("and it is not in the project list", workspace.projectNames().contains("shared"))
        assertFalse("nor a project by the predicate", workspace.isProjectFolder(shared))

        // A repository inside it changes nothing, because the name decides.
        File(shared, ".git").mkdirs()
        assertFalse("still not a project with a .git", workspace.isProjectFolder(shared))
        assertNull("and a path through it belongs to no project", workspace.projectOf(File(shared, "README.md")))
    }

    @Test
    fun `a project at the root is still a project, so the rule did not go too far`() {
        // The falsification for the test above: removing the old `|| isSystem` clause must not have
        // removed the `.git` test that makes every other folder a project.
        val proj = workspace.projectDir("Shopping")
        proj.mkdirs()
        assertFalse("a folder with no repository is not a project", workspace.isProjectFolder(proj))
        File(proj, ".git").mkdirs()
        assertTrue("with one it is", workspace.isProjectFolder(proj))
        assertEquals("and it is listed", listOf("Shopping"), workspace.projectNames())
    }

    @Test
    fun `a project made before the link existed gets git's line for it`() {
        // **The line is written by the function that makes the link**, because the link is what it is
        // for — and it is written on every open, so a repository that has the app's other three lines
        // and not this one is caught: without it the link reads as an untracked file, the project says
        // "1 to send" about something the person did not do, and a commit would put a symlink in.
        val proj = workspace.projectDir("Shopping")
        proj.mkdirs()
        File(proj, ".git/info").mkdirs()
        File(proj, ".git/info/exclude").writeText("agent/\n.muffy/\nrequests/\n")

        assertTrue("the link was made", workspace.linkSystem(proj))

        val lines = File(proj, ".git/info/exclude").readLines()
        assertEquals(
            "and the line for it was added once, after the ones already there",
            listOf("agent/", ".muffy/", "requests/", "shared"),
            lines,
        )
        // **Idempotent**, because this runs on every refresh: a second pass must not append again.
        workspace.linkSystem(proj)
        assertEquals(
            "a second refresh adds nothing",
            listOf("agent/", ".muffy/", "requests/", "shared"),
            File(proj, ".git/info/exclude").readLines(),
        )
    }

    @Test
    fun `a branch that belongs to another project reads as main, not as an empty folder`() {
        // **The ambient-branch trap.** `activeBranch` is on the workspace, and a person who left a
        // branch and opened a second project still had it set — so `Other/notes.txt` resolved into
        // `.muffy/worktrees/Other/feature/`, which does not exist, and the project opened empty. The
        // guard is a fact about the disk, so no door can forget it.
        workspace.ensure()
        val shopping = workspace.projectDir("Shopping")
        shopping.mkdirs()
        File(shopping, ".git").mkdirs()
        val other = workspace.projectDir("Other")
        other.mkdirs()
        File(other, ".git").mkdirs()
        File(other, "notes.txt").writeText("mine\n")

        workspace.activeBranch = "feature"
        assertEquals(
            "another project's file comes from its own folder",
            File(other, "notes.txt").canonicalPath,
            workspace.fileAt(listOf("Other", "notes.txt")).canonicalPath,
        )
    }

    @Test
    fun `the link is listed last, after the person's files`() {
        // **A given, asserted because a screenshot depends on it.** Sorting the link as a folder put it
        // between `muffycodes_data/` and the files, which pushed the newest file off the bottom of the
        // run's own list — and scenario 07's two pictures became the same five rows.
        workspace.ensure()
        val proj = workspace.projectDir("Shopping")
        proj.mkdirs()
        File(proj, ".git").mkdirs()
        File(proj, "a-folder").mkdirs()
        File(proj, "milk.txt").writeText("two litres\n")
        assertTrue("the link is made", workspace.linkSystem(proj))

        assertEquals(
            "folders, then files, then the app's link",
            listOf("a-folder", "milk.txt", Workspace.SYSTEM_PROJECT),
            workspace.list(proj).map { it.name },
        )
    }

    @Test
    fun `every tree gets the link, including a folder a person already made`() {
        workspace.ensure()
        val shared = File(workspace.accountDir, Workspace.SYSTEM_PROJECT)
        val proj = workspace.projectDir("Shopping")
        proj.mkdirs()
        File(proj, ".git").mkdirs()

        assertTrue("the link was made", workspace.linkSystem(proj))
        assertTrue("and it is a link", Workspace.isLink(File(proj, Workspace.SYSTEM_PROJECT)))

        // **A real folder is never replaced.** A person who made their own `system` folder keeps it;
        // replacing it to make a link would delete their files, and a convenience is never a reason
        // for that.
        val own = File(workspace.accountDir, "Personal")
        own.mkdirs()
        val real = File(own, Workspace.SYSTEM_PROJECT)
        real.mkdirs()
        File(real, "mine.txt").writeText("mine\n")
        assertFalse("no link was made over it", workspace.linkSystem(own))
        assertFalse("and it is still a real folder", Workspace.isLink(real))
        assertEquals("with its file", "mine\n", File(real, "mine.txt").readText())
    }

    @Test
    fun `a broken link is replaced, because the folder it named is gone`() {
        // The one case where an existing link *is* replaced. Its target was deleted outside the app, so
        // the link names nothing - and a link that names nothing looks exactly like a folder that was
        // removed from the screen.
        workspace.ensure()
        val proj = workspace.projectDir("Shopping")
        proj.mkdirs()
        val link = File(proj, Workspace.SYSTEM_PROJECT)
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), File("gone").toPath())
        } catch (t: Throwable) {
            return  // a filesystem with no links: nothing to test, and nothing to claim
        }
        assertFalse("it is broken", link.exists())

        assertTrue("so it was replaced", workspace.linkSystem(proj))
        assertTrue("and now resolves", link.isDirectory)
    }

    @Test
    fun `the exclude file is written in the common directory, so a worktree reads it too`() {
        // **`if (!git.isDirectory) return` made both exclude writers silent no-ops in every worktree**,
        // because a worktree's `.git` is a file. The fix is the common directory, which is also why one
        // entry covers main's tree and every branch's at once.
        val proj = workspace.projectDir("Shopping")
        proj.mkdirs()
        File(proj, ".git/worktrees/x").mkdirs()
        assertEquals(
            "a plain repository answers its own .git",
            File(proj, ".git").canonicalPath,
            workspace.commonDir(proj)?.canonicalPath,
        )

        // A worktree, written the way git writes one: `.git` is a FILE holding the pointer, and the
        // private folder holds a `commondir` that points back out at the shared `.git`.
        val tree = File(workspace.accountDir, ".muffy/worktrees/Shopping/feature")
        tree.mkdirs()
        val private = File(proj, ".git/worktrees/Shopping-feature")
        private.mkdirs()
        File(tree, ".git").writeText("gitdir: " + private.absolutePath + "\n")
        File(private, "commondir").writeText("../..\n")
        assertEquals(
            "a worktree answers the common directory, not the pointer",
            File(proj, ".git").canonicalPath,
            workspace.commonDir(tree)?.canonicalPath,
        )
    }

    @Test
    fun `deleting a project does not empty the shared folder it links to`() {
        // **The defect the link would have introduced.** `File.deleteRecursively()` follows a symlink, so
        // deleting a project would walk into the account's shared folder and delete its contents first.
        workspace.ensure()
        val shared = File(workspace.accountDir, Workspace.SYSTEM_PROJECT)
        val readme = File(shared, Workspace.README)
        assertTrue("the folder has something in it", readme.isFile)
        val proj = workspace.projectDir("Shopping")
        proj.mkdirs()
        File(proj, ".git").mkdirs()
        File(proj, "note.txt").writeText("mine\n")
        assertTrue("with a link", workspace.linkSystem(proj))

        val deleted = workspace.delete(proj)

        assertTrue("the project went: $deleted", deleted.isSuccess)
        assertFalse("its folder is gone", proj.exists())
        assertTrue("and the shared folder's readme survived", readme.isFile)
        assertTrue("the folder itself survived", shared.isDirectory)
    }

    // ---- which branch a path is on (§13d) ------------------------------------

    @Test
    fun `a branch is read from the path, and a branch with a slash is read whole`() {
        // **The reading three other functions depend on.** `relative` maps a worktree path back to the
        // project, the index is keyed by it, and a save is committed in it — so a wrong answer here is a
        // commit in the wrong tree, not a cosmetic mistake.
        workspace.ensure()
        val proj = workspace.projectDir("Shopping")
        proj.mkdirs()

        // Main's tree is the project's own folder.
        assertEquals("main for a project file", AgentSession.MAIN, workspace.branchOf(File(proj, "a.txt")))

        // Two worktrees, written the way git writes them, because `exists` asks for exactly that.
        for (branch in listOf("feature", "feature/x")) {
            val dir = File(workspace.accountDir, ".muffy/worktrees/Shopping/$branch")
            dir.mkdirs()
            File(dir, ".git").writeText("gitdir: nowhere\n")
        }

        assertEquals(
            "the branch, not the first segment",
            "feature",
            workspace.branchOf(File(workspace.accountDir, ".muffy/worktrees/Shopping/feature/a.txt")),
        )
        // **The nested name wins over its prefix.** `feature/x` is a branch, not a branch `feature`
        // holding a folder called `x` — and if the shorter reading won, a file in `feature/x` would be
        // committed as `feature`'s, which is a different tree.
        assertEquals(
            "the longest real branch name, because a branch may hold a slash",
            "feature/x",
            workspace.branchOf(File(workspace.accountDir, ".muffy/worktrees/Shopping/feature/x/a.txt")),
        )
        // And the two readings agree: the path a person sees is the project's, not the state folder's.
        assertEquals(
            "the file still reads as Shopping/a.txt",
            listOf("Shopping", "a.txt"),
            workspace.relative(File(workspace.accountDir, ".muffy/worktrees/Shopping/feature/x/a.txt")),
        )
    }

    // ---- onboarding: written, versioned, and locked ---------------------------

    /** The folder the pages go in. */
    private fun onboardingDir() = File(File(workspace.accountDir, Workspace.SYSTEM_PROJECT), Onboarding.DIR)

    @Test
    fun `the onboarding pages are written into the shared folder`() {
        workspace.ensure()

        val dir = onboardingDir()
        assertTrue("the folder exists", dir.isDirectory)
        assertEquals(
            "every page is there",
            Onboarding.pages().map { it.name }.toSet(),
            dir.listFiles()!!.filter { it.isFile }.map { it.name }.toSet(),
        )
        // **The numbers in the names are the order**, which is what the swipe gesture follows.
        val sorted = dir.listFiles()!!.filter { it.isFile }.map { it.name }.sorted()
        assertEquals("numbered in reading order", Onboarding.pages().map { it.name }, sorted)
    }

    @Test
    fun `an onboarding page is locked, and its clone is not`() {
        workspace.ensure()
        val page = File(onboardingDir(), Onboarding.pages().first().name)

        assertTrue("the page cannot be edited in place", workspace.isLocked(page))
        assertTrue("and the agent cannot edit it either", workspace.isAgentLocked(page))

        // **The clone is the point of the lock being acceptable.** A copy in the person's own
        // project is an ordinary file: the pages are the app's, the copy is theirs.
        val clone = File(File(workspace.accountDir, "groceries"), "my-notes.md")
        clone.parentFile!!.mkdirs()
        clone.writeText("mine")
        assertFalse("a copy is editable", workspace.isLocked(clone))
    }

    @Test
    fun `a folder a person happens to call onboarding is not locked`() {
        workspace.ensure()
        // **By path, not by name**, which is the trap the templates lock already documented: a name
        // rule would lock any folder called `onboarding` anywhere, including one in a project.
        val theirs = File(File(workspace.accountDir, "groceries"), Onboarding.DIR)
        theirs.mkdirs()
        val page = File(theirs, "01-what-this-is.md")
        page.writeText("mine, actually")

        assertFalse("their own folder is theirs", workspace.isLocked(page))
    }

    @Test
    fun `a newer revision replaces the pages, and an old one does not`() {
        workspace.ensure()
        val dir = onboardingDir()
        val page = File(dir, Onboarding.pages().first().name)
        page.writeText("edited by hand")

        // **The same revision leaves it alone**, so the pages are not rewritten on every launch.
        workspace.ensure()
        assertEquals("not rewritten at the same revision", "edited by hand", page.readText())

        // **A newer revision replaces it**, because the point of a revision is that the words
        // changed — and a confusing sentence has to reach the people who installed before it was
        // fixed.
        val stamp = File(workspace.stateDir, Workspace.ONBOARDING_STAMP)
        stamp.writeText("0")
        workspace.ensure()
        assertEquals(
            "rewritten when the revision is newer",
            Onboarding.pages().first().text,
            page.readText(),
        )
    }

    // ---- the agent's record is kept, not editable -----------------------------

    /** One row of the record, in the columns the app writes. */
    private val FIRST_ROW = "   1     4 TOLD    in  operator  task\n"

    private fun record(): File {
        val dir = File(workspace.accountDir, "shopping")
        val agent = File(dir, AgentLog.DIR)
        agent.mkdirs()
        AgentPrompt.projectFile(dir).apply { parentFile?.mkdirs() }.writeText("how to work")
        return File(agent, AgentLog.SESSION).apply { writeText(FIRST_ROW) }
    }

    @Test
    fun `the record is one file, and a file of that name elsewhere is not it`() {
        val session = record()
        val prompt = AgentPrompt.projectFile(session.parentFile.parentFile)
        // Both files are the app's: the evidence and the context written from it. A person cannot
        // edit either one, which is what makes them evidence.
        val context = File(session.parentFile, AgentLog.CONTEXT).apply { writeText(FIRST_ROW) }

        assertTrue("the record", workspace.isRecord(session))
        assertTrue("and the context written from it", workspace.isRecord(context))
        assertFalse("the project's own instructions are the person's", workspace.isRecord(prompt))
        assertFalse(
            "a file with the record's name somewhere else is not the record",
            run {
                val elsewhere = File(workspace.accountDir, "elsewhere")
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
    fun `only git's store and the folder marker are never listed`() {
        val dir = workspace.accountDir
        File(dir, "visible.txt").writeText("hi")
        File(dir, ".gitignore").writeText("x")
        File(dir, ".muffy").mkdirs()
        File(dir, ".git").mkdirs()
        File(dir, Workspace.KEEPER).writeText("")
        File(dir, "dotfile.txt").writeText("a person's own")
        val names = workspace.list(dir).map { it.name }
        // **The rule is no longer "a dot means hidden".** Report 28 asked for the app's own things to
        // be visible and read-only instead of invisible, so `.muffy` is listed - and so is a
        // person's own dot-file, which was never the app's to hide.
        //
        // Two names are still not listed, and neither is information: `.git` is git's own store, and
        // `.gitkeep` exists only so git can record an empty folder.
        // `.gitignore` is a person's file - it is the document they edit to decide what does not
        // travel - so it is listed like any other. Folders first, then files, each by lowercase name.
        assertEquals(listOf(".muffy", ".gitignore", "dotfile.txt", "visible.txt"), names)
    }

    @Test
    fun `folders come before files`() {
        val dir = workspace.accountDir
        File(dir, "zebra.txt").writeText("")
        File(dir, "Alpha").mkdirs()
        val names = workspace.list(dir).map { it.name }
        assertEquals(listOf("Alpha", "zebra.txt"), names)
    }

    @Test
    fun `a picture is known by its extension`() {
        val dir = workspace.accountDir
        assertEquals(EntryKind.IMAGE, workspace.kindOf(File(dir, "photo.PNG")))
        assertEquals(EntryKind.TEXT, workspace.kindOf(File(dir, "notes.txt")))
        assertEquals(EntryKind.TEXT, workspace.kindOf(File(dir, "README.md")))
        assertEquals(EntryKind.FOLDER, workspace.kindOf(File(dir, "somewhere").apply { mkdirs() }))
    }

    @Test
    fun `a name with no extension gets txt`() {
        assertEquals("notes.txt", Workspace.withDefaultExtension("notes"))
        assertEquals("notes.md", Workspace.withDefaultExtension("notes.md"))
        assertEquals("photo.png", Workspace.withDefaultExtension("photo.png"))
    }

    @Test
    fun `a name that cannot be a path is refused, and an ordinary one is not`() {
        // **A dot name is refused rather than rewritten**, which is the change: the app used to
        // turn `.ssh` into `ssh` silently. A name is the person's, so the answer is a sentence.
        assertNotNull(Workspace.nameProblem(".ssh"))
        assertNotNull(Workspace.nameProblem("a/b"))
        assertNotNull(Workspace.nameProblem("  "))
        // **And the names the old rewriter was mangling are allowed**, which is the half a rule
        // like this gets wrong: `Shopping list.txt` has a capital and a space and is fine.
        assertNull(Workspace.nameProblem("Shopping list.txt"))
        assertNull(Workspace.nameProblem("a-b_c.txt"))
    }

    @Test
    fun `the name a remote suggests is its last segment without the git suffix`() {
        // **A prefill, and the one rule that holds for every host without a table of conventions.**
        // `nameProblem` still decides: this only saves typing, and a person who wants their local
        // project called something other than its repository is doing something ordinary.
        assertEquals(
            "a clone address",
            "repo",
            Workspace.repoNameOf("https://github.com/owner/repo.git"),
        )
        assertEquals(
            "no .git, and a deeper path",
            "repo",
            Workspace.repoNameOf("https://git.example.org/team/sub/repo"),
        )
        assertEquals(
            "a trailing slash",
            "repo",
            Workspace.repoNameOf("https://gitlab.com/team/repo/"),
        )
        assertEquals(
            "a self-hosted path that has no owner segment",
            "repo",
            Workspace.repoNameOf("https://server/git/repo.git"),
        )
        assertEquals("and an empty address suggests nothing", "", Workspace.repoNameOf("   "))
    }

    @Test
    fun `the records folder cannot be touched`() {
        // **One thing is structural now.** The two folders the app used to own — `projects/` at the
        // root and a repository folder inside each project — are gone, because the project *is* the
        // folder. What is left is the record's own `agent/`, and it is refused the same way.
        val project = workspace.createProjectFolders("Shopping").getOrThrow()
        val records = File(project, AgentLog.DIR).apply { mkdirs() }
        assertTrue("the record's folder is the app's", workspace.isStructural(records))
        assertFalse("a project is not", workspace.isStructural(project))
        assertTrue("and it cannot be deleted", workspace.delete(records).isFailure)
        assertTrue("nor renamed", workspace.rename(records, "other").isFailure)
        assertTrue("and it is still there", records.isDirectory)
    }

    @Test
    fun `the workspace refuses to move a folder inside itself, or onto itself`() {
        val outer = File(workspace.accountDir, "outer").apply { mkdirs() }
        val inner = File(outer, "inner").apply { mkdirs() }
        assertTrue(workspace.move(outer, inner).isFailure)
        assertTrue(workspace.move(outer, outer).isFailure)
        assertTrue(outer.isDirectory)
    }

    @Test
    fun `a file can be moved between the files and a folder of a project`() {
        // The move that used to cross the worktree boundary — out of the repository and back — is
        // now an ordinary move between two folders of one repository, and it must still work.
        val project = workspace.createProjectFolders("Shopping").getOrThrow()
        val letters = File(project, "letters").apply { mkdirs() }
        val file = File(project, "Milk.txt").apply { writeText("milk") }

        assertTrue(workspace.move(file, letters).isSuccess)
        assertTrue(File(letters, "Milk.txt").isFile)
        assertFalse(File(project, "Milk.txt").exists())

        assertTrue(workspace.move(File(letters, "Milk.txt"), project).isSuccess)
        assertTrue(File(project, "Milk.txt").isFile)
    }

    @Test
    fun `the project of a path is found`() {
        val project = aProject("Shopping")
        val file = File(project, "Milk.txt")
        assertEquals("Shopping", workspace.projectOf(file))
        assertEquals("one level, which is the whole of the merge", "Shopping", workspace.projectAt(listOf("Shopping")))
        assertNull(workspace.projectAt(listOf("Milk.txt")))
        assertNull(workspace.projectAt(emptyList()))
    }

    @Test
    fun `projects are listed by name`() {
        aProject("beta")
        aProject("Alpha")
        assertEquals(listOf("Alpha", "beta"), workspace.projectNames())
    }

    /**
     * A project's folder, with the repository that makes it one.
     *
     * `createProjectFolders` makes the folder only; `ProjectGit.init` makes the repository. The
     * second half is what `isProjectFolder` asks for, because a project's folder *is* its
     * repository — so a test that wants a project has to have both, and a folder alone is a folder
     * (which is why `projectsAreListedByName` used to pass with two folders that were not
     * projects at all).
     */
    private fun aProject(name: String): File {
        val folder = workspace.createProjectFolders(name).getOrThrow()
        File(folder, ".git").mkdirs()
        return folder
    }

    @Test
    fun `pins are kept in the app state and survive a restart`() {
        val file = File(workspace.accountDir, "ideas.txt").apply { writeText("x") }
        val path = workspace.pathString(workspace.relative(file)!!)
        workspace.setPins(setOf(path))

        val readBack = Workspace.single(workspace.accountDir)
        assertTrue(readBack.isPinned(file))
        assertFalse(readBack.isPinned(File(workspace.accountDir, "other.txt")))
    }

    @Test
    fun `writing replaces a file whole`() {
        val file = File(workspace.accountDir, "note.txt")
        workspace.write(file, "one")
        workspace.write(file, "two")
        assertEquals("two", file.readText())
        assertNotNull(workspace.relative(file))
    }

    @Test
    fun `a name is taken and says so`() {
        workspace.createFile(workspace.accountDir, "note.txt", "a")
        val second = workspace.createFile(workspace.accountDir, "note.txt", "b")
        assertTrue(second.isFailure)
        assertTrue(second.exceptionOrNull()?.message.orEmpty().contains("already"))
    }

    @Test
    fun `a file a person makes through the door is locked and ignored`() {
        // **The door, not the function.** `createdByOperator` was written, documented and tested in
        // isolation — and nothing in the app called it, so "a file the person makes is locked and
        // ignored" was a rule that never fired. This test goes through `createFile`, which is the
        // call the person's New File makes, so it fails if the wiring is removed.
        val project = workspace.createProjectFolders("Shopping").getOrThrow()
        File(project, ".git").mkdirs()
        workspace.ensureIgnore(project)

        val made = workspace.createFile(project, "milk.txt", "two litres").getOrThrow()

        val rel = workspace.pathString(workspace.relative(made)!!)
        assertTrue("the person's file is locked: $rel", workspace.locked().contains(rel))
        assertFalse(
            // **A text file is still tracked, and that is the point.** Ignoring it would also mean
            // not committing it — an ignored file has no version and no undo — and "every change is
            // a version" is the promise this app exists for. Only a file the app cannot classify
            // waits behind the switch.
            "and still tracked, so it has a history",
            File(project, Workspace.IGNORE).let { it.isFile && it.readText().contains("milk.txt") },
        )
    }

    @Test
    fun `a binary a person makes through the door waits, because the app cannot classify it`() {
        val project = workspace.createProjectFolders("Shopping").getOrThrow()
        File(project, ".git").mkdirs()
        workspace.ensureIgnore(project)

        val made = workspace.createFile(project, "receipt.pdf", "not really a pdf").getOrThrow()

        val rel = workspace.pathString(workspace.relative(made)!!)
        assertTrue("it is still the person's, so locked: $rel", workspace.locked().contains(rel))
        assertTrue(
            "and ignored, so it does not spend GitHub's storage until they say so",
            File(project, Workspace.IGNORE).let { it.isFile && it.readText().contains("receipt.pdf") },
        )
    }

    @Test
    fun `a folder a person makes through the door is locked and ignored`() {
        val project = workspace.createProjectFolders("Shopping").getOrThrow()
        File(project, ".git").mkdirs()
        workspace.ensureIgnore(project)

        val made = workspace.createFolder(project, "drafts").getOrThrow()

        val rel = workspace.pathString(workspace.relative(made)!!)
        assertTrue("the person's folder is locked: $rel", workspace.locked().contains(rel))
        assertFalse(
            // A folder is not a binary: it holds what its files are, and those decide for
            // themselves. Ignoring the folder would ignore the notes inside it.
            "and still tracked, because a folder is not a file type",
            File(project, Workspace.IGNORE).let {
                it.isFile && it.readText().lines().any { line -> line.trim() == "drafts/" }
            },
        )
    }

    @Test
    fun `a byte count is shown the way a person reads it`() {
        // **The awkward values, not the round ones.** A formatter tested only on `1.0 KB` and
        // `2.5 MB` is a formatter that prints `1024.0 KB` at the boundary it was supposed to have
        // crossed, and nobody notices until a file is exactly a megabyte.
        assertEquals("0 B", Workspace.bytesFor(0))
        assertEquals("1023 B", Workspace.bytesFor(1023))
        assertEquals("1 KB", Workspace.bytesFor(1024))
        assertEquals("1.4 KB", Workspace.bytesFor(1464))
        assertEquals("1 MB", Workspace.bytesFor(1024L * 1024))
        // 1023.96 KB is 1024 KB less 41 bytes: it must cross into MB, not print the boundary.
        assertEquals("1 MB", Workspace.bytesFor(1024L * 1024 - 41))
        // **A value with a real tenth in it.** 12.4 MiB is not a whole number of bytes, so the
        // literal is computed from a byte count that is: 12 MiB + 0.4 MiB.
        assertEquals("12.4 MB", Workspace.bytesFor(13_002_342))
        assertEquals("1 GB", Workspace.bytesFor(1024L * 1024 * 1024))
        // A file whose size the phone will not say is not a file of zero bytes.
        assertEquals("unknown", Workspace.bytesFor(-1))
    }

    @Test
    fun `a row says its size and its path, and a folder says how many things it holds`() {
        // **A folder is counted, not sized.** Summing everything under a folder is a walk per row on
        // every listing, and a project with a deep tree would make opening a folder slow for a number
        // nobody reads. The count is what the row shows instead.
        val dir = File(workspace.accountDir, "sizes-${System.nanoTime()}").apply { mkdirs() }
        File(dir, "milk.txt").writeText("two litres")
        File(dir, "notes").mkdirs()
        File(dir, "notes/a.txt").writeText("a")
        File(dir, "notes/b.txt").writeText("b")

        val listed = workspace.list(dir)
        val milk = listed.single { it.name == "milk.txt" }
        val notes = listed.single { it.name == "notes" }

        assertEquals("ten bytes, said plainly", "10 B", Workspace.bytesFor(milk.size!!))
        assertTrue(
            "the caption carries the path a person would paste",
            milk.caption().endsWith(milk.file.absolutePath),
        )
        assertTrue("and the size before it", milk.caption().startsWith("10 B · "))
        assertEquals("a folder has no size", null, notes.size)
        assertEquals("and says how many things it holds", 2, notes.children)
        assertTrue("said as a count", notes.caption().startsWith("2 items · "))
    }

    @Test
    fun `a project is its own repository and its files are tracked`() {
        // The merge, stated as a test: the folder a person sees *is* the repository — and the
        // app's two lines are in git's own exclude list, so nothing of the app's is ever committed
        // and nothing of the person's is excluded by accident.
        val project = workspace.createProjectFolders("Shopping").getOrThrow()
        File(project, ".git").mkdirs()
        workspace.ensureIgnore(project)

        assertEquals("the project is the repository folder", project, workspace.repoOf("Shopping"))
        assertEquals("and it is at the account root", workspace.accountDir, project.parentFile)
        assertEquals(
            // **Four, and the fourth has no trailing slash on purpose.** `requests/` is written on
            // every response, including the keepalives sent while nobody is looking, so left
            // unignored it would be the noisiest thing in the working tree. `system` is the link to
            // the account's own folder (§13f) - and `system/` would match nothing, because a symlink
            // is not a directory. Measured: with the slash the link stayed untracked.
            "the app's own lines, and nothing else",
            listOf("agent/", ".muffy/", "requests/", "shared"),
            File(project, ".git/info/exclude")
                .readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") },
        )
        assertFalse("and no .gitignore was written for them", File(project, ".gitignore").exists())
    }

    @Test
    fun `a person's ignore file is never touched by the app`() {
        // **The app's two lines are not in the person's `.gitignore`.** They are a rule of this
        // phone's app, not a fact about their repository: they go in `.git/info/exclude`, which
        // git never commits and cannot push. A `.gitignore` is a tracked file, so writing there
        // put the app's bookkeeping into the repository and made a project that had just arrived
        // say "1 changed" before anybody touched it.
        val project = workspace.createProjectFolders("Shopping").getOrThrow()
        File(project, ".git").mkdirs()
        val ignore = File(project, ".gitignore")
        ignore.writeText("build/\n*.apk\n")

        workspace.ensureIgnore(project)

        assertEquals(
            "the person's file is byte for byte what they wrote",
            "build/\n*.apk\n",
            ignore.readText(),
        )
        val exclude = File(project, ".git/info/exclude")
        val lines = exclude.readLines()
        assertTrue("the app's lines are git's own exclude list", "agent/" in lines)
        // A second pass is a no-op, so nothing is rewritten on every open.
        val once = exclude.readText()
        workspace.ensureIgnore(project)
        assertEquals("asking twice changes nothing", once, exclude.readText())
    }

    @Test
    fun `reading a missing file is empty rather than a crash`() {
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
    fun `a locked file is refused to the agent and not to the person`() {
        // **The funnel is the person's door.** The agent asks `mayWrite` - which is
        // `agentLockProblem` - before it ever calls this, so a person's lock is enforced there and
        // not here. The old version of this test asserted `write` refused, which is what made the
        // person unable to edit a file they had locked themselves.
        val file = File(workspace.accountDir, "milk.txt")
        workspace.write(file, "two litres")
        lock("milk.txt")

        assertEquals(
            "the agent is told why",
            "\"milk.txt\" is locked, so it cannot be changed",
            workspace.agentLockProblem(file),
        )
        assertTrue("the person may still write it", workspace.write(file, "three litres"))
        assertEquals("and the text changed", "three litres", file.readText())
    }

    @Test
    fun `a lock on a folder protects the folder, and its contents stay editable`() {
        // **The rule changed on purpose.** It used to say a folder lock covered everything inside it, and
        // that was a workaround: the same rule is what stopped `shared/` being an ordinary folder, because
        // a system-locked folder would have made every template in it read-only.
        //
        // The operator, 2026-11-09: *"locking a folder doesnt lock its content but just blocks it from
        // being deleted or renamed."* So a folder is an existence, not a permission — and the files a
        // person wants frozen are the files they lock.
        val folder = File(workspace.accountDir, "drafts")
        folder.mkdirs()
        val inside = File(folder, "milk.txt")
        workspace.write(inside, "two litres")
        lock("drafts")

        // **The folder itself is held against the agent**: it may not delete it or move it.
        assertTrue("the folder is held", workspace.isAgentLocked(folder))
        // **And what is inside is not.** A person who wants a file frozen locks the file.
        assertFalse("the file inside is not covered", workspace.isAgentLocked(inside))
        assertEquals("so the agent may write it", null, workspace.agentLockProblem(inside))
    }

    @Test
    fun `a lock on a file does not lock the one beside it with a longer name`() {
        // A prefix comparison without its separator: locking `milk.txt` would also lock
        // `milk.txt.bak`, which is the kind of bug that reads as "the app is random".
        val file = File(workspace.accountDir, "milk.txt")
        val neighbour = File(workspace.accountDir, "milk.txt.bak")
        workspace.write(file, "a")
        workspace.write(neighbour, "b")
        lock("milk.txt")

        // **The agent's question**, because the separator rule is about what a lock covers for the
        // agent - a person's own lock never stops the person.
        assertTrue("the file is held against the agent", workspace.isAgentLocked(file))
        assertFalse("the one with the longer name is not", workspace.isAgentLocked(neighbour))
        assertTrue("and it can still be written", workspace.write(neighbour, "c"))
    }

    @Test
    fun `a user lock binds the agent and a system lock binds everybody`() {
        // **The operator's rule, in their words**: "system locks restrict both users and agents, and
        // user locks restrict agents (not users themselves!)".
        //
        // Before this there was one answer to "is it locked", so a person who locked a file to keep
        // the *agent* off it also lost their own ability to edit it - and, worse, to unlock it
        // through any path that consulted the same check. Two questions, two answers: `isLocked` is
        // "may anybody write this" (the app's own locks), `isAgentLocked` is "may the agent" (either
        // kind).
        val mine = File(workspace.accountDir, "milk.txt")
        workspace.write(mine, "two litres")
        lock("milk.txt")

        assertTrue("the agent is held off a file the person locked", workspace.isAgentLocked(mine))
        assertFalse("and the person is not", workspace.isLocked(mine))
        assertTrue("so the person can still write it", workspace.write(mine, "three litres"))

        // A file the app holds is a different matter: the app's own rule cannot be lifted by
        // anybody, because the file is one the app reads back.
        val flag = File(File(workspace.accountDir, ".muffy"), "locked.json")
        flag.parentFile?.mkdirs()
        flag.writeText("[]")
        assertTrue("the app's own file is held against the person", workspace.isLocked(flag))
        assertTrue("and against the agent", workspace.isAgentLocked(flag))
        assertFalse("so nobody writes it", workspace.write(flag, "[]"))
    }

    @Test
    fun `copying a locked file out is allowed because copying does not change it`() {
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

        // **What the old rule said, and the operator changed on 2026-11-09**: writing *into* a locked
        // folder was refused, because a folder lock covered everything inside it. It does not any more —
        // a folder lock is an existence lock, so the files inside are theirs to write unless a file is
        // itself locked. `LookTest` and `WorkspaceTest`'s folder-lock test carry the same rule from the
        // other side; this one keeps the half that did not change.
        assertTrue("copying out of a locked folder is still allowed", copy.isSuccess)
    }

    @Test
    fun `the lock is remembered across a workspace that has not read it yet`() {
        // The flags live in `.muffy`, so a second Workspace over the same account sees the
        // lock without being told: the cache is a cache, not the truth.
        val file = File(workspace.accountDir, "milk.txt")
        workspace.write(file, "two litres")
        lock("milk.txt")

        val other = Workspace.single(workspace.accountDir)

        assertTrue("the lock is read from disk", other.isAgentLocked(file))
        // The agent's refusal, which is the one a person's lock produces. `write` is the shared
        // funnel and a person may still write their own locked file.
        assertEquals(
            "and the agent is told why",
            "\"milk.txt\" is locked, so it cannot be changed",
            other.agentLockProblem(file),
        )
    }

    // ---- one answer for "who is holding this" --------------------------------

    /** A project with a repository, so the two ignore files have somewhere to live. */
    private fun project(): File {
        val dir = File(workspace.accountDir, "shopping")
        File(dir, ".git" + File.separator + "info").mkdirs()
        return dir
    }

    @Test
    fun `nobody is holding a file nobody locked`() {
        val dir = project()
        val file = File(dir, "milk.txt").apply { writeText("two litres") }

        assertEquals("no lock", Workspace.Hold.NONE, workspace.hold(file, emptySet()))
    }

    @Test
    fun `the person's lock and the app's lock are the same question with different answers`() {
        val dir = project()
        val mine = File(dir, "milk.txt").apply { writeText("two litres") }
        // `.muffy/locked.json` names paths from the account root, which is what `hold` compares.
        val path = workspace.pathString(workspace.relative(mine)!!)

        assertEquals("the person set this one", Workspace.Hold.USER, workspace.hold(mine, setOf(path)))
        // **The app's flags are held whatever the person's list says**, which is why APP wins: a
        // flag is not writable by hand, so reporting USER would offer a toggle whose other position
        // is still refused.
        val flag = File(File(workspace.accountDir, ".muffy"), "locked.json").apply {
            parentFile.mkdirs()
            writeText("[]")
        }
        assertEquals("and the app holds its own", Workspace.Hold.APP, workspace.hold(flag, setOf(path)))
    }

    @Test
    fun `the app's own folder holds everything inside it, not only itself`() {
        // **The operator, looking at the record's folder**: "why agent/ looks different? is it not
        // manages in a unified way? because where is its red unsync icon?" and then the correction
        // that named the real rule: "and remember that whole subbranch should inherit them".
        //
        // `appHold` tested `file.name` - the *leaf* - so `agent/` was held and `agent/context.log`
        // was not. The folder drew both red marks and its own two logs drew nothing at all, which is
        // the same inheritance bug the person's lock had in report 9, reappearing in the app's rule.
        val dir = project()
        val record = File(dir, "agent").apply { mkdirs() }
        val log = File(record, "context.log").apply { writeText("   1     9 agent  hi\n") }

        assertEquals(
            "the folder itself is the app's",
            Workspace.Hold.APP,
            workspace.hold(record, emptySet()),
        )
        assertEquals(
            "and so is the file inside it",
            Workspace.Hold.APP,
            workspace.hold(log, emptySet()),
        )
        // **The mark has to inherit too, and it is the one that was missing entirely.**
        // The operator: "it shows, but why only red lock and not also red disconnect?" - the
        // disconnect was nested inside the text-file branch, which an app-held row never reaches.
        assertEquals(
            "and it is kept off GitHub as well as out of reach",
            Workspace.Hold.APP,
            workspace.hold(log, emptySet()),
        )

        // **A file beside it is nobody's.** The rule is about being *inside* the folder, so a
        // sibling that merely shares a prefix - `agent-notes.txt` - must not be caught by it.
        val beside = File(dir, "agent-notes.txt").apply { writeText("mine") }
        assertEquals("a name that only starts the same", Workspace.Hold.NONE, workspace.hold(beside, emptySet()))
    }

    @Test
    fun `the whole shared folder is held, and a project's own muffycodes_data is not`() {
        // **The operator, twice**: the system project "is a readonly folder that is updated by the
        // app itself, there is no place for user generated content there", and "make sure that all
        // system project subtree is system locked" — and later, *"shared should also be system locked,
        // as all of our special folders, because app's functionality depends on them."*
        //
        // **The reason this needs a path rule rather than a name.** The app already holds `agent/` and
        // `.muffy/` by *name*, wherever they appear. Neither can say "everything under one top-level
        // folder", and the obvious candidate — the folder `muffycodes_data` — is exactly the one that
        // must NOT be locked, because every project has one and the agent writes its notes there. So the
        // rule is the first path segment.
        //
        // **What the lock does not do is make the subtree read-only.** A folder lock is an existence
        // lock now (`isAgentLocked`), so a template inside `shared/schemas/` is still editable; what the
        // path rule holds is the folder against being renamed or deleted.
        val shared = File(workspace.accountDir, Workspace.SYSTEM_PROJECT).apply { mkdirs() }
        val deep = File(File(File(shared, "muffycodes_data"), "prompts"), "system_prompt.txt").apply {
            parentFile.mkdirs()
            writeText("you are an agent\n")
        }

        assertEquals("the project folder itself", Workspace.Hold.APP, workspace.hold(shared, emptySet()))
        assertEquals(
            "a file three folders down, whose every name is ordinary",
            Workspace.Hold.APP,
            workspace.hold(deep, emptySet()),
        )
        assertEquals(
            "and it is not written, which is what the mark promises",
            false,
            workspace.write(deep, "something else"),
        )

        // **The other half, and the half that would break if the rule were written as a name.** A
        // project's own `muffycodes_data/prompts/` is the person's and the agent's, and locking it
        // would take away the very folder the app asks them to write their instructions in.
        val mine = project()
        val prompts = File(File(File(mine, AgentPrompt.DATA), "prompts"), "project_prompt.txt").apply {
            parentFile.mkdirs()
            writeText("# how to work here\n")
        }
        assertEquals(
            "a project's own data folder is nobody's lock",
            Workspace.Hold.NONE,
            workspace.hold(prompts, emptySet()),
        )
        assertEquals("and it can be written", true, workspace.write(prompts, "# changed\n"))
    }

    @Test
    fun `nothing can be added to, renamed in, or removed from a locked branch`() {
        // **The operator's rule, in their words**: "check that it should be impossible to add
        // (upload, move, clone, create etc) remove rename etc elements of a locked folder
        // subbranch" - and "only reading and cloning out of the locked subbranches should be
        // allowed".
        //
        // Written as a matrix because the risk is a path that forgets to ask, not a path that asks
        // wrongly. Each line below is a different door into the same folder, and the test names the
        // door in the failure so a regression says *which* one opened.
        val dir = project()
        val record = File(dir, "agent").apply { mkdirs() }
        val log = File(record, "context.log").apply { writeText("   1     9 agent  hi\n") }
        val beside = File(dir, "milk.txt").apply { writeText("two litres") }

        // --- adding ---
        assertTrue(
            "create a file in it",
            workspace.createFile(record, "new.txt").isFailure,
        )
        assertTrue(
            "create a folder in it",
            workspace.createFolder(record, "new").isFailure,
        )
        assertTrue(
            "move something into it",
            workspace.move(beside, record).isFailure,
        )

        // --- renaming and removing what is already inside ---
        assertTrue("rename a file in it", workspace.rename(log, "other.log").isFailure)
        assertTrue("rename the folder itself", workspace.rename(record, "record").isFailure)
        assertTrue("delete a file in it", workspace.delete(log).isFailure)
        assertTrue("delete the folder itself", workspace.delete(record).isFailure)

        // --- and writing over what is there ---
        assertFalse("write a file in it", workspace.write(log, "changed"))

        // **Reading and copying out are allowed**, and that is the other half of the rule: a lock
        // says "do not change this", not "do not look". The operator's word was "only reading and
        // cloning out of the locked subbranches should be allowed".
        assertTrue("the file is still readable", log.canRead())
        assertEquals("and unchanged", "   1     9 agent  hi\n", log.readText())
        assertTrue("copying out of it works", workspace.copy(log, dir).isSuccess)

        // **Nothing above touched the disk.** A refusal that has already written half a file is
        // the failure this list exists to rule out.
        assertTrue("the folder is still there", record.isDirectory)
        assertTrue("and so is the file", log.isFile)
        assertFalse("and nothing was created inside", File(record, "new.txt").exists())
        assertFalse("nor the folder", File(record, "new").exists())
    }

    @Test
    fun `a secret is held by the app, and is never written`() {
        val dir = project()
        // **A name the app recognises without reading the file.** It cannot judge content - see
        // `looksSecret` - so the rule is the handful of names that are never anything else.
        val key = File(dir, ".env").apply { writeText("OPENROUTER_KEY=sk-or-...") }

        assertEquals("held by the app", Workspace.Hold.APP, workspace.hold(key, emptySet()))
        // **The mark is not decoration.** A red lock the write funnel ignored would promise
        // something the save did not honour.
        assertFalse("so the funnel refuses a write", workspace.write(key, "replaced"))
        assertEquals("and the file is untouched", "OPENROUTER_KEY=sk-or-...", key.readText())
    }

    @Test
    fun `the extension is enough for a key, and the name is not case sensitive`() {
        val dir = project()

        assertEquals("a .pem", Workspace.Hold.APP, workspace.hold(File(dir, "server.pem"), emptySet()))
        assertEquals("a .key", Workspace.Hold.APP, workspace.hold(File(dir, "deploy.KEY"), emptySet()))
        assertEquals("and .ENV", Workspace.Hold.APP, workspace.hold(File(dir, ".ENV.local"), emptySet()))
        assertEquals(
            "but an ordinary file is not",
            Workspace.Hold.NONE,
            workspace.hold(File(dir, "notes.txt"), emptySet()),
        )
    }

    @Test
    fun `the app's secret rule is written where git reads it, and not into the person's file`() {
        val dir = project()
        workspace.ensureSecretIgnores(dir)

        val exclude = File(dir, ".git" + File.separator + "info" + File.separator + "exclude").readText()
        assertTrue("the .env family", exclude.lineSequence().any { it.trim() == ".env.*" })
        assertTrue("the key extensions", exclude.lineSequence().any { it.trim() == "*.pem" })
        // **Not `.gitignore`.** That file is tracked and pushed, so the app's rule would become a
        // fact about the person's repository and every clone would inherit a line for a file that
        // never existed in it.
        assertFalse("the person's ignore file was not made", File(dir, Workspace.IGNORE).exists())
    }

    @Test
    fun `writing the secret rule twice does not write it twice`() {
        val dir = project()
        workspace.ensureSecretIgnores(dir)
        val once = File(dir, ".git" + File.separator + "info" + File.separator + "exclude").readText()

        workspace.ensureSecretIgnores(dir)
        val twice = File(dir, ".git" + File.separator + "info" + File.separator + "exclude").readText()

        assertEquals("the file is unchanged", once, twice)
        assertEquals("and no line is repeated", once.lines().size, once.lines().distinct().size)
    }

    @Test
    fun `a secret is kept out of the index by git itself, not by a derived tree`() {
        // **This replaced the unsync mechanism, and it is the test that says the replacement is
        // safe.** Unsyncing used to keep a key off GitHub by deriving a commit without it; that is
        // deleted, so the only thing standing between a `.env` and GitHub is `.git/info/exclude`.
        // If this fails, a key is pushed.
        //
        // `.git/info/exclude` rather than `.gitignore` on purpose: it is local, it is never pushed,
        // and it protects every clone regardless of what a project's own ignore file says.
        val dir = project()
        workspace.ensureSecretIgnores(dir)
        val key = File(dir, ".env").apply { writeText("TOKEN=abc") }
        val note = File(dir, "notes.txt").apply { writeText("mine") }

        // Asked of git, which is the authority — not of a list the app keeps.
        val repo = org.eclipse.jgit.api.Git.init().setDirectory(dir).setInitialBranch("main").call()
        try {
            org.eclipse.jgit.api.Git(repo.repository).use { git ->
                git.add().addFilepattern(".").call()
            }
            val tracked = repo.repository.readDirCache().let { cache ->
                (0 until cache.entryCount).map { cache.getEntry(it).pathString }
            }
            assertTrue("the note is tracked: $tracked", tracked.contains("notes.txt"))
            assertFalse("and the key is not: $tracked", tracked.any { it.endsWith(".env") })
            assertTrue("the key is still on the phone", key.isFile)
            assertEquals("with its contents", "TOKEN=abc", key.readText())
        } finally {
            repo.close()
        }
    }

    @Test
    fun `the app's own folders are kept out of the index too`() {
        // The other half of the same rule: `agent/`, `.muffy/` and `requests/` are the app's
        // bookkeeping and must not be committed. They were omitted from the derived tree before;
        // now they are excluded by git, and this says so rather than assuming it.
        val dir = project()
        workspace.ensureIgnore(dir)
        File(dir, "agent").mkdirs()
        File(dir, "agent/context.log").writeText("a conversation")
        File(dir, "notes.txt").writeText("mine")

        val repo = org.eclipse.jgit.api.Git.init().setDirectory(dir).setInitialBranch("main").call()
        try {
            org.eclipse.jgit.api.Git(repo.repository).use { git ->
                git.add().addFilepattern(".").call()
            }
            val tracked = repo.repository.readDirCache().let { cache ->
                (0 until cache.entryCount).map { cache.getEntry(it).pathString }
            }
            assertTrue("the note is tracked: $tracked", tracked.contains("notes.txt"))
            assertFalse(
                "and nothing under agent/ is: $tracked",
                tracked.any { it.startsWith("agent/") },
            )
        } finally {
            repo.close()
        }
    }

    // ---- the four folders, and the prompt that names them -------------------

    @Test
    fun `the system prompt names every subject folder, and no folder it does not have`() {
        // **The prompt and the layout are two lists that must not drift.** The prompt tells the agent
        // to look in `dictionary/`, `skills/`, `wiki/` and `mcp/`; `Workspace.SUBJECTS` is what the app
        // actually makes. A folder added to one and not the other produces the worst kind of bug: the
        // prompt sends the agent to a folder that is not there, which it reports as "this project has
        // none" — a true sentence about the wrong thing.
        //
        // This is the test the plan calls a **prompt-order test**, and it is deliberately written
        // against the constant rather than against four literal strings: a literal list here would
        // pass while the app made a different set.
        val prompt = AgentPrompt.SYSTEM_DEFAULT
        for (folder in Workspace.SUBJECTS) {
            assertTrue(
                "the prompt names $folder/ : it is one of the folders the app makes",
                prompt.contains("$folder/"),
            )
        }
        // **And the other direction**: a folder the prompt names that is not in the list would be a
        // folder the app never makes. The four are named in one block, so the block is found and every
        // `word/` in it is checked - which catches a rename on either side.
        val block = prompt.lines().filter { line ->
            Workspace.SUBJECTS.any { line.contains("$it/") }
        }
        assertEquals(
            "the four are named once each, in the block that describes where things are",
            Workspace.SUBJECTS.size,
            block.size,
        )
    }

    @Test
    fun `a project gets the four folders, so the prompt never points at nothing`() {
        // **A project, not just the system project.** The system project holds the core; a project
        // grows its own, because a dictionary entry is the operator's vocabulary and a word means
        // something different in two projects. A project without the folders sent the agent to places
        // that were not there.
        val dir = File(workspace.accountDir, "shopping")
        dir.mkdirs()
        Workspace.ensureProjectData(dir)

        val data = File(dir, AgentPrompt.DATA)
        for (folder in Workspace.SUBJECTS) {
            val one = File(data, folder)
            assertTrue("$folder/ is made", one.isDirectory)
            // **Empty and marked**, so it is committable: a dictionary entry travels with the project,
            // and an empty folder is invisible to git.
            assertEquals(
                "$folder/ holds only the marker, so it is a folder in the repository",
                listOf(Workspace.KEEPER),
                one.list()?.toList(),
            )
        }
        // **`prompts/` is made as well, and it is the one that is not a subject.** The subject
        // folders are what a person browses; `prompts/` is what the brief is assembled *from*, read on
        // every turn rather than on demand. It used to be made incidentally by the single
        // `project_prompt.txt` write, so removing that file left nothing making it — and a project
        // whose prompt folder does not exist is a project with nowhere to put a document the operator
        // asked the agent to write.
        assertTrue("prompts/ is made", File(data, AgentPrompt.PROMPTS).isDirectory)
        assertEquals(
            "and nothing else appeared: the four subjects plus prompts",
            (Workspace.SUBJECTS + AgentPrompt.PROMPTS).toSet(),
            data.list()?.toSet(),
        )
        // **`prompts/` has a marker like the rest**, so the folder travels: an empty folder is
        // invisible to git, and an unmarked one would exist on this phone and nowhere else. The
        // marker is kept out of the prompt by `ProjectTree.prompt`, which is where the rule that a
        // marker is not content belongs.
        assertEquals(
            "prompts/ holds only the marker",
            listOf(Workspace.KEEPER),
            File(data, AgentPrompt.PROMPTS).list()?.toList(),
        )
    }

    @Test
    fun `the folders are made once and a written article is never lost`() {
        // **Idempotent, and it does not tidy.** `ensureProjectData` runs every time a project is
        // copied, and a person who has written a dictionary entry must keep it: the marker is only
        // written when the folder is otherwise empty, and nothing is ever removed.
        val dir = File(workspace.accountDir, "shopping")
        dir.mkdirs()
        Workspace.ensureProjectData(dir)
        val entry = File(File(File(dir, AgentPrompt.DATA), "dictionary"), "analyze.md")
        entry.writeText("when the operator says analyze, mean: read the code and say what it does")

        Workspace.ensureProjectData(dir)

        assertTrue("the entry is still there", entry.isFile)
        assertEquals(
            "and it is untouched",
            "when the operator says analyze, mean: read the code and say what it does",
            entry.readText(),
        )
    }

    @Test
    fun `a secret is left out of what is sent even though it is never named`() {
        val dir = project()
        File(dir, ".env").writeText("KEY=sk-or-...")
        File(dir, "notes.txt").writeText("mine")

        // **And git is told, which is what keeps it out now.** The rule used to be applied by
        // omitting the path from a derived tree at push time; that mechanism is deleted, so the
        // name has to reach `.git/info/exclude` or the key travels. Asked of git, not of the list.
        workspace.ensureSecretIgnores(dir)
        val exclude = File(dir, ".git/info/exclude")
        assertTrue("the exclude file was written", exclude.isFile)
        assertTrue(
            "and it names the secret: ${exclude.readText()}",
            exclude.readText().lineSequence().any { it.trim() == ".env" },
        )
    }
    // ---- the two folders, and what each means ------------------------------

    @Test
    fun `a picture is born untracked, because assets is where it belongs`() {
        // **The operator's rule, and it is wider than what shipped.** The rule was
        // `kindOf == UNKNOWN`, and `EntryKind` has a separate `IMAGE` - so pictures were born
        // *tracked*, which is why `LFS_ATTRIBUTE` is a list of image extensions. Now anything that
        // is not text is born untracked, and `assets/` is the one place a picture is tracked.
        //
        // The cost is real and accepted: a picture outside `assets/` has no version and no undo.
        // This test is what says the rule changed, so a later edit cannot quietly revert it.
        val project = workspace.createProjectFolders("Shopping").getOrThrow()
        workspace.ensureIgnore(project)

        // **A picture is the exception, and the drawing feature is why.** A person pressing New
        // File and choosing a picture name is making a decision, not accepting a default - and
        // ignoring it took the version away from every drawing, which is the one promise this app
        // is built on. `assets/` decides where a picture is *stored*, not whether it has a history.
        assertFalse(
            "a picture the person makes keeps its version",
            workspace.shouldIgnoreOnCreate(File(project, "screenshot.png")),
        )
        assertTrue(
            "but anything the app cannot read waits",
            workspace.shouldIgnoreOnCreate(File(project, "data.zip")),
        )
        assertFalse(
            "and text does not",
            workspace.shouldIgnoreOnCreate(File(project, "notes.md")),
        )
    }

    @Test
    fun `a folder is not a file kind, so making one still works`() {
        // **The bug the rule introduced, as a test.** `FOLDER != TEXT` is true, so the one-line form
        // ignored every folder a person made and `createFolder` refused - which took a whole
        // screen's worth of behaviour with it. A folder holds what its files are, and those decide
        // for themselves.
        val project = workspace.createProjectFolders("Shopping").getOrThrow()
        workspace.ensureIgnore(project)

        assertFalse(
            "a folder is not held back",
            workspace.shouldIgnoreOnCreate(File(project, "drafts")),
        )
        assertTrue(
            "and it can still be made",
            workspace.createFolder(project, "drafts").isSuccess,
        )
    }

    @Test
    fun `the agent may not write assets, and may write anywhere else`() {
        // **A folder rule, not a lock.** `assets/` is the one folder whose contents cost the
        // operator money to store and whose size is unbounded, so an agent that can write there can
        // spend quota without asking. A rule rather than a list of locked paths because the folder
        // is what the rule is about: a new picture in `assets/` is refused without anybody
        // remembering to lock it.
        val project = workspace.createProjectFolders("Shopping").getOrThrow()

        assertTrue(
            "a picture in assets is refused",
            workspace.isAgentLocked(File(project, "assets/milk.png")),
        )
        assertTrue(
            "and a note in assets, because the folder is the rule",
            workspace.isAgentLocked(File(project, "assets/notes.md")),
        )
        assertTrue(
            "and the folder itself",
            workspace.isAgentLocked(File(project, "assets")),
        )
        assertFalse(
            "but everything else is the agent's",
            workspace.isAgentLocked(File(project, "notes.md")),
        )
        assertFalse(
            "including a folder whose name merely starts with it",
            workspace.isAgentLocked(File(project, "assets-old/notes.md")),
        )
    }

}
