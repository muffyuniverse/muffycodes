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
        channel()
        clock = Executors.newSingleThreadScheduledExecutor()
        runner = AgentWiring.runnerFor(
            app = application,
            notify = { session -> notify(session.notice() ?: return@runnerFor) },
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // **Not sticky.** A service the system restarts after a force-stop would be the app
        // overruling the person who stopped it. Android's own restart of a killed foreground
        // service is a different thing and arrives here as a fresh start with the state read
        // from disk, which is why the runner reads `.muffy/agents.json` rather than a field.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
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

    /** A session needs a person: that is a notification of its own, above the standing one. */
    private fun notify(text: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(
            ASKED_ID,
            Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_chat)
                .setContentTitle("The agent needs you")
                .setContentText(text)
                .setContentIntent(open())
                .setAutoCancel(true)
                .build(),
        )
    }

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
            val running = try {
                val manager = context.getSystemService(android.app.ActivityManager::class.java)
                @Suppress("DEPRECATION")
                manager?.getRunningServices(Int.MAX_VALUE)
                    ?.any { it.service.className == AgentService::class.java.name } == true
            } catch (t: Throwable) {
                // Asking what is running is no longer a thing an app may do on newer Androids; the
                // answer only decides whether to say "start" again, which is harmless.
                false
            }
            try {
                if (needed && !running) {
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
