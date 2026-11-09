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
 * **The request schemas are files, so the rules that matter here are a file's rules.**
 *
 * What is asserted is what a person can do to these documents with their hands: write one, copy it,
 * break it, delete it. Each of those has one right answer and the rest of the suite depends on it —
 * a parse that fell back to defaults would be an agent sent with a model nobody chose, and a name that
 * was not checked would be a file written in another folder.
 */
class SchemasTest {

    private lateinit var account: File

    /**
     * **The app's folder** — one per phone (§3.11), which is where the library lives.
     *
     * A test builds one tree, so the app's folder is `shared/` inside it: the same path a person sees,
     * with the account beside it rather than containing it.
     */
    private val app: java.io.File get() = java.io.File(account, Workspace.SYSTEM_PROJECT)

    @Before
    fun makeSpace() {
        account = File(System.getProperty("java.io.tmpdir"), "schemas-${System.nanoTime()}")
        account.mkdirs()
    }

    private fun write(name: String, text: String) {
        val folder = Schemas.dir(app)
        folder.mkdirs()
        File(folder, name).writeText(text)
    }

    // ---- the folder ------------------------------------------------------------

    @Test
    fun `the schemas live in the account's shared folder, which no repository holds`() {
        // **Where it is decides what it is.** `shared/` is outside every project and therefore outside
        // every repository, so a commit cannot change a request shape behind a person's back — which is
        // the whole reason the templates are here and not in a project. The operator: *"we can just keep
        // llm request templates in shared as a system locked subfolder."*
        assertEquals("shared/schemas", Schemas.DIR)
        assertEquals(File(account, "shared/schemas"), Schemas.dir(app))
        // **Derived, not typed twice.** A rename of the account folder must carry the templates with it,
        // or every pointer would name a folder nothing creates.
        assertEquals(
            "the folder's own name, and one subfolder",
            Workspace.SYSTEM_PROJECT + "/schemas",
            Schemas.DIR,
        )
    }

    @Test
    fun `a new account is given the one request the app makes`() {
        val names = Schemas.ensure(app)

        // **One, not three.** The embedding and rerank templates went with the index (2026-11-09), so
        // a fresh folder holds the chat request and nothing pretending to be a call nobody makes.
        assertEquals(listOf("chat.json"), names)
        for (name in names) {
            val schema = Schemas.read(app, name).getOrThrow()
            assertNull("$name is usable as written", schema.problem())
            assertTrue("$name names a server", schema.address.startsWith("https://"))
        }
    }

    @Test
    fun `the shipped chat carries its model, because a template that named none could not be sent`() {
        Schemas.ensure(app)
        val chat = Schemas.read(app, "chat.json").getOrThrow()

        // **A literal, not `{{model}}`.** The model is what this file *is*: two templates are two
        // models, and the app has no other place to keep one.
        assertEquals(ModelConfigFile.DEFAULT_MODEL, chat.body.getString("model"))
        // And the placeholders that remain are the ones only the app can fill.
        assertEquals("{{messages}}", chat.body.getString("messages"))
        assertEquals("{{tools}}", chat.body.getString("tools"))
        assertEquals("{{stream}}", chat.body.getString("stream"))
    }

    @Test
    fun `the shipped chat ships with the numbers the settings shipped with`() {
        Schemas.ensure(app)
        val chat = Schemas.read(app, "chat.json").getOrThrow()

        assertEquals("the destination", 60_000L, chat.target)
        assertEquals("the trigger", 100_000L, chat.forget)
        // **Trim is off, and off is a decision rather than a gap**: an app that cuts a conversation on
        // its own loses the thing the person was working on.
        assertEquals("trimming is off", 0L, chat.trim)
    }

    @Test
    fun `a folder a person emptied stays empty`() {
        Schemas.ensure(app)
        for (name in Schemas.names(app)) Schemas.remove(app, name)

        // **The folder's existence is the flag, not whether it holds a schema.** "Fill it when it holds
        // none" reads the same and is wrong: a folder somebody emptied by hand holds none either, and
        // refilling it would be the app putting back the files they just deleted.
        assertEquals(emptyList<String>(), Schemas.ensure(app))
        assertTrue("the folder is still there, which is what says it was emptied", Schemas.dir(app).isDirectory)
    }

    @Test
    fun `a folder that still holds one schema is not refilled`() {
        Schemas.ensure(app)
        Schemas.remove(app, "chat.json")

        // Same rule from the other side: a folder that exists is a folder somebody has seen, so nothing
        // is written into it — not even the one that is missing.
        assertEquals(emptyList<String>(), Schemas.ensure(app))
    }

    @Test
    fun `deleting the folder is how a person asks for the shipped one back`() {
        Schemas.ensure(app)
        Schemas.remove(app, "chat.json")
        Schemas.dir(app).deleteRecursively()

        // **The one way back to the defaults, said in the code**: a folder that is not there is made and
        // filled, and a folder that is there is never added to. Nothing else in this class writes the
        // shipped files, so there is no second path that could disagree.
        assertEquals(listOf("chat.json"), Schemas.ensure(app))
    }

