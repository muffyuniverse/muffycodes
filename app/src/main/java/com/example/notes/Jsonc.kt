package com.example.notes

import org.json.JSONException
import org.json.JSONObject

/**
 * **JSON with comments, read by a scanner rather than by a regular expression.**
 *
 * The operator asked for `.jsonc` for one reason: a request template should be able to explain itself
 * beside itself. `//` and `/* */` are the two comments JSONC allows, and `org.json` refuses both — so a
 * commented template is a template the app could not read, which is the same as a feature that does not
 * exist.
 *
 * ### Why this is a scanner and not a `replace`
 *
 * **Every address in this library contains `//`.** `"address": "https://openrouter.ai/api/v1"` — so a
 * stripping pass that does not track string state deletes the address and leaves a template that parses
 * and points nowhere: a failure that looks like a typo in a file rather than a bug in the reader. The
 * rules below are therefore about state, and each one is a test:
 *
 * - Inside a string, `//` and `/*` are text. Outside, they start a comment.
 * - `\"` inside a string does not end it, and `\\` does not begin an escape (so `"a\\"` is a complete
 *   string holding one backslash).
 * - A line comment ends at a newline; a block comment ends at `*/`; **an unterminated block comment is
 *   refused by the line it opened on**, because that is the one thing a scanner knows that a parser
 *   would not.
 *
 * ### Why the comments become spaces
 *
 * A comment is replaced by **whitespace of the same length**, not removed. Two things follow, and both
 * are the reason: the character offsets `org.json` reports still map to the line a person is looking at
 * (which `Schema.whereIn` turns into a line number), and a block comment's newlines survive, so every
 * line after it keeps its number.
 *
 * ### What this deliberately is not
 *
 * No trailing commas of its own (Android's `JSONObject` already tolerates them), no `#` comments, no
 * unquoted keys, no single quotes. JSONC is JSON plus two comment forms; a reader that accepted more
 * would be a third dialect the servers never see.
 */
object Jsonc {

    /**
     * [text] with its comments replaced by whitespace, or the sentence saying why it cannot be scanned.
     *
     * Refuses exactly one thing — an unterminated block comment — and leaves everything else to the
     * parser, which has better sentences than a scanner could invent.
     */
    fun strip(text: String): Result<String> {
        val out = StringBuilder(text.length)
        var at = 0
        while (at < text.length) {
            val c = text[at]
            when {
                c == '"' -> {
                    // **The string, copied verbatim** — the one region where a slash is punctuation.
                    out.append(c)
                    at++
                    while (at < text.length) {
                        val one = text[at]
                        out.append(one)
                        if (one == '\\' && at + 1 < text.length) {
                            // The escaped character, whatever it is, is text and is skipped with the
                            // backslash so a `\"` cannot close the string.
                            out.append(text[at + 1])
                            at += 2
                            continue
                        }
                        at++
                        if (one == '"') break
                    }
                }
                c == '/' && at + 1 < text.length && text[at + 1] == '/' -> {
                    while (at < text.length && text[at] != '\n') {
                        out.append(' ')
                        at++
                    }
                }
                c == '/' && at + 1 < text.length && text[at + 1] == '*' -> {
                    val opened = lineAt(text, at)
                    out.append("  ")
                    at += 2
                    var closed = false
                    while (at < text.length) {
                        if (text[at] == '*' && at + 1 < text.length && text[at + 1] == '/') {
                            out.append("  ")
                            at += 2
                            closed = true
                            break
                        }
                        // **A newline stays a newline**, so every line after the comment keeps its number.
                        out.append(if (text[at] == '\n') '\n' else ' ')
                        at++
                    }
                    if (!closed) {
                        return Result.failure(
                            IllegalArgumentException("line $opened: the /* here is never closed"),
                        )
                    }
                }
                else -> {
                    out.append(c)
                    at++
                }
            }
        }
        return Result.success(out.toString())
    }

    /** The document [text] describes, comments and all. */
    fun parse(text: String): Result<JSONObject> {
        val plain = strip(text).getOrElse { return Result.failure(it) }
        return try {
            Result.success(JSONObject(plain))
        } catch (e: JSONException) {
            Result.failure(IllegalArgumentException(whereIn(text, e)))
        }
    }

    /** The 1-based line [at] falls on. */
    private fun lineAt(text: String, at: Int): Int = text.take(at).count { it == '\n' } + 1

    /**
     * Where a syntax error is, in a line.
     *
     * The same conversion `Schema` does, kept here because every JSONC document is
     * read through this object — a person editing a file needs a line, not a character offset.
     */
    fun whereIn(text: String, error: JSONException): String {
        val message = error.message.orEmpty().ifBlank { "it is not valid JSON" }
        Regex("""line (\d+)""").find(message)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.let { return "line $it: $message" }
        val offset = Regex("""character (\d+)""").find(message)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: Regex("""\bat (\d+)""").find(message)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: return message
        val line = text.take(offset.coerceIn(0, text.length)).count { it == '\n' } + 1
        return "line $line: $message"
    }
}
