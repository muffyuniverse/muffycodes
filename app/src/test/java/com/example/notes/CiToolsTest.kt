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
 * The agent's sight of GitHub Actions: `ci` and `runs`.
 *
 * **These are the first tools that reach outside the phone for something other than a web page**, so
 * what is asserted here is mostly what they do *not* do: they do not start a run, they do not spend
 * the conversation on a log, and they refuse in words when the project has no GitHub behind it.
 *
 * The GitHub they talk to is [DiskGitHub], which is a folder. A run is a directory under the
 * repository (`ci/<id>/run.json`, `ci/<id>/jobs/<job>.log`, `ci/<id>/artifacts/<name>`), so the whole
 * surface is exercised without a network, a token or a workflow.
 */
class CiToolsTest {

    private lateinit var root: File
    private lateinit var ws: Workspace
    private lateinit var git: ProjectGit
    private lateinit var log: AgentLog
    private lateinit var github: DiskGitHub

    private val repo = "muffycodes"

    @Before
    fun setUp() {
        root = File(System.getProperty("java.io.tmpdir"), "ci-tools-" + System.nanoTime())
        root.mkdirs()
        val accounts = AccountStore(root)
        val account = accounts.save(
            Account("dev", "Test Account", "dev@example.com", dev = true),
        )
        ws = Workspace(accounts.dirOf(account.login))
        github = DiskGitHub(accounts.devGitHubDir(account.login), account.login)
        log = AgentLog.open(ws.projectDir(repo))
        git = ProjectGit(
            workspace = ws,
            tokenProvider = { "a-key" },
            authorProvider = { AppViewModel.DEFAULT_AUTHOR },
        )
        ws.projectDir(repo).mkdirs()
        ws.write(File(ws.projectDir(repo), "milk.txt"), "two litres")
        // **A remote that is the fake GitHub**, because that is what `where()` reads: the tools ask
        // the repository where it lives, and a project with no remote has no CI to read.
        assertTrue("the project has a repository", git.init(repo).isSuccess)
        assertTrue("and a remote", git.makeRemote(repo, github.cloneUrlFor(account.login, repo)))
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    /** The tools, wired to the fake GitHub and its key. */
    private fun tools(withGitHub: Boolean = true) = AgentTools(
        ws, repo, git, log,
        github = { if (withGitHub) github else null },
        githubToken = { if (withGitHub) "a-key" else null },
    )

    /** A run on the fake GitHub: an id, an outcome, and the jobs it had. */
    private fun aRun(id: Long, conclusion: String?, vararg jobs: Pair<String, String>) {
        val dir = File(File(github.repoDir(repo), "ci"), id.toString())
        File(dir, "jobs").mkdirs()
        File(dir, "run.json").writeText(
            JSONObject().apply {
                put("name", "Build and release")
                put("status", if (conclusion == null) "in_progress" else "completed")
                if (conclusion == null) put("conclusion", JSONObject.NULL) else put("conclusion", conclusion)
                put("branch", "development")
                put("event", "push")
                put("sha", "236b090")
                put("created_at", "2026-10-31T06:38:00Z")
                put("url", "https://github.com/dev/$repo/actions/runs/$id")
            }.toString(),
        )
        for ((name, text) in jobs) {
            File(File(dir, "jobs"), "$name.log").writeText(text)
        }
    }

    private fun call(tool: String, args: String) = tools().run(Call("c1", tool, args))

    @Test
    fun `a remote is read as a repository whichever way it is written`() {
        // **Three shapes, because the app meets all three.** A clone URL from GitHub, an ssh one a
        // person may have set by hand, and a `file:` one — which is [DiskGitHub], and which
        // `File.toURI()` writes with a SINGLE slash (`file:/tmp/...`). That last shape is why the
        // parser has its own branch: without it the fake GitHub would be unreachable by the same
        // code the phone runs, and the tests would prove nothing about the phone.
        val cases = mapOf(
            "https://github.com/dev/muffycodes.git" to RepoRef("dev", "muffycodes"),
            "https://github.com/dev/muffycodes" to RepoRef("dev", "muffycodes"),
            "https://github.com/dev/muffycodes/" to RepoRef("dev", "muffycodes"),
            "git@github.com:dev/muffycodes.git" to RepoRef("dev", "muffycodes"),
            "file:/tmp/state/dev/github/dev/muffycodes.git" to RepoRef("dev", "muffycodes"),
        )
        for ((remote, expected) in cases) {
            assertEquals(remote, expected, RepoRef.of(remote))
        }
        // And what is not a repository is not one: a remote that names no pair has no owner to ask.
        assertEquals("an empty remote", null, RepoRef.of(""))
        assertEquals("a bare host", null, RepoRef.of("https://github.com"))
    }

    // ---- ci ---------------------------------------------------------------

    @Test
    fun `ci lists the runs newest first, and a run that has not finished is not a failure`() {
        aRun(100, "success", "Test (0)" to "all good")
        aRun(200, null, "Test (0)" to "still going")

        val body = call("ci", "{}")

        assertTrue("it worked: $body", body.startsWith(AgentLog.SUCCESS))
        // **Newest first**, because that is the one the question is about.
        assertTrue("the newer run is named first: $body", body.indexOf("200") < body.indexOf("100"))
        // **The distinction the tool exists to keep.** A blank conclusion would read as "it finished
        // and had nothing to say", which is the opposite of "it has not finished".
        assertTrue("the unfinished run says so: $body", body.contains("running"))
        assertTrue("and the finished one says what it concluded: $body", body.contains("success"))
    }

    @Test
    fun `a project with no GitHub is refused in words, and nothing is asked of it`() {
        // A local project has a remote that is not GitHub's — or none at all. The refusal names the
        // situation rather than reporting a failed request, because there was no request.
        val body = tools(withGitHub = false).run(Call("c1", "ci", "{}"))

        assertTrue("it is a refusal: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("and it says why: $body", body.contains("no GitHub"))
    }

    @Test
    fun `a branch with no runs says so, rather than showing an empty list`() {
        aRun(100, "success", "Test (0)" to "ok")

        val body = call("ci", """{"branch":"main"}""")

        assertTrue("it worked: $body", body.startsWith(AgentLog.SUCCESS))
        assertTrue("and it explains the nothing: $body", body.contains("No runs"))
    }

    // ---- runs -------------------------------------------------------------

    @Test
    fun `a log is filed, and the answer is its path rather than forty kilobytes of it`() {
        // **The whole reason `runs` exists.** A job log is tens of kilobytes; putting it in the
        // conversation would spend the window on a build's scrollback. So it is written into the
        // project — inside `agent/`, which git excludes — and the answer names the file.
        val noise = (1..400).joinToString("\n") { "2026-10-31T06:38:00Z step $it: nothing to report" }
        aRun(100, "failure", "Test (0)" to "$noise\nFAILED: AgentChatTest")

        val body = call("runs", """{"run":100}""")

        assertTrue("it worked: $body", body.startsWith(AgentLog.SUCCESS))
        val filed = File(ws.projectDir(repo), "agent/ci/run-100.log")
        assertTrue("the log is in the project: ${filed.absolutePath}", filed.isFile)
        assertTrue("with all of it: ${filed.length()}", filed.readText().contains("step 400"))
        assertTrue("and the answer says where: $body", body.contains("agent/ci/run-100.log"))
        // **Not the whole log in the answer.** A tool that files a result and then pastes it back has
        // done the work of filing for nothing.
        assertFalse("the answer is not the log: $body", body.contains("step 400"))
    }

    @Test
    fun `grep finds the failure in a log, with line numbers`() {
        val lines = (1..50).joinToString("\n") { "line $it" } + "\nFAILED: something broke"
        aRun(100, "failure", "Test (0)" to lines)

        val body = call("runs", """{"run":100,"grep":"FAILED"}""")

        assertTrue("it worked: $body", body.startsWith(AgentLog.SUCCESS))
        assertTrue("it found the line: $body", body.contains("FAILED: something broke"))
        // A number, so the next call can name a place — the same rule `read`'s grep follows.
        assertTrue("and says where it is: $body", body.contains("51:"))
    }

    @Test
    fun `a job name that is not in the run is refused with the names that are`() {
        // **"Test" is not "Test (0)"**, and the difference matters: an empty answer would read as
        // "that job said nothing", which is a very different fact from "that job is not here".
        aRun(100, "success", "Test (0)" to "ok", "Build" to "ok")

        val body = call("runs", """{"run":100,"job":"Test"}""")

        assertTrue("it is a refusal: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("it names the real ones: $body", body.contains("Test (0)"))
        assertTrue("and the other one: $body", body.contains("Build"))
    }

    @Test
    fun `a run id that was never given is refused before anything is asked`() {
        val body = call("runs", """{"grep":"FAILED"}""")

        assertTrue("it is a refusal: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("and it says which key is missing: $body", body.contains("run"))
    }

    @Test
    fun `both tools take only the keys they take, and an unknown one is refused`() {
        // **The rule every tool follows**, asserted for the two new ones: a key the model sends that
        // the command does not take means the model and the description disagree, and answering as if
        // they did not is how a key silently becomes "everything".
        for ((tool, args) in listOf(
            "ci" to """{"event":"workflow_dispatch"}""",
            "runs" to """{"run":100,"tail":true}""",
        )) {
            val body = call(tool, args)
            assertTrue("$tool refuses the unknown key: $body", body.startsWith(AgentLog.FAILURE))
            assertTrue("$tool names it: $body", body.contains("event") || body.contains("tail"))
        }
    }

    @Test
    fun `starting a run is not among the things the agent can do`() {
        // **A regression guard, and it is about a decision rather than a bug.** `ci` reads; it does
        // not dispatch. Starting a workflow is a capability that waits on the workflow guard — a run
        // the agent starts must not be able to reach repository secrets — and a key that quietly
        // existed here would be that capability granted by accident.
        assertFalse(
            "`ci` takes no event key",
            AgentTools.KEYS.getValue("ci").contains("event"),
        )
        val body = call("ci", """{"event":"workflow_dispatch"}""")
        assertTrue("and sending one is refused: $body", body.startsWith(AgentLog.FAILURE))
    }
}
