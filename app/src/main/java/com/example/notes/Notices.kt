package com.example.notes

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Whether this app may put a notification in the tray — and the fact that it must ask.
 *
 * **A foreground service without a visible notification is a service the system will not keep**, and
 * the whole agent design rests on one: a question the agent asked, a session that stopped on its
 * own, the standing line that says how many are on. `POST_NOTIFICATIONS` is declared in the
 * manifest, but from Android 13 that is only half of it: the permission is denied until the person
 * grants it, and the app never asked. A reminder that is posted and silently dropped is worse than
 * no reminder, because the session looks healthy.
 *
 * Asked from settings, like the battery exemption, and for the same reason: an app that opens with a
 * permissions dialog teaches people to dismiss permissions dialogs.
 */
object Notices {

    /** The permission, or null on a phone that predates it (which is always granted). */
    private val PERMISSION: String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.POST_NOTIFICATIONS
    } else {
        null
    }

    /**
     * True when a notification posted now would be seen.
     *
     * A phone that cannot answer the question is treated as granted: the alternative is a settings
     * row that asks for something the app already has.
     */
    fun granted(context: Context): Boolean = try {
        val permission = PERMISSION ?: return true
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    } catch (t: Throwable) {
        true
    }

    /**
     * The permission to ask for, or null when this phone does not have one.
     *
     * Public because the launcher lives in the activity: the decision "is there anything to ask"
     * belongs beside the permission, not in the screen that shows the button.
     */
    fun permission(): String? = PERMISSION

    /** What to say once the person is back, in the app's own words. */
    fun said(context: Context): String =
        if (granted(context)) "notifications on" else "notifications off"
}
