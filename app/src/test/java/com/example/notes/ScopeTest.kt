package com.example.notes

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * **How far an agent may reach, which is not the same as what it may do.**
 *
 * The rule this file exists for is that a shorter tool list is **not** a sandbox. `tree` accepts `../`
 * on purpose — its own description says so — and `search` walks every project in the account, so a
 * subagent handed `{read, tree, edit}` could still read the whole account and write into any project
 * it could name. The boundary has to be enforced on the **path**, in the one funnel every tool already
 * goes through, and that is what [Scope] is checked against.
 *
 * Every test here calls a real tool with real arguments: a scope checked only in `specs()` would pass
 * a test of the tool list and refuse nothing.
 */
class ScopeTest {

    private lateinit var root: File
    private lateinit var ws: Workspace
    private lateinit var git: ProjectGit
    private lateinit var log: AgentLog
    private lateinit var tools: AgentTools

    private val author = GitAuthor("Test", "test@example.com")

    @Before
    fun makeSpace() {
        root = File(System.getProperty("java.io.tmpdir"), "scope-${System.nanoTime()}")
        ws = Workspace(File(root, "muffyuniverse")).apply { accountDir.mkdirs() }
        ws.projectDir("groceries").mkdirs()
        ws.projectDir("system").mkdirs()
        ws.projectDir("notes").mkdirs()
        File(ws.projectDir("groceries"), "list.txt").writeText("milk\neggs\n")
        File(ws.projectDir("notes"), "secret.txt").writeText("the other project's file\n")
        git = ProjectGit(ws, { null }, { author })
        git.init("groceries").getOrThrow()
        git.init("notes").getOrThrow()
        log = AgentLog.open(ws.projectDir("groceries"))
    }

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    /** A tool set scoped to one project — the shape a subagent gets. */
    private fun scoped(project: String = "groceries") = AgentTools(
        ws = ws,
        project = project,
        git = git,
        log = log,
        scope = Scope.Project(project),
    )

    /** The same tools for the chat agent: the whole account. */
    private fun accountWide() = AgentTools(ws = ws, project = "groceries", git = git, log = log)

    private fun AgentTools.call(tool: String, args: String) =
        run(Call(id = "1", tool = tool, args = args), Deadline.none())

    // ---- reading ------------------------------------------------------------

    @Test
    fun `a scoped agent reads its own project`() {
        val answer = scoped().call("read", """{"path":"list.txt"}""")
        assertTrue("its own file is readable: $answer", answer.startsWith("0"))
        assertTrue("and it is the file that was asked for: $answer", answer.contains("milk"))
    }

    @Test
    fun `a scoped agent cannot read another project, and the refusal says which one it is on`() {
        val answer = scoped().call("read", """{"path":"../notes/secret.txt"}""")

        assertTrue("refused, not answered: $answer", answer.startsWith("1"))
        assertFalse("**the other project's text is not in the answer**", answer.contains("the other project"))
        assertTrue(
            "and the refusal names the boundary: $answer",
            answer.contains("outside groceries"),
        )
    }

    @Test
    fun `a scoped agent cannot list another project, which is the tool that made a shorter list useless`() {
        // **The falsification that matters.** `tree` takes `../` deliberately — "a thing worth
        // finding may live in another project" — so this is the exact call a limited tool list
        // would have allowed. If this passes, the scope is real; if it does not, the "sandbox" is
        // the tool list and nothing else.
        val answer = scoped().call("tree", """{"path":"../notes"}""")

        assertTrue("refused: $answer", answer.startsWith("1"))
        assertFalse("and no names from the other project: $answer", answer.contains("secret.txt"))
    }

    @Test
    fun `a scoped agent's search does not reach another project`() {
        // **The one tool that never asks `resolve` for a path** — it walks a folder — so this is the
        // tool that would make every other refusal a formality. `search`'s own description says it
        // searches "every project in the account", which is true for the chat agent and is exactly
        // what a subagent must not be able to do.
        //
        // The answer is `0` and empty rather than a refusal: a search is a question about a place,
        // and "nothing here" is the true answer about the place it was allowed to look. What must
        // not happen is the *other project's text* appearing.
        val answer = scoped().call("search", """{"query":"other project"}""")

        assertTrue("answered, not refused: $answer", answer.startsWith("0"))
        assertFalse("**and the other project's text is not in it**", answer.contains("the other project"))
        assertTrue(
            "and it says which place it looked in, rather than claiming the account: $answer",
            answer.contains("this project"),
        )
    }

