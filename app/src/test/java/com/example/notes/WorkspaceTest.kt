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
        workspace = Workspace(File(root, "muffyuniverse"))
        workspace.accountDir.mkdirs()
        workspace.accountDir.mkdirs()
    }

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    // ---- the agent's record is kept, not editable -----------------------------

    /** One row of the record, in the columns the app writes. */
    private val FIRST_ROW = "   1     4 TOLD    in  operator  task\n"

    private fun record(): File {
        val dir = File(workspace.accountDir, "shopping")
        val agent = File(dir, AgentLog.DIR)
        agent.mkdirs()
        File(dir, "project_prompt.txt").writeText("how to work")
        return File(agent, AgentLog.SESSION).apply { writeText(FIRST_ROW) }
    }

    @Test
    fun `the record is one file, and a file of that name elsewhere is not it`() {
        val session = record()
        val prompt = File(session.parentFile.parentFile, "project_prompt.txt")
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
    fun `a name starting with a dot is refused`() {
        assertTrue(workspace.nameProblem(".ssh") != null)
        assertTrue(workspace.nameProblem("a/b") != null)
        assertTrue(workspace.nameProblem("  ") != null)
        assertNull(workspace.nameProblem("Shopping list.txt"))
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

        val readBack = Workspace(workspace.accountDir)
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
            // **Three now, and the third is the request log.** It is written on every response,
            // including the keepalives sent while nobody is looking, so left unignored it would be
            // the noisiest thing in the working tree.
            "the app's own lines, and nothing else",
            listOf("agent/", ".muffy/", "requests/"),
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
    fun `a lock on a folder covers what is inside it`() {
        // The bug this is for: locking a folder protected the folder and nothing in it, so
        // the files a person had just frozen were all still editable.
        val folder = File(workspace.accountDir, "drafts")
        folder.mkdirs()
        val inside = File(folder, "milk.txt")
        workspace.write(inside, "two litres")
        lock("drafts")

        // **A person's lock binds the agent, not the person** - the operator's rule: "system locks
        // restrict both users and agents, and user locks restrict agents (not users themselves!)".
        // So `isAgentLocked` is true (the agent may not touch it) and `isLocked` is false (the person
        // may, which is how they can still edit and unlock what they locked).
        assertTrue("the file inside is held against the agent", workspace.isAgentLocked(inside))
        assertFalse("but not against the person who set the lock", workspace.isLocked(inside))
        // The agent's funnel refuses, and that is the rule that matters here.
        assertEquals(
            "the agent is told why",
            "\"milk.txt\" is locked, so it cannot be changed",
            workspace.agentLockProblem(inside),
        )
        // The person can still write it, because a lock they set is an instruction to the agent.
        assertTrue("the person may still write it", workspace.write(inside, "three litres"))
        assertEquals("and the text changed", "three litres", inside.readText())
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

        // A *different* source, so the second refusal can only be about the lock: the first copy
        // already put a `milk.txt` in `inbox`, and a taken name would refuse for another reason and
        // make this assertion pass while the lock had stopped working.
        val bread = File(folder, "bread.txt")
        workspace.write(bread, "one loaf")
        lock("inbox")
        // **The agent is refused, which is the rule the lock exists for.** Copying into a locked
        // folder is a write into it; a person may still do it to a folder they locked themselves.
        assertEquals(
            "the agent is told why",
            "\"inbox\" is locked, so it cannot be changed",
            workspace.agentLockProblem(File(elsewhere, "bread.txt")),
        )
    }

    @Test
    fun `the lock is remembered across a workspace that has not read it yet`() {
        // The flags live in `.muffy`, so a second Workspace over the same account sees the
        // lock without being told: the cache is a cache, not the truth.
        val file = File(workspace.accountDir, "milk.txt")
        workspace.write(file, "two litres")
        lock("milk.txt")

        val other = Workspace(workspace.accountDir)

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
        assertEquals("and nothing stops it", Workspace.Unsync.NONE, workspace.unsync(file))
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
    fun `a secret is held by the app, and is never written`() {
        val dir = project()
        // **A name the app recognises without reading the file.** It cannot judge content - see
        // `looksSecret` - so the rule is the handful of names that are never anything else.
        val key = File(dir, ".env").apply { writeText("OPENROUTER_KEY=sk-or-...") }

        assertEquals("held by the app", Workspace.Hold.APP, workspace.hold(key, emptySet()))
        assertEquals("and kept out of the repository", Workspace.Unsync.APP, workspace.unsync(key))
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
    fun `unsync is a list of paths this phone keeps, not a git ignore`() {
        // **The distinction the whole feature turns on.** A `.gitignore` line would untrack the file:
        // out of the index, out of future commits, unversioned here. Unsyncing leaves it tracked and
        // committed and only keeps it out of the tree that is *sent*, so the answer lives in the
        // app's own flags beside pins and locks.
        val dir = project()
        val file = File(dir, "notes.txt").apply { writeText("mine") }

        assertEquals("nothing yet", Workspace.Unsync.NONE, workspace.unsync(file))
        // A `.gitignore` line is NOT an unsync - it is a different act with a different consequence.
        File(dir, Workspace.IGNORE).writeText("notes.txt\n")
        assertEquals("an ignore is not an unsync", Workspace.Unsync.NONE, workspace.unsync(file))

        val path = workspace.pathString(workspace.relative(file)!!)
        workspace.setUnsynced(setOf(path))
        assertEquals("the person unsynced it", Workspace.Unsync.USER, workspace.unsync(file))
        assertEquals("and it is left out of what is sent", listOf("notes.txt"), workspace.unsentFrom("shopping"))

        // And back again: the toggle has two directions, which the ignore button never had.
        workspace.setUnsynced(emptySet())
        assertEquals("and it is sent again", Workspace.Unsync.NONE, workspace.unsync(file))
        assertTrue("with nothing left out", workspace.unsentFrom("shopping").isEmpty())
    }

    @Test
    fun `a secret is left out of what is sent even though it is never named`() {
        val dir = project()
        File(dir, ".env").writeText("KEY=sk-or-...")
        File(dir, "notes.txt").writeText("mine")

        // **Found by walking, not by the stored list.** The app's rule is about names, so the only
        // way to apply it is to look at what is actually in the project - a stored list could not
        // know about a file somebody made a second ago.
        assertEquals(
            "the secret is left out and the note is not",
            listOf(".env"),
            workspace.unsentFrom("shopping"),
        )
    }
}
