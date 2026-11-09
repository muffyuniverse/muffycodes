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
        ws = Workspace.single(temp.root)
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

        val answer = tools().run(call("""{"path":"src/"}"""))

        assertTrue("it is an answer: ${answer.replace("\n", " | ")}", answer.startsWith("0\n"))
        // **Beside the input, same name, other suffix** — the whole of report 38.
        assertTrue("the zip is beside the folder: $answer", File(project(), "src.zip").isFile)
        val names = java.util.zip.ZipFile(File(project(), "src.zip")).use { zip ->
            zip.entries().asSequence().map { it.name }.toList()
        }
        // **Named from the packed folder's own parent**, so unpacking it beside `src/` gives back
        // `src/main/Main.kt`. Sorted, because entry order is the walk's and not the call's.
        // **Named from the packed folder, not from the project**, so unpacking this zip gives back
        // a folder with exactly the shape of `src/` rather than `src/src/`.
        assertEquals(
            "every file, relative to the folder that was packed",
            listOf("Main.kt", "util/Text.kt"),
            names.sorted(),
        )
    }

    @Test
    fun `pack names the zip after its input and appends the suffix`() {
        // **Appended, not replaced.** `a.tar` packed to `a.zip` would be a name the caller never
        // said, and one that could collide with a file that already means something else.
        write("notes.tar", "not really a tarball")

        val answer = tools().run(call("""{"path":"notes.tar"}"""))

        assertTrue("accepted: $answer", answer.startsWith("0\n"))
        assertTrue("the suffix is added to the whole name: $answer", File(project(), "notes.tar.zip").isFile)
    }

    @Test
    fun `a second pack of the same thing makes a new name instead of overwriting`() {
        // **The caller never chose this name**, so a free one is a service rather than a surprise —
        // the same reasoning `Workspace.freeName` records for imports. The number goes after the
        // whole name, which is what makes `src 2.zip` (and not `src.zip 2`) the result.
        write("src/Main.kt", "fun main() {}")
        tools().run(call("""{"path":"src/"}"""))
        val second = tools().run(call("""{"path":"src/"}"""))

        assertTrue("accepted: $second", second.startsWith("0\n"))
        assertTrue("the second zip has its own name: $second", File(project(), "src 2.zip").isFile)
        assertTrue("and the first is untouched", File(project(), "src.zip").isFile)
    }

    @Test
    fun `pack refuses a source that is not there, rather than making a zip without it`() {
        // **A zip that quietly lacks the file the agent meant to send** is one the person finds
        // empty at the moment they needed it — and the agent has already been told it worked.
        write("README.md", "# readme")

        val answer = tools().run(call("""{"path":"missing.txt"}"""))

        assertTrue("it is a refusal: $answer", answer.startsWith("1\n"))
        assertTrue("and names what is missing: $answer", answer.contains("missing.txt"))
        assertFalse("and nothing was written", File(project(), "missing.txt.zip").exists())
    }

    @Test
    fun `pack refuses two sources that would write the same entry`() {
        // **A zip whose contents depend on the order the sources were listed** is a file nobody can
        // reason about, and the second copy silently wins.
        write("src/Main.kt", "one")

        // **One path means one source, so a duplicate entry can no longer come from the call.** The
        // rule still guards the walk: a link inside the project can point at a file the walk already
        // reached, and that is the only remaining way two entries can collide.
        val answer = tools().run(call("""{"path":"src/"}"""))

        assertTrue("it worked: $answer", answer.startsWith("0\n"))
        val names = java.util.zip.ZipFile(File(project(), "src.zip")).use { zip ->
            zip.entries().asSequence().map { it.name }.toList()
        }
        assertEquals("one entry, once", 1, names.size)
    }

    @Test
    fun `pack keeps the app's own folders out of the zip`() {
        // **A person is going to open this file.** Packing `agent/session.log` into a downloadable
        // archive would be the app handing out its own record, and `ws.list` is what already decides
        // that the record is not part of the project a person browses.
        write("README.md", "# readme")
        // **`parentFile.mkdirs()`, because `agent/` is the app's folder and nothing here has made
        // it yet.** A test that wrote the path without the folder was writing to a path that did not
        // exist — which threw, and looked like a pack that had done something wrong.
        File(project(), "agent/session.log").apply { parentFile?.mkdirs() }
            .writeText("   1     4 TOLD    in  operator  task\n")

        // **`shopping` is the project, so packing `.` packs the project itself.** The zip lands
        // beside it — in the account rather than in the project — and the record stays out.
        val answer = tools().run(call("""{"path":"."}"""))

        assertTrue("it worked: ${answer.replace("\n", " | ")}", answer.startsWith("0\n"))
        // **The project's parent is the account, which the agent may not write**, so the zip lands
        // inside the project instead. See `outputFolder`.
        val names = java.util.zip.ZipFile(File(project(), "shopping.zip")).use { zip ->
            zip.entries().asSequence().map { it.name }.toList()
        }
        assertTrue("the readme is in: $names", names.contains("README.md"))
        assertTrue("and the record is not: $names", names.none { it.startsWith("agent/") })
        // **Entries are relative to the folder that was packed**, so packing the project itself
        // names files from the project root — and unpacking it gives back one folder of that shape.
        assertTrue("and nothing is prefixed with the project's name: $names",
            names.none { it.startsWith("shopping/") })
    }

    // ---- unpack -------------------------------------------------------------

    @Test
    fun `unpack writes a tree into the folder the zip is named after`() {
        zipWith("agent/lib.zip", mapOf("lib/a.kt" to "a", "lib/b/c.kt" to "c"))

        val answer = tools().run(call("""{"path":"agent/lib.zip","unpack":true}"""))

        assertTrue("it is an answer: ${answer.replace("\n", " | ")}", answer.startsWith("0\n"))
        // **Named after the zip without its suffix, in the project** — `agent/lib.zip` would unpack
        // into `agent/`, which the agent may not write, so `outputFolder` sends it here instead.
        assertEquals("a", File(project(), "lib/lib/a.kt").readText())
        assertEquals("c", File(project(), "lib/lib/b/c.kt").readText())
    }

    @Test
    fun `unpack answers with the entry names and never the bytes`() {
        // **A zip in the conversation is binary that costs the whole context to say nothing** — the
        // same reasoning `artifact` already follows. The names are what a model asks next about.
        zipWith("agent/lib.zip", mapOf("lib/a.kt" to "fun a() {}", "lib/b.kt" to "fun b() {}"))

        val answer = tools().run(call("""{"path":"agent/lib.zip","unpack":true}"""))

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

        val answer = tools().run(call("""{"path":"agent/evil.zip","unpack":true}"""))

        assertTrue("it is a refusal: $answer", answer.startsWith("1\n"))
        assertTrue("and names the entry: $answer", answer.contains("../../outside.txt"))
        assertTrue("and says why: $answer", answer.contains("climbs out"))
        // **Derived destination, so the untouched folder is the one the zip is named after** — and
        // it must not exist at all, which is stronger than "the safe entry is not in it".
        assertFalse("the folder was not even made", File(project(), "evil").exists())
        assertFalse("and nothing escaped", File(project(), "outside.txt").exists())
    }

    @Test
    fun `a zip with an absolute entry is refused`() {
        zipWith("agent/abs.zip", mapOf("/etc/passwd" to "root"))

        val answer = tools().run(call("""{"path":"agent/abs.zip","unpack":true}"""))

        assertTrue("it is a refusal: $answer", answer.startsWith("1\n"))
        assertTrue("and names the entry: $answer", answer.contains("/etc/passwd"))
        assertTrue("and says why: $answer", answer.contains("absolute"))
    }

    @Test
    fun `a zip made on windows is refused for the same reason`() {
        // **Both separators**, because a zip made on Windows carries backslashes and `..\..` is the
        // same climb as `../../`. A check that only knew `/` would let this one through.
        zipWith("agent/win.zip", mapOf("..\\..\\outside.txt" to "escaped"))

        val answer = tools().run(call("""{"path":"agent/win.zip","unpack":true}"""))

        assertTrue("it is a refusal: $answer", answer.startsWith("1\n"))
        assertTrue("and says why: $answer", answer.contains("climbs out"))
    }

    @Test
    fun `unpacking another project's zip writes into this one, never into that`() {
        // **Reading across projects is allowed and writing is not**, and the derived destination is
        // what keeps them apart: `../work/lib.zip` would unpack *into* `work`, so it goes to this
        // project's own folder instead. The rule the whole app already has, expressed once.
        ws.projectDir("work").mkdirs()
        ZipOutputStream(File(ws.projectDir("work"), "lib.zip").outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("a.txt"))
            zip.write("a".toByteArray())
            zip.closeEntry()
        }

        val answer = tools().run(call("""{"path":"../work/lib.zip","unpack":true}"""))

        assertTrue("it is an answer: ${answer.replace("\n", " | ")}", answer.startsWith("0\n"))
        assertEquals("it landed here", "a", File(project(), "lib/a.txt").readText())
        assertFalse("and not there", File(ws.projectDir("work"), "lib").exists())
    }

    @Test
    fun `a fetched zip unpacks into the project, because fetch lands it somewhere read-only`() {
        // **The two-step workflow `fetch`'s description promises.** `agent/fetched/` is the app's
        // own folder, so "beside the input" would be a refusal and the documented step would be
        // impossible. It goes to the project root, and the answer says which folder it used.
        File(project(), "agent/fetched").mkdirs()
        ZipOutputStream(File(project(), "agent/fetched/lib.zip").outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("lib/a.kt"))
            zip.write("a".toByteArray())
            zip.closeEntry()
        }

        val answer = tools().run(call("""{"path":"agent/fetched/lib.zip","unpack":true}"""))

        assertTrue("it is an answer: ${answer.replace("\n", " | ")}", answer.startsWith("0\n"))
        assertEquals("a", File(project(), "lib/lib/a.kt").readText())
        assertTrue("and it says where: $answer", answer.contains("into lib"))
    }

    // ---- the round trip -----------------------------------------------------

    @Test
    fun `pack then unpack gives back the tree that was packed`() {
        // **The property that makes the pair useful**, and the one a person means by "zip it and
        // send it". Entry names are project-relative paths, so the tree survives.
        write("src/Main.kt", "fun main() {}")
        write("src/util/Text.kt", "fun text() {}")
        write("README.md", "# readme")

        // **Both sides derive their own destination**, so a round trip needs no names at all:
        // packing `.` writes `shopping.zip` in the project, and unpacking it writes `shopping/`
        // beside it — a second copy of the tree, with the same shape.
        tools().run(call("""{"path":"."}"""))
        val back = tools().run(call("""{"path":"shopping.zip","unpack":true}"""))

        assertTrue("accepted: $back", back.startsWith("0\n"))
        assertEquals("fun main() {}", File(project(), "shopping/src/Main.kt").readText())
        assertEquals("fun text() {}", File(project(), "shopping/src/util/Text.kt").readText())
        assertEquals("# readme", File(project(), "shopping/README.md").readText())
    }

    // ---- the two modes, and what a call with neither gets --------------------

    @Test
    fun `a call with no path is refused with both shapes`() {
        // The model has to be able to see what it should have written, which is why both examples
        // are in the refusal rather than only the one it did not use.
        val answer = tools().run(call("""{}"""))

        assertTrue("it is a refusal: $answer", answer.startsWith("1\n"))
        assertTrue("and says what it takes: $answer", answer.contains("`path`"))
        assertTrue("and names the flag: $answer", answer.contains("unpack"))
    }

    @Test
    fun `the two-mode call is gone, and its old keys are refused by name`() {
        // **`unpack` is a boolean now, so the value cannot contradict `path`.** The refusal this
        // test used to check is unreachable by construction — and `as`/`into` are now unknown keys,
        // which is a *better* failure: it names the keys the command does take, so a model that
        // remembers the old shape is corrected in one round rather than guessing at a new one.
        val answer = tools().run(call("""{"path":"README.md","as":"x.zip"}"""))

        assertTrue("it is a refusal: $answer", answer.startsWith("1\n"))
        assertTrue("and names the key it cannot take: $answer", answer.contains("`as`"))
        assertTrue("and the keys it does: $answer", answer.contains("`path`"))
    }

    @Test
    fun `the upload description no longer tells the agent to do the impossible`() {
        // **The bug this step exists to fix.** The sentence used to say *"there is no command that
        // makes one yet: say so rather than trying"* — true when written, and a lie the moment the
        // app could make one. It now names the tool that does.
        val spec = AgentTools.specs().first { it.name == "upload" }
        val said = spec.description

        assertFalse(
            "it does not claim there is no way to zip: $said",
            said.contains("no command that makes one"),
        )
        assertTrue("and names the tool that does: $said", said.contains("archive"))
    }
    // ---- seal and unseal, the third direction -------------------------------

    /**
     * **The round trip through the tool**, which is the contract a caller depends on: seal a file,
     * unseal it, and get the same bytes. The flag pair is `seal: true` then `seal: false`, because
     * the output is a property of the input — nothing is named.
     */
    @Test
    fun `a file is sealed to enc and opened again with the same key`() {
        val original = write("notes.txt", "the milk went sour")
        val key = EncryptedLog.newKey()

        val sealed = tools().run(call("""{"path":"notes.txt","seal":true,"key":"$key"}"""))
        assertTrue("it says what it did: $sealed", sealed.contains("notes.txt.enc"))

        val enc = File(project(), "notes.txt.enc")
        assertTrue("the sealed file is beside it", enc.isFile)
        assertTrue("and the plaintext is still there", original.isFile)

        val opened = tools().run(call("""{"path":"notes.txt.enc","seal":false,"key":"$key"}"""))
        assertTrue("it opened: $opened", opened.contains("notes.txt"))
        assertEquals(
            "and the bytes came back",
            "the milk went sour",
            File(project(), "notes.txt").readText(),
        )
    }

    /**
     * **The sealed file is not the plaintext.** A seal that wrote the bytes through unchanged would
     * pass every round-trip test above and encrypt nothing.
     */
    @Test
    fun `a sealed file does not contain the plaintext`() {
        write("notes.txt", "the milk went sour")
        tools().run(call("""{"path":"notes.txt","seal":true,"key":"${EncryptedLog.newKey()}"}"""))

        val enc = File(project(), "notes.txt.enc").readText()
        assertFalse("the sentence is not in it", enc.contains("milk went sour"))
        assertTrue("but the marker is", enc.contains(EncryptedLog.MARKER))
    }

    /**
     * **A wrong key is refused, and the sentence names the key rather than the format.** The reader's
     * own words, because a wrong key and a damaged blob are not distinguishable and pretending
     * otherwise would send the caller to fix the wrong thing.
     */
    @Test
    fun `opening with the wrong key is refused and says so`() {
        write("notes.txt", "the milk went sour")
        // **Sealed with one key, opened with another** — and the first call must actually run. It was
        // written without `tools().run(...)` here, so nothing was sealed and the "wrong key" was being
        // tried against a plain file.
        tools().run(call("""{"path":"notes.txt","seal":true,"key":"${EncryptedLog.newKey()}"}"""))

        val said = tools().run(call("""{"path":"notes.txt.enc","seal":false,"key":"${EncryptedLog.newKey()}"}"""))
        assertTrue("it is refused: ${said}", said.startsWith(AgentLog.FAILURE))
        assertTrue(
            "and names the key rather than the format: ${said}",
            said.contains("does not open it"),
        )
    }

    /**
     * **A key that names a vault secret is looked up, not used literally.** That is what keeps the key
     * out of the conversation and out of the trace — the reason the vault exists.
     */
    @Test
    fun `a key is looked up in the vault when a secret has that name`() {
        val real = EncryptedLog.newKey()
        // **The real fake, not a stub.** `MemoryCredentials` enforces the host rule the phone
        // enforces, so this test would fail if the tool asked for a secret in a way the vault could
        // never answer.
        val credentials = MemoryCredentials().apply {
            activeLogin = "dev"
            setSecret("my-key", real, "")
        }
        val withVault = AgentTools(ws, "shopping", git, log, credentials = credentials)
        write("notes.txt", "the milk went sour")

        val sealed = withVault.run(call("""{"path":"notes.txt","seal":true,"key":"my-key"}"""))
        assertTrue("it sealed with the vault's value: ${sealed}", sealed.contains(".enc"))

        // Opening with the literal word must fail, because the key was the SECRET, not the name.
        val literal = withVault.run(call("""{"path":"notes.txt.enc","seal":false,"key":"wrong-key"}"""))
        assertTrue("a different key fails: ${literal}", literal.startsWith(AgentLog.FAILURE))
    }

    /**
     * **A folder is refused with the step that works.** Sealing a folder would have to invent a file
     * and a name, and both would be guesses — `archive` then seal is the answer, and saying so is
     * what turns a refusal into a next step.
     */
    @Test
    fun `sealing a folder is refused and names archive`() {
        write("src/a.txt")
        val said = tools().run(call("""{"path":"src","seal":true,"key":"${EncryptedLog.newKey()}"}"""))

        assertTrue("it is refused: ${said}", said.startsWith(AgentLog.FAILURE))
        assertTrue("and names the tool that does: ${said}", said.contains("archive"))
    }

    /**
     * **Opening a file that is not sealed says so, rather than passing it through.** The reader
     * returns unsealed bytes unchanged for the artifact path's sake; the *tool* is asked to open a
     * specific file, so a silent pass-through would report success for a no-op.
     */
    @Test
    fun `opening a file that is not sealed is refused`() {
        write("notes.txt", "the milk went sour")
        val said = tools().run(call("""{"path":"notes.txt","seal":false,"key":"whatever"}"""))

        assertTrue("it is refused: ${said}", said.startsWith(AgentLog.FAILURE))
        assertTrue("and says why: ${said}", said.contains(".enc"))
    }

    /** No key at all is refused where the mistake is made, not later at open time. */
    @Test
    fun `sealing without a key is refused`() {
        write("notes.txt")
        val said = tools().run(call("""{"path":"notes.txt","seal":true}"""))

        assertTrue("it is refused: ${said}", said.startsWith(AgentLog.FAILURE))
        assertTrue("and asks for one: ${said}", said.contains("key"))
    }
}
