package com.example.notes

/**
 * One agent, as the app sees it: the three states, the poll's clock, and the words a person reads.
 *
 * **This is a state machine and nothing else.** It holds no file, no service and no socket, so
 * every rule the design states about waking, reminding and stopping can be tested by moving a
 * number — which is the only way to test four minutes and one hour without waiting for them.
 * The Android side is a thin shell that asks this object what to do.
 *
 * The design's table, exactly:
 *
 * | state | meaning | notification |
 * |---|---|---|
 * | idle | polling in `wait` · concluded · reconnecting | none |
 * | asked | called `ask` | now, then every interval until answered |
 * | stopped | by the operator · by the phone · an error | only for a cause the operator did not choose |
 *
 * A *session* is one per project, because a session is a conversation and a conversation belongs
 * to a project.
 */
data class AgentSession(
    val project: String,
    val state: State = State.IDLE,
    /** What an idle agent is doing: nothing yet, waiting, or getting its network back. */
    val sub: Sub = Sub.WAITING,
    /** Why it stopped, and null while it is running. */
    val reason: StopReason? = null,
    /** Consecutive failed polls, which is what the reconnect label counts. */
    val retries: Int = 0,
    /** When the agent last ended a turn in `ask`. */
    val askedAt: Long = 0,
    /** Reminders already sent for this ask, so the second one says "2nd". */
    val reminders: Int = 0,
    /** When the last poll was sent; the next one is one interval after it. */
    val lastPollAt: Long = 0,
    /**
     * What the app last said about why this stopped.
     *
     * **A constructor parameter, not a property set in the body**, because every change here goes
     * through `copy()` — and `copy` does not carry a body property. It would have been dropped on
     * the first transition, which is the one moment it is needed.
     */
    val lastWords: String = "",
) {

    /** The three states of §2.3, and the two the operator did not choose are the ones that speak. */
    enum class State { IDLE, ASKED, STOPPED }

    /** The idle agent's three sub-labels. */
    enum class Sub { WAITING, FINISHED, RECONNECTING }

    /**
     * Why a session stopped.
     *
     * **Three of these are the app's own doing and one is the person's**, and the difference is
     * the whole notification policy: a person who switched an agent off does not need to be told,
     * and a person whose agent died of a provider refusal does.
     */
    enum class StopReason {
        /** The operator switched it off. Silent, because they were there. */
        BY_OPERATOR,

        /** The system ended it: a force-stop, an OEM battery manager. */
        BY_THE_PHONE,

        /** The pinned provider refused, with no fallback by design. */
        PIN_REFUSED,

        /** It kept calling tools without doing anything, so the loop stopped it. */
        LOOP,

        /** Something else went wrong, and [AgentSession.lastWords] holds what. */
        ERROR,
    }

    companion object {
        /** The poll interval: four minutes, and it is the design's number, not a tuning knob. */
        const val POLL_MS = 4 * 60 * 1000L

        /** The reminder interval's default. The operator may change it in Settings. */
        const val REMIND_MS = 60 * 60 * 1000L

        /** The reconnect ladder: one second, then doubling to four, and no further. */
        const val RETRY_CAP_MS = 4_000L

        /** How many times a failing poll reconnects before the session admits it is a wall. */
        const val MAX_RETRIES = 8

        /** `2nd`, `3rd`… and `11th`, which is why the last two digits are checked first. */
        fun ordinal(n: Int): String {
            val suffix = when {
                n % 100 in 11..13 -> "th"
                n % 10 == 1 -> "st"
                n % 10 == 2 -> "nd"
                n % 10 == 3 -> "rd"
                else -> "th"
            }
            return "$n$suffix"
        }

        /**
         * The strip above the folder listing: `2 sessions · 1 needs you`.
         *
         * A stopped session the operator chose is not counted as needing them, and a session
         * that is merely `finished` is not either — the strip is a question, not a census.
         */
        fun strip(sessions: List<AgentSession>): String {
            val on = sessions.count { it.running }
            val needs = sessions.count { it.needsYou }
            if (on == 0 && needs == 0) return ""
            val sessionsWord = if (on == 1) "1 session" else "$on sessions"
            return if (needs == 0) sessionsWord else "$sessionsWord · $needs needs you"
        }
    }

    // ---- the three states ---------------------------------------------------

    /** True while the agent is switched on, whether it is working or waiting. */
    val running: Boolean get() = state != State.STOPPED

    /** True when there is nothing for the person to do. */
    val healthy: Boolean get() = state == State.IDLE

    /**
     * True when the session needs a person — which is what the strip counts.
     *
     * A stopped session the operator chose does **not** count: they stopped it, and an app that
     * kept asking about it would be arguing with them.
     */
    val needsYou: Boolean
        get() = state == State.ASKED || (state == State.STOPPED && reason != StopReason.BY_OPERATOR)

    // ---- the poll's clock ---------------------------------------------------

    /** When the next poll is due, one interval after the last one rather than one after now. */
    val nextPollAt: Long get() = lastPollAt + POLL_MS

    /**
     * True when a poll is owed at [at].
     *
     * **Only an idle agent that is waiting polls.** An agent in `ask` is waiting for the person,
     * and polling it would spend money asking the same question again; an agent that concluded
     * has nothing to do until it is spoken to; one that is reconnecting is already trying.
     */
    fun pollDue(at: Long): Boolean = when {
        state != State.IDLE -> false
        // Finished means the agent concluded: it has nothing to poll for until it is spoken to.
        sub == Sub.FINISHED -> false
        // Waiting polls every interval; reconnecting polls at its retry time, which is what
        // `failed` moved `lastPollAt` to. Treating reconnecting as "not due" would make one
        // dropped packet end the session, which is the opposite of the retry ladder's purpose.
        else -> at >= nextPollAt
    }

    /** How long until the next poll, for a label; never negative. */
    fun untilPoll(at: Long): Long = (nextPollAt - at).coerceAtLeast(0)

    /** A poll was sent at [at]. */
    fun polled(at: Long): AgentSession = copy(sub = Sub.WAITING, lastPollAt = at, retries = 0)

    /** A poll could not be sent, or was refused: the ladder decides when to try again. */
    fun failed(at: Long): AgentSession {
        val tries = retries + 1
        // **A blip reconnects; a wall stops.** Eight attempts at one, two and four seconds is
        // half a minute of trying — long enough to survive a tunnel, an elevator, a network
        // change, and short enough that a key that will never work is not retried for ever at
        // four seconds a time on somebody's battery.
        if (tries > MAX_RETRIES) {
            return stopped(
                StopReason.PIN_REFUSED,
                lastWords.ifBlank { "the provider refused $tries times" },
            )
        }
        return copy(
            sub = Sub.RECONNECTING,
            retries = tries,
            lastPollAt = at - POLL_MS + retryDelay(tries),
        )
    }

    /** The wait before try number [tryNumber], one-based: 1 s, 2 s, 4 s, 4 s, 4 s… */
    fun retryDelay(tryNumber: Int): Long = when {
        tryNumber <= 0 -> 1_000L
        else -> minOf(1_000L shl (tryNumber - 1).coerceAtMost(2), RETRY_CAP_MS)
    }

    /** The agent is back: whatever the ladder was doing, it is over. */
    fun reconnected(at: Long): AgentSession = copy(sub = Sub.WAITING, retries = 0, lastPollAt = at)

    // ---- the endings --------------------------------------------------------

    /** `ask` ended a turn: the person is needed, and the reminder clock starts now. */
    fun asked(at: Long): AgentSession = copy(
        state = State.ASKED,
        sub = Sub.WAITING,
        reason = null,
        retries = 0,
        askedAt = at,
        reminders = 0,
    )

    /** `conclude` ended a turn: idle, and silent — there is nothing wrong with finishing. */
    fun concluded(): AgentSession = copy(
        state = State.IDLE,
        sub = Sub.FINISHED,
        reason = null,
        retries = 0,
        askedAt = 0,
        reminders = 0,
    )

    /** `wait` ended a turn: idle and polling. */
    fun waited(at: Long): AgentSession = copy(
        state = State.IDLE,
        sub = Sub.WAITING,
        reason = null,
        retries = 0,
        askedAt = 0,
        reminders = 0,
        // The interval restarts from the `wait`: an agent that has just finished a turn has
        // nothing to check for four minutes, and a poll fired on the spot would ask it again
        // about the turn it just ended.
        lastPollAt = at,
    )

    /**
     * The session stopped.
     *
     * A turn that ends in `Stopped` is not always the session stopping: a refusal the agent can
     * recover from next turn is not a stop. This is called when the app decides the session is
     * over, or when a poll fails in a way that will not heal.
     */
    fun stopped(why: StopReason, words: String = ""): AgentSession = copy(
        state = State.STOPPED,
        reason = why,
        retries = 0,
        lastWords = words,
    )

    // ---- the reminder ladder ------------------------------------------------

    /**
     * True when a reminder is due at [at].
     *
     * **Only for a session that needs a person.** A healthy agent must be silent: a notification
     * that fires every hour for a working agent is a notification somebody learns to ignore, and
     * an agent in `wait` is working exactly as designed.
     */
    fun reminderDue(at: Long, intervalMs: Long = REMIND_MS): Boolean {
        if (!needsYou) return false
        if (intervalMs <= 0) return false
        // **A clock that went backwards does not queue reminders.** Time can move back — a
        // timezone change, a device synced to a different server — and `at - askedAt` going
        // negative is not a reason to fire.
        if (at < askedAt) return false
        return at - askedAt >= intervalMs * (reminders + 1)
    }

    /** One reminder was sent, which is what makes the next one say "2nd" and then "3rd". */
    fun reminded(): AgentSession = copy(reminders = reminders + 1)

    // ---- what a person reads ------------------------------------------------

    /**
     * The state line the box and the strip show.
     *
     * `idle · waiting` · `idle · finished` · `idle · reconnecting (4 s)` · `asked · 12 min` ·
     * `stopped · the phone stopped the app`.
     */
    fun label(at: Long = 0): String = when (state) {
        State.IDLE -> "idle · " + when (sub) {
            Sub.WAITING -> "waiting"
            Sub.FINISHED -> "finished"
            Sub.RECONNECTING -> "reconnecting (${retryDelay(retries) / 1000} s)"
        }

        State.ASKED -> "asked" + if (askedAt > 0 && at > askedAt) {
            " · ${(at - askedAt) / 60_000} min"
        } else {
            ""
        }

        State.STOPPED -> "stopped · " + stopWords(this)
    }

    /**
     * The sentence a notification would carry, or null when this session should be silent.
     *
     * One notification per session, replaced in place, prefixed with its count — the design's
     * `2nd reminder · shopping: the agent asked something`. A tray with twenty-four copies of one
     * sentence is a tray nobody reads.
     */
    fun notice(): String? {
        if (!needsYou) return null
        val said = when (state) {
            State.ASKED -> "$project: the agent asked something"
            State.STOPPED -> "$project: stopped, ${stopWords(this)}"
            State.IDLE -> return null
        }
        return if (reminders <= 0) said else "${ordinal(reminders + 1)} reminder · $said"
    }

    private fun stopWords(s: AgentSession): String = when (s.reason) {
        StopReason.BY_OPERATOR -> "stopped by you"
        StopReason.BY_THE_PHONE -> "the phone stopped the app"
        StopReason.PIN_REFUSED -> s.lastWords.ifBlank { "the pinned provider refused" }
        StopReason.LOOP -> "it kept going without doing anything"
        StopReason.ERROR -> s.lastWords.ifBlank { "it could not go on" }
        null -> "not running"
    }

}
