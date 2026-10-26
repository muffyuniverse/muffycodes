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
 * | stopped | by the operator · an error | only for a cause the operator did not choose |
 *
 * **There is no "stopped by the phone" state, and its absence is deliberate.** The design named
 * one, and the app cannot honour both it and the stronger rule it sits under: "it must never stop
 * until the operator removes it". A phone that force-stops the app has not been asked to end the
 * session — so on resume the session goes on, and what the phone did is written down where it can
 * be read ([AgentSession.missedBy] and the row the runner writes). A state that says "stopped"
 * about a session that is about to carry on would be the label lying about the fact.
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
    /**
     * The poll interval this session was started with, in milliseconds — **a copy of the setting.**
     *
     * Kept on the session so a tick can be answered from the file alone: the runner is rebuilt, the
     * process is killed, the phone reboots, and the session that comes back still knows how often it
     * is checked on. Zero means the file has none, and the design's [POLL_MS] applies.
     */
    val idleMs: Long = 0,
    /**
     * When this session started waiting on the person, which is what the keepalive's ceiling runs
     * from — **not the last ping**, or the ceiling would never arrive.
     *
     * Zero on a file written before the keepalive existed; [warmSince] reads that as the last poll,
     * which is the closest thing an older file has to the moment the wait began.
     */
    val warmFrom: Long = 0,
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

        /** The pinned provider refused, with no fallback by design. */
        PIN_REFUSED,

        /** It kept calling tools without doing anything, so the loop stopped it. */
        LOOP,

        /** Something else went wrong, and [AgentSession.lastWords] holds what. */
        ERROR,
    }

    companion object {
        /** The poll interval: four minutes, and it is the design's number, not a tuning knob. */
        /**
         * The app's own interval, for an agent that did not say what it wanted.
         *
         * Every four minutes is the design's number: between a provider's cache lifetime and a
         * person's patience, and it is what an agent that ends a turn in `wait` gets having asked
         * for nothing.
         */
        const val POLL_MS = 4 * 60 * 1000L

        /**
         * The longest polling interval a person may set: 250 seconds.
         *
         * **A ceiling on the setting, not a default.** Beyond four minutes the app is checking less
         * often than an agent that is waiting on somebody would want, and a session that went quiet
         * for an hour could not be told apart from one the phone had stopped. The number is the
         * operator's: "max in seconds (max limit is 250 sec)".
         */
        const val MAX_IDLE_MS = 250_000L

        /** The reminder interval's default. The operator may change it in Settings. */
        const val REMIND_MS = 60 * 60 * 1000L

        /** The reconnect ladder: one second, then doubling to four, and no further. */
        const val RETRY_CAP_MS = 4_000L

        /** How many times a failing poll reconnects before the session admits it is a wall. */
        const val MAX_RETRIES = 8

        /**
         * How many intervals late a poll has to be before the app says the phone was not running it.
         *
         * A multiple rather than a fixed number of minutes, because the interval is the operator's:
         * three late checks of a one-minute session is three minutes, and of a four-minute one is
         * twelve. A single constant would have been wrong for every setting but the default.
         */
        const val GAP_INTERVALS = 3

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
    /**
     * The interval before this session's next poll — **the operator's number.**
     *
     * The agent does not choose it and cannot ask for one: `wait` is a statement that it is idle,
     * and how often an idle agent is checked on is a person's decision about their phone, their
     * provider bill and their battery. [idleMs] carries that decision, and a session with none (a
     * test, or a file written before this existed) falls back to the design's [POLL_MS].
     */
    val nextPollAt: Long get() = lastPollAt + intervalMs

    /** This session's poll interval: the setting it was given, or the design's own. */
    val intervalMs: Long get() = idleMs.takeIf { it > 0 } ?: POLL_MS

    /** The moment the wait began: [warmFrom], or the last poll on a file written before it existed. */
    val warmSince: Long get() = if (warmFrom > 0) warmFrom else lastPollAt

    /**
     * True when the conversation should be kept warm at [at].
     *
     * **Every session waiting on the person, not only one that said `wait`.** An agent that asked a
     * question is the one most likely to be answered in the next few minutes, and an agent that
     * concluded is one the person will add to — both had their cache left to expire until now,
     * because a poll was something only an idle `wait` asked for.
     *
     * Three refusals. A stopped session is not waiting for anybody: the person said stop, and
     * spending their money to keep a conversation warm they switched off is the app arguing with
     * them. A check that already fails eight times in a row needs no ninth. And past [forAt] the
     * arithmetic has turned: a ping reads the whole prompt at cache price, so keeping it warm for
     * longer than a dozen of them costs more than letting it go cold and paying for one fresh
     * prompt later.
     *
     * **A reconnecting session is not refused.** Its ladder *is* the next check: `failed` moves
     * `lastPollAt` to one, two, four seconds from now, and a tick that skipped it because it was
     * reconnecting would make one dropped packet end the session.
     *
     * [keepAlive] is the operator's switch, passed in rather than stored here, so a switch flipped
     * while the app is running takes effect at the next tick.
     */
    fun warmDue(
        at: Long,
        keepAlive: Boolean,
        every: Long = intervalMs,
        forAt: Long = 0,
        from: Long = warmSince,
    ): Boolean = when {
        !keepAlive -> false
        state == State.STOPPED -> false
        forAt > 0 && from > 0 && at - from >= forAt -> false
        else -> at >= lastPollAt + every
    }

    /** How long until the next poll, for a label; never negative. */
    fun untilPoll(at: Long, every: Long = intervalMs): Long =
        (lastPollAt + every - at).coerceAtLeast(0)

    /**
     * How late this session's next poll is, in milliseconds, when it is late enough to be the phone.
     *
     * **The one thing the app can honestly say about a force-stop.** Android does not tell an app
     * that it was stopped: a force-stop is a cold start indistinguishable from any other, and an
     * OEM battery manager usually kills without calling anything at all. What *is* observable is the
     * clock: a session that was running should have polled every four minutes, so a `lastPollAt`
     * hours in the past means the app was not running for hours. That is a fact about the phone, and
     * it is worth writing down rather than resuming silently.
     *
     * Three intervals, not one: a late wake, a network change and a Doze window are all ordinary,
     * and a row about every one of them would be noise.
     */
    /**
     * How late this session's next check is, when it is late enough to be the phone.
     *
     * **Three intervals, and the interval is the setting's.** A session polled every minute that has
     * not been polled for five is the phone having stopped the app; one polled every four minutes
     * and four seconds late is a phone doing its best. With a fixed three-times-four-minutes this
     * would have called every session with a short interval a gap, and reported nothing for one with
     * a long interval.
     */
    fun missedBy(at: Long, every: Long = intervalMs): Long {
        if (!running || lastPollAt <= 0) return 0
        val late = at - lastPollAt
        return if (late > every * GAP_INTERVALS) late else 0
    }

    /**
     * A poll was sent at [at].
     *
     * **The retry count survives it.** Clearing the count here — which is what this did — meant
     * every attempt started again at one second, so the ladder never backed off and a provider
     * that refused for ever would be retried every second for ever. Only a turn that *succeeds*
     * clears the ladder, which is what `waited` and `asked` do.
     */
    fun polled(at: Long): AgentSession = copy(lastPollAt = at)

    /**
     * A ping was sent at [at].
     *
     * **Only the clock moves.** The last copy of this (`waited`) also set the sub-state and cleared
     * the retries, because it was the ending of a turn the model had taken; a ping is not a turn, and
     * a session that asked a question is still asked, a session that finished is still finished.
     */
    fun warmed(at: Long): AgentSession = copy(
        lastPollAt = at,
        // **A ping that got through is proof the transport is back**, so the ladder ends here — but
        // only a session that was reconnecting is moved: one that asked a question is still asked,
        // and one that finished is still finished.
        retries = 0,
        sub = if (sub == Sub.RECONNECTING) Sub.WAITING else sub,
    )

    /**
     * The person spoke to an agent that was switched off: **on again, and the wait starts now.**
     *
     * Not `waited`, which was a model's `wait` read back as an ending: this is the person's own act,
     * and the session it starts is a fresh one — nothing of the stop survives except the project.
     */
    fun resumed(at: Long): AgentSession = copy(
        state = State.IDLE,
        sub = Sub.WAITING,
        reason = null,
        retries = 0,
        askedAt = 0,
        reminders = 0,
        lastPollAt = at,
        warmFrom = 0,
        lastWords = "",
    )

    /** A ping could not be sent, or was refused: the ladder decides when to try again. */
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
        // **The keepalive starts here.** An answered question is the likeliest thing to be answered
        // next, and the ceiling in Settings runs from the moment the wait began rather than from the
        // last ping — which would push the ceiling along for ever.
        lastPollAt = at,
        warmFrom = at,
    )

    /**
     * Started with the operator's interval.
     *
     * **The interval travels with the session**, so a tick after a reboot knows how often this one
     * is checked on without reading the settings — and so a person changing the number affects the
     * sessions that exist, through the runner passing its own value to [warmDue].
     */
    fun withInterval(every: Long): AgentSession = copy(idleMs = every)

    /** `conclude` ended a turn: idle, and silent — there is nothing wrong with finishing. */
    fun concluded(at: Long): AgentSession = copy(
        state = State.IDLE,
        sub = Sub.FINISHED,
        reason = null,
        retries = 0,
        askedAt = 0,
        reminders = 0,
        lastPollAt = at,
        warmFrom = at,
    )

    /** `wait` ended a turn: idle and polling. */
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
        // **`reminders` is how many reminders have been sent, so the first one is the first.**
        // Counting the notice that came with the question as "1st" made the very first repeat
        // read "2nd reminder", which is the app being off by one about its own words.
        return if (reminders <= 0) said else "${ordinal(reminders)} reminder · $said"
    }

    private fun stopWords(s: AgentSession): String = when (s.reason) {
        StopReason.BY_OPERATOR -> "stopped by you"
        StopReason.PIN_REFUSED -> s.lastWords.ifBlank { "the pinned provider refused" }
        StopReason.LOOP -> "it kept going without doing anything"
        StopReason.ERROR -> s.lastWords.ifBlank { "it could not go on" }
        null -> "not running"
    }

}
