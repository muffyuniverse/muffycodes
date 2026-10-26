package com.example.notes

import java.io.File
import java.util.concurrent.Executors

/**
 * The agent's clock: read the sessions, remind whoever needs it, poll whoever is due.
 *
 * **One implementation, two callers.** The screen's tick and the foreground service's tick are the
 * same act — a poll is a request the app makes on its own schedule, and whether a person happens to
 * be looking at the phone is not a fact about it. Two copies of "which session is due" would be two
 * answers to the same question, and the one the person reads would be whichever ran last.
 *
 * The runner is deliberately *not* a view model: it has no Compose state and no `AndroidViewModel`,
 * so it can be built by a service, by a screen, or by a test with three lines. What it cannot do is
 * **speak** — a burst is the operator's act and belongs to the screen that carries their words.
 *
 * **A poll is claimed before it is made.** [AgentSession.polled] is written to `.muffy/agents.json`
 * *before* the request goes out, so a second ticker reading the same file sees a session that is
 * not due. That is what makes it safe for the service and the screen to share one clock without a
 * lock between them — and it is the reason `polled()` never clears the retry count, either.
 */
class AgentRunner(
    private val ws: Workspace,
    private val git: ProjectGit,
    private val settings: SettingsStore,
    /** The model to ask, read at the moment of use: the person may change it between polls. */
    private val model: () -> ActiveModel,
    /** Where requests go. Swappable, so the runner can be driven without a socket. */
    private val door: (ActiveModel) -> Door,
    /** Told when a session needs a person, or when one stopped for a cause nobody chose. */
    private val notify: (AgentSession) -> Unit = {},
    /** Called with the sessions after anything changes, so a screen can redraw. */
    private val changed: (List<AgentSession>) -> Unit = {},
    /** Called with a project's fresh rows after a ping, when somebody is reading them. */
    private val polled: (String, List<AgentLog.Row>, Agent.Warm) -> Unit = { _, _, _ -> },
    /**
     * Where a `background` call's work runs — **one thread per call, made when it is asked for.**
     *
     * The previous design kept a fixed pool here and held the test suite open for eighteen minutes
     * after the last test had finished; a thread that exists only for work that exists has nothing
     * to close and nothing to leak.
     */
    val bg: Background = Background.real(),
) {

    private val sessions = AgentSessions(ws.accountDir)

    private val worker = Executors.newSingleThreadExecutor()

    /** Projects with a poll in flight, so one slow poll is not started twice in this process. */
    private val inFlight = mutableSetOf<String>()

    /** The projects with a poll in flight, so a test can wait for one to finish. */
    fun polling(): Set<String> = synchronized(inFlight) { inFlight.toSet() }

    /** How often a waiting conversation is kept warm, from Settings — the person's number. */
    private val pollMs: Long get() = settings.pollMs

    /** Whether it is kept warm at all, from Settings. */
    private val keepAlive: Boolean get() = settings.keepAlive

    /** How long keeping it warm may go on, from Settings; zero is "until the person speaks". */
    private val warmMs: Long get() = settings.warmMs

    /** How often a session that needs a person reminds them, from Settings. */
    private val reminderMs: Long get() = settings.remindMinutes * 60_000L

    /** The sessions as the file has them, which is the only copy that outlives the process. */
    fun sessions(): List<AgentSession> = sessions.read()

    /**
     * One tick of the clock: remind whoever needs reminding, and poll whoever is due.
     *
     * **The clock is a parameter.** Every rule about four minutes and one hour is then a test that
     * moves a number, which is the only way to test them at all — and it is why the shell that
     * calls this can be a service, a screen, or a test, without changing a rule.
     */
    fun tick(now: Long = System.currentTimeMillis()) {
        for (session in sessions.read()) {
            var current = session
            if (current.reminderDue(now, reminderMs)) {
                current = current.reminded()
                put(current)
                Trace.note("agent-reminder", current.notice() ?: "")
                notify(current)
            }
            // **The setting decides, not the session's copy of it.** The person may have changed
            // the interval, the switch or the ceiling since this session was written, and a tick is
            // when that matters.
            if (current.warmDue(now, keepAlive, pollMs, warmMs) && current.project !in polling()) {
                warm(current, now)
            }
        }
        changed(sessions.read())
    }

    /**
     * Keeps one waiting conversation warm, on the worker, and keeps the session in step with it.
     *
     * **A ping is not a turn and cannot end one.** Its only outcomes are "the cache is warm again"
     * and "this one did not arrive": nothing is written into the conversation, nothing is parsed,
     * and the session's state, sub-state and retry count are untouched by a success.
     *
     * [now] is the tick that decided the ping was due, and the clock moves to it either way: a
     * session whose timing came from two clocks would land an hour away from where the state machine
     * put it.
     */
    private fun warm(session: AgentSession, now: Long) {
        val project = session.project
        if (!ws.projectDir(project).isDirectory) {
            // A project that is gone takes its session with it, rather than polling a folder that
            // does not exist for ever.
            Trace.note("agent-gone", project)
            sessions.remove(project)
            changed(sessions.read())
            return
        }
        synchronized(inFlight) { inFlight += project }
        // **What the phone took, said once.** A session whose last poll is hours old was not
        // running, whatever the reason — a force-stop, an OEM battery manager, a Doze window the
        // system never lifted. The app cannot be told that happened; it can see the clock, and a
        // row is how a hole becomes visible instead of looking like a poll that took no time.
        val missed = session.missedBy(now, pollMs)
        if (missed > 0) {
            try {
                AgentLog.open(ws.projectDir(project)).bookkeeping(
                    AgentLog.NOTE,
                    "gap",
                    "the phone did not run the agent for ${howLong(missed)}",
                )
            } catch (t: Throwable) {
                // A row about a gap is not worth failing the poll that ended the gap for.
            }
            Trace.note("agent-gap", "$project · ${howLong(missed)}")
        }
        put(session.polled(now))
        Trace.note("agent-warm-due", "$project · ${session.label(now)}")
        val active = model()
        // **The model's own window, read from the choice rather than guessed.** A poll builds the
        // same limits a turn does, and a ceiling the screen and the runner disagree about is a trim
        // nobody can explain.
        val limits = ContextBudget.Limits.of(
            triggerAsked = settings.triggerTokens.toLong(),
            hardAsked = settings.hardTokens.toLong(),
            ceiling = ContextBudget.ceiling(active.provider, active.contextLength, active.maxOutput),
        )
        val doorFor = door(active)
        worker.execute {
            val warmth = try {
                val log = AgentLog.open(ws.projectDir(project))
                val agent = Agent(
                    ws.accountDir,
                    project,
                    ws,
                    git,
                    log,
                    doorFor,
                    active,
                    maxResult = ContextBudget.resultLimit(settings.maxResult, active.contextLength)
                        .inForce.toInt(),
                    limits = limits,
                    // The ladder's position, so a repeated failure is not written down again.
                    retrying = session.retries > 0,
                    // A command that outlasts the wait is stopped, and its result says so. The
                    // wait is the operator's setting, not the agent's choice.
                    bg = bg,
                    foregroundMs = settings.foregroundMs,
                    backgroundMs = { settings.backgroundMs },
                    sessionBytes = settings.sessionBytes,
                )
                agent.warm() to log.rows()
            } catch (t: Throwable) {
                // What escapes the ping is the app's own machinery rather than the transport, and it
                // is the ladder's business for the same reason a refused key is: the alternative is
                // a session that keeps pinging into a wall it cannot see.
                Agent.Warm.Failed(Secrets.explain(t, "The conversation could not be kept warm"),
                    stopping = true) to emptyList()
            }
            val (how, rows) = warmth
            // **The tick's clock, not the wall's.** The whole point of `now` being a parameter is
            // that a session's timing is one number; mixing a test's clock with the phone's made
            // the retry ladder land an hour away from where the state machine had put it.
            val current = sessionOf(project) ?: session
            // **Three answers, and only one of them touches the session.** Sent moves the clock; a
            // refusal of the key or the credit goes on the ladder, because it will not heal by
            // waiting; anything else is a ping missed and stays out of the session's story — a
            // provider that will not take a one-token answer must not stop the person's work.
            when (how) {
                is Agent.Warm.Sent -> put(current.warmed(now))
                is Agent.Warm.Failed -> {
                    if (how.stopping) {
                        // **The words travel with the ladder**, or the session stops saying only
                        // "the pinned provider refused" when the provider said why.
                        put(current.copy(lastWords = how.why).failed(now))
                    } else {
                        put(current.warmed(now))
                    }
                }
            }
            // **One line when it comes back, with the count.** The other half of the design's rule:
            // an outage is bracketed — the first failure in the record, and this — rather than left
            // as fifteen `stopped` rows saying what one row and a number say.
            var shown = rows
            if (session.retries > 0 && how is Agent.Warm.Sent) {
                try {
                    val record = AgentLog.open(ws.projectDir(project))
                    record.bookkeeping(
                        AgentLog.NOTE,
                        "retry",
                        "reconnected after ${session.retries} " +
                            (if (session.retries == 1) "try" else "tries"),
                    )
                    shown = record.rows()
                } catch (t: Throwable) {
                    // A row about a reconnect is not worth failing the reconnect for.
                }
            }
            polled(project, shown, how)
            changed(sessions.read())
            // **Last, and the flag means "the poll is not finished" — screen included.** It used
            // to be cleared before the screen was told, so anything waiting on it could read the
            // session as it stood before the poll. Not in a finally, because every throw above is
            // already caught and turned into an ending.
            synchronized(inFlight) { inFlight -= project }
        }
    }

    /**
     * Switches a session off — **the tray's own door, and only that one.**
     *
     * The box's Stop does more than this (it also interrupts the request in flight and drops the
     * words waiting behind it), because the box owns those things and the notification does not.
     * What the two share is the session write, and it is here so the notification cannot invent a
     * second meaning for "the person stopped it".
     */
    fun stop(project: String) {
        val session = sessionOf(project) ?: return
        sessions.put(session.stopped(AgentSession.StopReason.BY_OPERATOR))
        Trace.tap("stop agent", project)
        Trace.note("agent-state", "$project · stopped by you")
        changed(sessions.read())
    }

    /** A span a person reads: `9 min`, `3 h 12 min`. */
    private fun howLong(ms: Long): String {
        val minutes = (ms / 60_000).coerceAtLeast(1)
        return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
    }

    /** Writes a session to its file and says what changed. */
    private fun put(session: AgentSession) {
        sessions.put(session)
        Trace.note("agent-state", "${session.project} · ${session.label()}")
        // **Only a session that just started needing somebody speaks.** Otherwise every reminder
        // tick would also post here, and the reminder count would never leave the first one.
        if (session.needsYou && session.reminders == 0) notify(session)
    }

    /** The session of [project] as the file has it, or null. */
    private fun sessionOf(project: String): AgentSession? =
        sessions.read().firstOrNull { it.project == project }

    /** Stops the runner's worker. Nothing else holds a thread, and closing twice is not an error. */
    fun close() {
        worker.shutdownNow()
        // **The command pool too.** A runner without this leaves two threads alive after the test
        // that made it, and Gradle's fork waits for every live thread — which is how a suite that
        // finishes in four minutes holds a job open until it is cancelled.

    }
}
