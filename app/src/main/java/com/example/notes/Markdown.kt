package com.example.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * A Markdown reader for the editor's **view** mode.
 *
 * A `.md` file is the one kind of text where the raw bytes are not what a person
 * wants to read: they wrote headings and lists, and showing them `## A heading`
 * makes the file look like it is still being typed rather than finished.
 *
 * This is a small subset on purpose — headings, paragraphs, lists, quotes, code,
 * rules, and the four inline styles people actually type. It is not CommonMark,
 * and it does not pretend to be: anything it does not understand is shown exactly
 * as written, which is the one behaviour that can never lose a reader's content.
 * A renderer brought in as a dependency would bring its own idea of what to do
 * with the parts it does not support, and a version of it that disagreed with this
 * Compose version would break the build rather than a paragraph.
 */
object Markdown {

    /** One block of a Markdown document. */
    sealed interface Block {
        data class Heading(val level: Int, val text: String) : Block
        data class Paragraph(val text: String) : Block
        /** A list, with the marker kept so a numbered list keeps its numbers. */
        data class Item(val marker: String, val text: String, val depth: Int) : Block
        data class Code(val language: String, val text: String) : Block
        data class Quote(val text: String) : Block
        data object Rule : Block
        /**
         * A picture that stands on its own line: `![a cat](cat.png)`.
         *
         * [alt] is what a screen reader says and what is shown when the file cannot
         * be read, so a picture that is missing is still a sentence about a picture
         * rather than a gap.
         */
        data class Image(val alt: String, val source: String) : Block
    }

    /**
     * Reads [text] into blocks.
     *
     * A line it does not recognise becomes a paragraph, which is what Markdown
     * itself says: there is no such thing as a line that is not text.
     */
    fun parse(text: String): List<Block> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = mutableListOf<Block>()
        val paragraph = StringBuilder()
        var i = 0

        fun flush() {
            if (paragraph.isNotEmpty()) {
                blocks += Block.Paragraph(paragraph.toString().trim())
                paragraph.setLength(0)
            }
        }

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()