    @Test
    fun `the list is sorted and holds only schema files`() {
        Schemas.ensure(app)
        write("a-first.json", Schemas.read(app, "chat.json").getOrThrow().text())
        // **A note is allowed beside them.** This folder is a person's; refusing to let them keep a
        // README in it would be tidiness at their expense, so a non-`.json` file is simply not a schema.
        write("notes.txt", "which model I use for what")
        // And a dot-file is not one either, for the same reason the list sorts only what it can send.
        write(".hidden.json", "{}")

        assertEquals(listOf("a-first.json", "chat.json"), Schemas.names(app))
    }

    // ---- writing and reading ---------------------------------------------------

    @Test
    fun `a schema survives being written and read`() {
        val schema = Schema(
            address = "https://api.example.com/v1",
            url = "chat/completions",
            body = JSONObject("""{"model":"m","messages":"{{messages}}"}"""),
            target = 1_000,
            forget = 2_000,
            trim = 3_000,
        )
        Schemas.write(app, "mine.json", schema).getOrThrow()
        val back = Schemas.read(app, "mine.json").getOrThrow()

        assertEquals(schema.address, back.address)
        assertEquals(schema.url, back.url)
        assertEquals(1_000L, back.target)
        assertEquals(2_000L, back.forget)
        assertEquals(3_000L, back.trim)
        assertEquals("m", back.body.getString("model"))
    }

    @Test
    fun `a number of zero is not written, because off is said by saying nothing`() {
        Schemas.write(app, "quiet.json", Schema("https://x.test/v1", url = "chat/completions", body = JSONObject("{}")))
        val text = File(Schemas.dir(app), "quiet.json").readText()

        // **The file a person opens is the shortest true statement of the request.** A `"trim": 0` in
        // every template would be three keys that mean "nothing" in a document whose point is that
        // nothing is derived.
        assertFalse(text.contains("target"))
        assertFalse(text.contains("forget"))
        assertFalse(text.contains("trim"))
    }

    @Test
    fun `a file that is not JSON is refused with its line`() {
        write("broken.json", "{\n  \"address\": \"https://x.test/v1\",\n  \"body\": {\n")

        val failure = Schemas.read(app, "broken.json").exceptionOrNull()

        assertNotNull(failure)
        assertTrue(
            "the sentence names the file and the line: ${failure!!.message}",
            failure.message!!.contains("broken.json") && failure.message!!.contains("line"),
        )
    }

    @Test
    fun `a number that is not a number is refused rather than read as off`() {
        // **`"target": "60k"` is a typo, and reading it as zero would silently turn compaction off on
        // the file where somebody tried hardest to set it.** Absent is off; wrong is wrong.
        val failure = Schema.parse(
            """{"address":"https://x.test/v1","url":"chat/completions","target":"60k","body":{}}""",
        ).exceptionOrNull()

        assertNotNull(failure)
        assertTrue(failure!!.message!!.contains("\"target\""))
    }

    @Test
    fun `a missing address is named as the thing that is missing`() {
        val failure = Schema.parse("""{"url":"chat/completions","body":{}}""").exceptionOrNull()
        assertTrue(failure!!.message!!.contains("\"address\""))
    }

    @Test
    fun `an address that is not an address is refused`() {
        val failure = Schema.parse(
            """{"address":"openrouter.ai","url":"chat/completions","body":{}}""",
        ).exceptionOrNull()

        // **The same rule `model_conf.json`'s baseUrl had**, and for the same reason: a bare host here
        // would build a relative URL and an error nobody could act on.
        assertTrue(failure!!.message!!.contains("http"))
    }

    @Test
    fun `a body that is a string is refused, because a request is an object`() {
        val failure = Schema.parse(
            """{"address":"https://x.test/v1","url":"chat/completions","body":"hello"}""",
        ).exceptionOrNull()
        assertTrue(failure!!.message!!.contains("\"body\""))
    }

    @Test
    fun `a negative size is refused`() {
        val failure = Schema("https://x.test/v1", url = "chat/completions", body = JSONObject("{}"), target = -1).problem()
        assertTrue(failure!!.contains("\"target\""))
    }

    // ---- the two rules a request must obey --------------------------------------

    @Test
    fun `a url that is an address is refused, because it would send the key somewhere else`() {
        // **[Shape]'s third rule, reached through the file**: an address here would mean the request —
        // and with it the key looked up for the file's own host — went to a host nobody chose.
        val schema = Schema("https://x.test/v1", url = "https://evil.test/chat", body = JSONObject("{}"))
        val problem = schema.problem()

        assertNotNull(problem)
        assertTrue(problem!!.contains("not an address"))
    }

    @Test
    fun `a placeholder the app cannot fill is named`() {
        val schema = Schema(
            address = "https://x.test/v1",
            url = "chat/completions",
            body = JSONObject("""{"model":"m","messages":"{{conversation}}"}"""),
        )

        val problem = schema.problem(ShapeKind.CHAT)

        assertNotNull(problem)
        // **The sentence lists what it can fill**, so the person does not have to find the app's source
        // to learn the vocabulary.
        assertTrue(problem!!.contains("{{conversation}}") && problem.contains("{{messages}}"))
    }

