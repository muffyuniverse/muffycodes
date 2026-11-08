package com.example.notes

import org.json.JSONArray
import org.json.JSONObject

/**
 * **The request, as a document rather than as Kotlin.**
 *
 * Researched because the operator asked whether one OpenAI protocol exists: it does not. What
 * transfers is a *shape* — `POST {base}/{url}` with `model`, `messages`, `stream`, `tools` — and
 * above that line every implementation negotiates separately. `max_tokens` became
 * `max_completion_tokens` at OpenAI; `system` became `developer` for the reasoning models; a
 * reasoning model refuses `temperature` outright; DeepSeek's thinking mode 400s unless
 * `reasoning_content` is echoed back; every self-hosted embedding server disagrees about the field
 * that carries the text.
 *
 * A request is a thing you can write down, so it is written down: a template with placeholders, and
 * the app fills it in. That is the whole mechanism, and it is deliberately smaller than it looks.
 *
 * ### Why there is no *response* shape
 *
 * **A request is a document; a streamed response is a fold.** This class substitutes values into a
 * JSON object built once and sent. The reply is not like that: it is accumulated — `choices[0].delta
 * .content` joined, tool-call argument fragments joined *by `index`*, `finish_reason` watched, and
 * `[DONE]` ending it. Anthropic ends on `message_stop`, Gemini answers in
 * `candidates[].content.parts[]`, the Responses API on `response.completed`. A file can name those
 * paths; it cannot name the accumulation rules, so a response shape would describe a fraction of a
 * protocol and mis-handle the rest in silence. Within the OpenAI-compatible family the replies
 * genuinely do not differ — the differences are *additive* (a `reasoning_content` field) or in the
 * error envelope, and both are already handled.
 *
 * ### The three rules, and why each one exists
 *
 * 1. **A placeholder is a whole value.** `"model": "{{model}}"`, never `"prefix-{{model}}"`. A
 *    placeholder inside a longer string is refused, because then the shape would be building
 *    strings — and a shape that can build a string can build a URL.
 * 2. **A blank or null placeholder removes its key.** No effort means no `reasoning_effort` field,
 *    not `""` — a server that rejects the field never sees it. (`false`, `0` and `[]` are values
 *    and stay: a ping's `max_tokens` of 1 is not "absent", and `tools: []` is a decision too.)
 * 3. **[url] is a relative path.** A shape whose url had a scheme could send the request — and with
 *    it the key — to a host nobody chose. This is the one rule that is a security boundary rather
 *    than a predictability rule.
 */
data class Shape(val url: String, val body: JSONObject, val kind: Kind = Kind.CHAT) {

    /**
     * Why this shape cannot be used, or null when it can.
     *
     * **Says which part is wrong**, because a template is written by a person and "invalid shape"
     * would leave them guessing at a document they can see. Both checks are here rather than in the
     * renderer so a window can refuse a template before it is stored, and the renderer can still
     * refuse it if it arrived some other way.
     */
    fun problem(): String? {
        val path = url.trim()
        if (path.isEmpty()) return "A shape needs a url to send to."
        if (path.contains("://") || path.startsWith("//")) {
            return "A shape's url is a path on the connection's address, not an address: \"$path\". " +
                "The address is the connection's, so a shape cannot send the key somewhere else."
        }
        return placeholderProblem(body, kind)
    }

    /**
     * The body, with [values] put where the placeholders are.
     *
     * **A missing name is not an error here**: the caller passes every name its kind defines, and a
     * name the template does not use is a template that does not need it. The refusal is for the
     * other direction — a template naming something the app cannot fill — and [problem] catches it.
     */
    fun bodyOf(values: Map<String, Any?>): JSONObject = fill(body, values) as JSONObject

    /** The address this shape is sent to, given the connection's [base]. */
    fun address(base: String): String {
        val root = base.trim().trimEnd('/')
        val path = url.trim().trimStart('/')
        return "$root/$path"
    }