    @Test
    fun `a scoped agent's search finds what is in its own project`() {
        // The other direction: a scope that quietly searched nothing would pass the test above.
        val answer = scoped().call("search", """{"query":"eggs"}""")
        assertTrue("found: $answer", answer.startsWith("0"))
        assertTrue("with the file: $answer", answer.contains("list.txt"))
    }

    @Test
    fun `the chat agent still reaches the whole account, because that is its rule`() {
        // **The other direction, and the one that keeps the scope from being a regression.** The
        // operator's rule is "any project's agent gets readonly access to account folders outside his
        // project", and a scope applied to everyone would have quietly removed it.
        val answer = accountWide().call("read", """{"path":"../notes/secret.txt"}""")
        assertTrue("the chat agent reads another project: $answer", answer.startsWith("0"))
        assertTrue("and gets the text: $answer", answer.contains("the other project's file"))
    }

    @Test
    fun `the chat agent still lists another project`() {
        val answer = accountWide().call("tree", """{"path":"../notes"}""")
        assertTrue("listed: $answer", answer.startsWith("0"))
        assertTrue("with the file in it: $answer", answer.contains("secret.txt"))
    }

    // ---- writing ------------------------------------------------------------

    @Test
    fun `a scoped agent cannot write into another project`() {
        val answer = scoped().call("create", """{"path":"../notes/written.txt","text":"x"}""")

        assertTrue("refused: $answer", answer.startsWith("1"))
        assertFalse(
            "**and nothing was written** — a refusal that still made the file is the worst outcome, " +
                "because the model is told it failed and the project disagrees",
            File(ws.projectDir("notes"), "written.txt").exists(),
        )
    }

    @Test
    fun `a scoped agent can write in its own project`() {
        val answer = scoped().call("create", """{"path":"new.txt","text":"hello"}""")
        assertTrue("written: $answer", answer.startsWith("0"))
        assertEquals("hello", File(ws.projectDir("groceries"), "new.txt").readText())
    }

    @Test
    fun `a scoped agent cannot restore a version of another project's file`() {
        // **`restore` is a write that goes through a different path** — it takes a revision id and
        // reaches the repository rather than the file — so it is the one that a scope checked only
        // on `write` would miss.
        File(ws.projectDir("notes"), "secret.txt").writeText("changed\n")
        git.commit("notes", "changed")
        val revision = git.log("notes", "secret.txt").first().id

        val answer = scoped().call(
            "restore",
            """{"path":"../notes/secret.txt","revision":"$revision"}""",
        )

        assertTrue("refused: $answer", answer.startsWith("1"))
        assertEquals(
            "and the other project's file is untouched",
            "changed\n",
            File(ws.projectDir("notes"), "secret.txt").readText(),
        )
    }

    // ---- what a subagent is offered -----------------------------------------

    @Test
    fun `a subagent is offered a smaller list, and every name in it is a real tool`() {
        // **A set of names is a set of strings**, and a typo in it would silently give a subagent
        // one tool fewer than intended with nothing to say so. The check is against the account's
        // own list, which is the only definition of what exists.
        val everything = AgentTools.specs().map { it.name }
        val subagent = AgentTools.specs(scope = Scope.Project("groceries")).map { it.name }

        assertTrue(
            "every name a subagent is offered is a tool that exists: " +
                "${subagent.filter { it !in everything }}",
            subagent.all { it in everything },
        )
        assertTrue("and the list really is smaller", subagent.size < everything.size)
        assertEquals(
            "**nothing is silently dropped** — the difference is exactly the tools withheld",
            everything.filter { it !in subagent }.toSet(),
            (everything.toSet() - AgentTools.SUBAGENT_TOOLS),
        )
    }

    @Test
    fun `a subagent has the three the operator asked for, and not the ones that reach out`() {
        // The instruction: *"subagents should still be able to run ci, use background and look"*.
        // The other half is the reason they are the exception — everything withheld either reaches
        // outside the project, costs money the subagent cannot see, or is the hiring agent's call.
        val subagent = AgentTools.specs(scope = Scope.Project("groceries")).map { it.name }

        for (tool in listOf("ci", "background", "look")) {
            assertTrue("$tool is offered", tool in subagent)
        }
        for (tool in listOf("browse", "fetch", "mcp", "subagent", "restore", "history")) {
            assertFalse("$tool is withheld", tool in subagent)
        }
    }

