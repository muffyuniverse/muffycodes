package com.example.notes

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A message's `content` is a **string** — unless it carries pictures, and then it is an **array**.
 *
 * That asymmetry is the whole of this file, and it is worth its own tests because it is the one place
 * where the app changes the shape of the request every conversation is built from. A message with no
 * picture must serialise exactly as it always has: every provider accepts the string form, and moving
 * the entire conversation to arrays to serve the rare message would be a risk taken for nothing.
 *
 * **Why a picture lives on a message at all.** The contract makes a `role: "tool"` message text-only,
 * so `look` cannot answer with an image. It records one as pending and the next message carries it.
 * The tool branch below is therefore asserted to *not* grow an array — the temptation to put the
 * picture there is exactly the mistake this file exists to catch.
 */
class PicturesInDoorTest {

    private val door = OpenRouterDoor(
        key = { "sk-or-v1-test" },
        model = "deepseek/deepseek-v4.1-flash-1017",
        provider = "deepinfra/fp8/flex",
        effort = "",
    )

    /** One small valid-looking data URL. The bytes are never decoded here — only the shape is read. */
    private val png = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUg=="

    private fun request(vararg messages: Msg) = Request(
        model = "deepseek/deepseek-v4.1-flash-1017",
        provider = "deepinfra/fp8/flex",
        effort = "",
        system = "",
        messages = messages.toList(),
        tools = emptyList(),
    )

    /** The message at [at] in the body, where index 0 is the system message. */
    private fun sent(vararg messages: Msg, at: Int) =
        door.body(request(*messages)).getJSONArray("messages").getJSONObject(at)

    @Test
    fun `a message with no picture is still a plain string`() {
        // **The shape that must not change.** If this ever fails, every conversation the app has ever
        // had is being sent in a form no provider has been asked to accept.
        val one = sent(Msg.user("what is in milk.txt"), at = 1)
        assertEquals("user", one.getString("role"))
        assertTrue(
            "content is a string, not an array — got ${one.get("content")::class.simpleName}",
            one.get("content") is String,
        )
        assertEquals("what is in milk.txt", one.getString("content"))
    }

    @Test
    fun `a message with a picture carries the text first and the picture after`() {
        val one = sent(Msg.user("what does this mean", pictures = listOf(png)), at = 1)

        val parts = one.get("content") as JSONArray
        assertEquals("the words, then the picture", 2, parts.length())

        // **Text before image, in that order.** The model reads what the message is about before it
        // looks at what it is showing, and `look`'s result sentence has already said which path is
        // which — so the order carries the pairing.
        val words = parts.getJSONObject(0)
        assertEquals("text", words.getString("type"))
        assertEquals("what does this mean", words.getString("text"))

        val picture = parts.getJSONObject(1)
        assertEquals("image_url", picture.getString("type"))
        assertEquals(png, picture.getJSONObject("image_url").getString("url"))
    }

    @Test
    fun `a message that is only a picture has no empty text part`() {
        // A `"text": ""` in front of the picture is a blank line the model has to read past. An
        // absent part and an empty one are not the same thing.
        val one = sent(Msg.user("", pictures = listOf(png)), at = 1)

        val parts = one.get("content") as JSONArray
        assertEquals("the picture alone", 1, parts.length())
        assertEquals("image_url", parts.getJSONObject(0).getString("type"))
    }

    @Test
    fun `several pictures keep the order they were named in`() {
        val second = "data:image/png;base64,AAAA"
        val one = sent(Msg.user("before and after", pictures = listOf(png, second)), at = 1)

        val parts = one.get("content") as JSONArray
        assertEquals("the words and both pictures", 3, parts.length())
        assertEquals(
            "the first named is the first sent",
            png,
            parts.getJSONObject(1).getJSONObject("image_url").getString("url"),
        )
        assertEquals(
            "and the second follows it",
            second,
            parts.getJSONObject(2).getJSONObject("image_url").getString("url"),
        )
    }

    @Test
    fun `a tool result cannot carry a picture, and does not pretend to`() {
        // **The API's shape, not a choice.** `role: "tool"` is text-only, which is why `look` records a
        // pending picture for the next message rather than answering with one.
        //
        // The message is built through the **constructor** rather than `Msg.tool`, and deliberately:
        // `tool()` takes no pictures, so the only way to ask this question at all is to hand the
        // branch one it should refuse. That is the point — a future edit that starts emitting an
        // array here would produce a body the provider rejects, and nothing else in the suite looks
        // at this branch.
        val tool = Msg("tool", "attached: a.png (1024×768)", callId = "call_1", pictures = listOf(png))
        val one = sent(tool, at = 1)

        assertEquals("tool", one.getString("role"))
        assertEquals("call_1", one.getString("tool_call_id"))
        assertTrue(
            "a tool result's content is still a string",
            one.get("content") is String,
        )
        assertEquals("attached: a.png (1024×768)", one.getString("content"))
    }

    @Test
    fun `an assistant turn with calls is unchanged by the picture field`() {
        // The branch that writes `content` itself, and the one most likely to be broken by a careless
        // edit: an assistant turn's content is null when it only called tools. Pictures belong to the
        // operator's turn, never to this one.
        val call = Call("call_1", "read", """{"path":"a.txt"}""")
        val one = sent(Msg.assistant("", calls = listOf(call)), at = 1)

        assertEquals("assistant", one.getString("role"))
        assertTrue("still null, not an empty array", one.isNull("content"))
        assertEquals("the call is there", 1, one.getJSONArray("tool_calls").length())
    }
}
