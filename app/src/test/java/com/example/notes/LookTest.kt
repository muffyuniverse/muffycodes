package com.example.notes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.Base64

/**
 * Turning a file into something a model can see.
 *
 * **Robolectric, because `BitmapFactory` is the platform's decoder.** The file's *name* is checked
 * before it is opened, so most of the refusals need no decoder at all — but the success path does,
 * and a test that could only run on a phone is a test that runs by hand or not at all.
 *
 * The arithmetic is [ContextBudget]'s and is tested there; what is tested here is that a real file
 * goes in and a real `data:` URL comes out **at the size the budget was told about** — because the
 * whole point of resizing in the app is that the number shown and the picture sent are the same
 * picture.
 *
 * **There is no test here for "a file that will not decode", and that is a limitation rather than an
 * oversight.** Robolectric's `BitmapFactory` is a shadow that answers a bitmap for any bytes it is
 * given, so a broken file cannot be made to fail here — a test that claimed to cover it would pass
 * for the wrong reason and would keep passing if the real decoder's failure path broke. The branch
 * is three lines and its behaviour on a phone is "null, so refuse", which `Look.read` returns as a
 * `LookFailed` the same way the name check does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LookTest {

    @get:Rule
    val folder = TemporaryFolder()

    /** A solid-colour PNG of exactly [width]×[height], written into the temporary folder. */
    private fun picture(width: Int, height: Int, name: String = "shot.png"): File {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(20, 120, 200))
        val file = File(folder.root, name)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    /** The bytes a `data:` URL carries, decoded back. */
    private fun bytesOf(url: String): ByteArray =
        Base64.getDecoder().decode(url.substringAfter("base64,"))

    @Test
    fun `a png becomes a data url at the size it was sent at`() {
        val file = picture(1024, 768)
        val seen = Look.read(file).getOrThrow()

        assertTrue("a png data url", seen.url.startsWith("data:image/png;base64,"))
        assertEquals("and it was not resized", 1024, seen.width)
        assertEquals("either dimension", 768, seen.height)
        assertEquals("so it costs what a 1024x768 costs", 765L, seen.tokens)
    }

    @Test
    fun `a big picture is shrunk to the size the provider would charge for anyway`() {
        // 4000x3000 is 48 MB decoded and would be 8,245 tokens sent as-is. After the two resize steps
        // it is 1024x768: four tiles, 765. **The picture sent and the number reported are the same
        // picture**, which is the whole reason the resize happens here rather than at the provider.
        val file = picture(4000, 3000, "photo.png")
        val seen = Look.read(file).getOrThrow()

        assertEquals("the width the budget was told about", 1024, seen.width)
        assertEquals("and the height", 768, seen.height)
        assertEquals("so the charge matches the picture", 765L, seen.tokens)
        assertEquals(
            "and the budget agrees with the picture that was actually made",
            seen.tokens,
            ContextBudget.pictureTokens(seen.width, seen.height),
        )
    }

    @Test
    fun `a portrait picture keeps its height and costs more`() {
        // The case the design's Q1 correction was about: the shortest side is pinned, so a tall
        // picture keeps its height and needs more tiles. 768x2048 is 1,445, not 765.
        val file = picture(768, 2048, "tall.png")
        val seen = Look.read(file).getOrThrow()

        assertEquals("untouched, the short side is already 768", 768, seen.width)
        assertEquals("and the height survives", 2048, seen.height)
        assertEquals("eight tiles", 1_445L, seen.tokens)
    }

    @Test
    fun `a format the model may not take is refused before the file is opened`() {
        // **The name is checked first.** A .bmp decodes perfectly well and would be refused by the
        // provider, so catching it here turns a confusing failure at the far end of a request into a
        // sentence the agent can act on. The file is not even a picture, which is the point: the
        // refusal comes from the name alone.
        val file = File(folder.root, "diagram.bmp")
        file.writeText("not a picture at all")

        val failure = Look.read(file).exceptionOrNull() as Look.LookFailed
        assertTrue(
            "the refusal names what it takes: ${failure.said}",
            failure.said.contains("png") && failure.said.contains("bmp"),
        )
    }

    @Test
    fun `a file with no extension says so rather than showing an empty list`() {
        val file = File(folder.root, "screenshot")
        file.writeText("x")
        val failure = Look.read(file).exceptionOrNull() as Look.LookFailed
        assertTrue("named as having none: ${failure.said}", failure.said.contains("no extension"))
    }

    @Test
    fun `a path with no file behind it is refused`() {
        val failure = Look.read(File(folder.root, "absent.png")).exceptionOrNull() as Look.LookFailed
        assertTrue("names the file: ${failure.said}", failure.said.contains("absent.png"))
    }

    @Test
    fun `the data url really carries the picture`() {
        // Not just the prefix: the payload decodes to bytes that start like a PNG. A URL that said
        // `data:image/png` and carried nothing would satisfy every assertion above.
        val file = picture(64, 64, "small.png")
        val seen = Look.read(file).getOrThrow()

        val bytes = bytesOf(seen.url)
        assertTrue("decodes to something", bytes.isNotEmpty())
        assertEquals("PNG magic, first byte", 0x89.toByte(), bytes[0])
        assertEquals("PNG magic, second", 'P'.code.toByte(), bytes[1])
        assertEquals("PNG magic, third", 'N'.code.toByte(), bytes[2])
        assertEquals("PNG magic, fourth", 'G'.code.toByte(), bytes[3])
    }

    @Test
    fun `every promised extension is accepted`() {
        // The promise in the tool's own description. A format listed there and refused here is a
        // description that lies to the model on every request.
        for (extension in Look.EXTENSIONS) {
            val file = picture(32, 32, "one.$extension")
            assertNotNull(
                "a .$extension must be readable",
                Look.read(file).getOrNull(),
            )
        }
    }

    // ---- a blank picture, which is what `+ New image` writes -------------------------------------

    @Test
    fun `a blank canvas is a real png at exactly its size`() {
        // **"Real" is the assertion.** A file with a `.png` name and no decodable body is the one
        // failure that looks like success until somebody opens it, so the bytes are decoded back and
        // measured rather than trusted.
        val canvas = Look.CANVASES.first { it.label == "Square" }
        val bytes = Look.blank(canvas)
        assertTrue("it wrote something", bytes.size > 100)

        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull("and it decodes", decoded)
        assertEquals("at the width asked for", canvas.width, decoded!!.width)
        assertEquals("and the height", canvas.height, decoded.height)
        decoded.recycle()
    }

    @Test
    fun `a blank canvas is white, so it can be drawn on in any theme`() {
        // A transparent PNG over a dark theme is a black rectangle, and a person cannot tell whether
        // they are drawing on nothing or the app failed to draw.
        val canvas = Look.CANVASES.first { it.label == "Square" }
        val decoded = BitmapFactory.decodeByteArray(Look.blank(canvas), 0, Look.blank(canvas).size)!!
        assertEquals("white, not transparent", android.graphics.Color.WHITE, decoded.getPixel(0, 0))
        assertEquals("everywhere", android.graphics.Color.WHITE, decoded.getPixel(100, 100))
        decoded.recycle()
    }

    @Test
    fun `the default canvas is the phone screenshot, and it costs what it costs`() {
        // The operator's choice, and the numbers that make it a decision: the default is the shape
        // already in the person's hand, and it is 1,105 tokens rather than a square's 765.
        assertEquals("the default", "Phone screenshot", Look.DEFAULT_CANVAS.label)
        assertEquals("1080x1920", 1080, Look.DEFAULT_CANVAS.width)
        assertEquals("and 1,105 tokens, not 765", 1_105L, Look.DEFAULT_CANVAS.tokens)
        assertEquals(
            "the square is the cheaper one, which is why the dialogue shows both",
            765L,
            Look.CANVASES.first { it.label == "Square" }.tokens,
        )
    }

    @Test
    fun `every offered canvas is one the provider charges a sane amount for`() {
        // A size that would be refused, or that cost tens of thousands of tokens, must not be offered.
        for (canvas in Look.CANVASES) {
            val tokens = canvas.tokens
            assertTrue(
                "${canvas.label} costs $tokens, which is not a picture's price",
                tokens in 200L..2_000L,
            )
        }
    }

    // ---- making a picture, which is what `+ New image` writes -----------------------------------
    //
    // **Here rather than in `WorkspaceTest`, because `Look.blank` makes a `Bitmap`.** A plain-JVM test
    // would throw at the first line of the writer and every assertion would be about an exception
    // rather than about a picture.

    private fun space(): Workspace {
        val account = File(folder.root, "account-${System.nanoTime()}")
        val ws = Workspace(account)
        ws.ensure()
        return ws
    }

    @Test
    fun `a picture is made with a png extension even when the name has none`() {
        val ws = space()
        val made = ws.createImage(ws.accountDir, "sketch", Look.CANVASES.first()).getOrThrow()
        assertEquals("the extension was added", "sketch.png", made.name)
    }

    @Test
    fun `a name that already ends in png is not given a second one`() {
        val ws = space()
        val made = ws.createImage(ws.accountDir, "sketch.png", Look.CANVASES.first()).getOrThrow()
        assertEquals("untouched", "sketch.png", made.name)
    }

    @Test
    fun `a picture refuses a name that is taken, in the same words a file does`() {
        // The refusals are `createFile`'s, asked in the same order, because a person does not think of
        // "new file" and "new picture" as different permissions.
        val ws = space()
        ws.createImage(ws.accountDir, "drawing.png", Look.CANVASES.first()).getOrThrow()
        val second = ws.createImage(ws.accountDir, "drawing.png", Look.CANVASES.first())

        assertTrue("refused", second.isFailure)
        assertTrue(
            "with the same sentence: ${second.exceptionOrNull()?.message}",
            second.exceptionOrNull()?.message.orEmpty().contains("already"),
        )
    }

    @Test
    fun `a person's lock does not stop the person making a picture there`() {
        // **The operator's two locks, and this is the one people get wrong.** *"system locks restrict
        // both users and agents, and user locks restrict agents (not users themselves!)"* — so a
        // folder the person locked still accepts a new picture from that person, and it is the
        // *agent* that is refused. Asserting a refusal here would have encoded the opposite rule, and
        // the test would have passed against a writer that took a lever away from its own operator.
        val ws = space()
        val folder = File(ws.accountDir, "mine")
        folder.mkdirs()
        val relative = ws.relative(folder) ?: error("the folder must be inside the account")
        ws.setLocked(setOf(ws.pathString(relative)))

        // The flag really is set — otherwise the rest of this test proves nothing.
        assertTrue("the folder is locked, as the person asked", ws.isLockedHere(folder))
        val made = ws.createImage(folder, "drawing.png", Look.CANVASES.first())
        assertTrue("made, not refused: ${made.exceptionOrNull()?.message}", made.isSuccess)
        // And the agent is the one held back — the same lock, read by the other question.
        assertTrue("the agent is refused", ws.isAgentLocked(File(folder, "other.png")))
    }

    @Test
    fun `the picture a person makes is one the editor will open`() {
        // The end of the chain, as one assertion: a new picture is `EntryKind.IMAGE`, which is what
        // makes the editor show it as a picture rather than refusing it as unknown.
        val ws = space()
        val made = ws.createImage(ws.accountDir, "drawing", Look.CANVASES.first()).getOrThrow()
        assertEquals("shown as a picture", EntryKind.IMAGE, ws.kindOf(made))
    }
}
