package com.example.notes

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        // **The trace goes to a file this class can read.** Two tests assert what the trace recorded,
        // and the trace is asynchronous — reading the sink is the only way not to race the writer.
        Trace.resetForTest()
        Trace.attach(File(root, "trace.log"))
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
        // **A remote that is the fake GitHub**, because that is what `ciWhere()` reads: the tools ask
        // the repository where it lives, and a project with no remote has no CI to read.
        assertTrue("the project has a repository", git.init(repo).isSuccess)
        // **The remote's target has to exist.** `makeRemote` only records a URL, and `DiskGitHub`
        // answers 404 for a repository that is not on its disk — which is what the real GitHub does,
        // and is why the fake is worth having. Making the folder is all `dispatch` asks of it; going
        // through `createRepo` would run a real git seed, which is more machinery than the question
        // needs and slower than the test deserves.
        assertTrue("the fake GitHub has the repository", github.repoDir(repo).mkdirs())
        assertTrue("and a remote", git.makeRemote(repo, github.cloneUrlFor(account.login, repo)))
    }

    @After
    fun tearDown() {
        Trace.resetForTest()
        Trace.attach((null as Trace.Sink?))
        root.deleteRecursively()
    }

    /** The tools, wired to the fake GitHub and its key. */
    /** What `upload` was asked to export, so a test can see the call rather than the store. */
    private val exported = mutableListOf<File>()

    /** A key and a seal, so these tests exercise the real reader rather than a stub of it. */
    private val KEY = "Zm9yLXRlc3Rpbmctb25seS0zMi1ieXRlLWtleSE="

    private fun sealed(text: String): String {
        val salt = ByteArray(8) { (it + 1).toByte() }
        val material = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(javax.crypto.spec.PBEKeySpec(KEY.toCharArray(), salt, 10_000, 48 * 8))
            .encoded
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(material.copyOfRange(0, 32), "AES"),
            javax.crypto.spec.IvParameterSpec(material.copyOfRange(32, 48)),
        )
        val body = "Salted__".toByteArray(Charsets.US_ASCII) + salt +
            cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return java.util.Base64.getEncoder().encodeToString(body)
    }

    /** The account's secrets, so a test can see whether the log reader was given the key. */
    private val secrets = mutableMapOf<String, String>()

    private val credentials = object : Credentials {
        override var activeLogin: String? = "dev"
        override fun logins() = listOf("dev")
        override fun token(login: String) = "a-key"
        override fun setToken(login: String, token: String) {}
        override fun forget(login: String) {}
        override fun hasToken(login: String) = true
        override fun modelKey(login: String): String? = null
        override fun setModelKey(login: String, key: String) {}
        override fun secret(name: String, host: String) = secrets[name]
        override fun setSecret(name: String, value: String, host: String) { secrets[name] = value }
    }

    private fun tools(withGitHub: Boolean = true, withDownloads: Boolean = false) = AgentTools(
        ws, repo, git, log,
        github = { if (withGitHub) github else null },
        githubToken = { if (withGitHub) "a-key" else null },
        credentials = credentials,
        // **Injected rather than reached for**, because `MediaStore` needs a `Context` and this test
        // has no phone. What is being tested here is the tool's decision — what it hands over — and
        // the real folder is `MediaStoreDownloads`.
        phone = if (withDownloads) {
            object : PhoneDownloads {
                override fun list() = Result.success(emptyList<PhoneDownloads.Item>())
                override fun read(name: String, into: File) =
                    Result.failure<Long>(IllegalStateException("nothing to read"))
                override fun write(file: File): Result<Export.Done> {
                    exported += file
                    return Result.success(Export.Done(file.name, file.length(), Export.FOLDER))
                }
            }
        } else {
            PhoneDownloads.NONE
        },
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

    private fun call(tool: String, args: String, withDownloads: Boolean = false) =
        tools(withDownloads = withDownloads).run(Call("c1", tool, args))

    /** Puts a workflow in the project, and answers its path from the project's root. */
    private fun workflow(name: String, text: String): String {
        val path = Workflows.DIR + "/" + name
        ws.write(File(ws.projectDir(repo), path), text)
        return path
    }

    /**
     * Locks [path], the way the screen's lock button does.
     *
     * **Account-relative, not project-relative**, because that is the shape `Workspace` stores and
     * the shape the app compares against. A test that locked the project-relative path would pass
     * while the phone never matched it — which is precisely the bug this helper exists to avoid.
     */
    private fun lock(path: String) {
        ws.setLocked(ws.locked() + "$repo/$path")
    }

    /**
     * What the trace has written so far.
     *
     * **Read from the sink, not from memory.** `Trace.note` is asynchronous (one task per event), so
     * a test that read a ring buffer would race the writer. The sink is a file this class attaches
     * in `@Before`, and reading it is the same read the report screen does.
     */
    private fun traceNow(): String = Trace.sinkNow()?.read().orEmpty()

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
    fun `an encrypted log is opened with the account's key before it is filed`() {
        // **Without this the tool files base64**, which reads exactly like a broken build. The build
        // encrypts its own output; the key is the account's, in the same store as every other secret.
        val plain = "EncryptedLogTest > something FAILED\n  AssertionError: unlocked"
        secrets[EncryptedLog.KEY_NAME] = KEY
        aRun(100, "failure", "Test (0)" to EncryptedLog.MARKER + "\n" + sealed(plain))

        val body = call("runs", """{"run":100}""")

        assertTrue("it worked: $body", body.startsWith(AgentLog.SUCCESS))
        val filed = File(ws.projectDir(repo), "agent/ci/run-100.log").readText()
        assertTrue("the filed log is the plaintext: $filed", filed.contains("AssertionError: unlocked"))
        assertFalse("and never the ciphertext: $filed", filed.contains(EncryptedLog.MARKER))
    }

    @Test
    fun `an encrypted log with no key says so rather than filing base64`() {
        // **The app's own gap must not read as the build's failure.** With no key stored the honest
        // answer is that the app has none — not a page of ciphertext, and not a refusal that blames
        // the run.
        aRun(100, "failure", "Test (0)" to EncryptedLog.MARKER + "\n" + sealed("anything"))

        val body = call("runs", """{"run":100}""")

        val filed = File(ws.projectDir(repo), "agent/ci/run-100.log").readText()
        assertTrue("it names the missing key: $filed", filed.contains("no key"))
    }

    /** An artifact on the fake GitHub, filed the way the real one serves it: a name and bytes. */
    private fun anArtifact(run: Long, name: String, bytes: ByteArray) {
        val dir = File(File(github.repoDir(repo), "ci"), "$run/artifacts")
        dir.mkdirs()
        File(dir, name).writeBytes(bytes)
    }

    /** A zip holding one `.enc` entry, which is what a sealed artifact arrives as. */
    private fun sealedArtifact(inner: String, sealedBytes: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry(inner + ".enc"))
            zip.write(sealedBytes)
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry(inner + ".enc.txt"))
            zip.write("This artifact is sealed.\n".toByteArray())
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun `a sealed artifact is opened before it is filed`() {
        // **The leak the log encryption does not cover.** The JUnit XML carries the assertion text
        // in plain — measured on a probe run — so the report is sealed before upload. This is the
        // step that turns that back into a report, and without it the tool would file ciphertext
        // under a `.zip` name and report success.
        secrets[EncryptedLog.KEY_NAME] = KEY
        val inner = "the report's own text, which a public repository must not carry"
        // **The workflow seals base64 of the FILE's bytes**, because `openssl -base64` writes base64
        // and the file is what that base64 decodes to. The test does the same thing, so it proves the
        // reader against the shape the workflow actually produces rather than a simpler one.
        val sealedBytes = (EncryptedLog.MARKER +
            sealed(java.util.Base64.getEncoder().encodeToString(inner.toByteArray())) +
            "\n").toByteArray(Charsets.US_ASCII)
        aRun(100, "failure", "Test (0)" to "ok")
        anArtifact(100, "test-report", sealedArtifact("test-report.zip", sealedBytes))

        val body = call("artifact", """{"run":100,"name":"test-report"}""")

        assertTrue("it worked: $body", body.startsWith(AgentLog.SUCCESS))
        val filed = File(ws.projectDir(repo), "agent/ci/artifacts/test-report.zip")
        assertTrue("the file is there: ${filed.absolutePath}", filed.isFile)
        assertEquals(
            "and what was filed is the plaintext, not the wrapper",
            inner,
            filed.readText(),
        )
    }

    @Test
    fun `an artifact that was never sealed is filed as it arrived`() {
        // **Every artifact this app made before the seal existed**, and every artifact from a
        // workflow that does not seal. Passing those through unchanged is what keeps them readable.
        aRun(100, "failure", "Test (0)" to "ok")
        anArtifact(100, "app-release", "ordinary zip bytes".toByteArray())

        val body = call("artifact", """{"run":100,"name":"app-release"}""")

        assertTrue("it worked: $body", body.startsWith(AgentLog.SUCCESS))
        assertEquals(
            "the bytes are exactly what arrived",
            "ordinary zip bytes",
            File(ws.projectDir(repo), "agent/ci/artifacts/app-release.zip").readText(),
        )
    }

    @Test
    fun `a sealed artifact with no key is refused rather than filed as ciphertext`() {
        // **The app's own gap must not read as a corrupt download.** Filing the wrapper and calling
        // it success would hand the caller a file that opens as nothing.
        val sealedBytes = (EncryptedLog.MARKER + sealed("anything")).toByteArray(Charsets.US_ASCII)
        aRun(100, "failure", "Test (0)" to "ok")
        anArtifact(100, "test-report", sealedArtifact("test-report.zip", sealedBytes))

        val body = call("artifact", """{"run":100,"name":"test-report"}""")

        assertTrue("it is a refusal: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("and it names the missing key: $body", body.contains("no key"))
        assertFalse(
            "and nothing was filed",
            File(ws.projectDir(repo), "agent/ci/artifacts/test-report.zip").exists(),
        )
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
    fun `a dispatch is refused for a workflow that is not there, and names the ones that are`() {
        // **The guard moved, so the test moves with it.** `ci` used to have no `event` key at all; it
        // does now, because the operator ruled that an unapproved workflow still runs. What remains
        // worth asserting is the refusal that is still a refusal: a workflow the project does not have
        // cannot be started, and the answer says which ones it does have.
        val body = call("ci", """{"event":"workflow_dispatch","workflow":"nope.yml"}""")

        assertTrue("it is a refusal: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("it names the path it looked for: $body", body.contains("nope.yml"))
    }

    @Test
    fun `the only startable event is workflow_dispatch`() {
        // A `push` happens when something is pushed and a `schedule` happens on its own. Accepting
        // either as an `event` would look like it started something and start nothing.
        val body = call("ci", """{"event":"push","workflow":"build.yml"}""")

        assertTrue("it is a refusal: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("and it says which event it takes: $body", body.contains("workflow_dispatch"))
    }

    // ---- dispatch: the environment check ----------------------------------

    // ---- dispatch: there is no approval gate, deliberately ------------------
    //
    // These four tests used to assert a refusal. The gate is gone (see `start`'s comment), and they
    // now assert the opposite — which is the point of rewriting rather than deleting them: the
    // *behaviour* is still pinned, so a future change that reintroduces a gate fails here.

    @Test
    fun `a workflow that names an environment starts without a lock`() {
        // **The gate that used to refuse this is gone.** Its premise was that a person audits the
        // YAML, and the YAML is not the danger: `run: ./script.sh` is a script the agent wrote and
        // can rewrite. What constrains a runner is required reviewers on the environment, which
        // GitHub enforces whatever started the run.
        workflow("deploy.yml", "jobs:\n  ship:\n    environment: release\n")

        val body = call(
            "ci",
            """{"event":"workflow_dispatch","workflow":"deploy.yml"}""",
        )

        assertTrue("it started: $body", body.startsWith(AgentLog.SUCCESS))
    }

    @Test
    fun `the trace still records whether the workflow was sealed`() {
        // **Removing the gate is not removing the record.** A person reading a trace should be able
        // to see whether the workflow in front of them was one they sealed, and that is the fact the
        // gate used to decide on. Keeping it in the trace costs nothing and outlives the gate.
        val path = workflow("deploy.yml", "jobs:\n  ship:\n    environment: release\n")
        lock(path)

        val body = call(
            "ci",
            """{"event":"workflow_dispatch","workflow":"deploy.yml"}""",
        )

        assertTrue("it started: $body", body.startsWith(AgentLog.SUCCESS))
        val note = traceNow()
        assertTrue("and the trace says it was sealed: $note", note.contains("locked"))
    }

    @Test
    fun `a workflow that names no environment starts too`() {
        workflow("build.yml", "jobs:\n  a:\n    runs-on: ubuntu-24.04\n")

        val body = call(
            "ci",
            """{"event":"workflow_dispatch","workflow":"build.yml"}""",
        )

        assertTrue("it started: $body", body.startsWith(AgentLog.SUCCESS))
    }

    @Test
    fun `a workflow whose environment name is an expression starts, and the trace says so`() {
        // **The scan cannot resolve an expression**, so the trace records that rather than claiming
        // the file reaches no environment. That distinction mattered when it decided a refusal; it
        // still matters, because it is what a person reads when a run reaches a secret they did not
        // expect.
        val dollar = "$"
        workflow(
            "dynamic.yml",
            "jobs:\n  ship:\n    environment:\n      name: " + dollar +
                "{{ github.ref_name }}\n",
        )

        val body = call(
            "ci",
            """{"event":"workflow_dispatch","workflow":"dynamic.yml"}""",
        )

        assertTrue("it started: $body", body.startsWith(AgentLog.SUCCESS))
        val note = traceNow()
        assertTrue("and the trace says the name could not be read: $note", note.contains("expression"))
    }

    @Test
    fun `naming the one branch is allowed, and so is saying nothing`() {
        // **The refusal must not take away a legitimate call.** A model that spells out `main` has
        // asked for exactly what it would have got. This test used to prove the opposite for
        // `main` — that naming it was refused — because there were two branches and `main`
        // published. There is one branch now, and the operator wants the agent able to release.
        workflow("build.yml", "jobs:\n  a:\n    runs-on: ubuntu-24.04\n")

        val explicit = call(
            "ci",
            """{"event":"workflow_dispatch","workflow":"build.yml","branch":"main"}""",
        )
        val silent = call("ci", """{"event":"workflow_dispatch","workflow":"build.yml"}""")

        assertTrue("explicit is fine: $explicit", explicit.startsWith(AgentLog.SUCCESS))
        assertTrue("and so is silent: $silent", silent.startsWith(AgentLog.SUCCESS))
    }

    @Test
    fun `naming a branch this project does not have is refused, and says which one it has`() {
        // **A typo check, not a permission question.** With one branch there is nothing to protect,
        // but a model that asked for `development` believed something about this project that is not
        // true — and silently substituting `main` would let it go on believing it.
        workflow("build.yml", "jobs:\n  a:\n    runs-on: ubuntu-24.04\n")

        val body = call(
            "ci",
            """{"event":"workflow_dispatch","workflow":"build.yml","branch":"development"}""",
        )

        assertTrue("it is refused: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("it names the branch this project has: $body", body.contains("`main`"))
    }

    @Test
    fun `dispatch inputs are passed through as strings`() {
        // A workflow with a required input is otherwise dispatched without it, and GitHub rejects the
        // call with a message that does not name the missing input.
        workflow("build.yml", "jobs:\n  a:\n    runs-on: ubuntu-24.04\n")

        val body = call(
            "ci",
            """{"event":"workflow_dispatch","workflow":"build.yml","inputs":{"version_name":"1.5"}}""",
        )

        assertTrue("it started: $body", body.startsWith(AgentLog.SUCCESS))
    }

    // ---- artifacts --------------------------------------------------------

    /** A run with one artifact in it, as the fake stores them. */
    private fun runWithArtifact(name: String, bytes: ByteArray): Long {
        val id = 41177826224L
        val dir = File(github.repoDir(repo), "ci/$id")
        File(dir, "artifacts").mkdirs()
        File(dir, "run.json").writeText(
            JSONObject().apply {
                put("name", "build-and-release.yml")
                put("status", "completed")
                put("conclusion", "success")
                put("branch", "development")
                put("event", "workflow_dispatch")
                put("sha", "")
                put("created_at", "now")
                put("url", "")
            }.toString(),
        )
        File(File(dir, "artifacts"), name).writeBytes(bytes)
        return id
    }

    @Test
    fun `an artifact is fetched into the project and named, not returned as bytes`() {
        // **A zip handed to a model as a string is binary no model can read**, spending the whole
        // context to say nothing. The answer is a path, a size and the names inside it.
        val id = runWithArtifact("app-release", "not really a zip".toByteArray())

        val body = call("artifact", """{"run":$id,"name":"app-release"}""")

        // **The body is put on one line in the message**, because a multi-line assertion message is
        // cut at its first newline by the CI log and showed `0` for a body that had far more in it.
        val said = body.replace("\n", " ⏎ ")
        assertTrue("it says what it fetched: $said", body.contains("app-release"))
        assertTrue("and where it went: $said", body.contains("agent/ci/artifacts/app-release.zip"))
        val file = File(ws.projectDir(repo), "agent/ci/artifacts/app-release.zip")
        assertTrue("the bytes are on disk at $file", file.isFile)
        assertEquals("not really a zip", file.readText())
    }

    @Test
    fun `naming no artifact lists them rather than guessing`() {
        // An artifact's name belongs to the workflow, and a model that assumed one would download the
        // wrong bytes and describe them confidently.
        val id = runWithArtifact("scenarios", "x".toByteArray())

        val body = call("artifact", """{"run":$id}""")

        assertTrue("it lists the real name: $body", body.contains("scenarios"))
        assertTrue("and says how to fetch one: $body", body.contains("\"name\""))
        assertFalse(
            "and nothing was downloaded",
            File(ws.projectDir(repo), "agent/ci/artifacts").exists(),
        )
    }

    @Test
    fun `a name that is not there is refused with the names that are`() {
        val id = runWithArtifact("scenarios", "x".toByteArray())

        val body = call("artifact", """{"run":$id,"name":"apk"}""")

        assertTrue("refused: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("and it says what does exist: $body", body.contains("scenarios"))
    }

    @Test
    fun `a run with no artifacts says so rather than failing`() {
        // A workflow that uploads nothing is ordinary, and "there is nothing here" is an answer.
        val id = runWithArtifact("unused", "x".toByteArray())
        // **The folder itself goes, not just the file in it**, because that is the difference between
        // "the run made no artifacts" and "the run does not exist" — and only the first is the case
        // this test is about. `deleteRecursively()` is a tree walk, which is fine for one empty folder.
        File(github.repoDir(repo), "ci/$id/artifacts").deleteRecursively()

        val body = call("artifact", """{"run":$id}""")

        assertTrue("it is not a failure: $body", body.startsWith(AgentLog.SUCCESS))
        assertTrue("and it explains: $body", body.contains("no artifacts"))
    }

    @Test
    fun `asking for an artifact with no run is refused before anything is asked`() {
        val body = call("artifact", """{"name":"app-release"}""")
        assertTrue("refused: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("and it says which key is missing: $body", body.contains("run"))
    }

    @Test
    fun `the artifact tool takes no keys the other CI tools take`() {
        // **The unknown-key-as-refusal property is what keeps a tool honest.** `artifact` reads a run
        // and downloads one thing; a `branch` or an `event` would be a key it silently ignored.
        val body = call("artifact", """{"run":1,"branch":"main"}""")
        assertTrue("refused: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("and it names the key: $body", body.contains("branch"))
    }

    // ---- downloads --------------------------------------------------------

    @Test
    fun `a file is handed to the exporter, through the same function the menu uses`() {
        // **The one exit from the project.** A file inside an app's private storage is a file nobody
        // can open, so an APK the agent fetched or a report it wrote is only useful once it can leave.
        ws.write(File(ws.projectDir(repo), "release/app-release.apk"), "not really an apk")

        val body = call("upload", """{"path":"release/app-release.apk"}""", withDownloads = true)

        assertTrue("it says where it went: $body", body.contains("Downloads"))
        assertEquals("one file was handed over", 1, exported.size)
        assertEquals("and it is the one asked for", "app-release.apk", exported[0].name)
    }

    @Test
    fun `a folder is refused, because a flat Downloads cannot hold a tree honestly`() {
        ws.write(File(ws.projectDir(repo), "release/one.txt"), "x")

        val body = call("upload", """{"path":"release"}""", withDownloads = true)

        assertTrue("refused: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("and it says why: $body", body.contains("folder"))
        assertTrue("and nothing was handed over", exported.isEmpty())
    }

    @Test
    fun `a path that escapes the account is refused`() {
        // **The same rule `read` and `write` use**, so an exit that reaches any file on the phone
        // cannot exist by a different route.
        //
        // **The account, not the project**, which is what `withinProject` has always compared —
        // and what this assertion used to mis-state. See `upload`'s refusal for the same
        // correction, found by the same false assertion on the other side of the file.
        val body = call("upload", """{"path":"../../etc/passwd"}""", withDownloads = true)

        assertTrue("refused: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("and it says where the limit is: $body", body.contains("outside this account"))
        assertTrue("and nothing was handed over", exported.isEmpty())
    }

    @Test
    fun `a file that is not there is refused rather than exported as nothing`() {
        val body = call("upload", """{"path":"release/nothing.apk"}""", withDownloads = true)

        assertTrue("refused: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("and it says so: $body", body.contains("no file"))
    }

    @Test
    fun `naming nothing is refused with the shape of a real call`() {
        val body = call("upload", """{}""", withDownloads = true)
        assertTrue("refused: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("and it shows what to say: $body", body.contains("path"))
    }

    @Test
    fun `an app with no way to write Downloads says so rather than failing at the last moment`() {
        // **A model that learns the app cannot do this is better than one that learns nothing.** The
        // file is still in the project, and the answer names where.
        ws.write(File(ws.projectDir(repo), "report.md"), "# hello")

        val body = call("upload", """{"path":"report.md"}""")

        assertTrue("refused: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("and it says the file is still there: $body", body.contains("report.md"))
    }
    // ---- op: setup, the door CiSetup did not have --------------------------

    @Test
    fun `op setup makes the environment, writes the key, and never shows it`() {
        // **The door that was missing.** `CiSetup` was written and tested and called by nothing, so
        // the one thing that prevents "green build, unreadable artifacts" could not be done from
        // inside the app at all. This is the wiring, and the assertion that matters is the last one:
        // the key is a secret, so it must not appear in the answer.
        val body = call("ci", """{"op":"setup"}""")

        assertTrue("it worked: $body", body.startsWith("0"))
        assertTrue("and says what it did: $body", body.contains("environment"))
        assertTrue(
            "and the key is NOT in the answer: $body",
            credentials.secret(EncryptedLog.KEY_NAME, "")?.let { it !in body } ?: true,
        )
    }

    @Test
    fun `op setup stores the key where a log can be read with it`() {
        // **The read-back, not the write.** `setSecret` returns Unit, so "it was saved" is a claim
        // only a read can support — and a setup that wrote to the repository but not to this phone
        // leaves every future log unreadable, which is the exact failure the function exists to
        // prevent.
        call("ci", """{"op":"setup"}""")

        assertNotNull(
            "the key is on this phone",
            credentials.secret(EncryptedLog.KEY_NAME, ""),
        )
    }

}