    @Test
    fun `a template is checked against the vocabulary of the kind it is asked as`() {
        // **The kind travels with the question rather than being stored in the file**, and chat is the
        // only vocabulary left now that the embedding and rerank calls are gone. A body naming a
        // placeholder the chat turn cannot fill is refused with the name, which is the assertion that
        // keeps the check from being a formality.
        val foreign = Schema(
            address = "https://x.test/v1",
            url = "embeddings",
            body = JSONObject("""{"model":"e","input":"{{input}}"}"""),
        )

        assertNotNull(
            "asked as a chat request, {{input}} is not a name it can fill",
            foreign.problem(ShapeKind.CHAT),
        )
        assertNull(
            "and with no kind asked for, a half-written file is not called wrong",
            foreign.problem(null),
        )
    }

    // ---- which template a worktree runs ---------------------------------------

    @Test
    fun `a worktree that has chosen nothing says so rather than guessing`() {
        // **No pointer, no request.** A fresh branch runs nothing until somebody names a template, which
        // is what makes "the picker is the choice" true — a default here would spend the key on a model
        // nobody chose for this worktree.
        assertEquals(null, Schemas.pointer(account, "Shopping", "main"))
        assertEquals(null, Schemas.pointer(account, "Shopping", "feature/x"))
    }

    @Test
    fun `a pointer is per worktree, and main is the bare project name`() {
        Schemas.setPointer(account, "Shopping", "main", "chat.json")
        Schemas.setPointer(account, "Shopping", "feature/x", "slow.json")

        assertEquals("chat.json", Schemas.pointer(account, "Shopping", "main"))
        assertEquals("slow.json", Schemas.pointer(account, "Shopping", "feature/x"))
        // **Two branches are two requests**, like two agents: a branch is a worktree and the request it
        // runs is part of what that worktree is.
        assertEquals("another project has none", null, Schemas.pointer(account, "Notes", "main"))
    }

    @Test
    fun `a pointer lives beside the sessions, not inside one`() {
        // **In `.muffy/`, keyed the way a session is keyed** — and in its own file, because a template is
        // chosen when the branch is made, before any agent has run, and forgetting a session must not
        // forget which request the branch runs.
        Schemas.setPointer(account, "Shopping", "main", "chat.json")

        assertEquals(
            File(File(account, Workspace.STATE_DIR), "schemas.json"),
            Schemas.pointerFile(account),
        )
        assertTrue("one file, not one per branch", Schemas.pointerFile(account).isFile)
    }

    @Test
    fun `a blank name forgets the pointer`() {
        Schemas.setPointer(account, "Shopping", "main", "chat.json")
        Schemas.setPointer(account, "Shopping", "main", "  ")

        assertEquals(null, Schemas.pointer(account, "Shopping", "main"))
    }

    @Test
    fun `a file that will not parse reads as nobody having chosen`() {
        // The sessions live in the next file over, and a damaged pointer file must not take the record of
        // what is running with it — the refusal the agent gets names the picker.
        Schemas.pointerFile(account).parentFile?.mkdirs()
        Schemas.pointerFile(account).writeText("{ not json")

        assertEquals(null, Schemas.pointer(account, "Shopping", "main"))
    }

    @Test
    fun `writing one pointer keeps the others`() {
        Schemas.setPointer(account, "Shopping", "main", "chat.json")
        Schemas.setPointer(account, "Notes", "main", "slow.json")

        assertEquals("chat.json", Schemas.pointer(account, "Shopping", "main"))
        assertEquals("slow.json", Schemas.pointer(account, "Notes", "main"))
    }

    // ---- names -----------------------------------------------------------------

    @Test
    fun `a name that would write into another folder is refused`() {
        val problem = Schemas.nameProblem("../escape.json")
        assertNotNull(problem)
        assertTrue(problem!!.contains("slash"))
    }

    @Test
    fun `a name without the suffix is refused, because it could never be offered again`() {
        assertTrue(Schemas.nameProblem("chat")!!.contains(".json"))
        assertTrue(Schemas.nameProblem(".hidden.json")!!.contains("dot"))
        assertNull(Schemas.nameProblem("chat.json"))
        assertNull(Schemas.nameProblem("deepseek fast.json"))
    }

    @Test
    fun `writing under a bad name writes nothing`() {
        val schema = Schema("https://x.test/v1", url = "chat/completions", body = JSONObject("{}"))

        assertTrue(Schemas.write(app, "sub/chat.json", schema).isFailure)
        assertFalse(File(Schemas.dir(app), "sub/chat.json").exists())
    }

    @Test
    fun `reading a name that is not there says so rather than failing silently`() {
        val failure = Schemas.read(app, "gone.json").exceptionOrNull()
        assertTrue(failure!!.message!!.contains("gone.json"))
    }
}
