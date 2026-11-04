package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The three Downloads acts: `downloads` lists, `download` copies out, `upload` copies in.
 *
 * **Report 37**: *"what is downloads command? shouldn't it be 2 commands: download (from downloads),
 * upload (to downloads)"* — and the listing is the third, because an agent cannot name a file it has
 * not been shown.
 *
 * ## What is tested here and what cannot be
 *
 * These are the **rules**: what a name may contain, where a file lands, what is refused and with
 * which sentence. They run against a fake, so they run everywhere.
 *
 * What is not tested, and cannot be without a device, is [MediaStoreDownloads] itself — the query,
 * the insert, the id. That class is deliberately thin for that reason: it moves bytes and knows
 * nothing about limits, names or destinations. The honest limitation is written into the tool's own
 * description instead of being trusted to someone's memory.
 */
class DownloadsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var ws: Workspace
    private lateinit var git: ProjectGit
    private lateinit var log: AgentLog
    private lateinit var phone: FakeDownloads

    @Before
    fun setUp() {
        ws = Workspace(temp.root)
        ws.projectDir("shopping").mkdirs()
        git = ProjectGit(ws, { null }, { GitAuthor("Muffy", "muffy@example.com") })
        log = AgentLog.open(ws.projectDir("shopping"))
        phone = FakeDownloads()
    }

    private fun tools(phone: PhoneDownloads = this.phone) = AgentTools(
        ws, "shopping", git, log, phone = phone,
    )

    private fun call(tool: String, args: String, phone: PhoneDownloads = this.phone) =
        tools(phone).run(Call("c1", tool, args))

    private fun project() = ws.projectDir("shopping")

    private fun write(path: String, text: String = "hello"): File {
        val file = File(project(), path)
        file.parentFile?.mkdirs()
        file.writeText(text)
        return file
    }

    /**
     * Downloads, as a folder in memory.
     *
     * **`writes` is recorded rather than only answered**, because the interesting question about
     * `upload` is not what it says but which file it handed over — the bug class this replaced was
     * a description promising a zip the app could not make.
     */
    private class FakeDownloads : PhoneDownloads {
        val items = mutableListOf<PhoneDownloads.Item>()
        val writes = mutableListOf<File>()
        var readable = mapOf<String, String>()
        var failed: String? = null

        override fun list(): Result<List<PhoneDownloads.Item>> {
            failed?.let { return Result.failure(IllegalStateException(it)) }
            return Result.success(items.sortedByDescending { it.at })
        }

        override fun read(name: String, into: File): Result<Long> {
            failed?.let { return Result.failure(IllegalStateException(it)) }
            val text = readable[name]
                ?: return Result.failure(IllegalStateException("There is no file called \"$name\" in Downloads."))
            into.parentFile?.mkdirs()
            into.writeText(text)
            return Result.success(text.length.toLong())
        }

        override fun write(file: File): Result<Export.Done> {
            failed?.let { return Result.failure(IllegalStateException(it)) }
            writes += file
            return Result.success(Export.Done(file.name, file.length(), Export.FOLDER))
        }

        fun holds(name: String, bytes: Long = 10, at: Long = 1_700_000_000_000) = apply {
            items += PhoneDownloads.Item(name, bytes, at)
        }
    }

    // ---- downloads: the listing ---------------------------------------------

    @Test
    fun `downloads lists names, sizes and dates, newest first`() {
        phone.holds("old.txt", bytes = 12, at = 1_600_000_000_000)
        phone.holds("new.log", bytes = 2_048, at = 1_700_000_000_000)

        val answer = call("downloads", "{}")

        assertTrue("it is an answer: $answer", answer.startsWith("0\n"))
        assertTrue("the name is there: $answer", answer.contains("new.log"))
        assertTrue("and the size: $answer", answer.contains("2.0 KB"))
        assertTrue("and the other file: $answer", answer.contains("old.txt"))
        // **Newest first**, because a download an agent was just told about is the one it wants.
        assertTrue("the newer file is listed first: $answer", answer.indexOf("new.log") < answer.indexOf("old.txt"))
    }

    @Test
    fun `an empty listing does not claim the folder is empty`() {
        // **The `MediaStore` fact, said where an agent will read it.** A file written to `Download/`
        // by raw path or by a shell may have no row in the store, so "nothing here" and "nothing the
        // store knows about" are different answers and only the second is true.
        val answer = call("downloads", "{}")

        assertTrue("it is an answer: $answer", answer.startsWith("0\n"))
        assertTrue("it says what it means: $answer", answer.contains("nothing in it"))
        assertTrue("and that empty is not proof: $answer", answer.contains("not proof"))
    }

    @Test
    fun `a long listing says how many it did not show`() {
        for (n in 1..45) phone.holds("file$n.txt", at = 1_700_000_000_000L + n)

        val answer = call("downloads", "{}")

        assertTrue("it says it stopped: $answer", answer.contains("the newest 40 of 45"))
        assertEquals(
            "and forty rows follow",
            41,
            answer.substringAfter('\n').lines().count { it.isNotBlank() },
        )
    }

    @Test
    fun `a listing that cannot be read is a refusal, not an empty answer`() {
        // **"Nothing matches" and "I could not look" are different**, and a model that reads the
        // first when the second happened concludes the file does not exist.
        phone.failed = "Downloads could not be read: no permission"

        val answer = call("downloads", "{}")

        assertTrue("it is a refusal: $answer", answer.startsWith("1\n"))
        assertTrue("and quotes the reason: $answer", answer.contains("no permission"))
    }

    // ---- download: copying out ----------------------------------------------

    @Test
    fun `download copies a file into the agent's own folder, never over a project file`() {
        // **Two visible steps, the same rule `fetch` follows**: a download is something that arrived
        // from outside, and a second copy in `agent/` cannot look like an edit to a project file.
        phone.holds("notes.txt")
        phone.readable = mapOf("notes.txt" to "from the phone")
        write("notes.txt", "the project's own")

        val answer = call("download", """{"name":"notes.txt"}""")

        assertTrue("it is an answer: $answer", answer.startsWith("0\n"))
        assertEquals("the copy is in agent/downloaded", "from the phone",
            File(project(), "agent/downloaded/notes.txt").readText())
        assertEquals("and the project's file is untouched", "the project's own",
            File(project(), "notes.txt").readText())
    }

    @Test
    fun `download refuses a name that is not there, and says how to look`() {
        val answer = call("download", """{"name":"ghost.txt"}""")

        assertTrue("it is a refusal: $answer", answer.startsWith("1\n"))
        assertTrue("and names what is missing: $answer", answer.contains("ghost.txt"))
        assertTrue("and points at the listing: $answer", answer.contains("`downloads`"))
    }

    @Test
    fun `download refuses a path, because Downloads is one folder`() {
        // **A `/` is either a mistake or an attempt to reach somewhere else**, and both are refused
        // rather than resolved — the same reason `upload` refuses a folder.
        val answer = call("download", """{"name":"../secrets/key.txt"}""")

        assertTrue("it is a refusal: $answer", answer.startsWith("1\n"))
        assertTrue("and says a name is a name: $answer", answer.contains("is a path"))
    }

    @Test
    fun `download refuses a file past the cap, naming both numbers`() {
        // **Refused, never truncated.** A video is a thing a phone's Downloads holds, and copying one
        // into a project spends the person's storage to read nothing.
        phone.holds("video.mp4", bytes = Fetched.LIMIT_BYTES + 1)

        val answer = call("download", """{"name":"video.mp4"}""")

        assertTrue("it is a refusal: $answer", answer.startsWith("1\n"))
        assertTrue("and names the size: $answer", answer.contains("100.0 MB"))
        assertTrue("and the cap: $answer", answer.contains(Fetcher.size(Fetched.LIMIT_BYTES)))
        assertFalse("and nothing landed",
            File(project(), "agent/downloaded/video.mp4").exists())
    }

    @Test
    fun `download with no way to reach Downloads says so instead of failing quietly`() {
        val answer = call("download", """{"name":"x.txt"}""", phone = PhoneDownloads.NONE)

        assertTrue("it is a refusal: $answer", answer.startsWith("1\n"))
        assertTrue("and says why: $answer", answer.contains("not given a way"))
    }

    // ---- upload: copying in ------------------------------------------------

    @Test
    fun `upload puts one project file into Downloads`() {
        val file = write("release/app.apk", "bytes")

        val answer = call("upload", """{"path":"release/app.apk"}""")

        assertTrue("it is an answer: $answer", answer.startsWith("0\n"))
        assertEquals("the file it handed over", listOf(file), phone.writes)
    }

    @Test
    fun `upload refuses a folder with the sentence that says what to do instead`() {
        write("release/app.apk", "bytes")

        val answer = call("upload", """{"path":"release/"}""")

        assertTrue("it is a refusal: $answer", answer.startsWith("1\n"))
        assertTrue("and names the alternative: $answer", answer.contains("archive"))
    }

    @Test
    fun `upload refuses a path outside the project`() {
        // **The bound is the resolved path**, so a `..` that escapes is caught here and so is a link
        // pointing out — the same rule `read` and `write` ask.
        File(ws.accountDir, "secret.txt").writeText("not yours")

        val answer = call("upload", """{"path":"../secret.txt"}""")

        assertTrue("it is a refusal: $answer", answer.startsWith("1\n"))
        assertTrue("and says the project is the bound: $answer", answer.contains("not inside this project"))
        assertTrue("and nothing was handed over", phone.writes.isEmpty())
    }

    // ---- the tool list itself ----------------------------------------------

    @Test
    fun `the three acts are three verbs, and none of them deletes`() {
        // **The split the report asked for**, asserted on the live catalogue: a model reads these
        // names, and one verb meaning both directions was the defect.
        val names = AgentTools.specs().map { it.name }
        assertTrue("listing: $names", names.contains("downloads"))
        assertTrue("copying out: $names", names.contains("download"))
        assertTrue("copying in: $names", names.contains("upload"))

        // **And `downloads` takes nothing**, because a filter here would be a second way to say what
        // the listing already is.
        assertEquals("no arguments", emptySet<String>(), AgentTools.KEYS["downloads"])
        assertEquals("a name", setOf("name"), AgentTools.KEYS["download"])
        assertEquals("a path", setOf("path"), AgentTools.KEYS["upload"])
    }

    @Test
    fun `the listing's own text says a file can be absent without being missing`() {
        // **The Android fact in the description, not in a comment** — an agent reads the description
        // and nothing else, and the failure this prevents is concluding a file does not exist.
        val said = AgentTools.specs().first { it.name == "downloads" }.description

        assertTrue("it names the store: $said", said.contains("store"))
        assertTrue("and says a file may be absent from it: $said", said.contains("not"))
    }
}
