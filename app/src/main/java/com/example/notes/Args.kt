package com.example.notes

import org.json.JSONArray
import org.json.JSONObject

/**
 * **Every argument value, expanded before the command sees it.**
 *
 * The operator's decision, 2026-11-10: *"polymorphic args with a brace expansion is what I want to be
 * done — because making it a general capability of expanding arg values is not much more difficult
 * than handling it individually but it gives a general uniform and useful feature."*
 *
 * The argument for doing it in one place is stronger than the effort argument: **per-tool handling
 * means each tool can get it slightly wrong**, and one tool forgetting is a silent difference between
 * two commands that are supposed to behave alike. So this runs once, in [AgentTools.run], after the
 * keys are checked and before dispatch, and no command can skip it.
 *
 * ### A value may be a source rather than a literal
 *
 * One JSON object with exactly one key:
 *
 * | value | means |
 * |---|---|
 * | `"milk.txt"` | itself |
 * | `{"row": 17}` | that session row's body — the **answer**, not the call |
 * | `{"file": "notes.md"}` | that file's contents |
 * | `{"tool": "read", "args": {...}}` | that call's result, run but not shown to the conversation |
 *
 * ### Braces multiply a path
 *
 * `notes/{milk,bread}.txt` is two paths; `v{1..3}/` is three; nesting works. Bash semantics, because
 * that is the grammar a model already knows and a second one would be a second thing to learn.
 *
 * **The two halves are one feature** because both answer "this argument is not one literal", and both
 * have to be resolved before the command runs. Braces produce *many* values; a source produces *one
 * text*. A value that is both (`{"file":"notes/{a,b}.md"}`) expands the braces first, then reads each.
 *
 * ### The rules that keep it from being dangerous
 *
 * - **A row that is gone is refused, never empty.** `forget` and `trimSession` delete rows, and a
 *   `{"row":7}` naming a deleted row must say so rather than hand a command an empty string — which
 *   would read as "replace this with nothing" and is exactly the silent act this app refuses.
 * - **A source is copied at call time**, so a later trim cannot empty a write that was already decided.
 * - **A command that takes one path refuses an expansion to many** rather than acting on the first,
 *   because acting on the first is a silent partial act.
 * - **Braces are expanded for path-shaped keys only.** A document containing `{a,b}` must not become a
 *   different document, so `text`, `grep` and `sql` are never brace-expanded.
 * - **No recursion through `{{…}}`**: a secret stays data and a row's body is text, never a template.
 *   That rule already exists in [Expand] and this does not weaken it.
 */
object Args {

    /**
     * The most values one argument may expand to.
     *
     * **A cap, not a guess at what is reasonable.** `{1..1000000}` is four characters and would build a
     * million paths before the first one was used; the bound is what makes a brace expression a
     * shorthand rather than a way to run out of memory.
     */
    const val MOST_EXPANSIONS = 1000

    /**
     * The most characters one resolved value may be.
     *
     * A file or a row is text of unknown size, and an argument that is a megabyte is not an argument
     * any command wants — it is a paste. The limit is generous enough for a whole document and small
     * enough that a mistake is a sentence rather than a stalled turn.
     */
    const val MOST_CHARS = 400_000

    /** What a resolved argument is: one text, or many paths. */
    sealed interface Expanded {
        data class One(val value: Any) : Expanded
        data class Many(val values: List<String>) : Expanded
        data class Failed(val why: String) : Expanded
    }

    /**
     * Resolves every value in [args], or says why one could not be.
     *
     * [pathKeys] is the set of argument names that are paths — the only ones braces apply to.
     * [read] answers a file's text and [row] answers a session row's body; both answer `null` for
     * "not found", because the sentence belongs to the caller that knows what it asked for.
     */
    fun resolve(
        args: JSONObject,
        pathKeys: Set<String>,
        read: (String) -> String?,
        row: (Int) -> String?,
        call: (String, JSONObject) -> Result<String>,
        /**
         * Keys whose value is **a call, not a value**, and so is not resolved here.
         *
         * `background`'s `args` is the arguments of the command it defers, and `mcp`'s `args` are
         * passed to a remote tool. Both are objects that *describe* a call — resolving them would
         * run the thing they describe, which is exactly backwards: a deferred command must not run
         * now, and a remote tool's arguments are the remote's to interpret.
         */
        verbatim: Set<String> = emptySet(),
    ): Result<JSONObject> {
        val out = JSONObject()
        for (key in args.keys()) {
            if (key in verbatim) {
                out.put(key, args.get(key))
                continue
            }
            val raw = args.get(key)
            val isPath = key in pathKeys
            when (val expanded = expand(raw, isPath, read, row, call)) {
                is Expanded.Failed -> return Result.failure(IllegalArgumentException(expanded.why))
                is Expanded.One -> out.put(key, expanded.value)
                is Expanded.Many -> {
                    // **Many values only make sense as an array**, and only a command that asked for
                    // one can take them. A key that is a single path becomes an array the command
                    // must refuse — which it does, because its schema says string.
                    out.put(key, JSONArray(expanded.values))
                }
            }
        }
        return Result.success(out)
    }

