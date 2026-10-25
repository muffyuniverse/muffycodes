package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Markdown reader, as a reader.
 *
 * These are the questions a person would ask of the file they wrote — is my
 * heading a heading, does a bullet stay a bullet, is code left alone — asked of the
 * parser rather than of the pixels, because the parser is where a wrong answer
 * loses content and the pixels only decide how loud it is.
 */
class MarkdownTest {

    @Test
    fun `headings are headings, and a hash without a space is not`() {
        val blocks = Markdown.parse("# Title\n\n## Smaller\n\n#notaheading")
        assertEquals(
            listOf(
                Markdown.Block.Heading(1, "Title"),
                Markdown.Block.Heading(2, "Smaller"),
                Markdown.Block.Paragraph("#notaheading"),
            ),
            blocks,
        )
    }

    @Test
    fun `a picture on its own line is a picture, with its words and its address`() {
        val blocks = Markdown.parse("before\n\n![a cat](cat.png)\n\nafter")
        assertEquals(
            listOf(
                Markdown.Block.Paragraph("before"),
                Markdown.Block.Image("a cat", "cat.png"),
                Markdown.Block.Paragraph("after"),
            ),
            blocks,
        )
    }

    @Test
    fun `a title after the address is not part of the address`() {
        val blocks = Markdown.parse("![a cat](pictures/cat.png \"The cat\")")
        assertEquals(listOf(Markdown.Block.Image("a cat", "pictures/cat.png")), blocks)
    }

    @Test
    fun `a picture with no words falls back to the file's name`() {
        // Alt text is what a screen reader says and what is shown when the picture
        // cannot be read, so an empty one would leave a person with nothing at all.
        val blocks = Markdown.parse("![](pictures/cat.png)")
        assertEquals(listOf(Markdown.Block.Image("cat.png", "pictures/cat.png")), blocks)
    }

    @Test
    fun `a picture inside a sentence is left as it was written`() {
        // The falsification: a parser that turned every `![` into a picture would
        // swallow the sentence around it. This reader draws whole-line pictures only,
        // and shows the rest exactly as the author typed it.
        val blocks = Markdown.parse("see ![a cat](cat.png) here")
        assertEquals(listOf(Markdown.Block.Paragraph("see ![a cat](cat.png) here")), blocks)
    }

    @Test
    fun `a picture is looked for beside the document, and an address is not a file`() {
        val folder = java.io.File(System.getProperty("java.io.tmpdir"), "md-${System.nanoTime()}")
        folder.mkdirs()
        try {
            assertEquals(
                java.io.File(folder, "cat.png"),
                resolvePicture("cat.png", folder),
            )
            assertEquals(
                "a path with spaces is a path",
                java.io.File(folder, "my cat.png"),
                resolvePicture("my%20cat.png", folder),
            )
            assertEquals(
                "nothing is fetched over the network",
                null,
                resolvePicture("https://example.com/cat.png", folder),
            )
            assertEquals(
                "and with nowhere to look there is no picture",
                null,
                resolvePicture("cat.png", null),
            )
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun `a paragraph is the lines that belong together`() {
        val blocks = Markdown.parse("one\nline\n\nand another")
        assertEquals(
            listOf(
                Markdown.Block.Paragraph("one line"),
                Markdown.Block.Paragraph("and another"),
            ),
            blocks,
        )
    }

    @Test
    fun `bullets keep their marker and their depth`() {
        val blocks = Markdown.parse("- milk\n- bread\n  - wholemeal\n\n1. first\n2. second")
        assertEquals(
            listOf(
                Markdown.Block.Item("-", "milk", 0),
                Markdown.Block.Item("-", "bread", 0),
                Markdown.Block.Item("-", "wholemeal", 1),
                Markdown.Block.Item("1.", "first", 0),
                Markdown.Block.Item("2.", "second", 0),
            ),
            blocks,
        )
    }

    @Test
    fun `code is left exactly as it was written`() {
        // The whole point of a fence: what is inside it is not Markdown, and a
        // reader that "helpfully" rendered `# not a heading` would be showing
        // something the author did not write.
        val blocks = Markdown.parse("```kotlin\n# not a heading\n- not a list\n```")
        assertEquals(
            listOf(Markdown.Block.Code("kotlin", "# not a heading\n- not a list")),
            blocks,
        )
    }

    @Test
    fun `a quote and a rule are told apart from text`() {
        val blocks = Markdown.parse("> said\n> twice\n\n---\n\nafter")
        assertEquals(
            listOf(
                Markdown.Block.Quote("said twice"),
                Markdown.Block.Rule,
                Markdown.Block.Paragraph("after"),
            ),
            blocks,
        )
    }

    @Test
    fun `a line it does not understand is shown as written`() {
        // The one behaviour that can never lose a reader's content.
        val blocks = Markdown.parse("| a | b |\n|---|---|")
        assertEquals(listOf(Markdown.Block.Paragraph("| a | b | |---|---|")), blocks)
    }

    @Test
    fun `emphasis and code become styling, and the markers go`() {
        val text = Markdown.inline("**bold** and *italic* and `code` and ~~gone~~").text
        assertEquals("bold and italic and code and gone", text)
    }

    @Test
    fun `code inside emphasis is not read as emphasis`() {
        // `**a `b` c**` is what people write; the backticks win.
        val text = Markdown.inline("**a `b*c` c**").text
        assertEquals("a b*c c", text)
    }

    @Test
    fun `a link shows its words and where it goes`() {
        // There is no browser in this app, and a link that looks tappable and does
        // nothing is worse than one that says where it would have gone.
        val text = Markdown.inline("see [the notes](https://example.com/x)").text
        assertTrue(text, text.contains("the notes"))
        assertTrue(text, text.contains("https://example.com/x"))
    }

    @Test
    fun `an unmatched marker stays a character, not a broken style`() {
        assertEquals("2 * 3 * 4", Markdown.inline("2 * 3 * 4").text)
        assertEquals("a lone ` tick", Markdown.inline("a lone ` tick").text)
    }

    @Test
    fun `the app's own commit messages are told apart from a person's`() {
        assertTrue(appVoice("Edited milk.txt"))
        assertTrue(appVoice("Moved a into b/"))
        // **A save's message is one word and still the app's.** A prefix test on
        // "Saved " would have missed it and printed "Saved" on every row of every
        // history as though a person had typed it.
        assertTrue(appVoice(CommitMessage.SAVE))
        assertFalse(appVoice("Added the recipes for the week"))
    }


}
