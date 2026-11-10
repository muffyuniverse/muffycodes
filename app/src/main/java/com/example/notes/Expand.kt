package com.example.notes

import java.io.File

/**
 * **`{{…}}` in a request, expanded before it is sent.**
 *
 * The operator's design, 2026-11-09: *"an http command is just a tool http with header and body
 * arguments or even better if as one complete argument, that can be written directly or take a path to
 * a file, and all {{}} paths and secrets inside that argument get expanded, recursively."*
 *
 * So a request is a document, and two things in it may be written as a name rather than as a value:
 *
 * | written | means |
 * |---|---|
 * | `{{file:notes/a.md}}` | the bytes of that file, as text — a path inside the project |
 * | `{{secret:github}}` | the value of a vault row called `github`, **only for this request's host** |
 *
 * ### Why the prefix is written out
 *
 * A bare `{{notes/a.md}}` cannot say whether it names a file or a secret, and a reader of the file
 * cannot tell either. The prefix costs four characters and removes the question — and it keeps a
 * *typo* from becoming a silent substitution of the wrong thing.
 *
 * ### Recursion, and why it terminates
 *
 * A file may hold placeholders of its own — a body cut from a template, a header list that names a
 * secret — so an expansion is repeated on the result. Two rules stop it running away:
 *
 * - **A name already expanded is refused rather than expanded again.** A file that includes itself
 *   would otherwise recurse until the phone ran out of memory, and the sentence names the loop.
 * - **A depth limit** for a chain that never repeats a name but never ends (a file including `b`,
 *   including `c`, …). Ten is far more than any honest document needs and is a bound a person can
 *   reason about.
 *
 * ### What a secret may not do
 *
 * **A secret cannot name the address.** The vault binds a value to one host, and the host is read
 * from the request's own URL — so a URL holding a secret would be the vault deciding its own
 * destination, which is the exfiltration primitive the binding exists to prevent. It is refused in
 * words, at the one place that can say it.
 */
object Expand {

    /** How many rounds of substitution a document may have. */
    const val DEPTH = 10

    /**
     * What a name may be resolved against.
     *
     * A function rather than a store, because the two questions have different homes: a file is
     * opened from the project and a secret comes from the vault, and neither wants to know about the
     * other. Answering `null` means "not found", and the sentence is the expander's to write because
     * it is the one that knows the name it was given.
     */
    interface Names {
        /** The text [mention] stands for, or null when it names nothing. */
        fun read(mention: Mention): String?

        /**
         * **Why [mention] answers nothing**, when the caller can say it better than a default.
         *
         * The default sentence is the right shape but a poor answer: "there is no secret called X for
         * this host" leaves a person to work out whether they spelled it wrong, whether it is stored
         * for another host, or whether the thing they want is one of the app's own two connections. A
         * caller that holds the vault can say all three, and it is the caller — not this object —
         * that knows what the vault holds.
         *
         * Null means "say the default", so a resolver built in a test needs only [read].
         */
        fun why(mention: Mention): String? = null
    }

    /**
     * [text] with every `{{…}}` replaced, recursively.
     *
     * `host` is the request's own host, and it is what a secret is bound to. An empty host means "this
     * document is not being sent anywhere", which is a real answer for reading a file into a
     * message — and it refuses every secret, because a value that may not travel must not be put in
     * something that will.
     */
    fun of(text: String, host: String, names: Names): Result<String> =
        round(text, host, names, emptySet(), 0)

    /**
     * Every name [text] mentions, **including the ones inside the files it names**, without
     * expanding anything.
     *
     * Exists because a caller sometimes has to know what a document will need *before* it sends it —
     * the `.http` picker lists the secrets a file uses so a person can see what it will spend, and a
     * refusal that says "there is no secret called X" is more useful when the answer also says which
     * file asked for it.
     */
    fun namesIn(text: String): List<String> = mentions(text).map { it.name }

    /** One `{{…}}` found in a text: the whole match, and the name inside it. */
    data class Mention(val at: Int, val whole: String, val name: String, val kind: Kind) {
        /** Which prefix was written, or `NONE` when it was a bare name. */
        enum class Kind { FILE, SECRET, NONE }

        /** False when this mention may not be resolved in a document sent to [host]. */
        fun allowedIn(host: String): Boolean = kind != Kind.SECRET || host.isNotBlank()
    }

    /**
     * Every `{{…}}` in [text], left to right, with no substitution.
     *
     * **One scanner, used by the expander and by the picker**, so a file cannot be described
     * differently from the way it is read. An unclosed `{{` is not a mention; it is left in the text
     * exactly as written, because a document is allowed to contain one — a template explaining the
     * syntax does.
     */
    fun mentions(text: String): List<Mention> {
        val out = mutableListOf<Mention>()
        var at = 0
        while (at < text.length) {
            val open = text.indexOf("{{", at)
            if (open < 0) break
            val close = text.indexOf("}}", open + 2)
            if (close < 0) break
            val inside = text.substring(open + 2, close).trim()
            val kind = when {
                inside.startsWith("file:") -> Mention.Kind.FILE
                inside.startsWith("secret:") -> Mention.Kind.SECRET
                else -> Mention.Kind.NONE
            }
            val name = when (kind) {
                Mention.Kind.FILE -> inside.removePrefix("file:").trim()
                Mention.Kind.SECRET -> inside.removePrefix("secret:").trim()
                Mention.Kind.NONE -> inside
            }
            out += Mention(open, text.substring(open, close + 2), name, kind)
            at = close + 2
        }
        return out
    }

