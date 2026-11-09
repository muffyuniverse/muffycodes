package com.example.notes

import android.graphics.Bitmap
import android.graphics.Color
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream

/**
 * `look` as the model meets it: what it refuses, what it attaches, and **where the picture goes**.
 *
 * The last part is the one that matters. A tool result cannot carry an image, so `look` writes a
 * `see` note and the note becomes the user message that carries the picture. That means the two
 * halves have to be tested together — a `look` that returned a perfect data URL and a `messages()`
 * that dropped the note would be a feature that does nothing, and either half alone would look fine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LookToolTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var account: File
    private lateinit var ws: Workspace
    private lateinit var git: ProjectGit
    private lateinit var log: AgentLog

    private val author = GitAuthor("Muffy", "muffy@example.com")

    @Before
    fun fresh() {
        account = File(System.getProperty("java.io.tmpdir"), "look-${System.nanoTime()}")
        ws = Workspace(account)
        ws.ensure()
        ws.repoOf("shopping").mkdirs()
        ws.write(File(ws.repoOf("shopping"), "milk.txt"), "two litres")
        git = ProjectGit(ws, { null }, { author })
        git.init("shopping")
        log = AgentLog.open(File(ws.accountDir, "shopping"))
    }

    private fun tools(sees: (() -> Boolean)? = null) =
        AgentTools(ws, "shopping", git, log, modelSees = sees)

    /** A solid-colour PNG in the project, at the path `look` will be told. */
    private fun picture(width: Int, height: Int, path: String = "shot.png"): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(200, 60, 60))
        val file = File(ws.repoOf("shopping"), path)
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    /**
     * An agent over this project, for the one test that needs the rebuilt conversation.
     *
     * Built the same way `AgentTest` builds one, and with a scripted door that is never asked
     * anything: `messages()` reads the record, so nothing has to be run for it to be right.
     */
    private fun agent() = Agent(
        accountDir = account,
        project = "shopping",
        ws = ws,
        git = git,
        log = log,
        door = ScriptedDoor(),
        model = ActiveModel("deepseek/deepseek-v4.1-flash-1017", "deepseek", "high"),
        limits = ContextBudget.Limits.of(trigger = 0, hard = 0),
    )

    private fun look(vararg paths: String): String = tools().run(
        Call("c1", "look", JSONObject().put("paths", org.json.JSONArray(paths.toList())).toString()),
    )

    @Test
    fun `look attaches the picture and says so, without the picture in the result`() {
        picture(1024, 768)
        val body = look("shot.png")

        assertTrue("it worked: $body", body.startsWith(AgentLog.SUCCESS))
        // **The result must NOT contain the data URL.** A megabyte of base64 in a tool result is a
        // result that costs the whole context to say "here is a picture" — and the picture would
        // then be sent twice, once as text and once as an image.
        assertFalse("no base64 in the result", body.contains("base64"))
        assertTrue("it names the path: $body", body.contains("shot.png"))
        assertTrue("and the size it was sent at: $body", body.contains("1024×768"))
        assertTrue("and what it costs: $body", body.contains("765"))
        assertTrue(
            "and says when it will arrive: $body",
            body.contains("next message"),
        )
    }

    @Test
    fun `the picture arrives on the next message, once`() {
        // **The seam that makes `look` work at all.** The note is a row, so `messages()` — which
        // rebuilds the whole array from the record every request — turns it into a user message
        // carrying the data URL. This is the assertion that would have caught a `look` that attached
        // nothing.
        picture(64, 64)
        look("shot.png")

        val messages = agent().messages(log.contextRows())
        val carrying = messages.filter { it.pictures.isNotEmpty() }
        assertEquals("one message carries pictures", 1, carrying.size)
        assertEquals("and it carries one picture", 1, carrying[0].pictures.size)
        assertTrue(
            "and it is a data URL",
            carrying[0].pictures[0].startsWith("data:image/png;base64,"),
        )
        assertEquals("the role is the operator's, the only one allowed mid-array", "user", carrying[0].role)
    }

    @Test
    fun `a blind model is refused in words rather than sent an image it would ignore`() {
        picture(64, 64)
        val body = tools(sees = { false }).run(
            Call("c1", "look", """{"paths":["shot.png"]}"""),
        )

        assertTrue("a refusal: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue(
            "which says why: $body",
            body.contains("cannot see pictures"),
        )
        // **And nothing was written.** A refusal that had already attached the picture would leave a
        // note behind that the next request would honour.
        assertEquals("no see note", 0, log.rows().count { it.kind == AgentLog.NOTE && it.name == "see" })
    }

    @Test
    fun `a model that can see, and a tool set that was never told, both proceed`() {
        // **Null is not "no".** Every existing test builds tools without a model, and the app passes
        // the real answer. If null meant "cannot see", every one of those tests would break and the
        // default would be a refusal nobody asked for.
        picture(64, 64)
        assertTrue("told it can see", tools(sees = { true }).run(
            Call("c1", "look", """{"paths":["shot.png"]}"""),
        ).startsWith(AgentLog.SUCCESS))
        picture(64, 64, "second.png")
        assertTrue("never told", tools(sees = null).run(
            Call("c2", "look", """{"paths":["second.png"]}"""),
        ).startsWith(AgentLog.SUCCESS))
    }

    @Test
    fun `nothing usable is a refusal, not a success with a complaint`() {
        // A result beginning with `0` says something happened, and the turn then carries on as
        // though a picture had been seen. That is worse than being told no.
        val body = look("absent.png")

        assertTrue("a refusal: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("naming the file: $body", body.contains("absent.png"))
    }

    @Test
    fun `a partial success keeps the refusals beside it`() {
        // A model that asked for two and got one needs to know which. A silent partial success reads
        // as though both arrived, and the next turn reasons about a picture that was never sent.
        picture(64, 64, "here.png")
        val body = look("here.png", "gone.png")

        assertTrue("it worked, partly: $body", body.startsWith(AgentLog.SUCCESS))
        assertTrue("the one that arrived: $body", body.contains("here.png"))
        assertTrue("the one that did not: $body", body.contains("gone.png"))
        assertTrue("said as a refusal of that one: $body", body.contains("Not attached"))
    }

    @Test
    fun `an empty list is refused rather than attaching nothing`() {
        val body = tools().run(Call("c1", "look", """{"paths":[]}"""))
        assertTrue("a refusal: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue("which says the list was empty: $body", body.contains("empty"))
    }

    @Test
    fun `more pictures than the cap are refused, and the cap is named`() {
        val many = (1..(AgentTools.MAX_PICTURES + 1)).map { "p$it.png" }.toTypedArray()
        val body = look(*many)

        assertTrue("a refusal: $body", body.startsWith(AgentLog.FAILURE))
        assertTrue(
            "naming the cap: $body",
            body.contains(AgentTools.MAX_PICTURES.toString()),
        )
    }

    @Test
    fun `a path outside the account is refused by resolve, not read anyway`() {
        // `look` goes through the same `resolve` every other tool uses, so the one `..` the operator
        // allowed is the only way out — and a path beyond the account folder is refused here exactly
        // as `read` refuses it.
        val body = look("../../../../etc/hosts")
        assertTrue("a refusal: $body", body.startsWith(AgentLog.FAILURE))
    }

    @Test
    fun `look is in the tool list with a shape the model can follow`() {
        val spec = AgentTools.specs().firstOrNull { it.name == "look" }
        assertEquals("look is offered", "look", spec?.name)
        assertTrue(
            "and says the picture arrives next turn",
            spec!!.description.contains("next message"),
        )
        assertTrue(
            "and names the formats it takes",
            spec.description.contains("png"),
        )
    }

    @Test
    fun `a picture is charged by its pixels, not by its base64`() {
        // **The defect this test exists for.** A row's cost is normally the text of its body, which
        // is right for every row whose body the model reads. A `see` row's body is a data URL the
        // model never reads as text — it reads the picture — and base64 is roughly 4 characters per
        // 3 bytes, so measuring it charges tens of thousands of tokens for a picture that costs 765.
        // The budget would then believe one image had filled the context.
        picture(1024, 768)
        val body = look("shot.png")
        assertTrue("it worked: $body", body.startsWith(AgentLog.SUCCESS))

        val see = log.rows().first { it.kind == AgentLog.NOTE && it.name == "see" }
        // The body is genuinely large — otherwise this test would pass on a row that had nothing to
        // mismeasure, which is the fixture trap in miniature.
        assertTrue(
            "the body really is a big data URL: ${see.body.length} chars",
            see.body.length > 10_000,
        )
        assertEquals(
            "charged as a 1024x768 picture, not as 10k+ characters of base64",
            ContextBudget.pictureTokens(1024, 768),
            see.size,
        )
        assertTrue(
            "which is far less than the text would have cost: ${see.size} vs " +
                ContextBudget.tokens(see.body),
            see.size < ContextBudget.tokens(see.body) / 10,
        )
    }

    @Test
    fun `the picture still arrives when it is charged cheaply`() {
        // The other half: a cost that does not measure the body must not stop the body being sent.
        // A row could be charged 765 and still carry the whole URL, and if a later change "optimised"
        // the row by truncating what it stores, the picture would silently become a fragment.
        picture(64, 64)
        look("shot.png")

        val carrying = agent().messages(log.contextRows()).filter { it.pictures.isNotEmpty() }
        assertEquals("one message carries it", 1, carrying.size)
        assertTrue(
            "and the URL is complete: ${carrying[0].pictures[0].length} chars",
            carrying[0].pictures[0].length > 100,
        )
    }

    @Test
    fun `the message carrying the picture names the file it came from`() {
        // **The audit finding.** With the row storing only the data URL, a conversation with several
        // pictures had several identical sentences and no way to tell which file each one was — the
        // model would have to guess from the image's contents, which is exactly the guessing a path
        // removes.
        picture(64, 64, "first.png")
        picture(64, 64, "second.png")
        look("first.png")
        look("second.png")

        val carrying = agent().messages(log.contextRows()).filter { it.pictures.isNotEmpty() }
        assertEquals("two messages carry pictures", 2, carrying.size)
        assertTrue(
            "the first names its file: ${carrying[0].text}",
            carrying[0].text.contains("first.png"),
        )
        assertTrue(
            "the second names its own: ${carrying[1].text}",
            carrying[1].text.contains("second.png"),
        )
        assertFalse(
            "and neither names the other's: ${carrying[0].text}",
            carrying[0].text.contains("second.png"),
        )
    }

    @Test
    fun `one picture is said in the singular, several in the plural`() {
        // A sentence that said "the pictures you asked to see: a.png" for one picture is a sentence
        // nobody wrote on purpose.
        picture(64, 64, "one.png")
        look("one.png")
        val single = agent().messages(log.contextRows()).first { it.pictures.isNotEmpty() }
        assertTrue("singular: ${single.text}", single.text.contains("the picture you asked"))
        assertFalse("not plural: ${single.text}", single.text.contains("the pictures you asked"))
    }
}
