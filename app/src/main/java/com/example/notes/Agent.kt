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
     * command that outlasts it is aborted, not handed over, and the call's own result says so. The
     * default is zero, which means wait for ever — exactly what a turn did before this existed, so a
     * test that is not about a slow command never acquires one.
     */
    private val bg: Background = Background.none(),
    private val foregroundMs: Long = 0L,
    /** The operator's limit for a `background` command; zero is "no limit". */
    private val backgroundMs: () -> Long = { 0L },
) {

    private val tools = AgentTools(ws, project, git, log, maxResult, bg, backgroundMs)

    /** How a turn ended. */
    sealed class End {
        /** The agent needs the person. */
        data class Asked(val question: String) : End()

        /** The work is done. */
        data class Concluded(val said: String) : End()

        /** Idle until the person speaks. */
        data object Waited : End()

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
                is Asked -> "asked"
                is Concluded -> "concluded"
                Waited -> "idle"
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
                is Asked -> question
                is Concluded -> said
                Waited -> Agent.WAITING
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
        fun onto(session: AgentSession, now: Long): AgentSession =
            if (session.state == AgentSession.State.STOPPED &&
                session.reason == AgentSession.StopReason.BY_OPERATOR
            ) {
                session
            } else {
                after(session, now)
            }

        fun after(session: AgentSession, now: Long): AgentSession = when (this) {
            is Asked -> session.asked(now)
            is Concluded -> session.concluded()
            Waited -> session.waited(now)
            // **The person speaking does not change the session.** The agent is still on and the
            // words that interrupted it are the next turn; a stop is the only thing here that
            // switches anything off.
            is Interrupted -> session
            is Stopped -> if (retryable) {
                session.copy(lastWords = why).failed(now)
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
            is Asked -> "The agent asked something"
            is Concluded -> "The agent finished"
            Waited -> "The agent is waiting"
            is Interrupted -> "The agent was cut short by what you said"
            is Stopped -> why
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

        /**
         * What `wait` answers, in the context and in the evidence.
         *
         * A constant on purpose: a number that changed ("3 changes since you last looked") would
         * make every poll a different request, which is the one thing the poll cannot be.
         */
        const val WAITING = "0\ncontinue waiting"

        /** Why a turn was cut short by the person stopping the agent. */
        const val BY_OPERATOR = "the agent was stopped"

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
     * One poll: the same request, sent again, with nothing new said.
     *
     * **No `TOLD` row, because the person did not say anything.** A poll is the app keeping a
     * promise — every four minutes, the identical request — so writing the operator's message
     * again would put words in their mouth and change the one part of the context that has to
     * stay byte-identical. The agent's own reply is recorded like any other turn.
     */
    fun poll(progress: () -> Unit = {}): End = turn(null, progress)

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
            // The evidence is the app's to keep to size, and a turn boundary is the only place a
            // trim cannot cut a conversation in half.
            log.trimSession()
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

            if (reply.text.isNotBlank()) log.note("agent", reply.text)

            val ending = reply.calls.firstOrNull { it.tool in AgentTools.TERMINATING }

            when {
                // Words and no calls: the model has nothing to do. Recorded and ended, rather than
                // asked again — a second request would be answered with more words.
                reply.calls.isEmpty() -> return finish(wordsWithoutCalls(reply))

                // **A terminating tool is called alone.** The refusal is written as each call's
                // own result, so the model sees what it did and what it should have done — and
                // the terminating call is not obeyed, because a reply that ignored the rule is a
                // reply that was improvising.
                ending != null && reply.calls.size > 1 -> {
                    refusedInCompany(reply.calls)
                    idle++
                    progress()
                }

                ending != null -> return finish(leave(ending, reply))

                else -> {
                    // **Measured, not asked.** Whether the clear-up happened is the context
                    // getting shorter on disk, not the model saying that it did.
                    val rowsBefore = if (watching) log.contextRows().size else 0
                    val moved = work(reply.calls)
                    if (watching && log.contextRows().size < rowsBefore) clearedUp = true
                    if (moved) idle = 0 else idle++
                    progress()
                }
            }
        }
        return finish(overBudget())
    }

    // ---- the four ways a turn ends -------------------------------------------
    //
    // One function each, and each one is the whole rule: the record rows it writes, and the
    // sentence the session keeps. [finish] is the only place a trace line is written, so an
    // ending cannot be added without being on the trace.

    /** The agent needs the person. */
    private fun asked(call: Call, args: org.json.JSONObject, reply: Reply): End {
        log.call(call.tool, call.args)
        val question = args.optString("question", reply.text).trim()
            .ifEmpty { "(a question with no words)" }
        log.ask(question)
        return End.Asked(question)
    }

    /** The work is done. */
    private fun concluded(call: Call, args: org.json.JSONObject, reply: Reply): End {
        log.call(call.tool, call.args)
        val said = args.optString("text", reply.text).trim()
        log.conclude(said)
        return End.Concluded(said)
    }

    /** Idle until the person speaks: [waited], which is the whole of what a poll does. */
    private fun leave(call: Call, reply: Reply): End {
        val args = try {
            org.json.JSONObject(call.args.ifBlank { "{}" })
        } catch (e: Exception) {
            org.json.JSONObject()
        }
        return when (call.tool) {
            "ask" -> asked(call, args, reply)
            "conclude" -> concluded(call, args, reply)
            else -> waited(call)
        }
    }

    /**
     * `wait` ends the turn, and only its first pair reaches the context.
     *
     * The evidence keeps every poll — fifteen rows an hour, which is why that file's limit is
     * generous — while the working context keeps the first call and its result and nothing after,
     * because a context that grew a timestamped row every four minutes would never be the same
     * request twice, and the poll is only cheap because it is a cache hit.
     *
     * **The result is written once, even though the tool never runs.** `wait` is terminating, so
     * nothing answers it in the loop — and a context whose last assistant message calls a tool
     * with no result is a request every endpoint refuses. The pair is what keeps the rebuilt
     * conversation legal while the agent is idle.
     */
    private fun waited(call: Call): End {
        val first = log.contextRows().none { it.kind == AgentLog.CALL && it.name == "wait" }
        if (first) {
            log.call(call.tool, call.args)
            log.got(call.tool, WAITING)
            log.note("idle", "waiting for the operator")
        } else {
            // Session only, both rows: the box still sees the poll, the model does not.
            log.bookkeeping(AgentLog.CALL, call.tool, call.args)
            log.bookkeeping(AgentLog.GOT, call.tool, WAITING)
            log.bookkeeping(AgentLog.NOTE, "idle", "waiting for the operator")
        }
        return End.Waited
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
            log.call(call.tool, call.args)
            log.got(call.tool, "${AgentLog.FAILURE} interrupted: $why")
        }
        log.note("stopped", why)
        // A person speaking is not a session stopping; a person stopping is.
        return if (why == BY_OPERATOR) {
            End.Stopped(why, reason = AgentSession.StopReason.BY_OPERATOR)
        } else {
            End.Interrupted(why)
        }
    }

    /** The model answered with prose and no call: nothing to do, and nothing to ask again. */
    private fun wordsWithoutCalls(reply: Reply): End {
        val said = reply.text.trim().ifEmpty { "(the model said nothing)" }
        log.note("stopped", "no tool calls, so the turn ended")
        return End.Stopped(
            "the model answered with words and no tool call: $said",
            reason = AgentSession.StopReason.LOOP,
        )
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
            log.got(call.tool, body)
            if (call.tool in AgentTools.WRITING && !body.startsWith(AgentLog.FAILURE)) changed = true
        }
        // A call that wrote nothing and left the project as it was is a call that did nothing —
        // the count the idle guard is about. Files, not the reply's own claim about itself.
        return changed || changedFiles() != before
    }

    /** A terminating call in company: refused as each call's own result, and nothing done. */
    private fun refusedInCompany(calls: List<Call>) {
        for (call in calls) {
            val id = log.call(call.tool, call.args)
            val body = if (call.tool in AgentTools.TERMINATING) {
                AgentLog.FAILURE +
                    " ${call.tool} is a terminating tool; call it alone. Nothing was done."
            } else {
                answer(call)
            }
            log.got(call.tool, body)
            Trace.note("agent", "$project call ${call.tool} #$id")
        }
    }

    /** Runs one call and returns its body with its `0` or `1` already on the front. */
    private fun runTool(call: Call): String = tools.run(call)

    /**
     * A call's result, **with the operator's deadline on it.**
     *
     * The conversation has one shape that is legal and it is unforgiving: every tool call in an
     * assistant message must be answered before the next request. So a command that outlasts the
     * deadline is answered now, with the truth — it was stopped, and nothing was kept — and the turn
     * carries on. Nothing is handed over and nothing is asked again.
     *
     * **The deadline is honoured by the command, not imposed on it.** `clone` asks JGit's
     * `ProgressMonitor` whether it may continue and `restore` checks between files, so a timeout
     * really stops the work; a timer over code that never looks at it would only stop the *waiting*,
     * leaving a thread writing into the project after the agent had been told it failed.
     */
    private fun answer(call: Call): String {
        if (call.tool !in AgentTools.LONG || foregroundMs <= 0L) return runTool(call)
        return tools.runWithin(call, Deadline(foregroundMs))
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
     * [line] is the row's position in `session.log` counted from one — the handle the `read` tool
     * takes, so `read agent/session.log from 7 lines 1` returns exactly this row, with its ctx
     * column and its id. [id] is the handle `forget` takes. Two tools, two handles on one row: one
     * to look, one to act.
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
        // The file's own line numbers, because that is what `read` pages through: the context is a
        // filtered view, so a row's position in it is not its position in the file.
        val lineOf = log.rows().withIndex().associate { (index, row) -> row.id to index + 1 }
        val costs = log.contextRows()
            .map { row ->
                RowCost(row.id, row.kind, row.name, ContextBudget.tokens(row.line), lineOf[row.id] ?: 0)
            }
            .filter { it.tokens > 0 }
            .sortedByDescending { it.tokens }
            .take(BIGGEST_ROWS)
        return Opening(measure(layers.message, log.contextRows(), tools), costs)
    }

    /** How many files in the project are different from what was committed. */
    private fun changedFiles(): Int = try {
        git.changes(project).total
    } catch (e: Throwable) {
        -1
    }

    /**
     * The request, rebuilt from the record every time.
     *
     * Reading it back rather than keeping it in memory is what makes the agent's own compaction real:
     * a row it forgot is `out` in the record, and the next request genuinely does not contain it. A
     * cached transcript would keep sending what it threw away.
     */
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
    private fun measure(system: String, rows: List<AgentLog.Row>, tools: List<ToolSpec>): Long =
        ContextBudget.tokens(system) +
            rows.sumOf { ContextBudget.tokens(it.line) } +
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
                // rows costs a line and saves the paging: `read from N lines 1` is one request for
                // the exact text of the row that matters most.
                append("\nThe rows taking the most room — `read agent/session.log from <line> `")
                append("`lines 1` shows one, with its ctx column and its id: ")
                append(biggest.joinToString("; ") {
                    "line ${it.line}: #${it.id} ${it.kind} ${it.name}, about " +
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
    fun size(): Long = measure(
        AgentPrompt.read(accountDir, tools.projectDir).message,
        log.contextRows(),
        AgentTools.specs(),
    )

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
        val awaiting = ArrayDeque<String>()

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
                AgentLog.CALL -> if (row.name !in AgentLog.SPEAKING) {
                    calls.add(Call(callId(row.id), row.name, row.body))
                }
                AgentLog.GOT -> {
                    flush()
                    // **An answer with no call above it is not sent.** Every request needs each
                    // tool message to answer a call the model made; an orphan result is a message
                    // it cannot place and an endpoint will refuse. The clear-up rules cannot
                    // produce one, but a reset can: the evidence keeps a call the context no
                    // longer has, and the app's own seal then answers that call in the evidence
                    // alone.
                    val id = awaiting.removeFirstOrNull() ?: continue
                    out.add(Msg.tool(id, row.body))
                }

                AgentLog.ASK, AgentLog.CONCLUDE -> {
                    flush()
                    out.add(Msg.assistant(row.body))
                }

                AgentLog.NOTE -> if (row.name == "agent" || row.name == "reason") {
                    text.append(row.body).append("\n")
                }
            }
        }
        flush()
        return out
    }

    /** The wire id of a call, derived from the row that recorded it — stable across rebuilds. */
    fun callId(rowId: Int): String = "call_$rowId"
}