    /**
     * One value, expanded.
     *
     * [isPath] decides whether braces apply, which is the difference between `notes/{a,b}.md` being two
     * files and a document that happens to contain braces staying one document.
     */
    fun expand(
        raw: Any?,
        isPath: Boolean,
        read: (String) -> String?,
        row: (Int) -> String?,
        call: (String, JSONObject) -> Result<String>,
    ): Expanded {
        // **A source is one object with exactly one key**, and more than one is refused rather than
        // resolved in some order: `{"row":7,"file":"x"}` has no meaning that a reader could predict.
        if (raw is JSONObject) {
            val keys = raw.keys().asSequence().toList()
            // **`tool` is the one source that takes a second key**, because a call needs arguments
            // as well as a name. The others are one key by definition — `{"row":7,"file":"x"}` has
            // no meaning a reader could predict, and refusing it is better than picking one.
            // **The source key is chosen, not assumed to be first.** `JSONObject` does not promise an
            // order, so `keys.first()` could be `args` — which is not a source and would report a
            // failure about a key the model never named as one.
            val source = keys.firstOrNull { it in setOf("row", "file", "tool") }
            val allowed = if (source == "tool") setOf("tool", "args") else setOfNotNull(source)
            val extra = keys.filterNot { it in allowed }
            if (source == null || extra.isNotEmpty()) {
                return Expanded.Failed(
                    "A value that names a source has one key — `row`, `file` or `tool` (with " +
                        "`args`) — and this one has " +
                        (if (keys.isEmpty()) "none" else keys.joinToString(", ")) + ".",
                )
            }
            return when (source) {
                "row" -> {
                    val id = raw.optInt("row", -1)
                    if (id < 0) {
                        return Expanded.Failed("`{\"row\":…}` needs a row number, and got ${raw.get("row")}.")
                    }
                    val body = row(id)
                        ?: return Expanded.Failed(
                            "There is no row $id in this conversation. Rows are not reused, and " +
                                "`forget` and a trim remove them — ask `help` for `read` if you need " +
                                "to see which rows survive.",
                        )
                    Expanded.One(body)
                }
                "file" -> {
                    val path = raw.optString("file")
                    val text = read(path)
                        ?: return Expanded.Failed("There is no file called `$path` to read.")
                    Expanded.One(text)
                }
                "tool" -> {
                    val tool = raw.optString("tool").trim()
                    // **Only `read` may supply a value.** A replacement that could name any tool
                    // would turn one call into a small program with an order nobody wrote down, and
                    // the order would be the model's — which is exactly what a refusal in
                    // `AgentTools` said before this moved here. `read` is the one command whose whole
                    // job is to produce text, so it is the only one that can be a value.
                    //
                    // **A source is a paste, not an action.** `{"tool":"delete"}` is a command that
                    // changes something, and running it as a side effect of assembling an argument
                    // is how a call ends up doing something the model did not ask for.
                    if (tool != "read") {
                        return Expanded.Failed(
                            "A value may name `read`, and `$tool` is not `read`. A source supplies " +
                                "text; a command that changes something is a call of its own.",
                        )
                    }
                    val inner = raw.optJSONObject("args") ?: JSONObject()
                    call(tool, inner).fold(
                        onSuccess = { Expanded.One(it) },
                        onFailure = { Expanded.Failed(it.message ?: "that command could not be run") },
                    )
                }
                else -> Expanded.Failed(
                    "`$key` is not a source. A value may name `row`, `file` or `tool`.",
                )
            }
        }
        // **An array is expanded element by element**, which is what `look`'s `paths` needs: each
        // entry may be a brace pattern or a source, and the array stays one array.
        if (raw is JSONArray) {
            val out = JSONArray()
            for (i in 0 until raw.length()) {
                when (val each = expand(raw.get(i), isPath, read, row, call)) {
                    is Expanded.Failed -> return each
                    is Expanded.One -> out.put(each.value)
                    is Expanded.Many -> each.values.forEach { out.put(it) }
                }
                if (out.length() > MOST_EXPANSIONS) {
                    return Expanded.Failed("That list expands to more than $MOST_EXPANSIONS entries.")
                }
            }
            return Expanded.One(out)
        }
        if (raw !is String) return Expanded.One(raw ?: "")
        if (!isPath) return Expanded.One(raw)
        val many = Braces.expand(raw)
            .getOrElse { return Expanded.Failed(it.message ?: "that path cannot be expanded") }
        return if (many.size == 1) Expanded.One(many.first()) else Expanded.Many(many)
    }
}

