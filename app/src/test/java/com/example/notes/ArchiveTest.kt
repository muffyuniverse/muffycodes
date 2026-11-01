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
 * `pack` and `unpack` — one tool with two modes, and the safety that makes it usable.
 *
 * **This tool exists because the app lied.** `downloads`' description told the agent to *"zip it
 * first"* and there was no zip code in the app that could write one — only `ZipFile` in `artifact`,
 * which reads. A model told to do the impossible spends a call discovering it and then improvises.
 *
 * Two things are worth testing here and they are the two that break quietly:
 *
 * 1. **Zip-slip is refused before anything is written.** An unpacker that writes as it reads has
 *    already escaped by the time it notices, so the whole entry list is checked first and a bad
 *    entry leaves the destination untouched.
 * 2. **A round trip reproduces the tree**, because an entry name is the project-relative path. A zip
 *    of `src/` that kept only the folder name would unpack to nothing.
 */
class ArchiveTest {

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

    private fun tools() = AgentTools(ws, "shopping", git, log)

    private fun call(args: String) = Call(id = "1", tool = "archive", args = args)

    /** Writes a file in the project, making the folders on the way. */
    private fun write(path: String, text: String = "hello"): File {
        val file = File(ws.projectDir("shopping"), path)
        file.parentFile?.mkdirs()
        file.writeText(text)
        return file
    }

    private fun project() = ws.projectDir("shopping")

