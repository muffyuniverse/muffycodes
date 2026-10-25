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
    /** Called with a project's fresh rows after a poll, when somebody is reading them. */
    private val polled: (String, List<AgentLog.Row>, Agent.End) -> Unit = { _, _, _ -> },
) {

    private val sessions = AgentSessions(ws.accountDir)

    private val worker = Executors.newSingleThreadExecutor()

    /** Projects with a poll in flight, so one slow poll is not started twice in this process. */
    private val inFlight = mutableSetOf<String>()

    /** The projects with a poll in flight, so a test can wait for one to finish. */
    fun polling(): Set<String> = synchronized(inFlight) { inFlight.toSet() }

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
            if (current.pollDue(now) && current.project !in polling()) {
                poll(current, now)
            }
        }
        changed(sessions.read())
    }

    /** Sends one poll, on the worker, and keeps the session in step with how it ended. */
    private fun poll(session: AgentSession, now: Long) {
        val project = session.project
        if (!File(ws.projectsDir, project).isDirectory) {
            // A project that is gone takes its session with it, rather than polling a folder that
            // does not exist for ever.
            Trace.note("agent-gone", project)
            sessions.remove(project)
            changed(sessions.read())
            return
        }
        synchronized(inFlight) { inFlight += project }
        put(session.polled(now))
        Trace.note("agent-poll", "$project · ${session.label(now)}")
        val active = model()
        val limits = ContextBudget.Limits.of(
            triggerAsked = settings.triggerTokens.toLong(),
            hardAsked = settings.hardTokens.toLong(),
            ceiling = ContextBudget.ceiling(active.provider),
        )
        val doorFor = door(active)
        worker.execute {
            val end = try {
                val log = AgentLog.open(File(ws.projectsDir, project))
                val agent = Agent(
                    ws.accountDir,
                    project,
                    ws,
                    git,
                    log,
                    doorFor,
                    active,
                    limits = limits,
                )
                agent.poll() to log.rows()
            } catch (t: Throwable) {
                // What escapes the loop is the turn's own machinery rather than the transport, so
                // it is retried on the ladder like a blip — the alternative is a poll that fails
                // once and stops a session for good.
                Agent.End.Stopped(Secrets.explain(t, "The poll could not run"), retryable = true) to
                    emptyList()
            }
            val (how, rows) = end
            put(how.after(sessionOf(project) ?: session, System.currentTimeMillis()))
            synchronized(inFlight) { inFlight -= project }
            polled(project, rows, how)
            changed(sessions.read())
        }
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
    }
}