            // A fence, with any language after it. Everything up to the closing
            // fence is code, including lines that look like headings or lists.
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                flush()
                val marker = trimmed.take(3)
                val language = trimmed.drop(3).trim()
                val body = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trim().startsWith(marker)) {
                    body.appendLine(lines[i])
                    i++
                }
                // Skip the closing fence when there is one.
                if (i < lines.size) i++
                blocks += Block.Code(language, body.toString().trimEnd())
                continue
            }

            if (trimmed.isEmpty()) {
                flush()
                i++
                continue
            }

            if (isRule(trimmed)) {
                flush()
                blocks += Block.Rule
                i++
                continue
            }

            val head = heading(trimmed)
            if (head != null) {
                flush()
                blocks += Block.Heading(head.first, head.second)
                i++
                continue
            }

            if (trimmed.startsWith(">")) {
                flush()
                val quoted = mutableListOf<String>()
                while (i < lines.size && lines[i].trim().startsWith(">")) {
                    quoted += lines[i].trim().removePrefix(">").trim()
                    i++
                }
                blocks += Block.Quote(quoted.joinToString(" "))
                continue
            }

            // A picture on its own line. Inline images are left exactly as written,
            // because a picture inside a paragraph is a layout this reader does not
            // do — and showing the words the author typed is never wrong.
            val picture = image(trimmed)
            if (picture != null) {
                flush()
                blocks += picture
                i++
                continue
            }

            val entry = item(line)
            if (entry != null) {
                flush()
                blocks += Block.Item(entry.first, entry.second, entry.third)
                i++
                continue
            }

            if (paragraph.isNotEmpty()) paragraph.append(' ')
            paragraph.append(trimmed)
            i++
        }
        flush()
        return blocks
    }

    /**
     * `![alt](address)` as a whole line, or null.
     *
     * The parentheses are matched to the *last* `)` on the line so that a path or
     * address with a bracket in it still reads as one picture rather than a picture
     * plus a loose bracket.
     */
    private fun image(line: String): Block.Image? {
        if (!line.startsWith("![")) return null
        val close = line.indexOf(']', 2)
        if (close < 0) return null
        if (close + 1 >= line.length || line[close + 1] != '(') return null
        if (!line.endsWith(")")) return null
        val alt = line.substring(2, close)
        var source = line.substring(close + 2, line.length - 1).trim()
        // `![a](cat.png "A cat")` — the title is not the address.
        val quoted = source.indexOf('"')
        if (quoted > 0 && source.endsWith("\"")) source = source.substring(0, quoted).trim()
        if (source.isEmpty()) return null
        return Block.Image(alt.ifBlank { source.substringAfterLast('/') }, source)
    }

    /** `# Title` … `###### Title`, or null when the line is not a heading. */
    private fun heading(line: String): Pair<Int, String>? {
        val hashes = line.takeWhile { it == '#' }.length
        if (hashes == 0 || hashes > 6) return null
        val rest = line.drop(hashes)
        // `#hashtag` is not a heading: a heading needs a space after the hashes,
        // or to be the hashes alone.
        if (rest.isNotEmpty() && !rest.startsWith(" ")) return null
        return hashes to rest.trim().trimEnd('#').trim()
    }

    /** A thematic break: three or more of `-`, `*` or `_`, and nothing else. */
    private fun isRule(line: String): Boolean {
        val body = line.replace(" ", "")
        if (body.length < 3) return false
        return body.all { it == '-' } || body.all { it == '*' } || body.all { it == '_' }
    }

    /**
     * A list item: an indent, a marker, and a space.
     *
     * The indent is kept as a depth of two spaces, so a nested list is indented
     * under the item it belongs to instead of starting again at the left margin.
     */
    private fun item(line: String): Triple<String, String, Int>? {
        // A tab counts as four spaces, and only the leading whitespace matters.
        val leading = line.takeWhile { it == ' ' || it == '\t' }
        val indent = leading.sumOf { if (it == '\t') 4 else 1 }
        val body = line.drop(leading.length)
        val bullet = body.firstOrNull()?.takeIf { it == '-' || it == '*' || it == '+' }
        if (bullet != null) {
            val rest = body.drop(1)
            if (!rest.startsWith(" ")) return null
            return Triple(bullet.toString(), rest.trim(), indent / 2)
        }
        val digits = body.takeWhile { it.isDigit() }
        if (digits.isEmpty() || digits.length > 3) return null
        val rest = body.drop(digits.length)
        if (!rest.startsWith(".") && !rest.startsWith(")")) return null
        val after = rest.drop(1)
        if (!after.startsWith(" ")) return null
        return Triple("$digits.", after.trim(), indent / 2)
    }

    /**
     * One line of Markdown as styled text.
     *
     * Code spans are found first and never interpreted again, so `` `**x**` `` is
     * written with its asterisks. Everything else is matched in one pass, which is
     * why the nesting people actually write (`**bold with `code`**`) resolves.
     */
    fun inline(text: String, linkColour: Color = Color.Unspecified): AnnotatedString =
        buildAnnotatedString { appendInline(this, text, linkColour) }

    /**
     * Whether a delimiter at [at] can *open* emphasis.
     *
     * A marker followed by a space is not emphasis in any Markdown — `2 * 3 * 4` is
     * arithmetic, not italics — and treating it as emphasis silently ate both
     * asterisks and left `2  3  4`. The rule is CommonMark's: the character after the
     * opening delimiter is not whitespace, and the one before the closing delimiter
     * is not either.
     */
    private fun opens(text: String, at: Int, width: Int): Boolean =
        at + width < text.length && !text[at + width].isWhitespace()

    private fun closes(text: String, at: Int): Boolean =
        at > 0 && !text[at - 1].isWhitespace()

    private fun appendInline(out: AnnotatedString.Builder, text: String, linkColour: Color) {
        var i = 0
        val plain = StringBuilder()

        fun flushPlain() {
            if (plain.isNotEmpty()) {
                out.append(plain.toString())
                plain.setLength(0)
            }
        }

        while (i < text.length) {
            val ch = text[i]
            when {
                ch == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) {
                        flushPlain()
                        out.withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        plain.append(ch); i++
                    }
                }

                (ch == '*' || ch == '_') && text.startsWith("${ch}${ch}", i) -> {
                    val end = text.indexOf("${ch}${ch}", i + 2)
                    if (end > i + 1 && opens(text, i, 2) && closes(text, end)) {
                        flushPlain()
                        out.withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            appendInline(this, text.substring(i + 2, end), linkColour)
                        }
                        i = end + 2
                    } else {
                        plain.append(ch); i++
                    }
                }

                ch == '~' && text.startsWith("~~", i) -> {
                    val end = text.indexOf("~~", i + 2)
                    if (end > i + 1 && opens(text, i, 2) && closes(text, end)) {
                        flushPlain()
                        out.withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        plain.append(ch); i++
                    }
                }

                ch == '*' || ch == '_' -> {
                    val end = text.indexOf(ch, i + 1)
                    if (end > i + 1 && opens(text, i, 1) && closes(text, end)) {
                        flushPlain()
                        out.withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            appendInline(this, text.substring(i + 1, end), linkColour)
                        }
                        i = end + 1
                    } else {
                        plain.append(ch); i++
                    }
                }

                ch == '[' -> {
                    // `[words](address)` — the words are shown, the address is
                    // shown too. There is no browser here, and a link that looks
                    // tappable and does nothing is worse than one that says where
                    // it would have gone.
                    val close = text.indexOf(']', i + 1)
                    val open = if (close > i && close + 1 < text.length && text[close + 1] == '(') {
                        text.indexOf(')', close + 2)
                    } else {
                        -1
                    }
                    if (close > i && open > close) {
                        flushPlain()
                        val words = text.substring(i + 1, close)
                        val address = text.substring(close + 2, open)
                        out.withStyle(
                            SpanStyle(
                                textDecoration = TextDecoration.Underline,
                                color = if (linkColour == Color.Unspecified) Color(0xFF1565C0)
                                else linkColour,
                            ),
                        ) { appendInline(this, words, linkColour) }
                        out.withStyle(
                            SpanStyle(
                                fontFamily = FontFamily.Monospace,
                                color = if (linkColour == Color.Unspecified) Color(0xFF1565C0)
                                else linkColour,
                            ),
                        ) { append(" ($address)") }
                        i = open + 1
                    } else {
                        plain.append(ch); i++
                    }
                }

                else -> {
                    plain.append(ch); i++
                }
            }
        }
        flushPlain()
    }
}

