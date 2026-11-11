package com.example.notes

/**
 * A door that answers from a script.
 *
 * Two things it is for, and both are about tests being worth something: the loop can be driven
 * through every path it has — a call, two calls, a terminating call, a refusal — without a
 * network, and `asked` is the record of exactly what the app *sent*, which is the only way to
 * assert that the instructions, the context and the seam were in the request at all.
 *
 * **It lived in `Door.kt` and shipped in the APK** while only tests used it. A test double in the
 * main source set is a class the app carries and no path can reach.
 */
class ScriptedDoor(vararg script: Reply) : Door {

    private val replies = ArrayDeque(script.toList())

    /** Every request the app made, in order. */
    val asked = mutableListOf<Request>()

    /** Every keepalive ping, in order — **not** counted as a question, and it consumes no reply. */
    val pings = mutableListOf<Request>()

    override fun turn(request: Request): Result<Reply> {
        asked.add(request)
        val next = replies.removeFirstOrNull()
            ?: return Result.failure(IllegalStateException("the script ran out of replies"))
        return Result.success(next)
    }

    override fun ping(request: Request): Result<Unit> {
        pings.add(request)
        return Result.success(Unit)
    }
}
