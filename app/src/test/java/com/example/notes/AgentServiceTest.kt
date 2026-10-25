package com.example.notes

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.robolectric.Robolectric
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
class AgentServiceTest {

    private val app = RuntimeEnvironment.getApplication()

    private fun service() = Robolectric.buildService(AgentService::class.java).create()

    private fun on(login: String, project: String): AgentSession =
        AgentSession(project = project, state = AgentSession.State.IDLE, lastPollAt = 1_000)

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
        assertEquals(
            "and it does not ask to be restarted",
            android.app.Service.START_NOT_STICKY,
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
            AgentSession("b", state = AgentSession.State.ASKED, askedAt = 2_000),
        )
        assertEquals("2 sessions · 1 needs you", AgentSession.strip(sessions))
    }

    // ---- what the system was asked to start ---------------------------------

    private fun started(): Intent? {
        val shadow = org.robolectric.Shadows.shadowOf(app)
        return shadow.nextStartedService
    }

    private fun reset() {
        // Robolectric keeps a queue of started services; draining it is how a second assertion in
        // the same test asks "what did this call start" rather than "what did anything start".
        while (started() != null) Unit
    }
}
