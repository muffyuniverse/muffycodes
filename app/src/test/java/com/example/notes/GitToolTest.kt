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
 * `git {op: "diff"}` — **the compressed form of a change.**
 *
 * The one question `read` cannot answer. `read` gives full contents, so a 400-line file where three
 * lines moved costs 400 lines of context and leaves the model to diff in its head, which it does
 * badly.
 *
 * **Revisions only**, because every change here is committed the moment it happens: an agent write
 * ends in a commit and a person's edit goes through `afterChange`, so there is no working-tree side
 * to compare against.
 */
class GitToolTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var ws: Workspace
    private lateinit var git: ProjectGit
    private lateinit var log: AgentLog

    @Before
    fun setUp() {
        ws = Workspace(temp.root)
        ws.createProjectFolders("shopping").getOrThrow()
        git = ProjectGit(ws, { null }, { GitAuthor("Muffy", "muffy@example.com") })
        git.init("shopping").getOrThrow()
        log = AgentLog.open(ws.projectDir("shopping"))
    }

    private fun tool(args: String): String =
        AgentTools(ws, "shopping", git, log).run(Call("c1", "git", args))

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

        val body = tool("""{"op":"diff","path":"milk.txt","from":"$from","to":"$to"}""")

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

        val body = tool("""{"op":"diff","path":"milk.txt","from":"$from","to":"$to"}""")
        val where = Regex("""agent/git/diff-[^\s.]+\.txt""").find(body)!!.value
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

        val body = tool("""{"op":"diff","path":"src/","from":"$from","to":"$to"}""")

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

        val body = tool("""{"op":"diff","path":"blob.bin","from":"$from","to":"$to"}""")

        assertTrue("it is named: $body", body.contains("blob.bin"))
        assertTrue("and said to be binary: $body", body.contains("binary"))
    }

    @Test
    fun `nothing changed between one revision and itself`() {
        write("milk.txt", "one\n")
        val from = git.commit("shopping", "first")!!

        val body = tool("""{"op":"diff","path":"milk.txt","from":"$from","to":"$from"}""")

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

        val body = tool("""{"op":"diff","path":"milk.txt","from":"$from"}""")

        assertTrue("refused: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("naming both ends: $body", body.contains("both `from` and `to`"))
        assertTrue("and why: $body", body.contains("committed"))
    }

    @Test
    fun `an op that is not diff is refused, and the refusal says what is`() {
        // **Named, because a model that guessed `log` or `status` deserves to know why not** — and
        // what to use instead. `status` was deleted; this is where a model learns that.
        val body = tool("""{"op":"status"}""")

        assertTrue("refused: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("naming the one op: $body", body.contains("`diff`"))
        assertTrue("and what to use for history: $body", body.contains("history"))
    }

    @Test
    fun `a revision that does not exist is refused by name, not silently treated as HEAD`() {
        write("milk.txt", "one\n")
        val from = git.commit("shopping", "first")!!

        val body = tool("""{"op":"diff","path":"milk.txt","from":"$from","to":"0000000"}""")

        assertTrue("refused: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("naming the id: $body", body.contains("0000000"))
    }

    @Test
    fun `the tool takes no argument it does not describe`() {
        // The schema is built from the example, so a key the runner reads and the schema omits is a
        // key the model cannot send. `op`, `path`, `from`, `to` are all four.
        val spec = AgentTools.specs().first { it.name == "git" }
        val described = spec.parameters.optJSONObject("properties")!!.keys().asSequence().toSet()

        assertEquals(setOf("op", "path", "from", "to"), described)
        assertEquals(described, AgentTools.KEYS["git"])
    }
}
