package com.example.notes

/**
 * **A stopped agent's working context is reset — one rule, two doors.**
 *
 * The operator's rule: *"reset is automatic whenever agent is stopped (whether by button or timeout
 * or any other trigger)"* — and his reason: *"to get rid of stale cache and allow an agent to read
 * what is important from the session log instead of dumping stale context of questionable utility."*
 *
 * **Why this is its own file.** There are two places a session is written: [AgentRunner.put] (the
 * timer, a turn ending, the tray's stop) and `AppViewModel.putSession` (the screen's stop and every
 * redraw that follows it). The rule lived in the first, and the second walked past it — which is the
 * exact failure this app avoids everywhere else: two places giving two answers about one act. A
 * function both call cannot drift; a copy of it in each can.
 *
 * **`STOPPED` already means finished**, which is why there is no reason to carve out. A transient
 * failure never reaches a stop at all: `End.Stopped(retryable = true)` goes to [AgentSession.failed]
 * and stays `RECONNECTING` on the keepalive, retrying. `PROVIDER_REFUSED` is only reached when that
 * ladder is spent — an ending the app arrived at by exhaustion. So every reason a session is
 * `STOPPED` for is an ending, and every ending gets the same treatment.
 *
 * **Nothing standing is lost.** The instructions are read from disk per turn (`Agent.turn` →
 * `AgentPrompt.layers()`), so this drops the conversation and not the brief; and `agent/` is
 * readable — the refusal for writing there says *"Read it; `forget` takes rows out of your
 * context"* — so the agent can read back what mattered and re-forget deliberately. That is the
 * whole argument: a stop is not a judgement, and `forget` is.
 */
object AgentStops {

    /**
     * **Resets [session]'s context if it has just become `STOPPED`.**
     *
     * [was] is the session as it was stored before this write, or null if there was none. Passing it
     * rather than reading it here is deliberate: the caller has already read it, and a second read
     * of the file inside this function would be a second answer to one question — the shape of bug
     * this whole file exists to prevent.
     *
     * **A session that was already stopped is left alone.** The timer writes a stopped session every
     * few minutes to move its clock, and resetting on each of those would throw away whatever the
     * agent had read back since — turning a clean slate into a loop that never lets it recover.
     */
    fun resetIfStopped(ws: Workspace, session: AgentSession, was: AgentSession?) {
        if (session.state != AgentSession.State.STOPPED) return
        if (was?.state == AgentSession.State.STOPPED) return
        val project = session.project
        try {
            // **The session's own worktree** (§13d): resetting main's context when a branch's agent was
            // stopped would clear a conversation that is still running, and leave the stopped one's
            // context in place.
            val log = AgentLog.open(ws.treeOf(project, session.branch))
            val id = log.reset(
                "the agent was stopped (${why(session)}) — the working context was cleared. " +
                    "What it did is still in ${AgentLog.DIR}/${AgentLog.SESSION}",
            )
            Trace.note("agent-reset", "$project · stopped · row $id")
        } catch (t: Throwable) {
            // **A failure here must not stop the stop.** The session write is the thing that
            // matters: an agent that is off with a stale context is better than an agent that is on
            // because its log would not open.
            Trace.note(
                "agent-reset-failed",
                "$project · ${Secrets.explain(t, "the context was kept")}",
            )
        }
    }

    /**
     * **Why it stopped, in the words the row will carry.**
     *
     * The reason goes in the row because a reset that said only "cleared" would leave the agent
     * reading its own log with no idea whether it was an outage or its own failure — and the two
     * call for different behaviour when it comes back. [AgentSession.lastWords] wins when it is
     * there, because the app's own sentence about a specific failure is better than a category.
     */
    fun why(session: AgentSession): String = session.lastWords.ifBlank {
        when (session.reason) {
            AgentSession.StopReason.BY_OPERATOR -> "you stopped it"
            AgentSession.StopReason.PROVIDER_REFUSED -> "the provider kept refusing"
            AgentSession.StopReason.LOOP -> "it was going in circles"
            AgentSession.StopReason.ERROR -> "something went wrong"
            // **Not a failure, and the row should say so in the person's own terms.** The agent
            // waited as long as the setting allowed and switched itself off; the next agent to read
            // this row needs to know it was a rule rather than a fault.
            AgentSession.StopReason.WAITED_OUT -> "it waited as long as you allowed"
            null -> "it stopped"
        }
    }
}
