package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stream's own protocol, **read from recordings rather than from a live socket.**
 *
 * Every fixture under `streams/` is a real server-sent-event body, kept as it arrived. That is the
 * point: the shapes that break a stream parser — an arguments object split mid-string, a provider
 * that reuses `index: 0`, reasoning under two different field names, a keepalive comment before any
 * content — are shapes somebody has to *have seen*. Written by hand from the documentation, a
 * fixture tests the documentation.
 *
 * The suite never opens a socket, so this is where the streaming rules can be checked at all.
 */
class StreamTest {

    private fun recorded(name: String): Streamed {
        // **From the classpath, because that is where a test resource lives** — a relative path
        // depends on the working directory Gradle happens to choose, which is a fact about the
        // build rather than about the app.
        val text = checkNotNull(javaClass.classLoader?.getResourceAsStream("streams/$name")) {
            "no recording called $name in src/test/resources/streams"
        }.bufferedReader().use { it.readText() }
        val stream = Streamed()
        serverSentLines(text).forEach { stream.line(it) }
        return stream
    }

    @Test
    fun `a tool call arrives in fragments and is one call when the stream ends`() {
        // The arguments arrive as `{"pa` then `th":""}` — two slices of one object. A parser that
        // kept the last fragment would answer with half an object; one that parsed each fragment
        // would answer with nothing at all.
        val stream = recorded("tool-call.sse")

        assertEquals("one call, not one per fragment", 1, stream.callCount)
        val call = stream.calls.single()
        assertEquals("the id from the first fragment", "call_a", call.id)
        assertEquals("the name from the first fragment", "tree", call.tool)
        assertEquals("the arguments, joined", """{"path":""}""", call.args)
    }

    @Test
    fun `two calls that both claim index zero are still two calls`() {
        // **This is the bug the id is for.** A provider that reuses `index: 0` for every call is a
        // real behaviour, and keying only by index folds them into one — the first call's name and
        // the second call's arguments, which is worse than either alone.
        val stream = recorded("two-calls-index-reused.sse")

        assertEquals("both calls are there", 2, stream.callCount)
        assertEquals("the first is the tree", "tree", stream.calls[0].tool)
        assertEquals("with its own arguments", """{"path":""}""", stream.calls[0].args)
        assertEquals("the second is the read", "read", stream.calls[1].tool)
        assertEquals(
            "with arguments joined across its own fragments",
            "{\"path\":\"milk.txt\"}",
            stream.calls[1].args,
        )
    }

    @Test
    fun `an answer is assembled from pieces that split mid-word`() {
        val stream = recorded("answer.sse")

        assertEquals("the words, joined", "There is one file.", stream.reply().text)
        assertEquals("and no calls", 0, stream.callCount)
        assertEquals("and the provider's reason for stopping", "stop", stream.finish)
    }

    @Test
    fun `reasoning is counted under both names it arrives in`() {
        // **Two fields, because the field was renamed and both are still sent.** Reading one is how
        // a model that is working looks dead — and "is it working" is the question the idle timeout
        // asks, so getting this wrong does not only cost a footer number.
        val stream = recorded("tool-call.sse")

        assertEquals(
            "both the plain field and the details array",
            "The person wants to know what is in the project, so tree.A tree call.",
            stream.thinking.toString(),
        )
    }

    @Test
    fun `the answer is what arrived, in characters, because that is what is known`() {
        // **Characters and not tokens.** A per-chunk `usage` block is not guaranteed, so a token
        // count taken per chunk would be an estimate dressed as a measurement. The real totals
        // arrive once, at the end, and are read from there.
        val stream = recorded("answer.sse")

        assertEquals("the length is the words' length", 18, stream.said.length)
        assertEquals("and the end of the stream is where the totals are", 14, stream.usage?.optInt("total_tokens"))
    }

    @Test
    fun `a stream is acknowledged by a comment as readily as by content`() {
        // **The ack timeout has to know the connection is alive**, and an upstream that is queueing
        // says so with a comment line and nothing else. Treating that as "nothing arrived" is how a
        // working request is killed before it starts.
        val fresh = Streamed()
        assertFalse("nothing yet", fresh.acknowledged)

        fresh.line(": OPENROUTER PROCESSING")

        assertTrue("a keepalive comment is an acknowledgement", fresh.acknowledged)
        assertEquals("and it is not content", 0, fresh.said.length)
    }

