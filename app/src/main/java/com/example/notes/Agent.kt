package com.example.notes

import java.io.File

/**
 * One burst: the operator spoke, and the agent works until it ends the turn.
 *
 * **The request is built from the record, not from a list held in memory.** Everything the
 * model is sent is `context.log` mapped into messages — the instructions above it, the
 * operator's message at the end of it — so the conversation the model sees and the record a
 * person reads are the same object. An in-memory transcript beside a file is two truths, and
 * they disagree the first time the app is closed mid-turn.
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
) {

    private val tools = AgentTools(ws, project, git, log, maxResult)

    /** How a turn ended. */
    sealed class End {
        /** The agent needs the person. */
        data class Asked(val question: String) : End()

        /** The work is done. */
        data class Concluded(val said: String) : End()

        /** Idle until the person speaks. */
        data object Waited : End()

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
                is Stopped -> why
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
        const val WAITING = "0\nstill waiting"
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
        try {
            return loop(layers, operator, progress)
        } finally {
            // The evidence is the app's to keep to size, and a turn boundary is the only place a
            // trim cannot cut a conversation in half.
            log.trimSession()
            progress()
        }
    }

    private fun loop(layers: AgentPrompt.Layers, operator: String?, progress: () -> Unit): End {
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
        while (requests < maxRequests) {
            if (idle >= AgentTools.IDLE_CALLS) return finish(tooIdle())
            requests++
            // The boundary the box counts turns by, and the app's own row, not the model's.
            log.sessionOnly(AgentLog.TURN, "turn", "$requests")
            val reply = door.turn(build(layers)).getOrElse { return finish(blip(it)) }
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
                    if (work(reply.calls)) idle = 0 else idle++
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
            log.sessionOnly(AgentLog.CALL, call.tool, call.args)
            log.sessionOnly(AgentLog.GOT, call.tool, WAITING)
            log.sessionOnly(AgentLog.NOTE, "idle", "waiting for the operator")
        }
        return End.Waited
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
        log.note("stopped", said)
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
            val body = runTool(call)
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
                runTool(call)
            }
            log.got(call.tool, body)
            Trace.note("agent", "$project call ${call.tool} #$id")
        }
    }

    /** Runs one call and returns its body with its `0` or `1` already on the front. */
    private fun runTool(call: Call): String = tools.run(call)

    /** How many files in the project are different from what was committed. */
    private fun changedFiles(): Int = try {
        git.changes(project).total
    } catch (e: Throwable) {
        -1
    }

    /**
     * The request, rebuilt from the record every time.
     *
     * Reading it back rather than keeping it in memory is what makes the agent's own clear-up
     * edit real: he deletes a row from `context.log`, and the next request genuinely does not
     * contain it. A cached transcript would keep sending what he threw away.
     */
    private fun build(layers: AgentPrompt.Layers): Request = Request(
        model = model.model,
        provider = model.provider,
        effort = model.effort,
        system = layers.message,
        messages = messages(log.contextRows()),
        tools = AgentTools.specs(),
    )

    /**
     * The request as the files stand right now.
     *
     * The same composition [loop] makes, spelled once, for anything that asks what the agent
     * would be sent — a check on a phone, and the tests that prove nothing is remembered.
     */
    fun request(): Request = build(AgentPrompt.read(accountDir, tools.projectDir))

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