/**
 * **Bash brace expansion, and the three forms it has.**
 *
 * `{a,b}` is a list; `{1..9}` is a range; both nest. Nothing else — no globbing, no command
 * substitution, no quoting rules — because those are a shell and this is a path.
 *
 * **An unbalanced brace is refused, not treated literally.** A path with a stray `{` is far more
 * likely to be a mistake than a file genuinely named that, and treating it literally would make the
 * mistake silent: the command would act on a path nobody meant.
 */
object Braces {

    /** What a range may count to, matching [Args.MOST_EXPANSIONS] so one cap governs both. */
    private const val MOST = Args.MOST_EXPANSIONS

    /**
     * Every path [text] stands for, or why it cannot be expanded.
     *
     * **The result is ordered as bash orders it** — leftmost group outermost — because a model that
     * wrote `a{1,2}b{3,4}` expects `a1b3, a1b4, a2b3, a2b4` and a different order would be a
     * different program.
     */
    fun expand(text: String): Result<List<String>> {
        val first = text.indexOf('{')
        if (first < 0) {
            return if (text.indexOf('}') >= 0) unbalanced(text) else Result.success(listOf(text))
        }
        val close = matchingBrace(text, first)
            ?: return unbalanced(text)
        val head = text.substring(0, first)
        val body = text.substring(first + 1, close)
        val tail = text.substring(close + 1)
        val parts = alternatives(body)
            .getOrElse { return Result.failure(it) }
        // **A group with no comma and no range is literal**, and returning `"{$body}"` from
        // [alternatives] would hand it straight back to this loop — the brace is still there, so it
        // expands forever. It is recognised here instead, before the recursion: the text is one
        // path, exactly as written.
        if (parts.isEmpty()) return Result.success(listOf(text))
        val out = mutableListOf<String>()
        for (part in parts) {
            // **The tail is expanded for each alternative**, so a second group multiplies rather than
            // being appended once: `a{1,2}b{3,4}` is four paths, not two with a brace on the end.
            val rest = expand(part + tail).getOrElse { return Result.failure(it) }
            out.addAll(rest.map { head + it })
            if (out.size > MOST) {
                return Result.failure(
                    IllegalArgumentException(
                        "That path expands to more than $MOST files, so it was not run. " +
                            "Name the ones you want, or use a folder.",
                    ),
                )
            }
        }
        return Result.success(out)
    }

    /**
     * What is inside one brace group: its alternatives, or the one text it is.
     *
     * A group with no comma and no `..` is **not a group** — `{milk}` is the literal text `{milk}`
     * to bash, and it must stay literal here too, or a file genuinely named with braces becomes
     * unreachable.
     */
    private fun alternatives(body: String): Result<List<String>> {
        val range = rangeOf(body)
        if (range != null) return Result.success(range)
        val parts = splitTopLevel(body)
        // **One part means no comma was found at this level**, so bash leaves `{x}` alone — and the
        // empty list is how that is said, because returning `{x}` would be re-expanded forever.
        return Result.success(if (parts.size == 1) emptyList() else parts)
    }

    /** `1..9`, `a..e`, and a step — the three shapes bash allows. */
    private fun rangeOf(body: String): List<String>? {
        val bits = body.split("..")
        if (bits.size !in 2..3) return null
        val step = if (bits.size == 3) bits[2].toIntOrNull() ?: return null else 1
        if (step == 0) return null
        val from = bits[0]
        val to = bits[1]
        val a = from.toIntOrNull()
        val b = to.toIntOrNull()
        if (a != null && b != null) {
            val width = maxOf(from.length, to.length)
            val ascending = a <= b
            val count = if (ascending) (b - a) / step + 1 else (a - b) / step + 1
            if (count > MOST) return null
            return (0 until count).map {
                val n = if (ascending) a + it * step else a - it * step
                n.toString().padStart(width, '0')
            }
        }
        if (from.length == 1 && to.length == 1) {
            val ascending = from[0] <= to[0]
            val count = if (ascending) (to[0] - from[0]) / step + 1 else (from[0] - to[0]) / step + 1
            if (count > MOST) return null
            return (0 until count).map {
                val c = if (ascending) from[0] + it * step else from[0] - it * step
                c.toChar().toString()
            }
        }
        return null
    }

    /** Commas that are not inside a nested group. */
    private fun splitTopLevel(body: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        var start = 0
        for (i in body.indices) {
            when (body[i]) {
                '{' -> depth++
                '}' -> depth--
                ',' -> if (depth == 0) {
                    out.add(body.substring(start, i))
                    start = i + 1
                }
            }
        }
        out.add(body.substring(start))
        return out
    }

    /** The `}` that closes the `{` at [open], counting nesting. */
    private fun matchingBrace(text: String, open: Int): Int? {
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    private fun unbalanced(text: String): Result<List<String>> = Result.failure(
        IllegalArgumentException(
            "`$text` has a `{` or `}` that does not match. A brace group is `{a,b}` or `{1..9}`.",
        ),
    )
}
