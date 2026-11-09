package com.example.notes

import org.json.JSONObject

/**
 * **One request, as a file** — the address, the path on it, the body, and the numbers the agent runs
 * under.
 *
 * This is the whole of what the app knows about a model. There is no model list, no provider tag, no
 * effort ladder and no window that asks for four fields one at a time: a person writes the request the
 * server will receive, and the app sends it. The operator's decision, 2026-11-09: *"we could just leave
 * in general settings a button that then could open a hidden folder with llm request schema templates
 * as files that user can open and edit."*
 *
 * ### Why the model is a literal here and a placeholder there
 *
 * [Shape]'s placeholders are for what **only the app can know at call time** — the conversation, its
 * tools, whether the reply is streamed. A model id is not like that: it is a name the server assigned,
 * and it stays the same for every turn of every session that uses this file. So it is written as a
 * literal, which means a template carries its model with it: `deepseek-fast.json`,
 * `claude-careful.json`, `llama-on-my-pc.json` are three requests, and copying one is how you get
 * another. That is also why the address is here rather than in a settings screen — a file that names a
 * model but not the server is a file that cannot be sent.
 *
 * ### The three numbers are here for the same reason the model is
 *
 * `target`, `forget` and `trim` are the compaction policy, and policy belongs with the request it
 * governs. A "long careful" template can therefore also carry a bigger target, and a template used for
 * a small local model can carry a small one, without any second place to look. Zero — or an absent key —
 * means that hand is off, which is a statement rather than a gap.
 *
 * ### What is deliberately absent
 *
 * **The key's value.** A file is a thing a person reads, copies and commits; a key in one would be the
 * leak the vault exists to prevent. A template names a **row** instead ([key]), and the name is resolved
 * against the row's own host — so `@openrouter` is offered to `openrouter.ai` and refused to anything
 * else. That check is what keeps a name from being an exfiltration primitive.
 *
 * **The model's window and reply reserve.** Nothing clamps any more (`ContextBudget`), so a number about
 * a model's size would be a fact nothing reads.
 */
