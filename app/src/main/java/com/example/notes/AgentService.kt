package com.example.notes

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Being on: the agent's clock keeps running when nobody is looking at the app.
 *
 * **Why a service at all.** A session that only ticks while the screen is open is a session that
 * stops every time the phone goes in a pocket — and "never stops until you remove it" is the
 * promise the whole feature is built on. Android gives an app two ways to keep doing work: a
 * foreground service, or a scheduled alarm it may be late about. A poll is a request whose answer
 * may be a question for the person, and a question that arrives an hour late because the system
 * batched an alarm is worse than one that arrives on time, so it is a foreground service.
 *
 * **`specialUse`, not `dataSync`.** `dataSync` is capped at six hours in any 24 on Android 15+,
 * which is the system deciding to end the operator's session. This is not a sync: it is the app
 * holding a conversation on the person's behalf, and the declared subtype says so out loud, where
 * a reviewer can read it (see `AndroidManifest.xml`).
 *
 * **The service is where the rules are not.** Everything it does — which session is due, what a
 * poll costs, where the session ends up — is [AgentRunner]'s, the same object the screen ticks. The
 * service is a clock and a notification, which is the part that cannot be tested without a phone.
 */
class AgentService : Service() {

    private var runner: AgentRunner? = null
    private lateinit var clock: ScheduledExecutorService

    /** The last runtime state in the notification: how many, and how many need a person. */
    @Volatile
    private var strip: String = ""

    override fun onCreate() {
        super.onCreate()
        alive = true
        channel()
        clock = Executors.newSingleThreadScheduledExecutor()
        runner = AgentWiring.runnerFor(
            app = application,
            // **The session, not its sentence.** The notification carries the project's own
            // buttons — Stop this session has to know which one — so narrowing it to a string here
            // would be narrowing it to the one thing the buttons do not need.
            notify = { session -> notify(session) },
            changed = { sessions ->
                val line = AgentSession.strip(sessions)
                // **The notification is the strip.** The same sentence, from the same function, so
                // what the tray says and what the app says cannot drift — and an empty strip stops
                // the service rather than leaving a permanent icon for an agent nobody runs.
                if (line != strip) {
                    strip = line
                    if (line.isEmpty()) stopSelf() else show(line)
                }
            },
        )
        // A tick every fifteen seconds, and the runner decides what is actually due. Waking more
        // often than the four-minute interval buys nothing; waking less often makes a reminder
        // late for no reason.
        clock.scheduleWithFixedDelay(
            { run() },
            0,
            TICK_SECONDS,
            TimeUnit.SECONDS,
        )
    }

    /** One tick, and nothing the runner throws may take the service down with it. */
    private fun run() {
        try {
            runner?.tick()
        } catch (t: Throwable) {
            Trace.failure(Secrets.explain(t, "The agent's clock stopped for a moment"))
        }
    }

