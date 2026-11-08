package com.example.notes

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **The request is a document, and this is the proof that the document is what the app already
 * sent.**
 *
 * The mechanism landed before it was ever stored, so that it could be shown to be a no-op: the
 * default preset's output has to equal the body `Door` built in Kotlin, field for field, for all
 * three kinds. If it does not, the refactor changed the wire format — which is the one thing it may
 * not do, because the phone would then be asking every server something slightly different from what
 * it asked yesterday and nothing on the screen would have changed.
 */
class ShapeTest {

    // ---- the default preset *is* the body the app sent before there was a shape ------

    @Test
    fun `the default chat shape is the body the door always built`() {
        val door = OpenRouterDoor(
            key = { "a-key" },
            model = "deepseek/deepseek-v4.1-flash",
            provider = "",
            effort = "high",
        )
        // **No tools, so the comparison is about the fields and not about a fixture list.** The
        // tools' own body is asserted in `AgentTest`, where the specs live; here the point is that
        // every field the door writes is a field the shape writes.
        val request = Request(
            model = "deepseek/deepseek-v4.1-flash",
            provider = "",
            effort = "high",
            system = "you are an agent",
            messages = listOf(Msg.user("a task")),
            tools = emptyList(),
        )

        val shaped = door.body(request, maxTokens = 1)

        // **Written out as the object, not as a call to the shape.** A test that asked the shape
        // twice would agree with itself; this is the body as the app sent it before the mechanism
        // existed, and it is the thing that must not change.
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "you are an agent"))
            .put(JSONObject().put("role", "user").put("content", "a task"))
        val expected = JSONObject()
            .put("model", "deepseek/deepseek-v4.1-flash")
            .put("messages", messages)
            .put("tools", JSONArray())
            .put("tool_choice", "auto")
            .put("stream", false)
            .put("stream_options", JSONObject().put("include_usage", true))
            .put("reasoning_effort", "high")
            .put("max_tokens", 1)

        // Keys are not ordered in a JSON object, so "the same document" is compared as the same
        // fields with the same values - which is what a server reads, and all a server can read.
        assertTrue(
            "every field the app sent, the shape sends: ${shaped.keys().asSequence().sorted().toList()}",
            shaped.keySet() == expected.keySet(),
        )
        for (key in expected.keys()) {
            assertSameJson("field \"$key\"", expected.opt(key), shaped.opt(key))
        }
    }

    @Test
    fun `a blank effort and no max tokens leave their keys out, exactly as the door did`() {
        // **Two fields, two absences, two different reasons.** A model that does not reason must not
        // be sent `reasoning_effort: ""` — one provider answers 400 and another silently ignores it,
        // and both are worse than not asking. And a turn has no `max_tokens` at all while a ping has
        // one token, so "no value" has to mean "no field".
        val door = OpenRouterDoor(
            key = { "a-key" },
            model = "a-model",
            provider = "",
            effort = "",
        )
        val request = Request(
            model = "a-model",
            provider = "",
            effort = "",
            system = "",
            messages = listOf(Msg.user("hello")),
            tools = emptyList(),
        )

        val body = door.body(request)

        assertFalse("no effort field when nothing was asked for", body.has("reasoning_effort"))
        assertFalse("and no size when a turn sets none", body.has("max_tokens"))
        assertTrue("while the fields that are always there stay", body.has("stream_options"))
    }

    @Test
    fun `the embedding and rerank presets are the bodies the app always built`() {
        // The two calls that are hard-coded to one vendor's shape today, and the reason the shape
        // mechanism is not speculative: these are what stop a phone being pointed at Ollama, at LM
        // Studio, or at somebody's own reranker.
        val embedded = Shape.EMBEDDING.bodyOf(
            mapOf("model" to "qwen/qwen3-embedding-8b", "input" to JSONArray(listOf("one", "two"))),
        )
        assertEquals("qwen/qwen3-embedding-8b", embedded.getString("model"))
        assertEquals(2, embedded.getJSONArray("input").length())
        assertFalse("no dimensions unless one was chosen", embedded.has("dimensions"))

        val sized = Shape.EMBEDDING.bodyOf(
            mapOf("model" to "m", "input" to JSONArray(listOf("one")), "dimensions" to 1024),
        )
        assertEquals("a chosen dimension is sent", 1024, sized.getInt("dimensions"))

        val ranked = Shape.RERANK.bodyOf(
            mapOf("model" to "m", "query" to "what is a vault", "documents" to JSONArray(listOf("a", "b"))),
        )
        assertEquals("what is a vault", ranked.getString("query"))
        assertEquals(2, ranked.getJSONArray("documents").length())
        assertEquals("and the three fields are its whole body", 3, ranked.length())
    }

    @Test
    fun `a false and a zero are values, and stay`() {
        // **"Empty" means blank, not falsy.** `stream: false` is a decision and `max_tokens: 0` is a
        // number somebody may mean; removing them would turn a statement into an absence.
        val shape = Shape(
            url = "chat/completions",
            body = JSONObject("""{"stream":"{{stream}}","max_tokens":"{{max_tokens}}","tools":"{{tools}}"}"""),
        )

        val body = shape.bodyOf(mapOf("stream" to false, "max_tokens" to 0, "tools" to JSONArray()))

        assertEquals("false is kept", false, body.getBoolean("stream"))
        assertEquals("zero is kept", 0, body.getInt("max_tokens"))
        assertEquals("and an empty list is a list", 0, body.getJSONArray("tools").length())
    }

    // ---- the three rules -------------------------------------------------------

    @Test
    fun `a shape's url is a path, never an address`() {
        // **The security rule, and the only one here that is.** A template that could carry a scheme
        // could send the request — and the key in its Authorization header — to a host nobody chose.
        // The address belongs to the connection, which is what a person edits.
        val elsewhere = Shape("https://evil.example/v1/chat/completions", Shape.CHAT.body)

        val said = elsewhere.problem()

        assertTrue("refused, and saying why: $said", said != null && said.contains("not an address"))
        assertTrue("and that the key is what is at stake: $said", said!!.contains("key"))

        // **And the protocol-relative form is the same hole.** `//host/path` resolves against the
        // connection's scheme and lands on another host, so it is refused with the same sentence.
        assertTrue("a protocol-relative url is refused too", Shape("//evil.example/x", Shape.CHAT.body).problem() != null)
        assertTrue("a leading slash is not a scheme", Shape("/v1/chat", Shape.CHAT.body).problem() == null)
        assertTrue("and neither is a plain path", Shape("chat/completions", Shape.CHAT.body).problem() == null)
    }

    @Test
    fun `a placeholder the app cannot fill is refused by name, with what it can fill`() {
        // **The file cannot describe a protocol the app does not speak.** A template asking for
        // {{reasoning}} or {{plugins}} is a person copying another tool's shape, and the sentence has
        // to say which word was not understood *and* what the vocabulary is.
        val typed = Shape(
            url = "chat/completions",
            body = JSONObject("""{"model":"{{model}}","reasoning":{"effort":"{{reasoning}}"}}"""),
        )

        val said = typed.problem()

        assertTrue("names the placeholder: $said", said != null && said.contains("{{reasoning}}"))
        assertTrue("and lists the vocabulary: $said", said!!.contains("{{messages}}"))
        assertFalse("while a name it knows passes", said.contains("{{model}}"))
    }

    @Test
    fun `a placeholder is a whole value, never part of a string`() {
        // **Because a shape that can build strings can build a url**, and because a half-substituted
        // endpoint is a request nobody can explain. Refused rather than guessed.
        val glued = Shape(
            url = "chat/completions",
            body = JSONObject("""{"model":"model: {{model}}"}"""),
        )

        val said = glued.problem()

        assertTrue("refused, saying it must stand alone: $said", said != null && said.contains("whole value"))
    }

    @Test
    fun `an unclosed placeholder is a typo the window can name`() {
        val broken = Shape(url = "chat/completions", body = JSONObject("""{"model":"{{model"}"""))

        assertTrue("named as unclosed", broken.problem()!!.contains("never closes"))
    }

    @Test
    fun `the address is the base joined with the shape's path, one slash`() {
        assertEquals(
            "no doubled slash when the base has a trailing one",
            "https://openrouter.ai/api/v1/chat/completions",
            Shape.CHAT.address("https://openrouter.ai/api/v1/"),
        )
        assertEquals(
            "and none when the shape's path opens with one",
            "https://openrouter.ai/api/v1/chat/completions",
            Shape("/chat/completions", Shape.CHAT.body).address("https://openrouter.ai/api/v1"),
        )
        assertEquals(
            "a path is all the shape adds",
            "http://192.168.1.9:8000/v1/embeddings",
            Shape.EMBEDDING.address("http://192.168.1.9:8000/v1"),
        )
    }

    // ---- the one property a shared default has to have --------------------------

    @Test
    fun `rendering does not edit the shape it renders`() {
        // **`CHAT` is one object for the whole process.** Filling it in place would make the second
        // request carry the first one's messages — the kind of bug that looks like a cache and is
        // not, since the words on the wire would be last turn's.
        val first = Shape.CHAT.bodyOf(mapOf("model" to "one", "messages" to JSONArray(), "tools" to JSONArray()))
        val second = Shape.CHAT.bodyOf(mapOf("model" to "two", "messages" to JSONArray(), "tools" to JSONArray()))

        assertEquals("the first render is its own", "one", first.getString("model"))
        assertEquals("and the second does not see it", "two", second.getString("model"))
        assertEquals(
            "while the template itself still holds placeholders",
            "{{model}}",
            Shape.CHAT.body.getString("model"),
        )
    }

    @Test
    fun `a name a template does not use is not an error`() {
        // **The caller passes every name its kind defines**, and a template that sends no effort is
        // a template for a server that does not take one. Requiring all of them would make the
        // smallest sensible shape impossible to write.
        val chatty = Shape(url = "chat/completions", body = JSONObject("""{"model":"{{model}}"}"""))

        assertNull("no complaint about the names it never mentions", chatty.problem())
        assertEquals("and the body is what it says", 1, chatty.bodyOf(mapOf("model" to "m", "effort" to "high")).length())
    }

    /** The same field, with the same value, at every depth. */
    private fun assertSameJson(what: String, expected: Any?, actual: Any?) {
        when {
            expected is JSONObject -> {
                assertTrue("$what is an object", actual is JSONObject)
                actual as JSONObject
                assertEquals("$what has the same fields", expected.keySet(), actual.keySet())
                for (key in expected.keys()) assertSameJson("$what.$key", expected.opt(key), actual.opt(key))
            }
            expected is JSONArray -> {
                assertTrue("$what is an array", actual is JSONArray)
                actual as JSONArray
                assertEquals("$what has the same length", expected.length(), actual.length())
                for (at in 0 until expected.length()) {
                    assertSameJson("$what[$at]", expected.opt(at), actual.opt(at))
                }
            }
            else -> assertEquals(what, expected, actual)
        }
    }
}
