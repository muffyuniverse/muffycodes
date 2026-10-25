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

        /** The turn could not go on, and the reason is in the record. */
        data class Stopped(val why: String) : End()
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
    }

    /**
     * Runs one turn and answers how it ended.
     *
     * [operator] is the person's own words; they are written into the record first, so a turn
     * that fails still begins with what was asked.
     */
    fun burst(operator: String): End {
        // The seal belongs here, at the start of a session, and not to a read: no endpoint
        // accepts a conversation whose last assistant message calls a tool nobody answered.
        log.beginSession()
        log.told(operator)
        Trace.note("agent", "$project: ${operator.take(80)}")

        var idle = 0
        var requests = 0
        while (requests < MAX_REQUESTS) {
            requests++
            val request = build()
            val reply = door.turn(request).getOrElse { problem ->
                val said = Secrets.explain(problem, "The model could not be asked")
                log.note("stopped", said)
                Trace.note("agent", "$project stopped: ${said.take(80)}")
                return End.Stopped(said)
            }

            if (reply.text.isNotBlank()) log.note("agent", reply.text)

            if (reply.calls.isEmpty()) {
                // Words and no calls: the model has nothing to do. Recorded and ended, rather
                // than asked again — a second request would be answered with more words.
                val said = reply.text.trim().ifEmpty { "(the model said nothing)" }
                log.note("stopped", "no tool calls, so the turn ended")
                return End.Stopped("the model answered with words and no tool call: $said")
            }

            val ending = reply.calls.firstOrNull { it.tool in AgentTools.TERMINATING }

            if (ending != null && reply.calls.size > 1) {
                // **A terminating tool is called alone.** The refusal is written as each call's
                // own result, so the model sees what it did and what it should have done — and
                // the terminating call is not obeyed, because a reply that ignored the rule is
                // a reply that was improvising.
                for (call in reply.calls) {
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
                idle++
                if (idle >= AgentTools.IDLE_CALLS) return tooIdle()
                continue
            }

            if (ending != null) {
                log.call(ending.tool, ending.args)
                val args = try {
                    org.json.JSONObject(ending.args.ifBlank { "{}" })
                } catch (e: Exception) {
                    org.json.JSONObject()
                }
                return when (ending.tool) {
                    "ask" -> {
                        val question = args.optString("question", reply.text).trim()
                            .ifEmpty { "(a question with no words)" }
                        log.ask(question)
                        Trace.note("agent", "$project asked")
                        End.Asked(question)
                    }

                    "conclude" -> {
                        val said = args.optString("text", reply.text).trim()
                        log.conclude(said)
                        Trace.note("agent", "$project concluded: ${said.take(80)}")
                        End.Concluded(said)
                    }

                    else -> {
                        // `wait` writes only the session's copy of its own row: the repeated
                        // poll belongs in the evidence, and a context that grew a timestamped
                        // row every four minutes would never be the same request twice.
                        log.note("idle", "waiting for the operator")
                        Trace.note("agent", "$project idle")
                        End.Waited
                    }
                }
            }

            val before = changedFiles()
            var changed = false
            for (call in reply.calls) {
                val id = log.call(call.tool, call.args)
                Trace.note("agent", "$project call ${call.tool} #$id")
                val body = runTool(call)
                log.got(call.tool, body)
                if (call.tool in AgentTools.WRITING && !body.startsWith(AgentLog.FAILURE)) {
                    changed = true
                }
            }
            if (!changed && changedFiles() == before) {
                idle++
            } else {
                idle = 0
            }
            if (idle >= AgentTools.IDLE_CALLS) return tooIdle()
        }
        log.note("stopped", "$MAX_REQUESTS requests in one turn")
        return End.Stopped("it made $MAX_REQUESTS requests without ending the turn")
    }

    private fun tooIdle(): End {
        val said = "it made ${AgentTools.IDLE_CALLS} calls in a row that changed nothing " +
            "and asked nothing"
        log.note("stopped", said)
        Trace.note("agent", "$project stopped: idle")
        return End.Stopped(said)
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
    fun build(): Request = Request(
        model = model.model,
        provider = model.provider,
        effort = model.effort,
        system = AgentPrompt.systemMessage(accountDir, tools.projectDir),
        messages = messages(log.contextRows()),
        tools = AgentTools.specs(),
    )

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

                AgentLog.CALL -> calls.add(Call(callId(row.id), row.name, row.body))
                AgentLog.GOT -> {
                    flush()
                    val id = awaiting.removeFirstOrNull() ?: callId(row.id)
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