data class Schema(
    /** The base URL: `https://openrouter.ai/api/v1`. An address, because it is where bytes go. */
    val address: String,
    /**
     * **The vault row that holds this server's key, by name** — `@openrouter` — or blank for none.
     *
     * Never the key itself: a template is a document people read, copy and hand to each other, and the
     * name is resolved by host so that a file cannot send one server's key to another. Absent means no
     * `Authorization` header is sent at all, which is why a server on this phone needs no flag.
     */
    val key: String = "",
    /** The path on it: `chat/completions`. Relative, by [Shape]'s third rule. */
    val url: String,
    /** What is sent, with the placeholders the app fills. */
    val body: JSONObject,
    /** Where the context should end up. Zero is off. */
    val target: Long = 0,
    /** The size at which the agent is asked to clear up. Zero is off. */
    val forget: Long = 0,
    /** The size at which the app trims the context itself. Zero is off. */
    val trim: Long = 0,
) {
    /** This request as the shape the sender uses, for a call of [kind]. */
    fun shape(kind: ShapeKind): Shape = Shape(url = url, body = body, kind = kind)

    /**
     * Why this file cannot be used, or null when it can.
     *
     * **Every refusal names the key it is about** — `address`, `url`, `target` — because a person
     * edited a document and "invalid schema" would leave them looking at it. [kind] is the vocabulary
     * of placeholders the caller will fill; null checks only the two rules that hold for every kind,
     * which is what the editor wants while a file is half written.
     */
    fun problem(kind: ShapeKind? = null): String? =
        // **The url and the placeholders are [Shape]'s rules, asked one at a time.** Not its
        // `problem()`, because that checks the placeholders against the vocabulary of a *kind* — and a
        // kind-less check (what the editor wants while a file is half written) must not call a
        // placeholder a mistake before the kind that defines it is known.
        addressProblem(address)
            ?: numberProblem("target", target)
            ?: numberProblem("forget", forget)
            ?: numberProblem("trim", trim)
            ?: Shape.urlProblem(url)
            ?: Shape.placeholderProblem(body, kind)

    /** The file's text. Two-space pretty, because a person opens these. */
    fun text(): String = toJson().toString(2)

    private fun toJson(): JSONObject = JSONObject().apply {
        put("address", address)
        // **Written only when one is named.** Absent is a statement — no key — and a `"key": ""` would
        // read as a row with an empty name rather than as "this server wants none".
        if (key.isNotBlank()) put("key", key)
        put("url", url)
        // **Written only when they are not zero**, so a template that turns a hand off says nothing
        // about it rather than saying 0 twice — and so a template that uses none of the three is not
        // cluttered with keys that mean nothing to it.
        if (target != 0L) put("target", target)
        if (forget != 0L) put("forget", forget)
        if (trim != 0L) put("trim", trim)
        put("body", body)
    }

    companion object {

        /** Why [address] is not somewhere bytes can go, or null when it is. **One copy**, asked by the
         * reader and by [problem]. */
        fun addressProblem(address: String): String? {
            val where = address.trim()
            if (where.isEmpty()) return "\"address\" is empty — it is the server this request goes to."
            if (!where.startsWith("http://") && !where.startsWith("https://")) {
                return "\"address\" must start with http:// or https:// — it is an address, like " +
                    "https://openrouter.ai/api/v1."
            }
            return null
        }

        /** Why one of the three numbers cannot be used, or null. */
        fun numberProblem(key: String, value: Long): String? =
            if (value < 0) "\"$key\" is negative; write a size, or 0 for off." else null

        /**
         * Reads a schema, or says which key is wrong.
         *
         * **A file that will not parse is refused, never replaced** — the same rule `model_conf.json`
         * followed, and for the same reason: a parser that falls back to defaults eats a person's work
         * and they find out from an agent using something they did not choose.
         */
        fun parse(text: String): Result<Schema> {
            // **The document is read as JSONC**, so a template may explain itself beside itself: the
            // operator asked for comments, and `org.json` refuses both forms. The scanner turns a
            // comment into whitespace of the same length, so a parse error after one still reports the
            // line a person is looking at.
            val root = Jsonc.parse(text).getOrElse { return Result.failure(it) }
            val address = root.optString("address").trim()
            val url = root.optString("url").trim()
            if (address.isEmpty()) {
                return Result.failure(IllegalArgumentException("\"address\" is missing"))
            }
            // **A file that could never be sent is refused when it is read**, rather than at the first
            // turn — the same rule `model_conf.json` follows, because the person who can fix it is
            // looking at the file now.
            addressProblem(address)?.let { return Result.failure(IllegalArgumentException(it)) }
            if (url.isEmpty()) return Result.failure(IllegalArgumentException("\"url\" is missing"))
            val key = root.optString("key").trim()
            // **A name, checked as a name.** A value that looks like somebody's key — long, no `@` — is
            // refused with the two ways to do it, because a person who pasted one has just written a
            // credential into a document that gets copied, and they deserve to be told rather than
            // silently obeyed.
            if (key.isNotEmpty() && !key.startsWith("@")) {
                return Result.failure(
                    IllegalArgumentException(
                        "\"key\" holds a name, not a key: write @$key and store the value in the vault.",
                    ),
                )
            }
            val body = root.optJSONObject("body")
                ?: return Result.failure(
                    IllegalArgumentException("\"body\" must be an object — the request to send."),
                )
            // **A number that is not a number is refused rather than read as zero.** Zero means "this
            // hand is off", which is a decision; `"target": "60k"` is a typo, and reading it as off
            // would silently turn compaction off on the file where somebody tried to set it.
            fun number(key: String): Result<Long> {
                if (!root.has(key)) return Result.success(0L)
                val raw = root.opt(key)
                if (raw !is Number) {
                    return Result.failure(
                        IllegalArgumentException("\"$key\" must be a whole number of tokens"),
                    )
                }
                return Result.success(raw.toLong())
            }
            val target = number("target").getOrElse { return Result.failure(it) }
            val forget = number("forget").getOrElse { return Result.failure(it) }
            val trim = number("trim").getOrElse { return Result.failure(it) }
            return Result.success(Schema(address, key, url, body, target, forget, trim))
        }

    }
}
