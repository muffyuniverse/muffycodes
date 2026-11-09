package com.example.notes

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * **The request library is a folder of files, so the rules that matter here are a file's rules.**
 *
 * What is asserted is what a person can do to these documents with their hands: write one, copy it,
 * break it, delete it, and name it in a pointer. Each of those has one right answer and the rest of the
 * suite depends on it — a parse that fell back to defaults would be an agent sent with a model nobody
 * chose, a name that was not checked would be a file written in another folder, and a pointer that
 * could climb out of the account would be a request read from somewhere nobody wrote.
 *
 * **Two folders, and the difference is who may write** (§3.8.1): the references under `shared/schemas/`
 * are the app's and locked; the units under `shared/requests/` are the person's and the agent's. The
 * assertions about *where* a thing is written are therefore as load-bearing as the ones about what it
 * says.
 */
class SchemasTest {

    private lateinit var account: File

    /** One tree: an account, and the app's folder beside it — the shape `Workspace.single` names. */
    private lateinit var ws: Workspace

    @Before
    fun makeSpace() {
        account = File(System.getProperty("java.io.tmpdir"), "schemas-${System.nanoTime()}")
        account.mkdirs()
        ws = Workspace.single(account)
    }

    /** A unit written by hand, the way the editor would write it. */
    private fun writeUnit(kind: ShapeKind, name: String, text: String) {
        val folder = Schemas.unitsDir(ws, kind)
        folder.mkdirs()
        File(folder, name).writeText(text)
    }

    // ---- the two folders -------------------------------------------------------

    @Test
    fun `the library is two folders, and neither is inside a repository`() {
        // **Where it is decides what it is.** `shared/` is outside every project and therefore outside
        // every repository, so a commit cannot change a request shape behind a person's back — which is
        // the whole reason the library is here and not in a project.
        assertEquals("shared/schemas", Schemas.DIR)
        assertEquals("shared/requests", Schemas.REQUESTS)
        assertEquals(
            "the references are the app's folder, under the kind",
            File(File(account, "shared"), "schemas/chat"),
            Schemas.refsDir(ws, ShapeKind.CHAT),
        )
        assertEquals(
            "and the units are the person's, under the same kind",
            File(File(account, "shared"), "requests/chat"),
            Schemas.unitsDir(ws, ShapeKind.CHAT),
        )
    }

    @Test
    fun `a fresh install is given a reference and a working unit`() {
        Schemas.ensure(ws)

        // **A reference that cannot be sent, and a unit that can.** The reference explains the format
        // field by field and is locked; the unit is a working request naming the model and the key row.
        assertTrue(
            "the reference is written",
            File(Schemas.refsDir(ws, ShapeKind.CHAT), Schemas.REFERENCE).isFile,
        )
        assertEquals("and exactly one unit is offered", listOf(Schemas.UNIT), Schemas.names(ws, ShapeKind.CHAT))

        val unit = Schemas.read(ws, Schemas.unitPath(ShapeKind.CHAT, Schemas.UNIT), ShapeKind.CHAT).getOrThrow()
        assertNull("the unit is usable as written", unit.problem(ShapeKind.CHAT))
        assertTrue("it names a server", unit.address.startsWith("https://"))
    }

    @Test
    fun `the shipped unit carries its model, because a form that named none could not be sent`() {
        Schemas.ensure(ws)
        val unit = Schemas.read(ws, Schemas.unitPath(ShapeKind.CHAT, Schemas.UNIT)).getOrThrow()

        // **A literal, not `{{model}}`.** The model is what this file *is*: two forms are two models,
        // and the app has no other place to keep one.
        assertEquals(ModelConfigFile.DEFAULT_MODEL, unit.body.getString("model"))
        // And the placeholders that remain are the ones only the app can fill.
        assertEquals("{{messages}}", unit.body.getString("messages"))
        assertEquals("{{tools}}", unit.body.getString("tools"))
        assertEquals("{{stream}}", unit.body.getString("stream"))
    }

