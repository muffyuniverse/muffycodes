package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * `fetch` — the one way bytes reach the phone from a URL.
 *
 * **The gap this fills was structural.** `browse` gives a page's text, `shot` a PNG, `artifact` one
 * file from a CI run — and none of them takes a URL. So "vendor this library" or "get that release
 * asset" had no answer at all, which is a hole in a coding agent rather than a missing convenience.
 *
 * Three things are worth testing, and they are the three that break quietly:
 *
 * 1. **A private address is refused before anything is opened.** The request is the harm; a check
 *    after the connection has already reached the operator's router.
 * 2. **A file that is too big is refused, never truncated.** Half an archive fails later, with the
 *    cause three steps back.
 * 3. **It always lands in `agent/fetched/`**, which is not in git — so a download never commits
 *    itself, and vendoring is two visible steps.
 *
 * **No socket is opened.** [Fetcher] is an interface for exactly this reason, the same rule `Browser`
 * follows: a tool that could only be checked by downloading something is one checked by hand.
 */
class FetchTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var ws: Workspace
    private lateinit var git: ProjectGit
    private lateinit var log: AgentLog

    @Before
    fun setUp() {
        ws = Workspace(temp.root)
        ws.projectDir("shopping").mkdirs()
        git = ProjectGit(ws, { null }, { GitAuthor("Muffy", "muffy@example.com") })
        log = AgentLog.open(ws.projectDir("shopping"))
    }

    /** What the fake was asked, so a test can assert the call as well as the answer. */
    private var asked: String? = null
    private var askedLimit: Long? = null

    /** A fetcher that answers [bytes], and records what it was asked. */
    private fun fake(bytes: ByteArray = "hello".toByteArray()) = object : Fetcher {
        override fun bytes(url: String, limitBytes: Long): Result<ByteArray> {
            asked = url
            askedLimit = limitBytes
            return Result.success(bytes)
        }
    }

    /** A fetcher that always fails, for the refusal paths. */
    private fun failing(said: String) = object : Fetcher {
        override fun bytes(url: String, limitBytes: Long): Result<ByteArray> {
            asked = url
            return Result.failure(IllegalStateException(said))
        }
    }

    private fun tools(
        fetcher: Fetcher = fake(),
        privateAllowed: () -> Boolean = { false },
    ) = AgentTools(
        ws, "shopping", git, log,
        fetcher = fetcher,
        privateAllowed = privateAllowed,
    )

    private fun call(url: String) = Call(id = "1", tool = "fetch", args = """{"url":"$url"}""")

    private fun project() = ws.projectDir("shopping")

    // ---- what it does -------------------------------------------------------

    @Test
    fun `a file lands in agent fetched, which is not in git`() {
        // **The operator's answer to "where does a fetch land" was "everything can land there"**, and
        // `agent/` is already git-excluded — so a download never commits itself. That is what makes
        // vendoring two visible steps rather than one call that commits forty megabytes.
        val answer = tools().run(call("https://example.com/data.txt"))

        assertTrue("it is an answer: ${answer.replace("\n", " | ")}", answer.startsWith("0\n"))
        val file = File(project(), "agent/fetched/data.txt")
        assertTrue("the file is there: $answer", file.isFile)
        assertEquals("with the bytes", "hello", file.readText())
        assertTrue("and the answer says where: $answer", answer.contains("agent/fetched/data.txt"))
        assertTrue("and how big: $answer", answer.contains("5 bytes"))
    }

    @Test
    fun `there is no into parameter, because the destination is not a choice`() {
        // **A key the tool does not take is refused before it runs.** `into` would be the natural
        // thing for a model to try, and answering as if it had been honoured would put a file
        // somewhere the person never chose.
        val answer = tools().run(
            Call(id = "1", tool = "fetch", args = """{"url":"https://example.com/x","into":"vendor/"}"""),
        )

        assertTrue("it is a refusal: $answer", answer.startsWith("1\n"))
        assertTrue("and names the key: $answer", answer.contains("into"))
        assertEquals("and nothing was downloaded", null, asked)
    }

    @Test
    fun `a zip is reported by its entry names, never its bytes`() {
        // **The shape `artifact` already returns**, and the reasoning that made it right: *"an
        // artifact is a zip, and a zip in this conversation is binary that costs the whole context
        // to say nothing."* The names are what a model asks about next.
        val buffer = java.io.ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            for (name in listOf("lib/a.kt", "lib/b.kt")) {
                zip.putNextEntry(ZipEntry(name))
                zip.write("fun x() {}".toByteArray())
                zip.closeEntry()
            }
        }

        val answer = tools(fetcher = fake(buffer.toByteArray()))
            .run(call("https://example.com/lib.zip"))

        assertTrue("the names are there: $answer", answer.contains("lib/a.kt"))
        assertTrue("and the second: $answer", answer.contains("lib/b.kt"))
        assertFalse("and the contents are not: $answer", answer.contains("fun x()"))
        // **The next step is named**, because a fetched archive that stays in `agent/` never reaches
        // the project and the agent has to be told how it does.
        assertTrue("and it says how to unpack: $answer", answer.contains("unpack"))
    }

    @Test
    fun `a file that is not an archive gets no entry list`() {
        val answer = tools().run(call("https://example.com/notes.txt"))

        assertTrue("it worked: $answer", answer.startsWith("0\n"))
        assertFalse("and there is no listing: $answer", answer.contains("It holds:"))
        assertFalse("nor an unpack suggestion: $answer", answer.contains("unpack"))
    }

    // ---- what it refuses ----------------------------------------------------

    @Test
    fun `a private address is refused before anything is opened`() {
        // **The refusal has to come first.** A check after the connection would already have reached
        // the operator's router, and a fetcher is a worse SSRF surface than a browser: a page can
        // lie, a download can write into the project.
        val answer = tools().run(call("http://192.168.1.1/data.zip"))

        assertTrue("it is a refusal: $answer", answer.startsWith("1\n"))
        assertTrue("and names the address: $answer", answer.contains("192.168.1.1"))
        assertEquals("and nothing was downloaded", null, asked)
    }

    @Test
    fun `loopback is refused too, because a name is a claim`() {
        val answer = tools().run(call("http://127.0.0.1:8080/x"))

        assertTrue("it is a refusal: $answer", answer.startsWith("1\n"))
        assertEquals("and nothing was downloaded", null, asked)
    }

    @Test
    fun `the operator's switch lets a private address through`() {
        val answer = tools(privateAllowed = { true }).run(call("http://192.168.1.1/data.zip"))

        assertTrue("it is allowed: $answer", answer.startsWith("0\n"))
        assertEquals("and the fetcher was asked", "http://192.168.1.1/data.zip", asked)
    }

    @Test
    fun `a scheme that is not http is refused`() {
        // `file://` would read the phone, and `ftp://` is not something this app can speak. The
        // address rule is the same one `browse` uses, so the two cannot disagree about what a URL is.
        val answer = tools().run(call("file:///etc/passwd"))

        assertTrue("it is a refusal: $answer", answer.startsWith("1\n"))
        assertEquals("and nothing was downloaded", null, asked)
    }

    @Test
    fun `a size past the cap is refused, and the sentence names both numbers`() {
        // **Refused, never truncated.** Half an archive fails later, with the cause three steps back,
        // and the agent believes it has the file. The refusal has to name the size and the cap so it
        // can tell the person rather than retrying.
        val answer = tools(
            fetcher = failing(
                "That file is 2.0 GB and the limit is ${Fetcher.size(Fetched.LIMIT_BYTES)}. " +
                    "Nothing was downloaded.",
            ),
        ).run(call("https://example.com/big.iso"))

        assertTrue("it is a refusal: $answer", answer.startsWith("1\n"))
        assertTrue("and names the size: $answer", answer.contains("2.0 GB"))
        assertTrue("and the cap: $answer", answer.contains("20.0 MB"))
        assertFalse("and nothing landed", File(project(), "agent/fetched/big.iso").exists())
    }

    @Test
    fun `a server error is quoted rather than summarised`() {
        // **"The download failed" tells a model nothing it can act on**, and the person can act on a
        // 404 — it says the file moved or the link was wrong, which are different next steps.
        val answer = tools(fetcher = failing("The server answered 404: Not Found"))
            .run(call("https://example.com/gone.zip"))

        assertTrue("it is a refusal: $answer", answer.startsWith("1\n"))
        assertTrue("and quotes the status: $answer", answer.contains("404"))
    }

    @Test
    fun `an empty answer is a refusal rather than an empty file`() {
        // **A zero-byte file is a real file**, so writing one and saying it worked would be a claim
        // the app cannot support: the server said nothing, and nothing is what should be on disk.
        val answer = tools(fetcher = fake(ByteArray(0)))
            .run(call("https://example.com/nothing.txt"))

        assertTrue("it is a refusal: $answer", answer.startsWith("1\n"))
        assertFalse("and nothing was written", File(project(), "agent/fetched/nothing.txt").exists())
    }

    @Test
    fun `a tool set built without a fetcher cannot download`() {
        // The default is `Fetcher.NONE`, so a tool set that was given no decision refuses rather
        // than reaching for a network — and the sentence says what is missing.
        val answer = AgentTools(ws, "shopping", git, log).run(call("https://example.com/x"))

        assertTrue("it is a refusal: $answer", answer.startsWith("1\n"))
        assertTrue("and says why: $answer", answer.contains("not given a way to download"))
    }

    // ---- the name it lands under -------------------------------------------

    @Test
    fun `the file keeps the name the server gave it`() {
        // **`Workspace.cleanName` is the wrong rule here**, for the reason `artifact` already found:
        // it lowercases and turns separators into `_`, so `lib-1.2.tar.gz` would arrive as
        // `lib_1_2_tar_gz` and the extension a person needs to recognise the file would be gone.
        assertEquals("lib-1.2.tar.gz", Fetched.nameFor("https://example.com/dl/lib-1.2.tar.gz"))
        assertEquals("app-release.apk", Fetched.nameFor("https://x.test/app-release.apk"))
    }

    @Test
    fun `a URL with no file name lands under its host`() {
        // A file has to be called something, and an empty name is a folder.
        assertEquals("example.com", Fetched.nameFor("https://example.com/"))
        assertEquals("example.com", Fetched.nameFor("https://example.com"))
    }

    @Test
    fun `a name that could leave the folder is made safe`() {
        // **A path segment cannot climb**, because the name is the last one only — but a server can
        // send anything, so the characters are checked rather than trusted.
        val name = Fetched.nameFor("https://example.com/..%2F..%2Fetc%2Fpasswd")
        assertFalse("no separators survive: $name", name.contains("/"))
        assertFalse("and it is not a parent reference: $name", name == ".." || name == ".")
    }

    @Test
    fun `a leading dot is kept, because dotfiles are real files`() {
        // Trimming dots to be safe would turn `.env` into `env`, which is a different file from the
        // one the person asked for.
        assertEquals(".env", Fetched.nameFor("https://example.com/.env"))
    }

    // ---- the limit is a fact about the tool, not the fake -------------------

    @Test
    fun `the fetcher is told the cap, so a real download stops at it`() {
        // The tool passes its own limit down rather than trusting the fetcher's default: a fetcher
        // built for one purpose must not decide how much of the phone a different tool may fill.
        tools().run(call("https://example.com/x"))
        assertEquals("the cap is passed down", Fetched.LIMIT_BYTES, askedLimit)
    }
}
