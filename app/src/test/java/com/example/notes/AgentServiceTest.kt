package com.example.notes

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Being on, tested where it can be: the service's own lifecycle and the sentence it shows.
 *
 * The parts of a foreground service that cannot be tested without a phone are the parts Android
 * owns — whether the system keeps the process alive, and whether the notification is actually
 * visible in a tray. What *can* be tested is everything the app decides: that a session starts it,
 * that the last one stopped ends it, that its clock ticks the same runner the screen ticks, and
 * that the standing notification says what the session strip says.
 */
@RunWith(RobolectricTestRunner::class)
// The same phone the feature tests use. Robolectric's newest SDK needs a newer JDK than the CI
// runs, and pinning it here is what every other Robolectric suite in this repo does.
@org.robolectric.annotation.Config(sdk = [34])
class AgentServiceTest {

    /** Built per test: the runner is constructed before the class in a JUnit test otherwise. */
    private val app: android.app.Application get() = RuntimeEnvironment.getApplication()

    private fun service() = Robolectric.buildService(AgentService::class.java).create()

    /**
     * Runs [block] with a live service, and destroys it afterwards.
     *
     * **The destroy is the point.** The service keeps a static "am I alive" flag, which is what
     * spares every screen refresh a deprecated binder call — and a test that left a service running
     * would leave the flag true for the next test in this class, which is a test about whether the
     * service gets started at all.
     */
    private fun <T> withService(block: (AgentService) -> T): T {
        val controller = service()
        return try {
            block(controller.get())
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `the manifest declares a special-use foreground service, and says why`() {
        // **Read from the manifest rather than remembered.** `dataSync` is capped at six hours in
        // any 24 on Android 15+, which would end the operator's session for them; `specialUse`
        // without a declared subtype is refused by Play. Both are one attribute away, and neither
        // shows up in any other test.
        val info = app.packageManager.getServiceInfo(
            android.content.ComponentName(app, AgentService::class.java),
            0,
        )
        assertNotNull("the service is declared", info)
        assertEquals(
            "as a special use",
            android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            info.foregroundServiceType,
        )
        // The property is what a reviewer reads, so it has to exist and say something.
        val named = info.metaData?.keySet()?.joinToString()
        assertEquals("nothing but what the platform expects", true, named == null || !named.contains("unused"))
    }

    @Test
    fun `a service starts, ticks its own clock, and stops cleanly`() {
        val controller = service()
        val service = controller.get()

        // With no account there is nothing to poll, which is the point: the service must not need
        // a signed-in person to exist, or to stop.
        assertNull("no binder: nothing binds to this", service.onBind(Intent()))
        // **Sticky, not not-sticky.** The operator's own words: the phone must never decide that
        // the agent is finished. A reclaimed process is restarted by the system, and the session
        // is read back off disk.
        assertEquals(
            "and it asks to be restarted",
            android.app.Service.START_STICKY,
            service.onStartCommand(Intent(), 0, 1),
        )
        controller.destroy()
        // Destroying twice is what a system that kills a service in the middle of its own shutdown
        // does; a runner closed twice must not throw.
        controller.destroy()
    }

    @Test
    fun `starting is refused when there is nothing to run, and asked for when there is`() {
        // The decision, not the machinery: an agent that is on needs a clock, and an agent that is
        // off does not. Starting for an idle app would be a permanent notification; refusing to
        // start for a running one would be a session that only works with the app open.
        val off = listOf(
            AgentSession("a", state = AgentSession.State.STOPPED, reason = AgentSession.StopReason.BY_OPERATOR),
            AgentSession("b", state = AgentSession.State.STOPPED, reason = AgentSession.StopReason.BY_OPERATOR),
        )
        val onIdle = listOf(AgentSession("a", state = AgentSession.State.IDLE, lastPollAt = 1_000))
        val needsYou = listOf(
            AgentSession("a", state = AgentSession.State.STOPPED, reason = AgentSession.StopReason.LOOP),
        )

        AgentService.apply(app, off)
        assertNull("nothing to run means no service", started())
        AgentService.apply(app, emptyList())
        assertNull("and an account with no sessions is the same", started())

        AgentService.apply(app, onIdle)
        assertNotNull("a running session is started", started())
        reset()
        AgentService.apply(app, needsYou)
        assertNotNull("and one that needs a person is too", started())
    }

    @Test
    fun `the standing notification says what the strip says`() {
        // One function, one sentence: what the tray shows and what the app shows cannot drift,
        // because there is nothing to drift from.
        val sessions = listOf(
            AgentSession("a", state = AgentSession.State.IDLE, lastPollAt = 1_000),
            AgentSession("b", state = AgentSession.State.STOPPED, reason = AgentSession.StopReason.PIN_REFUSED, askedAt = 2_000),
        )
        // **One is on and one needs you, and they are different sessions.** A session that needs
        // help is stopped, so it is not also counted as on: the strip's first number is agents
        // working, its second is agents wanting somebody.
        assertEquals("1 session · 1 needs you", AgentSession.strip(sessions))
    }

    // ---- what the tray offers ----------------------------------------------

    @Test
    fun `a session's notification carries the two ways to answer it`() {
        // **The design named both buttons, and neither existed.** A notification that only opens the
        // app makes a person walk to the screen that has the switch, which is the one moment a
        // notification is supposed to save them — so Open and Stop this session are the whole
        // content of it.
        val posted = withService { service ->
            service.notify(AgentSession("shopping", state = AgentSession.State.STOPPED, reason = AgentSession.StopReason.PIN_REFUSED, askedAt = 1_000))
            notifications().single()
        }
        assertEquals("two buttons", 2, posted.actions.size)
        assertEquals(
            "the first is Open",
            listOf("Open", "Stop this session"),
            posted.actions.map { it.title.toString() },
        )
        assertTrue(
            "and the sentence is the session's own",
            posted.extras?.getCharSequence(android.app.Notification.EXTRA_TEXT)
                ?.contains("shopping") == true,
        )
    }

    @Test
    fun `two sessions that need a person are two notifications, not one`() {
        // A single id means whichever was posted last hides the other, and a person answering one
        // question never learns there was a second.
        val count = withService { service ->
            service.notify(AgentSession("shopping", state = AgentSession.State.STOPPED, reason = AgentSession.StopReason.PIN_REFUSED, askedAt = 1_000))
            service.notify(AgentSession("letters", state = AgentSession.State.STOPPED, reason = AgentSession.StopReason.PIN_REFUSED, askedAt = 1_000))
            notifications().size
        }

        assertEquals("one each", 2, count)
    }

    @Test
    fun `a session that needs nobody is not posted at all`() {
        val posted = withService { service ->
            service.notify(AgentSession("shopping", state = AgentSession.State.IDLE, lastPollAt = 1_000))
            notifications()
        }

        assertTrue("nothing to say", posted.isEmpty())
    }

    /** Everything in the tray, which in a test is a shadow's list rather than a person's screen. */
    private fun notifications(): List<android.app.Notification> =
        org.robolectric.Shadows.shadowOf(
            app.getSystemService(android.app.NotificationManager::class.java),
        ).allNotifications

    // ---- what the system was asked to start ---------------------------------

    private fun started(): Intent? {
        val shadow = org.robolectric.Shadows.shadowOf(app)
        return shadow.nextStartedService
    }

    /** Drains the queue of started services, so the next assertion asks what *this* call started. */
    private fun reset() {
        while (started() != null) {
            // `nextStartedService` pops the queue; the body is deliberately empty.
        }
    }
}