    @Test
    fun `the end marker is a line of the protocol, not a failure`() {
        val stream = Streamed()
        stream.line("""data: {"choices":[{"index":0,"delta":{"content":"hi"}}]}""")
        stream.line("data: [DONE]")

        assertEquals("the words before it survive", "hi", stream.reply().text)
    }

    @Test
    fun `a line that is not JSON is dropped, and the rest of the answer still arrives`() {
        // A stream is cut into lines by the transport. A line the app cannot use must not take the
        // answer with it, because the answer is usually already half-delivered by then.
        val stream = Streamed()
        stream.line("data: this line is not JSON")
        stream.line("""data: {"choices":[{"index":0,"delta":{"content":"still here"}}]}""")

        assertEquals("the usable part is kept", "still here", stream.reply().text)
    }

    // ---- the two clocks ------------------------------------------------------

    private fun door(ackSeconds: Int = 90, gapSeconds: Int = 120) = OpenRouterDoor(
        key = { "sk-or-v1-the-persons-key" },
        model = "deepseek/deepseek-v4.1-flash-1017",
        provider = "decart/fp4",
        effort = "high",
        ackMs = ackSeconds * 1000,
        gapMs = gapSeconds * 1000,
    )

    @Test
    fun `a stall before the first chunk is the ack clock, and it costs nothing to retry`() {
        // **The two silences, and why they cannot share a number.** Nothing has arrived, so the
        // provider never took the request: no tokens were generated and repeating it is free. That
        // is what lets this clock be generous - it is not paying for anything while it waits.
        val silent = Streamed()
        val stall = door(ackSeconds = 90, gapSeconds = 120).stall(silent)

        assertTrue("it is the ack clock that ran out", stall is Unacknowledged)
        assertEquals("and it says how long it waited", 90_000L, (stall as Unacknowledged).waitedMs)
        assertEquals("and that nothing had arrived", 0, stall.stream.said.length)
    }

    @Test
    fun `a stall after the first chunk is the idle clock, because an answer was being written`() {
        // The opposite decision, from the same event. An answer had begun, so the work is real and
        // in progress; the connection died rather than the model thinking. Killing this is right,
        // and retrying it is not free - which is why the two clocks are two settings.
        val begun = Streamed()
        begun.line(""": OPENROUTER PROCESSING""")
        begun.line("""data: {"choices":[{"index":0,"delta":{"content":"There is "}}]}""")

        val stall = door(ackSeconds = 90, gapSeconds = 120).stall(begun)

        assertTrue("it is the idle clock that ran out", stall is Idle)
        assertEquals("and it says how long the gap was", 120_000L, (stall as Idle).waitedMs)
        assertEquals("and what had already arrived", "There is ", stall.stream.said.toString())
    }

    @Test
    fun `a comment line counts as the acknowledgement, because it is proof the request arrived`() {
        // **The case that makes the ack clock usable.** An upstream that is queueing sends a comment
        // and nothing else, and treating that as silence would kill a request that is working
        // perfectly - at the busiest moment, when the queue is longest.
        val queueing = Streamed()
        queueing.line(": OPENROUTER PROCESSING")

        assertTrue("a comment is an ack, so this is the idle clock", door().stall(queueing) is Idle)
    }

    @Test
    fun `the footer says elapsed and produced, and never a fraction`() {
        val stream = recorded("answer.sse")

        val line = stream.shape(elapsedMs = 4_000)

        assertEquals("the two numbers a person needs: 4s · 18 characters · 14 tokens", "4s · 18 characters · 14 tokens", line)
        assertFalse("and no percentage, because there is no denominator", line.contains("%"))
    }

    @Test
    fun `the footer counts calls, because a call is what it did rather than what it said`() {
        val line = recorded("tool-call.sse").shape(elapsedMs = 61_000)

        assertTrue("a minute in, with one call made: $line", line.startsWith("61s"))
        assertTrue("the call is counted: $line", line.contains("1 call"))
        assertTrue("and the thinking, which is why it is not dead: $line", line.contains("thinking"))
    }

    @Test
    fun `a refusal in the middle of a stream is still a refusal`() {
        val stream = Streamed()
        val said = try {
            stream.line("""data: {"error":{"message":"out of credit"}}""")
            ""
        } catch (t: IllegalStateException) {
            t.message.orEmpty()
        }

        assertTrue("the provider's own words, not a parse error: $said", said.contains("out of credit"))
    }
}