    private fun round(
        text: String,
        host: String,
        names: Names,
        seen: Set<String>,
        depth: Int,
    ): Result<String> {
        val found = mentions(text)
        if (found.isEmpty()) return Result.success(text)
        if (depth >= DEPTH) {
            return Result.failure(
                IllegalArgumentException(
                    "The request expands more than $DEPTH times, so it probably names itself. " +
                        "It names: " + found.joinToString(", ") { it.whole },
                ),
            )
        }
        val out = StringBuilder()
        var last = 0
        for (one in found) {
            // **A bare name is the app's own vocabulary** — `{{messages}}` and its siblings — and it
            // belongs to the request *shape*, not to a document a person wrote. Reaching here means a
            // document used one, which is not a thing this tool can fill.
            if (one.kind == Mention.Kind.NONE) {
                return Result.failure(
                    IllegalArgumentException(
                        "${one.whole} does not name anything: write {{file:…}} for a file's " +
                            "contents, or {{secret:…}} for a value in the vault.",
                    ),
                )
            }
            if (one.kind == Mention.Kind.SECRET && host.isBlank()) {
                return Result.failure(
                    IllegalArgumentException(
                        "${one.whole} is a secret, and this request has no host for it to be " +
                            "bound to.",
                    ),
                )
            }
            if (one.name.isEmpty()) {
                return Result.failure(
                    IllegalArgumentException("${one.whole} has no name in it."),
                )
            }
            if (one.name in seen) {
                return Result.failure(
                    IllegalArgumentException(
                        "${one.whole} is expanded twice, so the document names itself. " +
                            "A file may name another file, but not one already being read.",
                    ),
                )
            }
            val value = names.read(one)
                ?: return Result.failure(
                    IllegalArgumentException(
                        names.why(one) ?: if (one.kind == Mention.Kind.FILE) {
                            "There is no file called \"${one.name}\" in this project, so " +
                                "${one.whole} cannot be expanded."
                        } else {
                            "There is no secret called \"${one.name}\" for $host, so " +
                                "${one.whole} cannot be expanded. Add it in Settings → Secrets, " +
                                "or use `git` or `llm` for a connection whose row lets an agent " +
                                "spend it."
                        },
                    ),
                )
            out.append(text, last, one.at)
            // **A file is a template and is expanded again; a secret is data and is not.** A file may
            // name another file — a body cut from a template that names a header list — so its
            // contents go round again. A vault value is a token or a key, and running it back through
            // the expander would let four characters inside somebody's password be read as a
            // placeholder. That is the whole difference, and it is why the two are not one branch.
            val expanded = if (one.kind == Mention.Kind.SECRET) {
                value
            } else {
                round(value, host, names, seen + one.name, depth + 1).getOrElse {
                    return Result.failure(it)
                }
            }
            out.append(expanded)
            last = one.at + one.whole.length
        }
        out.append(text, last, text.length)
        return Result.success(out.toString())
    }

    /**
     * A [Names] over a project folder and a vault — **the two places a `{{…}}` may be answered from.**
     *
     * **A path is resolved inside [root] and nowhere else.** `../../other-project/x` names a file this
     * request was not given, and reading it would make the expander a way to send anything on the phone
     * to anything on the internet. The check is by canonical path, so a symlink — every tree holds
     * `system` as one — cannot spell its way out either.
     *
     * **A secret is answered by the vault and only for [host].** The host is the request's own, read
     * from its URL before anything is expanded, so a value cannot name where it travels.
     */
    fun names(
        root: File,
        host: String,
        credentials: Credentials?,
        project: String?,
        branch: String?,
        /** A better sentence than the default when a name answers nothing. See [Names.why]. */
        explain: ((Mention) -> String?)? = null,
    ): Names = object : Names {
        override fun read(one: Mention): String? = when (one.kind) {
            Mention.Kind.FILE -> readInside(root, one.name)
            // **A person's own secret first, then the two the app itself uses.** `git` and `llm` name
            // the account's connection rows, and they are answered only when the row carries the
            // checkmark that says an agent may spend it — so naming one cannot reach past a permission
            // the person did not give. Asked second, so a named secret always wins over the app's.
            Mention.Kind.SECRET -> credentials?.secret(project, branch, one.name, host)
                ?: credentials?.spendable(one.name, host, project, branch)
            Mention.Kind.NONE -> null
        }

        override fun why(one: Mention): String? = explain?.invoke(one)
    }

    /**
     * The text of [name] inside [root], or `null` when it is not there or is not inside.
     *
     * **One reader for the expander and for `@path`**, so a body written inline and a body read from a
     * file obey the same rule about where a file may be. `File.canonicalFile` is what makes it a rule
     * rather than a spelling: `..` and a symlink both resolve before the comparison.
     */
    fun readInside(root: File, name: String): String? {
        val base = runCatching { root.canonicalFile }.getOrNull() ?: return null
        val target = runCatching { File(base, name).canonicalFile }.getOrNull() ?: return null
        val inside = target == base || target.path.startsWith(base.path + File.separator)
        if (!inside || !target.isFile) return null
        return runCatching { target.readText() }.getOrNull()
    }
}