    /** A zip with exactly the entries given, written by hand so a bad name is possible. */
    private fun zipWith(path: String, entries: Map<String, String>): File {
        val file = File(project(), path)
        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    // ---- pack ---------------------------------------------------------------

    @Test
    fun `pack puts files and folders into one zip, keeping their paths`() {
        // **The entry name is the project-relative path**, which is what makes a round trip through
        // `unpack` reproduce the tree. A zip of `src/` that kept only the folder's name would
        // unpack to nothing at all.
        write("src/Main.kt", "fun main() {}")
        write("src/util/Text.kt", "fun text() {}")
        write("README.md", "# readme")

        val answer = tools().run(call("""{"pack":["src/","README.md"],"as":"agent/out.zip"}"""))

        assertTrue("it is an answer: ${answer.replace("\n", " | ")}", answer.startsWith("0\n"))
        assertTrue("the zip is there", File(project(), "agent/out.zip").isFile)
        val names = java.util.zip.ZipFile(File(project(), "agent/out.zip")).use { zip ->
            zip.entries().asSequence().map { it.name }.toList()
        }
        assertEquals(
            "every file, at its own path",
            listOf("src/Main.kt", "src/util/Text.kt", "README.md"),
            names,
        )
    }

    @Test
    fun `pack refuses a source that is not there, rather than making a zip without it`() {
        // **A zip that quietly lacks the file the agent meant to send** is one the person finds
        // empty at the moment they needed it — and the agent has already been told it worked.
        write("README.md", "# readme")

        val answer = tools().run(call("""{"pack":["README.md","missing.txt"],"as":"agent/out.zip"}"""))

        assertTrue("it is a refusal: $answer", answer.startsWith("1\n"))
        assertTrue("and names what is missing: $answer", answer.contains("missing.txt"))
        assertFalse("and nothing was written", File(project(), "agent/out.zip").exists())
    }

    @Test
    fun `pack refuses two sources that would write the same entry`() {
        // **A zip whose contents depend on the order the sources were listed** is a file nobody can
        // reason about, and the second copy silently wins.
        write("src/Main.kt", "one")
        write("src/Main.kt", "two")

        val answer = tools().run(call("""{"pack":["src/","src/Main.kt"],"as":"agent/out.zip"}"""))

        assertTrue("it is a refusal: $answer", answer.startsWith("1\n"))
        assertTrue("and names the entry: $answer", answer.contains("src/Main.kt"))
    }

    @Test
    fun `pack keeps the app's own folders out of the zip`() {
        // **A person is going to open this file.** Packing `agent/session.log` into a downloadable
        // archive would be the app handing out its own record, and `ws.list` is what already decides
        // that the record is not part of the project a person browses.
        write("README.md", "# readme")
        File(project(), "agent/session.log").writeText("   1     4 TOLD    in  operator  task\n")

        val answer = tools().run(call("""{"pack":["."],"as":"agent/out.zip"}"""))

        assertTrue("it worked: ${answer.replace("\n", " | ")}", answer.startsWith("0\n"))
        val names = java.util.zip.ZipFile(File(project(), "agent/out.zip")).use { zip ->
            zip.entries().asSequence().map { it.name }.toList()
        }
        assertTrue("the readme is in: $names", names.contains("README.md"))
        assertTrue("and the record is not: $names", names.none { it.startsWith("agent/") })
    }

    // ---- unpack -------------------------------------------------------------

    @Test
    fun `unpack writes a tree into the folder it was told to`() {
        zipWith("agent/lib.zip", mapOf("lib/a.kt" to "a", "lib/b/c.kt" to "c"))

        val answer = tools().run(call("""{"unpack":"agent/lib.zip","into":"vendor/lib"}"""))

        assertTrue("it is an answer: ${answer.replace("\n", " | ")}", answer.startsWith("0\n"))
        assertEquals("a", File(project(), "vendor/lib/lib/a.kt").readText())
        assertEquals("c", File(project(), "vendor/lib/lib/b/c.kt").readText())
    }

    @Test
    fun `unpack answers with the entry names and never the bytes`() {
        // **A zip in the conversation is binary that costs the whole context to say nothing** — the
        // same reasoning `artifact` already follows. The names are what a model asks next about.
        zipWith("agent/lib.zip", mapOf("lib/a.kt" to "fun a() {}", "lib/b.kt" to "fun b() {}"))

        val answer = tools().run(call("""{"unpack":"agent/lib.zip","into":"vendor/lib"}"""))

        assertTrue("the names are there: $answer", answer.contains("lib/a.kt"))
        assertTrue("and the second: $answer", answer.contains("lib/b.kt"))
        assertFalse("and the contents are not: $answer", answer.contains("fun a()"))
    }

    @Test
    fun `a zip that climbs out with dot-dot is refused by name, and nothing is written`() {
        // **Zip-slip, and the reason the whole entry list is checked first.** An unpacker that
        // writes as it reads has already escaped by the time it notices; this one refuses the call
        // and leaves the destination exactly as it was.
        zipWith(
            "agent/evil.zip",
            mapOf("ok.txt" to "fine", "../../outside.txt" to "escaped"),
        )

        val answer = tools().run(call("""{"unpack":"agent/evil.zip","into":"vendor/lib"}"""))

        assertTrue("it is a refusal: $answer", answer.startsWith("1\n"))
        assertTrue("and names the entry: $answer", answer.contains("../../outside.txt"))
        assertTrue("and says why: $answer", answer.contains("climbs out"))
        assertFalse("the safe entry was NOT written either", File(project(), "vendor/lib/ok.txt").exists())
        assertFalse("and nothing escaped", File(project(), "outside.txt").exists())
    }

    @Test
    fun `a zip with an absolute entry is refused`() {
        zipWith("agent/abs.zip", mapOf("/etc/passwd" to "root"))

        val answer = tools().run(call("""{"unpack":"agent/abs.zip","into":"vendor/lib"}"""))

        assertTrue("it is a refusal: $answer", answer.startsWith("1\n"))
        assertTrue("and names the entry: $answer", answer.contains("/etc/passwd"))
        assertTrue("and says why: $answer", answer.contains("absolute"))
    }

    @Test
    fun `a zip made on windows is refused for the same reason`() {
        // **Both separators**, because a zip made on Windows carries backslashes and `..\..` is the
        // same climb as `../../`. A check that only knew `/` would let this one through.
        zipWith("agent/win.zip", mapOf("..\\..\\outside.txt" to "escaped"))

        val answer = tools().run(call("""{"unpack":"agent/win.zip","into":"vendor/lib"}"""))

        assertTrue("it is a refusal: $answer", answer.startsWith("1\n"))
        assertTrue("and says why: $answer", answer.contains("climbs out"))
    }

    @Test
    fun `an unpack cannot write outside the project`() {
        // **The boundary every other write already honours**, not a second rule invented here: an
        // unpack into another project is refused by the same `resolve` that refuses an `edit` there.
        zipWith("agent/lib.zip", mapOf("a.txt" to "a"))
        ws.projectDir("work").mkdirs()

        val answer = tools().run(call("""{"unpack":"agent/lib.zip","into":"../work/vendor"}"""))

        assertTrue("it is a refusal: $answer", answer.startsWith("1\n"))
        assertFalse("and nothing landed there", File(ws.projectDir("work"), "vendor").exists())
    }

    // ---- the round trip -----------------------------------------------------

    @Test
    fun `pack then unpack gives back the tree that was packed`() {
        // **The property that makes the pair useful**, and the one a person means by "zip it and
        // send it". Entry names are project-relative paths, so the tree survives.
        write("src/Main.kt", "fun main() {}")
        write("src/util/Text.kt", "fun text() {}")
        write("README.md", "# readme")

        tools().run(call("""{"pack":["src/","README.md"],"as":"agent/out.zip"}"""))
        tools().run(call("""{"unpack":"agent/out.zip","into":"copy"}"""))

        assertEquals("fun main() {}", File(project(), "copy/src/Main.kt").readText())
        assertEquals("fun text() {}", File(project(), "copy/src/util/Text.kt").readText())
        assertEquals("# readme", File(project(), "copy/README.md").readText())
    }

    // ---- the two modes, and what a call with neither gets --------------------

    @Test
    fun `a call with neither mode is refused with both shapes`() {
        // The model has to be able to see what it should have written, which is why both examples
        // are in the refusal rather than only the one it did not use.
        val answer = tools().run(call("""{"as":"agent/out.zip"}"""))

        assertTrue("it is a refusal: $answer", answer.startsWith("1\n"))
        assertTrue("and offers pack: $answer", answer.contains("pack"))
        assertTrue("and unpack: $answer", answer.contains("unpack"))
    }

    @Test
    fun `asking for both modes at once is refused`() {
        val answer = tools().run(call("""{"pack":["README.md"],"as":"x.zip","unpack":"x.zip"}"""))

        assertTrue("it is a refusal: $answer", answer.startsWith("1\n"))
        assertTrue("and says to do one: $answer", answer.contains("one thing at a time"))
    }

    @Test
    fun `the downloads description no longer tells the agent to do the impossible`() {
        // **The bug this step exists to fix.** The sentence used to say *"there is no command that
        // makes one yet: say so rather than trying"* — true when written, and a lie the moment the
        // app could make one. It now names the tool that does.
        val spec = AgentTools.specs().first { it.name == "downloads" }
        val said = spec.description

        assertFalse(
            "it does not claim there is no way to zip: $said",
            said.contains("no command that makes one"),
        )
        assertTrue("and names the tool that does: $said", said.contains("pack"))
    }
}
