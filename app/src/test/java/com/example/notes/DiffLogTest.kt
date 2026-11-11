package com.example.notes

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * `diff` and `log` — **the two questions about a history.**
 *
 * `diff` is the compressed form of a change: `read` gives full contents, so a 400-line file where
 * three lines moved costs 400 lines of context and leaves the model to diff in its head, which it
 * does badly. `log` is the question that comes *first* — which versions there are — and its answer
 * names the ids `diff` takes.
 *
 * The one question `read` cannot answer. `read` gives full contents, so a 400-line file where three
 * lines moved costs 400 lines of context and leaves the model to diff in its head, which it does
 * badly.
 *
 * **Revisions only**, because work that is staged has no id until a `push` makes it a version — so
 * there is no working-tree side to compare against.
 */
class DiffLogTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var ws: Workspace
    private lateinit var git: ProjectGit
    private lateinit var log: AgentLog

    @Before
    fun setUp() {
        ws = Workspace.single(temp.root)
        ws.createProjectFolders("shopping").getOrThrow()
        git = ProjectGit(ws, { null }, { GitAuthor("Muffy", "muffy@example.com") })
        git.init("shopping").getOrThrow()
        log = AgentLog.open(ws.projectDir("shopping"))
    }

    private fun tool(args: String): String =
        AgentTools(ws, "shopping", git, log).run(Call("c1", "diff", args))

    private fun logged(args: String): String =
        AgentTools(ws, "shopping", git, log).run(Call("c2", "log", args))

    private fun restored(args: String): String =
        AgentTools(ws, "shopping", git, log).run(Call("c3", "restore", args))

    private fun write(path: String, text: String) =
        ws.write(File(ws.projectDir("shopping"), path), text)

    /** The two revisions this test compares, oldest first. */
    private fun twoVersions(path: String, before: String, after: String): Pair<String, String> {
        write(path, before)
        val first = git.commit("shopping", "first")!!
        write(path, after)
        val second = git.commit("shopping", "second")!!
        return first to second
    }

    // ---- what it answers ----------------------------------------------------

    @Test
    fun `a diff names the file, its counts, and files the lines`() {
        val (from, to) = twoVersions("milk.txt", "one\ntwo\nthree\n", "one\nTWO\nthree\nfour\n")

        val body = tool("""{"path":"milk.txt","from":"$from","to":"$to"}""")

        assertTrue("it worked: $body", body.startsWith(AgentLog.SUCCESS))
        assertTrue("the file is named: $body", body.contains("milk.txt"))
        assertTrue("with what changed: $body", body.contains("+2"))
        assertTrue("and what went: $body", body.contains("−1"))
        // **Filed, not quoted.** A diff of forty files is not a conversation, and the file is where
        // the lines are — the same protocol as `runs` and `shot`.
        assertTrue("and it says where the lines are: $body", body.contains("agent/git/diff-"))
    }

    @Test
    fun `the filed diff holds the added and removed lines`() {
        val (from, to) = twoVersions("milk.txt", "one\ntwo\n", "one\nTWO\n")

        val body = tool("""{"path":"milk.txt","from":"$from","to":"$to"}""")
        // **The path as the answer gives it**, which is project-relative and ends in `.txt`.
        val where = Regex("""agent/git/diff-[^ ]+\.txt""").find(body)!!.value
        val filed = File(ws.projectDir("shopping"), where).readText()

        assertTrue("the new line: $filed", filed.contains("+TWO"))
        assertTrue("and the old one: $filed", filed.contains("-two"))
        assertTrue("and what did not change: $filed", filed.contains(" one"))
    }

    @Test
    fun `a folder path answers for every file under it`() {
        // **`path` is a prefix**, so `src/` answers the `git log -- path/to/folder` question — which
        // is the one that matters after `unpack` lands forty files.
        write("src/a.kt", "fun a() = 1\n")
        write("src/b.kt", "fun b() = 2\n")
        write("README.md", "readme\n")
        val from = git.commit("shopping", "first")!!
        write("src/a.kt", "fun a() = 10\n")
        write("src/b.kt", "fun b() = 20\n")
        write("README.md", "readme, changed\n")
        val to = git.commit("shopping", "second")!!

        val body = tool("""{"path":"src/","from":"$from","to":"$to"}""")

        assertTrue("a is there: $body", body.contains("src/a.kt"))
        assertTrue("and b: $body", body.contains("src/b.kt"))
        assertFalse("and the file outside the folder is not: $body", body.contains("README.md"))
    }

    @Test
    fun `a binary file is named and not diffed`() {
        // **A diff of a zip is a page of replacement characters** that costs the whole conversation
        // and says nothing. `kindOfPath` answers by name, before anything is read.
        write("blob.bin", "not really binary, but its name says so\n")
        val from = git.commit("shopping", "first")!!
        write("blob.bin", "changed\n")
        val to = git.commit("shopping", "second")!!

        val body = tool("""{"path":"blob.bin","from":"$from","to":"$to"}""")

        assertTrue("it is named: $body", body.contains("blob.bin"))
        assertTrue("and said to be binary: $body", body.contains("binary"))
    }

    @Test
    fun `nothing changed between one revision and itself`() {
        write("milk.txt", "one\n")
        val from = git.commit("shopping", "first")!!

        val body = tool("""{"path":"milk.txt","from":"$from","to":"$from"}""")

        assertTrue("it worked: $body", body.startsWith(AgentLog.SUCCESS))
        assertTrue("and says nothing changed: $body", body.contains("nothing changed"))
    }

    // ---- what it refuses ----------------------------------------------------

    @Test
    fun `one revision is refused, because there is no working-tree side`() {
        // **The operator's ruling, as a refusal.** "Working tree vs HEAD doesnt make sense because
        // we autocommit on every change to tracked files/folders." Defaulting the missing end to
        // HEAD would answer a question nobody can have.
        write("milk.txt", "one\n")
        val from = git.commit("shopping", "first")!!

        val body = tool("""{"path":"milk.txt","from":"$from"}""")

        assertTrue("refused: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("naming both ends: $body", body.contains("both `from` and `to`"))
        // **"a version", not "committed"** — the refusal's wording follows the rule (2026-11-10): work
        // that is staged has no id until a push makes it a version, which is why one end cannot be left
        // out. The assertion names the reason rather than a word the sentence used to contain.
        assertTrue("and why: $body", body.contains("staged"))
    }

    @Test
    fun `a revision that does not exist is refused by name, not silently treated as HEAD`() {
        write("milk.txt", "one\n")
        val from = git.commit("shopping", "first")!!

        val body = tool("""{"path":"milk.txt","from":"$from","to":"0000000"}""")

        assertTrue("refused: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("naming the id: $body", body.contains("0000000"))
    }

    @Test
    fun `the tool takes no argument it does not describe`() {
        // The schema is built from the example, so a key the runner reads and the schema omits is a
        // key the model cannot send. `path`, `from`, `to` are all three.
        val spec = AgentTools.specs().first { it.name == "diff" }
        val described = spec.parameters.optJSONObject("properties")!!.keys().asSequence().toSet()

        assertEquals(setOf("path", "from", "to"), described)
        assertEquals(described, AgentTools.KEYS["diff"])
    }

    // ---- log ----------------------------------------------------------------

    @Test
    fun `log answers one line per version, newest first, and the ids are usable`() {
        // **The call that comes before `diff`.** `diff` needs two revision ids; this is where they
        // come from. The assertion that matters is that the id the answer gives is the id `diff`
        // takes — a log that named commits in a form nothing else accepts would be a dead end.
        write("milk.txt", "one\n")
        val first = git.commit("shopping", "first")!!
        write("milk.txt", "two\n")
        val second = git.commit("shopping", "second")!!

        val body = logged("""{"path":"milk.txt"}""")

        assertTrue("it worked: $body", body.startsWith(AgentLog.SUCCESS))
        assertTrue("the newest is named: $body", body.contains(second.take(7)))
        assertTrue("and the older one: $body", body.contains(first.take(7)))
        assertTrue("with its subject: $body", body.contains("second"))
        // **Newest first**, which is the order a person reads a history in and the order that makes
        // "the last change" the first line.
        assertTrue(
            "newest first: $body",
            body.indexOf(second.take(7)) < body.indexOf(first.take(7)),
        )
    }

    @Test
    fun `log with no path answers the whole project`() {
        write("a.txt", "one\n")
        git.commit("shopping", "touched a")!!
        write("b.txt", "two\n")
        git.commit("shopping", "touched b")!!

        val body = logged("{}")

        assertTrue("both are named: $body", body.contains("touched a") && body.contains("touched b"))
        assertTrue("and it says how many: $body", body.contains("version"))
    }

    @Test
    fun `log names nothing for a path that never changed`() {
        // **A sentence, not an empty list.** "Nothing has ever changed that file" is an answer; a
        // blank body would read as a command that failed to say anything.
        write("a.txt", "one\n")
        git.commit("shopping", "touched a")!!

        val body = logged("""{"path":"never.txt"}""")

        assertTrue("it answers: $body", body.startsWith(AgentLog.SUCCESS))
        assertTrue("and says so in words: $body", body.contains("Nothing has ever changed"))
    }

    @Test
    fun `log stops at count and says it stopped`() {
        // **A log that silently truncated would read as a short history.** The sentence is what
        // makes the difference, and it is the same rule every other bounded answer follows.
        write("a.txt", "0\n")
        repeat(5) { i ->
            write("a.txt", "$i\n")
            git.commit("shopping", "change $i")!!
        }

        val body = logged("""{"count":2}""")

        assertTrue("it answers: $body", body.startsWith(AgentLog.SUCCESS))
        assertTrue("and says it stopped: $body", body.contains("raise `count`"))
    }

    // ---- restore: one file, or the whole tree --------------------------------

    @Test
    fun `restore with no path puts the whole tree back, including removing what was not there`() {
        // **The half a per-path restore cannot do.** "Put the project back" means files the revision
        // did not have are **gone**, and a loop over the paths that existed then would leave them
        // behind — a tree half from one version and half from another, which is worse than either.
        write("milk.txt", "one\n")
        write("kept.txt", "keep me\n")
        val first = git.commit("shopping", "first")!!
        write("milk.txt", "two\n")
        write("added-later.txt", "not in first\n")
        git.commit("shopping", "second")!!

        val body = restored("""{"revision":"$first"}""")

        assertTrue("it worked: $body", body.startsWith(AgentLog.SUCCESS))
        val root = ws.projectDir("shopping")
        assertEquals("the old text is back", "one\n", File(root, "milk.txt").readText())
        assertTrue("the file the version did not have is gone", !File(root, "added-later.txt").exists())
        assertTrue("and the file it did have is still there", File(root, "kept.txt").exists())
    }

    @Test
    fun `restore clears the stage by default, so the next push does not commit the whole tree`() {
        // **Why this is not cosmetic.** `push` counts the index; an index full of "every file
        // changed" would make the next push commit the entire tree as one version, with a message
        // somebody wrote about something else.
        write("a.txt", "one\n")
        val first = git.commit("shopping", "first")!!
        write("a.txt", "two\n")
        git.commit("shopping", "second")!!

        val body = restored("""{"revision":"$first"}""")

        assertTrue("it worked: $body", body.startsWith(AgentLog.SUCCESS))
        assertTrue("and says the stage was cleared: $body", body.contains("stage was cleared"))
        assertEquals(
            "and nothing is staged",
            0,
            git.changes("shopping").staged,
        )
    }

    @Test
    fun `keep leaves the stage, for a caller that wants to see what moved`() {
        write("a.txt", "one\n")
        val first = git.commit("shopping", "first")!!
        write("a.txt", "two\n")
        git.commit("shopping", "second")!!

        val body = restored("""{"revision":"$first","keep":true}""")

        assertTrue("it worked: $body", body.startsWith(AgentLog.SUCCESS))
        assertTrue("and says the stage was kept: $body", body.contains("stage was kept"))
    }

    @Test
    fun `a whole-tree restore is refused over work that is in no version`() {
        // **The one irreversible act this command could do.** A checkout destroys uncommitted work
        // and nothing can bring it back — so the refusal comes before the checkout, not after, and
        // `keep` does not override it: `keep` is about the stage, and the stage is not the work.
        write("a.txt", "one\n")
        val first = git.commit("shopping", "first")!!
        write("a.txt", "unsaved\n")

        val body = restored("""{"revision":"$first"}""")

        assertTrue("refused: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("saying why: $body", body.contains("in no version"))
        assertEquals(
            "and the work is still there",
            "unsaved\n",
            File(ws.projectDir("shopping"), "a.txt").readText(),
        )
    }

    @Test
    fun `keep does not let a checkout destroy uncommitted work`() {
        // **The flag governs a description, not the bytes.** This test exists because "keep" reads
        // like "keep my work", and it does not mean that.
        write("a.txt", "one\n")
        val first = git.commit("shopping", "first")!!
        write("a.txt", "unsaved\n")

        val body = restored("""{"revision":"$first","keep":true}""")

        assertTrue("still refused: $body", body.startsWith(AgentLog.FAILURE))
        assertEquals(
            "and the work is still there",
            "unsaved\n",
            File(ws.projectDir("shopping"), "a.txt").readText(),
        )
    }

    @Test
    fun `log takes path and count, and nothing else`() {
        val spec = AgentTools.specs().first { it.name == "log" }
        val described = spec.parameters.optJSONObject("properties")!!.keys().asSequence().toSet()

        assertEquals(setOf("path", "count"), described)
        assertEquals(described, AgentTools.KEYS["log"])
    }
}
