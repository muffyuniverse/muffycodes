package com.example.notes

/**
 * **"Check this connection" — one small request, and the server's own words.**
 *
 * `PLAN-credentials` §2.4b: *"Compatible" is a claim; this is the measurement.* A window that stores an
 * address, a key and a shape is a set of beliefs about a server, and three of them can be wrong
 * independently: the address may not be there, the key may be refused, and the shape may name a path or
 * a field the server does not have. A person who is told which of the three it was fixes one thing; a
 * person who is told "it did not work" fixes all three, or none.
 *
 * **The answer is the server's, not the app's.** A refusal carries the server's own sentence (the
 * door unwraps `error.metadata.raw`), because "the shape is wrong" would leave a person reading a
 * template they can see and guessing which field a server they cannot see objects to.
 *
 * **One request, and the smallest one.** It is a real completion rather than a `/models` read: the
 * catalogue is a convenience some servers have and a chat completion is the protocol, so asking for
 * the thing the app will actually send is the only check that covers the model id, the key and the
 * shape together. It costs about one output token (see `Door.PING_TOKENS`), and a model server on the
 * phone costs nothing at all.
 */
object Probe {

    /**
     * The request a check sends: **the shape of a real turn, with nothing in it.**
     *
     * Empty system prompt, one short message, no tools. The tools are left out rather than sent empty
     * so a server that never sees a `tools` field is not made to answer a question about one — what is
     * being checked is the address, the key, the model and the shape, and a tool call is none of those.
     */
    fun request(model: String, provider: String, effort: String): Request = Request(
        model = model,
        provider = provider,
        effort = effort,
        system = "",
        messages = listOf(Msg(role = "user", text = "ping")),
        tools = emptyList(),
    )

    /**
     * What the server said, in one sentence a person can act on.
     *
     * **Reachable, key accepted and model served are one success**, because a completion that arrives
     * proves all three: it could not have arrived otherwise. A failure is the door's own sentence, which
     * already names the status and any `error.metadata.raw` the server sent — so this adds the frame and
     * nothing else.
     */
    fun check(door: Door, request: Request): String = try {
        door.ping(request).getOrThrow()
        "The server answered, so it is reachable, the key was accepted and it serves " +
            "${request.model}. The shape fits."
    } catch (e: Throwable) {
        // **The status is named when the door carried one**, because `Refused` keeps only the
        // server's sentence in its message: 401 (the key), 404 (the path the shape names) and 400 (a
        // field the server will not take) send a person to three different places, and the number is
        // the fastest way to tell them apart.
        val code = (e as? Refused)?.code?.let { " ($it)" }.orEmpty()
        "The server did not answer as this connection expects$code: " +
            Secrets.explain(e, "It refused")
    }
}