    /**
     * **Sticky, because the operator said the phone must not decide.**
     *
     * v1's own words were "set up max resiliency for the app and its processes", and v2's "must
     * never stop until operator removes it from background — operator should decide, not android".
     * A not-sticky service is exactly the opposite: the system reclaims it under memory pressure
     * and the session dies quietly. Sticky means Android restarts it, and the state is rebuilt from
     * `.muffy/agents.json` rather than from a field, so a restart resumes the session instead of
     * losing it.
     *
     * A force-stop still wins — the person used the system's own switch — and the only thing the
     * app can do about it is say so: the next time it runs, [AgentRunner.tick] sees a session whose
     * last poll is hours old and writes a `gap` row. It does not invent a stopped state for a
     * session that is about to carry on.
     */
    /**
     * The standing service, and **the one thing a notification's button asks for.**
     *
     * `START_STICKY` is the answer for every ordinary start: the operator said the phone must not
     * decide the agent is finished, so a reclaimed process is restarted by the system and the
     * session is read back off disk.
     *
     * `ACTION_STOP` is a person pressing **Stop this session** in the tray. It goes through the
     * same door the box's Stop uses ([AgentRunner.stop]) rather than a second copy of "switch it
     * off": one act, one implementation, whichever window the person pressed it in.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val project = intent?.getStringExtra(EXTRA_PROJECT)
        if (intent?.action == ACTION_STOP && !project.isNullOrBlank()) {
            runner?.stop(project)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        alive = false
        clock.shutdownNow()
        runner?.close()
        runner = null
        super.onDestroy()
    }

    private fun channel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "The agent", NotificationManager.IMPORTANCE_LOW).apply {
                description = "One line while an agent is on: how many, and how many need you"
                setShowBadge(true)
            },
        )
    }

    /**
     * A session needs a person: a notification of its own, above the standing line.
     *
     * **One per session, at the session's own id.** The design's rule is that a reminder replaces
     * the previous one in place rather than stacking a new copy every hour — but "one per session",
     * not one for the whole app: two projects both asking a question are two things to answer, and
     * a single id would show whichever was posted last and silently hide the other.
     *
     * **The two buttons are the two ways to resolve it**, and the design named them: Open, and Stop
     * this session. A notification that only opens the app makes the person walk to the screen that
     * has the switch.
     */
    internal fun notify(session: AgentSession) {
        val text = session.notice() ?: return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(
            idFor(session.project),
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("The agent needs you")
                .setContentText(text)
                .setContentIntent(open())
                .setAutoCancel(true)
                .addAction(Notification.Action.Builder(null, "Open", open()).build())
                .addAction(
                    Notification.Action.Builder(
                        null,
                        "Stop this session",
                        stop(session.project),
                    ).build(),
                )
                .build(),
        )
    }

    /** The id a project's notification lives at: stable, so a reminder replaces its own. */
    private fun idFor(project: String): Int = ASKED_ID + (project.hashCode() and 0x7FFF)

    /** What **Stop this session** sends: the project, to the one door that stops a session. */
    private fun stop(project: String): PendingIntent = PendingIntent.getService(
        this,
        project.hashCode(),
        Intent(this, AgentService::class.java)
            .setAction(ACTION_STOP)
            .putExtra(EXTRA_PROJECT, project),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** The standing line: the service's own foreground notification. */
    private fun show(text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val notification: Notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Muffy Codes")
            .setContentText(text)
            .setContentIntent(open())
            .setOngoing(true)
            .build()
        manager.notify(FOREGROUND_ID, notification)
        startForeground(FOREGROUND_ID, notification)
    }

    private fun open(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {

        /** The channel, named once so a notification and its channel cannot disagree. */
        const val CHANNEL = "agent"

        const val FOREGROUND_ID = 1
        const val ASKED_ID = 2

        /** What **Stop this session** sends, and the project it names. */
        const val ACTION_STOP = "com.example.notes.agent.STOP"
        const val EXTRA_PROJECT = "project"

        /** True while an instance of this service is alive in this process. */
        @Volatile
        private var alive = false

        /** How often the clock wakes to ask what is due. The runner owns the intervals. */
        const val TICK_SECONDS = 15L

        /**
         * Starts the service when there is something for it to do, and stops it when there is not.
         *
         * **Called on every change of session, and it answers the same question every time**: an
         * agent that is on needs a clock, and an agent that is off does not. Starting when there
         * is nothing to run would be a permanent notification for an idle app; refusing to start
         * when a session is on would be the screen doing the work and the phone doing none.
         */
        fun apply(context: Context, sessions: List<AgentSession>) {
            val needed = sessions.any { it.running || it.needsYou }
            try {
                // **Asked of this class rather than of the system.** `getRunningServices` is
                // deprecated, and on newer Androids it answers only about the caller's own
                // services — which is exactly the question, at the price of a binder call on every
                // screen refresh. A flag set in `onCreate` and cleared in `onDestroy` is the same
                // answer for nothing, and it is what lets [apply] be called on every tick.
                if (needed && !alive) {
                    val intent = Intent(context, AgentService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                }
            } catch (t: Throwable) {
                // A background start refused by the system is not a crash: the screen's own tick
                // still runs while the app is open, and the trace says the service did not start.
                Trace.failure(Secrets.explain(t, "The agent's clock could not be started"))
            }
        }
    }
}
