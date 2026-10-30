package com.example.notes

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tools as the model is shown them.
 *
 * **This file exists because its absence cost a feature.** Every chat request carried eleven tool
 * schemas that were bare examples - `{"ids":"2 5 7-12 14"}` - with no `"type":"object"` around them.
 * OpenRouter forwarded them to DeepSeek, which refused the entire request before reading a single
 * message, and the app reported only OpenRouter's wrapper sentence. So the chat answered nothing, to
 * anything, for as long as it had existed, and no test asked what a schema looked like.
 */
class AgentToolsTest {

    private val specs = AgentTools.specs()

    @Test
    fun `every tool is an object schema, which is the only shape an endpoint accepts`() {
        // **The exact refusal, as a test.** DeepSeek's words were: "schema must be a JSON Schema of
        // 'type: \"object\"', got 'type: null'". A schema with no `type` is not a lenient schema; it
        // is a broken request, and one broken tool refuses the whole turn.
        for (spec in specs) {
            assertEquals(
                "${spec.name} must be an object schema",
                "object",
                spec.parameters.optString("type"),
            )
            assertTrue(
                "${spec.name} must describe its properties",
                spec.parameters.optJSONObject("properties") != null,
            )
        }
    }

    @Test
    fun `every property is typed, and none is left as an example`() {
        val allowed = setOf("string", "number", "integer", "boolean", "object", "array")
        for (spec in specs) {
            val properties = spec.parameters.optJSONObject("properties")!!
            val names = properties.keys()
            while (names.hasNext()) {
                val name = names.next()
                val one = properties.optJSONObject(name)!!
                val type = one.optString("type")
                assertTrue(
                    "${spec.name}.$name has type '$type', which is not a JSON Schema type",
                    type in allowed,
                )
            }
        }
    }

    @Test
    fun `a tool the model must fill in says which keys are required`() {
        // **Required, because an example is what a caller actually fills in.** `read` cannot work
        // without a path, and a schema that let the model omit it would turn a clear refusal into a
        // tool that failed at run time with a message about a missing argument.
        for (spec in specs) {
            val properties = spec.parameters.optJSONObject("properties")!!
            val required = spec.parameters.optJSONArray("required")
            assertTrue("${spec.name} must say what is required", required != null)
            val names = properties.keys().asSequence().toSet()
            for (i in 0 until required!!.length()) {
                assertTrue(
                    "${spec.name} requires '${required.optString(i)}', which it does not describe",
                    required.optString(i) in names,
                )
            }
        }
    }

    @Test
    fun `there is no wait tool, because a keepalive is the app's own act`() {
        // **A regression guard with a reason.** `wait` was a tool the model called to sit still; it
        // is gone, replaced by the app's own keepalive poll. A tool the model can call to do nothing
        // is a turn spent on nothing, and it would show in the conversation as work.
        assertFalse("wait is not a tool", specs.any { it.name == "wait" })
        // **The list is the whole surface, and it is asserted in order on purpose.** A tool that
        // appears here without a decision is a capability the operator never granted, which is why
        // this is an exact list rather than a "contains" check.
        //
        // `browse` and `shot` joined it when the browser module did. They are the app's first tools
        // that reach outside the phone, and both refuse private addresses unless the operator has
        // said otherwise — so the capability is granted deliberately rather than by the list
        // growing.
        assertEquals(
            "and the thirteen that are",
            listOf(
                "tree", "read", "edit", "forget", "create", "delete",
                "move", "history", "restore", "background", "reason", "browse", "shot",
            ),
            specs.map { it.name },
        )
    }

    @Test
    fun `a tool's description is a sentence a model can act on, not its own name`() {
        // The description is the whole of what the model knows about when to call a tool, and the
        // failure mode is silent: a tool described as "tree" is one the model uses at random.
        for (spec in specs) {
            assertTrue("${spec.name} needs a description", spec.description.length > 20)
            assertTrue(
                "${spec.name}'s description must not just repeat its name",
                spec.description.lowercase() != spec.name.lowercase(),
            )
        }
    }

    @Test
    fun `the schema is derived from the example, so the two cannot disagree`() {
        // **`spec()` is the single place an example becomes a schema.** The call sites stay readable
        // - a filled-in sample says far more than a tree of type declarations - and the conversion
        // happens once, where a twelfth tool cannot forget it.
        val forget = specs.first { it.name == "forget" }
        val ids = forget.parameters.optJSONObject("properties")!!.optJSONObject("ids")!!
        assertEquals("a string, as the example says", "string", ids.optString("type"))
        // The example's value is kept as the description, which is what makes the sample do double
        // duty: it documents the shape and it types the field.
        assertTrue("and the example is its description", ids.optString("description").isNotEmpty())
    }

    @Test
    fun `a tool with an object argument keeps it an object`() {
        // `background` takes `args`, which is itself an object. Typing it "string" would be accepted
        // by every endpoint and would tell the model to send JSON as a quoted string - which then
        // fails at run time, in the tool, a long way from the schema that caused it.
        val background = specs.first { it.name == "background" }
        val args = background.parameters.optJSONObject("properties")!!.optJSONObject("args")!!
        assertEquals("object", args.optString("type"))
    }

    @Test
    fun `no tool takes an argument it never describes`() {
        // **The other half of the same defect.** A schema is what the model is told it may send, so
        // an argument the tool reads but the schema omits is one the model has no way to know about -
        // and the tool then refuses a call the schema invited.
        for (spec in specs) {
            val took = AgentTools.KEYS[spec.name] ?: continue
            val described = spec.parameters.optJSONObject("properties")!!.keys().asSequence().toSet()
            assertEquals(
                "${spec.name}: the schema and the runner must name the same arguments",
                took,
                described,
            )
        }
    }

    @Test
    fun `a tool's parameters survive being written to JSON and read back`() {
        // The schema is serialised into the request body, so a value that only exists in memory -
        // `JSONObject.NULL`, an `Int` where the wire wants a `Number` - would arrive differently
        // than it was built. Round-tripping it here is the cheap version of the same check the
        // endpoint makes.
        for (spec in specs) {
            val once = spec.parameters.toString()
            val twice = JSONObject(once)
            assertEquals("${spec.name} survives a round trip", once, twice.toString())
            assertEquals("object", twice.optString("type"))
        }
    }
}