/**
 * The document, drawn.
 *
 * The spacing here is the whole of what makes rendered Markdown look different
 * from raw text: a heading is bigger and has air above it, a list item hangs under
 * its bullet, and code is in a box because a line of indented text and a line of
 * text are otherwise the same thing on a phone.
 */
@Composable
fun MarkdownBody(
    text: String,
    modifier: Modifier = Modifier,
    /**
     * The folder the document lives in, so a relative `![](cat.png)` means the
     * picture beside the file. Null means there is nowhere to look, and the reader
     * says so instead of drawing an empty frame.
     */
    baseDir: File? = null,
) {
    val blocks = Markdown.parse(text)
    val link = MaterialTheme.colorScheme.primary
    Column(modifier = modifier) {
        blocks.forEach { block ->
            when (block) {
                is Markdown.Block.Heading -> Text(
                    text = Markdown.inline(block.text, link),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.headlineSmall
                        2 -> MaterialTheme.typography.titleLarge
                        3 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )

                is Markdown.Block.Paragraph -> Text(
                    text = Markdown.inline(block.text, link),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(vertical = 4.dp),
                )

                is Markdown.Block.Item -> Text(
                    text = Markdown.inline("${block.marker}  ${block.text}", link),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = (block.depth * 16).dp, top = 2.dp, bottom = 2.dp),
                )

                is Markdown.Block.Quote -> Text(
                    text = Markdown.inline(block.text, link),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )

                is Markdown.Block.Code -> Text(
                    text = AnnotatedString(block.text),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .padding(vertical = 6.dp)
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(12.dp)
                        // Code is the one kind of text whose line breaks are its
                        // meaning, so it scrolls sideways instead of wrapping.
                        .horizontalScroll(rememberScrollState()),
                )

                Markdown.Block.Rule -> HorizontalDivider(
                    modifier = Modifier.padding(vertical = 10.dp),
                )

                is Markdown.Block.Image -> MarkdownPicture(
                    alt = block.alt,
                    source = block.source,
                    baseDir = baseDir,
                )
            }
        }
    }
}

/**
 * A picture in a Markdown document, read off this phone.
 *
 * **The address is resolved against the document, not against the app.** `![](cat.png)`
 * means the `cat.png` beside the file the person is reading, which is the only
 * reading that makes sense on a phone where there is no web server.
 *
 * **A picture that cannot be read says so, in words the author wrote.** The alt text
 * and the address are shown instead of an empty frame: an image that silently fails
 * to appear is a bug the reader cannot tell from a broken screen, and an `http`
 * address is not something this app fetches at all — saying that is more honest than
 * a blank space where a picture was promised.
 */
@Composable
private fun MarkdownPicture(alt: String, source: String, baseDir: File?) {
    var image by remember(source, baseDir) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var missing by remember(source, baseDir) { mutableStateOf(false) }
    val file = remember(source, baseDir) { resolvePicture(source, baseDir) }

    LaunchedEffect(file) {
        if (file == null || !file.isFile) {
            missing = true
            return@LaunchedEffect
        }
        val decoded = Pictures.decode(file)
        if (decoded == null) missing = true else image = decoded
    }

    val bitmap = image
    when {
        bitmap != null -> androidx.compose.foundation.Image(
            bitmap = bitmap,
            contentDescription = alt,
            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
        )

        missing -> Text(
            text = "$alt — \"$source\" could not be read here",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp),
        )

        else -> Text(
            text = alt,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp),
        )
    }
}

/**
 * Where a document's picture lives: beside the document, unless it says otherwise.
 *
 * An `http` address answers null on purpose. Nothing in this app opens a socket to
 * draw a paragraph, and a reader that quietly did would turn opening a note into a
 * network request.
 */
internal fun resolvePicture(source: String, baseDir: File?): File? {
    if (source.startsWith("http://") || source.startsWith("https://")) return null
    if (source.startsWith("file://")) return File(source.removePrefix("file://"))
    val plain = source.replace("%20", " ")
    val named = File(plain)
    if (named.isAbsolute) return named
    return baseDir?.let { File(it, plain) }
}