    @Test
    fun `the shipped unit carries the numbers the settings shipped with`() {
        Schemas.ensure(ws)
        val unit = Schemas.read(ws, Schemas.unitPath(ShapeKind.CHAT, Schemas.UNIT)).getOrThrow()

        assertEquals("the destination", 60_000L, unit.target)
        assertEquals("the trigger", 100_000L, unit.forget)
        // **Trim is off, and off is a decision rather than a gap**: an app that cuts a conversation on
        // its own loses the thing the person was working on.
        val trimming = unit.trim
        assertEquals("trimming is off", 0L, trimming)
    }

    @Test
    fun `the shipped files are commented, so a person can read the format in the file`() {
        // **The reason `.jsonc` exists at all.** A template that cannot explain a field beside itself
        // is a template whose format lives only in a source file.
        Schemas.ensure(ws)
        val reference = File(Schemas.refsDir(ws, ShapeKind.CHAT), Schemas.REFERENCE).readText()
        val unit = File(Schemas.unitsDir(ws, ShapeKind.CHAT), Schemas.UNIT).readText()

        assertTrue("the reference explains itself", reference.contains("//"))
        assertTrue("and so does the unit", unit.contains("//"))
    }

    @Test
    fun `a resolved folder is not refilled, even when it holds nothing`() {
        Schemas.ensure(ws)
        Schemas.remove(ws, Schemas.unitPath(ShapeKind.CHAT, Schemas.UNIT))

        // **The folder's existence is the flag, not whether it holds a form.** "Fill it when it holds
        // none" reads the same and is wrong: a folder somebody emptied by hand holds none either, and
        // refilling it would be the app putting back the files they just deleted.
        Schemas.ensure(ws)
        assertEquals(emptyList<String>(), Schemas.names(ws, ShapeKind.CHAT))
    }

    @Test
    fun `deleting the requests folder is how a person asks for the shipped unit back`() {
        Schemas.ensure(ws)
        Schemas.remove(ws, Schemas.unitPath(ShapeKind.CHAT, Schemas.UNIT))
        File(account, "shared/requests").deleteRecursively()

        // **The one way back to the defaults, said in the code**: a folder that is not there is made and
        // filled, and a folder that is there is never added to.
        Schemas.ensure(ws)
        assertEquals(listOf(Schemas.UNIT), Schemas.names(ws, ShapeKind.CHAT))
    }

    @Test
    fun `the list is sorted, offers both suffixes, and holds only schema files`() {
        Schemas.ensure(ws)
        writeUnit(ShapeKind.CHAT, "a-first.json", "{}")
        writeUnit(ShapeKind.CHAT, "b-second.jsonc", "// a comment\n{}")
        // **A note is allowed beside them.** This folder is a person's; refusing to let them keep a
        // README in it would be tidiness at their expense, so a non-schema file is simply not offered.
        writeUnit(ShapeKind.CHAT, "notes.txt", "which model I use for what")
        // And a dot-file is not one either, for the same reason the list sorts only what it can send.
        writeUnit(ShapeKind.CHAT, ".hidden.json", "{}")

        assertEquals(
            listOf("a-first.json", "b-second.jsonc", Schemas.UNIT),
            Schemas.names(ws, ShapeKind.CHAT),
        )
    }

    // ---- reading and writing ---------------------------------------------------

    @Test
    fun `a commented form is read, which is what makes jsonc a template format`() {
        // **The measured reason the scanner exists** ([Jsonc]): `//` in a URL is not a comment, and a
        // reader that did not track strings would delete the address and leave a form pointing nowhere.
        Schemas.ensure(ws)
        writeUnit(
            ShapeKind.CHAT,
            "commented.jsonc",
            """
            // the model this phone talks to
            {
              "address": "https://openrouter.ai/api/v1", // the host
              "url": "chat/completions",
              /* a block comment
                 across two lines */
              "body": { "model": "m", "messages": "{{messages}}" }
            }
            """.trimIndent(),
        )

        val form = Schemas.read(ws, Schemas.unitPath(ShapeKind.CHAT, "commented.jsonc")).getOrThrow()
        assertEquals("https://openrouter.ai/api/v1", form.address)
        assertEquals("m", form.body.getString("model"))
    }

