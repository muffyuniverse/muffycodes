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
    /** Called with a project's fresh rows after a check, when somebody is reading them. */
    private val polled: (String, List<AgentLog.Row>) -> Unit = { _, _ -> },
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

    /**
     * **One request at a time, on purpose.**
     *
     * The per-project guard above already allows several projects to be due at once, so this thread
     * is the thing that decides how many of them actually go out together: one. The operator's ruling
     * (2026-10-27) is that serial is easier to manage, predictable and simple — and the alternatives
     * each cost something this app does not want to pay:
     *
     * - **Three projects due is three requests in flight**, and a ping's whole point is buying warmth
     *   cheaply. Three at once is three times the bill for the same warmth, and a phone on a battery
     *   doing three simultaneous completions is a phone that gets hot.
     * - **Concurrency needs a queue policy that can be explained**: what happens when four are due,
     *   which one goes first, whether a person's turn may jump ahead of a ping. Serial answers all of
     *   that by not asking it.
     *
     * The cost is honest and it is paid by whoever is at the back: a ping on a slow model makes the
     * next project wait. That is the trade — a delay a person can predict, instead of a bill and a
     * temperature nobody can.
     *
     * **What is *not* serialised:** a `background` call, which gets a thread of its own
     * ([Background.real]), and a turn from the screen, which does not run here at all. This queue is
     * the app's own keepalive and wake work, which nothing is waiting on.
     */
    private val worker = Executors.newSingleThreadExecutor()

    /**
     * **The unit of concurrency is the agent, and today an agent is its project.**
     *
     * These are agent ids, not project names — they only look the same because one project has one
     * agent. A subagent will be `"<project>/<name>"`, and everything below keeps working unchanged:
     * two agents of one project are two ids, so neither blocks the other, and two agents writing the
     * same file is a *scope* rule rather than a threading one (see `docs/PLAN.md`).
     *
     * **Keyed this way on purpose, before there was a second kind of agent.** The alternative was to
     * write "project" everywhere and rename later, which is the change that goes wrong: a guard whose
     * name says project will be *reasoned about* as a project, and a subagent added under it would be
     * serialised by a set that was never meant to hold two.
     */
    private val inFlight = mutableSetOf<String>()

    /** Agents a screen turn is running, which the tick must not disturb. Guarded by [inFlight]. */
    private val held = mutableSetOf<String>()

    /**
     * The agents with a check or a turn in flight — **the tick's own guard, and the screen's.**
     *
     * A turn from the screen and a wake from the tick would otherwise be two requests on one
     * conversation at the same time, writing two endings and two sets of results. The screen takes
     * the same claim a ping does, so the tick sees the agent as busy and leaves it alone.
     */
    fun polling(): Set<String> = synchronized(inFlight) { (inFlight + held).toSet() }

    /** Whether [project]'s agent is busy — **the comparison, in one place.**
     *
     * `polling()` holds agent ids, and a caller holding a project name must not compare the two
     * directly: they are equal today and will not be the day a project has a subagent, and the
     * mistake would be silent (the screen would think an agent was idle while it worked).
     */
    fun isBusy(project: String): Boolean = agentId(project) in polling()

    /**
     * Says "a turn is being taken for [agent]" until the returned function is called.
     *
     * Separate from [inFlight] rather than the same set: a turn and a ping may overlap for a moment
     * (a ping in flight while the person types), and one finishing must not release the other's claim.
     */
    fun hold(agent: String): () -> Unit {
        synchronized(inFlight) { held += agent }
        return { synchronized(inFlight) { held -= agent } }
    }

    /**
     * The id of the agent that owns a project's conversation.
     *
     * **A function, not a string concatenation at each call site**, because it is the one place that
     * will change when a project has more than one agent. Everything that claims, releases or checks
     * work asks this — so `inFlight` holds ids rather than project names without any caller knowing
     * that a subagent is possible at all.
     */
    fun agentId(project: String): String = project

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
            // **The folder is the project, and a tick can see whether it is there for free.** This
            // used to be asked only on the way into a request, so a session the keepalive had no
            // reason to poll — switched off, its ceiling reached, asked too recently — outlived the
            // folder it was for, and the screen kept offering a conversation with nothing behind it.
            // It is not a poll and does not wait for one: the person may delete the folder, another
            // app may, and the phone may tidy it away.
            if (!ws.projectDir(session.project).isDirectory) {
                Trace.note("agent-gone", session.project)
                sessions.remove(session.project)
                continue
            }
            var current = session
            if (current.reminderDue(now, reminderMs)) {
                current = current.reminded()
                put(current)
                Trace.note("agent-reminder", current.notice() ?: "")
                notify(current)
            }
            // **A gap is its own event, and it does not need a request to be worth writing.**
            // The phone being off is a fact about the phone; the keepalive's ceiling is a fact
            // about the bill, and a session that has been asleep for three hours has also passed
            // its ceiling — so reading the gap inside the ping would have lost the one row that
            // makes the hole visible. The wait restarts from the wake: the cache was lost hours
            // ago, and a ping now would buy four minutes of warmth for someone who is not here.
            val missed = current.missedBy(now, pollMs)
            if (missed > 0) {
                noteGap(current.project, missed)
                current = current.polled(now)
                put(current, speak = false)
            }
            // **A finished command outranks a ping, and does not wait for the interval.** Its own work
            // coming back is the one thing worth spending a turn on that the person did not ask for.
            //
            // A stopped session is not woken, and "stopped" is four things: the person's Stop, a
            // provider that refused eight times, the loop guard, and an error. Three of those already
            // ask the person to look, and a wake on the fourth would spend a request against the
            // provider that just refused it. An `asked` session *is* woken: the result may be exactly
            // what it was waiting for.
            //
            // Nothing is thrown away by the skip. The completion stays in the registry, so a session
            // switched back on has no watermark and collects it on the next tick.
            val finished = if (current.state == AgentSession.State.STOPPED) {
                emptyList()
            } else {
                bg.finishedSince(current.project, current.wokenThrough)
            }
            // **The setting decides, not the session's copy of it.** The person may have changed
            // the interval, the switch or the ceiling since this session was written, and a tick is
            // when that matters.
            if (finished.isNotEmpty() && agentId(current.project) !in polling()) {
                wake(current, now, finished)
            } else if (
                current.warmDue(now, keepAlive, pollMs, warmMs) &&
                agentId(current.project) !in polling()
            ) {
                warm(current, now)
            }
        }
        changed(sessions.read())
    }

    /**
     * A background command finished: **one real turn, so the agent can do something about it.**
     *
     * The task is the same shape as a ping's — claim the session, build the agent, run on the worker
     * — and only the work differs, which is why both go through [dispatch]. What makes it a turn is
     * [Agent.wake]: the model is asked with the finished file named in the conversation, so it can
     * read the result, act on it, ask, or conclude, exactly as if the person had said "that finished".
     */
    private fun wake(session: AgentSession, now: Long, finished: List<Background.Done>) =
        dispatch(session, now, woke(finished))

    /** What the agent is told, in the conversation, about the command that landed. */
    private fun woke(finished: List<Background.Done>): String = buildString {
        append(
            if (finished.size == 1) {
                "A background command has finished: "
            } else {
                "${finished.size} background commands have finished: "
            },
        )
        append(finished.joinToString("; ") { "${it.path} (${it.tool})" })
        append(". Read the result and carry on, or conclude if there is nothing to do.")
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
    private fun warm(session: AgentSession, now: Long) = dispatch(session, now, null)

    /**
     * One request for [session]: **[waking] is the sentence a wake carries, or null for a ping.**
     *
     * The two branches share everything that is not the work itself — the project check, the claim,
     * the model and its window, the worker, the in-flight flag — because a second copy of those is a
     * second answer to "which model, which limits, who owns this session".
     */
    private fun dispatch(session: AgentSession, now: Long, waking: String?) {
        val project = session.project
        if (!ws.projectDir(project).isDirectory) {
            // A project that is gone takes its session with it, rather than polling a folder that
            // does not exist for ever.
            Trace.note("agent-gone", project)
            sessions.remove(project)
            changed(sessions.read())
            return
        }
        synchronized(inFlight) { inFlight += agentId(project) }
        // **The claim, written before the work goes out, and it names which kind of work it was.** A
        // wake also moves the watermark, so the same finished file cannot wake the agent twice.
        put(if (waking == null) session.polled(now) else session.woken(now), speak = false)
        Trace.note(
            if (waking == null) "agent-warm-due" else "agent-wake",
            "$project · ${session.label(now)}",
        )
        val active = model()
        // **The model's own window, read from the choice rather than guessed.** A poll builds the
        // same limits a turn does, and a ceiling the screen and the runner disagree about is a trim
        // nobody can explain.
        val limits = ContextBudget.Limits.of(
            triggerAsked = settings.triggerTokens.toLong(),
            hardAsked = settings.hardTokens.toLong(),
            targetAsked = settings.targetTokens.toLong(),
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
                    // **The clear-up point.** `forget` refuses under it, so the number that applies
                    // is the one in force when the call arrives. `limits` is already built here from
                    // the model's own window, so the trigger is its own resolved number rather than
                    // a second reading of the settings.
                    triggerTokens = { limits.triggerAt },
                    sessionTokens = settings.sessionLimit,
                )
                if (waking == null) {
                    agent.warm() to log.rows()
                } else {
                    agent.wake(waking) to log.rows()
                }
            } catch (t: Throwable) {
                // What escapes the ping is the app's own machinery rather than the transport, and it
                // is the ladder's business for the same reason a refused key is: the alternative is
                // a session that keeps pinging into a wall it cannot see.
                Agent.Warm.Failed(Secrets.explain(t, Agent.Warm.NOT_WARM), stopping = true) to
                    emptyList()
            }
            val (how, rows) = warmth
            // **The tick's clock, not the wall's.** The whole point of `now` being a parameter is
            // that a session's timing is one number; mixing a test's clock with the phone's made
            // the retry ladder land an hour away from where the state machine had put it.
            val current = sessionOf(project) ?: session
            // **A wake is a turn; a ping is a check**, and the difference is the work that was done.
            // A turn's ending moves the session — ask, conclude, stopped, or a blip onto the ladder —
            // and it may speak. A ping's may only move the clock: the session already needed the
            // person (or did not) long before this check, and posting the question again every four
            // minutes is what the reminder ladder is for, and it counts its own.
            if (how is Agent.End) {
                put(how.onto(current, now, reconnectMs = settings.reconnectMs))
                polled(
                    project,
                    // **One line when an outage ends, with the count.** The outage is bracketed — the
                    // first failure in the record, and this — rather than left as eight `stopped` rows
                    // saying what one row and a number say.
                    if (session.retries > 0 && how !is Agent.End.Stopped) {
                        afterOutage(session, project, rows)
                    } else {
                        rows
                    },
                )
            } else if (how is Agent.Warm.Failed && how.stopping) {
                // **The words travel with the ladder**, or the session stops saying only "the pinned
                // provider refused" when the provider said why. This one may speak: it is the moment
                // a session that was fine became one that needs fixing.
                put(current.copy(lastWords = how.why).failed(now, forMs = settings.reconnectMs))
                polled(project, rows)
            } else {
                put(current.warmed(now), speak = false)
                polled(
                    project,
                    if (how is Agent.Warm.Sent && session.retries > 0) {
                        afterOutage(session, project, rows)
                    } else {
                        rows
                    },
                )
            }
            changed(sessions.read())
            // **The same id the claim was made with.** `project` and `agentId(project)` are the same
            // string today, so this line looks like a no-op either way - and the day they differ, a
            // release that named the project would leave the claim standing and wedge the agent for
            // ever. The pair is what makes the guard correct, not either line alone.
            synchronized(inFlight) { inFlight -= agentId(project) }
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

    /**
     * Writes a session to its file and says what changed.
     *
     * **[speak] is false for a ping.** The notification rule is "a session that just started needing
     * somebody speaks", and a ping is written every few minutes against a session that is already
     * asked — so notifying here posted the question again on every check, and the reminder ladder
     * counted them as its own.
     */
    private fun put(session: AgentSession, speak: Boolean = true) {
        sessions.put(session)
        Trace.note("agent-state", "${session.project} · ${session.label()}")
        // **Only a session that just started needing somebody speaks.** Otherwise every reminder
        // tick would also post here, and the reminder count would never leave the first one.
        if (speak && session.needsYou && session.reminders == 0) notify(session)
    }

    /** The record's one line when an outage ends, and the rows it leaves behind. */
    private fun afterOutage(
        session: AgentSession,
        project: String,
        rows: List<AgentLog.Row>,
    ): List<AgentLog.Row> = try {
        val record = AgentLog.open(ws.projectDir(project))
        record.bookkeeping(
            AgentLog.NOTE,
            "retry",
            "reconnected after ${session.retries} " +
                (if (session.retries == 1) "try" else "tries"),
        )
        record.rows()
    } catch (t: Throwable) {
        // A row about a reconnect is not worth failing the reconnect for.
        rows
    }

    /**
     * What the phone took, said once.
     *
     * A session whose last ping is hours old was not running, whatever the reason — a force-stop, an
     * OEM battery manager, a Doze window the system never lifted. The app cannot be told that
     * happened; it can see the clock, and a row is how a hole becomes visible instead of looking
     * like a check that took no time.
     */
    private fun noteGap(project: String, missed: Long) {
        try {
            AgentLog.open(ws.projectDir(project)).bookkeeping(
                AgentLog.NOTE,
                "gap",
                "the phone did not run the agent for ${howLong(missed)}",
            )
        } catch (t: Throwable) {
            // A row about a gap is not worth failing the tick that ended the gap for.
        }
        Trace.note("agent-gap", "$project · ${howLong(missed)}")
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
