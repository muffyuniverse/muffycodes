package com.example.notes

import java.io.File

/**
 * One burst: the operator spoke, and the agent works until it ends the turn.
 *
 * **The request is built from the record, not from a list held in memory.** Everything the model is
 * sent is the record's `in` rows mapped into messages — the instructions above them — so the
 * conversation the model sees and the file a person reads are the same object. An in-memory
 * transcript beside a file is two truths, and they disagree the first time the app is closed
 * mid-turn.
 *
 * The loop is synchronous on purpose. It runs on the app's one worker, which means a burst
 * cannot overlap another burst, and a file cannot be written twice from two turns. What it
 * costs is that a long turn blocks the worker; what it buys is that the record has one order.
 */
class Agent(
    private val accountDir: File,
    private val project: String,
    private val ws: Workspace,
    private val git: ProjectGit,
    private val log: AgentLog,
    private val door: Door,
    private val model: ActiveModel,
    /**
     * How much of one tool result may enter the conversation, in characters.
     *
     * **A budget for the conversation, applied at the one boundary a result crosses** (see
     * [AgentTools.forConversation]). Over it the body is written whole to a file and the answer is
     * that path, so nothing is lost and nothing is cut. The caller has already clamped the operator's
     * number to the model's input window — a result bigger than the window cannot be sent at all.
     */
    maxResult: Int = AgentTools.MAX_RESULT,
    /**
     * The backstop from [MAX_REQUESTS], overridable so a test can reach it in three requests
     * instead of forty. The policy is the constant; this is only who is asking.
     */
    private val maxRequests: Int = MAX_REQUESTS,
    /**
     * The two context limits, as the settings and the pinned endpoint make them at this moment.
     *
     * A value rather than a settings reader, because the ceiling is the pin's and the pin does not
     * change mid-turn — and because a test that had to build a `SettingsStore` to reach a size
     * limit would be testing the store instead.
     */
    private val limits: ContextBudget.Limits = ContextBudget.Limits.of(
        triggerAsked = 0,
        hardAsked = 0,
        ceiling = ContextBudget.UNKNOWN_CEILING,
    ),
    /**
     * Why the turn should stop now, or null to go on.
     *
     * **A turn is cut short, never raced.** The app has one worker and one record, so a second
     * request cannot be sent beside the first; what can happen "immediately" is that the request in
     * flight is abandoned the moment it returns and the operator's words are the next turn
     * (v1 C8). Checked between requests and again before a reply is acted on, so a command is never
     * obeyed after the person said to stop.
     */
    private val cut: () -> String? = { null },
    /**
     * True when this poll is a retry — the session has already failed at least once.
     *
     * **A failed attempt is news once.** Eight attempts at a provider that is down are one outage,
     * and a row per attempt would put eight identical `stopped` rows into a file the next clear-up
     * then has to carry. The design's rule is the first attempt written down and one line when it
     * comes back, with the count. The runner knows the ladder's position and the turn does not, so
     * it is passed in.
     */
    private val retrying: Boolean = false,
    /**
     * Where a `background` call's work runs, and how long the foreground waits for a command.
     *
     * **The wait is the person's, not the model's.** [foregroundMs] is the operator's setting; a
     * command that outlasts it is aborted and the call's own result says so. Zero means wait for
     * ever, and it is what a test that is not about a slow command uses, so no test acquires a
     * deadline it did not ask for.
     */
    private val bg: Background = Background.none(),
    private val foregroundMs: Long = 0L,
    /**
     * How long a failing connection is retried before the session stops and says a person is
     * needed — **the operator's window**, from Settings.
     *
     * Passed in rather than read here for the same reason every other limit is: the agent is built
     * with the numbers in force when the turn began, so a setting changed mid-turn cannot make one
     * turn follow two rules.
     */
    private val reconnectMs: Long = AgentSession.DEFAULT_RECONNECT_MS,
    /**
     * The clock a foreground deadline is measured against.
     *
     * **Injected so a test can say the wait is over** without sleeping for it. The deadline itself
     * asks a clock for the time; this is which clock, and the only place the foreground's deadline
     * is made.
     */
    private val clock: () -> Long = { System.currentTimeMillis() },
    /**
     * Three symbols for the keepalive's tail — **injected so its uniqueness is testable.**
     *
     * A test that drew from the real generator and compared two draws would be asserting a
     * probability: with 238,328 combinations it would pass a quarter of a million times and fail
     * once, which is a flake dressed as a proof. A test supplies a sequence instead and asserts the
     * *rule* — every ping gets the tail it was given, and the tail reaches nothing but the wire.
     */
    private val pingMark: () -> String = { mark() },
    /** The operator's limit for a `background` command; zero is "no limit". */
    private val backgroundMs: () -> Long = { 0L },
    /**
     * How big the record may get, in bytes; **zero keeps everything, which is the default.**
     *
     * A different act from [limits]: trimming the context takes a row out of what the model is sent
     * and leaves the evidence alone, while this removes the row. The app used to do this at four
     * megabytes without being asked, so a person trimming their context could watch rows disappear
     * from a file they had understood as append-only.
     */
    /**
     * How many tokens of `session.log` to keep; zero keeps everything.
     *
     * **Tokens, not bytes.** The operator's correction: the limit and the cost should be one unit,
     * and the unit the model is billed in is the one a person is choosing about.
     */
    private val sessionTokens: Long = 0L,
) {

    private val tools = AgentTools(ws, project, git, log, maxResult, bg, backgroundMs)

    /** How a turn ended. */
    sealed class End {
        /**
         * The work is done — **the model said something and called nothing.**
         *
         * There used to be an `Asked` beside this, for a `conclude`/`ask` tool the model called to
         * end a turn. Both tools are gone: an agent that needs a person says so in its words, which
         * is a conclusion like any other, and the session goes idle — which is what the keepalive
         * polls, and what a person replies into.
         */
        data class Concluded(val said: String) : End()

        /**
         * The person spoke while the agent was working, so the turn was abandoned.
         *
         * **Not a stop.** The session goes on — the words that interrupted it are the next turn —
         * and the only thing that changed is that this request's commands did not run. Treating it
         * as a stop would switch the agent off every time somebody typed a second sentence.
         */
        data class Interrupted(val why: String) : End()

        /**
         * The turn could not go on, and the reason is in the record.
         *
         * [retryable] is the difference between **a blip and a wall**: a transport that failed is
         * a session that reconnects in one, two, four seconds, and a model that answered with
         * nonsense is a session that stops and says so. Without the difference, a tunnel through
         * a train would end an agent for good, and a stuck model would spin for ever.
         *
         * [reason] is what the session's own state says once it stops, so the app has one
         * vocabulary for "why" rather than translating a sentence back into a cause.
         */
        data class Stopped(
            val why: String,
            val retryable: Boolean = false,
            val reason: AgentSession.StopReason = AgentSession.StopReason.ERROR,
        ) : End()

        /** The one word the trace uses for this ending. */
        val word: String
            get() = when (this) {
                is Concluded -> "concluded"
                is Interrupted -> "interrupted"
                is Stopped -> "stopped"
            }

        /**
         * What follows the word: the question, the conclusion, or why it stopped.
         *
         * Named `detail`, not `reason`: [Stopped.reason] is the session's own vocabulary for
         * why it stopped, and a property in the parent with that name would hide it.
         */
        val detail: String
            get() = when (this) {
                is Concluded -> said
                is Interrupted -> why
                is Stopped -> why
            }

        /**
         * The session's own state, moved on by this ending.
         *
         * **Here rather than in the caller**, because there are two callers — a burst from the
         * screen and a poll from the timer — and a transition each of them remembered is a
         * transition one of them forgets. A blip reconnects on the session's ladder; a wall stops
         * and keeps the provider's words, because those are what the stopped label shows.
         */
        /**
         * The session this ending leaves — **with the operator's stop standing over it.**
         *
         * **One rule, one place, because it was two and they disagreed.** The screen and the runner
         * each carried a copy, and the copies were not the same: the screen kept the operator's stop
         * against every ending, while the runner kept it only against an ending that left the session
         * running — so a hard stop arriving a second later (a provider refusing the key) replaced the
         * operator's "I stopped it" with the app's reason. A session the person switched off must not
         * change its story because a reply landed after they did it.
         *
         * The rule is the operator's own: it must never stop until they remove it, and it must never
         * be restarted by anything but them either.
         */
        fun onto(
            session: AgentSession,
            now: Long,
            reconnectMs: Long = AgentSession.DEFAULT_RECONNECT_MS,
        ): AgentSession =
            if (session.state == AgentSession.State.STOPPED &&
                session.reason == AgentSession.StopReason.BY_OPERATOR
            ) {
                session
            } else {
                after(session, now, reconnectMs)
            }

        /**
         * [reconnectMs] is **the operator's window**, passed in rather than read here: this is a
         * nested class, so it has no `Agent` to ask, and the number that applies is the one in force
         * when the turn began.
         */
        fun after(
            session: AgentSession,
            now: Long,
            reconnectMs: Long = AgentSession.DEFAULT_RECONNECT_MS,
        ): AgentSession = when (this) {
            is Concluded -> session.concluded(now)
            // **The person speaking does not change the session.** The agent is still on and the
            // words that interrupted it are the next turn; a stop is the only thing here that
            // switches anything off.
            is Interrupted -> session
            is Stopped -> if (retryable) {
                session.copy(lastWords = why).failed(now, forMs = reconnectMs)
            } else {
                session.stopped(reason, why)
            }
        }

        /**
         * The sentence the box shows a person after a turn.
         *
         * Not the trace's: a trace is read later by somebody reconstructing what happened, and a
         * sentence on the screen is read now by the person who asked. Two audiences, two strings,
         * and each one defined once — here, on the type that knows the difference.
         */
        fun plainly(): String = when (this) {
            is Concluded -> "The agent finished"
            is Interrupted -> "The agent was cut short by what you said"
            is Stopped -> why
        }
    }

    /**
     * What one keepalive ping did. **Not an [End]**, because a ping cannot end a turn.
     *
     * The distinction is the feature: an ending moves the session, and a ping that could move the
     * session would be a model able to stop itself by failing. [Failed.stopping] is the one thing a
     * ping may act on, and only when the refusal is the person's to fix — a key or an empty balance.
     */
    sealed class Warm {
        /** Sent, and the cache is warm again. Nothing else happened. */
        data object Sent : Warm()

        /**
         * It was not sent. [stopping] when the ladder should take over, which it does only for a
         * refusal of the key or the credit: everything else — a provider that will not take a
         * one-token answer, a tunnel, a Doze window — is a ping missed, not a session stopped.
         */
        data class Failed(val why: String, val stopping: Boolean = false) : Warm()

        companion object {
            /**
             * What a ping that could not be built says, **in the one place it can be changed.**
             *
             * It is written by the turn as well as by the runner, and two copies of a sentence a
             * person reads is two sentences the day one of them is edited.
             */
            const val NOT_WARM = "The conversation could not be kept warm"
        }
    }

    companion object {
        /**
         * The most requests one turn may make.
         *
         * A backstop, not a policy: the loop's own guard is [AgentTools.IDLE_CALLS] calls that
         * change nothing. This exists so that a provider answering with one harmless call for
         * ever cannot hold the worker, and it is high enough that real work never reaches it.
         */
        const val MAX_REQUESTS = 40

        /** Why a turn was cut short by the person stopping the agent. */
        const val BY_OPERATOR = "the agent was stopped"

        /**
         * The alphabet the keepalive's tail is drawn from: **three symbols, base62.**
         *
         * Base62 rather than digits, and the reason is a measurement rather than taste. The tail is
         * the only part of a ping that is not in the provider's cache, so its cost is its *length*
         * and not its alphabet: three digits and three base62 symbols are both about two tokens.
         * What differs is the chance of drawing the same tail twice in a row, which is the one
         * repeat that matters - 1.4% of 15-ping sessions for digits against 0.01% for base62, and
         * 26% against 0.13% over 300 pings. Making a rare failure rarer by 238x for no cost is not a
         * trade, it is a free move.
         */
        const val MARK_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

        /** Three symbols, freshly drawn. In the companion so a default argument can name it. */
        fun mark(): String = (1..3).map { MARK_ALPHABET.random() }.joinToString("")

        /**
         * The refusals that are the person's to fix, and so the only ones a ping may hand to the
         * ladder: the key was refused (401/403) or the credit ran out (402).
         *
         * Everything else — a provider that will not take a one-token answer, a tunnel, a Doze
         * window — is a courtesy that did not arrive, and a session stopped over one of those would
         * be the app charging a mistake in its own keepalive to the person's work.
         */
        val KEY_REFUSALS = setOf(401, 402, 403)

        /** How many rows the clear-up line names. Five is enough to make a dent; more is a list. */
        const val BIGGEST_ROWS = 5
    }

    /**
     * Runs one turn and answers how it ended.
     *
     * [operator] is the person's own words; they are written into the record first, so a turn
     * that fails still begins with what was asked.
     */
    fun burst(operator: String, progress: () -> Unit = {}): End = turn(operator, progress)

    /**
     * The keepalive: the same conversation, sent again for the provider's cache.
     *
     * **Nothing is written into the context and nothing is read back.** A ping asks for one token
     * (see [Door.PING_TOKENS]) and drops the answer, so the conversation the person will add to is
     * the one that was kept warm, byte for byte — which is the whole economy of it. Two rows go into
     * the evidence, both bookkeeping: the box shows the check, the model never sees it.
     *
     * **No `TOLD` row either.** The person did not say anything, and the words they will say next
     * are the only thing that may change that part of the context.
     *
     * The request is built by [build], the same function a turn uses, so the ping is the turn the
     * person is about to take. It can trim the context if the app's own limit says so, exactly as a
     * turn would: a limit the app enforces only when somebody is talking is not a limit.
     */
    fun warm(): Warm {
        val layers = AgentPrompt.read(accountDir, tools.projectDir)
        val request = try {
            // **The instruction is appended, never written down.** It is a message on the wire and
            // not a row in the record: a `TOLD` row here would enter the conversation the ping
            // exists to keep warm, and the next real turn would be answering a question the person
            // never asked. `build` reads the record; this is the one thing added after it.
            val built = build(layers, readOpening(layers, log, AgentTools.specs()), clearedUp = false)
            built.copy(messages = built.messages + pingInstruction())
        } catch (t: Throwable) {
            // **A request that could not be built is not a ping that got through.** This said
            // `stopping = false`, and the runner's false branch calls `warmed()` — which clears the
            // ladder, because a ping that *arrived* is proof the transport is back. A build failure
            // proves nothing of the kind: it is the app's own machinery, and it is very often the
            // very thing that is wrong (a context that will not trim, a log that will not open).
            // So it takes the same branch a refusal does, which is where a repeated failure belongs.
            return Warm.Failed(Secrets.explain(t, Warm.NOT_WARM), stopping = true)
        }
        return door.ping(request).fold(
            onSuccess = {
                log.bookkeeping(AgentLog.TURN, "turn", "ping")
                log.bookkeeping(AgentLog.NOTE, "ping", "kept warm")
                // **The shape, and not the cost.** What a ping cost is a fact about one response
                // and it goes in the request log, where every response has a line; the trace is
                // the running account of what the app *did*, and a number repeated there would be
                // a second copy of the same fact that could drift from the first.
                Trace.note("agent-warm", "$project · ${request.shape}")
                Warm.Sent
            },
            onFailure = { t ->
                val said = Secrets.explain(t, Warm.NOT_WARM)
                val code = (t as? Refused)?.code ?: (t.cause as? Refused)?.code
                val stopping = code != null && code in KEY_REFUSALS
                Trace.note("agent-warm-failed", "$project · $said")
                // **The first failure of an outage is written down, and the rest are one outage.**
                // Same rule as [blip]'s, for the same reason: a key that was refused eight times is
                // one thing a person has to fix, and the runner's `retry` row carries the count.
                if (stopping && !retrying) log.note("stopped", said)
                Warm.Failed(said, stopping = stopping)
            },
        )
    }

    /**
     * A background command finished: **one turn the model takes without being spoken to.**
     *
     * Its own work coming back is the one thing worth spending a turn on that the person did not
     * ask for, and nothing else in the app can act on it — a ping is one token, and the file is only
     * read by something that decides to read it.
     *
     * [what] is written into the record first, in the context, as the app's own line: the model is
     * handed the same conversation it ended plus one sentence saying what landed and where. Without
     * it there is nothing new to answer, and a model asked the same question twice answers the same
     * way.
     *
     * **No `TOLD` row.** The person did not speak; the note is the app's, and the ending is the
     * turn's own.
     */
    fun wake(what: String): End {
        log.note("woke", what)
        return turn(null, {})
    }

    /**
     * One turn, and the two things that happen however it ends.
     *
     * **The layers are read here, once.** Not at startup, so an edit to the project's
     * instructions is not missed; not per request, so one conversation cannot be answered under
     * two different briefs. [AgentPrompt.Layers] carries that decision.
     *
     * The trim and the redraw are in a `finally` because they belong to the turn, not to a loop
     * that reached its end: a turn that threw still has to leave the evidence at its size, and
     * still has to hand the box what was written — otherwise a failure stays on the screen as
     * "working" for ever.
     */
    private fun turn(operator: String?, progress: () -> Unit): End {
        val layers = AgentPrompt.read(accountDir, tools.projectDir)
        // **Measured once, at the start of the turn.** The warning names a size, and a size that
        // changed with every request would change the *system* message — the first thing in the
        // array — and a provider's cache matches the longest common prefix, so the warning would
        // invalidate the whole conversation on every request of the turn it appeared in. That is
        // the 50× cost §2.4 is about, paid by the feature meant to prevent it.
        val opening = readOpening(layers, log, AgentTools.specs())
        try {
            return loop(layers, opening, operator, progress)
        } finally {
            // The evidence is kept to the size the operator asked for, and a turn boundary is the
            // only place a trim cannot cut a conversation in half. Zero means they asked for nothing
            // to be removed, and then nothing is.
            log.trimSession(sessionTokens)
            progress()
        }
    }

    private fun loop(
        layers: AgentPrompt.Layers,
        opening: Opening,
        operator: String?,
        progress: () -> Unit,
    ): End {
        // The seal belongs here, at the start of a session, and not to a read: no endpoint
        // accepts a conversation whose last assistant message calls a tool nobody answered. A
        // poll continues a session, so it does not seal anything.
        if (operator != null) {
            log.beginSession()
            log.told(operator)
            Trace.note("agent", "$project: ${operator.take(80)}")
        }

        var idle = 0
        var requests = 0
        // Watched only when the warning was actually shown: the count is a file read, and the only
        // turn that cares is the one where the app is asking for a clear-up.
        val watching = limits.triggerAt > 0 && opening.size > limits.triggerAt
        var clearedUp = false
        while (requests < maxRequests) {
            // Before the request: nothing is sent for a turn that has been called off.
            cut()?.let { return finish(cutShort(it, emptyList())) }
            if (idle >= AgentTools.IDLE_CALLS) return finish(tooIdle())
            requests++
            // The boundary the box counts turns by, and the app's own row, not the model's.
            log.bookkeeping(AgentLog.TURN, "turn", "$requests")
            val request = build(layers, opening, clearedUp)
            // **The shape of the request, once per turn the person started.** Which model, which
            // pinned endpoint, how big the conversation had grown: the three things needed to
            // explain a refusal or a bill, and nowhere else on the trace. Not traced for a poll —
            // fifteen an hour of the same line would bury everything else.
            if (operator != null && requests == 1) {
                Trace.note("agent-request", "$project · ${request.shape}")
            }
            val reply = door.turn(request).getOrElse { return finish(blip(it)) }
            // After the reply, before it is obeyed: a request already on the wire cannot be
            // un-sent, but its commands must not run once the person has spoken or stopped.
            cut()?.let { return finish(cutShort(it, reply.calls)) }

            if (reply.text.isNotBlank()) {
                val said = log.note("agent", reply.text)
                // **The accounting goes on the words, because the words are what it is for.**
                // `output` is the tokens the model wrote and `thinking` the ones it spent getting
                // there, so they belong to the message a person reads - not to the tool calls, whose
                // own bubble would then show the same figure twice and invite adding it up.
                //
                // **Nothing is written when the provider said nothing.** A scripted door has no
                // usage, and a row of zeroes would claim the request was free rather than that it
                // was never reported.
                if (reply.outputTokens != null || reply.thinkingTokens != null) {
                    log.usage(said, reply.outputTokens, reply.thinkingTokens)
                }
            }

            // **Words and no calls is the ending**, and it is the only one. There used to be a
            // second: `ask` and `conclude` were tools the model called to end a turn, with a rule
            // that they be called alone, a refusal for calling them in company, and a branch here
            // for each shape. All of it is gone — a turn now ends the way a conversation does, with
            // the agent saying something and calling nothing.
            if (reply.calls.isEmpty()) return finish(wordsWithoutCalls(reply))

            // **Measured, not asked.** Whether the clear-up happened is the context getting shorter
            // on disk, not the model saying that it did.
            val rowsBefore = if (watching) log.contextRows().size else 0
            val moved = work(reply.calls)
            if (watching && log.contextRows().size < rowsBefore) clearedUp = true
            if (moved) idle = 0 else idle++
            progress()
        }
        return finish(overBudget())
    }

    // ---- the ways a turn ends ------------------------------------------------
    //
    // One function each, and each one is the whole rule: the record rows it writes, and the
    // sentence the session keeps. [finish] is the only place a trace line is written, so an
    // ending cannot be added without being on the trace.
    //
    // **Two of the four are gone with `ask` and `conclude`.** `asked` wrote a question row and
    // stopped the session needing a person; `concluded` wrote a statement and stopped it finished.
    // A conclusion is now the model's own words with no call, which [wordsWithoutCalls] handles —
    // and it does not need a row of its own, because the words are already on the record as the
    // agent's own note. The old pair said the same paragraph twice.

    /**
     * A call's arguments, tolerated rather than thrown.
     *
     * A reply whose arguments are not JSON at all is a reply the loop still has to answer — the
     * conversation refuses an unanswered call — so the parse failure is `{}` here and the command's
     * own refusals do the complaining.
     */
    private fun argsOf(call: Call): org.json.JSONObject = try {
        org.json.JSONObject(call.args.ifBlank { "{}" })
    } catch (e: Exception) {
        org.json.JSONObject()
    }

    /**
     * The turn was called off, and its calls are answered anyway.
     *
     * **A call with no result is an illegal conversation**, so every call the abandoned reply
     * carried is closed with `1 interrupted` — the operator's own correction: "the tool that was
     * running gets its result anyway (1 interrupted)". The next request, which carries the
     * person's words, is then a legal one with the interrupted results above it in order.
     */
    private fun cutShort(why: String, calls: List<Call>): End {
        for (call in calls) {
            val id = log.call(call.tool, call.args)
            log.got(call.tool, "${AgentLog.FAILURE} interrupted: $why", answers = id)
        }
        log.note("stopped", why)
        // A person speaking is not a session stopping; a person stopping is.
        return if (why == BY_OPERATOR) {
            End.Stopped(why, reason = AgentSession.StopReason.BY_OPERATOR)
        } else {
            End.Interrupted(why)
        }
    }

    /**
     * The model answered with words and no call: **that is the end of the turn.**
     *
     * It used to stop the session with `LOOP`, on the reasoning that a model which ignored the tool
     * protocol would only produce more prose. The operator's ruling is the better one: the words are
     * what it has to say, and a person reading "I read the three files and changed nothing, because
     * the file they asked about is locked" has been answered — stopping the agent over the shape of
     * the reply punishes the person for the model's grammar.
     *
     * **One row, not two.** A `CONCLUDE` row used to be written here beside the agent's own note —
     * the same paragraph twice, once for the box and once for the model. The note is the one that
     * matters: it is what the model reads back, and it is what the box draws. What is left is the
     * app's own line saying *why* the turn ended, which is a fact about the loop rather than a copy
     * of what the model said.
     */
    private fun wordsWithoutCalls(reply: Reply): End {
        val said = reply.text.trim().ifEmpty { "(the model said nothing)" }
        log.bookkeeping(AgentLog.NOTE, "ended", "no tool call, so the words are the conclusion")
        return End.Concluded(said)
    }

    /** A transport that failed: the session reconnects on its ladder, and the words are kept. */
    private fun blip(problem: Throwable): End {
        val said = Secrets.explain(problem, "The model could not be asked")
        // The first failure of an outage is written down; the retries are one outage, not eight
        // events. What the person wants to know when it comes back is how many tries it took, and
        // that row is the runner's — it is the one holding the ladder.
        if (!retrying) log.note("stopped", said)
        return End.Stopped(said, retryable = true)
    }

    private fun tooIdle(): End {
        val said = "it made ${AgentTools.IDLE_CALLS} calls in a row that changed nothing " +
            "and asked nothing"
        log.note("stopped", said)
        return End.Stopped(said, reason = AgentSession.StopReason.LOOP)
    }

    private fun overBudget(): End {
        val said = "it made $maxRequests requests without ending the turn"
        log.note("stopped", said)
        return End.Stopped(said, reason = AgentSession.StopReason.LOOP)
    }

    /**
     * The one place a turn is put on the trace.
     *
     * Worth the indirection: five separate `Trace.note` calls meant a sixth ending could be
     * added and be invisible on the screen that exists to show what the app is doing.
     */
    private fun finish(end: End): End {
        Trace.note("agent", "$project ${end.word}: ${end.detail.take(80)}")
        return end
    }

    /** Runs a reply's calls, and says whether any of them changed the project. */
    private fun work(calls: List<Call>): Boolean {
        val before = changedFiles()
        var changed = false
        for (call in calls) {
            val id = log.call(call.tool, call.args)
            Trace.note("agent", "$project call ${call.tool} #$id")
            val body = answer(call)
            // **The id the result is for.** Written by the caller that just took it, so the record
            // says which call this answers rather than leaving a reader to infer it from position.
            log.got(call.tool, body, answers = id)
            if (call.tool in AgentTools.WRITING && !body.startsWith(AgentLog.FAILURE)) changed = true
        }
        // A call that wrote nothing and left the project as it was is a call that did nothing —
        // the count the idle guard is about. Files, not the reply's own claim about itself.
        return changed || changedFiles() != before
    }

    /** Runs one call and returns its body with its `0` or `1` already on the front. */
    private fun runTool(call: Call): String = tools.run(call)
    /**
     * A call's result, **with the operator's deadline on it.**
     *
     * The conversation has one shape that is legal and it is unforgiving: every tool call in an
     * assistant message must be answered before the next request. So a command that runs out of time
     * is answered now, with the truth, and the turn carries on. Nothing is handed over and nothing is
     * asked again.
     *
     * **Every command, not a short list of slow ones.** There used to be a `LONG` set here, on the
     * argument that everything outside it finishes in microseconds — which stopped being true the
     * moment nothing bounded a `read` by its size. A file and a project are as big as the person's
     * disk, so the deadline is attached to the dispatch itself and each command asks it where it can.
     */
    private fun answer(call: Call): String {
        // **A command that outlasts the wait is handed over, not thrown away** - report 13. The
        // handover lives inside `tools.run`, because that is where the stop is caught: the deadline
        // is asked inside each command's own loop, `StoppedException` travels up through it, and
        // `run` is the one place that turns it into a sentence. A `catch` here would never fire.
        //
        // What the app used to say was "stopped after 60 s; nothing was kept", which is the app
        // treating a limit on how long it *waits* as a limit on the work. The operator asked for the
        // other half: the command carries on and its result arrives with the next batch. That
        // machinery already exists and is already proven by `background`, so a slow foreground
        // command joins it rather than getting a second mechanism of its own.
        val body = if (foregroundMs <= 0L) {
            runTool(call)
        } else {
            tools.run(call, Deadline(foregroundMs, clock), canHandOver = true)
        }
        // **The one place the result limit applies**, because this is the one place a result enters
        // the conversation. A backgrounded command's body goes straight to its file, and so does the
        // text an `edit` copies out of one, so neither is measured — the limit is a conversation
        // budget, not a size cap on work.
        return tools.forConversation(call, body)
    }

    /**
     * What the turn began with.
     *
     * [biggest] is here rather than re-derived per request for the same reason [size] is: the line
     * that carries them is part of the system message, and a list that changed as tool results
     * arrived would cost a full re-read of the conversation on every request. A list that goes
     * slightly stale within one turn is the cheaper wrong.
     */
    private data class Opening(val size: Long, val biggest: List<RowCost>)

    /**
     * One row and what it costs, for the index the clear-up line carries.
     *
     * [line] is the row's position in `agent/context.log`, counted from one — the handle `read`
     * takes, so `read agent/context.log from 7 to 7` returns exactly this row. [id] is the handle
     * `forget` takes. Two tools, two handles on one row: one to look, one to act.
     *
     * **The line is a line because the file is appended to.** A record written newest-first would
     * make this a moving target, and the index would open a different row a turn later.
     */
    private data class RowCost(
        val id: Int,
        val kind: String,
        val name: String,
        val tokens: Long,
        val line: Int,
    )

    /**
     * Reads the turn's opening size and the rows taking the most room.
     *
     * **The sizes are the app's estimate, in the unit the limit is in** — a list in bytes beside a
     * limit in tokens would be two numbers a model has to convert before it can act, and it would
     * convert wrong. The exact bytes are what a *person* is shown, where they can check it.
     */
    private fun readOpening(layers: AgentPrompt.Layers, log: AgentLog, tools: List<ToolSpec>): Opening {
        // **The line numbers are the context file's, and they cost nothing to know.**
        // `context.log` holds the `in` rows in the order they entered it, so the nth one is its nth
        // line. The index is read off the evidence, with no second file to parse and no position to
        // look up — and because both files are only ever appended to, line N stays line N until a
        // row leaves the context, which is a moment the clear-up line is being rewritten anyway.
        val costs = log.contextRows()
            .withIndex()
            .map { (index, row) -> RowCost(row.id, row.kind, row.name, row.size, index + 1) }
            .filter { it.tokens > 0 }
            .sortedByDescending { it.tokens }
            .take(BIGGEST_ROWS)
        // **Measured from the rows in hand, not from the memo.** This function has already read
        // every context row to find the biggest ones - the clear-up line names them - so the sum is
        // free here, and asking the memo would read the file a second time to avoid an addition.
        // The memo pays where a caller has no rows: `size()`, and the trim.
        return Opening(measure(layers.message, log.contextRows(), tools), costs)
    }

    /** How many files in the project are different from what was committed. */
    private fun changedFiles(): Int = try {
        git.changes(project).total
    } catch (e: Throwable) {
        -1
    }

    /**
     * What a ping tells the model to do, **so that the ping is a bounded piece of work.**
     *
     * Measured, on the pinned endpoint: the same conversation sent with no instruction and one
     * token allowed produced a hundred and thirty-six tokens of answer, because a conversation
     * with no question in it invites the model to continue it. With this instruction the same
     * request produced twenty. The instruction is the difference between "say something" and "do
     * nothing", and a heartbeat should be the second.
     *
     * **It also removes the last ambiguity a provider could reason about.** The keepalive asks the
     * model to spend nothing, and an instruction that can be read one way leaves nothing to
     * interpret. That matters more than the tokens: OpenRouter's own documentation warns that
     * `max_tokens` bounds reasoning *on most providers*, and a provider that ignores it while
     * reasoning about an unclear request is exactly the failure this sentence prevents.
     */
    private fun pingInstruction(): Msg = Msg.user(
        // **The instruction, then three random symbols.** The words are what bound the answer: the
        // same conversation with no instruction and one token allowed produced 136 tokens, because
        // a conversation with no question in it invites the model to continue. With the one-dot
        // instruction it produced 20. The tail is what stops the request ever being the one before
        // it - see [mark], and the operator's ruling at L28198: *"attach a new dot to every
        // subsequent keepalive message so that message never stays exactly the same."*
        //
        // **Why a changing tail cannot break the cache the ping exists to protect.** A provider
        // matches the longest common *prefix*, and this sits after the whole conversation - so the
        // cached part is untouched and only the last two tokens are new. It defeats an exact-match
        // cache, which is the thing that was answering instead of the upstream, and leaves a
        // prefix cache hitting.
        //
        // **Appended rather than prepended, deliberately.** A prefix cache reads forwards, so
        // anything new belongs at the end; a symbol at the front would invalidate every cached
        // token behind it and turn every ping into a full-price re-prefill.
        "Answer with a message that contains only one dot and nothing else. " + pingMark(),
    )

    private fun build(layers: AgentPrompt.Layers, opening: Opening, clearedUp: Boolean): Request {
        val tools = AgentTools.specs()
        var rows = log.contextRows()
        val size = measure(layers.message, rows, tools)
        // **Said by the trim, not inferred from two measurements.** Rows grow between requests as
        // tool results arrive, so "the size changed" is true of every turn and would have silenced
        // the warning whenever the app's own limit happened to be configured at all.
        var trimmed = false

        // **One messagebox per request, and never in a loop.** The record's trim removes the oldest
        // conversation and says how many rows went; the next request measures again. A loop that
        // re-trimmed until the size fitted would empty the context against a limit below what the
        // instructions alone cost, and could never converge — an impossible limit is honoured once
        // per request rather than fought.
        val hard = limits.hardAt
        if (hard > 0 && size > hard) {
            val dropped = log.trimContext(hard)
            if (dropped > 0) {
                trimmed = true
                Trace.note("agent-trim", "$project · $dropped rows, over ${ContextBudget.short(hard)}")
                // Read back from the file: what is sent has to be what was left, or the trim is a
                // note about the app's intentions rather than a change to the request.
                rows = log.contextRows()
            }
        }

        // **The same line for every request of this turn**, from the size the turn began at — and
        // none at all when the app has just trimmed: the context is no longer the one the warning
        // was about, and telling the model it is over a limit it was just brought under would be a
        // message from before the app acted.
        val trigger = limits.triggerAt
        // **The stop condition, and the only thing the target is checked against.** The app does
        // not gate a clear-up on a size — the estimate is its own, and a refusal on arithmetic is
        // a refusal for the wrong reason. What it does is *stop asking*: once an edit in this turn
        // took the context under the target, the rest of the turn is not nagged, which is what
        // keeps a model from deleting rows that matter to satisfy a sentence that is already true.
        val done = clearedUp && size <= trigger
        val line = if (trigger > 0 && opening.size > trigger && !trimmed && !done) {
            Trace.note(
                "agent-clear-up",
                "$project · ${ContextBudget.short(opening.size)} over ${ContextBudget.short(trigger)}",
            )
            clearUpLine(opening.size, trigger, limits.trigger.ceiling, opening.biggest)
        } else {
            null
        }

        return Request(
            model = model.model,
            provider = model.provider,
            effort = model.effort,
            system = layers.withLine(line),
            messages = messages(rows),
            tools = tools,
        )
    }

    /**
     * What the request costs, estimated.
     *
     * The three parts it is made of and nothing else: the instructions, the rows, and the tool
     * descriptions. A measurement that left out the tool specs would be a limit the request could
     * exceed without ever being over it.
     */
    /**
     * What a request of these parts would cost.
     *
     * **The rows are summed here rather than asked of the record's memo**, because this function is
     * also handed rows that are not the record's - `build` passes the list it has already filtered
     * and possibly trimmed. The memo is used where the caller genuinely has no rows in hand: see
     * [size], which is the number a person asks for.
     */
    private fun measure(system: String, rows: List<AgentLog.Row>, tools: List<ToolSpec>): Long =
        ContextBudget.tokens(system) +
            // **The number the row already carries.** It is measured once, when the row is written,
            // as the cost of what a request actually carries — the body, not the formatted line with
            // its columns. Summing a stored number also means the request is measured in one pass
            // rather than two, and that no byte array is allocated to count a row.
            rows.sumOf { it.size } +
            tools.sumOf { ContextBudget.tokens(it.description, it.parameters.toString()) }

    /**
     * What the agent is told when its context is over the trigger.
     *
     * Two halves, and both are in the design for a reason: the instruction — clear out the records
     * that do not relate to the current task — and **the numbers**, because "you are over your
     * limit" with no size teaches a model nothing about how much to remove. The words are the
     * app's, not the agent's, so the row that says it is a trace note and not a record row: the
     * model reads this, the evidence does not pretend the agent wrote it.
     */
    private fun clearUpLine(size: Long, trigger: Long, ceiling: Long, biggest: List<RowCost>): String =
        buildString {
            append("Your context was about ${ContextBudget.short(size)} tokens when this turn began. ")
            append("The limit is ${ContextBudget.short(trigger)} tokens, and this endpoint takes ")
            append("${ContextBudget.short(ceiling)} tokens of input. ")
            // **By id, and that is the whole command.** `forget` names rows; it cannot carry text,
            // so compaction cannot change what the record says — which is what makes the context
            // auditable afterwards rather than a claim the model made about it.
            append("Clear up your context: call `forget` with the ids of the rows that do not ")
            append("relate to your current task. Keep the newest task, and forget a call together ")
            append("with its result. ")
            // **A target, and it is a floor rather than a ceiling.** "Cut it down" is advice; "cut
            // it to at least this" is a number the next turn can measure, which is what makes the
            // request repeat until it is true. Cutting **past** the target is wanted rather than
            // tolerated: rows that do not serve the current task have no place in the context at
            // any size, and a model told to stop exactly at a number keeps the near-irrelevant
            // ones that come before it.
            append("Compaction target: get it to ${ContextBudget.short(trigger)} tokens or less — ")
            append("cut at least to that, and further if the rows do not serve the current task. ")
            append("Anything not related to what you are doing now can go.")
            if (biggest.isNotEmpty()) {
                // **The index, because the file cannot be read in one go.** A `read` of a large
                // context stops at the tool's own limit, so a model deciding what to remove either
                // pages through it — a request per 20k characters — or guesses. Naming the biggest
                // rows costs a line and saves the paging: one `read` of one line is one request for
                // the exact row that matters most.
                //
                // **The lines are `context.log`'s, and they are stable because it is appended to.**
                // A newest-first record would make every one of them a position that moves under
                // the model; an appended file keeps line N as line N.
                //
                // And it names the call the model has to make, exactly: **`from <line> to <line>`,
                // not `lines 1`** — `lines` is a key `read` refuses, so the old sentence was an
                // instruction that came back as a refusal at the moment the context had least room
                // to spare. `kind` is left out on purpose: `context.log` does not carry it.
                append("\nThe rows taking the most room — `read agent/context.log from <line> to ")
                append("<line>` shows one, as `id size name body`: ")
                append(biggest.joinToString("; ") {
                    "line ${it.line}: #${it.id} ${it.name}, about " +
                        "${ContextBudget.short(it.tokens)} tokens"
                })
                append(". Forget the ones that do not serve the task, by id.")
            }
        }

    /**
     * The request as the files stand right now.
     *
     * The same composition [loop] makes, spelled once, for anything that asks what the agent
     * would be sent — a check on a phone, and the tests that prove nothing is remembered.
     */
    /**
     * What the request would cost right now, estimated — the number the limits are compared with.
     *
     * Public because it is a question a person can have ("how full is it?") and because a test that
     * had to re-derive it would be measuring its own arithmetic rather than the app's.
     */
    fun size(): Long =
        // **The rows come from the record's memo**, because this is the question asked on a timer -
        // "how full is it?" - and re-reading and re-summing every context row to answer it is what
        // the memo exists to avoid. The instructions and the tool specs are counted as usual: they
        // change rarely and are cheap, and a second memo for them would be a second thing to
        // invalidate.
        ContextBudget.tokens(AgentPrompt.read(accountDir, tools.projectDir).message) +
            log.contextTokens() +
            AgentTools.specs().sumOf {
                ContextBudget.tokens(it.description, it.parameters.toString())
            }

    fun request(): Request {
        val layers = AgentPrompt.read(accountDir, tools.projectDir)
        return build(layers, readOpening(layers, log, AgentTools.specs()), clearedUp = false)
    }

    /**
     * The record, as the model reads it.
     *
     * Three decisions:
     * - **Everything between two tool results belongs to one assistant turn.** The prose, the
     *   reasoning and the calls were one reply on the wire, so they are put back as one.
     * - **A `GOT` answers the oldest call still waiting**, matched by the id the call's own row
     *   gave it. That is what makes a rebuilt conversation legal even after a seal.
     * - **`ASK` is the agent's own words.** It is the seam: without it the operator's "yes"
     *   answers nothing, because the question would have been dropped at the burst boundary.
     */
    fun messages(rows: List<AgentLog.Row>): List<Msg> {
        val out = mutableListOf<Msg>()
        val text = StringBuilder()
        val calls = mutableListOf<Call>()
        /**
         * The call ids still waiting for a result.
         *
         * A **set**, not a queue: the result names the call it answers, so the order results arrive
         * in stops being load-bearing. What remains is the one thing that is: a result may only be
         * sent if its call is still unanswered, which is what keeps the array legal.
         */
        val awaiting = mutableSetOf<String>()

        fun flush() {
            if (text.isEmpty() && calls.isEmpty()) return
            out.add(Msg.assistant(text.toString().trim(), calls.toList()))
            awaiting.clear()
            awaiting.addAll(calls.map { it.id })
            text.clear()
            calls.clear()
        }

        for (row in rows) {
            if (row.broken) continue
            when (row.kind) {
                AgentLog.TOLD -> {
                    flush()
                    out.add(Msg.user(row.body))
                }

                // **A speaking call is not part of the conversation.** `ask` and `conclude`
                // are never run, so they never get a result — and a request whose assistant
                // message calls a tool with no answer is refused by every endpoint. Their
                // words are the ASK/CONCLUDE row, which is added below as the agent's own
                // message; that is the seam an answer needs.
                AgentLog.CALL -> calls.add(Call(callId(row.id), row.name, row.body))
                AgentLog.GOT -> {
                    flush()
                    // **The result says which call it answers.** It used to be inferred: a queue of
                    // unanswered calls, and the next result took the oldest. That is right only
                    // while the writer interleaves calls and results — it does, but nothing said it
                    // had to, and a reply may carry several calls.
                    //
                    // A result that names no call, or names one this conversation no longer has, is
                    // **not sent**: every request needs each tool message to answer a call the model
                    // made, and an orphan result is a message an endpoint refuses. The clear-up
                    // rules cannot produce one, but a reset can — the evidence keeps a call the
                    // context no longer has, and the app's own seal then answers it in the evidence
                    // alone.
                    val call = callId(row.answers)
                    if (row.answers <= 0 || !awaiting.remove(call)) continue
                    out.add(Msg.tool(call, row.body))
                }

                // **`woke` is a message of its own, and the only note that is.** A wake has to say
                // why it happened; `agent`/`reason` accumulate into the assistant's own paragraph,
                // which would make the app's sentence look like something the model wrote. It is a
                // user message because that is the only role every endpoint lets a conversation
                // carry mid-array — and it is at the end of the array, where changing it costs one
                // message rather than the whole prompt.
                AgentLog.NOTE -> when (row.name) {
                    "woke" -> {
                        flush()
                        out.add(Msg.user(row.body))
                    }
                    "agent", "reason" -> text.append(row.body).append("\n")
                    else -> Unit
                }
            }
        }
        flush()
        return out
    }

    /** The wire id of a call, derived from the row that recorded it — stable across rebuilds. */
    fun callId(rowId: Int): String = "call_$rowId"
}