    @Test
    fun `a form survives being written and read`() {
        val form = Schema(
            address = "https://api.example.com/v1",
            url = "chat/completions",
            body = JSONObject("""{"model":"m","messages":"{{messages}}"}"""),
            target = 1_000,
            forget = 2_000,
            trim = 3_000,
        )
        val path = Schemas.unitPath(ShapeKind.CHAT, "mine.json")

        Schemas.write(ws, path, form).getOrThrow()
        val back = Schemas.read(ws, path).getOrThrow()

        assertEquals(form.address, back.address)
        assertEquals(form.url, back.url)
        assertEquals(1_000L, back.target)
        assertEquals(2_000L, back.forget)
        assertEquals(3_000L, back.trim)
        assertEquals("m", back.body.getString("model"))
    }

    @Test
    fun `a number of zero is not written, because off is said by saying nothing`() {
        val path = Schemas.unitPath(ShapeKind.CHAT, "quiet.json")
        Schemas.write(ws, path, Schema("https://x.test/v1", url = "chat/completions", body = JSONObject("{}")))
        val text = File(Schemas.unitsDir(ws, ShapeKind.CHAT), "quiet.json").readText()

        // **The file a person opens is the shortest true statement of the request.** A `"trim": 0` in
        // every form would be three keys that mean "nothing" in a document whose point is that nothing
        // is derived.
        assertFalse(text.contains("target"))
        assertFalse(text.contains("forget"))
        assertFalse(text.contains("trim"))
    }

    @Test
    fun `a file that is not JSON is refused with its name and its line`() {
        Schemas.ensure(ws)
        writeUnit(ShapeKind.CHAT, "broken.json", "{\n  \"address\": \"https://x.test/v1\",\n  \"body\": {\n")

        val failure = Schemas.read(ws, Schemas.unitPath(ShapeKind.CHAT, "broken.json")).exceptionOrNull()

        assertNotNull(failure)
        assertTrue(
            "the sentence names the file and the line: ${failure!!.message}",
            failure.message!!.contains("broken.json") && failure.message!!.contains("line"),
        )
    }

    @Test
    fun `a form asked for as the wrong kind is refused by name`() {
        Schemas.ensure(ws)
        // The kind is a fact about the place that chose the file, so it is checked when the file is
        // read: a chat form naming a placeholder the chat turn cannot fill is not a chat form.
        writeUnit(
            ShapeKind.CHAT,
            "foreign.json",
            """{"address":"https://x.test/v1","url":"embeddings","body":{"input":"{{input}}"}}""",
        )

        val failure = Schemas.read(ws, Schemas.unitPath(ShapeKind.CHAT, "foreign.json"), ShapeKind.CHAT)
            .exceptionOrNull()

        assertNotNull(failure)
        assertTrue("it names the placeholder", failure!!.message!!.contains("{{input}}"))
    }

    @Test
    fun `a path that climbs out of the account is refused rather than clamped`() {
        Schema.parse("""{"address":"https://x.test/v1","url":"a","body":{}}""").getOrThrow()
        for (path in listOf("../../etc/passwd", "shared/../../x.json", "shared/requests/chat/../../x.json")) {
            val failure = Schemas.read(ws, path).exceptionOrNull()
            assertNotNull("$path is refused", failure)
            assertTrue(
                "and says why: ${failure!!.message}",
                failure!!.message!!.contains("climb"),
            )
        }
    }

    @Test
    fun `a path with no file names the path, not a name`() {
        val path = Schemas.unitPath(ShapeKind.CHAT, "gone.json")
        val failure = Schemas.read(ws, path).exceptionOrNull()

        assertNotNull(failure)
        // **The whole path**, because that is what a person needs when a pointer outlives its file.
        assertTrue(failure!!.message!!.contains(path))
    }

    // ---- names -----------------------------------------------------------------

    @Test
    fun `a name that would write into another folder is refused`() {
        val problem = Schemas.nameProblem("../escape.json")
        assertNotNull(problem)
        assertTrue(problem!!.contains("slash"))
    }

