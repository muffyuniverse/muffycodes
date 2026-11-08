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
    fun `the prompt does not name a tool that no longer exists`() {
        // **Report 39 in the 1285 trace**: "why system prompt still tells about `ask` and `conclude` -
        // they were removed." It was right, and the contradiction was total: the prompt told the model
        // two turn-ending tools existed, [AgentTools.run] never dispatched either, and `KEYS` no longer
        // listed them — so a model following the brief was told to call things that answer as unknown.
        //
        // **A prompt is the one file no compiler checks.** Every other tool name is reachable from a
        // `when` that has to compile; the brief is a string, and a string can describe a mechanism that
        // was deleted a week ago. So the assertion is on the text, against the live tool list.
        val brief = AgentPrompt.read(java.io.File("."), java.io.File(".")).system
        val names = specs.map { it.name }.toSet()
        for (gone in listOf("ask", "conclude", "wait")) {
            assertFalse(
                "the brief must not name `$gone`, which is not in the tool list ($names)",
                Regex("""(^|\s)`?$gone`?\s+(you need|the work|to sit)""").containsMatchIn(brief),
            )
        }
        // **And the ending it does describe is the one that exists**: calling nothing.
        assertTrue(
            "the brief says how a turn ends",
            brief.contains("A turn ends when you call nothing"),
        )
    }

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
        //
        // `ci` and `runs` joined it when the agent was given sight of GitHub Actions. **Both are
        // read-only**: a tool that starts a run is a different grant, and it waits on the workflow
        // guard (a run the agent dispatches must not be able to reach repository secrets).
        assertEquals(
            "and the twenty-one that are",
            listOf(
                // **`search` is every read of the tree there is.** It absorbed `tree` and `history`:
                // `scope` names where, `query` names what, `history` widens it to saved versions —
                // three questions that were three commands and are now six keys of one.
                "search", "read", "write", "forget", "delete",
                "move",
                // **`query` is the agent's own reading of what it cost.** It is the one tool that
                // answers questions nobody wrote a tool for, and it sits beside the request log's
                // own card: both read the same database, one for a glance and one for a question.
                "query",
                "restore", "background", "browse", "shot",
                // **`look` sits beside `shot`**, because they are the two halves of one loop:
                // `shot` makes a picture and nothing could consume one until `look` existed.
                "look",
                "ci", "runs",
                // **The ones that move a file rather than words.** `artifact` fetches what a run
                // produced; `downloads` lists the phone's folder, `download` copies out of it and
                // `upload` is the only way anything leaves the project.
                "artifact", "downloads", "download", "upload",
                // **`archive` is what makes the `upload` sentence true.** It packs a folder into
                // one file for that flat folder, and it unpacks a fetched dependency into the
                // project — the two directions of one capability, which is why it is one tool.
                "archive",
                // **`fetch` is the missing primitive**, and it is the only tool that turns a URL
                // into a file: `browse` gives a page's text, `shot` a picture, `artifact` one file
                // from a CI run, and none of them takes an address.
                "fetch",
                // **`mcp` is one tool for every server**, not one tool per remote tool: the tool
                // list is part of every request's cache prefix, so a server's own catalogue is
                // something the agent reads on demand instead.
                "mcp",
            ),
            specs.map { it.name },
        )
    }

    @Test
    fun `turning the network off removes the tool rather than making it refuse`() {
        // **A described tool is a tool the model reaches for**, and its description is paid for in every
        // request's cache prefix — so a capability a person turned off must not be in the list at all.
        val without = AgentTools.specs(httpAllowed = false).map { it.name }

        assertFalse("http is not offered: $without", "http" in without)
        // **And everything else still is**, because a switch that quietly removed a second tool would be a
        // worse bug than the one it fixed.
        assertEquals("only http went", specs.map { it.name } - "http", without)
    }

    @Test
    fun `a tool's description is a sentence a model can act on, not its own name`() {
        // The description is the whole of what the model knows about when to call a tool, and the
        // failure mode is silent: a tool described as "search" is one the model uses at random.
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
    fun `push and pull take no argument, so no agent can name a branch`() {
        // **The portable half of the isolation rule**, and the reason it survives every provider
        // decision (PLAN-portable-2026-11-08 §3): `push` sends `currentBranch(repo)` and there is no
        // argument through which a model could name another ref. A `branch` argument here would turn
        // "an agent can only send the branch it stands in" into a promise, and a promise is what the
        // next refactor removes. This test is the thing that would fail first.
        assertEquals("push takes no argument", emptySet<String>(), AgentTools.KEYS["push"])
        assertEquals("and neither does pull", emptySet<String>(), AgentTools.KEYS["pull"])
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
