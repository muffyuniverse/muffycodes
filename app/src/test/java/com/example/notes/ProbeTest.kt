package com.example.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **"Check this connection", which is a measurement rather than a claim.**
 *
 * The plan's words: a window that stores an address, a key and a shape is a set of beliefs about a
 * server, and three of them can be wrong independently. What this class asserts is that each of the
 * three comes back as its own sentence — because a person told "it did not work" fixes all three, or
 * none, and the server is the only one that knows which.
 */
class ProbeTest {

    private fun request() = Probe.request("llama3", "localhost", "low")

    /** A door that answers a ping the way a server would: with nothing, or with a refusal. */
    private class DoorThat(private val refuses: Throwable? = null) : Door {
        var pings = 0
        override fun turn(request: Request): Result<Door.Reply> =
            Result.failure(IllegalStateException("a check must not ask for a turn"))

        override fun ping(request: Request): Result<Unit> {
            pings += 1
            return refuses?.let { Result.failure(it) } ?: Result.success(Unit)
        }
    }

    @Test
    fun `an answer proves the address, the key and the model at once`() {
        // A completion that arrives could not have arrived otherwise, so one success covers all three
        // and the sentence says so rather than saying "OK".
        val door = DoorThat()

        val said = Probe.check(door, request())

        assertEquals("one ping, and not a turn", 1, door.pings)
        assertTrue("it says the server answered: $said", "answered" in said)
        assertTrue("and names the model it serves: $said", "llama3" in said)
        assertFalse("so it is not a complaint", "not answer" in said)
    }

    @Test
    fun `a refusal keeps the server's own words`() {
        // **The whole point of a probe.** "The shape is wrong" would leave a person reading a template
        // they can see and guessing which field a server they cannot see objects to. The status and the
        // server's own sentence are what a door's refusal carries, and they arrive here unchanged.
        val door = DoorThat(Refused(400, "The model does not take `reasoning_effort`"))

        val said = Probe.check(door, request())

        assertTrue("it says it did not work: $said", "did not answer" in said)
        assertTrue("and repeats what the server said: $said", "reasoning_effort" in said)
        assertTrue("including the status: $said", "400" in said)
    }

    @Test
    fun `the request a check sends is a real turn and carries no tools`() {
        // **Small, but not a shape of its own.** A `/models` read is a convenience some servers have
        // and a chat completion is the protocol, so the check asks for the thing the app will send —
        // and it leaves the tools out rather than sending an empty list, so a server that has never
        // seen a `tools` field is not made to answer a question about one.
        val one = request()

        assertEquals("ping", one.messages.single().text)
        assertEquals("user", one.messages.single().role)
        assertTrue("no tools", one.tools.isEmpty())
    }
}