    @Test
    fun `a name without a suffix is refused, because it could never be offered again`() {
        assertTrue(Schemas.nameProblem("chat")!!.contains(".json"))
        assertTrue(Schemas.nameProblem(".hidden.json")!!.contains("dot"))
        assertNull(Schemas.nameProblem("chat.json"))
        assertNull(Schemas.nameProblem("chat.jsonc"))
        assertNull(Schemas.nameProblem("deepseek fast.jsonc"))
    }

    @Test
    fun `writing under a bad name writes nothing`() {
        val form = Schema("https://x.test/v1", url = "chat/completions", body = JSONObject("{}"))

        assertTrue(Schemas.write(ws, Schemas.unitPath(ShapeKind.CHAT, "sub/chat.json"), form).isFailure)
        assertFalse(File(Schemas.unitsDir(ws, ShapeKind.CHAT), "sub/chat.json").exists())
    }

    // ---- which form a chat runs --------------------------------------------

    @Test
    fun `a chat that has chosen nothing says so rather than guessing`() {
        // **No pointer, no request.** A fresh branch runs nothing until somebody names a form, which is
        // what makes "the picker is the choice" true — a default here would spend the key on a model
        // nobody chose for this chat.
        assertEquals(null, Schemas.pointer(account, "Shopping", "main"))
        assertEquals(null, Schemas.pointer(account, "Shopping", "feature/x"))
    }

    @Test
    fun `a pointer is a path, per chat, and main is the bare project name`() {
        val slow = Schemas.unitPath(ShapeKind.CHAT, "slow.json")
        Schemas.setPointer(account, "Shopping", "main", Schemas.unitPath(ShapeKind.CHAT, "chat.json"))
        Schemas.setPointer(account, "Shopping", "feature/x", slow)

        assertEquals(Schemas.unitPath(ShapeKind.CHAT, "chat.json"), Schemas.pointer(account, "Shopping", "main"))
        assertEquals(slow, Schemas.pointer(account, "Shopping", "feature/x"))
        // **Two branches are two requests**, like two agents: a branch is a worktree and the request it
        // runs is part of what that worktree is.
        assertEquals("another project has none", null, Schemas.pointer(account, "Notes", "main"))
    }

    @Test
    fun `a pointer lives beside the sessions, not inside one`() {
        // **In `.muffy/`, keyed the way a session is keyed** — and in its own file, because a form is
        // chosen when the chat is opened, before the first turn, and forgetting a session must not
        // forget which request the chat runs.
        Schemas.setPointer(account, "Shopping", "main", Schemas.unitPath(ShapeKind.CHAT, "chat.json"))

        assertEquals(
            File(File(account, Workspace.STATE_DIR), "schemas.json"),
            Schemas.pointerFile(account),
        )
        assertTrue("one file, not one per chat", Schemas.pointerFile(account).isFile)
    }

    @Test
    fun `a blank path forgets the pointer`() {
        Schemas.setPointer(account, "Shopping", "main", Schemas.unitPath(ShapeKind.CHAT, "chat.json"))
        Schemas.setPointer(account, "Shopping", "main", "  ")

        assertEquals(null, Schemas.pointer(account, "Shopping", "main"))
    }

    @Test
    fun `a pointer file that will not parse reads as nobody having chosen`() {
        // The sessions live in the next file over, and a damaged pointer file must not take the record
        // of what is running with it — the refusal the agent gets names the picker.
        Schemas.pointerFile(account).parentFile?.mkdirs()
        Schemas.pointerFile(account).writeText("{ not json")

        assertEquals(null, Schemas.pointer(account, "Shopping", "main"))
    }

    @Test
    fun `writing one pointer keeps the others`() {
        Schemas.setPointer(account, "Shopping", "main", Schemas.unitPath(ShapeKind.CHAT, "chat.json"))
        Schemas.setPointer(account, "Notes", "main", Schemas.unitPath(ShapeKind.CHAT, "slow.json"))

        assertEquals(Schemas.unitPath(ShapeKind.CHAT, "chat.json"), Schemas.pointer(account, "Shopping", "main"))
        assertEquals(Schemas.unitPath(ShapeKind.CHAT, "slow.json"), Schemas.pointer(account, "Notes", "main"))
    }
}