    @Test
    fun `the chat agent's list is unchanged by any of this`() {
        // The falsification for the whole step: a filter that leaked into the default would take
        // tools away from every chat agent, which is a regression nobody would look for here.
        assertEquals(
            "the account's list is the full set",
            AgentTools.specs().map { it.name },
            AgentTools.specs(scope = Scope.Account).map { it.name },
        )
        assertTrue("and it is every tool", AgentTools.specs().size >= 20)
    }

    // ---- two agents, one file ------------------------------------------------

    @Test
    fun `a write after a read of a file another agent changed is noticed`() {
        // **The lost-update this app cannot prevent.** There is no merge and no conflict: one write
        // simply lands last and the history shows two commits with the second winning. A claim file
        // would be a second source of truth about who is editing what, so the mitigation is that it
        // stops being invisible.
        val file = File(ws.projectDir("groceries"), "list.txt")
        scoped().call("read", """{"path":"list.txt"}""")

        // Another agent writes, a moment later.
        Thread.sleep(20)
        file.writeText("milk\neggs\nbread\n")

        val before = Trace.report().lines.size
        scoped().call("edit", """{"path":"list.txt","at":3,"text":"butter"}""")

        val fresh = Trace.report().lines.take(Trace.report().lines.size - before)
        assertTrue(
            "**the stale read is named**: $fresh",
            fresh.any { it.contains("stale-read") && it.contains("list.txt") },
        )
    }

    @Test
    fun `a write with no read before it is not called stale, because there is nothing to compare`() {
        // The falsification: a guard that fired on every write would be noise, and noise in a trace
        // is the same as no trace — a person stops reading it.
        File(ws.projectDir("groceries"), "fresh.txt").writeText("a\n")

        val before = Trace.report().lines.size
        scoped().call("edit", """{"path":"fresh.txt","at":1,"text":"b"}""")

        val fresh = Trace.report().lines.take(Trace.report().lines.size - before)
        assertFalse(
            "nothing is claimed about a file this agent never read: $fresh",
            fresh.any { it.contains("stale-read") },
        )
    }

    @Test
    fun `a write after a read of an unchanged file is not called stale`() {
        // The other half: the ordinary case must be silent, or the line means nothing.
        File(ws.projectDir("groceries"), "same.txt").writeText("a\nb\n")
        scoped().call("read", """{"path":"same.txt"}""")

        val before = Trace.report().lines.size
        scoped().call("edit", """{"path":"same.txt","at":1,"text":"A"}""")

        val fresh = Trace.report().lines.take(Trace.report().lines.size - before)
        assertFalse("no claim: $fresh", fresh.any { it.contains("stale-read") })
    }

    // ---- the boundary itself ------------------------------------------------

    @Test
    fun `the account's own folder is outside a project's scope`() {
        // **The asymmetry worth naming.** The account root is where the projects live, so a scoped
        // agent that could list it could enumerate every project it is not allowed to read — which
        // makes the scope a formality.
        val answer = scoped().call("tree", """{"path":".."}""")
        assertTrue("refused: $answer", answer.startsWith("1"))
        assertFalse("and no project names in it: $answer", answer.contains("notes"))
    }

    @Test
    fun `a path outside the account is refused by the account's rule, not the scope's`() {
        // **Two boundaries, two sentences.** Telling a scoped agent "that is outside groceries"
        // about `/etc/passwd` would name the wrong boundary and send it looking for a way around the
        // one that is not there. The account check comes first for exactly this reason.
        val answer = scoped().call("read", """{"path":"../../../etc/passwd"}""")
        assertTrue("refused: $answer", answer.startsWith("1"))
        assertTrue(
            "by the account's rule: $answer",
            answer.contains("outside this account"),
        )
    }

    @Test
    fun `an Account scope refuses nothing that the account rule allows`() {
        // The scope's own falsification: `Account.refusal()` is empty, and a bug that returned a
        // sentence for it would refuse everything the chat agent does.
        assertEquals("an account scope has no boundary of its own", "", Scope.Account.refusal())
        assertTrue(
            "and a project scope names the project",
            Scope.Project("groceries").refusal().contains("groceries"),
        )
    }
}