    companion object {

        /** The three kinds of request the app makes, each with its own placeholders. */
        enum class Kind(val names: Set<String>) {
            CHAT(setOf("model", "messages", "tools", "tool_choice", "stream", "effort", "max_tokens")),
            EMBEDDING(setOf("model", "input", "dimensions")),
            RERANK(setOf("model", "query", "documents")),
        }

        /**
         * The OpenAI chat shape, **which is what the app sent before there was a shape at all.**
         *
         * Written as data here rather than as Kotlin in `Door`, so the default and a person's own
         * template take the same road — a default that skipped the mechanism would be the one shape
         * that could never be checked or sent anywhere.
         */
        val CHAT = Shape(
            kind = Kind.CHAT,
            url = "chat/completions",
            body = JSONObject(
                """
                {
                  "model": "{{model}}",
                  "messages": "{{messages}}",
                  "tools": "{{tools}}",
                  "tool_choice": "{{tool_choice}}",
                  "stream": "{{stream}}",
                  "stream_options": { "include_usage": true },
                  "reasoning_effort": "{{effort}}",
                  "max_tokens": "{{max_tokens}}"
                }
                """.trimIndent(),
            ),
        )

        val EMBEDDING = Shape(
            kind = Kind.EMBEDDING,
            url = "embeddings",
            body = JSONObject(
                """
                {
                  "model": "{{model}}",
                  "input": "{{input}}",
                  "dimensions": "{{dimensions}}"
                }
                """.trimIndent(),
            ),
        )

        val RERANK = Shape(
            kind = Kind.RERANK,
            url = "rerank",
            body = JSONObject(
                """
                {
                  "model": "{{model}}",
                  "query": "{{query}}",
                  "documents": "{{documents}}"
                }
                """.trimIndent(),
            ),
        )

        fun preset(kind: Kind): Shape = when (kind) {
            Kind.CHAT -> CHAT
            Kind.EMBEDDING -> EMBEDDING
            Kind.RERANK -> RERANK
        }

        /**
         * The name of the placeholder in [text], or null when there is none.
         *
         * A name is anything between `{{` and `}}`. `{{model}}` and ` {{model}} ` are the same
         * name — a JSON template is written by hand and the space is a typo, not a different name.
         */
        fun placeholderIn(text: String): String? {
            val open = text.indexOf("{{")
            if (open < 0) return null
            val close = text.indexOf("}}", open + 2)
            if (close < 0) return null
            return text.substring(open + 2, close).trim()
        }

        /**
         * Why a template cannot be filled, or null when it can — **recursively, and naming the
         * placeholder**, because "invalid shape" is useless to the person who wrote the file.
         */
        fun placeholderProblem(body: JSONObject?, kind: Kind? = null): String? {
            if (body == null) return null
            for (key in body.keys()) {
                val value = body.opt(key)
                val text = value as? String
                if (text != null && text.contains("{{")) {
                    val name = placeholderIn(text)
                        ?: return "\"$key\" opens a placeholder and never closes it."
                    if (text.trim() != "{{$name}}" && text.trim() != "{{ $name }}") {
                        return "\"$key\" puts a placeholder inside other text. A placeholder is a " +
                            "whole value, so \"{{$name}}\" on its own."
                    }
                    if (kind != null && name !in kind.names) {
                        // **Built outside the sentence**, so the braces of the vocabulary are data
                        // rather than a splice inside a string: `{{x}}` written inside `${...}`
                        // inside a `"` is legal Kotlin and unreadable.
                        val vocabulary = kind.names.sorted().joinToString(", ") { "{{" + it + "}}" }
                        return "\"$key\" asks for {{$name}}, which is not something the app can " +
                            "fill. It can fill: $vocabulary."
                    }
                }
                if (value is JSONObject) {
                    placeholderProblem(value, kind)?.let { return it }
                }
                if (value is JSONArray) {
                    for (at in 0 until value.length()) {
                        (value.opt(at) as? JSONObject)?.let { one ->
                            placeholderProblem(one, kind)?.let { return it }
                        }
                    }
                }
            }
            return null
        }

        /**
         * [template] with every placeholder replaced, **copying** rather than editing.
         *
         * A shape is shared by every request the app makes — `CHAT` is one object for the whole
         * process — so filling it in place would make the second request send the first one's
         * message. The copy is the price of the default being data.
         */
        private fun fill(template: Any?, values: Map<String, Any?>): Any? = when (template) {
            is JSONObject -> {
                val out = JSONObject()
                for (key in template.keys()) {
                    val value = filled(template.opt(key), values)
                    // **A blank or null value removes the key** - rule 2. `JSONObject.put` with a
                    // null removes in Android's implementation, but saying so here is the rule
                    // rather than a fact about one implementation.
                    if (value != null) out.put(key, value)
                }
                out
            }
            else -> template
        }

        private fun filled(value: Any?, values: Map<String, Any?>): Any? = when (value) {
            is String -> {
                val name = placeholderIn(value)
                if (name != null && value.trim() == "{{$name}}") {
                    val given = values[name]
                    if (given == null || (given is String && given.isBlank())) null else given
                } else {
                    value
                }
            }
            is JSONObject -> fill(value, values)
            is JSONArray -> {
                val out = JSONArray()
                for (at in 0 until value.length()) {
                    val one = value.opt(at)
                    if (one is JSONObject) out.put(fill(one, values)) else out.put(one)
                }
                out
            }
            else -> value
        }
    }
}
