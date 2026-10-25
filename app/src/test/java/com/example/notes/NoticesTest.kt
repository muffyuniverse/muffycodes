package com.example.notes

import android.Manifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Whether a reminder can reach the tray — the fact the settings row reads.
 *
 * Worth a test of its own because the failure is silent: a notification that is posted without the
 * permission is simply not shown, so an agent in `ask` looks healthy and the person is never told.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NoticesTest {

    private val app: android.app.Application get() = RuntimeEnvironment.getApplication()

    @Test
    fun `a granted permission is what the row says`() {
        org.robolectric.Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertTrue("the app may post", Notices.granted(app))
        assertEquals("notifications on", Notices.said(app))
    }

    @Test
    fun `a refused permission is what the row says instead`() {
        org.robolectric.Shadows.shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertFalse("the app may not", Notices.granted(app))
        assertEquals("notifications off", Notices.said(app))
    }

    @Test
    fun `there is a permission to ask for on this phone`() {
        // The launcher needs a name; null means "this phone does not have this permission", which is
        // a phone that predates it — and then the row must not offer a button at all.
        assertNotNull("the phone has POST_NOTIFICATIONS", Notices.permission())
    }
}
